package io.github.mugenoesis.sidereal.camera

/**
 * Pure enum-name-to-display-string parsers, extracted from MainActivity so
 * they're unit-testable without Robolectric/a live Activity - these derive
 * short display text from the SDK's own enum member names rather than
 * hardcoding an exhaustive when() over every ladder member (ISO/
 * ShutterSpeed/Aperture especially have many members), which is exactly
 * the kind of string-parsing code that had real, silent bugs this session
 * (shutterSpeedLabel's "_DOT_" handling, for one) - worth locking down with
 * tests. Kept deliberately free of getString()/Context so every function
 * here is a plain, deterministic String -> String mapping; the handful of
 * labels that need localized strings (exposureModeLabel, meteringLabel,
 * focusModeLabel, whiteBalanceLabel's named presets) stay in MainActivity.
 *
 * Every function here takes the enum's .name (a plain String) rather than
 * the live DJI SDK enum type, even though the original code had the real
 * enum in hand at every call site - loading dji-sdk-provided's classes
 * directly in a plain JVM unit test throws java.lang.VerifyError (that jar
 * is packaged for Android's ART runtime, not a desktop JVM bytecode
 * verifier). Working off .name sidesteps that entirely, since a String
 * needs no DJI class to exist. Callers (MainActivity) still pass
 * `iso.name` etc. from the real enum, so no accuracy is lost - the
 * decision of which member maps to which string is unchanged.
 */
object CameraLabels {

    fun isoLabel(isoName: String): String =
        if (isoName == "AUTO") "AUTO" else isoName.removePrefix("ISO_")

    /**
     * Real hardware testing found members like SHUTTER_SPEED_1_2_DOT_5
     * (1/2.5s) - the "_DOT_" decimal marker has to be collapsed to "."
     * before splitting on "_", or a slow shutter speed with a fractional
     * denominator splits into 4 parts instead of 2 and falls through to
     * the raw enum name.
     */
    fun shutterSpeedLabel(speedName: String): String {
        val raw = speedName.removePrefix("SHUTTER_SPEED_").replace("_DOT_", ".")
        val parts = raw.split("_")
        return when (parts.size) {
            2 -> "${parts[0]}/${parts[1]}"
            1 -> "${parts[0]}s"
            else -> raw
        }
    }

    fun apertureLabel(apertureName: String): String =
        "f/" + apertureName.removePrefix("F_").replace("_DOT_", ".")

    fun evLabel(evName: String): String {
        if (evName == "N_0_0") return "0.0"
        val sign = when {
            evName.startsWith("N_") -> "-"
            evName.startsWith("P_") -> "+"
            else -> ""
        }
        val numeric = evName.removePrefix("N_").removePrefix("P_").replace("_", ".")
        return "$sign$numeric"
    }

    fun antiFlickerLabel(freqName: String?): String = when (freqName) {
        "AUTO" -> "AUTO"
        "MANUAL_60HZ" -> "60Hz"
        "MANUAL_50HZ" -> "50Hz"
        "DISABLED" -> "OFF"
        else -> "AUTO"
    }

    fun photoFormatLabel(formatName: String?): String = when (formatName) {
        "JPEG" -> "JPEG"
        "RAW" -> "RAW"
        "RAW_AND_JPEG" -> "RAW+JPEG"
        else -> formatName ?: "JPEG"
    }

    fun photoAspectLabel(ratioName: String?): String = when (ratioName) {
        "RATIO_4_3" -> "4:3"
        "RATIO_16_9" -> "16:9"
        "RATIO_3_2" -> "3:2"
        "RATIO_1_1" -> "1:1"
        "RATIO_18_9" -> "18:9"
        "RATIO_5_4" -> "5:4"
        else -> "16:9"
    }

    fun videoResolutionLabel(resolutionName: String, frameRateName: String): String {
        val resText = when (resolutionName) {
            "RESOLUTION_4096x2160" -> "DCI 4K"
            "RESOLUTION_3840x2160" -> "UHD 4K"
            "RESOLUTION_2704x1520" -> "2.7K"
            "RESOLUTION_1920x1080" -> "1080p"
            else -> resolutionName
        }
        return "$resText · ${frameRateText(frameRateName)}"
    }

    /** `FRAME_RATE_23_DOT_976_FPS` -> "23.976", `FRAME_RATE_47_DOT_950_FPS` -> "47.95", `FRAME_RATE_25_FPS` -> "25". */
    fun frameRateText(frameRateName: String): String {
        val raw = frameRateName.removePrefix("FRAME_RATE_").removeSuffix("_FPS").replace("_DOT_", ".")
        return if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
    }

    fun videoStandardLabel(standardName: String?): String = when (standardName) {
        "PAL" -> "PAL (25/50)"
        "NTSC" -> "NTSC (24/30/60)"
        else -> "--"
    }

    /** The camera's picture profile ("color") as a person would say it. */
    fun colorLabel(colorName: String?): String = when (colorName) {
        null -> "--"
        "NONE" -> "Standard"
        "D_LOG" -> "D-Log"
        "D_CINELIKE" -> "D-Cinelike"
        "BLACK_AND_WHITE" -> "B&W"
        "M_31" -> "M31"
        "K_DX" -> "K-DX"
        else -> colorName.lowercase().split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }
}
