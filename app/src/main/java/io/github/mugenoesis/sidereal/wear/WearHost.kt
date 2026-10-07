package io.github.mugenoesis.sidereal.wear

import io.github.mugenoesis.sidereal.wearprotocol.WearStatus

/**
 * What the watch can make the phone app do. Implemented by MainActivity with the same code the touch controls
 * use, so every existing guard still applies. Methods that can be refused return the reason, or null on success.
 */
interface WearHost {
    fun status(): WearStatus
    fun capture(): String?
    fun toggleRecord(): String?
    fun toggleMode(): String?
    fun recenter()
    fun gimbal(yaw: Float, pitch: Float)
    fun setLiveView(on: Boolean)
    fun connectOsmo(): String?
}
