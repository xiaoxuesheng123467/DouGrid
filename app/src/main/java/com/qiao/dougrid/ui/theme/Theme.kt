package com.qiao.dougrid.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.qiao.dougrid.data.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E8),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFFB33E52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF3F0012),
    tertiary = Color(0xFF7B5C00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF8C),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181D1C),
    surface = Color(0xFFFCFDFC),
    onSurface = Color(0xFF181D1C),
    surfaceVariant = Color(0xFFDCE5E2),
    onSurfaceVariant = Color(0xFF404946),
    outline = Color(0xFF707976),
    outlineVariant = Color(0xFFBFC9C6),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81D5CD),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9EF2E8),
    secondary = Color(0xFFFFB1BE),
    onSecondary = Color(0xFF670025),
    secondaryContainer = Color(0xFF8F263B),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFF2C94C),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5D4400),
    onTertiaryContainer = Color(0xFFFFDF8C),
    background = Color(0xFF101514),
    onBackground = Color(0xFFDFE4E2),
    surface = Color(0xFF141A18),
    onSurface = Color(0xFFDFE4E2),
    surfaceVariant = Color(0xFF404946),
    onSurfaceVariant = Color(0xFFBFC9C6),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF404946),
    error = Color(0xFFFFB4AB),
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
        typography = Typography(),
        content = content,
    )
}
