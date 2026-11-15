/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.theme.ThemeMode
import cc.ptoe.messenger.presentation.ui.components.MainScaffold

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppContainerHolder.instance.themePreferences.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)

            MessengerTheme(themeMode = themeMode) {
                MainScaffold()
                var showPushConsent by remember { mutableStateOf(!PushSetup.hasConsent(this)) }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                if (showPushConsent) {
                    AlertDialog(
                        onDismissRequest = { showPushConsent = false },
                        title = { Text(stringResource(R.string.push_consent_title)) },
                        text = {
                            TextButton(onClick = {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jiguang.cn/license/privacy")))
                            }) { Text(stringResource(R.string.push_consent_message)) }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                PushSetup.enable(this)
                                showPushConsent = false
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) { Text(stringResource(R.string.push_consent_enable)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPushConsent = false }) {
                                Text(stringResource(R.string.push_consent_later))
                            }
                        },
                    )
                }
            }
        }
    }
}
