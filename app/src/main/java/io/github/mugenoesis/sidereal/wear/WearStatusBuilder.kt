package io.github.mugenoesis.sidereal.wear

import io.github.mugenoesis.sidereal.camera.CameraStatus
import io.github.mugenoesis.sidereal.sequence.SequenceProgress
import io.github.mugenoesis.sidereal.sequence.SequenceState
import io.github.mugenoesis.sidereal.wearprotocol.WearCameraMode
import io.github.mugenoesis.sidereal.wearprotocol.WearStatus

/** Flattens the phone's camera status and sequence progress into the small [WearStatus] the watch shows. */
object WearStatusBuilder {

    /** @param sequence the sequence's display name and live progress, or null if none has been started */
    fun build(camera: CameraStatus, connected: Boolean, sequence: Pair<String, SequenceProgress>?): WearStatus {
        val running = sequence?.second?.state.let { it is SequenceState.Running || it is SequenceState.AwaitingUser }
        return WearStatus(
            phoneOnOsmo = connected,
            cameraMode = if (camera.isVideoMode) WearCameraMode.VIDEO else WearCameraMode.PHOTO,
            recording = camera.isRecording,
            recordElapsedSec = camera.recordElapsedSec,
            batteryPercent = camera.batteryPercent ?: -1,
            photosLeft = camera.photosLeft?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
            recordSecondsLeft = camera.recordSecondsLeft ?: -1,
            sequenceRunning = running,
            sequenceLabel = if (running && sequence != null) "${sequence.first} ${sequence.second.capturesDone}/${sequence.second.capturesTotal}" else ""
        )
    }
}
