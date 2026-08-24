package com.xiaozhi.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color.White,
    secondary = Color(0xFFFFA726),
    onSecondary = Color(0xFF201300),
    tertiary = Color(0xFF66BB6A),
    onTertiary = Color.White,
    background = Color(0xFF16213E),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A2E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF1F2940),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF263148),
    error = Color(0xFFEF5350)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0288D1),
    onPrimary = Color.White,
    secondary = Color(0xFFFB8C00),
    onSecondary = Color(0xFF201300),
    tertiary = Color(0xFF43A047),
    onTertiary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFEDF2F7),
    onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFE0E0E0),
    error = Color(0xFFE53935)
)

@Composable
fun XiaozhiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground
        ) {
            content()
        }
    }
}
