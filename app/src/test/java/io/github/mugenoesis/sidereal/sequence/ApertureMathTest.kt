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

    @Test fun `only apertures the lens can really do are candidates, widest first`() {
        val all = listOf("F_1_DOT_4", "F_1_DOT_7", "F_2", "F_3_DOT_5", "F_4", "F_5_DOT_6", "F_8", "F_11")
        // a 12-32 mm f/3.5-5.6 zoom: nothing wider than f/5.6 is safe at every zoom position
        assertEquals(listOf("F_5_DOT_6", "F_8", "F_11"), ApertureMath.atOrNarrowerThan(all, 5.6f))
        assertEquals(listOf("F_1_DOT_7", "F_2", "F_3_DOT_5", "F_4", "F_5_DOT_6", "F_8", "F_11"), ApertureMath.atOrNarrowerThan(all, 1.7f))
    }

    @Test fun `a tolerance for the rounding of 1_DOT_7 style names`() {
        assertEquals(listOf("F_1_DOT_8"), ApertureMath.atOrNarrowerThan(listOf("F_1_DOT_7", "F_1_DOT_8"), 1.8f))
    }
}
