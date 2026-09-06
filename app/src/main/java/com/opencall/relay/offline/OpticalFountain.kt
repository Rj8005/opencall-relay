package com.opencall.relay.offline

import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * PART D-REMEDIATION: OCP's own, independent fountain-coding wire format
 * for the animated-QR optical channel. An earlier version of this file
 * ported github.com/bashalarmistalt/decimen-optical-transfer's actual
 * TypeScript source (shared/fountain.ts) - that project's LICENSE file is
 * AGPL-3.0-or-later (its README's "MIT" claim is stale; confirmed against
 * both the LICENSE file and the live site's own footer), so translating
 * its source carried real copyleft exposure for this app. This file is a
 * from-scratch replacement: no shared code, no shared field layout, no
 * shared PRNG/degree-distribution parameters - only the general, decades-
 * old, published Luby Transform / robust-soliton technique (Luby, 2002 -
 * an algorithm, not anyone's copyrighted expression of it) is reused, the
 * same way this codebase already reuses SHA-256 or AES-GCM without that
 * implying any license obligation.
 *
 * Frame layout, big-endian (matching every other wire format in this
 * codebase - MeshFrame included - unlike the AGPL project's little-endian
 * choice, which this format has no reason to mirror):
 *   0   4B  payloadId    - random per transfer
 *   4   4B  totalSize    - total payload bytes
 *   8   4B  symbolIndex  - drives the degree/neighbor PRNG (see neighborsFor)
 *  12   4B  crc32        - of the symbol bytes only; a corrupted frame is
 *                          dropped HERE, before it ever reaches the
 *                          decoder's linear-algebra state
 *  16   symbolSize bytes of symbol payload
 */
object OpticalFountain {

    const val HEADER_BYTES = 4 + 4 + 4 + 4

    /** Refuse outright rather than run an absurdly long transfer - matches
     *  this app's own 64MB ceiling for optical file transfer. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    class PayloadTooLargeException(val actualBytes: Int) :
        Exception("Optical payload is $actualBytes bytes, over the ${MAX_PAYLOAD_BYTES}B cap")

    data class FrameHeader(val payloadId: Int, val totalSize: Int, val symbolIndex: Int, val crc32: Int)
    class Frame(val header: FrameHeader, val symbol: ByteArray)

    fun symbolCount(totalSize: Int, symbolSize: Int): Int = (totalSize + symbolSize - 1) / symbolSize

    fun encodeFrame(payloadId: Int, payload: ByteArray, symbolSize: Int, symbolIndex: Int): Frame {
        if (payload.size > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(payload.size)
        val k = symbolCount(payload.size, symbolSize)
        val symbol = ByteArray(symbolSize)
        neighborsFor(payloadId, symbolIndex, k).forEach { xorSourceSymbolInto(symbol, payload, symbolSize, it) }
        return Frame(FrameHeader(payloadId, payload.size, symbolIndex, crc32Of(symbol)), symbol)
    }

    fun serializeFrame(frame: Frame): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES + frame.symbol.size)
        buf.putInt(frame.header.payloadId)
        buf.putInt(frame.header.totalSize)
        buf.putInt(frame.header.symbolIndex)
        buf.putInt(frame.header.crc32)
        buf.put(frame.symbol)
        return buf.array()
    }

    /** Never throws; returns null for anything too short, or whose CRC
     *  doesn't match its own symbol bytes - dropped before it can corrupt
     *  a reconstruction in progress. */
    fun deserializeFrame(bytes: ByteArray): Frame? {
        if (bytes.size <= HEADER_BYTES) return null
        return try {
            val buf = ByteBuffer.wrap(bytes)
            val payloadId = buf.int
            val totalSize = buf.int
            val symbolIndex = buf.int
            val crc = buf.int
            val symbol = bytes.copyOfRange(HEADER_BYTES, bytes.size)
            if (crc32Of(symbol) != crc) null else Frame(FrameHeader(payloadId, totalSize, symbolIndex, crc), symbol)
        } catch (e: Exception) {
            null
        }
    }

    /** Degree 1 (systematic) for symbolIndex < k; robust soliton for any
     *  repair index >= k. Deterministic given (payloadId, symbolIndex, k) -
     *  the receiver calls this SAME function to know which unknowns a
     *  received symbol constrains, so no neighbor list ever crosses the
     *  wire. */
    fun neighborsFor(payloadId: Int, symbolIndex: Int, k: Int): List<Int> {
        if (k <= 0) return emptyList()
        if (symbolIndex < k) return listOf(symbolIndex)
        val rng = Random(seedFor(payloadId, symbolIndex))
        val degree = robustSolitonDegree(rng, k).coerceAtMost(k)
        val neighbors = LinkedHashSet<Int>()
        while (neighbors.size < degree) neighbors.add(rng.nextInt(k))
        return neighbors.toList()
    }

    private fun seedFor(payloadId: Int, symbolIndex: Int): Long =
        (payloadId.toLong() * 2654435761L) xor (symbolIndex.toLong() * 40503L) xor 0x9E3779B9L

    /** Robust Soliton Distribution (Luby, 2002) - see class doc: this is
     *  the published algorithm, independently implemented; nothing here
     *  is copied from any project's source. */
    private fun robustSolitonDegree(rng: Random, k: Int): Int {
        val delta = 0.05
        val c = 0.1
        val s = (c * ln(k / delta) * sqrt(k.toDouble())).coerceIn(1.0, k.toDouble())
        val sInt = s.toInt().coerceIn(1, k)

        fun rho(d: Int): Double = if (d == 1) 1.0 / k else 1.0 / (d.toDouble() * (d - 1))
        fun tau(d: Int): Double = when {
            d < sInt -> s / (k.toDouble() * d)
            d == sInt -> s * ln(s / delta) / k
            else -> 0.0
        }
        var z = 0.0
        for (d in 1..k) z += rho(d) + tau(d)

        val u = rng.nextDouble().coerceIn(1e-9, 1.0)
        var cumulative = 0.0
        for (d in 1..k) {
            cumulative += (rho(d) + tau(d)) / z
            if (u <= cumulative) return d
        }
        return k
    }

    private fun xorSourceSymbolInto(dst: ByteArray, payload: ByteArray, symbolSize: Int, sourceIndex: Int) {
        val start = sourceIndex * symbolSize
        for (i in 0 until symbolSize) {
            val srcPos = start + i
            val b = if (srcPos < payload.size) payload[srcPos] else 0
            dst[i] = (dst[i].toInt() xor b.toInt()).toByte()
        }
    }

    private fun crc32Of(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }
}

/**
 * PART D-REMEDIATION: the receiving half - feed OpticalFountain.Frame
 * objects in ANY order via offer(); once isComplete(), reconstruct()
 * returns the original payload. Decodes via incremental Gauss-Jordan
 * elimination over GF(2) (XOR) - full elimination decodes from ANY set of
 * symbols whose coefficient vectors span the full k-dimensional space, a
 * real (not probabilistic) loss-tolerance guarantee. See OpticalFrame's
 * sibling files for how this plugs into the SHOW/SCAN screens; entirely
 * independent design from any external project (see OpticalFountain's own
 * doc).
 */
class OpticalFountainDecoder(private val symbolSize: Int) {
    private var payloadId: Int? = null
    private var totalSize: Int = -1
    private var k: Int = -1
    private lateinit var pivotCoeffs: Array<Set<Int>?>
    private lateinit var pivotBytes: Array<ByteArray?>
    private var solvedCount = 0
    private var framesSeen = 0

    val receivedFrameCount: Int get() = framesSeen

    private fun ensureInit(header: OpticalFountain.FrameHeader) {
        if (payloadId != null) return
        payloadId = header.payloadId
        totalSize = header.totalSize
        k = OpticalFountain.symbolCount(totalSize, symbolSize)
        pivotCoeffs = arrayOfNulls(k)
        pivotBytes = arrayOfNulls(k)
    }

    fun isComplete(): Boolean = payloadId != null && solvedCount >= k

    fun progress(): Pair<Int, Int> = solvedCount to (if (k <= 0) 0 else k)

    /** Returns true iff frame contributed new rank. */
    fun offer(frame: OpticalFountain.Frame): Boolean {
        ensureInit(frame.header)
        if (frame.header.payloadId != payloadId || frame.header.totalSize != totalSize) return false
        if (isComplete()) return false
        framesSeen++

        var coeffs: Set<Int> = OpticalFountain.neighborsFor(frame.header.payloadId, frame.header.symbolIndex, k).toSet()
        var bytes = frame.symbol.copyOf()

        for (i in 0 until k) {
            if (i !in coeffs) continue
            val pivot = pivotCoeffs[i] ?: continue
            coeffs = xorSets(coeffs, pivot)
            bytes = xorBytes(bytes, pivotBytes[i]!!)
        }
        if (coeffs.isEmpty()) return false

        val pivotIndex = coeffs.min()
        for (i in 0 until k) {
            val other = pivotCoeffs[i] ?: continue
            if (pivotIndex in other) {
                pivotCoeffs[i] = xorSets(other, coeffs)
                pivotBytes[i] = xorBytes(pivotBytes[i]!!, bytes)
            }
        }
        pivotCoeffs[pivotIndex] = coeffs
        pivotBytes[pivotIndex] = bytes
        solvedCount++
        return true
    }

    fun reconstruct(): ByteArray? {
        if (!isComplete()) return null
        val out = ByteArray(totalSize)
        for (i in 0 until k) {
            val bytes = pivotBytes[i] ?: return null
            val start = i * symbolSize
            val len = minOf(symbolSize, totalSize - start)
            System.arraycopy(bytes, 0, out, start, len)
        }
        return out
    }

    private fun xorSets(a: Set<Int>, b: Set<Int>): Set<Int> {
        val result = a.toMutableSet()
        b.forEach { if (!result.add(it)) result.remove(it) }
        return result
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size)
        for (i in a.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }
}
