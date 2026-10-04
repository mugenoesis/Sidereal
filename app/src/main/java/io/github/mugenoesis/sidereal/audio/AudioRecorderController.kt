package io.github.mugenoesis.sidereal.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone-local audio recording that runs alongside DJI video capture. This
 * rig has no mic plugged into the gimbal, so the camera's own recording is
 * silent - and the DJI SDK has no way to inject phone-captured audio into
 * that recording anyway, even if it did. This is a plain MediaRecorder
 * writing an AAC/M4A file to the phone's own storage; entirely independent
 * of the DJI SDK and camera state.
 *
 * Started by MainActivity off the app's own record-start button press
 * (CameraModeController.isRecordingIntent going true - starting is
 * reliable on this rig), but STOPPED off the camera's real reported
 * SystemState.isRecording instead of that same button's stop press -
 * CameraModeController's doc comment documents at length that stopping the
 * DJI camera's recording via software is unreliable here (confirmed on two
 * independent phones): pressing the app's stop button usually does
 * nothing real, and the camera just keeps recording until the physical
 * stop button on the gimbal itself is pressed. Tying this recorder's stop
 * to the app button instead would almost always cut the audio off far
 * earlier than the video it's meant to go with. Watching the real
 * isRecording state means this keeps rolling for as long as the video
 * actually does, however that stop happens - normally the physical
 * button, or on the rare occasion the software stop actually works, that.
 * The eventual playback feature's manual sync offset is the tool for
 * nudging around whatever start-of-clip mismatch still results from all
 * of this.
 *
 * Saved under getExternalFilesDir(DIRECTORY_MUSIC) - app-private external
 * storage that needs no runtime storage permission on any supported API
 * level, rather than MediaStore: these files are only ever meaningful to
 * this app, paired with a specific downloaded video, not something that
 * belongs in the user's shared Music library.
 */
class AudioRecorderController {

    companion object {
        private const val TAG = "AudioRecorderController"
    }

    private val filenameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /**
     * No-op (returns false) for AudioSourceKind.GIMBAL - that choice means
     * "don't record phone-side audio at all", not a device to resolve.
     * Also a no-op if already recording (start/stop are meant to track a
     * single video recording 1:1).
     */
    fun start(context: Context, kind: AudioSourceKind): Boolean {
        if (kind == AudioSourceKind.GIMBAL || _isRecording.value) return false

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SiderealAudio")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "start: couldn't create $dir")
            return false
        }
        val file = File(dir, "audio_${filenameFormat.format(Date())}.m4a")

        val preferredDevice = when (kind) {
            AudioSourceKind.PHONE_MIC -> AudioSourceController.builtInMic(context)
            AudioSourceKind.BLUETOOTH -> AudioSourceController.connectedBluetoothMic(context)
                ?: AudioSourceController.builtInMic(context).also {
                    Log.w(TAG, "start: no Bluetooth mic connected anymore, falling back to phone mic")
                }
            AudioSourceKind.GIMBAL -> null
        }

        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            // MediaRecorder.setPreferredDevice needs API 28 - below that,
            // this just uses whatever the system's default input routing
            // picks (unset, not a crash risk).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredDevice != null) {
                mr.setPreferredDevice(preferredDevice)
            }
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            _isRecording.value = true
            Log.d(TAG, "start: recording $kind to $file")
            true
        } catch (e: Exception) {
            Log.w(TAG, "start: failed to start MediaRecorder: $e")
            mr.release()
            false
        }
    }

    /** Returns the finished file, or null if nothing was recording or it failed to finalize. */
    fun stop(): File? {
        val mr = recorder ?: return null
        recorder = null
        _isRecording.value = false
        val file = outputFile
        outputFile = null
        return try {
            mr.stop()
            mr.release()
            file
        } catch (e: Exception) {
            Log.w(TAG, "stop: failed to finalize recording: $e")
            file?.delete()
            null
        }
    }
}
