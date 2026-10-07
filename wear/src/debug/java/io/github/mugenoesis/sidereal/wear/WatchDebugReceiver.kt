package io.github.mugenoesis.sidereal.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.mugenoesis.sidereal.wearprotocol.WearAck
import io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode
import io.github.mugenoesis.sidereal.wearprotocol.WearStatus

/**
 * adb shell am broadcast -n io.github.mugenoesis.sidereal/.wear.WatchDebugReceiver \
 *     -a io.github.mugenoesis.sidereal.wear.DEBUG --es state ready|recording|video|offwifi|sequence|none [--ez live true] [--es ack text]
 */
class WatchDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val activity = WatchActivity.active ?: return
        val base = WearStatus.UNKNOWN.copy(phoneOnOsmo = true, cameraMode = WearCameraMode.PHOTO, batteryPercent = 82, photosLeft = 13_716, recordSecondsLeft = 12_173)
        val status = when (intent.getStringExtra("state")) {
            "ready" -> base
            "video" -> base.copy(cameraMode = WearCameraMode.VIDEO)
            "recording" -> base.copy(cameraMode = WearCameraMode.VIDEO, recording = true, recordElapsedSec = 75)
            "offwifi" -> base.copy(phoneOnOsmo = false)
            "sequence" -> base.copy(sequenceRunning = true, sequenceLabel = "Panorama 3/9")
            else -> null
        }
        val live = if (intent.hasExtra("live")) intent.getBooleanExtra("live", false) else null
        val frame = if (live == true) testPicture() else null
        val ack = intent.getStringExtra("ack")?.let { WearAck("/cmd/capture", ok = false, message = it) }
        activity.runOnUiThread { activity.debugShow(status, live, ack, frame) }
    }

    /** A recognisable synthetic frame: sky-to-ground gradient with a bright "star" off centre. */
    private fun testPicture(): Bitmap {
        val bitmap = Bitmap.createBitmap(280, 158, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until 158) {
            paint.color = Color.rgb(10 + y / 3, 20 + y / 2, 60 + y / 2)
            canvas.drawLine(0f, y.toFloat(), 280f, y.toFloat(), paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(190f, 50f, 6f, paint)
        return bitmap
    }
}
