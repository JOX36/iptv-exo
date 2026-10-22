package com.jox3.tvdesktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/** Mismos colores que la app Android (tv/ui/Theme.kt). */
object Jx {
    val Bg = Color(0xFF070A0F)
    val Rail = Color(0xFF0A0E14)
    val Surface = Color(0xFF111923)
    val SurfaceHi = Color(0xFF152131)
    val Line = Color(0xFF1F2A38)
    val Text = Color(0xFFEAF4FA)
    val TextSoft = Color(0xFFC9D8E2)
    val Muted = Color(0xFF9DB3C2)
    val Faint = Color(0xFF7F95A5)
    val Accent = Color(0xFF00D4FF)
    val OnAccent = Color(0xFF04121A)
    val Live = Color(0xFFFF5A4E)
    val Gold = Color(0xFFFFC861)
    val Ok = Color(0xFF7CF29A)
}

/** Fuente Exo 2 (la misma de la app Android), incluida en el programa. */
val Exo2 = FontFamily(
    Font("font/exo2_regular.ttf", FontWeight.Normal),
    Font("font/exo2_medium.ttf", FontWeight.Medium),
    Font("font/exo2_semibold.ttf", FontWeight.SemiBold),
    Font("font/exo2_semibold.ttf", FontWeight.Bold),
)

private val base = TextStyle(fontFamily = Exo2, color = Jx.Text)

private val JxTypography = Typography(
    displaySmall = base.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.copy(fontSize = 15.sp),
    bodyMedium = base.copy(fontSize = 14.sp),
    bodySmall = base.copy(fontSize = 12.sp, color = Jx.Muted),
    labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val JxColors = darkColorScheme(
    primary = Jx.Accent,
    onPrimary = Jx.OnAccent,
    secondary = Jx.Accent,
    onSecondary = Jx.OnAccent,
    background = Jx.Bg,
    onBackground = Jx.Text,
    surface = Jx.Surface,
    onSurface = Jx.Text,
    surfaceVariant = Jx.SurfaceHi,
    onSurfaceVariant = Jx.Muted,
    outline = Jx.Line,
    error = Jx.Live,
)

@Composable
fun JxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JxColors, typography = JxTypography, content = content)
}
