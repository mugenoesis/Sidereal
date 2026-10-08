package io.github.mugenoesis.sidereal.series

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import io.github.mugenoesis.sidereal.camera.MediaLibraryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encodes the downloaded timelapse JPEGs into an H.264 MP4 as they arrive, one frame in memory at a time, so a
 * 600-frame timelapse never needs 4 GB of free space or RAM. Frames go in as raw YUV with explicit timestamps (no
 * GL surface needed), spaced evenly at [fps].
 *
 * The first frame sets the size: the largest of a short ladder the phone's encoder accepts. If anything goes wrong the
 * video is abandoned with a message; the downloaded frames are unaffected.
 */
class TimelapseVideoProcessor(
    private val context: Context,
    private val folder: String,
    private val fps: Int,
    private val media: MediaLibraryController = MediaLibraryController()
) : FrameProcessor {

    override val retainsFiles = false

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var outFile: File? = null
    private var width = 0
    private var height = 0
    private var frames = 0
    private var failure: String? = null
    private val info = MediaCodec.BufferInfo()

    override suspend fun onFrame(index: Int, tag: String, jpeg: File) {
        if (failure != null) return
        withContext(Dispatchers.Default) {
            try {
                if (codec == null) start(jpeg)
                encode(jpeg, index)
            } catch (e: Exception) {
                Log.w(TAG, "frame $index ($tag) failed: ${e.message}", e)
                failure = e.message ?: e.javaClass.simpleName
                release()
            }
        }
    }

    override suspend fun finish(folder: String, frameFiles: List<File>): String? = withContext(Dispatchers.Default) {
        failure?.let { return@withContext "Couldn't make the video ($it)" }
        val encoder = codec ?: return@withContext null
        try {
            val eos = dequeueInput(encoder)
            encoder.queueInputBuffer(eos, 0, 0, VideoMath.ptsUs(frames, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
            muxer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "finishing the video failed: ${e.message}", e)
            release()
            outFile?.delete()
            return@withContext "Couldn't make the video (${e.message})"
        }
        release()
        val file = outFile ?: return@withContext null
        val name = "${this@TimelapseVideoProcessor.folder}_${fps}fps.mp4"
        val saved = try { media.saveToMediaStore(context, file, name, "MP4", this@TimelapseVideoProcessor.folder) } catch (e: Exception) { false }
        file.delete()
        if (!saved) return@withContext "Couldn't save the video to the gallery"
        String.format(java.util.Locale.US, "Video saved to Movies/Sidereal/%s (%d×%d, %d frames, %.1fs)", this@TimelapseVideoProcessor.folder, width, height, frames, frames.toDouble() / fps)
    }

    // --- encoding ---

    private fun start(first: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(first.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "can't read the first frame" }

        var lastError: Exception? = null
        sizes@ for ((maxW, maxH) in LADDER) {
            val (w, h) = VideoMath.outputSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
            // High profile compresses noticeably better than Baseline at the same rate; fall back if the encoder won't.
            for (profile in listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, null)) {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, VideoMath.bitrate(w, h, fps))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (profile != null) {
                        setInteger(MediaFormat.KEY_PROFILE, profile)
                        if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51)
                    }
                }
                val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: continue
                try {
                    val encoder = MediaCodec.createByCodecName(name)
                    try {
                        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        encoder.start()
                    } catch (e: Exception) {
                        encoder.release()
                        throw e
                    }
                    codec = encoder
                    width = w
                    height = h
                    Log.i(TAG, "encoding ${w}x$h @ $fps fps with $name (${if (profile != null) "High" else "default"} profile)")
                    break@sizes
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "$name can't do ${w}x$h profile=$profile: ${e.message}")
                }
            }
        }
        if (codec == null) throw IllegalStateException(lastError?.message ?: "this phone has no H.264 encoder for the size")
        val file = File(context.cacheDir, "timelapse_${System.nanoTime()}.mp4")
        outFile = file
        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun encode(jpeg: File, index: Int) {
        val encoder = codec ?: return
        val bitmap = decode(jpeg) ?: throw IllegalStateException("can't read frame ${index + 1}")
        val yuv = try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            VideoMath.toI420(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
        val slot = dequeueInput(encoder)
        val image = encoder.getInputImage(slot) ?: throw IllegalStateException("encoder has no input image")
        copyPlane(image.planes[0], yuv.y, width, height)
        copyPlane(image.planes[1], yuv.u, width / 2, height / 2)
        copyPlane(image.planes[2], yuv.v, width / 2, height / 2)
        encoder.queueInputBuffer(slot, 0, width * height * 3 / 2, VideoMath.ptsUs(index, fps), 0)
        frames++
        drain(endOfStream = false)
    }

    /** The frame at exactly the output size: decoded no smaller than needed, then scaled to fit. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (decoded.width == width && decoded.height == height) return decoded
        return Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun copyPlane(plane: Image.Plane, src: ByteArray, w: Int, h: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.put(src, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = row * rowStride
                val srcBase = row * w
                for (col in 0 until w) buf.put(base + col * pixelStride, src[srcBase + col])
            }
        }
    }

    /** Waits for a free input buffer, draining output meanwhile so a full encoder can't stall us. */
    private fun dequeueInput(encoder: MediaCodec): Int {
        val deadline = System.currentTimeMillis() + INPUT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val slot = encoder.dequeueInputBuffer(0)
            if (slot >= 0) return slot
            drain(endOfStream = false)
            Thread.sleep(5)
        }
        throw IllegalStateException("the encoder stopped accepting frames")
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val deadline = System.currentTimeMillis() + OUTPUT_TIMEOUT_MS
        while (true) {
            when (val out = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (System.currentTimeMillis() > deadline) throw IllegalStateException("the encoder never finished")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer!!.addTrack(encoder.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                }
                else -> if (out >= 0) {
                    val data = encoder.getOutputBuffer(out)
                    if (data != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, data, info)
                    }
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        codec = null
        muxer = null
        muxerStarted = false
    }

    private companion object {
        const val TAG = "TimelapseVideo"
        const val INPUT_TIMEOUT_MS = 10_000L
        const val OUTPUT_TIMEOUT_MS = 20_000L

        /** Biggest first; each is a bounding box, the picture keeps its own shape inside it. */
        val LADDER = listOf(2304 to 1728, 1920 to 1440, 1280 to 960)
    }
}
