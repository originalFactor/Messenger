/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

internal data class ImageDimensions(val width: Long, val height: Long)
internal fun checkedDimensions(width: Int, height: Int): ImageDimensions {
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000L) { "Image dimensions exceed limit" }
    return ImageDimensions(width.toLong(), height.toLong())
}
internal expect fun validateOwnedImage(bytes: ByteArray, mime: String): ImageDimensions
