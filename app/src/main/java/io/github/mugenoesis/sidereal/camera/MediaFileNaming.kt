package io.github.mugenoesis.sidereal.camera

/**
 * Pure filename logic for MediaLibraryController's downloads, split out so
 * it's unit-testable without a live MediaFile/SDK download. Exists because
 * of a real bug found on real hardware: MediaFile.fetchFileData(destDir,
 * destFileName, listener) appends the file's own real extension to
 * whatever destFileName it's given - passing "dl_DJI_0034.jpg" produced
 * "dl_DJI_0034.jpg.jpg" on disk (confirmed by listing the actual cache
 * directory on the device), so every "did the download really land"
 * check against the naively-assumed path silently failed even though the
 * transfer itself succeeded. destFileName must be the base name only; the
 * expected final name has the extension added back once.
 */
object MediaFileNaming {

    /** "DJI_0034.jpg" -> ("DJI_0034", "jpg"). No extension present returns (fileName, ""). */
    fun splitExtension(fileName: String): Pair<String, String> {
        val extension = fileName.substringAfterLast('.', "")
        val baseName = fileName.substringBeforeLast('.', fileName)
        return baseName to extension
    }

    /** What to pass as fetchFileData's destFileName - deliberately WITHOUT fileName's own extension. */
    fun downloadDestBaseName(fileName: String, prefix: String = "dl_"): String {
        val (baseName, _) = splitExtension(fileName)
        return "$prefix$baseName"
    }

    /** The real on-disk name fetchFileData produces for [fileName] once it appends the extension back onto [downloadDestBaseName]. */
    fun expectedDownloadedFileName(fileName: String, prefix: String = "dl_"): String {
        val (_, extension) = splitExtension(fileName)
        val base = downloadDestBaseName(fileName, prefix)
        return if (extension.isEmpty()) base else "$base.$extension"
    }
}

/**
 * Which MediaFile.MediaType names MediaLibraryController's browser lists.
 * PHOTO_FOLDER/VIDEO_FOLDER (grouped shots - panorama bursts, etc.) are
 * excluded: they aren't fetchable as a single file via
 * MediaFile.fetchFileData() the way every other type is (that needs the
 * separate fetchSubFileDataList() path) - out of scope for this first pass.
 *
 * Works off MediaFile.MediaType.name (a plain String) rather than the live
 * enum type - see CameraLabels' doc comment for why: dji-sdk-provided's
 * classes throw java.lang.VerifyError when touched from a plain JVM unit
 * test (they're packaged for Android's ART runtime). Callers pass
 * `mediaType.name` from the real enum, so the actual set of allowed types
 * is unchanged.
 */
object MediaTypeFilter {
    val DOWNLOADABLE_TYPE_NAMES: Set<String> = setOf(
        "JPEG",
        "RAW_DNG",
        "TIFF",
        "PANORAMA",
        "SHALLOW_FOCUS",
        "MOV",
        "MP4"
    )

    fun isDownloadable(mediaTypeName: String): Boolean = mediaTypeName in DOWNLOADABLE_TYPE_NAMES

    private val VIDEO_TYPE_NAMES: Set<String> = setOf("MOV", "MP4")

    /** Whether [mediaTypeName] should be saved into the Movies (vs Pictures) MediaStore collection. */
    fun isVideo(mediaTypeName: String): Boolean = mediaTypeName in VIDEO_TYPE_NAMES

    /** MIME type MediaLibraryController.saveToMediaStore writes into ContentValues for [mediaTypeName]. */
    fun mimeTypeFor(mediaTypeName: String): String = when (mediaTypeName) {
        "MOV" -> "video/quicktime"
        "MP4" -> "video/mp4"
        "RAW_DNG" -> "image/x-adobe-dng"
        "TIFF" -> "image/tiff"
        else -> "image/jpeg"
    }
}

/**
 * Order of the media browser's list: newest capture first. The camera hands files back oldest first. Files with the
 * same time (or a camera clock that was never set, so every time is 0) fall back to the highest file name first -
 * DJI numbers its files upward (DJI_0011 after DJI_0010), so that is still newest first.
 */
object MediaOrdering {
    fun <T> newestFirst(items: List<T>, timeOf: (T) -> Long, nameOf: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { timeOf(it) }.thenByDescending { nameOf(it) })
}
