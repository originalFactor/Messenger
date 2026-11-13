package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.navigation.Screen
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BottomNavBarTest {
    @Test fun fullWidthBarKeepsEveryDestinationVisibleAndClickable() = runComposeUiTest {
        val width = mutableStateOf(360)
        val selected = mutableStateOf(Screen.Conversations.route)
        setContent {
            MessengerTheme(dynamicColor = false) {
                Box(Modifier.width(width.value.dp)) {
                    BottomNavBar(selected.value, { selected.value = it.screen.route }, Modifier.fillMaxWidth())
                }
            }
        }
        for (barWidth in listOf(360, 480)) {
            runOnIdle { width.value = barWidth }
            val destinations = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            destinations.assertCountEquals(4)
            val bounds = destinations.fetchSemanticsNodes().map { it.boundsInRoot }
            val totalWidth = bounds.last().right - bounds.first().left
            bounds.forEachIndexed { index, item ->
                destinations[index].assertIsDisplayed()
                assertTrue(item.width <= totalWidth / 4 + 1f, "Destination $index takes more than its share: $bounds")
                if (index > 0) assertTrue(item.left >= bounds[index - 1].right - 1f)
                destinations[index].performClick()
                runOnIdle { assertEquals(BottomNavItem.items[index].screen.route, selected.value) }
            }
        }
    }
}
