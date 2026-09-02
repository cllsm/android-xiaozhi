package com.xiaozhi.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF74DCC9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF96F5E4),
    inversePrimary = Color(0xFF006B5F),
    secondary = Color(0xFFFFB68A),
    onSecondary = Color(0xFF552100),
    secondaryContainer = Color(0xFF783200),
    onSecondaryContainer = Color(0xFFFFDBC6),
    tertiary = Color(0xFFBBD47C),
    onTertiary = Color(0xFF2A3600),
    tertiaryContainer = Color(0xFF404D00),
    onTertiaryContainer = Color(0xFFD6F095),
    background = Color(0xFF0F1614),
    onBackground = Color(0xFFDFE7E3),
    surface = Color(0xFF141C1A),
    onSurface = Color(0xFFDFE7E3),
    surfaceVariant = Color(0xFF1E2926),
    onSurfaceVariant = Color(0xFFA5B5AF),
    surfaceTint = Color(0xFF74DCC9),
    surfaceDim = Color(0xFF0B100F),
    surfaceBright = Color(0xFF3A4743),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171F1D),
    surfaceContainer = Color(0xFF1B2522),
    surfaceContainerHigh = Color(0xFF202B28),
    surfaceContainerHighest = Color(0xFF2A3632),
    inverseSurface = Color(0xFFDFE7E3),
    inverseOnSurface = Color(0xFF2C3532),
    outline = Color(0xFF374743),
    outlineVariant = Color(0xFF2C3B37),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
)

// 晨雾绿(Morning Mint):安静的学习氛围底色,暖橙作为活动状态的点缀色。
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2E4),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF74DCC9),
    secondary = Color(0xFF955D22),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF321200),
    tertiary = Color(0xFF4F7A2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5EDAD),
    onTertiaryContainer = Color(0xFF172600),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF18231F),
    surface = Color.White,
    onSurface = Color(0xFF18231F),
    surfaceVariant = Color(0xFFE5F0EA),
    onSurfaceVariant = Color(0xFF536761),
    surfaceTint = Color(0xFF006B5F),
    surfaceDim = Color(0xFFD6E9E1),
    surfaceBright = Color(0xFFFBFDFB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F8F4),
    surfaceContainer = Color(0xFFEBF4EF),
    surfaceContainerHigh = Color(0xFFE5F0EA),
    surfaceContainerHighest = Color(0xFFDFEAE4),
    inverseSurface = Color(0xFF2C3532),
    inverseOnSurface = Color(0xFFEFF6F2),
    outline = Color(0xFFA8BDB4),
    outlineVariant = Color(0xFFD8E6DF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color.Black
)

/**
 * 统一圆角节奏:小元素轻收、卡片中圆、大面板大圆,
 * 全 App 的 Button/Card/Chip 默认形状都从这里取值。
 */
val XiaozhiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

/** 扩展语义色的 CompositionLocal(随深浅主题切换) */
private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** 从 MaterialTheme 读取扩展语义色 */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

@Composable
fun XiaozhiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    MaterialTheme(
        colorScheme = colors,
        typography = XiaozhiTypography,
        shapes = XiaozhiShapes
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalExtendedColors provides extended
        ) {
            content()
        }
    }
}
