package io.github.mugenoesis.sidereal.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import io.github.mugenoesis.sidereal.wearprotocol.FrameCodec
import io.github.mugenoesis.sidereal.wearprotocol.WearAck
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand
import io.github.mugenoesis.sidereal.wearprotocol.WearPaths
import io.github.mugenoesis.sidereal.wearprotocol.WearProtocol
import io.github.mugenoesis.sidereal.wearprotocol.WearStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The watch's end of the link to the phone: sends [WearCommand]s as messages and receives status, acks and the
 * live-view frame stream the phone opens as a channel. Callbacks arrive on the main thread.
 */
class WatchLink(
    context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (WearStatus) -> Unit,
    private val onAck: (WearAck) -> Unit,
    private val onFrame: (Bitmap) -> Unit
) {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val channels = Wearable.getChannelClient(appContext)
    private val nodes = Wearable.getNodeClient(appContext)

    private var phoneNodeId: String? = null
    private var frameJob: Job? = null

    /** When the last status arrived (elapsedRealtime), for [io.github.mugenoesis.sidereal.wearprotocol.WatchDisplay.STALE_MS]. */
    @Volatile var lastStatusAt = 0L
        private set

    /** Counts a status as just received - for the debug harness, which injects status without a phone. */
    internal fun markStatusReceived() {
        lastStatusAt = SystemClock.elapsedRealtime()
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event -> onMessage(event) }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path != WearPaths.LIVE_CHANNEL) return
            frameJob?.cancel()
            frameJob = scope.launch(Dispatchers.IO) { readFrames(channel) }
        }

        override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
            if (channel.path == WearPaths.LIVE_CHANNEL) frameJob?.cancel()
        }
    }

    fun start() {
        messages.addListener(messageListener)
        channels.registerChannelCallback(channelCallback)
        send(WearCommand.Hello)
    }

    fun stop() {
        messages.removeListener(messageListener)
        channels.unregisterChannelCallback(channelCallback)
        frameJob?.cancel()
    }

    fun send(command: WearCommand) {
        val (path, payload) = WearProtocol.encode(command)
        scope.launch {
            val id = phoneNode() ?: return@launch
            runCatching { messages.sendMessage(id, path, payload).await() }
                .onFailure { Log.w(TAG, "send $path failed: ${it.message}"); phoneNodeId = null }
        }
    }

    /**
     * Asks Wear OS to open Sidereal on the paired phone (the phone app answers [WearPaths.OPEN_PHONE_URI]) - for when
     * the watch hears nothing because the phone app is not running. [onResult] says whether the request was delivered.
     */
    fun openOnPhone(onResult: (Boolean) -> Unit) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            .setData(android.net.Uri.parse(WearPaths.OPEN_PHONE_URI))
        val future = androidx.wear.remote.interactions.RemoteActivityHelper(appContext, java.util.concurrent.Executors.newSingleThreadExecutor())
            .startRemoteActivity(intent)
        future.addListener({
            val ok = try { future.get(); true } catch (e: Exception) { Log.w(TAG, "open on phone failed: ${e.message}"); false }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(ok) }
        }, java.util.concurrent.Executors.newSingleThreadExecutor())
    }

    private suspend fun phoneNode(): String? {
        phoneNodeId?.let { return it }
        val connected = runCatching { nodes.connectedNodes.await() }.getOrNull().orEmpty()
        return (connected.firstOrNull { it.isNearby } ?: connected.firstOrNull())?.id.also { phoneNodeId = it }
    }

    private fun onMessage(event: MessageEvent) {
        when (event.path) {
            WearPaths.STATUS -> WearProtocol.decodeStatus(event.data)?.let {
                lastStatusAt = SystemClock.elapsedRealtime()
                onStatus(it)
            }
            WearPaths.ACK -> WearProtocol.decodeAck(event.data)?.let(onAck)
        }
    }

    private suspend fun readFrames(channel: ChannelClient.Channel) {
        try {
            val input = channels.getInputStream(channel).await()
            val confirmations = channels.getOutputStream(channel).await()
            var received = 0
            while (true) {
                val jpeg = FrameCodec.read(input) ?: break
                // Tell the phone this one has ARRIVED (before decoding it) - that is what keeps the picture current.
                received++
                runCatching { FrameCodec.writeAck(confirmations, received) }.onFailure { Log.w(TAG, "confirmation failed: ${it.message}") }
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                withContext(Dispatchers.Main) { onFrame(bitmap) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "live view ended: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "WatchLink"
    }
}
