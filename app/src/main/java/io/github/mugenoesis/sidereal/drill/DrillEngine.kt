package io.github.mugenoesis.sidereal.drill

import java.util.Random
import kotlin.math.hypot

enum class DrillState { PLAYING, ENDED }

sealed class DrillEvent {
    data class Cleared(val score: Int) : DrillEvent()
    object Off : DrillEvent()
    data class Strike(val remaining: Int) : DrillEvent()
    data class Ended(val finalPoints: Int) : DrillEvent()
}

/**
 * One target flying at the player. It starts far away (z = 1) at the middle of the screen and grows as it comes,
 * drifting out to the spot it is aimed at ([targetX],[targetY]); at z = 0 it arrives - and hits.
 */
class Target(
    val id: Int,
    val targetX: Float,
    val targetY: Float,
    var z: Float,
    val baseRadius: Float,
    /** Each one flies a little faster or slower than the rest (0.85..1.15). */
    val pace: Float,
    /** Drives its lumpy outline and its spin on screen. */
    val shapeSeed: Int
) {
    var x = 0f
        internal set
    var y = 0f
        internal set
    var radius = 0f
        internal set
}

enum class EffectKind { HIT, MISS, CONTACT }

class Effect(val x: Float, val y: Float, val kind: EffectKind) {
    var age = 0f
        internal set
}

