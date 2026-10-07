package io.github.mugenoesis.sidereal.drill

enum class ChordKey { UP, DOWN, LEFT, RIGHT, B, A, START }

/** Recognises a fixed sequence of controller buttons in a stream of presses (last eleven keys; a pause starts over). */
class ChordDetector(private val timeoutMs: Long = 3_000) {

    private val recent = ArrayList<ChordKey>()
    private var lastAt = Long.MIN_VALUE

    /** How many keys of the code the latest presses match so far (0 when the last key broke the run). */
    val progress: Int
        get() = (minOf(recent.size, CODE.size) downTo 1).firstOrNull { n -> recent.takeLast(n) == CODE.take(n) } ?: 0

    /** Feed each press; true on the press that completes the code. */
    fun onKey(key: ChordKey, nowMs: Long): Boolean {
        if (lastAt != Long.MIN_VALUE && nowMs - lastAt > timeoutMs) recent.clear()
        lastAt = nowMs
        recent += key
        while (recent.size > CODE.size) recent.removeAt(0)
        if (recent == CODE) {
            recent.clear()
            return true
        }
        return false
    }

    companion object {
        val CODE = listOf(
            ChordKey.UP, ChordKey.UP, ChordKey.DOWN, ChordKey.DOWN,
            ChordKey.LEFT, ChordKey.RIGHT, ChordKey.LEFT, ChordKey.RIGHT, ChordKey.B, ChordKey.A, ChordKey.START
        )
    }
}
