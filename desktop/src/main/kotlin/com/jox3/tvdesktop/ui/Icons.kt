package com.jox3.tvdesktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Iconos propios dibujados (no dependen de fuentes ni de paquetes externos). */
enum class Ico { PLAY, PAUSE, PREV, NEXT, VOLUME, MUTE, FULLSCREEN, EXIT_FULLSCREEN, MINI, STAR, STAR_FILLED,
    LIST, INFO, SUBTITLES, AUDIO, PICTURE, SLEEP, SEARCH, CLOSE, GUIDE, REFRESH, LIVE, USER, CLOCK }

@Composable
fun JxIcon(ico: Ico, size: Dp = 20.dp, color: Color = Jx.Text, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(ico, color) }
}

private fun DrawScope.drawIco(ico: Ico, c: Color) {
    val s = size.minDimension
    val w = s * 0.09f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = true) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
        if (close) close()
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), w, StrokeCap.Round)
    fun star(filled: Boolean) {
        val pts = (0 until 10).map { i ->
            val r = if (i % 2 == 0) 0.46f else 0.2f
            val a = Math.toRadians((-90 + i * 36).toDouble())
            (0.5f + r * cos(a).toFloat()) to (0.53f + r * sin(a).toFloat())
        }
        val pa = path(*pts.toTypedArray())
        if (filled) drawPath(pa, c) else drawPath(pa, c, style = stroke)
    }
    when (ico) {
        Ico.PLAY, Ico.LIVE -> drawPath(path(0.28f to 0.18f, 0.82f to 0.5f, 0.28f to 0.82f), c)
        Ico.PAUSE -> {
            drawRoundRect(c, p(0.24f, 0.18f), Size(s * 0.17f, s * 0.64f), CornerRadius(s * 0.04f))
            drawRoundRect(c, p(0.59f, 0.18f), Size(s * 0.17f, s * 0.64f), CornerRadius(s * 0.04f))
        }
        Ico.PREV -> { drawPath(path(0.8f to 0.2f, 0.35f to 0.5f, 0.8f to 0.8f), c); line(0.24f, 0.2f, 0.24f, 0.8f) }
        Ico.NEXT -> { drawPath(path(0.2f to 0.2f, 0.65f to 0.5f, 0.2f to 0.8f), c); line(0.76f, 0.2f, 0.76f, 0.8f) }
        Ico.VOLUME, Ico.MUTE, Ico.AUDIO -> {
            drawPath(path(0.12f to 0.38f, 0.3f to 0.38f, 0.52f to 0.18f, 0.52f to 0.82f, 0.3f to 0.62f, 0.12f to 0.62f), c)
            if (ico == Ico.MUTE) { line(0.66f, 0.36f, 0.9f, 0.64f); line(0.9f, 0.36f, 0.66f, 0.64f) }
            else {
                drawArc(c, -50f, 100f, false, p(0.48f, 0.3f), Size(s * 0.24f, s * 0.4f), style = stroke)
                drawArc(c, -50f, 100f, false, p(0.48f, 0.16f), Size(s * 0.42f, s * 0.68f), style = stroke)
            }
        }
        Ico.FULLSCREEN -> {
            line(0.15f, 0.38f, 0.15f, 0.15f); line(0.15f, 0.15f, 0.38f, 0.15f)
            line(0.62f, 0.15f, 0.85f, 0.15f); line(0.85f, 0.15f, 0.85f, 0.38f)
            line(0.85f, 0.62f, 0.85f, 0.85f); line(0.85f, 0.85f, 0.62f, 0.85f)
            line(0.38f, 0.85f, 0.15f, 0.85f); line(0.15f, 0.85f, 0.15f, 0.62f)
        }
        Ico.EXIT_FULLSCREEN -> {
            line(0.15f, 0.38f, 0.38f, 0.38f); line(0.38f, 0.38f, 0.38f, 0.15f)
            line(0.62f, 0.15f, 0.62f, 0.38f); line(0.62f, 0.38f, 0.85f, 0.38f)
            line(0.85f, 0.62f, 0.62f, 0.62f); line(0.62f, 0.62f, 0.62f, 0.85f)
            line(0.38f, 0.85f, 0.38f, 0.62f); line(0.38f, 0.62f, 0.15f, 0.62f)
        }
        Ico.MINI -> {
            drawRoundRect(c, p(0.1f, 0.18f), Size(s * 0.8f, s * 0.64f), CornerRadius(s * 0.06f), style = stroke)
            drawRoundRect(c, p(0.48f, 0.48f), Size(s * 0.34f, s * 0.26f), CornerRadius(s * 0.03f))
        }
        Ico.STAR -> star(false)
        Ico.STAR_FILLED -> star(true)
        Ico.LIST -> {
            listOf(0.25f, 0.5f, 0.75f).forEach { y -> drawCircle(c, s * 0.05f, p(0.17f, y)); line(0.33f, y, 0.86f, y) }
        }
        Ico.INFO -> {
            drawCircle(c, s * 0.4f, p(0.5f, 0.5f), style = stroke)
            line(0.5f, 0.45f, 0.5f, 0.72f); drawCircle(c, s * 0.055f, p(0.5f, 0.3f))
        }
        Ico.SUBTITLES -> {
            drawRoundRect(c, p(0.08f, 0.2f), Size(s * 0.84f, s * 0.6f), CornerRadius(s * 0.08f), style = stroke)
            line(0.22f, 0.45f, 0.42f, 0.45f); line(0.52f, 0.45f, 0.78f, 0.45f)
            line(0.22f, 0.62f, 0.56f, 0.62f); line(0.66f, 0.62f, 0.78f, 0.62f)
        }
        Ico.PICTURE -> {
            drawRoundRect(c, p(0.1f, 0.2f), Size(s * 0.8f, s * 0.6f), CornerRadius(s * 0.06f), style = stroke)
            line(0.22f, 0.5f, 0.32f, 0.5f); line(0.68f, 0.5f, 0.78f, 0.5f)
            drawPath(path(0.22f to 0.5f, 0.3f to 0.42f, 0.3f to 0.58f), c)
            drawPath(path(0.78f to 0.5f, 0.7f to 0.42f, 0.7f to 0.58f), c)
        }
        Ico.SLEEP -> {
            val moon = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(p(0.14f, 0.14f), Size(s * 0.66f, s * 0.66f)))
            }
            drawPath(moon, c)
            drawCircle(Jx.Bg, s * 0.3f, p(0.66f, 0.36f))
        }
        Ico.SEARCH -> { drawCircle(c, s * 0.27f, p(0.43f, 0.43f), style = stroke); line(0.63f, 0.63f, 0.86f, 0.86f) }
        Ico.CLOSE -> { line(0.22f, 0.22f, 0.78f, 0.78f); line(0.78f, 0.22f, 0.22f, 0.78f) }
        Ico.GUIDE -> {
            drawRoundRect(c, p(0.1f, 0.16f), Size(s * 0.8f, s * 0.68f), CornerRadius(s * 0.06f), style = stroke)
            line(0.1f, 0.38f, 0.9f, 0.38f); line(0.38f, 0.38f, 0.38f, 0.84f); line(0.64f, 0.38f, 0.64f, 0.84f)
        }
        Ico.REFRESH -> {
            drawArc(c, 30f, 290f, false, p(0.18f, 0.18f), Size(s * 0.64f, s * 0.64f), style = stroke)
            drawPath(path(0.84f to 0.22f, 0.86f to 0.48f, 0.6f to 0.42f), c)
        }
        Ico.USER -> {
            drawCircle(c, s * 0.18f, p(0.5f, 0.32f))
            drawArc(c, 180f, 180f, true, p(0.18f, 0.56f), Size(s * 0.64f, s * 0.6f))
        }
        Ico.CLOCK -> {
            drawCircle(c, s * 0.4f, p(0.5f, 0.5f), style = stroke)
            line(0.5f, 0.5f, 0.5f, 0.28f); line(0.5f, 0.5f, 0.66f, 0.6f)
        }
    }
}
