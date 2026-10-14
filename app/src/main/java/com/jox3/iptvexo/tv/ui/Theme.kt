package com.jox3.iptvexo.tv.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.jox3.iptvexo.R

/** Paleta del diseño "JOX3 TV" (fondo nocturno + acento cian). */
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

    /** Tonos para tarjetas sin imagen: estables por nombre. */
    private val tones = listOf(
        Color(0xFF1C2B3B), Color(0xFF2A2133), Color(0xFF33281C), Color(0xFF18302B),
        Color(0xFF22303F), Color(0xFF3A2A1D), Color(0xFF2C2440), Color(0xFF1E3328),
    )
    fun tone(key: String) = tones[(key.hashCode() and 0x7fffffff) % tones.size]
}

val Display = FontFamily(
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)
val Body = FontFamily(
    Font(R.font.exo2_regular, FontWeight.Normal),
    Font(R.font.exo2_medium, FontWeight.Medium),
    Font(R.font.exo2_semibold, FontWeight.SemiBold),
)

/**
 * Tamaños pensados para la "pantalla de 960×540 dp" de Android TV (1080p a 2×):
 * se leen bien a 3 metros sin desperdiciar espacio.
 */
object Type {
    val Hero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp)
    val H1 = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 30.sp)
    val H2 = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val Eyebrow = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
    val Body = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val Label = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val Small = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontWeight = FontWeight.Normal, fontSize = 11.sp)
    val Number = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

/** Márgenes seguros de TV (zona que nunca recorta el televisor). */
object Safe {
    val H = 48.dp
    val V = 27.dp
}

/**
 * Desplazamiento de listas al moverse con el control: el elemento enfocado se
 * mantiene en una "zona cómoda" (lejos de los bordes) y la lista avanza con una
 * curva suave, igual que en la interfaz web que afinamos antes.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ComfortScroll = object : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> =
        tween(durationMillis = 220, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        val lead = containerSize * 0.18f
        val trail = containerSize * 0.82f
        return when {
            size >= trail - lead -> offset - lead
            offset < lead -> offset - lead
            end > trail -> end - trail
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Jx.Accent,
            onPrimary = Jx.OnAccent,
            background = Jx.Bg,
            onBackground = Jx.Text,
            surface = Jx.Surface,
            onSurface = Jx.Text,
            surfaceVariant = Jx.SurfaceHi,
            onSurfaceVariant = Jx.Muted,
        ),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortScroll, content = content)
    }
}
