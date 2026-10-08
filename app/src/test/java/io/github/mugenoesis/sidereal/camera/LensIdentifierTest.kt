package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.series.CameraFile
import io.github.mugenoesis.sidereal.series.CardSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LensIdentifierTest {

    private class Card(val files: List<CameraFile>?, val fetchable: Boolean = true) : CardSource {
        val fetched = mutableListOf<String>()
        var closed = false
        override suspend fun listPhotos() = files
        override suspend fun fetch(file: CameraFile): File? {
            fetched += file.name
            return if (fetchable) File.createTempFile("lens", ".jpg") else null
        }
        override fun close() { closed = true }
    }

    private fun identify(card: Card, model: String?) = runBlocking { LensIdentifier(card) { model }.identify() }

    @Test fun `the lens named in the newest photo is returned`() {
        val card = Card(listOf(CameraFile("DJI_0001.JPG", "JPEG", 100), CameraFile("DJI_0002.JPG", "JPEG", 200)))
        val result = identify(card, "LUMIX G VARIO 12-32/F3.5-5.6")
        assertEquals(LensIdentifier.Result.Identified("LUMIX G VARIO 12-32/F3.5-5.6"), result)
        assertEquals(listOf("DJI_0002.JPG"), card.fetched)
    }

    @Test fun `raw files are skipped, the newest jpeg is used`() {
        val card = Card(listOf(CameraFile("DJI_0001.JPG", "JPEG", 100), CameraFile("DJI_0002.DNG", "RAW_DNG", 200)))
        identify(card, "x")
        assertEquals(listOf("DJI_0001.JPG"), card.fetched)
    }

    @Test fun `an empty card says there are no photos`() {
        assertEquals(LensIdentifier.Result.NoPhotos, identify(Card(emptyList()), "x"))
    }

    @Test fun `an unreadable card is reported`() {
        assertEquals(LensIdentifier.Result.CardUnreadable, identify(Card(null), "x"))
    }

    @Test fun `a failed download is reported`() {
        val card = Card(listOf(CameraFile("DJI_0001.JPG", "JPEG", 100)), fetchable = false)
        assertEquals(LensIdentifier.Result.DownloadFailed, identify(card, "x"))
    }

    @Test fun `a photo that names no lens is reported`() {
        val card = Card(listOf(CameraFile("DJI_0001.JPG", "JPEG", 100)))
        assertEquals(LensIdentifier.Result.NoLensInPhoto, identify(card, null))
        assertEquals(LensIdentifier.Result.NoLensInPhoto, identify(card, "  "))
    }

    @Test fun `the card is always closed and the downloaded file removed`() {
        val card = Card(listOf(CameraFile("DJI_0001.JPG", "JPEG", 100)))
        var seen: File? = null
        runBlocking { LensIdentifier(card) { seen = it; "x" }.identify() }
        assertTrue(card.closed)
        assertTrue(!seen!!.exists())
        val unreadable = Card(null)
        identify(unreadable, "x")
        assertTrue(unreadable.closed)
    }
}
