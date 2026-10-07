package io.github.mugenoesis.sidereal.dji

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device probe: what do SystemState's photo flags actually do around a
 * photo, fast and long exposure? The answer decides how
 * RealSequenceHost.capture() knows a frame is finished - guessing that is
 * how sequences end up firing the shutter on top of a still-writing frame.
 * Read logcat tag "PhotoProbe".
 *
 * Run: ./gradlew connectedDebugAndroidTest --tests "*.PhotoCaptureProbeTest"
 */
@RunWith(AndroidJUnit4::class)
class PhotoCaptureProbeTest {

    private val tag = "PhotoProbe"

    @Test
    fun probePhotoFlags() {
        waitUntil(20_000) { DJIConnectionManager.connectionState.value is DJIConnectionManager.ConnectionState.ProductConnected }
        assertTrue("Osmo not connected", DJIConnectionManager.camera != null)

        call { RealCameraGateway.setCameraMode("SHOOT_PHOTO", it) }
        waitUntil(10_000) { DJIConnectionManager.cameraSystemState.value?.mode?.name == "SHOOT_PHOTO" }
        call { RealCameraGateway.setExposureMode("MANUAL", it) }

        for (shutter in listOf("SHUTTER_SPEED_1_100", "SHUTTER_SPEED_3")) {
            Log.i(tag, "setShutter($shutter) -> ${call { RealCameraGateway.setShutterSpeed(shutter, it) }}")
            Thread.sleep(1000)
            val t0 = System.currentTimeMillis()
            val err = call { RealCameraGateway.startShootPhoto(it) }
            Log.i(tag, "[$shutter] startShootPhoto callback after ${System.currentTimeMillis() - t0}ms error=$err")
            var last = ""
            val end = t0 + 12_000
            while (System.currentTimeMillis() < end) {
                val s = DJIConnectionManager.cameraSystemState.value
                val now = "shooting=${s?.isShootingSinglePhoto} storing=${s?.isStoringPhoto} raw=${s?.isShootingSinglePhotoInRAWFormat}"
                if (now != last) {
                    Log.i(tag, "[$shutter] t+${System.currentTimeMillis() - t0}ms $now")
                    last = now
                }
                Thread.sleep(20)
            }
        }
        call { RealCameraGateway.setExposureMode("PROGRAM", it) }
        Log.i(tag, "PROBE COMPLETE")
    }

    private fun call(action: (onResult: (String?) -> Unit) -> Unit): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        action { result = it; latch.countDown() }
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(200) }
    }
}
