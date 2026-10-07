package io.github.mugenoesis.sidereal.wear

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import io.github.mugenoesis.sidereal.wearprotocol.FrameCodec
import io.github.mugenoesis.sidereal.wearprotocol.LivePacer
import io.github.mugenoesis.sidereal.wearprotocol.PushBackoff
import io.github.mugenoesis.sidereal.wearprotocol.Thumbnail
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand
import io.github.mugenoesis.sidereal.wearprotocol.WearPaths
import io.github.mugenoesis.sidereal.wearprotocol.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * The phone end of the watch link, on Google's Wearable Data Layer. The phone remains the only thing that talks
 * to the Osmo; the watch sends [WearCommand]s (small messages), receives a [io.github.mugenoesis.sidereal.wearprotocol.WearStatus]
 * a couple of times a second plus an ack per command, and - only while its live view is open - a stream of
 * small JPEG frames over a channel. Nothing is ever pushed over Bluetooth that the watch did not ask for.
 *
 * Lives as long as MainActivity is on screen: the controllers that actually drive the camera live there, so
 * when the app is closed the watch simply stops receiving status and shows "open Sidereal on your phone".
 */
class WearBridge(
    context: Context,
    private val scope: CoroutineScope,
    private val host: WearHost
) {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val channels = Wearable.getChannelClient(appContext)
    private val nodes = Wearable.getNodeClient(appContext)
    private val handler = WearCommandHandler(host)

    private val listener = MessageClient.OnMessageReceivedListener { onMessage(it) }
    private var statusJob: Job? = null
    private var deadManJob: Job? = null
    private val deadMan = GimbalDeadMan()

    private val frameThread = Executors.newSingleThreadExecutor { Thread(it, "wear-frames") }
    private val backoff = PushBackoff()
    // Paced by the watch's delivery confirmations, not by when a write returns (see LivePacer). Guarded by itself:
    // frames are offered from the preview thread, confirmations arrive on their own reader thread.
    private val pacerLock = Any()
    private var pacer = LivePacer(minIntervalMs = FRAME_INTERVAL_MS)
    private var framesInWindow = 0
    private var bytesInWindow = 0L
    private var windowStartedAt = 0L
    @Volatile private var liveStream: OutputStream? = null
    @Volatile private var liveChannel: ChannelClient.Channel? = null

    fun start() {
        active = this
        messages.addListener(listener)
        deadManJob = scope.launch {
            while (isActive) {
                delay(DEAD_MAN_CHECK_MS)
                if (deadMan.shouldStop(SystemClock.elapsedRealtime())) {
                    Log.w(TAG, "no word from the watch while the gimbal was turning - stopping it")
                    host.gimbal(0f, 0f)
                }
            }
        }
        statusJob = scope.launch {
            while (isActive) {
                pushStatus()
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (active === this) active = null
        messages.removeListener(listener)
        statusJob?.cancel()
        deadManJob?.cancel()
        closeLiveView()
        frameThread.shutdown()
    }

    private fun onMessage(event: MessageEvent) {
        if (!event.path.startsWith(WearPaths.COMMAND_PREFIX)) return
        val command = WearProtocol.decode(event.path, event.data)
        Log.d(TAG, "command from the watch: ${event.path} -> $command")
        if (command == null) return
        if (command is WearCommand.Gimbal) deadMan.onRate(SystemClock.elapsedRealtime(), command.yaw, command.pitch)
        if (command is WearCommand.LiveView) {
            if (command.on) openLiveView(event.sourceNodeId) else closeLiveView()
        }
        val ack = handleCommand(command)
        if (ack != null) messages.sendMessage(event.sourceNodeId, WearPaths.ACK, WearProtocol.encodeAck(ack))
    }

    /** Runs one decoded command and returns the ack to send back (null for the streaming ones). Also used by the debug harness. */
    fun handleCommand(command: WearCommand): io.github.mugenoesis.sidereal.wearprotocol.WearAck? {
        val ack = handler.handle(command)
        if (command == WearCommand.Hello) scope.launch { pushStatus() }
        return ack
    }

    fun statusSnapshot() = host.status()

    private suspend fun pushStatus() {
        val now = SystemClock.elapsedRealtime()
        if (!backoff.shouldTry(now)) return
        runCatching {
            val connected = nodes.connectedNodes.await()
            backoff.onSuccess()
            if (connected.isEmpty()) return
            val payload = WearProtocol.encodeStatus(host.status())
            for (node in connected) messages.sendMessage(node.id, WearPaths.STATUS, payload)
        }.onFailure { error ->
            if ((error as? ApiException)?.statusCode == WEARABLE_API_UNAVAILABLE) {
                // No Wear OS service on this phone (e.g. no watch ever paired): say so once, then check only occasionally.
                if (backoff.onUnavailable(now)) Log.i(TAG, "no Wear OS service on this phone - the watch link is idle")
            } else {
                backoff.onFailure(now)
                Log.w(TAG, "status push failed: ${error.message}")
            }
        }
    }

    private fun openLiveView(nodeId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                closeLiveView()
                val channel = channels.openChannel(nodeId, WearPaths.LIVE_CHANNEL).await()
                liveStream = channels.getOutputStream(channel).await()
                liveChannel = channel
                synchronized(pacerLock) { pacer = LivePacer(minIntervalMs = FRAME_INTERVAL_MS); framesInWindow = 0; bytesInWindow = 0; windowStartedAt = SystemClock.elapsedRealtime() }
                Log.i(TAG, "live view channel open to $nodeId")
                readConfirmations(channels.getInputStream(channel).await())
            }.onFailure { Log.w(TAG, "couldn't open live view: ${it.message}") }
        }
    }

    private fun closeLiveView() {
        val stream = liveStream
        val channel = liveChannel
        liveStream = null
        liveChannel = null
        runCatching { stream?.close() }
        if (channel != null) channels.close(channel)
    }

    /** The watch confirms every frame it receives with a running count; that, not the write, is what paces the next one. */
    private fun readConfirmations(input: java.io.InputStream) {
        try {
            while (true) {
                val count = FrameCodec.readAck(input) ?: break
                val now = SystemClock.elapsedRealtime()
                synchronized(pacerLock) {
                    pacer.onAck(now, count)
                    if (now - windowStartedAt >= STATS_WINDOW_MS) {
                        val seconds = (now - windowStartedAt) / 1000.0
                        Log.i(TAG, "live view: %.1f fps, %.0f KB/s, round trip %.0f ms, quality level %d (%dpx q%d)".format(
                            framesInWindow / seconds, bytesInWindow / 1024.0 / seconds, pacer.smoothedRttMs ?: 0.0,
                            pacer.level, pacer.quality.maxSide, pacer.quality.encodeQuality
                        ))
                        framesInWindow = 0
                        bytesInWindow = 0
                        windowStartedAt = now
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "live view confirmations ended: ${e.message}")
        }
    }

    /** Call with each preview frame (any thread); cheap unless the watch's live view is open and a frame is due. */
    fun offerFrame(source: Bitmap) {
        val stream = liveStream ?: return
        val now = SystemClock.elapsedRealtime()
        val quality = synchronized(pacerLock) {
            if (!pacer.shouldSend(now)) return
            pacer.onSent(now)
            pacer.quality
        }
        val (w, h) = Thumbnail.fit(source.width, source.height, quality.maxSide)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        frameThread.execute {
            try {
                // WebP carries the same picture in about 40% fewer bytes than JPEG, and the watch link is the bottleneck
                // (measured: ~13 KB/s). Phones before Android 11 only have JPEG, so give it a little more quality.
                val encoded = ByteArrayOutputStream().also {
                    if (Build.VERSION.SDK_INT >= 30) small.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality.encodeQuality, it)
                    else small.compress(Bitmap.CompressFormat.JPEG, (quality.encodeQuality + 12).coerceAtMost(90), it)
                }.toByteArray()
                val jpeg = encoded
                FrameCodec.write(stream, jpeg)
                synchronized(pacerLock) { framesInWindow++; bytesInWindow += jpeg.size }
            } catch (e: Exception) {
                Log.w(TAG, "frame send failed, closing live view: ${e.message}")
                closeLiveView()
            } finally {
                small.recycle()
            }
        }
    }

    val liveViewOpen: Boolean get() = liveStream != null

    companion object {
        /** The running bridge (while MainActivity is on screen), for the debug harness. */
        @Volatile var active: WearBridge? = null

        private const val TAG = "WearBridge"
        private const val WEARABLE_API_UNAVAILABLE = 17 // ConnectionResult.API_UNAVAILABLE
        private const val STATUS_INTERVAL_MS = 1_500L
        private const val DEAD_MAN_CHECK_MS = 150L
        private const val FRAME_INTERVAL_MS = 60L        // ~16 fps at best, if the link keeps up
        private const val STATS_WINDOW_MS = 5_000L
    }
}
