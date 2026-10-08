package io.github.mugenoesis.sidereal.series

import io.github.mugenoesis.sidereal.sequence.SequenceMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone

private class FakeCard(val files: List<CameraFile>, var readable: Boolean = true) : CardSource {
    val failing = mutableSetOf<String>()
    val failOnce = mutableSetOf<String>()
    val fetched = mutableListOf<String>()
    var closed = 0
    lateinit var dir: File

    override suspend fun listPhotos(): List<CameraFile>? = if (readable) files else null

    override suspend fun fetch(file: CameraFile): File? {
        fetched += file.name
        if (file.name in failing) return null
        if (failOnce.remove(file.name)) return null
        return File(dir, "tmp_${file.name}").also { it.writeText("data ${file.name}") }
    }

    override fun close() { closed++ }
}

private class FakeGallery : Gallery {
    data class Saved(val name: String, val type: String, val folder: String, val text: String)
    val saved = mutableListOf<Saved>()
    var failNames = setOf<String>()
    override fun save(file: File, displayName: String, mediaType: String, folder: String): Boolean {
        if (displayName in failNames) return false
        saved += Saved(displayName, mediaType, folder, file.readText())
        return true
    }
}

private class RecordingProcessor(val retains: Boolean = false, val result: String? = "Made it") : FrameProcessor {
    val frames = mutableListOf<Triple<Int, String, String>>()
    var finishedFolder: String? = null
    var existedAtFinish = 0
    override val retainsFiles = retains
    override suspend fun onFrame(index: Int, tag: String, jpeg: File) { frames += Triple(index, tag, jpeg.name) }
    override suspend fun finish(folder: String, frameFiles: List<File>): String? {
        finishedFolder = folder
        existedAtFinish = frameFiles.count { it.exists() }
        return result
    }
}

class SeriesPostRunnerTest {

    private lateinit var dir: File
    private val utc = TimeZone.getTimeZone("UTC")
    private val start = 1_791_422_130_000L // 2026-10-08 01:15:30 UTC

