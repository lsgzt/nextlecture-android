package com.gndec.timetable.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val GndecTeal = Color(0xFF0B7978)
val GndecTealDark = Color(0xFF056466)
val GndecAqua = Color(0xFFE6F4F2)
val GndecAquaStrong = Color(0xFFD3EEEB)
val GndecBackground = Color(0xFFFCFCFA)
val GndecInk = Color(0xFF17202B)
val GndecMuted = Color(0xFF637079)
val GndecLine = Color(0xFFE6ECEB)
val GndecOrange = Color(0xFFE49A2A)
val GndecOrangeSoft = Color(0xFFFFF1DE)
val GndecGreen = Color(0xFF42B86D)
val GndecGreenSoft = Color(0xFFE3F7EA)

private val LightColors = lightColorScheme(
    primary = GndecTeal,
    onPrimary = Color.White,
    primaryContainer = GndecAquaStrong,
    onPrimaryContainer = GndecTealDark,
    secondary = GndecTealDark,
    onSecondary = Color.White,
    secondaryContainer = GndecAqua,
    onSecondaryContainer = GndecTealDark,
    tertiary = GndecOrange,
    onTertiary = Color.White,
    tertiaryContainer = GndecOrangeSoft,
    onTertiaryContainer = Color(0xFF6E4300),
    background = GndecBackground,
    onBackground = GndecInk,
    surface = Color.White,
    onSurface = GndecInk,
    surfaceVariant = GndecAqua,
    onSurfaceVariant = GndecMuted,
    outline = Color(0xFF6E9998),
    outlineVariant = GndecLine
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DDED7),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF075A59),
    onPrimaryContainer = Color(0xFFB5F2EC),
    secondary = Color(0xFF8BDDD7),
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF173E3D),
    onSecondaryContainer = Color(0xFFB5F2EC),
    tertiary = Color(0xFFFFC766),
    onTertiary = Color(0xFF432900),
    tertiaryContainer = Color(0xFF6B4708),
    onTertiaryContainer = Color(0xFFFFDE9F),
    background = Color(0xFF0D1515),
    onBackground = Color(0xFFE8F1F0),
    surface = Color(0xFF152020),
    onSurface = Color(0xFFE8F1F0),
    surfaceVariant = Color(0xFF1F3534),
    onSurfaceVariant = Color(0xFFB9CCCA),
    outline = Color(0xFF7AAEAA),
    outlineVariant = Color(0xFF304B49)
)

private val LightTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, color = GndecInk),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, color = GndecInk),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, color = GndecInk),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, color = GndecInk),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, color = GndecInk),
        bodyLarge = bodyLarge.copy(color = GndecInk),
        bodyMedium = bodyMedium.copy(color = GndecInk),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun GndecTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = if (dark) Typography() else LightTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
