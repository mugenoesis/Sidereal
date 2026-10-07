package io.github.mugenoesis.sidereal.wearprotocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * How the watch and the phone talk. The phone stays the only thing that talks to the Osmo - the watch is a thin
 * remote that sends [WearCommand]s, receives [WearStatus] and [WearAck]s, and (optionally) a stream of small
 * JPEG frames for a live view. Commands are tiny one-shot messages; frames go over a channel so nothing is
 * stored on either device.
 */
object WearPaths {
    /**
     * What the watch asks the phone to open when the phone app is not running: a link the phone's main screen
     * answers (see its manifest). Wear OS's remote-activity request carries it to the phone.
     */
    const val OPEN_PHONE_SCHEME = "sidereal"
    const val OPEN_PHONE_URI = "$OPEN_PHONE_SCHEME://open"


    // watch -> phone (MessageClient)
    const val CAPTURE = "/cmd/capture"
    const val TOGGLE_RECORD = "/cmd/toggle_record"
    const val TOGGLE_MODE = "/cmd/toggle_mode"
    const val CONNECT_OSMO = "/cmd/connect_osmo"
    const val HELLO = "/cmd/hello"
    const val RECENTER = "/cmd/recenter"
    const val GIMBAL = "/cmd/gimbal"
    const val LIVE_VIEW = "/cmd/liveview"

    // phone -> watch (MessageClient)
    const val STATUS = "/status"
    const val ACK = "/ack"

    // phone -> watch (ChannelClient)
    const val LIVE_CHANNEL = "/liveview"

    const val COMMAND_PREFIX = "/cmd/"
}

sealed class WearCommand {
    object Capture : WearCommand()
    object ToggleRecord : WearCommand()
    object ToggleMode : WearCommand()
    object ConnectOsmo : WearCommand()
    object Hello : WearCommand()
    object Recenter : WearCommand()

    /** Gimbal rates -1..1: positive yaw pans right, positive pitch tilts up; (0, 0) stops. */
    data class Gimbal(val yaw: Float, val pitch: Float) : WearCommand()

    data class LiveView(val on: Boolean) : WearCommand()
}

enum class WearCameraMode { PHOTO, VIDEO, OTHER }

/** What the watch shows. -1 means "not known" for the numeric fields. */
data class WearStatus(
    val phoneOnOsmo: Boolean,
    val cameraMode: WearCameraMode,
    val recording: Boolean,
    val recordElapsedSec: Int,
    val batteryPercent: Int,
    val photosLeft: Int,
    val recordSecondsLeft: Int,
    val sequenceRunning: Boolean,
    val sequenceLabel: String
) {
    companion object {
        val UNKNOWN = WearStatus(false, WearCameraMode.OTHER, false, 0, -1, -1, -1, false, "")
    }
}

/** The phone's answer to a command: which one, whether it worked, and why not if it didn't. */
data class WearAck(val commandPath: String, val ok: Boolean, val message: String)

object WearProtocol {

    private const val STATUS_VERSION: Byte = 1

    fun encode(command: WearCommand): Pair<String, ByteArray> = when (command) {
        WearCommand.Capture -> WearPaths.CAPTURE to ByteArray(0)
        WearCommand.ToggleRecord -> WearPaths.TOGGLE_RECORD to ByteArray(0)
        WearCommand.ToggleMode -> WearPaths.TOGGLE_MODE to ByteArray(0)
        WearCommand.ConnectOsmo -> WearPaths.CONNECT_OSMO to ByteArray(0)
        WearCommand.Hello -> WearPaths.HELLO to ByteArray(0)
        WearCommand.Recenter -> WearPaths.RECENTER to ByteArray(0)
        is WearCommand.Gimbal -> WearPaths.GIMBAL to ByteBuffer.allocate(8)
            .putFloat(command.yaw.coerceIn(-1f, 1f))
            .putFloat(command.pitch.coerceIn(-1f, 1f))
            .array()
        is WearCommand.LiveView -> WearPaths.LIVE_VIEW to byteArrayOf(if (command.on) 1 else 0)
    }

    /** Null for a path that isn't one of ours or a payload of the wrong shape - never throws. */
    fun decode(path: String, payload: ByteArray): WearCommand? = when (path) {
        WearPaths.CAPTURE -> WearCommand.Capture
        WearPaths.TOGGLE_RECORD -> WearCommand.ToggleRecord
        WearPaths.TOGGLE_MODE -> WearCommand.ToggleMode
        WearPaths.CONNECT_OSMO -> WearCommand.ConnectOsmo
        WearPaths.HELLO -> WearCommand.Hello
        WearPaths.RECENTER -> WearCommand.Recenter
        WearPaths.GIMBAL -> if (payload.size == 8) {
            val buffer = ByteBuffer.wrap(payload)
            WearCommand.Gimbal(buffer.float.coerceIn(-1f, 1f), buffer.float.coerceIn(-1f, 1f))
        } else null
        WearPaths.LIVE_VIEW -> if (payload.size == 1) WearCommand.LiveView(payload[0].toInt() != 0) else null
        else -> null
    }

    fun encodeStatus(status: WearStatus): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(STATUS_VERSION.toInt())
            out.writeBoolean(status.phoneOnOsmo)
            out.writeByte(status.cameraMode.ordinal)
            out.writeBoolean(status.recording)
            out.writeInt(status.recordElapsedSec)
            out.writeInt(status.batteryPercent)
            out.writeInt(status.photosLeft)
            out.writeInt(status.recordSecondsLeft)
            out.writeBoolean(status.sequenceRunning)
            out.writeUTF(status.sequenceLabel)
        }
        return bytes.toByteArray()
    }

    fun decodeStatus(payload: ByteArray): WearStatus? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            if (input.readByte() != STATUS_VERSION) return null
            WearStatus(
                phoneOnOsmo = input.readBoolean(),
                cameraMode = WearCameraMode.values()[input.readByte().toInt()],
                recording = input.readBoolean(),
                recordElapsedSec = input.readInt(),
                batteryPercent = input.readInt(),
                photosLeft = input.readInt(),
                recordSecondsLeft = input.readInt(),
                sequenceRunning = input.readBoolean(),
                sequenceLabel = input.readUTF()
            )
        }
    }.getOrNull()

    fun encodeAck(ack: WearAck): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(ack.commandPath)
            out.writeBoolean(ack.ok)
            out.writeUTF(ack.message)
        }
        return bytes.toByteArray()
    }

    fun decodeAck(payload: ByteArray): WearAck? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            WearAck(input.readUTF(), input.readBoolean(), input.readUTF())
        }
    }.getOrNull()
}
