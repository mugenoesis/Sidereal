package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExifLensModelTest {

    /** A JPEG header with an Exif block whose Exif sub-IFD holds a LensModel string (and a focal length, to be skipped over). */
    private fun jpeg(lens: String?, littleEndian: Boolean): ByteArray {
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val text = (lens ?: "").toByteArray(Charsets.ISO_8859_1) + 0
        // tiff layout: header(8) | IFD0 (1 entry: ExifIFD pointer) | Exif IFD (2 entries) | string data
        val ifd0 = 8
        val exifIfd = ifd0 + 2 + 12 + 4
        val entries = if (lens != null) 2 else 1
        val data = exifIfd + 2 + 12 * entries + 4
        val b = ByteBuffer.allocate(data + text.size + 8).order(order)
        b.put(if (littleEndian) "II".toByteArray() else "MM".toByteArray()).putShort(42).putInt(8)
        b.position(ifd0); b.putShort(1)
        b.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(exifIfd); b.putInt(0)
        b.position(exifIfd); b.putShort(entries.toShort())
        b.putShort(0x829D.toShort()).putShort(3).putInt(1).putShort(56).putShort(0) // an unrelated entry first
        if (lens != null) {
            b.putShort(0xA434.toShort()).putShort(2).putInt(text.size)
            // up to four bytes live in the entry itself; anything longer is an offset
            if (text.size <= 4) b.put(text).also { repeat(4 - text.size) { b.put(0) } } else b.putInt(data)
        }
        b.putInt(0)
        if (lens != null && text.size > 4) { b.position(data); b.put(text) }
        val tiff = b.array()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()))
        val len = 2 + 6 + tiff.size
        out.write(len shr 8); out.write(len and 0xFF)
        out.write("Exif".toByteArray()); out.write(0); out.write(0)
        out.write(tiff)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    @Test fun `reads the lens model from a little-endian file`() {
        assertEquals("LUMIX G VARIO 12-32/F3.5-5.6", ExifLensModel.read(jpeg("LUMIX G VARIO 12-32/F3.5-5.6", true)))
    }

    @Test fun `reads the lens model from a big-endian file`() {
        assertEquals("OLYMPUS M.45mm F1.8", ExifLensModel.read(jpeg("OLYMPUS M.45mm F1.8", false)))
    }

    @Test fun `a short model stored inline is read`() {
        assertEquals("X", ExifLensModel.read(jpeg("X", true)))
    }

    @Test fun `no lens model gives null`() {
        assertNull(ExifLensModel.read(jpeg(null, true)))
    }

    @Test fun `not a jpeg, or no exif, gives null`() {
        assertNull(ExifLensModel.read(ByteArray(0)))
        assertNull(ExifLensModel.read("hello world".toByteArray()))
        assertNull(ExifLensModel.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test fun `a truncated file gives null rather than throwing`() {
        val whole = jpeg("LUMIX G VARIO 12-32/F3.5-5.6", true)
        assertNull(ExifLensModel.read(whole.copyOf(40)))
    }
}
