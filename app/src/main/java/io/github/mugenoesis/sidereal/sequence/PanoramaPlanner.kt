package io.github.mugenoesis.sidereal.sequence

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.PI

/**
 * @param center attitude the panorama is centered on
 * @param yawSpanDeg / [pitchSpanDeg] total angular size to cover, edge to edge (not node to node)
 * @param hFovDeg / [vFovDeg] one frame's field of view
 * @param overlap fraction (0 until 1) neighbouring frames must share
 * @param shotsPerNode exposures stacked at each node
 */
data class PanoramaConfig(
    val center: Attitude,
    val yawSpanDeg: Float,
    val pitchSpanDeg: Float,
    val hFovDeg: Float,
    val vFovDeg: Float,
    val overlap: Float,
    val settleMs: Long,
    val exposureMs: Long,
    val shotsPerNode: Int = 1
)

data class Node(val row: Int, val col: Int, val pitch: Float, val yaw: Float)

/** [nodes] and [steps] are both in visiting order. */
data class PanoramaPlan(val rows: Int, val cols: Int, val nodes: List<Node>, val steps: List<SequenceStep>)

object PanoramaPlanner {

    /** The Osmo gimbal's usable pitch range in degrees - nodes outside it can't be reached. */
    const val MIN_PITCH = -90f
    const val MAX_PITCH = 30f

    /** Micro four thirds sensor, 4:3 - the X5's. Returns (horizontal, vertical) FOV in degrees. */
    fun fovFor(focalMm: Float, sensorWidthMm: Float = 17.3f, sensorHeightMm: Float = 13f): Pair<Float, Float> =
        fov(sensorWidthMm, focalMm) to fov(sensorHeightMm, focalMm)

    private fun fov(sensorMm: Float, focalMm: Float): Float =
        (2.0 * atan((sensorMm / 2.0) / focalMm) * 180.0 / PI).toFloat()

    fun plan(config: PanoramaConfig): PanoramaPlan {
        require(config.overlap >= 0f && config.overlap < 1f) { "overlap must be in [0, 1)" }
        require(config.shotsPerNode >= 1) { "need at least one shot per node" }

        val yaws = positions(config.center.yaw, config.yawSpanDeg, config.hFovDeg, config.overlap)
        val pitches = positions(config.center.pitch, config.pitchSpanDeg, config.vFovDeg, config.overlap)
        require(pitches.all { it in MIN_PITCH..MAX_PITCH }) {
            "panorama needs pitch ${pitches.min()}..${pitches.max()} but the gimbal only reaches $MIN_PITCH..$MAX_PITCH"
        }

        val nodes = ArrayList<Node>()
        pitches.forEachIndexed { row, pitch ->
            val ordered = if (row % 2 == 0) yaws.indices else yaws.indices.reversed()
            for (col in ordered) nodes += Node(row, col, pitch, yaws[col])
        }

        val total = nodes.size * config.shotsPerNode
        val steps = ArrayList<SequenceStep>()
        var shot = 0
        for (node in nodes) {
            steps += SequenceStep.MoveTo(node.pitch, node.yaw)
            steps += SequenceStep.Settle(config.settleMs)
            repeat(config.shotsPerNode) {
                shot++
                steps += SequenceStep.Capture(config.exposureMs, "pano $shot/$total")
            }
        }
        return PanoramaPlan(pitches.size, yaws.size, nodes, steps)
    }

    /** Evenly spaced node centers covering [span] with at least [overlap] between neighbours, ascending. */
    private fun positions(center: Float, span: Float, fov: Float, overlap: Float): List<Float> {
        if (span <= fov) return listOf(center)
        val travel = span - fov
        val maxStep = fov * (1f - overlap)
        val n = ceil(travel / maxStep - 1e-4f).toInt() + 1
        val spacing = travel / (n - 1)
        return (0 until n).map { center - travel / 2f + it * spacing }
    }
}
