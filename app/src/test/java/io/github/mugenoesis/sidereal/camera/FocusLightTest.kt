package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLightTest {
    @Test fun `a fast shutter at low ISO is bright`() {
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_320", 100))
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_120", 100))
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_60", 400))
    }

    @Test fun `a slow shutter is not`() {
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_25", 100))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_6_25", 100))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_2", 100))
    }

    @Test fun `a high ISO means the camera is working hard even with a quick shutter`() {
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_250", 3200))
    }

    @Test fun `unknown exposure is treated as dim`() {
        assertFalse(FocusLight.isBright(null, 100))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_BOGUS", 100))
    }

    @Test fun `an unknown ISO does not stop a fast shutter counting as bright`() {
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_500", null))
    }
}
