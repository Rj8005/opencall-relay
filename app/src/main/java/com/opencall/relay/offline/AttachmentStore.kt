package com.opencall.relay.offline

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Step 3 (diagnostic follow-up): on-disk body storage for the generic
 * metadata-first attachment protocol (TYPE_ATTACHMENT_META/REQUEST/DATA)
 * — filesDir/attachments/<msgId>/body, one file per attachment. Written
 * either when THIS device is the original sender (so it can answer a
 * future TYPE_ATTACHMENT_REQUEST even after a process restart — unlike
 * the old in-memory-only voiceNotesInternal list it replaces, which lost
 * everything on process death) or when a TYPE_ATTACHMENT_DATA response
 * arrives (the fetched copy, for a receiver who tapped Download).
 *
 * Process-wide singleton, same .get(context) DI pattern as MeshLedger/
 * MeshBarometer/MeshCarrier/IncidentLog.
 */
class AttachmentStore private constructor(context: Context) {

    companion object {
        private const val ATTACHMENTS_DIR_NAME = "attachments"

        @Volatile private var instance: AttachmentStore? = null

        fun get(context: Context): AttachmentStore =
            instance ?: synchronized(this) {
                instance ?: AttachmentStore(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, ATTACHMENTS_DIR_NAME).apply { mkdirs() }

    private fun bodyFile(msgId: String): File {
        val dir = File(rootDir, msgId).apply { mkdirs() }
        return File(dir, "body")
    }

    /** Atomic (temp file + fsync + rename, same pattern as MeshLedger/
     *  MeshCarrier) write of [bytes] for [msgId]. Never throws — a failed
     *  write is logged and reported false; the caller decides what that
     *  means for its own in-memory state, same "must not risk the caller"
     *  posture as every other best-effort disk write in this app. */
    fun write(msgId: String, bytes: ByteArray): Boolean {
        val target = bodyFile(msgId)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("atomic rename failed for $msgId")
            true
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ATTACH: write failed msgId=$msgId: ${e.javaClass.simpleName}:${e.message}")
            false
        }
    }

    fun read(msgId: String): ByteArray? {
        val f = bodyFile(msgId)
        if (!f.exists()) return null
        return try {
            f.readBytes()
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ATTACH: read failed msgId=$msgId: ${e.javaClass.simpleName}:${e.message}")
            null
        }
    }

    fun has(msgId: String): Boolean = bodyFile(msgId).exists()

    /** Discards a stored body (sent or fetched) — not wired to anything
     *  yet (no retention/eviction policy exists for this store today); a
     *  deliberate hook for whoever adds one later, not dead code removed
     *  on sight, since on-disk attachment bodies accumulating forever is a
     *  real, foreseeable concern this app's other stores (MeshLedger's
     *  ring buffer, MeshCarrier's expiryMins) already take seriously. */
    fun delete(msgId: String) {
        File(rootDir, msgId).deleteRecursively()
    }
}
