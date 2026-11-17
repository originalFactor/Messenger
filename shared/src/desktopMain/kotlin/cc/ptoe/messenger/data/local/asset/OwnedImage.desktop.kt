/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal actual fun validateOwnedImage(bytes: ByteArray, mime: String): ImageDimensions {
    require(bytes.isNotEmpty() && bytes.size.toLong() <= CoreAssetReader.MAX_BYTES) { "Invalid image size" }
    require(mime in setOf("image/png", "image/jpeg", "image/webp")) { "Unsupported image MIME" }
    return MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
        val reader = if (mime == "image/webp") {
            val spi = WebPImageReaderSpi()
            require(spi.canDecodeInput(stream)) { "Image MIME does not match bytes" }
            stream.seek(0)
            spi.createReaderInstance()
        } else {
            val candidates = ImageIO.getImageReaders(stream)
            require(candidates.hasNext()) { "Invalid image" }
            candidates.next()
        }
        try {
            val actual = when (reader.formatName.lowercase()) { "png" -> "image/png"; "jpeg", "jpg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "" }
            require(actual == mime) { "Image MIME does not match bytes" }
            reader.input = stream
            val dimensions = checkedDimensions(reader.getWidth(0), reader.getHeight(0))
            val type = reader.getImageTypes(0).asSequence().firstOrNull() ?: error("Unsupported image type")
            val sample = type.sampleModel
            val sampleBytes = when (sample.dataType) {
                java.awt.image.DataBuffer.TYPE_BYTE -> 1
                java.awt.image.DataBuffer.TYPE_USHORT, java.awt.image.DataBuffer.TYPE_SHORT -> 2
                java.awt.image.DataBuffer.TYPE_INT, java.awt.image.DataBuffer.TYPE_FLOAT -> 4
                java.awt.image.DataBuffer.TYPE_DOUBLE -> 8
                else -> error("Unsupported image buffer type")
            }
            require(dimensions.width * dimensions.height * sample.numBands * sampleBytes <= 128L * 1024 * 1024) { "Image buffer exceeds limit" }
            var warning = false
            reader.addIIOReadWarningListener { _, _ -> warning = true }
            val bitmap = reader.read(0) ?: error("Invalid image")
            require(!warning && bitmap.width.toLong() == dimensions.width && bitmap.height.toLong() == dimensions.height) { "Incomplete image" }
            bitmap.flush()
            dimensions
        } finally { reader.dispose() }
    }
}