    @Before fun setUp() { dir = Files.createTempDirectory("series").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun jpg(n: Int, t: Long) = CameraFile("DJI_%04d.JPG".format(n), "JPEG", t)
    private fun dng(n: Int, t: Long) = CameraFile("DJI_%04d.DNG".format(n), "RAW_DNG", t)

    private fun plan(mode: SequenceMode, tags: List<String>, keep: Boolean = true, stitch: Boolean = false, video: Boolean = false) =
        SeriesPlan(mode, tags, keep, stitch, video)

    private fun runSummary(done: Int, span: Long = 60_000L) = RunSummary(start, span, done, done)

    private fun runner(card: FakeCard, gallery: FakeGallery, processor: FrameProcessor? = null) =
        SeriesPostRunner(card, gallery, { _, _, _ -> processor }, utc).also { card.dir = dir }

    private fun run(r: SeriesPostRunner, plan: SeriesPlan, summary: RunSummary, progress: MutableList<AfterRunProgress> = mutableListOf()): String? =
        runBlocking { r.run(plan, summary) { progress += it } }

    @Test
    fun `frames are saved into a folder named for the series with a tag per shot`() {
        val card = FakeCard((1..3).map { jpg(it, 1_000L * it) })
        val gallery = FakeGallery()
        val msg = run(runner(card, gallery), plan(SequenceMode.INTERVALOMETER, listOf("f0001", "f0002", "f0003")), runSummary(3))
        assertEquals(
            listOf(
                "Intervalometer_2026-10-08_0115_f0001_DJI_0001.JPG",
                "Intervalometer_2026-10-08_0115_f0002_DJI_0002.JPG",
                "Intervalometer_2026-10-08_0115_f0003_DJI_0003.JPG"
            ),
            gallery.saved.map { it.name }
        )
        assertTrue(gallery.saved.all { it.folder == "Intervalometer_2026-10-08_0115" })
        assertTrue(msg!!.contains("3"))
        assertTrue(msg.contains("Intervalometer_2026-10-08_0115"))
    }

    @Test
    fun `a raw and its jpeg are both saved under the same tag`() {
        val card = FakeCard(listOf(jpg(1, 1_000L), dng(1, 1_000L), jpg(2, 2_000L), dng(2, 2_000L)))
        val gallery = FakeGallery()
        run(runner(card, gallery), plan(SequenceMode.PANORAMA, listOf("r1c1", "r1c2")), runSummary(2))
        assertEquals(
            setOf(
                "Panorama_2026-10-08_0115_r1c1_DJI_0001.JPG", "Panorama_2026-10-08_0115_r1c1_DJI_0001.DNG",
                "Panorama_2026-10-08_0115_r1c2_DJI_0002.JPG", "Panorama_2026-10-08_0115_r1c2_DJI_0002.DNG"
            ),
            gallery.saved.map { it.name }.toSet()
        )
        assertEquals("RAW_DNG", gallery.saved.first { it.name.endsWith(".DNG") }.type)
    }

    @Test
    fun `temporary downloads never pile up`() {
        val card = FakeCard((1..3).map { jpg(it, 1_000L * it) })
        run(runner(card, FakeGallery()), plan(SequenceMode.INTERVALOMETER, listOf("a", "b", "c")), runSummary(3))
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(1, card.closed)
    }

    @Test
    fun `progress counts files as they come off the card`() {
        val card = FakeCard((1..4).map { jpg(it, 1_000L * it) })
        val progress = mutableListOf<AfterRunProgress>()
        run(runner(card, FakeGallery()), plan(SequenceMode.INTERVALOMETER, listOf("a", "b", "c", "d")), runSummary(4), progress)
        val downloading = progress.filter { it.stage == "Downloading" }
        assertEquals(listOf(0, 1, 2, 3, 4), downloading.map { it.done })
        assertTrue(downloading.all { it.total == 4 })
    }

    @Test
    fun `a card that cannot be read leaves the photos where they are and says so`() {
        val card = FakeCard(emptyList(), readable = false)
        val msg = run(runner(card, FakeGallery()), plan(SequenceMode.INTERVALOMETER, listOf("a")), runSummary(1))
        assertTrue(msg!!.contains("card", ignoreCase = true))
        assertTrue(msg.contains("media", ignoreCase = true))
        assertEquals(1, card.closed)
    }

    @Test
    fun `a file that fails to download is retried once and then counted`() {
        val card = FakeCard((1..3).map { jpg(it, 1_000L * it) })
        card.failOnce += "DJI_0002.JPG"
        card.failing += "DJI_0003.JPG"
        val gallery = FakeGallery()
        val msg = run(runner(card, gallery), plan(SequenceMode.INTERVALOMETER, listOf("a", "b", "c")), runSummary(3))
        assertEquals(2, gallery.saved.size)
        assertTrue(msg!!.contains("1 failed"))
    }

    @Test
    fun `when the files found do not match the plan they are numbered instead of tagged`() {
        // a stray shot taken just before the run is on the card too
        val card = FakeCard(listOf(jpg(1, 100_000L)) + (2..4).map { jpg(it, 110_000L + it * 5_000L) })
        val gallery = FakeGallery()
        val msg = run(runner(card, gallery), plan(SequenceMode.INTERVALOMETER, listOf("f0001", "f0002", "f0003")), runSummary(3, span = 10_000L))
        assertTrue(gallery.saved.none { it.name.contains("_f000") })
        assertTrue(gallery.saved.all { Regex("_n\\d{4}_").containsMatchIn(it.name) })
        assertNotNull(msg)
    }

    @Test
    fun `frames are not kept in the gallery when only the result is wanted`() {
        val card = FakeCard((1..3).map { jpg(it, 1_000L * it) })
        val gallery = FakeGallery()
        val processor = RecordingProcessor()
        run(runner(card, gallery, processor), plan(SequenceMode.TIMELAPSE, listOf("a", "b", "c"), keep = false, video = true), runSummary(3))
        assertTrue(gallery.saved.isEmpty())
        assertEquals(3, processor.frames.size)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `the processor sees every jpeg in shooting order and finishes with the folder name`() {
        val card = FakeCard(listOf(jpg(1, 1_000L), dng(1, 1_000L), jpg(2, 2_000L), dng(2, 2_000L)))
        val processor = RecordingProcessor()
        val msg = run(runner(card, FakeGallery(), processor), plan(SequenceMode.TIMELAPSE, listOf("f0001", "f0002"), video = true), runSummary(2))
        assertEquals(listOf(0 to "f0001", 1 to "f0002"), processor.frames.map { it.first to it.second })
        assertTrue(processor.frames.all { it.third.endsWith(".JPG") })
        assertEquals("Timelapse_2026-10-08_0115", processor.finishedFolder)
        assertTrue(msg!!.contains("Made it"))
    }

    @Test
    fun `a processor that keeps the files gets them all at the end and they are removed afterwards`() {
        val card = FakeCard((1..3).map { jpg(it, 1_000L * it) })
        val processor = RecordingProcessor(retains = true)
        run(runner(card, FakeGallery(), processor), plan(SequenceMode.PANORAMA, listOf("r1c1", "r1c2", "r1c3"), stitch = true), runSummary(3))
        assertEquals(3, processor.existedAtFinish)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `without any jpeg a video or stitch cannot be made and the raws are still saved`() {
        val card = FakeCard(listOf(dng(1, 1_000L), dng(2, 2_000L)))
        val gallery = FakeGallery()
        val processor = RecordingProcessor()
        val msg = run(runner(card, gallery, processor), plan(SequenceMode.TIMELAPSE, listOf("a", "b"), video = true), runSummary(2))
        assertEquals(2, gallery.saved.size)
        assertTrue(processor.frames.isEmpty())
        assertTrue(msg!!.contains("JPEG"))
    }

    @Test
    fun `a gallery that refuses a file is counted as failed`() {
        val card = FakeCard((1..2).map { jpg(it, 1_000L * it) })
        val gallery = FakeGallery().also { it.failNames = setOf("Intervalometer_2026-10-08_0115_a_DJI_0001.JPG") }
        val msg = run(runner(card, gallery), plan(SequenceMode.INTERVALOMETER, listOf("a", "b")), runSummary(2))
        assertEquals(1, gallery.saved.size)
        assertTrue(msg!!.contains("1 failed"))
    }

    @Test
    fun `nothing is shown for a plan that needs no download`() {
        val card = FakeCard(listOf(jpg(1, 1L)))
        val msg = run(runner(card, FakeGallery()), plan(SequenceMode.TIMELAPSE, listOf("a"), keep = false), runSummary(1))
        assertNull(msg)
        assertTrue(card.fetched.isEmpty())
        assertFalse(card.closed > 0)
    }
}
