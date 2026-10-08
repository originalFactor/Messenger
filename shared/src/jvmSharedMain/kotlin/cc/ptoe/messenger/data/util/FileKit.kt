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

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * okio-backed file helpers shared by the cloud sync and repository
 * layers. All paths are plain absolute strings (the same values that
 * used to be `java.io.File` paths on Android) so persisted data stays
 * byte-compatible with the pre-KMP app.
 */
internal object FileKit {
    private val fs = FileSystem.SYSTEM

    fun exists(path: String): Boolean = fs.exists(path.toPath())

    /** File exists and is non-empty (the old `File.isFile && length() > 0` check). */
    fun isUsableFile(path: String): Boolean {
        val p = path.toPath()
        return fs.exists(p) && (fs.metadataOrNull(p)?.isRegularFile == true) &&
            (fs.metadataOrNull(p)?.size ?: 0L) > 0L
    }

    fun size(path: String): Long = fs.metadataOrNull(path.toPath())?.size ?: 0L

    fun readBytes(path: String): ByteArray = fs.read(path.toPath()) { readByteArray() }

    fun delete(path: String) {
        val p = path.toPath()
        if (fs.exists(p)) fs.delete(p)
    }

    fun deleteRecursively(path: Path) {
        if (fs.exists(path)) fs.deleteRecursively(path)
    }

    fun mkdirs(path: Path) = fs.createDirectories(path)

    fun list(dir: Path): List<Path> = if (fs.exists(dir)) fs.list(dir) else emptyList()

    /** Extension of a path string, lowercase, "" when absent (mirrors java.io.File.extension). */
    fun extensionOf(path: String): String =
        path.substringAfterLast('.', "").let { if (it == path) "" else it.lowercase() }

    fun nameOf(path: String): String = path.toPath().name

    fun parentOf(path: String): String? = path.toPath().parent?.toString()

    fun copy(source: String, target: String) {
        val t = target.toPath()
        t.parent?.let(fs::createDirectories)
        fs.write(t) { write(readBytes(source)) }
    }

    /** Atomic-ish persist: write a .part sibling then rename over the target. */
    fun writeBytesAtomic(target: Path, bytes: ByteArray) {
        target.parent?.let(fs::createDirectories)
        val temporary = target.parent!!.resolve("${target.name}.part")
        try {
            fs.write(temporary) { write(bytes) }
            // okio 3.x atomicMove signature is (source, target) — overwrites by default.
            if (fs.exists(target)) fs.delete(target)
            fs.atomicMove(temporary, target)
        } finally {
            if (fs.exists(temporary)) fs.delete(temporary)
        }
    }

    fun writeText(path: String, text: String) {
        val p = path.toPath()
        p.parent?.let(fs::createDirectories)
        fs.write(p) { writeUtf8(text) }
    }

    fun readText(path: String): String = fs.read(path.toPath()) { readUtf8() }
}
