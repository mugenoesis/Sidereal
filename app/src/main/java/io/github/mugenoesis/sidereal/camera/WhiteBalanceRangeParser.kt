package io.github.mugenoesis.sidereal.camera

import dji.common.util.DJIParamMinMaxCapability

/**
 * Parses the CameraKey WHITE_BALANCE_CUSTOM_COLOR_TEMPERATURE_RANGE query
 * result, extracted from WhiteBalanceController so it's independently
 * testable. NOT confirmed against real hardware which shape this key
 * actually returns - handles the two most likely cases (a
 * DJIParamMinMaxCapability-style min/max object, same pattern as
 * DJIConnectionManager.queryGimbalRange's gimbal range, or a raw
 * 2-element int/Integer array) and otherwise reports the range as unknown
 * rather than risking a ClassCastException on whatever the key system
 * actually hands back.
 *
 * The DJIParamMinMaxCapability branch itself is untested: constructing one
 * in a plain JVM unit test throws java.lang.VerifyError, same as every
 * other concrete DJI SDK class beyond bare enums (confirmed by direct
 * experiment) - only the IntArray/Array<Number> branches and the
 * min>=max rejection are covered.
 */
object WhiteBalanceRangeParser {
    fun parseRange(value: Any): IntRange? {
        val (min, max) = when {
            value is DJIParamMinMaxCapability -> (value.min?.toInt() ?: return null) to (value.max?.toInt() ?: return null)
            value is IntArray && value.size == 2 -> value[0] to value[1]
            value is Array<*> && value.size == 2 && value[0] is Number && value[1] is Number ->
                (value[0] as Number).toInt() to (value[1] as Number).toInt()
            else -> return null
        }
        return if (min >= max) null else min..max
    }
}
