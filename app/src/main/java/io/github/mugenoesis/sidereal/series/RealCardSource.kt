package io.github.mugenoesis.sidereal.series

import android.content.Context
import android.util.Log
import dji.sdk.media.MediaFile
import io.github.mugenoesis.sidereal.camera.MediaLibraryController
import io.github.mugenoesis.sidereal.camera.MediaLoadState
import io.github.mugenoesis.sidereal.camera.MediaTypeFilter
import kotlinx.coroutines.delay
import java.io.File

/**
 * The real card: switches the camera to its media (playback) mode to list and download, and back to photo mode on
 * [close]. The camera's list sometimes sits on "syncing" for a long time, so listing waits a bounded while, then
 * asks again once before giving up.
 */
class RealCardSource(
    private val context: Context,
    private val media: MediaLibraryController = MediaLibraryController(),
    private val listTimeoutMs: Long = 45_000L
) : CardSource {

    private var byName: Map<String, MediaFile> = emptyMap()

    override suspend fun listPhotos(): List<CameraFile>? {
        media.enterAndLoad()
        if (!awaitLoaded()) {
            Log.w(TAG, "card list did not load, asking again")
            media.refreshList()
            if (!awaitLoaded()) return null
        }
        val files = media.files.value.filter { !MediaTypeFilter.isVideo(it.mediaType.name) }
        byName = files.associateBy { it.fileName }
        return files.map { CameraFile(it.fileName, it.mediaType.name, it.timeCreated) }
    }

    private suspend fun awaitLoaded(): Boolean {
        var waited = 0L
        while (waited < listTimeoutMs) {
            when (media.loadState.value) {
                MediaLoadState.LOADED -> return true
                MediaLoadState.ERROR -> return false
                else -> { delay(POLL_MS); waited += POLL_MS }
            }
        }
        return false
    }

    override suspend fun fetch(file: CameraFile): File? {
        val mediaFile = byName[file.name] ?: return null
        return media.fetchToCache(context, mediaFile)
    }

    override fun close() {
        media.exit()
    }

    private companion object {
        const val TAG = "RealCardSource"
        const val POLL_MS = 250L
    }
}

/** Saves into the phone's gallery under Pictures/Sidereal/<series folder> (Movies/Sidereal/... for video). */
class MediaStoreGallery(
    private val context: Context,
    private val media: MediaLibraryController = MediaLibraryController()
) : Gallery {
    override fun save(file: File, displayName: String, mediaType: String, folder: String): Boolean = try {
        media.saveToMediaStore(context, file, displayName, mediaType, folder)
    } catch (e: Exception) {
        Log.w("MediaStoreGallery", "save($displayName) failed: ${e.message}")
        false
    }
}
