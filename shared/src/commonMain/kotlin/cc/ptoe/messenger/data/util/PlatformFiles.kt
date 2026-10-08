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
 * Platform file helpers used by shared code that must also compile for the
 * browser. Android/Desktop forward to [FileKit] (okio's `FileSystem.SYSTEM`);
 * the browser has no filesystem at all — see the wasmJs actual for the
 * exact, deliberately narrow contract.
 */
expect fun fileExists(path: String): Boolean

/** Deletes [path] when the platform can; a no-op where it cannot. */
expect fun deleteFile(path: String)

/**
 * Writes [bytes] to [path] when the platform can. On web this is a no-op:
 * durable storage there belongs to the Rust core's SQLite-on-OPFS store and
 * the OPFS preferences DataStore, not to this helper.
 */
expect suspend fun writeFileBytes(path: String, bytes: ByteArray)
