package cc.ptoe.messenger.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RustCoreToolCatalogTest {
    private fun catalog(enabled: Boolean) = """{"tools":[{"name":"messenger_learning","description":"Learning drafts","parametersJson":"{}","enabled":$enabled,"workspaceRequired":false,"futureField":{"version":9}}]}"""

    @Test
    fun disabledCapabilitiesRemainConfigurableAndDescriptorsCannotExecute() = runBlocking {
        var enabled = false
        val inventory = RustCoreToolCatalog { catalog(enabled) }
        inventory.refresh()
        assertTrue(inventory.state.value.ready)
        val descriptor = inventory.state.value.tools.single()
        assertEquals("messenger_learning", descriptor.name)
        assertFalse(descriptor.capabilityEnabled)
        assertFalse(descriptor.writeAccess)
        assertTrue(descriptor.execute("{}").isError)
        enabled = true
        inventory.refresh()
        assertTrue(inventory.state.value.tools.single().capabilityEnabled)
    }

    @Test
    fun failureIsVisibleAndPreservesLastCatalogUntilExplicitRefresh() = runBlocking {
        var fail = true
        var calls = 0
        val inventory = RustCoreToolCatalog {
            calls++
            check(!fail) { "Core connection unavailable" }
            catalog(true)
        }
        inventory.refresh()
        assertEquals(1, calls, "failure must not automatically retry")
        assertFalse(inventory.state.value.ready)
        assertTrue(inventory.state.value.error.orEmpty().contains("Core connection unavailable"))
        fail = false
        inventory.refresh()
        val saved = inventory.state.value.tools
        fail = true
        inventory.refresh()
        assertEquals(saved, inventory.state.value.tools)
        assertTrue(inventory.state.value.ready)
        assertNotNull(inventory.state.value.error)
        fail = false
        inventory.refresh()
        assertEquals(null, inventory.state.value.error)
    }

    @Test(expected = CancellationException::class)
    fun cancellationDoesNotBecomeAnApiError() = runBlocking {
        RustCoreToolCatalog { throw CancellationException("closed") }.refresh()
    }
}
