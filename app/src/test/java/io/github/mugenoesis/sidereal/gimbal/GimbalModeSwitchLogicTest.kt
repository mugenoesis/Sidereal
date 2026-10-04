package io.github.mugenoesis.sidereal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GimbalModeSwitchLogicTest {

    @Test
    fun `switching to the same mode is a no-op`() {
        for (mode in GimbalMode.values()) {
            assertNull(GimbalModeSwitchLogic.planSwitch(mode, mode))
        }
    }

    @Test
    fun `MANUAL to TIMED_MOVE stops manual and starts nothing (armed separately via UI)`() {
        val plan = GimbalModeSwitchLogic.planSwitch(GimbalMode.MANUAL, GimbalMode.TIMED_MOVE)
        assertEquals(GimbalModeSwitchLogic.SwitchPlan(GimbalModeSwitchLogic.StopAction.STOP_MANUAL, GimbalModeSwitchLogic.StartAction.NONE, GimbalMode.TIMED_MOVE), plan)
    }

    @Test
    fun `TIMED_MOVE to MANUAL stops the timed move and activates manual`() {
        val plan = GimbalModeSwitchLogic.planSwitch(GimbalMode.TIMED_MOVE, GimbalMode.MANUAL)
        assertEquals(GimbalModeSwitchLogic.SwitchPlan(GimbalModeSwitchLogic.StopAction.STOP_TIMED_MOVE, GimbalModeSwitchLogic.StartAction.START_MANUAL, GimbalMode.MANUAL), plan)
    }

    @Test
    fun `MANUAL to FACE_TRACK stops manual and arms face tracking`() {
        val plan = GimbalModeSwitchLogic.planSwitch(GimbalMode.MANUAL, GimbalMode.FACE_TRACK)
        assertEquals(GimbalModeSwitchLogic.SwitchPlan(GimbalModeSwitchLogic.StopAction.STOP_MANUAL, GimbalModeSwitchLogic.StartAction.START_FACE_TRACK, GimbalMode.FACE_TRACK), plan)
    }

    @Test
    fun `FACE_TRACK to TIMED_MOVE disarms face tracking and starts nothing`() {
        val plan = GimbalModeSwitchLogic.planSwitch(GimbalMode.FACE_TRACK, GimbalMode.TIMED_MOVE)
        assertEquals(GimbalModeSwitchLogic.SwitchPlan(GimbalModeSwitchLogic.StopAction.STOP_FACE_TRACK, GimbalModeSwitchLogic.StartAction.NONE, GimbalMode.TIMED_MOVE), plan)
    }

    @Test
    fun `every mode pair (other than same-to-same) is covered without throwing`() {
        val modes = GimbalMode.values()
        for (from in modes) {
            for (to in modes) {
                if (from == to) continue
                val plan = GimbalModeSwitchLogic.planSwitch(from, to)
                assertEquals(to, plan?.newMode)
            }
        }
    }
}