/** Rules for the reticle drill - pure and seeded so every part is unit-tested. The world is [width] x [height] units; the view scales it. */
class DrillEngine(
    private val seed: Long = System.nanoTime(),
    /** Off only in tests that place their own targets. */
    private val spawning: Boolean = true,
    val width: Float = 1600f,
    val height: Float = 900f
) {
    var state = DrillState.PLAYING
        private set
    var score = 0
        private set
    var margin = START_MARGIN
        private set
    var time = 0f
        private set
    var crosshairX = width / 2
        private set
    var crosshairY = height / 2
        private set

    private val _targets = ArrayList<Target>()
    val targets: List<Target> get() = _targets
    private val _effects = ArrayList<Effect>()
    val effects: List<Effect> get() = _effects

    private var rng = Random(seed)
    private var runs = 0
    private var nextId = 0
    private var spawnTimer = FIRST_SPAWN_S
    private var cooldown = 0f

    fun step(dt: Float, stickX: Float, stickY: Float): List<DrillEvent> {
        if (state != DrillState.PLAYING) {
            ageEffects(dt) // the last crash's flash still has to fade
            return emptyList()
        }
        val events = ArrayList<DrillEvent>()
        time += dt
        cooldown = maxOf(0f, cooldown - dt)

        crosshairX = (crosshairX + stickX * CROSSHAIR_SPEED * dt).coerceIn(0f, width)
        crosshairY = (crosshairY + stickY * CROSSHAIR_SPEED * dt).coerceIn(0f, height)

        if (spawning) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawn()
                spawnTimer = spawnInterval(time) * (0.8f + 0.4f * rng.nextFloat())
            }
        }

        val speed = speedFactor(time) / BASE_TRAVEL_S
        val arrived = ArrayList<Target>()
        for (a in _targets) {
            a.z -= dt * speed * a.pace
            place(a)
            if (a.z <= 0f) arrived += a
        }
        for (a in arrived) {
            _targets.remove(a)
            _effects += Effect(a.x, a.y, EffectKind.CONTACT)
            margin--
            events += DrillEvent.Strike(margin)
            if (margin <= 0) {
                state = DrillState.ENDED
                events += DrillEvent.Ended(score)
                break
            }
        }

        ageEffects(dt)
        return events
    }

    private fun ageEffects(dt: Float) {
        _effects.forEach { it.age += dt }
        _effects.removeAll { it.age > EFFECT_LIFE_S }
    }

    /** Marks the spot under the crosshair. Ignored during the cooldown and after the drill is over. */
    fun mark(): List<DrillEvent> {
        if (state != DrillState.PLAYING || cooldown > 0f) return emptyList()
        cooldown = MARK_COOLDOWN
        val target = _targets
            .filter { hypot(it.x - crosshairX, it.y - crosshairY) <= it.radius * HIT_SLOP }
            .minByOrNull { it.z }
        if (target == null) {
            _effects += Effect(crosshairX, crosshairY, EffectKind.MISS)
            return listOf(DrillEvent.Off)
        }
        _targets.remove(target)
        score++
        _effects += Effect(target.x, target.y, EffectKind.HIT)
        return listOf(DrillEvent.Cleared(score))
    }

    fun restart() {
        runs++
        rng = Random(seed + runs * 7919L)
        state = DrillState.PLAYING
        score = 0
        margin = START_MARGIN
        time = 0f
        crosshairX = width / 2
        crosshairY = height / 2
        _targets.clear()
        _effects.clear()
        spawnTimer = FIRST_SPAWN_S
        cooldown = 0f
    }

    private fun spawn() {
        val a = Target(
            id = nextId++,
            targetX = MARGIN + rng.nextFloat() * (width - 2 * MARGIN),
            targetY = MARGIN + rng.nextFloat() * (height - 2 * MARGIN),
            z = 1f,
            baseRadius = 55f + rng.nextFloat() * 45f,
            pace = 0.85f + rng.nextFloat() * 0.3f,
            shapeSeed = rng.nextInt()
        )
        place(a)
        _targets += a
    }

    private fun place(a: Target) {
        val near = 1f - a.z.coerceIn(0f, 1f)
        a.x = width / 2 + (a.targetX - width / 2) * near
        a.y = height / 2 + (a.targetY - height / 2) * near
        a.radius = a.baseRadius * (MIN_SCALE + (1f - MIN_SCALE) * near)
    }

    /** Puts the crosshair at a world position (touch input). */
    fun aimAt(x: Float, y: Float) {
        if (state != DrillState.PLAYING) return
        crosshairX = x.coerceIn(0f, width)
        crosshairY = y.coerceIn(0f, height)
    }

    internal fun addTargetForTest(targetX: Float, targetY: Float, z: Float, baseRadius: Float = 70f) {
        val a = Target(nextId++, targetX, targetY, z, baseRadius, 1f, nextId)
        place(a)
        _targets += a
    }

    internal fun moveCrosshairForTest(x: Float, y: Float) {
        crosshairX = x.coerceIn(0f, width)
        crosshairY = y.coerceIn(0f, height)
    }

    companion object {
        const val START_MARGIN = 3
        const val CROSSHAIR_SPEED = 900f
        const val MARK_COOLDOWN = 0.18f
        const val EFFECT_LIFE_S = 0.6f

        private const val BASE_TRAVEL_S = 8f
        private const val FIRST_SPAWN_S = 0.5f
        private const val MARGIN = 120f
        private const val MIN_SCALE = 0.10f
        private const val HIT_SLOP = 1.15f
        private const val MAX_SPEED_FACTOR = 4.5f
        private const val SLOWEST_SPAWN_S = 2.2f
        private const val FASTEST_SPAWN_S = 0.55f

        /** 1x at the start, a bit faster every second, up to 4.5x. */
        fun speedFactor(t: Float): Float = (1f + t / 40f).coerceAtMost(MAX_SPEED_FACTOR)

        /** Seconds between new targets: starts at 2.2 and shrinks to 0.55. */
        fun spawnInterval(t: Float): Float = (SLOWEST_SPAWN_S - t * 0.03f).coerceAtLeast(FASTEST_SPAWN_S)

        /** How long one target takes from far away to you at drill time [t]. */
        fun travelSeconds(t: Float): Float = BASE_TRAVEL_S / speedFactor(t)
    }
}

object BestRecord {
    data class Result(val best: Int, val isNewRecord: Boolean)

    fun submit(previous: Int, score: Int): Result =
        if (score > previous) Result(score, true) else Result(previous, false)
}
