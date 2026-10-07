package io.github.mugenoesis.sidereal.camera

enum class GridMode(val label: String) {
    OFF("Off"), THIRDS("Thirds"), GOLDEN("Golden"), CENTER("Center");

    fun next(): GridMode = values()[(ordinal + 1) % values().size]
}

data class GridLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** Composition guide lines for a [w] x [h] frame. */
object GridGeometry {

    private const val GOLDEN_LOW = 0.382f
    private const val GOLDEN_HIGH = 0.618f

    fun lines(mode: GridMode, w: Float, h: Float): List<GridLine> = when (mode) {
        GridMode.OFF -> emptyList()
        GridMode.THIRDS -> cross(w, h, 1f / 3f, 2f / 3f)
        GridMode.GOLDEN -> cross(w, h, GOLDEN_LOW, GOLDEN_HIGH)
        GridMode.CENTER -> cross(w, h, 0.5f)
    }

    private fun cross(w: Float, h: Float, vararg fractions: Float): List<GridLine> =
        fractions.map { GridLine(it * w, 0f, it * w, h) } + fractions.map { GridLine(0f, it * h, w, it * h) }
}
