package io.github.mugenoesis.sidereal.sync

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.mugenoesis.sidereal.R
import io.github.mugenoesis.sidereal.SystemBars
import io.github.mugenoesis.sidereal.display.NightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Lines a phone-recorded audio take up with a camera video. The camera clip has no sound and nothing in
 * either file says how they relate, so the app saves a sidecar with each take ([SyncSidecar]) holding the
 * moments each recording started; that gives an automatic starting offset, which is tuned by ear here with
 * a live preview, then exported as one MP4 by [AudioMuxer] (no re-encoding).
 *
 * Preview is two players kept in step by delaying the one that must start later - good to a few tens of
 * milliseconds, which is enough to judge by ear; the export itself is sample-exact.
 */
class AudioSyncActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var audioPlayer: MediaPlayer? = null

    private var takes: List<SyncSidecar> = emptyList()
    private var takeIndex = -1
    private var videoUri: Uri? = null
    private var videoName: String? = null
    private var manualOffsetMs = 0L
    private var previewing = false
    private var exporting = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoChosen(uri)
    }

    private val audioDir: File
        get() = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SiderealAudio")

    private val currentTake: SyncSidecar? get() = takes.getOrNull(takeIndex)

    private val totalOffsetMs: Long get() = (currentTake?.suggestedOffsetMs ?: 0L) + manualOffsetMs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_audio_sync)
        SystemBars.applyInsets(this, immersive = false)
        NightMode.apply(window)

        takes = SyncSidecarStore.listWithAudio(audioDir)
        if (takes.isNotEmpty()) selectTake(0)

        findViewById<ImageButton>(R.id.btnSyncClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSyncPickVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        findViewById<Button>(R.id.btnSyncPickTake).setOnClickListener {
            if (takes.isNotEmpty()) selectTake((takeIndex + 1).mod(takes.size))
        }
        nudgeButton(R.id.btnNudgeMinusCoarse, NudgeStep.COARSE, -1)
        nudgeButton(R.id.btnNudgeMinusMedium, NudgeStep.MEDIUM, -1)
        nudgeButton(R.id.btnNudgeMinusFine, NudgeStep.FINE, -1)
        nudgeButton(R.id.btnNudgePlusFine, NudgeStep.FINE, +1)
        nudgeButton(R.id.btnNudgePlusMedium, NudgeStep.MEDIUM, +1)
        nudgeButton(R.id.btnNudgePlusCoarse, NudgeStep.COARSE, +1)
        findViewById<Button>(R.id.btnSyncReset).setOnClickListener { setManualOffset(0) }
        findViewById<Button>(R.id.btnSyncPlay).setOnClickListener { if (previewing) stopPreview() else startPreview() }
        findViewById<Button>(R.id.btnSyncExport).setOnClickListener { export() }

        findViewById<VideoView>(R.id.syncVideo).setOnPreparedListener {
            it.setVolume(0f, 0f)
            // Show the first frame instead of a black box until the preview is played.
            findViewById<VideoView>(R.id.syncVideo).seekTo(1)
        }
        findViewById<VideoView>(R.id.syncVideo).setOnCompletionListener { stopPreview() }
        render()
        // Opened on a specific video (e.g. handed over by another screen): start with it already chosen.
        intent?.data?.let { onVideoChosen(it) }
    }

    private fun nudgeButton(id: Int, step: NudgeStep, direction: Int) {
        findViewById<Button>(id).setOnClickListener {
            setManualOffset(SyncOffset.nudge(totalOffsetMs, step, direction) - (currentTake?.suggestedOffsetMs ?: 0L))
        }
    }

    private fun selectTake(index: Int) {
        stopPreview()
        takeIndex = index
        manualOffsetMs = currentTake?.manualOffsetMs ?: 0L
        render()
    }

    private fun setManualOffset(value: Long) {
        manualOffsetMs = value
        // Remember it with the take so tuning survives leaving the screen; a restarted preview picks it up.
        currentTake?.let { take ->
            val updated = take.copy(manualOffsetMs = manualOffsetMs)
            takes = takes.toMutableList().also { it[takeIndex] = updated }
            SyncSidecarStore.save(audioDir, updated)
        }
        if (previewing) { stopPreview(); startPreview() }
        render()
    }

    private fun onVideoChosen(uri: Uri) {
        stopPreview()
        videoUri = uri
        videoName = displayName(uri)
        // Suggest the take that began closest to this video, if the video says when it was made.
        val created = runCatching {
            val retriever = MediaMetadataRetriever().apply { setDataSource(this@AudioSyncActivity, uri) }
            try { VideoCreationTime.parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) } finally { retriever.release() }
        }.getOrNull()
        if (created != null) {
            TakeMatcher.best(created, takes)?.let { match -> selectTake(takes.indexOfFirst { it.audioFileName == match.audioFileName }) }
        }
        findViewById<VideoView>(R.id.syncVideo).setVideoURI(uri)
        findViewById<View>(R.id.syncVideoHint).visibility = View.GONE
        render()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun startPreview() {
        val take = currentTake ?: return toast("No phone audio takes yet - record a video with a phone mic selected")
        if (videoUri == null) return toast("Choose a video first")
        val video = findViewById<VideoView>(R.id.syncVideo)
        val offset = totalOffsetMs
        val player = MediaPlayer().apply {
            setDataSource(File(audioDir, take.audioFileName).absolutePath)
            prepare()
        }
        audioPlayer = player
        previewing = true
        video.seekTo(0)
        if (offset >= 0) {
            video.start()
            handler.postDelayed({ if (previewing) { player.seekTo(0); player.start() } }, offset)
        } else {
            player.seekTo((-offset).toInt())
            player.start()
            video.start()
        }
        render()
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(null)
        runCatching { findViewById<VideoView>(R.id.syncVideo).pause() }
        audioPlayer?.let { runCatching { it.stop() }; it.release() }
        audioPlayer = null
        previewing = false
        if (!isDestroyed) render()
    }

    private fun export() {
        val take = currentTake ?: return toast("No phone audio take selected")
        val uri = videoUri ?: return toast("Choose a video first")
        if (exporting) return
        stopPreview()
        exporting = true
        setStatus("Merging… this copies the video without re-encoding")
        val offset = totalOffsetMs
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(cacheDir, "sync_export.mp4").apply { delete() }
                    val result = AudioMuxer.mux(
                        AudioMuxer.Source.Content(this@AudioSyncActivity, uri),
                        AudioMuxer.Source.Path(File(audioDir, take.audioFileName).absolutePath),
                        offset,
                        temp.absolutePath
                    )
                    val name = (videoName?.substringBeforeLast('.') ?: "video") + "_synced.mp4"
                    saveToMovies(temp, name) to result
                }
            }
            exporting = false
            outcome.onSuccess { (where, result) ->
                setStatus("Saved to $where\n${result.videoSamples} video frames, ${SyncOffset.format(offset)}")
                toast("Saved $where")
            }.onFailure { e ->
                setStatus("Export failed: ${e.message}")
            }
            render()
        }
    }

    /** Copies the merged file into the shared Movies/Sidereal folder (where downloads already go) and returns where. */
    private fun saveToMovies(source: File, displayName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Sidereal")
            }
            val target = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("couldn't create the output file")
            contentResolver.openOutputStream(target)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            return "Movies/Sidereal/$displayName"
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Sidereal").apply { mkdirs() }
        source.copyTo(File(dir, displayName), overwrite = true)
        return "${dir.name}/$displayName"
    }

    private fun render() {
        val take = currentTake
        findViewById<Button>(R.id.btnSyncPickVideo).text = videoName ?: "Choose video"
        findViewById<Button>(R.id.btnSyncPickTake).text = take?.let { "Audio take ${takeIndex + 1}/${takes.size}" } ?: "No audio takes yet"
        findViewById<TextView>(R.id.syncTakeInfo).text = take?.let {
            val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it.audioStartEpochMs))
            val auto = if (it.cameraStartEpochMs != null) "automatic ${SyncOffset.format(it.suggestedOffsetMs)}" else "camera start unknown, automatic offset 0"
            "${it.audioFileName}\nrecorded $started · $auto"
        } ?: "Phone audio takes appear here after recording a video with a phone or Bluetooth mic selected (More → Audio source)."
        findViewById<TextView>(R.id.syncOffsetValue).text = SyncOffset.format(totalOffsetMs)
        findViewById<TextView>(R.id.syncOffsetDescription).text = SyncOffset.describe(totalOffsetMs)
        findViewById<Button>(R.id.btnSyncPlay).text = if (previewing) "Stop preview" else "Play preview"
        findViewById<Button>(R.id.btnSyncExport).isEnabled = !exporting && take != null && videoUri != null
        findViewById<Button>(R.id.btnSyncExport).alpha = if (findViewById<Button>(R.id.btnSyncExport).isEnabled) 1f else 0.4f
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.syncStatus).text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        stopPreview()
    }
}
