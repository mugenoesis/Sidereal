package io.github.mugenoesis.sidereal.sync

import java.io.File

/** Reads and writes [SyncSidecar]s in the folder the phone audio takes live in. */
object SyncSidecarStore {

    fun save(dir: File, sidecar: SyncSidecar): File {
        dir.mkdirs()
        val file = File(dir, SyncSidecar.fileNameFor(sidecar.audioFileName))
        file.writeText(sidecar.serialize())
        return file
    }

    fun load(file: File): SyncSidecar? =
        if (!file.isFile) null else runCatching { SyncSidecar.parse(file.readText()) }.getOrNull()

    /** Every readable sidecar in [dir], newest take first; unreadable or unrelated files are ignored. */
    fun list(dir: File): List<SyncSidecar> =
        dir.listFiles { f -> f.name.endsWith(SyncSidecar.SUFFIX) }
            ?.mapNotNull { load(it) }
            ?.sortedByDescending { it.audioStartEpochMs }
            .orEmpty()

    /** Like [list], but only for takes whose audio file is still there. */
    fun listWithAudio(dir: File): List<SyncSidecar> = list(dir).filter { File(dir, it.audioFileName).isFile }
}
