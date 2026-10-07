package io.github.mugenoesis.sidereal.sequence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.mugenoesis.sidereal.MainActivity
import io.github.mugenoesis.sidereal.R

/**
 * Keeps a long sequence alive while the phone is in a pocket or the screen is off: a foreground service (so Android
 * does not kill the process), a partial wake lock (so the CPU keeps running the sequence's timing) and a
 * low-latency (high-performance before Android 10) WiFi lock (so the radio does not power-save away the camera link). All three exist only while a
 * sequence is running; the notification shows progress and has a Stop button.
 *
 * The service type is connectedDevice, not dataSync: the camera IS a connected device, and Android 15 cuts dataSync
 * services off after six hours, which would end exactly the overnight runs this is for.
 */
class SequenceKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SEQUENCE -> {
                onStopRequested?.invoke()
                return START_NOT_STICKY
            }
            ACTION_END -> {
                release()
                val title = intent.getStringExtra(EXTRA_TITLE)
                if (title != null) {
                    // Leave how it ended where the user will find it - the point of an overnight run. It needs its own
                    // id: the foreground notification is cancelled with the service, and so would a reused one.
                    stopForeground(true)
                    ensureChannel()
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(OUTCOME_NOTIFICATION_ID, build(
                        SequenceNotificationContent(title, intent.getStringExtra(EXTRA_TEXT).orEmpty(), 100, false), ongoing = false
                    ))
                } else {
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> { // ACTION_SHOW: start, or update the text of the running one
                val content = SequenceNotificationContent(
                    intent?.getStringExtra(EXTRA_TITLE) ?: "Sequence running",
                    intent?.getStringExtra(EXTRA_TEXT).orEmpty(),
                    intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0,
                    intent?.getBooleanExtra(EXTRA_INDETERMINATE, true) ?: true
                )
                ensureChannel()
                val notification = build(content)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                acquire()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun acquire() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // The timeout is only a backstop against a lock leaked by a crash; normal release is explicit.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sidereal:sequence").apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm?.createWifiLock(mode, "sidereal:sequence")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        Log.i(TAG, "keeping the sequence alive (wake lock + wifi lock)")
    }

    private fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Running sequences", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a timelapse or other sequence is shooting, so Android keeps it running"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun build(content: SequenceNotificationContent, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SequenceKeepAliveService::class.java).setAction(ACTION_STOP_SEQUENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_sequence_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setProgress(if (ongoing) 100 else 0, content.percent, ongoing && content.indeterminate)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (ongoing) addAction(Notification.Action.Builder(null, "Stop", stop).build()) }
            .build()
    }

    companion object {
        private const val TAG = "SequenceKeepAlive"
        private const val CHANNEL_ID = "sequence_running"
        private const val NOTIFICATION_ID = 4201
        private const val OUTCOME_NOTIFICATION_ID = 4202
        private const val MAX_HOLD_MS = 14L * 60 * 60 * 1000

        const val ACTION_SHOW = "io.github.mugenoesis.sidereal.SEQUENCE_SHOW"
        const val ACTION_END = "io.github.mugenoesis.sidereal.SEQUENCE_END"
        const val ACTION_STOP_SEQUENCE = "io.github.mugenoesis.sidereal.SEQUENCE_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_INDETERMINATE = "indeterminate"

        /** Called when the user taps Stop in the notification; wired to the sequence controller by the feature. */
        @Volatile var onStopRequested: (() -> Unit)? = null

        /** Starts the service, or refreshes the text of the running one. Safe to call on every progress change. */
        fun show(context: Context, content: SequenceNotificationContent) {
            val intent = Intent(context, SequenceKeepAliveService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_TITLE, content.title)
                .putExtra(EXTRA_TEXT, content.text)
                .putExtra(EXTRA_PERCENT, content.percent)
                .putExtra(EXTRA_INDETERMINATE, content.indeterminate)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Stops the service; with [outcome] the notification stays behind, saying how the sequence ended. */
        fun end(context: Context, outcome: SequenceNotificationContent? = null) {
            val intent = Intent(context, SequenceKeepAliveService::class.java).setAction(ACTION_END)
            outcome?.let { intent.putExtra(EXTRA_TITLE, it.title).putExtra(EXTRA_TEXT, it.text) }
            context.startService(intent)
        }
    }
}
