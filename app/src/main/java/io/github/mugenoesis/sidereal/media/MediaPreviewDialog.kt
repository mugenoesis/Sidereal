package io.github.mugenoesis.sidereal.media

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.camera.DownloadStatus
import io.github.mugenoesis.sidereal.camera.MediaLibraryController
import io.github.mugenoesis.sidereal.camera.MediaTypeFilter
import io.github.mugenoesis.sidereal.display.NightMode
import dji.sdk.media.MediaFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Full-screen "look before you download" step between tapping a grid
 * thumbnail and actually pulling the full file over WiFi - see
 * MediaLibraryActivity's doc comment for why tapping used to skip straight
 * to a full download with no way to just look first.
 *
 * Uses MediaFile.fetchPreview() (a real, separate, medium-resolution image
 * distinct from both the tiny grid thumbnail and the full-resolution
 * download - confirmed present on dji.sdk.media.MediaFile), not another
 * full download, so looking is cheap regardless of the real file's size.
 *
 * Videos only ever get a still preview frame here - this SDK has no API to
 * stream video playback off the SD card without downloading the whole
 * file first, so previewVideoNotice makes that limitation visible instead
 * of the screen looking like a frozen/broken video player.
 *
 * Owns a private CoroutineScope (not the caller's lifecycleScope) so its
 * StateFlow collectors - which touch this dialog's own views - stop
 * exactly when the dialog closes, not whenever the hosting Activity
 * eventually does.
 */
class MediaPreviewDialog(
    private val activity: Activity,
    private val mediaFile: MediaFile,
    private val controller: MediaLibraryController
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var existingUri: Uri? = null
    private var pollExistingJob: Job? = null

    private val isVideo = mediaFile.mediaType == MediaFile.MediaType.MOV || mediaFile.mediaType == MediaFile.MediaType.MP4

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // A Dialog has its own Window, separate from the hosting
        // Activity's - MediaLibraryActivity setting FLAG_KEEP_SCREEN_ON on
        // itself does not carry over to this window, and this is exactly
        // where a long video download's progress is actually shown (see
        // MediaLibraryActivity's matching flag for why this matters - a
        // real native SDK crash was traced to the screen sleeping
        // mid-download).
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NightMode.apply(dialog)
        dialog.setContentView(R.layout.dialog_media_preview)

        dialog.findViewById<TextView>(R.id.previewFileNameText).text = mediaFile.fileName
        dialog.findViewById<ImageButton>(R.id.btnPreviewClose).setOnClickListener { dialog.dismiss() }

        if (isVideo) {
            val totalSeconds = mediaFile.durationInSeconds.toInt()
            dialog.findViewById<TextView>(R.id.previewVideoDurationText).text =
                String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
            dialog.findViewById<android.view.View>(R.id.previewVideoNotice).visibility = android.view.View.VISIBLE
        }

        loadPreviewImage()
        checkExistingDownload()
        observeDownloadState()

        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
    }

    private fun loadPreviewImage() {
        val imageView = dialog.findViewById<ImageView>(R.id.previewImage)
        val spinner = dialog.findViewById<ProgressBar>(R.id.previewLoadingSpinner)

        // Grid thumbnail (already fetched to show the grid at all) as an
        // immediate low-res placeholder while the real preview loads -
        // better than a blank screen, and covers the fallback case if
        // fetchPreview() fails for this file (seen on videos sometimes).
        mediaFile.thumbnail?.let { imageView.setImageBitmap(it) }

        val cachedPreview = mediaFile.preview
        if (cachedPreview != null) {
            imageView.setImageBitmap(cachedPreview)
            spinner.visibility = android.view.View.GONE
            return
        }

        mediaFile.fetchPreview { error ->
            activity.runOnUiThread {
                spinner.visibility = android.view.View.GONE
                if (error == null) {
                    mediaFile.preview?.let { imageView.setImageBitmap(it) }
                }
                // error != null: the thumbnail already set above stays as
                // the fallback - no toast, this is a "nice to have"
                // upgrade, not something the user needs to be told failed.
            }
        }
    }

    private fun checkExistingDownload() {
        scope.launch {
            existingUri = controller.findExistingDownload(activity, mediaFile)
            updateActionButton()
        }
    }

    private fun updateActionButton() {
        val button = dialog.findViewById<Button>(R.id.btnPreviewAction)
        val uri = existingUri
        if (uri != null) {
            button.text = "Open"
            button.setOnClickListener { openExisting(uri) }
        } else {
            button.text = "Download"
            button.setOnClickListener { startDownload() }
        }
    }

    private fun openExisting(uri: Uri) {
        val mimeType = MediaTypeFilter.mimeTypeFor(mediaFile.mediaType.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, "No app installed that can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownload() {
        scope.launch { controller.downloadFiles(activity, listOf(mediaFile)) }
    }

    /**
     * Mirrors MediaLibraryActivity's own per-file progress rendering, but
     * scoped to just this one file, and additionally re-checks
     * findExistingDownload() once this file's own download reaches DONE -
     * cheaper than a full recheck cycle for every progress tick, and turns
     * the button into "Open" (and jumps straight there, since a single
     * explicit download just for this file makes "now open it" the
     * obvious next step) the moment the file this dialog is actually
     * showing finishes, not some other file from a batch elsewhere.
     */
    private fun observeDownloadState() {
        controller.downloadProgress.onEach { progressByName ->
            val progress = progressByName[mediaFile.fileName] ?: return@onEach
            val progressGroup = dialog.findViewById<android.view.View>(R.id.previewDownloadProgressGroup)
            val progressBar = dialog.findViewById<ProgressBar>(R.id.previewDownloadProgressBar)
            val progressText = dialog.findViewById<TextView>(R.id.previewDownloadProgressText)
            val button = dialog.findViewById<Button>(R.id.btnPreviewAction)

            when (progress.status) {
                DownloadStatus.DOWNLOADING -> {
                    progressGroup.visibility = android.view.View.VISIBLE
                    button.isEnabled = false
                    button.alpha = 0.5f
                    val percent = if (progress.bytesTotal > 0) ((progress.bytesDownloaded * 100) / progress.bytesTotal).toInt() else 0
                    progressBar.progress = percent
                    progressText.text = "$percent%"
                }
                DownloadStatus.DONE -> {
                    progressGroup.visibility = android.view.View.GONE
                    button.isEnabled = true
                    button.alpha = 1f
                    if (pollExistingJob?.isActive != true) {
                        pollExistingJob = scope.launch {
                            existingUri = controller.findExistingDownload(activity, mediaFile)
                            updateActionButton()
                            existingUri?.let { openExisting(it) }
                        }
                    }
                }
                DownloadStatus.FAILED -> {
                    progressGroup.visibility = android.view.View.GONE
                    button.isEnabled = true
                    button.alpha = 1f
                }
                DownloadStatus.QUEUED -> {
                    progressGroup.visibility = android.view.View.VISIBLE
                    button.isEnabled = false
                    button.alpha = 0.5f
                }
            }
        }.launchIn(scope)

        dialog.findViewById<Button>(R.id.btnPreviewCancelDownload).setOnClickListener {
            controller.cancelDownloads()
        }
    }
}
