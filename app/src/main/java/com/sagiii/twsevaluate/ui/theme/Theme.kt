package com.sagiii.twsevaluate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF38BDF8)
private val BlueDark = Color(0xFF0369A1)
private val Background = Color(0xFF0B1220)

private val DarkColors = darkColorScheme(
    primary = Blue,
    secondary = BlueDark,
    background = Background,
    surface = Color(0xFF141C2E),
)

private val LightColors = lightColorScheme(
    primary = BlueDark,
    secondary = Blue,
)

@Composable
fun TwsEvaluateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
