package io.github.mugenoesis.sidereal.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncSidecarStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sidereal-sync").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sidecar(name: String, start: Long) = SyncSidecar(name, start, start - 200, start + 60_000)

    @Test
    fun `saving writes the sidecar beside the audio under its derived name`() {
        val saved = SyncSidecarStore.save(dir, sidecar("audio_1.m4a", 1_000))
        assertEquals("audio_1.sync.properties", saved.name)
        assertTrue(saved.exists())
        assertEquals(sidecar("audio_1.m4a", 1_000), SyncSidecarStore.load(saved))
    }

    @Test
    fun `takes are listed newest first`() {
        SyncSidecarStore.save(dir, sidecar("a.m4a", 1_000))
        SyncSidecarStore.save(dir, sidecar("c.m4a", 3_000))
        SyncSidecarStore.save(dir, sidecar("b.m4a", 2_000))
        assertEquals(listOf("c.m4a", "b.m4a", "a.m4a"), SyncSidecarStore.list(dir).map { it.audioFileName })
    }

    @Test
    fun `corrupt sidecars and unrelated files are skipped`() {
        SyncSidecarStore.save(dir, sidecar("good.m4a", 5_000))
        File(dir, "bad.sync.properties").writeText("not a sidecar")
        File(dir, "notes.txt").writeText("audioFileName=x.m4a\naudioStartEpochMs=1")
        assertEquals(listOf("good.m4a"), SyncSidecarStore.list(dir).map { it.audioFileName })
    }

    @Test
    fun `listing a missing folder is just empty`() {
        assertTrue(SyncSidecarStore.list(File(dir, "nope")).isEmpty())
    }

    @Test
    fun `loading a missing or corrupt file gives null`() {
        assertNull(SyncSidecarStore.load(File(dir, "missing.sync.properties")))
        val bad = File(dir, "bad.sync.properties").apply { writeText("x") }
        assertNull(SyncSidecarStore.load(bad))
    }

    @Test
    fun `saving again replaces the earlier version, which is how the tuned offset is kept`() {
        SyncSidecarStore.save(dir, sidecar("t.m4a", 1_000))
        SyncSidecarStore.save(dir, sidecar("t.m4a", 1_000).copy(manualOffsetMs = -120))
        val all = SyncSidecarStore.list(dir)
        assertEquals(1, all.size)
        assertEquals(-120L, all[0].manualOffsetMs)
    }

    @Test
    fun `only audio files that still exist next to the sidecar count as takes`() {
        File(dir, "present.m4a").writeText("audio")
        SyncSidecarStore.save(dir, sidecar("present.m4a", 1_000))
        SyncSidecarStore.save(dir, sidecar("deleted.m4a", 2_000))
        assertEquals(listOf("present.m4a"), SyncSidecarStore.listWithAudio(dir).map { it.audioFileName })
    }
}
