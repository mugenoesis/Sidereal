package io.github.mugenoesis.sidereal

import android.content.Context
import android.content.SharedPreferences
import io.github.mugenoesis.sidereal.audio.AudioSourceKind
import io.github.mugenoesis.sidereal.tracking.FaceOverlayView
import io.github.mugenoesis.sidereal.tracking.FollowStyle
import dji.common.camera.SettingsDefinitions

/**
 * Persists the small set of user preferences that live only in this app -
 * deliberately NOT the ones the camera itself already remembers (ISO,
 * white balance, focus mode, sharpness, etc. all persist on the camera's
 * own storage and are re-read fresh on every connect via each
 * controller's refresh()/startObserving()). Re-applying a stale
 * app-remembered value for those here would fight the camera's real
 * current state instead of reflecting it - this only covers choices that
 * have no camera-side equivalent at all.
 *
 * Gimbal mode (Manual vs A->B) is deliberately NOT persisted either -
 * restoring A->B mode with stale captured points from a previous session
 * would be confusing/unsafe (the physical gimbal has moved since), so
 * always starting in Manual is the safer default regardless of what was
 * active last time.
 *
 * Backed by plain SharedPreferences - a handful of scalar values, not
 * enough to justify anything heavier.
 */
object AppPreferences {

    private const val PREFS_NAME = "sidereal_prefs"
    private const val KEY_DEFAULT_TAP_ACTION = "default_tap_action"
    private const val KEY_EXPOSURE_MODE = "exposure_mode"
    private const val KEY_FOLLOW_STYLE = "follow_style"
    private const val KEY_AUTO_ZOOM_ENABLED = "auto_zoom_enabled"
    private const val KEY_MOVE_DURATION_MS = "move_duration_ms"
    private const val KEY_AUDIO_SOURCE_KIND = "audio_source_kind"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_OSMO_WIFI_PASSPHRASE = "osmo_wifi_passphrase"
    private const val KEY_GRID_MODE = "grid_mode"
    private const val KEY_SELF_TIMER_SEC = "self_timer_sec"
    private const val KEY_CAMERA_SOUNDS = "camera_sounds"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var defaultTapAction: FaceOverlayView.TapMode
        get() = prefs.getString(KEY_DEFAULT_TAP_ACTION, null)
            ?.let { runCatching { FaceOverlayView.TapMode.valueOf(it) }.getOrNull() }
            ?: FaceOverlayView.TapMode.TAP_TO_FOCUS
        set(value) = prefs.edit().putString(KEY_DEFAULT_TAP_ACTION, value.name).apply()

    var exposureMode: SettingsDefinitions.ExposureMode
        get() = prefs.getString(KEY_EXPOSURE_MODE, null)
            ?.let { runCatching { SettingsDefinitions.ExposureMode.valueOf(it) }.getOrNull() }
            ?: SettingsDefinitions.ExposureMode.PROGRAM
        set(value) = prefs.edit().putString(KEY_EXPOSURE_MODE, value.name).apply()

    var followStyle: FollowStyle
        get() = prefs.getString(KEY_FOLLOW_STYLE, null)
            ?.let { runCatching { FollowStyle.valueOf(it) }.getOrNull() }
            ?: FollowStyle.LOCKED_ON
        set(value) = prefs.edit().putString(KEY_FOLLOW_STYLE, value.name).apply()

    var autoZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ZOOM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ZOOM_ENABLED, value).apply()

    /** A -> B move duration, milliseconds - MainActivity.moveDurationOptionsMs snaps this to the nearest preset (3/5/10/15/30s) if an old value is stored. */
    var moveDurationMs: Long
        get() = prefs.getLong(KEY_MOVE_DURATION_MS, 3000L)
        set(value) = prefs.edit().putLong(KEY_MOVE_DURATION_MS, value).apply()

    /** Red-only night display - see display/NightMode. Persisted so a restart in the field doesn't blast white light. */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply()

    /** The camera's WiFi password, used to join its network from the app. Defaults to the Osmo's factory value. */
    var osmoWifiPassphrase: String
        get() = prefs.getString(KEY_OSMO_WIFI_PASSPHRASE, null) ?: io.github.mugenoesis.sidereal.dji.OsmoWifiPassphrase.DEFAULT
        set(value) = prefs.edit().putString(KEY_OSMO_WIFI_PASSPHRASE, value).apply()

    var gridMode: io.github.mugenoesis.sidereal.camera.GridMode
        get() = prefs.getString(KEY_GRID_MODE, null)
            ?.let { runCatching { io.github.mugenoesis.sidereal.camera.GridMode.valueOf(it) }.getOrNull() }
            ?: io.github.mugenoesis.sidereal.camera.GridMode.OFF
        set(value) = prefs.edit().putString(KEY_GRID_MODE, value.name).apply()

    /** Photo self-timer delay in seconds; 0 = off. */
    var selfTimerSeconds: Int
        get() = prefs.getInt(KEY_SELF_TIMER_SEC, 0)
        set(value) = prefs.edit().putInt(KEY_SELF_TIMER_SEC, value).apply()

    /** Which camera sounds are on, as CameraSoundSettings.encode() writes it; null = the defaults (all on). */
    var cameraSounds: String?
        get() = prefs.getString(KEY_CAMERA_SOUNDS, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_SOUNDS, value).apply()

    // GIMBAL (no phone-side recording) is the safe default - only a real
    // choice once a mic is actually plugged into the gimbal, which this
    // app can't detect either way. Never restores BLUETOOTH pointing at a
    // no-longer-connected device since only the KIND is persisted, not a
    // specific device identity - see AudioSourceController's doc comment.
    var audioSourceKind: AudioSourceKind
        get() = prefs.getString(KEY_AUDIO_SOURCE_KIND, null)
            ?.let { runCatching { AudioSourceKind.valueOf(it) }.getOrNull() }
            ?: AudioSourceKind.GIMBAL
        set(value) = prefs.edit().putString(KEY_AUDIO_SOURCE_KIND, value.name).apply()
}
