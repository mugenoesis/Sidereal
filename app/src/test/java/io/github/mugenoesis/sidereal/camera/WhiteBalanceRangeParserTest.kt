package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhiteBalanceRangeParserTest {

    @Test
    fun `parses a 2-element IntArray as min to max`() {
        assertEquals(2000..10000, WhiteBalanceRangeParser.parseRange(intArrayOf(2000, 10000)))
    }

    @Test
    fun `parses a 2-element Number array as min to max`() {
        assertEquals(2000..10000, WhiteBalanceRangeParser.parseRange(arrayOf(2000, 10000)))
    }

    @Test
    fun `rejects a range where min is not less than max`() {
        assertNull(WhiteBalanceRangeParser.parseRange(intArrayOf(5000, 5000)))
        assertNull(WhiteBalanceRangeParser.parseRange(intArrayOf(10000, 2000)))
    }

    @Test
    fun `returns null for an unrecognized shape rather than throwing`() {
        assertNull(WhiteBalanceRangeParser.parseRange("not a range"))
        assertNull(WhiteBalanceRangeParser.parseRange(intArrayOf(1, 2, 3)))
    }

    // The DJIParamMinMaxCapability branch is untested - constructing one in
    // a plain JVM unit test throws java.lang.VerifyError, same as every
    // other concrete DJI SDK class beyond bare enums (confirmed by direct
    // experiment). See the class doc comment.
}
