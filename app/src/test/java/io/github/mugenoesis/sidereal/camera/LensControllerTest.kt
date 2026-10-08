package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensControllerTest {

    private fun controller(read: ((String?) -> Unit) -> Unit) = LensController(read = read)

    @Test fun `nothing is known until the camera has been asked`() {
        val c = controller { _ -> }
        assertNull(c.info.value)
    }

    @Test fun `the camera's answer is parsed and published`() {
        val c = controller { cb -> cb("DJI MFT 15mm F1.7 ASPH") }
        c.refresh()
        assertEquals(15f, c.info.value!!.primeFocalMm)
        assertEquals("DJI MFT 15mm F1.7 ASPH", c.info.value!!.name)
    }

    @Test fun `no answer leaves the last known lens in place`() {
        var answer: String? = "OLYMPUS M.25mm F1.8"
        val c = controller { cb -> cb(answer) }
        c.refresh()
        answer = null
        c.refresh()
        assertEquals(25f, c.info.value!!.primeFocalMm)
    }

    @Test fun `a changed lens replaces the old one`() {
        var answer: String? = "OLYMPUS M.25mm F1.8"
        val c = controller { cb -> cb(answer) }
        c.refresh()
        answer = "OLYMPUS M.12-40mm F2.8 PRO"
        c.refresh()
        assertEquals(true, c.info.value!!.isZoom)
    }

    @Test fun `the line says it is checking until the camera has answered`() {
        val c = controller { _ -> }
        assertEquals(LensLine.Kind.CHECKING, c.line.value.kind)
    }

    @Test fun `an answer of nothing still ends the checking state`() {
        val c = controller { cb -> cb(null) }
        c.refresh()
        assertEquals(LensLine.Kind.UNKNOWN, c.line.value.kind)
    }

    @Test fun `the line follows the lens`() {
        val c = controller { cb -> cb("DJI MFT 15mm F1.7 ASPH") }
        c.refresh()
        assertEquals("15 mm f/1.7", c.line.value.text)
    }

    @Test fun `a stowed lens is noticed from the ring reading, and cleared when it is extended`() {
        var ring = -26270
        val c = LensController({ cb -> cb("Unknown") }, { cb -> cb(ring, 1570) })
        c.refresh()
        c.refreshRing()
        assertEquals(LensLine.Kind.NOT_EXTENDED, c.line.value.kind)
        ring = 1570
        c.refreshRing()
        assertEquals(LensLine.Kind.UNKNOWN, c.line.value.kind)
    }

    @Test fun `a stowed lens is noticed even before the name has been asked for`() {
        val c = LensController({ _ -> }, { cb -> cb(-26270, 1570) })
        c.refreshRing()
        assertEquals(LensLine.Kind.NOT_EXTENDED, c.line.value.kind)
    }
}
