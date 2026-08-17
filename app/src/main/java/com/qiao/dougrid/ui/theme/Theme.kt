package com.qiao.dougrid.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.qiao.dougrid.data.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF765548),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E1DA),
    onPrimaryContainer = Color(0xFF2E1710),
    secondary = Color(0xFF666248),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E5CA),
    onSecondaryContainer = Color(0xFF201F0D),
    tertiary = Color(0xFF7A6240),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E3C3),
    onTertiaryContainer = Color(0xFF2A1C08),
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF25211F),
    surface = Color(0xFFFFFCF8),
    onSurface = Color(0xFF25211F),
    surfaceVariant = Color(0xFFEAE4DE),
    onSurfaceVariant = Color(0xFF5E5651),
    outline = Color(0xFF8B7E77),
    outlineVariant = Color(0xFFD5CCC6),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4BBAA),
    onPrimary = Color(0xFF43261C),
    primaryContainer = Color(0xFF5D3E33),
    onPrimaryContainer = Color(0xFFF0E1DA),
    secondary = Color(0xFFCAC6A7),
    onSecondary = Color(0xFF34321F),
    secondaryContainer = Color(0xFF4B4932),
    onSecondaryContainer = Color(0xFFE9E5CA),
    tertiary = Color(0xFFDDC394),
    onTertiary = Color(0xFF3E2E14),
    tertiaryContainer = Color(0xFF584724),
    onTertiaryContainer = Color(0xFFF2E3C3),
    background = Color(0xFF1A1715),
    onBackground = Color(0xFFEDE7E2),
    surface = Color(0xFF221E1B),
    onSurface = Color(0xFFEDE7E2),
    surfaceVariant = Color(0xFF4B433E),
    onSurfaceVariant = Color(0xFFD0C5BE),
    outline = Color(0xFF9A8D85),
    outlineVariant = Color(0xFF4B433E),
    error = Color(0xFFFFB4AB),
)

private val DouGridShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val DouGridTypography = Typography().copy(
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Typography().titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun DouGridTheme(
    mode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = DouGridTypography,
        shapes = DouGridShapes,
        content = content,
    )
}
