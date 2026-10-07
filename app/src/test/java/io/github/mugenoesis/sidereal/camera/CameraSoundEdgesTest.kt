package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSoundEdgesTest {

    private fun edges() = CameraSoundEdges()

    @Test
    fun `the first reading makes no sound even if the camera is already shooting or recording`() {
        assertEquals(emptyList<CameraSoundEvent>(), edges().onState(shooting = true, recording = true))
    }

    @Test
    fun `a shot is one click however many readings say the camera is busy`() {
        val e = edges()
        e.onState(false, false)
        assertEquals(listOf(CameraSoundEvent.PhotoTaken), e.onState(true, false))
        assertEquals(emptyList<CameraSoundEvent>(), e.onState(true, false))
        assertEquals(emptyList<CameraSoundEvent>(), e.onState(true, false))
        assertEquals(emptyList<CameraSoundEvent>(), e.onState(false, false))
        assertEquals(listOf(CameraSoundEvent.PhotoTaken), e.onState(true, false))
    }

    @Test
    fun `recording start and stop are each announced once`() {
        val e = edges()
        e.onState(false, false)
        assertEquals(listOf(CameraSoundEvent.RecordingStarted), e.onState(false, true))
        assertEquals(emptyList<CameraSoundEvent>(), e.onState(false, true))
        assertEquals(listOf(CameraSoundEvent.RecordingStopped), e.onState(false, false))
    }

    @Test
    fun `losing the camera and reconnecting does not replay a sound`() {
        val e = edges()
        e.onState(false, true)
        e.onLost()
        assertEquals(emptyList<CameraSoundEvent>(), e.onState(false, true))
    }

    @Test
    fun `focus lock is announced on the way in only`() {
        val e = edges()
        assertEquals(emptyList<CameraSoundEvent>(), e.onFocusLocked(false))
        assertEquals(listOf(CameraSoundEvent.FocusLocked), e.onFocusLocked(true))
        assertEquals(emptyList<CameraSoundEvent>(), e.onFocusLocked(true))
        assertEquals(emptyList<CameraSoundEvent>(), e.onFocusLocked(false))
        assertEquals(listOf(CameraSoundEvent.FocusLocked), e.onFocusLocked(true))
    }
}
