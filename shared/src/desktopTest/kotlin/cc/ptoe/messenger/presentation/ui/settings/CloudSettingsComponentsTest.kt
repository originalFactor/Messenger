package cc.ptoe.messenger.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CloudSettingsComponentsTest {
    @Test fun quotaAndExpiryDoNotCollideInNarrowOrWideContent() = runComposeUiTest {
        val width = mutableStateOf(240)
        setContent { MessengerTheme(dynamicColor = false) {
            Box(Modifier.width(width.value.dp)) { CloudQuotaSummary("9,997,012", "Remaining quota", "Expires", "2026-12-07") }
        } }
        val quota = onNodeWithText("9,997,012").fetchSemanticsNode().boundsInRoot
        val narrowExpiry = onNodeWithText("Expires").fetchSemanticsNode().boundsInRoot
        assertTrue(narrowExpiry.top >= quota.bottom)
        runOnIdle { width.value = 600 }
        val wideQuota = onNodeWithText("9,997,012").fetchSemanticsNode().boundsInRoot
        val wideExpiry = onNodeWithText("Expires").fetchSemanticsNode().boundsInRoot
        assertTrue(wideExpiry.left >= wideQuota.right)
    }
    @Test fun securityRowsRespectTheBusyGate() = runComposeUiTest {
        var count = 0
        val enabled = mutableStateOf(false)
        setContent { MessengerTheme(dynamicColor = false) {
            CloudSettingsSection("Security", Icons.Default.Lock, groupedActions = true) {
                CloudActionRow("Change password", Icons.Default.Lock, { count++ }, 0, 1, enabled = enabled.value)
            }
        } }
        onNodeWithText("Change password").assertIsNotEnabled()
        runOnIdle { enabled.value = true }
        onNodeWithText("Change password").performClick()
        runOnIdle { assertEquals(1, count) }
    }
}
