package io.github.mugenoesis.sidereal.wearprotocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Length-prefixed frames (a 4 byte big-endian length, then the bytes) over a plain byte stream - the live view's JPEGs. */
object FrameCodec {

    /** A watch-sized JPEG is a few KB; anything near this size means the stream is corrupt. */
    const val MAX_FRAME_BYTES = 512 * 1024

    fun write(out: OutputStream, frame: ByteArray) {
        require(frame.size <= MAX_FRAME_BYTES) { "frame of ${frame.size} bytes exceeds $MAX_FRAME_BYTES" }
        val data = DataOutputStream(out)
        data.writeInt(frame.size)
        data.write(frame)
        data.flush()
    }

    /** The next frame, or null if the stream ended cleanly between frames. A frame cut short throws [EOFException]. */
    fun read(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val data = DataInputStream(input)
        val length = (first shl 24) or (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("implausible frame length $length")
        val frame = ByteArray(length)
        data.readFully(frame)
        return frame
    }

    /** The watch's confirmation: how many frames it has received in total (a 4 byte big-endian count). */
    fun writeAck(out: OutputStream, receivedCount: Int) {
        val data = DataOutputStream(out)
        data.writeInt(receivedCount)
        data.flush()
    }

    /** The next confirmation, or null if the stream ended cleanly. One cut short throws [EOFException]. */
    fun readAck(input: InputStream): Int? {
        val first = input.read()
        if (first < 0) return null
        val data = DataInputStream(input)
        return (first shl 24) or (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
    }
}
