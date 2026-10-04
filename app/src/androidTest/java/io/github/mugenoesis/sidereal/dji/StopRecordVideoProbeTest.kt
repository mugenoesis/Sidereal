package io.github.mugenoesis.sidereal.dji

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented (on-device, real-hardware) probe for the stop-recording
 * mystery documented in CameraModeController's doc comment. Unlike the
 * app/src/test JVM unit tests, this runs under real ART with a real
 * DJISDKManager/Camera - registration happens automatically via
 * SiderealApp.onCreate() the same way it does in the real app, since
 * instrumented tests run inside the app-under-test's own process.
 *
 * This only produces useful output with the Osmo actually connected and
 * WiFi-bound at run time. It's not a pass/fail regression test - the whole
 * point is to log the REAL timestamped sequence of SDK callback results and
 * SystemState/StorageState changes around a real stop-record command, since
 * that's exactly the data extensive manual testing was working from before.
 * Read the logcat output (tag "StopRecordProbe") after running this.
 *
 * Run via: ./gradlew connectedDebugAndroidTest --tests "*.StopRecordVideoProbeTest"
 * (needs the device connected via adb, same as any instrumented test).
 */
@RunWith(AndroidJUnit4::class)
class StopRecordVideoProbeTest {

    companion object {
        private const val TAG = "StopRecordProbe"
    }

    @Test
    fun probeStopRecordVideoBehavior() {
        waitUntil(timeoutMs = 20_000, description = "product connected") {
            DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected
        }
        assertTrue(
            "Camera not connected after 20s - is the Osmo powered on and WiFi-bound to this phone?",
            DJIConnectionManager.camera != null
        )

        logState("initial")

        val modeError = callAndAwait { onResult -> RealCameraGateway.setCameraMode("RECORD_VIDEO", onResult) }
        Log.i(TAG, "setCameraMode(RECORD_VIDEO) callback: error=$modeError")
        waitUntil(timeoutMs = 10_000, description = "camera reports RECORD_VIDEO mode") {
            DJIConnectionManager.cameraSystemState.value?.mode?.name == "RECORD_VIDEO"
        }
        logState("after mode switch")

        val startError = callAndAwait { onResult -> RealCameraGateway.startRecordVideo(onResult) }
        Log.i(TAG, "startRecordVideo callback: error=$startError")

        waitUntil(timeoutMs = 10_000, description = "isRecording becomes true") {
            DJIConnectionManager.cameraSystemState.value?.isRecording == true
        }
        logState("isRecording confirmed true")

        // Let it actually record a few real seconds so this is a genuine
        // clip, not an instant start/stop.
        Thread.sleep(5000)
        logState("after 5s of recording")

        val stopCalledAt = System.currentTimeMillis()
        val stopError = callAndAwait { onResult -> RealCameraGateway.stopRecordVideo(onResult) }
        val callbackElapsedMs = System.currentTimeMillis() - stopCalledAt
        Log.i(TAG, "stopRecordVideo callback: error=$stopError, callbackElapsedMs=$callbackElapsedMs")
        logState("immediately after stop callback")

        // The actual question this probe exists to answer: does
        // isRecording ever flip back to false on its own after a
        // successful-looking stop callback, and if so how long does it
        // take? Polling and logging every transition for 30s, rather than
        // asserting a fixed expectation, since the answer itself is the
        // point - not a known-good value to check against.
        val pollDeadline = System.currentTimeMillis() + 30_000
        var lastRecording = DJIConnectionManager.cameraSystemState.value?.isRecording
        Log.i(TAG, "isRecording at t+0ms since stop() = $lastRecording")
        while (System.currentTimeMillis() < pollDeadline) {
            val current = DJIConnectionManager.cameraSystemState.value?.isRecording
            if (current != lastRecording) {
                Log.i(TAG, "isRecording changed: $lastRecording -> $current at t+${System.currentTimeMillis() - stopCalledAt}ms since stop()")
                lastRecording = current
            }
            Thread.sleep(500)
        }

        logState("final, 30s after stop() called")
        Log.i(TAG, "PROBE COMPLETE - final isRecording=${DJIConnectionManager.cameraSystemState.value?.isRecording}")
    }

    private fun logState(label: String) {
        val sys = DJIConnectionManager.cameraSystemState.value
        val storage = DJIConnectionManager.storageState.value
        Log.i(
            TAG,
            "[$label] isRecording=${sys?.isRecording} mode=${sys?.mode} recTimeSec=${sys?.currentVideoRecordingTimeInSeconds} " +
                "hasError=${sys?.hasError()} overheating=${sys?.isOverheating}"
        )
        Log.i(
            TAG,
            "[$label] storage: isFull=${storage?.isFull} hasError=${storage?.hasError()} isReadOnly=${storage?.isReadOnly} " +
                "remainingMB=${storage?.getRemainingSpaceInMB()} availRecSec=${storage?.getAvailableRecordingTimeInSeconds()}"
        )
    }

    /** Bridges an async CameraGateway-style callback into a blocking call - fine here since instrumented tests already run off the main thread. */
    private fun callAndAwait(action: (onResult: (String?) -> Unit) -> Unit): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        action { error ->
            result = error
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        Log.w(TAG, "waitUntil timed out waiting for: $description")
    }
}
