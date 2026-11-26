package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LunaLightColorScheme = lightColorScheme(
    primary = Pinky500,
    onPrimary = Color.White,
    primaryContainer = Pinky100,
    onPrimaryContainer = Pinky800,
    secondary = Pinky600,
    onSecondary = Color.White,
    secondaryContainer = Pinky200,
    onSecondaryContainer = Pinky900,
    tertiary = Pinky700,
    onTertiary = Color.White,
    background = Pinky50,
    onBackground = InkPrimary,
    surface = Color.White,
    onSurface = InkPrimary,
    surfaceVariant = Pinky50,
    onSurfaceVariant = InkSecondary,
    outline = Pinky200,
    outlineVariant = Pinky100,
    error = RoseDanger,
    onError = Color.White,
    errorContainer = RoseDangerContainer,
    onErrorContainer = RoseDanger
)

private val LunaDarkColorScheme = darkColorScheme(
    primary = Pinky400,
    onPrimary = Color(0xFF3E061B),
    primaryContainer = Color(0xFF4A182D),
    onPrimaryContainer = Pinky100,
    secondary = Pinky300,
    onSecondary = Color(0xFF3E061B),
    secondaryContainer = DarkBorder,
    onSecondaryContainer = Pinky200,
    tertiary = Pinky200,
    onTertiary = Color(0xFF3E061B),
    background = DarkBg,
    onBackground = Pinky50,
    surface = DarkCard,
    onSurface = Pinky50,
    surfaceVariant = DarkCardElevated,
    onSurfaceVariant = Color(0xFFCBB8C4),
    outline = DarkBorder,
    outlineVariant = Color(0xFF2D212A),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = RoseDangerDarkContainer,
    onErrorContainer = Color(0xFFFDA4AF)
)

private val LunaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LunaChanTheme(
    darkTheme: Boolean,
    isArabic: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) LunaDarkColorScheme else LunaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = lunaTypography(isArabic),
        shapes = LunaShapes,
        content = content
    )
}
