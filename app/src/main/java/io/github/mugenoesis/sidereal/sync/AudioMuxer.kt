package io.github.mugenoesis.sidereal.sync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Merges phone-recorded audio into the camera's (silent) video file without re-encoding either: the video
 * samples are copied across untouched and the audio samples are copied with their timestamps moved by the
 * offset (see [AudioShift] for the arithmetic). Positive offset delays the audio, negative brings it forward
 * by cutting its start.
 *
 * Samples from the two tracks are written in timestamp order so the output interleaves properly for streaming
 * and for players that read it front to back.
 */
object AudioMuxer {

    data class Result(val videoSamples: Int, val audioKept: Int, val audioDropped: Int)

    /** Where a media file comes from: a plain path, or a content URI such as the system file picker returns. */
    sealed class Source {
        data class Path(val path: String) : Source()
        data class Content(val context: Context, val uri: Uri) : Source()

        internal fun extractor(): MediaExtractor = MediaExtractor().also {
            when (this) {
                is Path -> it.setDataSource(path)
                is Content -> it.setDataSource(context, uri, null)
            }
        }

        internal fun retriever(): MediaMetadataRetriever = MediaMetadataRetriever().also {
            when (this) {
                is Path -> it.setDataSource(path)
                is Content -> it.setDataSource(context, uri)
            }
        }
    }

    private const val BUFFER_BYTES = 8 * 1024 * 1024

    fun mux(videoPath: String, audioPath: String, offsetMs: Long, outPath: String): Result =
        mux(Source.Path(videoPath), Source.Path(audioPath), offsetMs, outPath)

    /** @throws IllegalArgumentException if the video has no video track or the audio file has no audio track */
    fun mux(videoSource: Source, audioSource: Source, offsetMs: Long, outPath: String): Result {
        val video = videoSource.extractor()
        val audio = audioSource.extractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val videoTrack = firstTrack(video, "video/") ?: throw IllegalArgumentException("no video track in the chosen video")
            val audioTrack = firstTrack(audio, "audio/") ?: throw IllegalArgumentException("no audio track in the chosen audio")
            video.selectTrack(videoTrack)
            audio.selectTrack(audioTrack)

            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            rotationOf(videoSource)?.let { muxer.setOrientationHint(it) }
            val videoOut = muxer.addTrack(video.getTrackFormat(videoTrack))
            val audioOut = muxer.addTrack(audio.getTrackFormat(audioTrack))

            // First pass over the audio: just its timestamps, so the shift can be planned before anything is written.
            val audioTimes = ArrayList<Long>()
            while (true) {
                val t = audio.sampleTime
                if (t < 0) break
                audioTimes += t
                audio.advance()
            }
            audio.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val shifted = AudioShift.apply(audioTimes, SyncOffset.toUs(offsetMs))

            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var audioIndex = 0
            var videoSamples = 0
            var audioKept = 0
            var audioDropped = 0

            while (true) {
                while (audioIndex < shifted.size && !shifted[audioIndex].keep) {
                    audio.advance()
                    audioIndex++
                    audioDropped++
                }
                val videoTime = video.sampleTime
                val audioTime = if (audioIndex < shifted.size) shifted[audioIndex].newTimeUs else -1L
                if (videoTime < 0 && audioTime < 0) break

                if (audioTime < 0 || (videoTime in 0..audioTime)) {
                    write(video, muxer, videoOut, buffer, info, videoTime)
                    video.advance()
                    videoSamples++
                } else {
                    write(audio, muxer, audioOut, buffer, info, audioTime)
                    audio.advance()
                    audioIndex++
                    audioKept++
                }
            }
            return Result(videoSamples, audioKept, audioDropped)
        } finally {
            try {
                if (started) muxer?.stop()
            } finally {
                muxer?.release()
                video.release()
                audio.release()
            }
        }
    }

    private fun write(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        timeUs: Long
    ) {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return
        info.set(0, size, timeUs, extractor.sampleFlags)
        muxer.writeSampleData(track, buffer, info)
    }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        }

    private fun rotationOf(source: Source): Int? {
        val retriever = try { source.retriever() } catch (e: Exception) { return null }
        return try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
