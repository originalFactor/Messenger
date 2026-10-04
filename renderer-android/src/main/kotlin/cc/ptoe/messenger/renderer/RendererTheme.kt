/*
 * Copyright 2026 ECSDevs
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

package cc.ptoe.messenger.renderer

import android.graphics.Bitmap

/**
 * Theme tokens for the native chat renderer, resolved from the host's
 * MaterialTheme (Compose side) so the View-based renderer follows light/dark
 * and dynamic color exactly like the rest of the app. The renderer module has
 * no resources of its own — localized labels and decoded avatar bitmaps ride
 * in here.
 */
data class RendererTheme(
    // Bubble palette (Google Messages style: user primary / assistant surfaceContainerHigh)
    @androidx.annotation.ColorInt val userBubble: Int,
    @androidx.annotation.ColorInt val onUserBubble: Int,
    @androidx.annotation.ColorInt val aiBubble: Int,
    @androidx.annotation.ColorInt val onAiBubble: Int,
    @androidx.annotation.ColorInt val errorBubble: Int,
    @androidx.annotation.ColorInt val onErrorBubble: Int,
    // Content palette
    @androidx.annotation.ColorInt val onSurfaceVariant: Int,
    @androidx.annotation.ColorInt val surfaceContainerHighest: Int,
    @androidx.annotation.ColorInt val secondaryContainer: Int,
    @androidx.annotation.ColorInt val onSecondaryContainer: Int,
    @androidx.annotation.ColorInt val outlineVariant: Int,
    @androidx.annotation.ColorInt val primary: Int,
    // Localized labels (renderer module carries no resources)
    val errorTitle: String,
    val retryAction: String,
    val thinkingTitle: String,
    val copyAction: String,
    val copiedToast: String,
    val runningLabel: String,
    val successLabel: String,
    val failedLabel: String,
    val resultLabel: String,
    val terminalToolName: String,
    // Avatars (decoded by the Compose side; null → vector fallback)
    val assistantAvatar: Bitmap?,
    val userAvatar: Bitmap?
)
