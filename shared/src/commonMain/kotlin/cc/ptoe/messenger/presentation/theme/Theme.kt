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

package cc.ptoe.messenger.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BD5C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFA7F2DD),
    secondary = Color(0xFFB6CCD0),
    onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF354B4E),
    onSecondaryContainer = Color(0xFFD2E9EC),
    tertiary = Color(0xFFF5B5A7),
    onTertiary = Color(0xFF4A170F),
    tertiaryContainer = Color(0xFF6A2E24),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF0D1513),
    surface = Color(0xFF0D1513),
    surfaceVariant = Color(0xFF3F4946),
    onSurface = Color(0xFFE0E3DF),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8DF5DC),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A6265),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8EB),
    onSecondaryContainer = Color(0xFF051F22),
    tertiary = Color(0xFF985044),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD2),
    onTertiaryContainer = Color(0xFF3B0803),
    background = Color(0xFFF6FBF8),
    surface = Color(0xFFF6FBF8),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurface = Color(0xFF171D1A),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7974),
    outlineVariant = Color(0xFFBFC9C3)
)

private val ExpressiveShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
)

private val ExpressiveTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

/**
 * Platform dynamic color scheme (Material You on Android 12+,
 * `null` everywhere else so the static schemes apply).
 */
@Composable
expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

/** Platform side effects such as status-bar icon colors (no-op on Desktop). */
@Composable
expect fun PlatformThemeSideEffects(
    darkTheme: Boolean,
    navigationBarColor: Color
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessengerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor -> platformDynamicColorScheme(darkTheme)
            ?: if (darkTheme) DarkColorScheme else LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    PlatformThemeSideEffects(
        darkTheme = darkTheme,
        navigationBarColor = colorScheme.surface
    )

    // Material 3 Expressive：expressive MotionScheme 带来规范的弹性组件动效
    //（形状变形、容器动画等），配合 ExpressiveShapes / ExpressiveTypography。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = ExpressiveTypography,
        content = content
    )
}
