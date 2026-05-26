package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ClayOrange,
    secondary = PineGreen,
    tertiary = GlowCoral,
    background = CharcoalSlate, // #FCF8F7
    surface = CardShale,       // #F3EFEF
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = SoftWhite,   // #1D1B1B
    onSurface = SoftWhite,      // #1D1B1B
    surfaceVariant = FineClayBackground,
    onSurfaceVariant = SoftGray
)

private val LightColorScheme = lightColorScheme(
    primary = ClayOrange,
    secondary = PineGreen,
    tertiary = GlowCoral,
    background = CharcoalSlate, // #FCF8F7
    surface = CardShale,       // #F3EFEF
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = SoftWhite,   // #1D1B1B
    onSurface = SoftWhite,      // #1D1B1B
    surfaceVariant = FineClayBackground,
    onSurfaceVariant = SoftGray
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic colors to keep our beautiful brand identity prominent!
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
