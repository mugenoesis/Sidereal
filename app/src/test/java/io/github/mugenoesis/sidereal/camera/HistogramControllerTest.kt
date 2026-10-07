package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class HistogramControllerTest {

    @Test
    fun `the SDK reusing one array for every push still produces a new value each time`() {
        // The real camera pushes the same ShortArray instance, rewritten in place; a StateFlow compares by reference,
        // so storing it as-is meant the picture never updated after the first push.
        val controller = HistogramController()
        val sdkArray = shortArrayOf(1, 2, 3)

        controller.onData(sdkArray)
        val first = controller.histogramData.value

        sdkArray[0] = 9
        controller.onData(sdkArray)
        val second = controller.histogramData.value

        assertNotSame(first, second)
        assertEquals(1, first!![0].toInt())
        assertEquals(9, second!![0].toInt())
    }

    @Test
    fun `later changes to the SDK's array do not alter what was already published`() {
        val controller = HistogramController()
        val sdkArray = shortArrayOf(5, 5)
        controller.onData(sdkArray)
        sdkArray[0] = 0
        assertEquals(5, controller.histogramData.value!![0].toInt())
    }
}
