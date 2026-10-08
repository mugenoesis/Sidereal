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

    @Test fun `indoors in daylight at low ISO still gives a clean preview`() {
        // measured: 1/10 s, ISO 100 - a smooth hill with under 2% frame to frame noise
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_10", 100))
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_25", 200))
    }

    @Test fun `a slow shutter in dim indoor light is fine at low ISO`() {
        // the room measured at 1/6.25 - 1/10 s, ISO 100: still a clean, smooth hill
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_6_DOT_25", 100))
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_4", 100))
    }

    @Test fun `a very slow shutter is not trusted`() {
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1", 100))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_2", 100))
    }

    @Test fun `a high ISO means noise even with a quick shutter`() {
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_250", 3200))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_1000", 6400))
    }

    @Test fun `nothing known is treated as dim`() {
        assertFalse(FocusLight.isBright(null, null))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_BOGUS", null))
    }

    @Test fun `a low ISO alone is evidence enough when the shutter cannot be read`() {
        assertTrue(FocusLight.isBright(null, 100))
    }

    @Test fun `an unknown ISO falls back to the shutter alone`() {
        assertTrue(FocusLight.isBright("SHUTTER_SPEED_1_500", null))
        assertFalse(FocusLight.isBright("SHUTTER_SPEED_1_10", null))
    }
}
