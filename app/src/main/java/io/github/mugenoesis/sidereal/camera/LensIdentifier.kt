package io.github.mugenoesis.sidereal.camera

import io.github.mugenoesis.sidereal.series.CardSource
import java.io.File

/**
 * Names the lens by looking at the newest photo on the card. The camera reports "Unknown" for any lens that is not
 * DJI's own, but every photo it takes carries the lens' name in its EXIF, so one downloaded photo is enough.
 *
 * @param readLensModel the EXIF lens model of a downloaded photo, or null if it names none
 */
class LensIdentifier(
    private val card: CardSource,
    private val readLensModel: (File) -> String?
) {
    sealed class Result {
        data class Identified(val lensModel: String) : Result()
        object NoPhotos : Result() { override fun toString() = "NoPhotos" }
        object CardUnreadable : Result() { override fun toString() = "CardUnreadable" }
        object DownloadFailed : Result() { override fun toString() = "DownloadFailed" }
        object NoLensInPhoto : Result() { override fun toString() = "NoLensInPhoto" }
    }

    suspend fun identify(): Result {
        try {
            val photos = card.listPhotos() ?: return Result.CardUnreadable
            val newest = MediaOrdering.newestFirst(photos.filter { it.type == "JPEG" }, { it.timeMs }, { it.name }).firstOrNull()
                ?: return Result.NoPhotos
            val file = card.fetch(newest) ?: return Result.DownloadFailed
            try {
                val model = readLensModel(file)?.trim().orEmpty()
                return if (model.isEmpty()) Result.NoLensInPhoto else Result.Identified(model)
            } finally {
                file.delete()
            }
        } finally {
            card.close()
        }
    }
}
