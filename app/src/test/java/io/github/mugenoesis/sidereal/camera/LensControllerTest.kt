package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `a lens named from a photo shows on the line and survives the camera still saying unknown`() {
        val c = controller { cb -> cb("Unknown") }
        c.refresh()
        c.identifyFromPhoto("LUMIX G VARIO 12-32/F3.5-5.6")
        assertTrue(c.line.value.text, c.line.value.text.startsWith("12-32 mm f/3.5-5.6"))
        c.refresh()
        assertEquals(32f, c.info.value!!.focalMaxMm)
        assertTrue(c.line.value.text, c.line.value.text.startsWith("12-32 mm f/3.5-5.6"))
    }

    @Test fun `a lens the camera does name replaces one identified from a photo`() {
        var answer: String? = "Unknown"
        val c = controller { cb -> cb(answer) }
        c.refresh()
        c.identifyFromPhoto("LUMIX G VARIO 12-32/F3.5-5.6")
        answer = "DJI MFT 15mm F1.7 ASPH"
        c.refresh()
        assertEquals(15f, c.info.value!!.primeFocalMm)
    }

    @Test fun `identifying from a photo ends the checking state`() {
        val c = controller { _ -> }
        c.identifyFromPhoto("LUMIX G VARIO 12-32/F3.5-5.6")
        assertEquals(LensLine.Kind.ZOOM_UNSET, c.line.value.kind)
    }

    private val panasonic = "LUMIX G VARIO 12-32/F3.5-5.6"

    @Test fun `a zoom with no position entered asks for one`() {
        val c = controller { cb -> cb(panasonic) }
        c.refresh()
        assertEquals(LensLine.Kind.ZOOM_UNSET, c.line.value.kind)
        assertTrue(c.line.value.text, c.line.value.text.contains("tap to set"))
        assertNull(c.zoomMm.value)
        assertNull(c.effectiveFocalMm())
    }

    @Test fun `an entered zoom position is shown and used`() {
        val c = controller { cb -> cb(panasonic) }
        c.refresh()
        c.setZoomMm(25f)
        assertEquals(LensLine.Kind.KNOWN, c.line.value.kind)
        assertEquals("12-32 mm f/3.5-5.6 - at 25 mm", c.line.value.text)
        assertEquals(25f, c.effectiveFocalMm())
    }

    @Test fun `a prime ignores an entered zoom position`() {
        val c = controller { cb -> cb("DJI MFT 15mm F1.7 ASPH") }
        c.refresh()
        c.setZoomMm(25f)
        assertEquals(15f, c.effectiveFocalMm())
        assertEquals("15 mm f/1.7", c.line.value.text)
    }

    @Test fun `a position outside the lens' range is refused`() {
        val c = controller { cb -> cb(panasonic) }
        c.refresh()
        c.setZoomMm(50f)
        assertNull(c.zoomMm.value)
        c.setZoomMm(5f)
        assertNull(c.zoomMm.value)
    }

    @Test fun `clearing forgets the position`() {
        val c = controller { cb -> cb(panasonic) }
        c.refresh()
        c.setZoomMm(18f)
        c.setZoomMm(null)
        assertNull(c.effectiveFocalMm())
    }

    @Test fun `moving the zoom ring after entering a position is noticed from the ring's limit`() {
        var max = 2633 // 25 mm
        val c = LensController({ cb -> cb(panasonic) }, { cb -> cb(900, max) })
        c.refresh(); c.refreshRing()
        c.setZoomMm(25f)
        max = 2640 // the limit wobbles a little
        c.refreshRing()
        assertEquals(25f, c.effectiveFocalMm())
        max = 3837 // now at 32 mm
        c.refreshRing()
        assertNull(c.effectiveFocalMm())
        assertEquals(LensLine.Kind.ZOOM_UNSET, c.line.value.kind)
        assertTrue(c.line.value.text, c.line.value.text.contains("moved"))
    }

    @Test fun `a different lens forgets the position`() {
        var answer = panasonic
        val c = controller { cb -> cb(answer) }
        c.refresh()
        c.setZoomMm(25f)
        answer = "OLYMPUS M.14-42mm F3.5-5.6 EZ"
        c.refresh()
        assertNull(c.effectiveFocalMm())
    }

    @Test fun `the focus ring's upper limit follows the zoom and is published for the autofocus`() {
        var max = 1570
        val c = LensController({ cb -> cb(panasonic) }, { cb -> cb(900, max) })
        assertNull(c.ringUpperBound.value)
        c.refreshRing()
        assertEquals(1570, c.ringUpperBound.value)
        max = 3837
        c.refreshRing()
        assertEquals(3837, c.ringUpperBound.value)
    }
}
