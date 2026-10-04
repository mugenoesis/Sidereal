package io.github.mugenoesis.sidereal.camera

/**
 * Pure normalized-tap-to-grid-cell math extracted from
 * MeteringController.setSpotMeteringTarget - the SDK's spot-metering
 * target is a discrete grid cell (col, row), not a pixel or normalized
 * point, so a tap has to be converted before it can be sent.
 */
object MeteringGrid {
    /**
     * xNorm/yNorm are normalized 0..1 (same convention as FaceOverlayView's
     * bounding boxes), clamped before conversion so an out-of-range input
     * lands on an edge cell rather than an invalid one.
     */
    fun normalizedToCell(xNorm: Float, yNorm: Float, cols: Int, rows: Int): Pair<Int, Int> {
        val col = (xNorm.coerceIn(0f, 1f) * cols).toInt().coerceIn(0, cols - 1)
        val row = (yNorm.coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows - 1)
        return col to row
    }
}
