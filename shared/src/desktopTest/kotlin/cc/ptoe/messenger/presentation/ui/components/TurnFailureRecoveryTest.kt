/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_not_now
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.chat_failure_review
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TurnFailureRecoveryTest {
    @Test fun deferredFailureRemainsReachableWhileOtherActionsWork() {
        val notNow = runBlocking { getString(Res.string.action_not_now) }
        val review = runBlocking { getString(Res.string.chat_failure_review) }
        val retry = runBlocking { getString(Res.string.action_retry) }
        runComposeUiTest {
            val failure = mutableStateOf<String?>("database unavailable")
            var navigationActions = 0
            var retries = 0
            setContent {
                MessengerTheme(dynamicColor = false) {
                    Column {
                        TextButton(onClick = { navigationActions++ }) { Text("Continue") }
                        failure.value?.let { TurnFailureRecovery(it, onRetry = { retries++; failure.value = null }) }
                    }
                }
            }
            onNodeWithText("database unavailable").assertIsDisplayed()
            onNodeWithText(notNow).performClick()
            onNodeWithText("database unavailable").assertDoesNotExist()
            onNodeWithText("Continue").performClick()
            onNodeWithText(review).assertIsDisplayed().performClick()
            onNodeWithText("database unavailable").assertIsDisplayed()
            runOnIdle { assertEquals(1, navigationActions); assertEquals(0, retries) }
            onNodeWithText(retry).performClick()
            onNodeWithText(review).assertDoesNotExist()
            runOnIdle { assertEquals(1, retries) }
        }
    }
}
