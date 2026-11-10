package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsItemTest {
    @Test fun pressAnimationDoesNotRecomposeTheTextEveryFrame() = runComposeUiTest {
        val interactions = MutableInteractionSource()
        var compositions = 0
        setContent { MessengerTheme(dynamicColor = false) {
            SettingsItem(onClick = {}, interactionSource = interactions, index = 1, count = 3) {
                SideEffect { compositions++ }
                Text("Animated setting")
            }
        } }
        mainClock.autoAdvance = false
        val press = PressInteraction.Press(Offset.Zero)
        runOnIdle { runBlocking { interactions.emit(press) } }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val before = compositions
        repeat(12) { mainClock.advanceTimeByFrame(); waitForIdle() }
        assertEquals(before, compositions, "Outline frames must not recompose the setting content")
        runOnIdle { runBlocking { interactions.emit(PressInteraction.Cancel(press)) } }
        mainClock.advanceTimeBy(1000)
        onNodeWithText("Animated setting").assertExists()
    }

    @Test fun dynamicGroupsKeepItemStateWhenReorderedAndUpdateTheirPositions() = runComposeUiTest {
        val rows = mutableStateOf(listOf("a", "b", "c"))
        setContent { MessengerTheme(dynamicColor = false) {
            LazyColumn(Modifier.height(300.dp)) {
                settingsItems(rows.value, key = { it }, contentType = "test-setting") { id ->
                    var count by remember { mutableIntStateOf(0) }
                    val position = LocalSettingsItemPosition.current
                    ExpressiveRow({ count++ }) { Text("$id:$count:${position.index}/${position.count}") }
                }
            }
        } }
        onNodeWithText("b:0:1/3").performClick()
        runOnIdle { rows.value = listOf("b", "a") }
        onNodeWithText("b:1:0/2").assertExists()
        onNodeWithText("a:0:1/2").assertExists()
    }

    @Test fun longCollectionsOnlyComposeVisibleEntries() = runComposeUiTest {
        val composed = mutableSetOf<Int>()
        setContent { MessengerTheme(dynamicColor = false) {
            LazyColumn(Modifier.height(200.dp)) {
                settingsItems((0 until 1000).toList(), key = { it }, contentType = "test-setting") { id ->
                    SideEffect { composed += id }
                    ExpressiveRow({}) { Text("Setting $id") }
                }
            }
        } }
        runOnIdle { assertTrue(composed.size < 30, "Expected viewport composition, got ${composed.size} rows") }
    }
    @Test fun largeFontAndRtlKeepTrailingControlsSeparateFromTheText() = runComposeUiTest {
        setContent { MessengerTheme(dynamicColor = false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(320.dp)) {
                    SettingsItem(
                        onClick = {}, modifier = Modifier.testTag("row"),
                        leadingContent = { Box(Modifier.size(24.dp).testTag("leading")) },
                        trailingContent = { Box(Modifier.size(48.dp).testTag("trailing")) },
                        supportingContent = { Text("A setting description that wraps onto several lines") },
                    ) { Text("Setting title", Modifier.testTag("title")) }
                }
            }
        } }
        val title = onNodeWithTag("title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val leading = onNodeWithTag("leading", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val trailing = onNodeWithTag("trailing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val row = onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        assertTrue(trailing.right <= title.left)
        assertTrue(title.right <= leading.left)
        assertTrue(title.bottom <= row.bottom)
    }

}
