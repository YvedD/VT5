package com.yvesds.vt5.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ALTIJD donker
private val VTColorSchemeDark: ColorScheme = darkColorScheme(
    primary = VT_Primary,
    onPrimary = VT_OnPrimary,
    secondary = VT_Primary,
    onSecondary = VT_OnPrimary,
    tertiary = VT_Primary,
    onTertiary = VT_OnPrimary,
    background = VT_Surface,
    onBackground = VT_OnSurface,
    surface = VT_Surface,
    onSurface = VT_OnSurface,
    error = Color(0xFFCF6679),
    onError = Color(0xFF1C1B1F),
    outline = VT_Outline
)

private val VTShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),     // gebruik voor knoppen
    extraLarge = RoundedCornerShape(6.dp)
)

@Composable
fun VT5DonkerThema(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VTColorSchemeDark,
        typography = Typography(),
        shapes = VTShapes,
        content = content
    )
}
