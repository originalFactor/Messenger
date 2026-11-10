package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
class SettingsScrollTest {
    @Test fun settingsGroupsScrollByTouchAndMouseWheel() = runComposeUiTest {
        val state = LazyListState()
        setContent { MessengerTheme(dynamicColor = false) {
            val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            ExpressiveScaffold(modifier = Modifier.size(360.dp, 500.dp), topBar = {
                LargeFlexibleTopAppBar(title = { Text("Settings") }, scrollBehavior = scroll)
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().nestedScroll(scroll.nestedScrollConnection).testTag("list")) {
                        item { Box(Modifier.height(200.dp)) }
                        item {
                            SettingsGroup {
                                repeat(8) { index -> item { ListItem("Setting $index", subtitle = "Description", onClick = {}) } }
                            }
                        }
                        item { Text("Bottom") }
                    }
                }
            }
        } }
        onNodeWithTag("list").performTouchInput { swipeUp() }
        waitForIdle()
        runOnIdle { assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0, "Touch must scroll settings") }
        runOnIdle { runBlocking { state.scrollToItem(0) } }
        val before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        onNodeWithTag("list").performMouseInput { moveTo(center); scroll(20f) }
        waitForIdle()
        runOnIdle { assertTrue(before != (state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset), "Mouse wheel must scroll settings") }
        onNodeWithTag("list").performScrollToNode(hasText("Bottom"))
        onNodeWithText("Bottom").assertIsDisplayed()
    }
    @Test fun sharedAppBarStillCollapsesAndAllowsTheListToScroll() = runComposeUiTest {
        val state = LazyListState()
        setContent { MessengerTheme(dynamicColor = false) {
            ExpressiveScaffold(modifier = Modifier.size(360.dp, 500.dp), topBar = {
                ExpressiveTopAppBar(title = { Text("Settings") })
            }) { padding ->
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(padding).testTag("list")) {
                    items(30) { Text("Setting $it", Modifier.height(60.dp)) }
                }
            }
        } }
        val expandedTitle = onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("list").performTouchInput { swipeUp() }
        waitForIdle()
        runOnIdle { assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
        val collapsedTitle = onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot.top
        assertTrue(collapsedTitle < expandedTitle, "The shared app bar must still collapse")
    }

    @Test fun pagesWithoutACollapsingAppBarDoNotConsumeListDrags() = runComposeUiTest {
        val state = LazyListState()
        setContent { MessengerTheme(dynamicColor = false) {
            ExpressiveScaffold(modifier = Modifier.size(360.dp, 500.dp), topBar = { Text("Settings") }) { padding ->
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(padding).testTag("list")) {
                    items(30) { Text("Setting $it", Modifier.height(60.dp)) }
                }
            }
        } }
        onNodeWithTag("list").performTouchInput { swipeUp() }
        waitForIdle()
        runOnIdle { assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
    }

}
