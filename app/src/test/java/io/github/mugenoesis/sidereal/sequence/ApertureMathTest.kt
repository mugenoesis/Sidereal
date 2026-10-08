package io.github.mugenoesis.sidereal.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApertureMathTest {
    @Test fun `camera aperture names become f numbers`() {
        assertEquals(1.7, ApertureMath.fNumber("F_1_DOT_7")!!, 1e-9)
        assertEquals(2.2, ApertureMath.fNumber("F_2_DOT_2")!!, 1e-9)
        assertEquals(8.0, ApertureMath.fNumber("F_8")!!, 1e-9)
        assertEquals(11.0, ApertureMath.fNumber("F_11")!!, 1e-9)
    }

    @Test fun `names that are not apertures give nothing`() {
        assertNull(ApertureMath.fNumber("UNKNOWN"))
        assertNull(ApertureMath.fNumber("F_"))
        assertNull(ApertureMath.fNumber("ISO_100"))
    }

    @Test fun `opening up from f8 to f1 point 7 gains about four and a half stops`() {
        assertEquals(4.46, ApertureMath.stopsGained("F_8", "F_1_DOT_7")!!, 0.02)
    }

    @Test fun `closing down is negative and the same aperture is zero`() {
        assertEquals(-2.0, ApertureMath.stopsGained("F_2", "F_4")!!, 0.01)
        assertEquals(0.0, ApertureMath.stopsGained("F_4", "F_4")!!, 1e-9)
    }

    @Test fun `an unreadable aperture gives no answer`() {
        assertNull(ApertureMath.stopsGained(null, "F_1_DOT_7"))
        assertNull(ApertureMath.stopsGained("F_8", "weird"))
    }

    @Test fun `candidates are the widest first and only real apertures`() {
        val names = listOf("F_8", "UNKNOWN", "F_1_DOT_7", "F_4", "F_2_DOT_2")
        assertEquals(listOf("F_1_DOT_7", "F_2_DOT_2", "F_4", "F_8"), ApertureMath.widestFirst(names))
    }
}
