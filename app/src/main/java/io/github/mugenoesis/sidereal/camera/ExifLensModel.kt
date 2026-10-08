package io.github.mugenoesis.sidereal.camera

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the EXIF "LensModel" string (tag 0xA434) out of a JPEG. The framework ExifInterface on the phone does not know that
 * tag, and the lens' name is the only thing that identifies a third-party lens. Only the start of the file is looked at.
 */
object ExifLensModel {
    private const val EXIF_IFD_POINTER = 0x8769
    private const val LENS_MODEL = 0xA434
    private const val TYPE_ASCII = 2
    private const val HEAD_BYTES = 200_000

    fun read(file: File): String? = try {
        file.inputStream().use { input ->
            val head = ByteArray(minOf(file.length(), HEAD_BYTES.toLong()).toInt())
            var got = 0
            while (got < head.size) { val n = input.read(head, got, head.size - got); if (n < 0) break; got += n }
            head.copyOf(got)
        }.let(::read)
    } catch (e: Exception) {
        null
    }

    fun read(data: ByteArray): String? = try {
        parse(data)
    } catch (e: Exception) {
        null // truncated or malformed: no answer
    }

    private fun parse(d: ByteArray): String? {
        val marker = "Exif".toByteArray() + byteArrayOf(0, 0)
        val start = indexOf(d, marker)
        if (start < 0) return null
        val tiff = start + marker.size
        val order = when (String(d, tiff, 2, Charsets.ISO_8859_1)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val b = ByteBuffer.wrap(d).order(order)
        fun u16(o: Int) = b.getShort(tiff + o).toInt() and 0xFFFF
        fun u32(o: Int) = b.getInt(tiff + o)

        fun find(ifd: Int, tag: Int): Triple<Int, Int, Int>? { // type, count, offset of the 4-byte value field
            for (k in 0 until u16(ifd)) {
                val o = ifd + 2 + 12 * k
                if (u16(o) == tag) return Triple(u16(o + 2), u32(o + 4), o + 8)
            }
            return null
        }

        val pointer = find(u32(4), EXIF_IFD_POINTER) ?: return null
        val exifIfd = u32(pointer.third)
        val (type, count, field) = find(exifIfd, LENS_MODEL) ?: return null
        if (type != TYPE_ASCII || count <= 0) return null
        val at = tiff + if (count > 4) u32(field) else field
        val text = String(d, at, count, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        return text.ifEmpty { null }
    }

    private fun indexOf(d: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..d.size - pattern.size) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
