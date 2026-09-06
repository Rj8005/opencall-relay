package com.opencall.relay.offline

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PART D-REMEDIATION: OCP's own file/text envelope for the optical
 * channel — filename, media type, optional gzip, and a SHA-256 of the
 * original bytes, verified before anything is offered to the user.
 * Independent design (own magic bytes, own field order, big-endian like
 * every other wire format in this codebase) — see [OpticalFountain]'s
 * class doc for why this replaced a version ported from an AGPL-licensed
 * project.
 *
 * Layout, big-endian, HEADER_LEN = 49 bytes:
 *   0-3   magic "OCPF" (0x4F 0x43 0x50 0x46)
 *   4     compression byte (1=gzip, 0=none)
 *   5-6   u16  nameLength
 *   7-8   u16  typeLength
 *   9-12  u32  original fileLength
 *  13-16  u32  transmittedLength
 *  17-48  32 bytes — full SHA-256 of the ORIGINAL (uncompressed) bytes
 *  49+    filename bytes, then type bytes, then payload
 */
object OpticalFileContainer {

    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_FILE_LABEL = "${MAX_FILE_BYTES / 1024 / 1024} MB"

    private const val HEADER_LEN = 49
    private val MAGIC = byteArrayOf(0x4F, 0x43, 0x50, 0x46) // "OCPF"

    class OpticalContainerException(message: String) : Exception(message)

    enum class CompressionMode { NONE, GZIP }

    data class PackedFile(val container: ByteArray, val compression: CompressionMode, val originalSize: Int, val transmittedSize: Int)

    data class UnpackedFile(
        val name: String,
        val type: String,
        val bytes: ByteArray,
        val sha256: ByteArray,
        val compression: CompressionMode
    )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Hard output ceiling — a gzip trailer's declared size is attacker-
     *  controlled (it arrived over the optical channel like everything
     *  else), so it's a hint, never a bound. */
    private fun gunzip(bytes: ByteArray, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { gzIn ->
            val buf = ByteArray(8192)
            while (true) {
                val n = gzIn.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw OpticalContainerException("inflateOverflow")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.code !in 0..0x1F && it.code != 0x7F }.trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "transfer.bin" else cleaned
    }

    private val PRECOMPRESSED_TYPES = setOf(
        "application/gzip", "application/java-archive", "application/vnd.rar",
        "application/x-7z-compressed", "application/x-bzip", "application/x-bzip2",
        "application/x-gzip", "application/x-lzma", "application/x-rar-compressed",
        "application/x-xz", "application/x-zip-compressed", "application/zip", "application/zstd"
    )
    private val COMPRESSIBLE_IMAGES = Regex("^image/(bmp|x-ms-bmp|svg\\+xml|tiff|x-icon)$")

    fun isPrecompressedType(type: String): Boolean {
        val media = type.substringBefore(';').trim().lowercase()
        return when {
            media.startsWith("video/") -> true
            media.startsWith("audio/") -> true
            media.startsWith("image/") -> !COMPRESSIBLE_IMAGES.matches(media)
            media.endsWith("+zip") -> true
            else -> media in PRECOMPRESSED_TYPES
        }
    }

    fun packFile(name: String, type: String, bytes: ByteArray): PackedFile {
        if (bytes.isEmpty()) throw OpticalContainerException("fileEmpty")
        if (bytes.size > MAX_FILE_BYTES) throw OpticalContainerException("fileOverLimit: limit is $MAX_FILE_LABEL")

        val nameBytes = safeFileName(name).toByteArray(StandardCharsets.UTF_8)
        val typeBytes = (type.ifBlank { "application/octet-stream" }).toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size > 0xFFFF || typeBytes.size > 0xFFFF) throw OpticalContainerException("fileNameTooLong")

        val tryGzip = bytes.size >= 768 && !isPrecompressedType(type)
        val sha = sha256(bytes)
        val compressed = if (tryGzip) gzip(bytes) else null
        val useGzip = compressed != null && compressed.size + 64 < bytes.size
        val transmitted = if (useGzip) compressed!! else bytes
        val compression = if (useGzip) CompressionMode.GZIP else CompressionMode.NONE

        val out = ByteBuffer.allocate(HEADER_LEN + nameBytes.size + typeBytes.size + transmitted.size)
        out.put(MAGIC)
        out.put((if (useGzip) 1 else 0).toByte())
        out.putShort(nameBytes.size.toShort())
        out.putShort(typeBytes.size.toShort())
        out.putInt(bytes.size)
        out.putInt(transmitted.size)
        out.put(sha)
        out.put(nameBytes)
        out.put(typeBytes)
        out.put(transmitted)
        return PackedFile(out.array(), compression, bytes.size, transmitted.size)
    }

    fun unpackFile(container: ByteArray): UnpackedFile {
        if (container.size < HEADER_LEN) throw OpticalContainerException("containerTruncated")
        for (i in MAGIC.indices) {
            if (container[i] != MAGIC[i]) throw OpticalContainerException("containerBadMagic")
        }
        val buf = ByteBuffer.wrap(container)
        val compressionByte = buf.get(4).toInt() and 0xFF
        if (compressionByte > 1) throw OpticalContainerException("containerBadCompression")
        val compression = if (compressionByte == 1) CompressionMode.GZIP else CompressionMode.NONE
        val nameLength = buf.getShort(5).toInt() and 0xFFFF
        val typeLength = buf.getShort(7).toInt() and 0xFFFF
        val fileLength = buf.getInt(9)
        val transmittedLength = buf.getInt(13)
        val dataOffset = HEADER_LEN + nameLength + typeLength
        if (fileLength <= 0 || fileLength > MAX_FILE_BYTES ||
            transmittedLength <= 0 || transmittedLength > MAX_FILE_BYTES ||
            dataOffset + transmittedLength != container.size
        ) {
            throw OpticalContainerException("containerLengthMismatch")
        }

        val transmitted = container.copyOfRange(dataOffset, container.size)
        val bytes = if (compression == CompressionMode.GZIP) gunzip(transmitted, fileLength) else transmitted
        if (bytes.size != fileLength) throw OpticalContainerException("decompressedLengthMismatch")

        return UnpackedFile(
            name = safeFileName(String(container, HEADER_LEN, nameLength, StandardCharsets.UTF_8)),
            type = String(container, HEADER_LEN + nameLength, typeLength, StandardCharsets.UTF_8).ifBlank { "application/octet-stream" },
            sha256 = container.copyOfRange(17, 49),
            bytes = bytes,
            compression = compression
        )
    }

    fun verifyFile(file: UnpackedFile): Boolean = sha256(file.bytes).contentEquals(file.sha256)
}
