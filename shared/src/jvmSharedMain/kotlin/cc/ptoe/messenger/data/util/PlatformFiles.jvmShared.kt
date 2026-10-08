/*
 * Copyright 2026 ECSDevs
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

package cc.ptoe.messenger.data.util

import okio.Path.Companion.toPath

actual fun fileExists(path: String): Boolean = FileKit.exists(path)

actual fun deleteFile(path: String) = FileKit.delete(path)

actual suspend fun writeFileBytes(path: String, bytes: ByteArray) {
    FileKit.writeBytesAtomic(path.toPath(), bytes)
}
