/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

internal actual fun validateOwnedImage(bytes: ByteArray, mime: String): ImageDimensions {
    require(bytes.isNotEmpty() && bytes.size.toLong() <= CoreAssetReader.MAX_BYTES) { "Invalid image size" }
    require(mime in setOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image MIME" }
    var dimensions: ImageDimensions? = null
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(info.mimeType == mime) { "Image MIME does not match bytes" }
        dimensions = checkedDimensions(info.size.width, info.size.height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    }
    try {
        val expected = requireNotNull(dimensions)
        require(bitmap.width.toLong() == expected.width && bitmap.height.toLong() == expected.height &&
            bitmap.allocationByteCount.toLong() <= 128L * 1024 * 1024) { "Invalid decoded image" }
        return expected
    } finally { bitmap.recycle() }
}
