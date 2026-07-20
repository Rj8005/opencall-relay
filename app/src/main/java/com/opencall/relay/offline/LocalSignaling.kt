package com.opencall.relay.offline

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Newline-delimited JSON control channel over a plain TCP socket on the
 * Wi-Fi-Direct subnet. The group owner listens on PORT, the client (the
 * other peer) dials in.
 *
 * FIX: the offline call path (OfflineCallActivity) no longer carries sdp/ice —
 * OfflineMediaTransport is its entire media path now. This class gained its
 * own small control protocol on top of the existing generic JSON send/receive
 * (kept as public API because OfflineCallManager — unused by the offline path
 * now, but not deleted per the "online path may share code" note — still
 * calls send()/onMessage directly):
 *   {"t":"hangup"} — sent once when the local user ends the call, so the far
 *                    side can tear down cleanly instead of relying solely on
 *                    link-death detection.
 *   {"t":"ping"}   — sent every PING_INTERVAL_MS while connected; any
 *                    received message (ping or otherwise) resets the
 *                    keepalive watchdog. No message at all for
 *                    KEEPALIVE_TIMEOUT_MS means the peer is gone.
 * Both are intercepted internally; anything else (including old sdp_offer/
 * sdp_answer/ice messages, which use a "type" key rather than "t") is still
 * forwarded to onMessage untouched, for whoever still consumes that.
 */
