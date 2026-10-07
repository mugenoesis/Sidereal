package io.github.mugenoesis.sidereal.debug

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small known media for hardware-testing the audio muxer without a real recording: an H.264 clip of moving
 * stripes and an AAC clip that is silent, then a 440 Hz tone, then silent - so where the tone begins after
 * muxing proves where the audio ended up.
 */
object SyntheticMedia {

    const val SAMPLE_RATE = 44_100
    const val TONE_START_MS = 500
    const val TONE_LENGTH_MS = 1_000
    const val AUDIO_LENGTH_MS = 2_000

    fun makeVideo(out: File, frames: Int = 60, fps: Int = 30, width: Int = 320, height: Int = 240) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var submitted = 0
        var done = false
        while (!done) {
            if (submitted <= frames) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (submitted == frames) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = codec.getInputImage(index)!!
                        fillStripes(image, submitted)
                        codec.queueInputBuffer(index, 0, width * height * 3 / 2, submitted * 1_000_000L / fps, 0)
                    }
                    submitted++
                }
            }
            when (val out2 = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (out2 >= 0) {
                    val data = codec.getOutputBuffer(out2)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(out2, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
    }

    private fun fillStripes(image: android.media.Image, frame: Int) {
        val planes = image.planes
        for (p in planes.indices) {
            val buf = planes[p].buffer
            val rowStride = planes[p].rowStride
            val pixelStride = planes[p].pixelStride
            val w = if (p == 0) image.width else image.width / 2
            val h = if (p == 0) image.height else image.height / 2
            for (y in 0 until h) for (x in 0 until w) {
                val value = if (p == 0) (((x + frame * 4) / 16) % 2) * 160 + 40 else 128
                buf.put(y * rowStride + x * pixelStride, value.toByte())
            }
        }
    }

    /** Silence, then a tone from [TONE_START_MS] for [TONE_LENGTH_MS], then silence, [AUDIO_LENGTH_MS] long in total. */
    fun makeAudio(out: File) {
        val total = SAMPLE_RATE * AUDIO_LENGTH_MS / 1000
        val pcm = ShortArray(total) { i ->
            val ms = i * 1000L / SAMPLE_RATE
            if (ms in TONE_START_MS until (TONE_START_MS + TONE_LENGTH_MS)) (12_000 * sin(2 * PI * 440 * i / SAMPLE_RATE)).toInt().toShort() else 0
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var position = 0
        var inputDone = false
        var done = false
        while (!done) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buf = codec.getInputBuffer(index)!!
                    buf.clear()
                    val count = minOf(1024, total - position, buf.remaining() / 2)
                    if (count <= 0) {
                        codec.queueInputBuffer(index, 0, 0, position * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (i in 0 until count) buf.putShort(pcm[position + i])
                        codec.queueInputBuffer(index, 0, count * 2, position * 1_000_000L / SAMPLE_RATE, 0)
                        position += count
                    }
                }
            }
            when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (o >= 0) {
                    val data = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
    }

    /** Decodes the first audio track of [file] and returns the time in ms at which the tone first becomes audible, or null. */
    fun toneOnsetMs(file: File, threshold: Int = 3_000): Long? {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        try {
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            var onset: Long? = null
            while (!done && onset == null) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val shorts = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val n = shorts.remaining()
                    for (i in 0 until n step channels) {
                        if (abs(shorts.get(i).toInt()) > threshold) {
                            onset = info.presentationTimeUs / 1000 + (i / channels) * 1000L / rate
                            break
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            codec.stop(); codec.release()
            return onset
        } finally {
            extractor.release()
        }
    }
}
