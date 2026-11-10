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

package cc.ptoe.messenger.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MotionScheme
import androidx.compose.ui.graphics.Color

@Composable
fun MessengerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Color(0xFF8BD5C1), primaryDim = Color(0xFF6CB9A5),
            primaryContainer = Color(0xFF005143), onPrimary = Color(0xFF00382E), onPrimaryContainer = Color(0xFFA7F2DD),
            secondary = Color(0xFFB6CCD0), secondaryDim = Color(0xFF97ADB1),
            secondaryContainer = Color(0xFF354B4E), onSecondary = Color(0xFF1C3437), onSecondaryContainer = Color(0xFFD2E9EC),
            tertiary = Color(0xFFF5B5A7), tertiaryDim = Color(0xFFD4998C),
            tertiaryContainer = Color(0xFF6A2E24), onTertiary = Color(0xFF4A170F), onTertiaryContainer = Color(0xFFFFDAD2),
            surfaceContainerLow = Color(0xFF151D1A), surfaceContainer = Color(0xFF19211E), surfaceContainerHigh = Color(0xFF242C29),
        ),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
