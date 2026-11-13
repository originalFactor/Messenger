package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.presentation.platform.SkillImportBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    private val summary = """{"name":"test-skill","description":"Test","enabled":true}"""
    private val detail = """{"name":"test-skill","description":"Test","enabled":true,"files":{"SKILL.md":"instructions","references/guide.md":"guide"},"binaryFiles":{"assets/icon.png":"/wAB"}}"""

    private fun bridge(handler: (String, Array<out Any?>) -> Any?): CoreBridge =
        Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as CoreBridge

    @Test fun remoteSelectionPinsDiscoveredCommitAndPreservesPreviewWithoutInstalling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var discoveryRequest: JsonObject? = null
            var previewRequest: JsonObject? = null
            val revision = "a".repeat(40)
            val core = bridge { method, args -> when (method) {
                "listSkills" -> """{"items":[$summary]}"""
                "discoverRemoteSkills" -> {
                    discoveryRequest = Json.parseToJsonElement(args[0] as String).jsonObject
                    """{"source":"https://github.com/owner/repo.git","ref":"$revision","items":[{"name":"test-skill","description":"Test","path":"skills/test-skill"}],"warnings":[{"path":"skills/invalid","message":"description exceeds 1024 characters"}]}"""
                }
                "previewRemoteSkill" -> { previewRequest = Json.parseToJsonElement(args[0] as String).jsonObject; detail }
                else -> error("Unexpected bridge call: $method")
            } }
            val vm = SkillsViewModel(core)
            advanceUntilIdle()
            vm.discoverRemote(" owner/repo ", " feature/skills ", " skills ")
            advanceUntilIdle()
            assertEquals("owner/repo", discoveryRequest?.get("source")?.jsonPrimitive?.content)
            assertEquals("feature/skills", discoveryRequest?.get("ref")?.jsonPrimitive?.content)
            assertEquals("skills", discoveryRequest?.get("path")?.jsonPrimitive?.content)
            assertNull(vm.state.value.editing)
            val catalog = assertNotNull(vm.state.value.remoteCatalog)
            assertEquals("skills/invalid", catalog.warnings.single().path)
            assertContains(catalog.warnings.single().message, "1024")
            vm.previewRemote(catalog.items.single())
            advanceUntilIdle()
            assertEquals(revision, previewRequest?.get("ref")?.jsonPrimitive?.content)
            assertEquals(catalog.source, previewRequest?.get("source")?.jsonPrimitive?.content)
            assertEquals("skills/test-skill", previewRequest?.get("path")?.jsonPrimitive?.content)
            assertTrue(vm.state.value.imported)
            assertEquals("/wAB", vm.state.value.editing?.binaryFiles?.get("assets/icon.png"))
            assertEquals("instructions", vm.state.value.draftFiles["SKILL.md"])
            assertNull(vm.state.value.remoteCatalog)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun remoteFailureRemainsVisibleWithoutRetryAndSourceChangeClearsResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val core = bridge { method, _ -> when (method) {
                "listSkills" -> """{"items":[]}"""
                "discoverRemoteSkills" -> { attempts++; error("HTTP 502: repository unavailable") }
                else -> error("Unexpected bridge call: $method")
            } }
            val vm = SkillsViewModel(core)
            advanceUntilIdle()
            vm.discoverRemote("owner/repo")
            advanceUntilIdle()
            assertEquals(1, attempts)
            assertContains(assertNotNull(vm.state.value.error), "repository unavailable")
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.editing)
            vm.clearRemoteCatalog()
            assertNull(vm.state.value.remoteCatalog)
            assertNull(vm.state.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun toggleFetchesCompletePackageAndPreservesEveryResource() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var written: JsonObject? = null
            val core = bridge { method, args -> when (method) {
                "listSkills" -> """{"items":[$summary]}"""
                "getSkill" -> detail
                "saveSkill" -> { written = Json.parseToJsonElement(args[1] as String).jsonObject; Unit }
                else -> error("Unexpected bridge call: $method")
            } }
            val vm = SkillsViewModel(core)
            advanceUntilIdle()
            vm.setEnabled(vm.state.value.skills.single(), false)
            advanceUntilIdle()
            assertEquals(false, written?.get("enabled")?.jsonPrimitive?.boolean)
            assertEquals("guide", written?.get("files")?.jsonObject?.get("references/guide.md")?.jsonPrimitive?.content)
            assertEquals("/wAB", written?.get("binaryFiles")?.jsonObject?.get("assets/icon.png")?.jsonPrimitive?.content)
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun importOnlyPreviewsAndFailedSaveRetainsDraftAndVisibleError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var writes = 0
            var validated: JsonObject? = null
            val core = bridge { method, args -> when (method) {
                "listSkills" -> """{"items":[$summary]}"""
                "validateSkill" -> { validated = Json.parseToJsonElement(args[0] as String).jsonObject; detail }
                "saveSkill" -> { writes++; error("HTTP 400: name must match frontmatter") }
                else -> error("Unexpected bridge call: $method")
            } }
            val vm = SkillsViewModel(core)
            advanceUntilIdle()
            vm.importFiles(SkillImportBundle(mapOf("SKILL.md" to "instructions"), mapOf("assets/icon.png" to "/wAB")))
            advanceUntilIdle()
            assertEquals(0, writes)
            assertTrue(vm.state.value.imported)
            val draft = assertNotNull(vm.state.value.editing)
            assertEquals("/wAB", validated?.get("binaryFiles")?.jsonObject?.get("assets/icon.png")?.jsonPrimitive?.content)
            val editedFiles = draft.files + ("references/guide.md" to "unsaved changes")
            vm.updateDraftFiles(editedFiles)
            assertEquals(editedFiles, vm.state.value.draftFiles)
            vm.save("other", editedFiles, draft.enabled)
            advanceUntilIdle()
            assertEquals(1, writes)
            assertEquals(draft, vm.state.value.editing)
            assertEquals(editedFiles, vm.state.value.draftFiles)
            assertContains(assertNotNull(vm.state.value.error), "name must match frontmatter")
            assertFalse(vm.state.value.busy)
        } finally { Dispatchers.resetMain() }
    }
}
