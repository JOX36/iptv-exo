package com.jox3.tvdesktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jox3.tvdesktop.player.Aspect
import com.jox3.tvdesktop.player.VideoPlayer
import kotlin.math.roundToInt

/**
 * Dibuja el video del reproductor según el formato elegido.
 * El cuadro se lee solo al dibujar: un cuadro nuevo repinta esta zona sin
 * recomponer el resto de la pantalla (así el video no gasta de más).
 */
@Composable
fun VideoSurface(player: VideoPlayer, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black)
        @Suppress("UNUSED_VARIABLE") val v = player.frameVersion // repintar con cada cuadro
        val img = player.frame ?: return@Canvas
        val box = size
        val (dst, srcRect) = layout(player.aspect, player.videoAspect, img.width, img.height, box)
        drawImage(
            image = img,
            srcOffset = IntOffset(srcRect.first, srcRect.second),
            srcSize = IntSize(srcRect.third, srcRect.fourth),
            dstOffset = IntOffset(dst.first.x.roundToInt(), dst.first.y.roundToInt()),
            dstSize = IntSize(dst.second.width.roundToInt().coerceAtLeast(1), dst.second.height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private data class Quad(val first: Int, val second: Int, val third: Int, val fourth: Int)

/** Dónde y cómo se dibuja el cuadro: destino (posición, tamaño) y parte del cuadro que se usa. */
private fun layout(aspect: Aspect, videoAspect: Float, w: Int, h: Int, box: Size): Pair<Pair<Offset, Size>, Quad> {
    val full = Quad(0, 0, w, h)
    fun centered(ar: Float): Pair<Offset, Size> {
        val boxAr = box.width / box.height
        val size = if (ar > boxAr) Size(box.width, box.width / ar) else Size(box.height * ar, box.height)
        return Offset((box.width - size.width) / 2, (box.height - size.height) / 2) to size
    }
    return when (aspect) {
        Aspect.STRETCH -> (Offset.Zero to box) to full
        Aspect.FIT -> centered(videoAspect) to full
        Aspect.R16_9 -> centered(16f / 9f) to full
        Aspect.R4_3 -> centered(4f / 3f) to full
        Aspect.CROP -> {
            // Llena la pantalla recortando lo que sobra del video
            val boxAr = box.width / box.height
            val src = if (videoAspect > boxAr) {
                val cw = (w * boxAr / videoAspect).roundToInt().coerceIn(1, w)
                Quad((w - cw) / 2, 0, cw, h)
            } else {
                val ch = (h * videoAspect / boxAr).roundToInt().coerceIn(1, h)
                Quad(0, (h - ch) / 2, w, ch)
            }
            (Offset.Zero to box) to src
        }
    }
}
