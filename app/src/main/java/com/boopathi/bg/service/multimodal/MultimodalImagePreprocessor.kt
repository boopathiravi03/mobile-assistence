package com.boopathi.bg.service.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Validates, bounds, and normalizes user-provided images before analysis.
 */
object MultimodalImagePreprocessor {

    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024L // 5 MB
    const val MAX_DIMENSION = 1024 // Downsample if exceeding 1024x1024

    val SUPPORTED_MIME_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp"
    )

    /**
     * Validates image byte array against format, size, and corrupt payload boundaries.
     */
    fun validateImage(bytes: ByteArray, mimeType: String): ImageValidationResult {
        if (bytes.isEmpty()) {
            return ImageValidationResult.CorruptOrUnreadable("Image byte array is empty.")
        }

        if (bytes.size > MAX_IMAGE_BYTES) {
            return ImageValidationResult.Oversized(bytes.size.toLong(), MAX_IMAGE_BYTES)
        }

        val normalizedMime = mimeType.lowercase().trim()
        if (normalizedMime !in SUPPORTED_MIME_TYPES) {
            return ImageValidationResult.UnsupportedFormat(normalizedMime)
        }

        // Validate magic header bytes to ensure content isn't corrupt or disguised executable
        if (!hasValidImageHeader(bytes)) {
            return ImageValidationResult.CorruptOrUnreadable("Invalid or corrupted image header.")
        }

        // Test decoding bounds safely without loading full pixels into memory
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return ImageValidationResult.CorruptOrUnreadable("Unable to decode image dimensions.")
        }

        return ImageValidationResult.Valid
    }

    /**
     * Downsamples or compresses image if dimensions exceed MAX_DIMENSION,
     * maintaining aspect ratio and reducing network transmission footprint.
     */
    fun downsampleIfNeeded(
        bytes: ByteArray,
        mimeType: String,
        maxDimension: Int = MAX_DIMENSION
    ): ByteArray {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight

        if (width <= maxDimension && height <= maxDimension) {
            return bytes
        }

        // Calculate sample size (power of 2)
        var inSampleSize = 1
        val maxSide = maxOf(width, height)
        while ((maxSide / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val sampledBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: return bytes

        // Resize precisely if needed
        val finalBitmap = if (sampledBitmap.width > maxDimension || sampledBitmap.height > maxDimension) {
            val ratio = minOf(
                maxDimension.toFloat() / sampledBitmap.width,
                maxDimension.toFloat() / sampledBitmap.height
            )
            val targetW = (sampledBitmap.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (sampledBitmap.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(sampledBitmap, targetW, targetH, true)
            if (scaled != sampledBitmap) {
                sampledBitmap.recycle()
            }
            scaled
        } else {
            sampledBitmap
        }

        val stream = ByteArrayOutputStream()
        val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        finalBitmap.compress(format, 85, stream)
        finalBitmap.recycle()

        return stream.toByteArray()
    }

    /**
     * Checks magic numbers for JPEG (FF D8 FF), PNG (89 50 4E 47), and WEBP (RIFF...WEBP).
     */
    fun hasValidImageHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false

        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return true
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return true
        }

        // WEBP: RIFF (bytes 0..3) ... WEBP (bytes 8..11)
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return true
        }

        return false
    }
}

