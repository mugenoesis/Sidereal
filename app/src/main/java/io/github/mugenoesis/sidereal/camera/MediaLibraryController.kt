package io.github.mugenoesis.sidereal.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.sdk.media.DownloadListener
import dji.sdk.media.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

enum class MediaLoadState { IDLE, ENTERING_MODE, LOADING, LOADED, ERROR }

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadProgress(val fileName: String, val status: DownloadStatus, val bytesDownloaded: Long = 0, val bytesTotal: Long = 0)

/**
 * Browses and downloads files off the camera's SD card via the DJI SDK's
 * MediaManager. Requires the camera to be in CameraMode.MEDIA_DOWNLOAD -
 * enterAndLoad()/exit() handle that switch, which is why this is a
 * deliberate "open a browser, do stuff, close it" flow rather than
 * something that runs alongside normal shooting: MEDIA_DOWNLOAD is
 * mutually exclusive with SHOOT_PHOTO/RECORD_VIDEO on this camera.
 *
 * PHOTO_FOLDER/VIDEO_FOLDER entries (grouped shots - panorama bursts, etc.)
 * are filtered out of [files] rather than listed: they aren't fetchable as
 * a single file via MediaFile.fetchFileData() the way every other type is
 * (that needs the separate fetchSubFileDataList() path), and supporting
 * that is out of scope for this first pass.
 *
 * Downloads are deliberately sequential (one MediaFile at a time, not
 * parallel) - simpler, and this session's real-hardware testing has
 * repeatedly found this camera's WiFi link to be the bottleneck/flaky
 * element, not phone-side throughput, so concurrent downloads would mostly
 * just contend with each other rather than go faster.
 */
class MediaLibraryController {

    companion object {
        private const val TAG = "MediaLibraryController"
    }

    private val _files = MutableStateFlow<List<MediaFile>>(emptyList())
    val files: StateFlow<List<MediaFile>> = _files

    private val _loadState = MutableStateFlow(MediaLoadState.IDLE)
    val loadState: StateFlow<MediaLoadState> = _loadState

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents

    // One entry per file currently queued/downloading/finished this
    // session - the UI renders per-item progress/status off this rather
    // than a single global percentage, since multi-select downloads happen
    // one file at a time but the whole batch is one user action.
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    private var cancelRequested = false
    private var currentlyDownloading: MediaFile? = null

