package io.github.mugenoesis.sidereal.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.github.mugenoesis.sidereal.AppPreferences

/** Which microphone the phone-side audio recording (see AudioRecorderController) should use. */
enum class AudioSourceKind { GIMBAL, PHONE_MIC, BLUETOOTH }

/**
 * Enumerates and resolves which microphone should feed the phone-side
 * audio recording that runs alongside video capture - see
 * AudioRecorderController's doc comment for why this exists at all (no mic
 * plugged into the gimbal on this rig, and the DJI SDK has no way to
 * inject phone-captured audio into the camera's own recording anyway).
 *
 * GIMBAL is the default and means "don't record phone-side audio" - it's
 * only a real option once a mic actually gets plugged into the gimbal
 * itself, which this controller has no visibility into either way; it
 * exists purely so recording-alongside-video isn't the default behavior
 * for a rig that doesn't need it.
 *
 * AudioDeviceInfo instances aren't stable across app runs or even across a
 * Bluetooth device reconnecting, so only the KIND is persisted
 * (AppPreferences.audioSourceKind) - connectedBluetoothMic()/builtInMic()
 * re-resolve the live device fresh every time, and BLUETOOTH silently
 * isn't offered as a cycle option at all unless something is actually
 * connected right now.
 */
object AudioSourceController {

    /** GIMBAL and PHONE_MIC are always offered; BLUETOOTH only when a Bluetooth mic is currently connected. */
    fun availableKinds(context: Context): List<AudioSourceKind> {
        val kinds = mutableListOf(AudioSourceKind.GIMBAL, AudioSourceKind.PHONE_MIC)
        if (connectedBluetoothMic(context) != null) kinds += AudioSourceKind.BLUETOOTH
        return kinds
    }

    fun connectedBluetoothMic(context: Context): AudioDeviceInfo? {
        val devices = inputDevices(context) ?: return null
        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun builtInMic(context: Context): AudioDeviceInfo? =
        inputDevices(context)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun inputDevices(context: Context): Array<AudioDeviceInfo>? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    }

    fun label(context: Context, kind: AudioSourceKind): String = when (kind) {
        AudioSourceKind.GIMBAL -> "Gimbal"
        AudioSourceKind.PHONE_MIC -> "Phone mic"
        AudioSourceKind.BLUETOOTH -> {
            // productName needs BLUETOOTH_CONNECT (API 31+) to return the
            // real paired-device name - falls back to a generic label
            // rather than failing if that's not granted or the lookup
            // otherwise throws.
            val device = connectedBluetoothMic(context)
            val name = device?.let { runCatching { it.productName?.toString() }.getOrNull() }?.takeIf { it.isNotBlank() }
            name ?: "Bluetooth mic"
        }
    }

    var selectedKind: AudioSourceKind
        get() = AppPreferences.audioSourceKind
        set(value) { AppPreferences.audioSourceKind = value }
}
