package com.opencall.relay.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** PART D-REMEDIATION: tests for OCP's own [OpticalFileContainer]. */
class OpticalFileContainerTest {

    @Test
    fun `a compressible text file round trips and gzip is chosen`() {
        val bytes = "hello world ".repeat(200).toByteArray()
        val packed = OpticalFileContainer.packFile("notes.txt", "text/plain", bytes)
        assertEquals(OpticalFileContainer.CompressionMode.GZIP, packed.compression)
        assertTrue(packed.transmittedSize < bytes.size)

        val unpacked = OpticalFileContainer.unpackFile(packed.container)
        assertEquals("notes.txt", unpacked.name)
        assertEquals("text/plain", unpacked.type)
        assertArrayEquals(bytes, unpacked.bytes)
        assertTrue(OpticalFileContainer.verifyFile(unpacked))
    }

    @Test
    fun `an already-compressed media type skips gzip even if it would shrink`() {
        val bytes = ByteArray(2000) { 0 }
        val packed = OpticalFileContainer.packFile("clip.mp4", "video/mp4", bytes)
        assertEquals(OpticalFileContainer.CompressionMode.NONE, packed.compression)
        assertArrayEquals(bytes, OpticalFileContainer.unpackFile(packed.container).bytes)
    }

    @Test
    fun `binary (random, incompressible) bytes round trip byte-for-byte`() {
        val bytes = ByteArray(10_000)
        Random(9).nextBytes(bytes)
        val packed = OpticalFileContainer.packFile("blob.bin", "application/octet-stream", bytes)
        val unpacked = OpticalFileContainer.unpackFile(packed.container)
        assertArrayEquals(bytes, unpacked.bytes)
        assertTrue(OpticalFileContainer.verifyFile(unpacked))
    }

    @Test
    fun `a path-like name is reduced to its basename`() {
        val packed = OpticalFileContainer.packFile("../../etc/passwd.txt", "text/plain", "x".repeat(800).toByteArray())
        val unpacked = OpticalFileContainer.unpackFile(packed.container)
        assertEquals("passwd.txt", unpacked.name)
    }

    @Test(expected = OpticalFileContainer.OpticalContainerException::class)
    fun `an empty file is refused`() {
        OpticalFileContainer.packFile("empty.txt", "text/plain", ByteArray(0))
    }

    @Test(expected = OpticalFileContainer.OpticalContainerException::class)
    fun `a file over the 64MB cap is refused, not truncated`() {
        OpticalFileContainer.packFile("huge.bin", "application/octet-stream", ByteArray(OpticalFileContainer.MAX_FILE_BYTES + 1))
    }

    @Test(expected = OpticalFileContainer.OpticalContainerException::class)
    fun `a truncated container is refused, never partially parsed`() {
        val packed = OpticalFileContainer.packFile("f.txt", "text/plain", "some bytes here".repeat(10).toByteArray())
        OpticalFileContainer.unpackFile(packed.container.copyOf(20))
    }

    @Test(expected = OpticalFileContainer.OpticalContainerException::class)
    fun `wrong magic is refused`() {
        val packed = OpticalFileContainer.packFile("f.txt", "text/plain", "some bytes here".repeat(10).toByteArray())
        val corrupted = packed.container.copyOf()
        corrupted[0] = 0
        OpticalFileContainer.unpackFile(corrupted)
    }
}
