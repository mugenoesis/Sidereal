package io.github.mugenoesis.sidereal.wearprotocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class FrameCodecTest {

    @Test
    fun `frames come out whole and in order`() {
        val out = ByteArrayOutputStream()
        val a = ByteArray(1000) { it.toByte() }
        val b = byteArrayOf(1, 2, 3)
        FrameCodec.write(out, a)
        FrameCodec.write(out, b)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(a, FrameCodec.read(input))
        assertArrayEquals(b, FrameCodec.read(input))
    }

    @Test
    fun `a clean end of stream is null, not an error`() {
        assertNull(FrameCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a frame cut off mid way is an error`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, ByteArray(100))
        val cut = out.toByteArray().copyOf(50)
        try {
            FrameCodec.read(ByteArrayInputStream(cut))
            fail("expected EOFException")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun `an absurd length is rejected rather than allocated`() {
        val bad = byteArrayOf(0x7F, 0x00, 0x00, 0x00) // ~2 GB
        try {
            FrameCodec.read(ByteArrayInputStream(bad))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `an empty frame is allowed`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, ByteArray(0))
        assertEquals(0, FrameCodec.read(ByteArrayInputStream(out.toByteArray()))!!.size)
    }

    @Test
    fun `frames over the size limit cannot be written`() {
        try {
            FrameCodec.write(ByteArrayOutputStream(), ByteArray(FrameCodec.MAX_FRAME_BYTES + 1))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
