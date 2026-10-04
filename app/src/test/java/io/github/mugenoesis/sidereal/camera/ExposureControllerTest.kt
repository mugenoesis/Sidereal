package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.FakeCameraGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises ExposureController's *ByName internal entry points, never the
 * enum-typed public wrapper - see CameraGateway's doc comment for why:
 * passing a live SettingsDefinitions value as a parameter into any
 * app-authored function throws java.lang.VerifyError the moment a plain
 * JVM unit test actually calls it. The wrappers (isIsoEditable, setIso,
 * etc.) are one-line delegations to these and untested directly.
 */
class ExposureControllerTest {

    // --- PASM mode-to-editability table - the actual bug-prone logic here ---

    @Test
    fun `only MANUAL mode allows editing ISO`() {
        val controller = ExposureController()
        assertTrue(controller.isIsoEditableByName("MANUAL"))
        assertFalse(controller.isIsoEditableByName("PROGRAM"))
        assertFalse(controller.isIsoEditableByName("APERTURE_PRIORITY"))
        assertFalse(controller.isIsoEditableByName("SHUTTER_PRIORITY"))
    }

    @Test
    fun `shutter is editable in SHUTTER_PRIORITY and MANUAL only`() {
        val controller = ExposureController()
        assertTrue(controller.isShutterEditableByName("SHUTTER_PRIORITY"))
        assertTrue(controller.isShutterEditableByName("MANUAL"))
        assertFalse(controller.isShutterEditableByName("PROGRAM"))
        assertFalse(controller.isShutterEditableByName("APERTURE_PRIORITY"))
    }

    @Test
    fun `aperture is editable in APERTURE_PRIORITY and MANUAL only`() {
        val controller = ExposureController()
        assertTrue(controller.isApertureEditableByName("APERTURE_PRIORITY"))
        assertTrue(controller.isApertureEditableByName("MANUAL"))
        assertFalse(controller.isApertureEditableByName("PROGRAM"))
        assertFalse(controller.isApertureEditableByName("SHUTTER_PRIORITY"))
    }

    @Test
    fun `EV is editable in PROGRAM, SHUTTER_PRIORITY, and APERTURE_PRIORITY, but not MANUAL, CINE, or UNKNOWN`() {
        // MANUAL confirmed excluded via an on-device instrumented probe
        // against the real Zenmuse X5 (ExposureCompensationModeProbeTest):
        // setExposureCompensation is rejected in MANUAL with "Cannot set
        // the parameters in this state" every time, while PROGRAM/
        // APERTURE_PRIORITY/SHUTTER_PRIORITY all genuinely succeed.
        val controller = ExposureController()
        assertTrue(controller.isEvEditableByName("PROGRAM"))
        assertTrue(controller.isEvEditableByName("SHUTTER_PRIORITY"))
        assertTrue(controller.isEvEditableByName("APERTURE_PRIORITY"))
        assertFalse(controller.isEvEditableByName("MANUAL"))
        assertFalse(controller.isEvEditableByName("CINE"))
        assertFalse(controller.isEvEditableByName("UNKNOWN"))
    }

    // --- Setter -> gateway wiring and error surfacing ---

    @Test
    fun `setIsoByName forwards to the gateway and emits nothing on success`() {
        val gateway = FakeCameraGateway()
        val controller = ExposureController(gateway)
        controller.setIsoByName("ISO_400")
        assertEquals(listOf("setIso(ISO_400)"), gateway.calls)
    }

    @Test
    fun `setIsoByName emits a message naming the rejected value and the gateway's error`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "COMMON_PARAM_ILLEGAL" }
        val controller = ExposureController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setIsoByName("ISO_400") }
        val message = messages.single()
        assertTrue(message.contains("ISO_400"))
        assertTrue(message.contains("COMMON_PARAM_ILLEGAL"))
    }

    @Test
    fun `setExposureModeByName reports success to onComplete when the gateway accepts it`() {
        val gateway = FakeCameraGateway()
        val controller = ExposureController(gateway)
        var reportedSuccess: Boolean? = null
        controller.setExposureModeByName("APERTURE_PRIORITY") { success -> reportedSuccess = success }
        assertEquals(true, reportedSuccess)
    }

    @Test
    fun `setExposureModeByName reports failure to onComplete when the gateway rejects it`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "No camera connected" }
        val controller = ExposureController(gateway)
        var reportedSuccess: Boolean? = null
        // Regression test: MainActivity's mode selector used to commit its
        // locally-selected mode (and persist it to AppPreferences) before
        // knowing whether the camera accepted the switch, so a rejected
        // switch (e.g. a mid-reconnect "No camera connected") left the UI
        // pointing at a mode the camera was never actually in. This
        // onComplete callback is what lets the caller revert on failure.
        awaitEvents(controller.errorEvents) {
            controller.setExposureModeByName("APERTURE_PRIORITY") { success -> reportedSuccess = success }
        }
        assertEquals(false, reportedSuccess)
    }

    @Test
    fun `setExposureModeByName emits an error naming the rejected mode`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Not supported" }
        val controller = ExposureController(gateway)
        val messages = awaitEvents(controller.errorEvents) { controller.setExposureModeByName("MANUAL") }
        val message = messages.single()
        assertTrue(message.contains("MANUAL"))
        assertTrue(message.contains("Not supported"))
    }

    @Test
    fun `setExposureCompensationByName reports null to onComplete when the gateway accepts it`() {
        val gateway = FakeCameraGateway()
        val controller = ExposureController(gateway)
        var reportedError: String? = "not yet called"
        controller.setExposureCompensationByName("P_3_0") { error -> reportedError = error }
        assertEquals(null, reportedError)
    }

    @Test
    fun `setExposureCompensationByName reports the real error to onComplete when the gateway rejects it`() = runBlocking {
        val gateway = FakeCameraGateway().apply { errorToReturn = "Param Illegal" }
        val controller = ExposureController(gateway)
        var reportedError: String? = null
        // Regression test: MainActivity's EV stepper used to optimistically
        // advance its local "selected" value before knowing whether the
        // camera actually accepted it, so stepping past the real (but
        // unqueryable) EV ceiling let repeated presses walk further and
        // further into territory the camera had already rejected. This
        // onComplete callback is what lets the caller hold at the last
        // accepted value instead. It passes the real error through (not a
        // collapsed Boolean) because MainActivity needs to tell a genuine
        // out-of-range rejection ("Param Illegal") apart from other
        // failures ("Cannot set the parameters in this state", "Invalid
        // key for component") that must NOT be treated as a discovered
        // range boundary.
        awaitEvents(controller.errorEvents) {
            controller.setExposureCompensationByName("P_5_0") { error -> reportedError = error }
        }
        assertEquals("Param Illegal", reportedError)
    }

    @Test
    fun `setShutterSpeedByName, setApertureByName, and setExposureCompensationByName all reach the gateway`() {
        val gateway = FakeCameraGateway()
        val controller = ExposureController(gateway)
        controller.setShutterSpeedByName("SHUTTER_SPEED_1_100")
        controller.setApertureByName("F_2")
        controller.setExposureCompensationByName("N_0_3")
        assertEquals(
            listOf(
                "setShutterSpeed(SHUTTER_SPEED_1_100)",
                "setAperture(F_2)",
                "setExposureCompensation(N_0_3)"
            ),
            gateway.calls
        )
    }
}