    fun enterAndLoad() {
        val camera = DJIConnectionManager.camera
        if (camera == null) {
            _loadState.value = MediaLoadState.ERROR
            _errorEvents.tryEmit("No camera connected")
            return
        }
        _loadState.value = MediaLoadState.ENTERING_MODE
        camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD) { error ->
            if (error != null) {
                Log.w(TAG, "setMode(MEDIA_DOWNLOAD) failed: ${error.description}")
                _loadState.value = MediaLoadState.ERROR
                _errorEvents.tryEmit("Couldn't switch to media mode (${error.description})")
                return@setMode
            }
            refreshList()
        }
    }

    /** The caller's watchdog decided the camera has stopped answering - stop showing "loading" and say so. */
    fun reportStalled() {
        if (_loadState.value != MediaLoadState.ENTERING_MODE && _loadState.value != MediaLoadState.LOADING) return
        Log.w(TAG, "media load stalled - the camera's file list never finished syncing")
        _loadState.value = MediaLoadState.ERROR
        _errorEvents.tryEmit("The camera isn't responding to the file list request")
    }

    fun refreshList() {
        val manager = DJIConnectionManager.camera?.mediaManager
        if (manager == null) {
            _loadState.value = MediaLoadState.ERROR
            _errorEvents.tryEmit("Media browsing not supported on this camera")
            return
        }
        _loadState.value = MediaLoadState.LOADING
        manager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD) { error ->
            if (error != null) {
                Log.w(TAG, "refreshFileListOfStorageLocation failed: ${error.description}")
                _loadState.value = MediaLoadState.ERROR
                _errorEvents.tryEmit("Couldn't load file list (${error.description})")
                return@refreshFileListOfStorageLocation
            }
            val downloadable = manager.getSDCardFileListSnapshot().orEmpty().filter { MediaTypeFilter.isDownloadable(it.mediaType.name) }
            _files.value = MediaOrdering.newestFirst(downloadable, { it.timeCreated }, { it.fileName })
            _loadState.value = MediaLoadState.LOADED
        }
    }

    /** Leaves media mode and returns the camera to photo shooting - call when the browser UI closes. */
    fun exit() {
        DJIConnectionManager.camera?.mediaManager?.exitMediaDownloading()
        DJIConnectionManager.camera?.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { error ->
            if (error != null) Log.w(TAG, "setMode(SHOOT_PHOTO) on exit failed: ${error.description}")
        }
        _files.value = emptyList()
        _loadState.value = MediaLoadState.IDLE
        _downloadProgress.value = emptyMap()
    }

    /**
     * Stops the batch: no further queued files start, AND the one
     * currently in flight (if any) is actively aborted via
     * stopFetchingFileData() - cancelRequested alone only stops the loop
     * BETWEEN files, so without this a cancel on a single-file download (or
     * on whichever file happens to be mid-transfer) would silently do
     * nothing until that transfer finished on its own. Real hardware
     * testing against a large video file caught this: Cancel visibly had
     * no effect until the fix below.
     */
    fun cancelDownloads() {
        cancelRequested = true
        currentlyDownloading?.stopFetchingFileData { error ->
            if (error != null) Log.w(TAG, "stopFetchingFileData failed: ${error.description}")
        }
    }

    /**
     * Downloads each file in [mediaFiles] to the device, one at a time,
     * copying into the public Pictures/Sidereal or Movies/Sidereal
     * MediaStore collection (so it shows up in the phone's normal
     * gallery/files apps) rather than the app's private storage. Safe to
     * call with a single-element list for a single download - same path
     * either way.
     *
     * Refuses a second call while one batch is already running - real
     * hardware testing caught this the hard way: tapping two different
     * single-file downloads in quick succession launched two overlapping
     * coroutines both mutating this controller's shared cancelRequested/
     * currentlyDownloading/downloadProgress state, and one file silently
     * came back FAILED with no underlying SDK error at all - the two
     * batches were just corrupting each other's bookkeeping. One global
     * queue, not one per call, is what "downloads never run in parallel"
     * actually requires.
     */
    suspend fun downloadFiles(context: Context, mediaFiles: List<MediaFile>) {
        if (mediaFiles.isEmpty()) return
        if (_isDownloading.value) {
            _errorEvents.tryEmit("A download is already in progress")
            return
        }
        cancelRequested = false
        _isDownloading.value = true
        _downloadProgress.value = mediaFiles.associate { it.fileName to DownloadProgress(it.fileName, DownloadStatus.QUEUED) }

        for (mediaFile in mediaFiles) {
            if (cancelRequested) break
            downloadOne(context, mediaFile)
        }

        _isDownloading.value = false
    }

    private suspend fun downloadOne(context: Context, mediaFile: MediaFile) {
        val fileName = mediaFile.fileName
        updateProgress(fileName, DownloadStatus.DOWNLOADING, 0, mediaFile.fileSize)
        currentlyDownloading = mediaFile

        // See MediaFileNaming's doc comment - fetchFileData appends the
        // real extension to destFileName itself, so destFileName must be
        // the base name only. (onSuccess(result)'s "result" string is just
        // destDir echoed back, not a full path - not useful for locating
        // the file either.)
        val destFileName = MediaFileNaming.downloadDestBaseName(fileName)
        val tempFile = File(context.cacheDir, MediaFileNaming.expectedDownloadedFileName(fileName))
        var resultPath: String? = null
        val success = suspendCancellableCoroutine<Boolean> { cont ->
            mediaFile.fetchFileData(context.cacheDir, destFileName, object : DownloadListener<String> {
                override fun onStart() {}

                override fun onRateUpdate(total: Long, current: Long, persec: Long) {}

                override fun onRealtimeDataUpdate(bytes: ByteArray?, position: Long, isLast: Boolean) {}

                override fun onProgress(total: Long, current: Long) {
                    updateProgress(fileName, DownloadStatus.DOWNLOADING, current, total)
                }

                override fun onSuccess(result: String?) {
                    resultPath = result
                    if (cont.isActive) cont.resume(true)
                }

                override fun onFailure(error: DJIError) {
                    Log.w(TAG, "fetchFileData($fileName) failed: ${error.description}")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }

        currentlyDownloading = null

        if (!success || !tempFile.exists()) {
            Log.w(TAG, "downloadOne($fileName): success=$success resultPath=$resultPath tempFile=${tempFile.absolutePath} exists=${tempFile.exists()}")
            updateProgress(fileName, DownloadStatus.FAILED, 0, mediaFile.fileSize)
            if (!cancelRequested) _errorEvents.tryEmit("Download failed: $fileName")
            return
        }

        val saved = try {
            saveToMediaStore(context, tempFile, fileName, mediaFile.mediaType)
        } catch (e: Exception) {
            Log.w(TAG, "saveToMediaStore($fileName) failed: ${e.message}")
            false
        } finally {
            tempFile.delete()
        }

        updateProgress(fileName, if (saved) DownloadStatus.DONE else DownloadStatus.FAILED, mediaFile.fileSize, mediaFile.fileSize)
        if (!saved) _errorEvents.tryEmit("Couldn't save $fileName to gallery")
    }

    private fun updateProgress(fileName: String, status: DownloadStatus, bytes: Long, total: Long) {
        _downloadProgress.value = _downloadProgress.value + (fileName to DownloadProgress(fileName, status, bytes, total))
    }

    private fun collectionFor(isVideo: Boolean): Uri =
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /**
     * Looks for a file already downloaded from a previous session, so
     * MediaPreviewDialog can offer "Open" instead of re-downloading over
     * WiFi something already sitting on the phone. Matches on DISPLAY_NAME
     * (the camera's own fileName - what saveToMediaStore below writes it
     * as) within Sidereal's own RELATIVE_PATH folder, not just anywhere
     * in the phone's whole Pictures/Movies library - camera filenames like
     * "DJI_0001.JPG" are generic enough to plausibly collide with something
     * unrelated outside our own folder. RELATIVE_PATH only exists on
     * API 29+ (same guard saveToMediaStore uses for writing it); below
     * that, this falls back to matching on DISPLAY_NAME alone within the
     * collection, accepting the (small, and only on very old Android) risk
     * of a false-positive match.
     */
    suspend fun findExistingDownload(context: Context, mediaFile: MediaFile): Uri? = withContext(Dispatchers.IO) {
        val isVideo = MediaTypeFilter.isVideo(mediaFile.mediaType.name)
        val collection = collectionFor(isVideo)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val relativePath = "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/Sidereal/"
            args = arrayOf(mediaFile.fileName, relativePath)
        } else {
            selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            args = arrayOf(mediaFile.fileName)
        }
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return@withContext ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: SecurityException) {
            // MainActivity requests READ_MEDIA_IMAGES/READ_MEDIA_VIDEO (or
            // READ_EXTERNAL_STORAGE pre-API 33) at startup, but a user can
            // still deny it - treat that the same as "not found" (falls
            // back to offering a fresh download) rather than crashing.
            // Confirmed on real hardware this throws, not just returns
            // null/empty, when the permission is missing.
            Log.w(TAG, "findExistingDownload(${mediaFile.fileName}) denied: ${e.message}")
        }
        null
    }

    private fun saveToMediaStore(context: Context, source: File, displayName: String, mediaType: MediaFile.MediaType): Boolean {
        val isVideo = MediaTypeFilter.isVideo(mediaType.name)
        val mimeType = MediaTypeFilter.mimeTypeFor(mediaType.name)
        val collection = collectionFor(isVideo)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) "${Environment.DIRECTORY_MOVIES}/Sidereal" else "${Environment.DIRECTORY_PICTURES}/Sidereal"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        val copied = resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
            true
        } ?: false
        if (!copied) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return true
    }
}
