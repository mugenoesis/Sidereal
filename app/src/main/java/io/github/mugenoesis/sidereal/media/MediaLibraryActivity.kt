package io.github.mugenoesis.sidereal.media

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.SystemBars
import io.github.mugenoesis.sidereal.camera.DownloadStatus
import io.github.mugenoesis.sidereal.camera.MediaLibraryController
import io.github.mugenoesis.sidereal.camera.MediaLoadState
import io.github.mugenoesis.sidereal.display.NightMode
import dji.sdk.media.MediaFile
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Browses and downloads files off the camera's SD card. Self-contained:
 * owns its own MediaLibraryController instance (enterAndLoad() on create,
 * exit() on destroy) rather than sharing MainActivity's - see that
 * controller's doc comment for why this is a deliberate "open a browser,
 * do stuff, close it" flow, not something that runs alongside normal
 * shooting. MainActivity's launch site is responsible for refusing to open
 * this screen while recording; this activity assumes that guard already
 * happened.
 *
 * Tapping a thumbnail outside select mode opens MediaPreviewDialog (a real
 * preview image first, download only if explicitly requested, or straight
 * to opening the existing local copy if it's already been downloaded) -
 * previously downloaded that one file immediately on tap, with no way to
 * just look first. Select mode (the "Select" button, or a long-press) is
 * for downloading more than one at a time, and skips the preview step
 * entirely - batch downloads run one file at a time
 * (MediaLibraryController.downloadFiles()), never in parallel.
 */
class MediaLibraryActivity : AppCompatActivity() {

    private val mediaLibraryController = MediaLibraryController()
    private lateinit var adapter: MediaFileAdapter

    private var selectMode = false
    private var selectedNames = linkedSetOf<String>()
    private var allFiles: List<MediaFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MainActivity sets this too (Litchi-matching behavior), but
        // FLAG_KEEP_SCREEN_ON is per-Activity/per-Window in Android, not
        // inherited - this screen needs its own. Confirmed why this
        // matters specifically here, not just generally: real hardware
        // testing hit a native SIGSEGV crash (in the DJI SDK's own UDP
        // receiver thread) partway through a ~1 minute video download
        // after the screen went to sleep - consistent with Android's
        // screen-off WiFi power-saving disrupting the SDK's active
        // transfer mid-flight. A native crash kills the whole process, not
        // just this Activity, so a long-running download is exactly the
        // wrong time for the screen to be allowed to sleep.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_media_library)
        SystemBars.applyInsets(this, immersive = false)
        NightMode.apply(window)

        adapter = MediaFileAdapter(onItemClick = ::onItemTapped)
        findViewById<RecyclerView>(R.id.mediaRecyclerView).apply {
            layoutManager = GridLayoutManager(this@MediaLibraryActivity, 6)
            adapter = this@MediaLibraryActivity.adapter
        }

        findViewById<ImageButton>(R.id.btnMediaClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnMediaSelectMode).setOnClickListener { setSelectMode(!selectMode) }
        findViewById<ImageButton>(R.id.btnMediaDownload).setOnClickListener { downloadSelected() }
        findViewById<Button>(R.id.btnMediaCancelDownload).setOnClickListener { mediaLibraryController.cancelDownloads() }

        observeController()
        mediaLibraryController.enterAndLoad()
    }

    private fun observeController() {
        mediaLibraryController.loadState.onEach { state ->
            val loading = state == MediaLoadState.ENTERING_MODE || state == MediaLoadState.LOADING
            findViewById<View>(R.id.mediaLoadingGroup).visibility = if (loading) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.mediaLoadingText).text = if (state == MediaLoadState.ENTERING_MODE) {
                "Switching to media mode..."
            } else {
                "Loading files..."
            }
            findViewById<View>(R.id.mediaEmptyText).visibility =
                if (state == MediaLoadState.LOADED && allFiles.isEmpty()) View.VISIBLE else View.GONE
        }.launchIn(lifecycleScope)

        mediaLibraryController.files.onEach { files ->
            allFiles = files
            adapter.submitFiles(files)
            findViewById<View>(R.id.mediaEmptyText).visibility =
                if (mediaLibraryController.loadState.value == MediaLoadState.LOADED && files.isEmpty()) View.VISIBLE else View.GONE
        }.launchIn(lifecycleScope)

        mediaLibraryController.errorEvents.onEach { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }.launchIn(lifecycleScope)

        mediaLibraryController.downloadProgress.onEach { progress ->
            adapter.setProgress(progress)
        }.launchIn(lifecycleScope)

        mediaLibraryController.isDownloading.onEach { downloading ->
            updateActionBar(downloading)
        }.launchIn(lifecycleScope)
    }

    private fun onItemTapped(mediaFile: MediaFile) {
        if (selectMode) {
            if (!selectedNames.remove(mediaFile.fileName)) selectedNames.add(mediaFile.fileName)
            adapter.setSelected(selectedNames)
            updateActionBar(mediaLibraryController.isDownloading.value)
        } else {
            // Used to download immediately on tap, with no way to just
            // look first - MediaPreviewDialog shows a real preview image
            // (MediaFile.fetchPreview(), not another full download) and
            // only downloads if the user explicitly asks, or opens the
            // existing local copy directly if this file was already
            // downloaded in a previous session.
            MediaPreviewDialog(this, mediaFile, mediaLibraryController).show()
        }
    }

    private fun setSelectMode(enabled: Boolean) {
        selectMode = enabled
        if (!enabled) selectedNames.clear()
        adapter.setSelectMode(enabled)
        adapter.setSelected(selectedNames)
        findViewById<Button>(R.id.btnMediaSelectMode).text = if (enabled) "Cancel" else "Select"
        updateActionBar(mediaLibraryController.isDownloading.value)
    }

    private fun downloadSelected() {
        val files = allFiles.filter { selectedNames.contains(it.fileName) }
        if (files.isEmpty()) return
        lifecycleScope.launch { mediaLibraryController.downloadFiles(this@MediaLibraryActivity, files) }
    }

    private fun updateActionBar(downloading: Boolean) {
        val actionBar = findViewById<View>(R.id.mediaActionBar)
        val progressBar = findViewById<ProgressBar>(R.id.mediaActionBarProgress)
        val cancelButton = findViewById<Button>(R.id.btnMediaCancelDownload)
        val downloadButton = findViewById<ImageButton>(R.id.btnMediaDownload)
        val text = findViewById<TextView>(R.id.mediaActionBarText)

        if (downloading) {
            actionBar.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            cancelButton.visibility = View.VISIBLE
            downloadButton.visibility = View.GONE
            val progress = mediaLibraryController.downloadProgress.value.values
            val done = progress.count { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED }
            text.text = "Downloading ${done + 1} of ${progress.size}..."
        } else {
            cancelButton.visibility = View.GONE
            progressBar.visibility = View.GONE
            if (selectMode) {
                actionBar.visibility = View.VISIBLE
                downloadButton.visibility = View.VISIBLE
                downloadButton.isEnabled = selectedNames.isNotEmpty()
                downloadButton.alpha = if (selectedNames.isNotEmpty()) 1f else 0.4f
                text.text = "${selectedNames.size} selected"
            } else {
                actionBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaLibraryController.exit()
    }
}
