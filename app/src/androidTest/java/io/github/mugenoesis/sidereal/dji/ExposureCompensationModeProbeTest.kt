package io.github.mugenoesis.sidereal.dji

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented (on-device, real-hardware) probe for a user report: EV
 * compensation can't be changed while the camera is in APERTURE_PRIORITY,
 * SHUTTER_PRIORITY, or MANUAL exposure mode - only PROGRAM. ExposureController
 * .isEvEditableByName currently assumes all four (PASM) allow it, per the
 * general P/A/S/M photography spec this app was built against - but that
 * assumption was explicitly flagged in its doc comment as unverified for
 * MANUAL specifically, and never checked at all for A/S. This logs the real
 * per-mode result instead of guessing from ambiguous historical app logs.
 *
 * Run via: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.mugenoesis.sidereal.dji.ExposureCompensationModeProbeTest
 */
@RunWith(AndroidJUnit4::class)
class ExposureCompensationModeProbeTest {

    companion object {
        private const val TAG = "EvModeProbe"
    }

    @Test
    fun probeEvAcrossExposureModes() {
        waitUntil(timeoutMs = 20_000, description = "product connected") {
            DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected
        }
        assertTrue(
            "Camera not connected after 20s - is the Osmo powered on and WiFi-bound to this phone?",
            DJIConnectionManager.camera != null
        )

        val apertureAdjustable = DJIConnectionManager.camera?.getLens(0)?.isAdjustableApertureSupported()
        Log.i(TAG, "mounted lens isAdjustableApertureSupported=$apertureAdjustable")

        for (modeName in listOf("PROGRAM", "APERTURE_PRIORITY", "SHUTTER_PRIORITY", "MANUAL")) {
            val modeSetError = callAndAwait { onResult -> RealCameraGateway.setExposureMode(modeName, onResult) }
            Thread.sleep(1500) // let the pushed readout catch up before trusting getExposureMode()
            val confirmedMode = getCurrentExposureMode()
            Log.i(TAG, "setExposureMode($modeName): setError=$modeSetError confirmedMode=$confirmedMode")

            val beforeEv = getCurrentExposureCompensation()
            // Deliberately targets a FIXED value (not "one step up from
            // wherever it happens to be") so success/failure is unambiguous
            // regardless of what EV was left at by a previous iteration.
            val target = if (beforeEv == "P_1_0") "N_1_0" else "P_1_0"
            val setError = callAndAwait { onResult -> RealCameraGateway.setExposureCompensation(target, onResult) }
            Thread.sleep(1500) // give the pushed ExposureSettings callback a real chance to arrive
            val afterEv = getCurrentExposureCompensation()
            Log.i(
                TAG,
                "  [$modeName] setExposureCompensation($target): setError=$setError beforeEv=$beforeEv afterEv=$afterEv " +
                    "actuallyChanged=${afterEv == target}"
            )
        }

        Log.i(TAG, "PROBE COMPLETE")
    }

    private fun getCurrentExposureMode(): String? {
        val camera = DJIConnectionManager.camera ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        camera.getExposureMode(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ExposureMode> {
            override fun onSuccess(mode: SettingsDefinitions.ExposureMode) {
                result = mode.name
                latch.countDown()
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getExposureMode failed: ${error.description}")
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    /** Reads EV back via the SDK's own synchronous-style getter - independent of this app's pushed-readout pipeline, to isolate whether a "successful" set actually took effect on the camera itself. */
    private fun getCurrentExposureCompensation(): String? {
        val camera = DJIConnectionManager.camera ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        camera.getExposureCompensation(object : CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.ExposureCompensation> {
            override fun onSuccess(ev: SettingsDefinitions.ExposureCompensation) {
                result = ev.name
                latch.countDown()
            }

            override fun onFailure(error: DJIError) {
                Log.w(TAG, "getExposureCompensation failed: ${error.description}")
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

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
