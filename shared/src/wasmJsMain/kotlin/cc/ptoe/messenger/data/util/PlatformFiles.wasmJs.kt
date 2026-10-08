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

/**
 * The browser has no general filesystem, so this is deliberately narrow and
 * honest about it:
 *
 * - [fileExists] answers `true` only for URI strings the page itself can
 *   render (`data:`, `blob:`, `http(s):`). Anything else — including every
 *   path Android/Desktop would have written — is `false`.
 * - [writeFileBytes] and [deleteFile] are no-ops. Durable bytes on web belong
 *   to the Rust core's SQLite-on-OPFS store (`messenger-store`) and the OPFS
 *   preferences DataStore; a second, unreferenced file layer here would be
 *   dead code that silently pretends to persist.
 */
actual fun fileExists(path: String): Boolean {
    val trimmed = path.trim()
    return trimmed.startsWith("data:") ||
        trimmed.startsWith("blob:") ||
        trimmed.startsWith("http://") ||
        trimmed.startsWith("https://")
}

actual fun deleteFile(path: String) = Unit

actual suspend fun writeFileBytes(path: String, bytes: ByteArray) = Unit
