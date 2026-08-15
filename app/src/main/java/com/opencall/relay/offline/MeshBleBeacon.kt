package com.opencall.relay.offline

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 6 TRACK C: BLE presence + standard beacon broadcast. BLE reaches
 * further than Wi-Fi Direct at a fraction of the power, and standard beacon
 * formats (iBeacon/Eddystone) let a non-OCP scanner see a distress signal.
 *
 * PLATFORM REALITY — read before relying on this: many budget phones cannot
 * advertise BLE at all ([BluetoothAdapter.getBluetoothLeAdvertiser] is null,
 * or [BluetoothAdapter.isMultipleAdvertisementSupported] is false). This class
 * degrades exactly the way the rest of this app degrades on missing hardware
 * (no GPS, no barometer): [start] logs a distinct reason and skips
 * ADVERTISING only — SCANNING (seeing everyone ELSE's beacons) still works on
 * any device with a Bluetooth radio at all, so a phone that can't advertise is
 * invisible to others over BLE but can still see everyone else.
 *
 * UNLIKE Wi-Fi Direct (which exposes no per-link RSSI on any Android API level
 * — see MeshLedger's class doc on why the PHASE 5BC proximity idea was
 * dropped), BLE scan results DO carry per-advertisement RSSI, making a
 * CLOSER/FARTHER proximity trend viable on this radio. [smoothedRssiTrend]
 * smooths over 5 samples and is presented ONLY as an estimate — it must never
 * be shown as if it were a GPS fix (see MeshLedger.BlePresence's doc).
 *
 * WIRE FORMAT (OCP-native, manufacturer-specific data, company id 0xFFFF —
 * reserved for internal/testing use per the Bluetooth spec; this is a closed
 * app-to-app protocol, not a real SIG-registered company):
 *   nodeId    8B
 *   flags     1B  bit0 SOS active, bit1 hasFix, bit2 batteryLow
 *   battery   1B  0-100, 0xFF = unknown
 * See [OcpBeaconPayload] for the pure, unit-testable codec.
 *
 * While any SOS is active, advertising ROTATES every [ROTATE_INTERVAL_MS]
 * between this OCP-native frame, an iBeacon frame, and an Eddystone-UID frame
 * — a single legacy BLE advertisement can only carry one payload at a time on
 * the minSdk-26-compatible API used here, so simultaneous multi-format
 * broadcast isn't available; rotation is the practical alternative so a
 * third-party scanner catches SOMETHING within a few rotations.
 */
class MeshBleBeacon private constructor(context: Context) {

    /** Pure, unit-testable OCP-native manufacturer-data codec — no Android
     *  dependency, same "testable off-device" spirit as MeshLocation/MeshCarrier. */
    object OcpBeaconPayload {
        const val COMPANY_ID = 0xFFFF
        private const val FLAG_SOS_ACTIVE = 0x01
        private const val FLAG_HAS_FIX = 0x02
        private const val FLAG_BATTERY_LOW = 0x04
        private const val BATTERY_UNKNOWN_RAW = 0xFF

        data class Decoded(
            val nodeId: Long,
            val sosActive: Boolean,
            val hasFix: Boolean,
            val batteryLow: Boolean,
            val batteryPercent: Int?
        )

        fun encode(nodeId: Long, sosActive: Boolean, hasFix: Boolean, batteryLow: Boolean, batteryPercent: Int?): ByteArray {
            val buf = ByteBuffer.allocate(10)
            buf.putLong(nodeId)
            var flags = 0
            if (sosActive) flags = flags or FLAG_SOS_ACTIVE
            if (hasFix) flags = flags or FLAG_HAS_FIX
            if (batteryLow) flags = flags or FLAG_BATTERY_LOW
            buf.put(flags.toByte())
            buf.put(((batteryPercent ?: BATTERY_UNKNOWN_RAW) and 0xFF).toByte())
            return buf.array()
        }

        fun decode(data: ByteArray): Decoded? {
            if (data.size < 10) return null
            return try {
                val buf = ByteBuffer.wrap(data)
                val nodeId = buf.long
                val flags = buf.get().toInt() and 0xFF
                val battRaw = buf.get().toInt() and 0xFF
                Decoded(
                    nodeId = nodeId,
                    sosActive = (flags and FLAG_SOS_ACTIVE) != 0,
                    hasFix = (flags and FLAG_HAS_FIX) != 0,
                    batteryLow = (flags and FLAG_BATTERY_LOW) != 0,
                    batteryPercent = if (battRaw == BATTERY_UNKNOWN_RAW) null else battRaw
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    enum class Format { OCP_NATIVE, IBEACON, EDDYSTONE }

    companion object {
        private const val ROTATE_INTERVAL_MS = 5_000L
        private const val RSSI_SMOOTH_WINDOW = 5
        private const val RSSI_TREND_THRESHOLD_DBM = 3
        private const val APPLE_COMPANY_ID = 0x004C // required by the iBeacon spec, even from non-Apple devices
        private const val EDDYSTONE_SERVICE_UUID = 0xFEAA
        // Fixed "namespace" bytes so every OpenCall Eddystone frame is
        // recognizably from this app family — first 10 bytes of a constant
        // string hash, not derived from any per-device secret.
        private val EDDYSTONE_NAMESPACE = byteArrayOf(
            0x4F, 0x50, 0x45, 0x4E, 0x43, 0x41, 0x4C, 0x4C, 0x53, 0x4F
        ) // "OPENCALLSO" (10 bytes)
        private val IBEACON_UUID_PREFIX = byteArrayOf(
            0x4F, 0x43, 0x50, 0x2D, 0x53, 0x4F, 0x53, 0x2D // "OCP-SOS-"
        )

        @Volatile private var instance: MeshBleBeacon? = null

        fun get(context: Context): MeshBleBeacon =
            instance ?: synchronized(this) {
                instance ?: MeshBleBeacon(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var rotateRunnable: Runnable? = null
    private var rotateIndex = 0

    @Volatile private var localNodeId: Long = 0L
    @Volatile private var sosActive = false
    @Volatile private var running = false
    @Volatile private var canAdvertise = false

    private val ledger get() = MeshLedger.get(appContext)
    private val rssiHistory = ConcurrentHashMap<Long, ArrayDeque<Int>>()

    /** Fired on the main thread whenever a BLE-only sighting (not currently
     *  reachable over Wi-Fi Direct) updates — the party-status screen's cue to
     *  show/refresh a "NEARBY, NOT CONNECTED" row. */
    var onPresenceUpdated: ((Long) -> Unit)? = null
    /** Supplies whether [nodeId] is CURRENTLY a connected Wi-Fi Direct mesh
     *  member — a BLE sighting of an already-connected peer is not a new
     *  "presence" state, just noise (they're already fully in the mesh). */
    var isConnectedOverWifiDirect: ((Long) -> Boolean)? = null

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    private fun canAdvertisePermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

    /** Session-scoped: call when a group session becomes active. Degrades
     *  cleanly (see class doc) rather than crashing on missing hardware/
     *  permissions. */
    fun start(localNodeId: Long) {
        if (running) return
        this.localNodeId = localNodeId
        val bleAdapter = adapter
        if (bleAdapter == null || !bleAdapter.isEnabled) {
            Log.w("OFFTRACE", "BLE: unavailable — reason=no_adapter_or_disabled")
            return
        }
        running = true
        startScanning(bleAdapter)
        startAdvertisingIfSupported(bleAdapter)
    }

    fun stop() {
        if (!running) return
        running = false
        stopScanning()
        stopAdvertising()
        rotateRunnable?.let { mainHandler.removeCallbacks(it) }
        rotateRunnable = null
        rssiHistory.clear()
    }

    /** Called whenever the local active-SOS-senders set changes (empty vs
     *  non-empty is all this cares about) — switches scan mode to BALANCED and
     *  starts format rotation while any SOS is active, back to LOW_POWER/
     *  OCP-native-only otherwise. */
    fun setSosActive(active: Boolean) {
        if (sosActive == active) return
        sosActive = active
        if (!running) return
        val bleAdapter = adapter ?: return
        restartScanning(bleAdapter)
        if (active) {
            startRotation()
        } else {
            rotateRunnable?.let { mainHandler.removeCallbacks(it) }
            rotateRunnable = null
            rotateIndex = 0
            if (canAdvertise) advertiseAs(Format.OCP_NATIVE)
        }
    }

    // ── Advertising ──────────────────────────────────────────────────────────

    private fun startAdvertisingIfSupported(bleAdapter: BluetoothAdapter) {
        if (!canAdvertisePermission()) {
            Log.w("OFFTRACE", "BLE: adv unavailable — reason=missing_permission")
            return
        }
        if (!bleAdapter.isMultipleAdvertisementSupported) {
            Log.w("OFFTRACE", "BLE: adv unavailable — reason=peripheral_mode_unsupported (scanning still works)")
            return
        }
        val adv = bleAdapter.bluetoothLeAdvertiser
        if (adv == null) {
            Log.w("OFFTRACE", "BLE: adv unavailable — reason=advertiser_null (scanning still works)")
            return
        }
        advertiser = adv
        canAdvertise = true
        advertiseAs(Format.OCP_NATIVE)
    }

    private fun advertiseAs(format: Format) {
        val adv = advertiser ?: return
        if (!canAdvertisePermission()) return
        try {
            advertiseCallback?.let { try { adv.stopAdvertising(it) } catch (_: Exception) {} }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build()
            val data = buildAdvertiseData(format)
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d("OFFTRACE", "BLE: adv start format=${format.name.lowercase()} sos=$sosActive")
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.w("OFFTRACE", "BLE: adv start failed format=${format.name.lowercase()} error=$errorCode")
                }
            }
            advertiseCallback = callback
            adv.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.w("OFFTRACE", "BLE: adv failed — reason=security_exception:${e.message}")
        } catch (e: Exception) {
            Log.w("OFFTRACE", "BLE: adv failed — reason=${e.javaClass.simpleName}:${e.message}")
        }
    }

    private fun buildAdvertiseData(format: Format): AdvertiseData {
        val builder = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        when (format) {
            Format.OCP_NATIVE -> {
                val payload = OcpBeaconPayload.encode(
                    localNodeId, sosActive, hasFix = false, batteryLow = false, batteryPercent = null
                )
                builder.addManufacturerData(OcpBeaconPayload.COMPANY_ID, payload)
            }
            Format.IBEACON -> {
                val uuidBytes = ByteArray(16)
                System.arraycopy(IBEACON_UUID_PREFIX, 0, uuidBytes, 0, 8)
                val idBuf = ByteBuffer.wrap(uuidBytes, 8, 8)
                idBuf.putLong(localNodeId)
                val payload = ByteBuffer.allocate(23)
                payload.put(0x02) // iBeacon type
                payload.put(0x15) // length of remaining iBeacon fields (21)
                payload.put(uuidBytes)
                payload.putShort(1) // major
                payload.putShort((localNodeId and 0xFFFF).toShort()) // minor
                payload.put((-59).toByte()) // calibrated TX power at 1m, typical placeholder
                builder.addManufacturerData(APPLE_COMPANY_ID, payload.array())
            }
            Format.EDDYSTONE -> {
                builder.addServiceUuid(ParcelUuid(UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")))
                val instanceId = ByteArray(6)
                val idBuf = ByteBuffer.wrap(instanceId)
                idBuf.putInt((localNodeId ushr 16).toInt())
                idBuf.putShort((localNodeId and 0xFFFF).toShort())
                val serviceData = ByteBuffer.allocate(18)
                serviceData.put(0x00) // Eddystone-UID frame type
                serviceData.put((-16).toByte()) // calibrated TX power at 0m, typical placeholder
                serviceData.put(EDDYSTONE_NAMESPACE)
                serviceData.put(instanceId)
                builder.addServiceData(
                    ParcelUuid(UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")),
                    serviceData.array()
                )
            }
        }
        return builder.build()
    }

    private fun startRotation() {
        rotateRunnable?.let { mainHandler.removeCallbacks(it) }
        val formats = Format.values()
        val runnable = object : Runnable {
            override fun run() {
                if (!running || !sosActive || !canAdvertise) return
                rotateIndex = (rotateIndex + 1) % formats.size
                advertiseAs(formats[rotateIndex])
                mainHandler.postDelayed(this, ROTATE_INTERVAL_MS)
            }
        }
        rotateRunnable = runnable
        mainHandler.postDelayed(runnable, ROTATE_INTERVAL_MS)
    }

    private fun stopAdvertising() {
        val adv = advertiser ?: return
        val cb = advertiseCallback ?: return
        try { adv.stopAdvertising(cb) } catch (_: Exception) {}
        advertiseCallback = null
        canAdvertise = false
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    private fun startScanning(bleAdapter: BluetoothAdapter) {
        if (!canScan()) {
            Log.w("OFFTRACE", "BLE: scan unavailable — reason=missing_permission")
            return
        }
        val scan = bleAdapter.bluetoothLeScanner
        if (scan == null) {
            Log.w("OFFTRACE", "BLE: scan unavailable — reason=scanner_null")
            return
        }
        scanner = scan
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(if (sosActive) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handleScanResult(result)
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.w("OFFTRACE", "BLE: scan failed error=$errorCode")
                }
            }
            scanCallback = callback
            scan.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            Log.w("OFFTRACE", "BLE: scan failed — reason=security_exception:${e.message}")
        } catch (e: Exception) {
            Log.w("OFFTRACE", "BLE: scan failed — reason=${e.javaClass.simpleName}:${e.message}")
        }
    }

    private fun stopScanning() {
        val scan = scanner ?: return
        val cb = scanCallback ?: return
        try { scan.stopScan(cb) } catch (_: Exception) {}
        scanCallback = null
    }

    private fun restartScanning(bleAdapter: BluetoothAdapter) {
        stopScanning()
        startScanning(bleAdapter)
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val manufacturerData = record.getManufacturerSpecificData(OcpBeaconPayload.COMPANY_ID) ?: return
        val decoded = OcpBeaconPayload.decode(manufacturerData) ?: return
        if (decoded.nodeId == localNodeId) return // hearing our own advertisement
        val rssi = result.rssi
        val (smoothed, trend) = updateRssiHistory(decoded.nodeId, rssi)
        val alreadyConnected = isConnectedOverWifiDirect?.invoke(decoded.nodeId) == true
        Log.d(
            "OFFTRACE",
            "BLE: seen ${MeshFrame.hex(decoded.nodeId)} rssi=${rssi}dBm trend=$trend wifi=$alreadyConnected"
        )
        if (alreadyConnected) {
            // Already fully in the mesh over Wi-Fi Direct — clear any stale
            // "nearby, not connected" marker rather than double-reporting them.
            ledger.clearBlePresence(decoded.nodeId)
            return
        }
        ledger.recordBlePresence(decoded.nodeId, smoothed, trend)
        mainHandler.post { onPresenceUpdated?.invoke(decoded.nodeId) }
    }

    /** 5-sample moving average, trend from the older half of the window vs. the
     *  newer half — same "never report a trend smaller than the noise floor"
     *  spirit as MeshLedger.trendTo's GPS-based version, just with a real
     *  signal (BLE RSSI) behind it this time. */
    private fun updateRssiHistory(nodeId: Long, rssi: Int): Pair<Int, MeshLedger.Trend> {
        val history = rssiHistory.getOrPut(nodeId) { ArrayDeque() }
        synchronized(history) {
            if (history.size >= RSSI_SMOOTH_WINDOW) history.removeFirst()
            history.addLast(rssi)
            val smoothed = history.average().toInt()
            if (history.size < RSSI_SMOOTH_WINDOW) return smoothed to MeshLedger.Trend.UNKNOWN
            val half = RSSI_SMOOTH_WINDOW / 2
            val olderAvg = history.toList().take(half).average()
            val newerAvg = history.toList().takeLast(half).average()
            val delta = newerAvg - olderAvg
            val trend = when {
                delta > RSSI_TREND_THRESHOLD_DBM -> MeshLedger.Trend.CLOSER
                delta < -RSSI_TREND_THRESHOLD_DBM -> MeshLedger.Trend.FARTHER
                else -> MeshLedger.Trend.STEADY
            }
            return smoothed to trend
        }
    }
}
