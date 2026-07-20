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
    primary = Color(0xFF087E8B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7ECEA),
    onPrimaryContainer = Color(0xFF00373C),
    secondary = Color(0xFFE76F51),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9CE),
    onSecondaryContainer = Color(0xFF3B0D06),
    tertiary = Color(0xFFD99A22),
    onTertiary = Color(0xFF241800),
    tertiaryContainer = Color(0xFFFFE3A5),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF4F6F3),
    onBackground = Color(0xFF17211F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17211F),
    surfaceVariant = Color(0xFFE2E9E5),
    onSurfaceVariant = Color(0xFF4F5A56),
    outline = Color(0xFF7A8782),
    outlineVariant = Color(0xFFC8D2CE),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF70D6D7),
    onPrimary = Color(0xFF00373C),
    primaryContainer = Color(0xFF00545C),
    onPrimaryContainer = Color(0xFFB7ECEA),
    secondary = Color(0xFFFFB59F),
    onSecondary = Color(0xFF5C180A),
    secondaryContainer = Color(0xFF8B3B28),
    onSecondaryContainer = Color(0xFFFFD9CE),
    tertiary = Color(0xFFF1C65B),
    onTertiary = Color(0xFF3F2C00),
    tertiaryContainer = Color(0xFF604700),
    onTertiaryContainer = Color(0xFFFFE3A5),
    background = Color(0xFF101817),
    onBackground = Color(0xFFE2E9E5),
    surface = Color(0xFF17201E),
    onSurface = Color(0xFFE2E9E5),
    surfaceVariant = Color(0xFF3D4945),
    onSurfaceVariant = Color(0xFFBECBC5),
    outline = Color(0xFF899791),
    outlineVariant = Color(0xFF3D4945),
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
