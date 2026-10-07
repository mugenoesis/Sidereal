package io.github.mugenoesis.sidereal.wear

import io.github.mugenoesis.sidereal.wearprotocol.WearAck
import io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode
import io.github.mugenoesis.sidereal.wearprotocol.WearCommand
import io.github.mugenoesis.sidereal.wearprotocol.WearPaths
import io.github.mugenoesis.sidereal.wearprotocol.WearProtocol

/**
 * Decides what a command from the watch means right now and what to tell the watch back. Pure - the real
 * actions are behind [WearHost] - so the rules are unit-tested:
 *  - camera and gimbal commands need the phone to be on the Osmo's network;
 *  - a running sequence owns the camera and gimbal, so those are refused (a stop is always allowed);
 *  - recording needs video mode, and the mode can't change mid-recording;
 *  - stopping a recording is sent but the watch is told this camera may ignore it;
 *  - continuous gimbal and live-view commands get no ack (they would flood the link).
 * Anything the phone throws becomes a failed ack rather than escaping into the message listener.
 */
class WearCommandHandler(private val host: WearHost) {

    fun handle(command: WearCommand): WearAck? = try {
        dispatch(command)
    } catch (e: Exception) {
        WearAck(WearProtocol.encode(command).first, ok = false, message = e.message ?: "The phone hit an error")
    }

    private fun dispatch(command: WearCommand): WearAck? {
        val path = WearProtocol.encode(command).first
        val status = host.status()
        return when (command) {
            WearCommand.Hello -> WearAck(path, true, "")
            is WearCommand.LiveView -> { host.setLiveView(command.on); null }
            WearCommand.ConnectOsmo -> host.connectOsmo().let { WearAck(path, it == null, it.orEmpty()) }
            is WearCommand.Gimbal -> {
                val stopping = command.yaw == 0f && command.pitch == 0f
                if (stopping || (status.phoneOnOsmo && !status.sequenceRunning)) host.gimbal(command.yaw, command.pitch)
                null
            }
            WearCommand.Capture, WearCommand.ToggleRecord, WearCommand.ToggleMode, WearCommand.Recenter -> {
                if (!status.phoneOnOsmo) return WearAck(path, false, "The phone isn't connected to the Osmo")
                if (status.sequenceRunning) {
                    return WearAck(path, false, "A sequence is running (${status.sequenceLabel}) - stop it on the phone first")
                }
                when (command) {
                    WearCommand.Capture -> host.capture().let { WearAck(path, it == null, it.orEmpty()) }
                    WearCommand.ToggleRecord -> toggleRecord(path, status.cameraMode, status.recording)
                    WearCommand.ToggleMode ->
                        if (status.recording) WearAck(path, false, "Can't switch mode while recording")
                        else host.toggleMode().let { WearAck(path, it == null, it.orEmpty()) }
                    else -> { host.recenter(); WearAck(path, true, "") }
                }
            }
        }
    }

    private fun toggleRecord(path: String, mode: WearCameraMode, recording: Boolean): WearAck {
        if (mode != WearCameraMode.VIDEO) return WearAck(path, false, "Switch to video mode first")
        val error = host.toggleRecord()
        if (error != null) return WearAck(path, false, error)
        val note = if (recording) "Stop sent - this camera may ignore it; use its own record button if it keeps going" else ""
        return WearAck(path, true, note)
    }
}
