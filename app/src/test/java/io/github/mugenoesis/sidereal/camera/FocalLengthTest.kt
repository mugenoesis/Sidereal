package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocalLengthTest {
    @Test fun `exif stores the focal length as a rational`() {
        assertEquals(15f, FocalLength.fromExif("15/1")!!, 1e-6f)
        assertEquals(7.5f, FocalLength.fromExif("75/10")!!, 1e-6f)
        assertEquals(12.5f, FocalLength.fromExif("1250/100")!!, 1e-6f)
    }

    @Test fun `or as a plain number`() {
        assertEquals(25f, FocalLength.fromExif("25")!!, 1e-6f)
        assertEquals(14.5f, FocalLength.fromExif(" 14.5 ")!!, 1e-6f)
    }

    @Test fun `unusable values give nothing`() {
        assertNull(FocalLength.fromExif(null))
        assertNull(FocalLength.fromExif(""))
        assertNull(FocalLength.fromExif("0/0"))
        assertNull(FocalLength.fromExif("abc"))
        assertNull(FocalLength.fromExif("0/1"))
        assertNull(FocalLength.fromExif("5000/1"))
    }

    @Test fun `a planned lens that the pictures disagree with is detected`() {
        assertEquals(true, FocalLength.disagrees(planned = 15f, actual = 12f))
        assertEquals(false, FocalLength.disagrees(planned = 15f, actual = 15.2f))
        assertEquals(false, FocalLength.disagrees(planned = 15f, actual = null))
    }

    @Test fun `the focal length behind a planned field of view`() {
        val (h, _) = io.github.mugenoesis.sidereal.sequence.PanoramaPlanner.fovFor(25f)
        assertEquals(25f, FocalLength.fromHorizontalFov(h), 0.01f)
        val (h15, _) = io.github.mugenoesis.sidereal.sequence.PanoramaPlanner.fovFor(15f)
        assertEquals(15f, FocalLength.fromHorizontalFov(h15), 0.01f)
    }
}
