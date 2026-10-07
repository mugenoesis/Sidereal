package io.github.mugenoesis.sidereal.sync

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses `MediaMetadataRetriever.METADATA_KEY_DATE` ("20261007T101500.000Z", UTC) into epoch milliseconds. */
object VideoCreationTime {

    // Cameras with no clock set write a 1904 (QuickTime epoch) or 1970 placeholder; those carry no information.
    private const val EARLIEST_REAL_MS = 946_684_800_000L // 2000-01-01

    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val patterns = listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'")
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { format.parse(text.trim())?.time }.getOrNull() ?: continue
            return parsed.takeIf { it >= EARLIEST_REAL_MS }
        }
        return null
    }
}
