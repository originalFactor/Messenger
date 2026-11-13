package cc.ptoe.messenger.presentation.platform

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

private const val MAX_FILE_BYTES = 1024 * 1024
private const val MAX_PACKAGE_BYTES = 4 * MAX_FILE_BYTES
private const val MAX_ARCHIVE_BYTES = 8 * MAX_FILE_BYTES
private const val MAX_FILES = 128

/** No archive entry is written to disk. Bound actual bytes, regardless of ZIP header sizes. */
internal fun decodeSkillImport(name: String, input: InputStream): SkillImportBundle {
    if (name.endsWith(".md", ignoreCase = true)) {
        return SkillImportBundle(mapOf("SKILL.md" to decodeText(input.readLimited(MAX_FILE_BYTES))))
    }
    require(name.endsWith(".zip", ignoreCase = true)) { "Choose a SKILL.md or ZIP file" }
    // Bound the compressed input too, including archives with enormous padding or entry headers.
    val archive = input.readLimited(MAX_ARCHIVE_BYTES)
    val files = linkedMapOf<String, ByteArray>()
    var total = 0
    var entries = 0
    ZipInputStream(archive.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= MAX_FILES * 2) { "Skill archive has too many entries" }
            val path = entry.name.removeSuffix("/")
            requireSafeSkillPath(path)
            if (entry.isDirectory) continue
            require(files.size < MAX_FILES) { "A skill can contain at most 128 files" }
            require(path !in files) { "Duplicate skill path: $path" }
            val bytes = zip.readLimited(minOf(MAX_FILE_BYTES, MAX_PACKAGE_BYTES - total))
            total += bytes.size
            files[path] = bytes
        }
    }
    require(files.isNotEmpty()) { "Skill archive is empty" }
    val normalized = if ("SKILL.md" in files) files else {
        // Accept both a package root and a ZIP with a single enclosing skill directory.
        val root = files.keys.first().substringBefore('/', missingDelimiterValue = "")
        require(root.isNotBlank() && files.keys.all { it.startsWith("$root/") } && "$root/SKILL.md" in files) {
            "ZIP must contain SKILL.md at its root or in a single skill directory"
        }
        files.mapKeys { (path, _) -> path.removePrefix("$root/") }
    }
    val text = linkedMapOf<String, String>()
    val binary = linkedMapOf<String, String>()
    normalized.forEach { (path, bytes) ->
        val decoded = if (path == "SKILL.md") decodeText(bytes) else try {
            decodeText(bytes).takeIf { '\u0000' !in it }
        } catch (_: IllegalArgumentException) { null }
        if (decoded != null) text[path] = decoded
        else binary[path] = kotlin.io.encoding.Base64.encode(bytes)
    }
    return SkillImportBundle(text, binary)
}

internal fun requireSafeSkillPath(path: String) {
    require(path.isNotEmpty() && path.toByteArray(Charsets.UTF_8).size <= 512 &&
        !path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.none { it.code < 32 || it.code == 127 } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Invalid skill resource path: $path"
    }
}

private fun InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        require(output.size() + count <= limit) { "Skill file or package exceeds its size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeText(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (failure: java.nio.charset.CharacterCodingException) {
    throw IllegalArgumentException("SKILL.md must contain valid UTF-8 text", failure)
}
