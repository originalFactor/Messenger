/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cc.ptoe.messenger.domain.model.MessageImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation: downscales the picked bitmap (bounds-then-
 * sample decode), applies EXIF rotation, and caches a PNG copy inside
 * `filesDir/chat_images/` that Coil can decode without re-reading the
 * source.
 */
class AndroidChatImageStore(private val context: Context) : ChatImageStore {

    private val cacheDir: File
        get() = File(context.filesDir, ChatImageStore.CACHE_SUBDIR).apply { mkdirs() }

    override suspend fun importImage(bytes: ByteArray, extension: String): MessageImage =
        withContext(Dispatchers.IO) {
            val bounds = readBounds(bytes)
            val sampleSize = computeInSampleSize(
                bounds.first, bounds.second,
                ChatImageStore.MAX_DIMENSION_PX, ChatImageStore.MAX_DIMENSION_PX
            )
            val rawBitmap = decodeBitmap(bytes, sampleSize) ?: error("Unable to decode picked image")
            val oriented = applyExifRotation(bytes, rawBitmap)

            val targetFile = File(cacheDir, "${UUID.randomUUID()}.png")
            FileOutputStream(targetFile).use { out ->
                oriented.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (oriented !== rawBitmap) rawBitmap.recycle()
            oriented.recycle()

            val dataUri = withContext(Dispatchers.IO) {
                val pngBytes = targetFile.readBytes()
                val b64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)
                "data:image/png;base64,$b64"
            }

            MessageImage(
                dataUri = dataUri,
                localPath = targetFile.absolutePath
            )
        }

    /**
     * Allocates a destination file in the cache dir and returns a
     * `content://` URI for it. Used by the camera capture flow where
     * the system camera writes the captured photo to the URI we
     * supply.
     */
    suspend fun newCameraOutput(): CameraTarget = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "capture_${UUID.randomUUID()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        CameraTarget(file = file, uri = uri)
    }

    /**
     * Reads a previously-allocated camera output back as a
     * [MessageImage]. The caller is responsible for confirming the
     * file exists and is non-empty (camera may have been cancelled).
     */
    suspend fun importCameraTarget(target: CameraTarget): MessageImage? = withContext(Dispatchers.IO) {
        if (!target.file.exists() || target.file.length() == 0L) return@withContext null
        importImage(target.file.readBytes(), target.file.extension)
    }

    data class CameraTarget(val file: File, val uri: Uri)

    private fun readBounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun decodeBitmap(bytes: ByteArray, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, opts)
    }

    private fun applyExifRotation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var sample = 1
        var halfW = srcWidth / 2
        var halfH = srcHeight / 2
        while (halfW / sample >= reqWidth && halfH / sample >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    override fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
