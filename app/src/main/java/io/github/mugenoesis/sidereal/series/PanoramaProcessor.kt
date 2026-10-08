package io.github.mugenoesis.sidereal.series

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mugenoesis.sidereal.camera.MediaLibraryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.tan

/**
 * Joins a finished panorama's JPEGs into one picture: the frames are placed by where the gimbal pointed (see
 * [PanoramaStitcher]), rendered a band of rows at a time so memory stays bounded, cropped to the clean rectangle and
 * saved next to the individual frames.
 */
class PanoramaProcessor(
    private val context: Context,
    private val folder: String,
    private val layout: PanoramaLayout,
    private val media: MediaLibraryController = MediaLibraryController()
) : FrameProcessor {

    override val retainsFiles = true

    private val files = sortedMapOf<Int, File>()
    private var report: (AfterRunProgress) -> Unit = {}

    override fun attach(report: (AfterRunProgress) -> Unit) { this.report = report }

    override suspend fun onFrame(index: Int, tag: String, jpeg: File) {
        if (index in layout.nodes.indices) files[index] = jpeg
    }

    override suspend fun finish(folder: String, frameFiles: List<File>): String? = withContext(Dispatchers.Default) {
        if (files.size < 2) return@withContext "Not enough frames to stitch a panorama"
        try {
            stitch()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory stitching", e)
            "Couldn't stitch the panorama - the phone ran out of memory (the individual frames are saved)"
        } catch (e: Exception) {
            Log.w(TAG, "stitching failed: ${e.message}", e)
            "Couldn't stitch the panorama (${e.message})"
        }
    }

    private fun stitch(): String {
        val indices = files.keys.toList()
        val specs = indices.map { GimbalAngles.toFrameSpec(layout.nodes[it].yaw, layout.nodes[it].pitch) }
        val first = files.getValue(indices.first())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(first.path, bounds)
        var hFov = layout.hFovDeg.toDouble()
        var vFov = layout.vFovDeg.toDouble()
        var frameSpecs = specs

        // Correct the gimbal's angles and the lens' field of view from the pictures themselves.
        report(AfterRunProgress("Aligning", 0, 0))
        val aligned = try {
            PanoramaAligner.align(indices.mapIndexed { k, i -> AlignFrame(specs[k], grayThumbnail(files.getValue(i))) }, hFov, vFov)
        } catch (e: Exception) {
            Log.w(TAG, "alignment failed, using the gimbal angles: ${e.message}", e)
            null
        }
        if (aligned != null && aligned.matchCount >= MIN_ALIGN_MATCHES && aligned.rmsDegAfter < ALIGN_ACCEPT_RMS_DEG) {
            Log.i(TAG, "aligned on ${aligned.matchCount} matches: fov x${"%.3f".format(aligned.fovScale)}, rms ${"%.2f".format(aligned.rmsDegBefore)} -> ${"%.2f".format(aligned.rmsDegAfter)} deg")
            frameSpecs = aligned.specs
            hFov = PanoramaAligner.effectiveFov(hFov, aligned.fovScale)
            vFov = PanoramaAligner.effectiveFov(vFov, aligned.fovScale)
        } else {
            Log.i(TAG, "not enough to align on (${aligned?.matchCount} matches), using the gimbal angles")
        }
        val nativePpd = (bounds.outWidth / 2.0) / tan(hFov / 2 * PI / 180) * PI / 180

        var maxPixels = MAX_PIXELS
        while (true) {
            try {
                return render(indices, frameSpecs, hFov, vFov, nativePpd, maxPixels)
            } catch (e: OutOfMemoryError) {
                if (maxPixels <= MIN_PIXELS) throw e
                maxPixels /= 2
                Log.w(TAG, "out of memory, trying $maxPixels pixels")
            }
        }
    }

    private fun render(indices: List<Int>, specs: List<PanoFrameSpec>, hFov: Double, vFov: Double, nativePpd: Double, maxPixels: Long): String {
        val canvas = PanoramaGeometry.canvasFor(specs, hFov, vFov, maxPixels, nativePpd)
        val w = canvas.width
        val h = canvas.height
        val canvasPpd = w / (canvas.lonMax - canvas.lonMin)
        var sample = 1
        while (nativePpd / (sample * 2) >= canvasPpd * 0.85) sample *= 2
        Log.i(TAG, "stitching ${specs.size} frames into ${w}x$h (${"%.1f".format(canvasPpd)} px/deg, frames decoded 1/$sample)")

        val stitcher = PanoramaStitcher(canvas, specs, hFov, vFov, blendPower = BLEND_POWER)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val covered = BooleanArray(w * h)
        val rowsPerBand = max(16, (BAND_BYTES / (w * 16L)).toInt())
        val bands = (h + rowsPerBand - 1) / rowsPerBand
        val band = IntArray(rowsPerBand * w)
        try {
            kotlinx.coroutines.runBlocking {
                var row = 0
                var done = 0
                while (row < h) {
                    report(AfterRunProgress("Stitching", done, bands))
                    val end = minOf(h, row + rowsPerBand)
                    stitcher.renderBand(row, end, { i -> decode(files.getValue(indices[i]), sample) }, band, outRowOrigin = row)
                    bitmap.setPixels(band, 0, w, 0, row, w, end - row)
                    for (r in 0 until end - row) for (c in 0 until w) covered[(row + r) * w + c] = (band[r * w + c] ushr 24) != 0
                    row = end
                    done++
                }
            }
            report(AfterRunProgress("Stitching", bands, bands))
            val crop = PanoramaCrop.largestCovered(covered, w, h)
            val cw = crop.right - crop.left
            val ch = crop.bottom - crop.top
            if (cw < 16 || ch < 16) return "The frames didn't overlap into a panorama (the individual frames are saved)"
            val result = if (cw == w && ch == h) bitmap else Bitmap.createBitmap(bitmap, crop.left, crop.top, cw, ch).also { bitmap.recycle() }
            val out = File(context.cacheDir, "panorama_${System.nanoTime()}.jpg")
            try {
                FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                val saved = media.saveToMediaStore(context, out, "${folder}_panorama.jpg", "JPEG", folder)
                if (!saved) return "Couldn't save the panorama to the gallery"
            } finally {
                out.delete()
                result.recycle()
            }
            return "Panorama saved to Pictures/Sidereal/$folder (${cw}×$ch, ${specs.size} frames)"
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** A small grey picture of a frame, about 600 px wide, for finding matches. */
    private fun grayThumbnail(file: File): GrayImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ALIGN_WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("can't read ${file.name}")
        try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return GrayImage(bmp.width, bmp.height, FloatArray(px.size) {
                val p = px[it]
                0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            })
        } finally {
            bmp.recycle()
        }
    }

    /** A frame as ARGB pixels, decoded at 1/[sample] size. */
    private fun decode(file: File, sample: Int): Raster? {
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Raster(bmp.width, bmp.height, px)
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val TAG = "PanoramaProcessor"
        const val MAX_PIXELS = 20_000_000L
        const val MIN_PIXELS = 2_500_000L
        const val BAND_BYTES = 64L * 1024 * 1024
        const val JPEG_QUALITY = 95
        const val ALIGN_WIDTH = 560
        const val BLEND_POWER = 8
        const val MIN_ALIGN_MATCHES = 24
        const val ALIGN_ACCEPT_RMS_DEG = 0.5
    }
}
