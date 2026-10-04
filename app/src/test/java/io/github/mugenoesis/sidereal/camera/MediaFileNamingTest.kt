package io.github.mugenoesis.sidereal.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileNamingTest {

    @Test
    fun `splitExtension separates base name and extension`() {
        assertEquals("DJI_0034" to "jpg", MediaFileNaming.splitExtension("DJI_0034.jpg"))
        assertEquals("DJI_0032" to "mp4", MediaFileNaming.splitExtension("DJI_0032.mp4"))
    }

    @Test
    fun `splitExtension returns the whole name with an empty extension when there is no dot`() {
        assertEquals("DJI_0034" to "", MediaFileNaming.splitExtension("DJI_0034"))
    }

    @Test
    fun `downloadDestBaseName drops the source extension`() {
        // Regression test: fetchFileData(destDir, destFileName, listener)
        // appends the file's real extension to destFileName itself, so
        // passing a name that already includes ".jpg" produces
        // "dl_DJI_0034.jpg.jpg" on disk (confirmed by listing the actual
        // device cache directory). The name handed to fetchFileData must
        // NOT include the extension.
        assertEquals("dl_DJI_0034", MediaFileNaming.downloadDestBaseName("DJI_0034.jpg"))
        assertFalse(MediaFileNaming.downloadDestBaseName("DJI_0034.jpg").endsWith(".jpg"))
    }

    @Test
    fun `expectedDownloadedFileName reconstructs the real on-disk name`() {
        // This is the exact name the SDK actually wrote to disk once it
        // appends the extension back onto downloadDestBaseName's result.
        assertEquals("dl_DJI_0034.jpg", MediaFileNaming.expectedDownloadedFileName("DJI_0034.jpg"))
        assertEquals("dl_DJI_0032.mp4", MediaFileNaming.expectedDownloadedFileName("DJI_0032.mp4"))
    }

    @Test
    fun `expectedDownloadedFileName never produces a double extension`() {
        val expected = MediaFileNaming.expectedDownloadedFileName("DJI_0034.jpg")
        val occurrences = expected.split(".jpg").size - 1
        assertEquals(1, occurrences)
    }

    @Test
    fun `expectedDownloadedFileName handles a name with no extension`() {
        assertEquals("dl_README", MediaFileNaming.expectedDownloadedFileName("README"))
    }

    @Test
    fun `MediaTypeFilter allows real single-file photo and video types`() {
        assertTrue(MediaTypeFilter.isDownloadable("JPEG"))
        assertTrue(MediaTypeFilter.isDownloadable("RAW_DNG"))
        assertTrue(MediaTypeFilter.isDownloadable("MP4"))
        assertTrue(MediaTypeFilter.isDownloadable("MOV"))
    }

    @Test
    fun `MediaTypeFilter excludes grouped-shot folder types`() {
        // PHOTO_FOLDER/VIDEO_FOLDER entries aren't fetchable as a single
        // file via fetchFileData() - the browser must not list them.
        assertFalse(MediaTypeFilter.isDownloadable("PHOTO_FOLDER"))
        assertFalse(MediaTypeFilter.isDownloadable("VIDEO_FOLDER"))
    }

    @Test
    fun `MediaTypeFilter excludes non-media types`() {
        assertFalse(MediaTypeFilter.isDownloadable("JSON"))
        assertFalse(MediaTypeFilter.isDownloadable("UNKNOWN"))
    }

    @Test
    fun `MediaTypeFilter isVideo is true only for MOV and MP4`() {
        assertTrue(MediaTypeFilter.isVideo("MOV"))
        assertTrue(MediaTypeFilter.isVideo("MP4"))
        assertFalse(MediaTypeFilter.isVideo("JPEG"))
        assertFalse(MediaTypeFilter.isVideo("RAW_DNG"))
        assertFalse(MediaTypeFilter.isVideo("TIFF"))
    }

    @Test
    fun `MediaTypeFilter mimeTypeFor maps each known type to its real MIME type`() {
        assertEquals("video/quicktime", MediaTypeFilter.mimeTypeFor("MOV"))
        assertEquals("video/mp4", MediaTypeFilter.mimeTypeFor("MP4"))
        assertEquals("image/x-adobe-dng", MediaTypeFilter.mimeTypeFor("RAW_DNG"))
        assertEquals("image/tiff", MediaTypeFilter.mimeTypeFor("TIFF"))
    }

    @Test
    fun `MediaTypeFilter mimeTypeFor defaults to jpeg for JPEG and any unrecognized type`() {
        assertEquals("image/jpeg", MediaTypeFilter.mimeTypeFor("JPEG"))
        assertEquals("image/jpeg", MediaTypeFilter.mimeTypeFor("PANORAMA"))
        assertEquals("image/jpeg", MediaTypeFilter.mimeTypeFor("SHALLOW_FOCUS"))
        assertEquals("image/jpeg", MediaTypeFilter.mimeTypeFor("SOMETHING_UNKNOWN"))
    }
}
