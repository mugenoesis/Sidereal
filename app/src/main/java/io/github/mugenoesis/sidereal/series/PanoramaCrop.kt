package io.github.mugenoesis.sidereal.series

/** [left], [top] inclusive; [right], [bottom] exclusive. */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Stitched panoramas are not rectangles: the frames' edges bow towards the poles and the outer frames leave ragged
 * corners. This finds a clean rectangle inside the covered area by shaving off whichever outer row or column is least
 * covered until every edge is (nearly) all picture.
 */
object PanoramaCrop {

    fun largestCovered(covered: BooleanArray, width: Int, height: Int, tolerance: Double = 0.002): CropRect {
        var left = 0; var top = 0; var right = width; var bottom = height
        while (right > left && bottom > top) {
            val rectW = right - left
            val rectH = bottom - top
            val missTop = rectW - coveredInRow(covered, width, top, left, right)
            val missBottom = rectW - coveredInRow(covered, width, bottom - 1, left, right)
            val missLeft = rectH - coveredInCol(covered, width, left, top, bottom)
            val missRight = rectH - coveredInCol(covered, width, right - 1, top, bottom)
            val fracTop = missTop.toDouble() / rectW
            val fracBottom = missBottom.toDouble() / rectW
            val fracLeft = missLeft.toDouble() / rectH
            val fracRight = missRight.toDouble() / rectH
            val worst = maxOf(fracTop, fracBottom, fracLeft, fracRight)
            if (worst <= tolerance) break
            when (worst) {
                fracTop -> top++
                fracBottom -> bottom--
                fracLeft -> left++
                else -> right--
            }
        }
        if (right <= left || bottom <= top) return CropRect(0, 0, 0, 0)
        return CropRect(left, top, right, bottom)
    }

    private fun coveredInRow(c: BooleanArray, w: Int, row: Int, from: Int, to: Int): Int {
        var n = 0
        for (x in from until to) if (c[row * w + x]) n++
        return n
    }

    private fun coveredInCol(c: BooleanArray, w: Int, col: Int, from: Int, to: Int): Int {
        var n = 0
        for (y in from until to) if (c[y * w + col]) n++
        return n
    }
}