class LocalSignaling(
    private val isGroupOwner: Boolean,
    private val groupOwnerAddress: InetAddress?
) {
    companion object {
        private const val TAG = "LocalSignaling"
        private const val PORT = 8888
        private const val CONNECT_RETRIES = 10
        private const val CONNECT_RETRY_DELAY_MS = 500L
        private const val PING_INTERVAL_MS = 5000L
        private const val KEEPALIVE_TIMEOUT_MS = 15000L
        private const val KEEPALIVE_CHECK_INTERVAL_MS = 1000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: OutputStream? = null

    private var acceptThread: Thread? = null
    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    private var keepaliveThread: Thread? = null

    @Volatile private var lastReceivedAtMs = 0L
    // Latches once so a hangup message racing the keepalive watchdog (or vice versa)
    // can't fire onPeerGone twice for the same call.
    private val peerGoneReported = AtomicBoolean(false)

    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Fired (main thread) exactly once when the peer hangs up cleanly ({"t":"hangup"})
     *  or the keepalive watchdog sees no message at all for KEEPALIVE_TIMEOUT_MS —
     *  either way the caller should end the call the same way a link failure would.
     *  The string is a short human-readable reason, for logging. */
    var onPeerGone: ((String) -> Unit)? = null
    /** Kept for OfflineCallManager (not used by the offline call path anymore) —
     *  any message that isn't this class's own "hangup"/"ping" control protocol is
     *  forwarded here untouched. */
    var onMessage: ((JSONObject) -> Unit)? = null

    fun start() {
        running.set(true)
        if (isGroupOwner) startServer() else startClient()
    }

    private fun startServer() {
        acceptThread = Thread {
            Log.d("OFFTRACE", "signaling: opening ServerSocket $PORT")
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                Log.d(TAG, "Listening on port $PORT")
                Log.d("OFFTRACE", "signaling: server bound, awaiting client")
                val client = server.accept()
                if (running.get()) {
                    socket = client
                    onSocketReady(client)
                } else {
                    client.close()
                }
            } catch (e: BindException) {
                Log.e("OFFTRACE", "FATAL port 8888 in use: ${e.message}")
                if (running.get()) postError("port 8888 in use: ${e.message}")
            } catch (e: Exception) {
                if (running.get()) postError("server accept failed: ${e.message}")
            }
        }.also { it.start() }
    }

    private fun startClient() {
        val address = groupOwnerAddress
        if (address == null) {
            postError("no group owner address")
            return
        }
        connectThread = Thread {
            var attempt = 0
            var connected = false
            while (running.get() && attempt < CONNECT_RETRIES && !connected) {
                try {
                    Log.d("OFFTRACE", "signaling: dialing $address:$PORT")
                    val s = Socket(address, PORT)
                    socket = s
                    connected = true
                    Log.d("OFFTRACE", "signaling: connected")
                    onSocketReady(s)
                } catch (e: ConnectException) {
                    Log.e("OFFTRACE", "signaling client failed: ${e.message}")
                    attempt++
                    Log.w(TAG, "connect attempt $attempt failed: ${e.message}")
                    try { Thread.sleep(CONNECT_RETRY_DELAY_MS) } catch (ie: InterruptedException) {}
                } catch (e: Exception) {
                    attempt++
                    Log.w(TAG, "connect attempt $attempt failed: ${e.message}")
                    try { Thread.sleep(CONNECT_RETRY_DELAY_MS) } catch (ie: InterruptedException) {}
                }
            }
            if (!connected && running.get()) postError("could not connect to group owner")
        }.also { it.start() }
    }

    private fun onSocketReady(s: Socket) {
        writer = s.getOutputStream()
        lastReceivedAtMs = System.currentTimeMillis()
        mainHandler.post { onConnected?.invoke() }
        startKeepalive()
        readThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    lastReceivedAtMs = System.currentTimeMillis()
                    try {
                        val json = JSONObject(line)
                        val t = json.optString("t")
                        Log.d("OFFTRACE", "sig<- ${if (t.isNotEmpty()) t else json.optString("type")}")
                        when (t) {
                            "hangup" -> reportPeerGone("peer hung up")
                            "ping" -> { /* keepalive only — lastReceivedAtMs already updated above */ }
                            // Not this class's own control protocol (t is unset for old
                            // sdp/ice-style {"type": ...} messages, or genuinely unknown) —
                            // forward untouched rather than assume it's garbage.
                            else -> mainHandler.post { onMessage?.invoke(json) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "bad json line: ${e.message}")
                    }
                }
                if (running.get()) postError("connection closed by peer")
            } catch (e: Exception) {
                if (running.get()) postError("read failed: ${e.message}")
            }
        }.also { it.start() }
    }

    /** FIX: pings the peer every PING_INTERVAL_MS and independently watches
     *  lastReceivedAtMs (updated by any incoming line, ping or otherwise) — if
     *  nothing at all arrives for KEEPALIVE_TIMEOUT_MS, the peer is presumed gone. */
    private fun startKeepalive() {
        var lastPingSentMs = System.currentTimeMillis()
        keepaliveThread = Thread {
            while (running.get()) {
                try { Thread.sleep(KEEPALIVE_CHECK_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                val now = System.currentTimeMillis()
                if (now - lastReceivedAtMs >= KEEPALIVE_TIMEOUT_MS) {
                    Log.e("OFFTRACE", "signaling: keepalive timeout — no message in ${KEEPALIVE_TIMEOUT_MS}ms")
                    reportPeerGone("keepalive timeout")
                    return@Thread
                }
                if (now - lastPingSentMs >= PING_INTERVAL_MS) {
                    send(JSONObject().put("t", "ping"))
                    lastPingSentMs = now
                }
            }
        }.also { it.start() }
    }

    private fun reportPeerGone(reason: String) {
        if (!peerGoneReported.compareAndSet(false, true)) return
        mainHandler.post { onPeerGone?.invoke(reason) }
    }

    /** Sends {"t":"hangup"} so the far side can end the call cleanly instead of
     *  waiting on link-death detection. Safe to call from any thread. */
    fun sendHangup() {
        send(JSONObject().put("t", "hangup"))
    }

    /** Generic send, kept public for OfflineCallManager (see class doc). Safe to call
     *  from any thread. */
    fun send(json: JSONObject) {
        Log.d("OFFTRACE", "sig-> ${json.optString("t").ifEmpty { json.optString("type") }}")
        val out = writer ?: return
        Thread {
            try {
                synchronized(out) {
                    out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: Exception) {
                postError("send failed: ${e.message}")
            }
        }.start()
    }

    private fun postError(msg: String) {
        Log.e(TAG, msg)
        mainHandler.post { onError?.invoke(msg) }
    }

    fun stop() {
        running.set(false)
        keepaliveThread?.interrupt()
        keepaliveThread = null
        try { socket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        socket = null
        serverSocket = null
        writer = null
    }
}
