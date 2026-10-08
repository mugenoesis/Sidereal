package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensControllerTest {
    @Test fun `nothing is known until the camera has been asked`() {
        val c = LensController { _ -> }
        assertNull(c.info.value)
    }

    @Test fun `the camera's answer is parsed and published`() {
        val c = LensController { cb -> cb("DJI MFT 15mm F1.7 ASPH") }
        c.refresh()
        assertEquals(15f, c.info.value!!.primeFocalMm)
        assertEquals("DJI MFT 15mm F1.7 ASPH", c.info.value!!.name)
    }

    @Test fun `no answer leaves the last known lens in place`() {
        var answer: String? = "OLYMPUS M.25mm F1.8"
        val c = LensController { cb -> cb(answer) }
        c.refresh()
        answer = null
        c.refresh()
        assertEquals(25f, c.info.value!!.primeFocalMm)
    }

    @Test fun `a changed lens replaces the old one`() {
        var answer: String? = "OLYMPUS M.25mm F1.8"
        val c = LensController { cb -> cb(answer) }
        c.refresh()
        answer = "OLYMPUS M.12-40mm F2.8 PRO"
        c.refresh()
        assertEquals(true, c.info.value!!.isZoom)
    }
}
