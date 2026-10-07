package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.dji.DJIConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Folds the camera's pushed system/storage state and the battery into the single [CameraStatus] the status strip renders. */
class CameraStatusController(scope: CoroutineScope) {

    val status: StateFlow<CameraStatus> = combine(
        DJIConnectionManager.cameraSystemState,
        DJIConnectionManager.storageState,
        DJIConnectionManager.batteryPercent
    ) { system, storage, battery ->
        CameraStatus(
            batteryPercent = battery,
            cardInserted = storage?.isInserted,
            cardFull = storage?.isFull == true,
            cardError = storage?.hasError() == true,
            photosLeft = storage?.availableCaptureCount,
            recordSecondsLeft = storage?.availableRecordingTimeInSeconds,
            isRecording = system?.isRecording == true,
            recordElapsedSec = system?.currentVideoRecordingTimeInSeconds ?: 0,
            isVideoMode = system?.mode?.name == "RECORD_VIDEO"
        )
    }.stateIn(scope, SharingStarted.Eagerly, CameraStatus())
}
