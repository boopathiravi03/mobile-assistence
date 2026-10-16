package com.boopathi.bg

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boopathi.bg.service.multimodal.ImageValidationResult
import com.boopathi.bg.service.multimodal.MultimodalImage
import com.boopathi.bg.service.multimodal.MultimodalImagePreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Phase 7 Unit Tests: Image Selection, Header Verification & Bounds Validation.
 * - Requirement 1: Valid image selection.
 * - Requirement 2: Invalid or corrupt image rejection.
 * - Requirement 3: Oversized image handling.
 */
@RunWith(AndroidJUnit4::class)
class Phase7ImageValidationTest {

    private fun createSampleBitmapBytes(width: Int = 10, height: Int = 10, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, 90, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    @Test
    fun test1_validPngImageSelectionIsValidated() {
        val bytes = createSampleBitmapBytes(100, 100, Bitmap.CompressFormat.PNG)
        val result = MultimodalImagePreprocessor.validateImage(bytes, "image/png")

        assertTrue("Valid PNG image must pass validation", result.isValid)
        assertTrue(result is ImageValidationResult.Valid)

        val image = MultimodalImage(
            bytes = bytes,
            mimeType = "image/png",
            width = 100,
            height = 100
        )
        assertEquals(100, image.width)
        assertEquals(100, image.height)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun test2_validJpegImageSelectionIsValidated() {
        val bytes = createSampleBitmapBytes(50, 50, Bitmap.CompressFormat.JPEG)
        val result = MultimodalImagePreprocessor.validateImage(bytes, "image/jpeg")

        assertTrue("Valid JPEG image must pass validation", result.isValid)
    }

    @Test
    fun test3_emptyBytesRejectedAsCorruptOrUnreadable() {
        val emptyBytes = ByteArray(0)
        val result = MultimodalImagePreprocessor.validateImage(emptyBytes, "image/png")

        assertFalse("Empty image bytes must be rejected", result.isValid)
        assertTrue(result is ImageValidationResult.CorruptOrUnreadable)
    }

    @Test
    fun test4_corruptHeaderBytesRejected() {
        val corruptBytes = "NotAnImageHeaderStringData".toByteArray()
        val result = MultimodalImagePreprocessor.validateImage(corruptBytes, "image/png")

        assertFalse("Bytes with corrupt header must be rejected", result.isValid)
        assertTrue(result is ImageValidationResult.CorruptOrUnreadable)
    }

    @Test
    fun test5_oversizedImageBeyond5MbIsRejected() {
        // Construct array simulating > 5 MB
        val oversizeBytes = ByteArray((5 * 1024 * 1024) + 1024)
        // Add valid PNG magic bytes to ensure size check specifically triggers
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        System.arraycopy(pngMagic, 0, oversizeBytes, 0, pngMagic.size)

        val result = MultimodalImagePreprocessor.validateImage(oversizeBytes, "image/png")

        assertFalse("Image exceeding 5MB bound must be rejected", result.isValid)
        assertTrue(result is ImageValidationResult.Oversized)
        val oversizedResult = result as ImageValidationResult.Oversized
        assertEquals(5 * 1024 * 1024L, oversizedResult.maxBytes)
    }

    @Test
    fun test6_unsupportedMimeTypeIsRejected() {
        val bytes = createSampleBitmapBytes(10, 10, Bitmap.CompressFormat.PNG)
        val result = MultimodalImagePreprocessor.validateImage(bytes, "image/gif")

        assertFalse("GIF format must be rejected as unsupported", result.isValid)
        assertTrue(result is ImageValidationResult.UnsupportedFormat)
    }

    @Test
    fun test7_magicHeaderHelperIdentifiesValidFormats() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        val fakeBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

        assertTrue("PNG magic bytes must be recognized", MultimodalImagePreprocessor.hasValidImageHeader(pngBytes))
        assertTrue("JPEG magic bytes must be recognized", MultimodalImagePreprocessor.hasValidImageHeader(jpegBytes))
        assertFalse("Arbitrary bytes must not be recognized", MultimodalImagePreprocessor.hasValidImageHeader(fakeBytes))
    }

    @Test
    fun test8_downsamplingMaintainsBoundsWithinMaxDimension() {
        val largeBytes = createSampleBitmapBytes(1500, 1200, Bitmap.CompressFormat.PNG)
        val optimized = MultimodalImagePreprocessor.downsampleIfNeeded(largeBytes, "image/png", maxDimension = 1024)

        assertTrue("Downsampled image must not be empty", optimized.isNotEmpty())
        assertTrue("Downsampled image size should be smaller or bounded", optimized.size <= largeBytes.size)
    }
}

