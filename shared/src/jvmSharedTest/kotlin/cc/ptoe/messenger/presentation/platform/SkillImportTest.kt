package cc.ptoe.messenger.presentation.platform

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillImportTest {
    private val markdown = "---\nname: test-skill\ndescription: Test skill\n---\nBody"

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun importsStandaloneMarkdownAndWrappedPackageWithoutLosingResources() {
        assertEquals(mapOf("SKILL.md" to markdown), decodeSkillImport("SKILL.md", markdown.byteInputStream()).files)
        val zip = archive(
            "test-skill/SKILL.md" to markdown.toByteArray(),
            "test-skill/references/guide.md" to "中文参考".toByteArray(),
            "test-skill/scripts/helper.py" to "print('example')".toByteArray(),
        )
        assertEquals(mapOf("SKILL.md" to markdown, "references/guide.md" to "中文参考", "scripts/helper.py" to "print('example')"),
            decodeSkillImport("skill.zip", zip.inputStream()).files)
    }

    @Test fun rejectsTraversalAndAmbiguousArchiveRoots() {
        for (path in listOf("../outside", "/absolute", "root/../../outside", "C:/drive", "root\\outside", "root//empty")) {
            val zip = archive("SKILL.md" to markdown.toByteArray(), path to byteArrayOf())
            assertFailsWith<IllegalArgumentException>(path) { decodeSkillImport("skill.zip", zip.inputStream()) }
        }
        val zip = archive("first/SKILL.md" to markdown.toByteArray(), "second/guide.md" to byteArrayOf())
        assertFailsWith<IllegalArgumentException> { decodeSkillImport("skill.zip", zip.inputStream()) }
    }

    @Test fun preservesBinaryAssetsAndRejectsInvalidSkillMarkdown() {
        val bytes = byteArrayOf(0xff.toByte(), 0x00, 0x01)
        val zip = archive("SKILL.md" to markdown.toByteArray(), "assets/image.png" to bytes)
        val bundle = decodeSkillImport("skill.zip", zip.inputStream())
        assertEquals(markdown, bundle.files["SKILL.md"])
        kotlin.test.assertContentEquals(bytes, kotlin.io.encoding.Base64.decode(bundle.binaryFiles.getValue("assets/image.png")))
        val invalid = archive("SKILL.md" to byteArrayOf(0xff.toByte()))
        assertFailsWith<IllegalArgumentException> { decodeSkillImport("skill.zip", invalid.inputStream()) }
    }

    @Test fun boundsExpandedArchiveSize() {
        val zip = archive("SKILL.md" to markdown.toByteArray(), "huge.txt" to ByteArray(1024 * 1024 + 1) { 65 })
        assertFailsWith<IllegalArgumentException> { decodeSkillImport("skill.zip", zip.inputStream()) }
    }
}
