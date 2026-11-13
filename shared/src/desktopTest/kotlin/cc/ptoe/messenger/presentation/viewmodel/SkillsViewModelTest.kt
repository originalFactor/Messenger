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
