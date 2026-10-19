package com.scanpdf.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val BrandBlue = Color(0xFF1F6FEB)
val InkDark = Color(0xFF1B2430)
val InkSub = Color(0xFF5B6472)
val CanvasBg = Color(0xFFF6F7F9)

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF1FF),
    onPrimaryContainer = Color(0xFF10233F),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    background = CanvasBg,
    onBackground = InkDark,
    surface = Color.White,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFEFF2F7),
    onSurfaceVariant = InkSub,
    outline = Color(0xFFDDE3EC),
    outlineVariant = Color(0xFFE9EDF3)
)

@Composable
fun ScanPdfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}
