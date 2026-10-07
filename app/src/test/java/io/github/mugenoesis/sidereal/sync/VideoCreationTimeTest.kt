package io.github.mugenoesis.sidereal.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCreationTimeTest {

    @Test
    fun `parses the metadata retriever's creation time as UTC`() {
        // 2026-10-07 10:15:00 UTC
        assertEquals(1_791_368_100_000L, VideoCreationTime.parse("20261007T101500.000Z"))
    }

    @Test
    fun `milliseconds are honoured`() {
        assertEquals(1_791_368_100_250L, VideoCreationTime.parse("20261007T101500.250Z"))
    }

    @Test
    fun `the form without fractional seconds also parses`() {
        assertEquals(1_791_368_100_000L, VideoCreationTime.parse("20261007T101500Z"))
    }

    @Test
    fun `missing or garbage values give null`() {
        assertNull(VideoCreationTime.parse(null))
        assertNull(VideoCreationTime.parse(""))
        assertNull(VideoCreationTime.parse("yesterday"))
    }

    @Test
    fun `the epoch placeholder some cameras write is treated as unknown`() {
        assertNull(VideoCreationTime.parse("19040101T000000.000Z"))
        assertNull(VideoCreationTime.parse("19700101T000000.000Z"))
    }
}
