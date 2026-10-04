package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises CameraLabels against the DJI SDK's real enum member NAMES
 * (typed literally, not via SettingsDefinitions.* references) - see
 * CameraLabels' doc comment for why: touching dji-sdk-provided's actual
 * enum classes from a plain JVM unit test throws java.lang.VerifyError
 * (that jar is packaged for Android's ART runtime). These names were
 * copied from `javap -classpath dji-sdk-provided-4.16.4.jar
 * dji.common.camera.SettingsDefinitions$<EnumName>` against the exact SDK
 * version this project depends on - re-verify there if the SDK is ever
 * upgraded and one of these starts failing.
 */
class CameraLabelsTest {

    @Test
    fun `isoLabel shows AUTO for the AUTO member`() {
        assertEquals("AUTO", CameraLabels.isoLabel("AUTO"))
    }

    @Test
    fun `isoLabel strips the ISO_ prefix for numeric values`() {
        assertEquals("100", CameraLabels.isoLabel("ISO_100"))
        assertEquals("102400", CameraLabels.isoLabel("ISO_102400"))
    }

    @Test
    fun `shutterSpeedLabel formats a simple fraction`() {
        assertEquals("1/100", CameraLabels.shutterSpeedLabel("SHUTTER_SPEED_1_100"))
    }

    @Test
    fun `shutterSpeedLabel formats whole seconds`() {
        assertEquals("1s", CameraLabels.shutterSpeedLabel("SHUTTER_SPEED_1"))
        assertEquals("30s", CameraLabels.shutterSpeedLabel("SHUTTER_SPEED_30"))
    }

    @Test
    fun `shutterSpeedLabel handles a fractional-denominator DOT member without falling through to the raw name`() {
        // Regression test: SHUTTER_SPEED_1_2_DOT_5 (1/2.5s) used to split
        // into 4 parts instead of 2 before the "_DOT_" -> "." collapse was
        // added, and fell through to returning the raw enum name.
        assertEquals("1/2.5", CameraLabels.shutterSpeedLabel("SHUTTER_SPEED_1_2_DOT_5"))
    }

    @Test
    fun `shutterSpeedLabel handles a DOT member in the whole-seconds range`() {
        assertEquals("1.3s", CameraLabels.shutterSpeedLabel("SHUTTER_SPEED_1_DOT_3"))
    }

    @Test
    fun `apertureLabel formats a whole f-stop`() {
        assertEquals("f/2", CameraLabels.apertureLabel("F_2"))
    }

    @Test
    fun `apertureLabel formats a DOT f-stop`() {
        assertEquals("f/1.4", CameraLabels.apertureLabel("F_1_DOT_4"))
    }

    @Test
    fun `evLabel shows plain zero for N_0_0`() {
        assertEquals("0.0", CameraLabels.evLabel("N_0_0"))
    }

    @Test
    fun `evLabel shows a minus sign and dotted value for negative members`() {
        assertEquals("-1.3", CameraLabels.evLabel("N_1_3"))
        assertEquals("-5.0", CameraLabels.evLabel("N_5_0"))
    }

    @Test
    fun `evLabel shows a plus sign and dotted value for positive members`() {
        assertEquals("+0.3", CameraLabels.evLabel("P_0_3"))
        assertEquals("+5.0", CameraLabels.evLabel("P_5_0"))
    }

    @Test
    fun `antiFlickerLabel maps known members and defaults unknown null to AUTO`() {
        assertEquals("60Hz", CameraLabels.antiFlickerLabel("MANUAL_60HZ"))
        assertEquals("50Hz", CameraLabels.antiFlickerLabel("MANUAL_50HZ"))
        assertEquals("OFF", CameraLabels.antiFlickerLabel("DISABLED"))
        assertEquals("AUTO", CameraLabels.antiFlickerLabel(null))
    }

    @Test
    fun `photoFormatLabel maps known members and falls back to the raw name`() {
        assertEquals("JPEG", CameraLabels.photoFormatLabel("JPEG"))
        assertEquals("RAW+JPEG", CameraLabels.photoFormatLabel("RAW_AND_JPEG"))
        assertEquals("JPEG", CameraLabels.photoFormatLabel(null))
    }

    @Test
    fun `photoAspectLabel maps every ratio and defaults null to 16-9`() {
        assertEquals("4:3", CameraLabels.photoAspectLabel("RATIO_4_3"))
        assertEquals("1:1", CameraLabels.photoAspectLabel("RATIO_1_1"))
        assertEquals("16:9", CameraLabels.photoAspectLabel(null))
    }

    @Test
    fun `videoResolutionLabel combines resolution and frame rate text`() {
        assertEquals("4K24", CameraLabels.videoResolutionLabel("RESOLUTION_4096x2160", "FRAME_RATE_24_FPS"))
    }

    @Test
    fun `videoResolutionLabel falls back to the raw name for an unmapped resolution`() {
        assertEquals("RESOLUTION_1280x72030", CameraLabels.videoResolutionLabel("RESOLUTION_1280x720", "FRAME_RATE_30_FPS"))
    }
}
