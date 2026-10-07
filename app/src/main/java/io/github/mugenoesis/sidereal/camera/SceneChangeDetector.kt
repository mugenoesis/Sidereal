package io.github.mugenoesis.sidereal.camera

import kotlin.math.abs

/** A tiny fingerprint of a frame: 8x8 block averages of luminance, enough to tell "a different scene" from "the same scene". */
object SceneSignature {
    private const val BLOCKS = 8

    /** [pixels] is a [size] x [size] ARGB image (as from Bitmap.getPixels). */
    fun of(pixels: IntArray, size: Int): FloatArray {
        val block = (size / BLOCKS).coerceAtLeast(1)
        val out = FloatArray(BLOCKS * BLOCKS)
        for (by in 0 until BLOCKS) for (bx in 0 until BLOCKS) {
            var sum = 0f
            var n = 0
            for (y in by * block until minOf((by + 1) * block, size)) for (x in bx * block until minOf((bx + 1) * block, size)) {
                val p = pixels[y * size + x]
                sum += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                n++
            }
            out[by * BLOCKS + bx] = if (n > 0) sum / n else 0f
        }
        return out
    }

    /** Mean absolute difference between two signatures as a fraction of full scale, 0..1. */
    fun difference(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size / 255f
    }
}

/**
 * Notices that the camera has been pointed at something else, so a locked autofocus should look again. Sharpness
 * alone cannot tell: a panned-to scene may simply have more or less detail than the old one, so it can look "sharper"
 * while out of focus. This watches the picture itself instead. It fires once, only after the picture has both changed
 * a lot from the reference and then stopped changing (so a pan in progress doesn't set off a hunt at every frame),
 * ignores a flash that comes straight back, and follows slow exposure drift so auto-exposure isn't mistaken for a new
 * scene.
 */
class SceneChangeDetector(
    reference: FloatArray,
    private val changeThreshold: Float = 0.06f,
    private val steadyThreshold: Float = 0.02f,
    private val steadyFrames: Int = 3,
    private val driftFollow: Float = 0.1f
) {
    private var ref = reference.copyOf()
    private var previous: FloatArray? = null
    private var steadyCount = 0
    private var fired = false

    /** Adopt a new scene as "the same as before" - call after refocusing on it. */
    fun rebase(newReference: FloatArray) {
        ref = newReference.copyOf()
        previous = null
        steadyCount = 0
        fired = false
    }

    /** Returns true once, when a changed scene has settled. */
    fun onFrame(signature: FloatArray): Boolean {
        val sinceRef = SceneSignature.difference(ref, signature)
        val before = previous
        previous = signature.copyOf()
        if (sinceRef < changeThreshold) {
            // Same scene: follow slow drift (auto-exposure), and arm for the next change.
            for (i in ref.indices) ref[i] += driftFollow * (signature[i] - ref[i])
            steadyCount = 0
            fired = false
            return false
        }
        val moving = before == null || SceneSignature.difference(before, signature) >= steadyThreshold
        steadyCount = if (moving) 0 else steadyCount + 1
        if (steadyCount >= steadyFrames && !fired) {
            fired = true
            return true
        }
        return false
    }
}
