package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ExpressiveComponentsTest {
    @Test fun listRetainsDistinctClickAndLongPressActions() = runComposeUiTest {
        var clicks = 0
        var holds = 0
        setContent { MessengerTheme(dynamicColor = false) {
            ExpressiveRow({ clicks++ }, onLongClick = { holds++ }) { Text("Conversation") }
        } }
        onNodeWithText("Conversation").performTouchInput { click() }
        onNodeWithText("Conversation").performTouchInput { longClick() }
        runOnIdle { assertEquals(1, clicks); assertEquals(1, holds) }
    }

    @Test fun switchExposesOneActionAndWaitsForConfirmedValue() = runComposeUiTest {
        var value: Boolean? = null
        setContent { MessengerTheme(dynamicColor = false) {
            ExpressiveSwitchRow("Tools", false, { value = it })
        } }
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNode(isToggleable()).performClick().assertIsOff()
        runOnIdle { assertEquals(true, value) }
    }

    @Test fun disabledSettingsKeepTheirValuesAndBlockChanges() = runComposeUiTest {
        var clicks = 0
        setContent { MessengerTheme(dynamicColor = false) {
            ExpressiveSwitchRow("Managed model", true, { clicks++ }, enabled = false)
        } }
        onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun navigationKeepsAllDestinationsInCompactAndWideLayouts() = runComposeUiTest {
        var selection = ""
        val wide = mutableStateOf(false)
        setContent { MessengerTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
            if (wide.value) NavigationRailBar("conversations", { selection = it.screen.route })
            else BottomNavBar("conversations", { selection = it.screen.route })
        } }
        onNodeWithText("Agents").performClick()
        runOnIdle { assertEquals("agents", selection); wide.value = true }
        onNodeWithText("Settings").performClick()
        runOnIdle { assertEquals("settings", selection) }
    }
}
