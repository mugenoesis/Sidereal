package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSoundPolicyTest {

    private val all = CameraSoundSettings()

    @Test
    fun `by default every sound is on`() {
        assertTrue(all.enabled && all.shutter && all.timer && all.recording && all.focus)
    }

    @Test
    fun `each event plays its own tone`() {
        assertEquals(CameraTone.SHUTTER, CameraSoundPolicy.toneFor(CameraSoundEvent.PhotoTaken, all))
        assertEquals(CameraTone.RECORD_START, CameraSoundPolicy.toneFor(CameraSoundEvent.RecordingStarted, all))
        assertEquals(CameraTone.RECORD_STOP, CameraSoundPolicy.toneFor(CameraSoundEvent.RecordingStopped, all))
        assertEquals(CameraTone.FOCUS, CameraSoundPolicy.toneFor(CameraSoundEvent.FocusLocked, all))
    }

    @Test
    fun `timer ticks beep and the last second is a different tone`() {
        assertEquals(CameraTone.TIMER_TICK, CameraSoundPolicy.toneFor(CameraSoundEvent.TimerTick(5), all))
        assertEquals(CameraTone.TIMER_TICK, CameraSoundPolicy.toneFor(CameraSoundEvent.TimerTick(2), all))
        assertEquals(CameraTone.TIMER_LAST, CameraSoundPolicy.toneFor(CameraSoundEvent.TimerTick(1), all))
    }

    @Test
    fun `the master switch silences everything`() {
        val off = all.copy(enabled = false)
        for (e in listOf(CameraSoundEvent.PhotoTaken, CameraSoundEvent.RecordingStarted, CameraSoundEvent.RecordingStopped,
            CameraSoundEvent.FocusLocked, CameraSoundEvent.TimerTick(3))) {
            assertNull("$e", CameraSoundPolicy.toneFor(e, off))
        }
    }

    @Test
    fun `each option only silences its own sounds`() {
        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.PhotoTaken, all.copy(shutter = false)))
        assertEquals(CameraTone.FOCUS, CameraSoundPolicy.toneFor(CameraSoundEvent.FocusLocked, all.copy(shutter = false)))

        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.TimerTick(3), all.copy(timer = false)))
        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.TimerTick(1), all.copy(timer = false)))
        assertEquals(CameraTone.SHUTTER, CameraSoundPolicy.toneFor(CameraSoundEvent.PhotoTaken, all.copy(timer = false)))

        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.RecordingStarted, all.copy(recording = false)))
        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.RecordingStopped, all.copy(recording = false)))

        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.FocusLocked, all.copy(focus = false)))
    }

    @Test
    fun `shots of a running sequence are silent, everything else still plays`() {
        assertNull(CameraSoundPolicy.toneFor(CameraSoundEvent.PhotoTaken, all, sequenceRunning = true))
        assertEquals(CameraTone.FOCUS, CameraSoundPolicy.toneFor(CameraSoundEvent.FocusLocked, all, sequenceRunning = true))
    }

    @Test
    fun `toggling flips exactly one option`() {
        val t = all.toggled(CameraSoundOption.TIMER)
        assertFalse(t.timer)
        assertTrue(t.shutter && t.recording && t.focus && t.enabled)
        assertTrue(t.toggled(CameraSoundOption.TIMER).timer)
        assertFalse(all.toggled(CameraSoundOption.MASTER).enabled)
    }

    @Test
    fun `settings survive a round trip through their stored form`() {
        val s = CameraSoundSettings(enabled = true, shutter = false, timer = true, recording = false, focus = true)
        assertEquals(s, CameraSoundSettings.decode(s.encode()))
        assertEquals(all, CameraSoundSettings.decode(null))
        assertEquals(all, CameraSoundSettings.decode("garbage"))
    }
}
