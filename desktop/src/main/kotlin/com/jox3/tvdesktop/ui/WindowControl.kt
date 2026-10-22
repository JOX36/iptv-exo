package com.jox3.tvdesktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment

/**
 * Pantalla completa y mini reproductor "siempre encima".
 * Guarda cómo estaba la ventana antes, para volver exactamente igual.
 */
class WindowControl(val state: WindowState, val window: ComposeWindow) {
    var mini by mutableStateOf(false); private set
    val fullscreen: Boolean get() = state.placement == WindowPlacement.Fullscreen

    private var before: Triple<WindowPlacement, DpSize, WindowPosition>? = null

    /** Cómo estaba la ventana antes de la pantalla completa (normal o maximizada). */
    private var beforeFull = WindowPlacement.Floating

    fun setFullscreen(on: Boolean) {
        if (on && mini) switchMini(false)
        if (on) {
            if (state.placement != WindowPlacement.Fullscreen) beforeFull = state.placement
            state.placement = WindowPlacement.Fullscreen
        } else if (state.placement == WindowPlacement.Fullscreen) {
            state.placement = beforeFull
        }
    }

    fun switchMini(on: Boolean) {
        if (on == mini) return
        if (on) {
            before = Triple(
                if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else state.placement,
                state.size, state.position,
            )
            state.placement = WindowPlacement.Floating
            val size = DpSize(480.dp, 270.dp)
            state.size = size
            // Esquina inferior derecha de la pantalla actual, sobre la barra de tareas
            val bounds = try {
                GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            } catch (_: Exception) { null }
            if (bounds != null) {
                state.position = WindowPosition(
                    (bounds.x + bounds.width - 480 - 24).dp,
                    (bounds.y + bounds.height - 270 - 24).dp,
                )
            }
            window.minimumSize = java.awt.Dimension(320, 180)
            window.isAlwaysOnTop = true
            mini = true
        } else {
            window.isAlwaysOnTop = false
            window.minimumSize = java.awt.Dimension(960, 600)
            before?.let { (pl, size, pos) ->
                state.placement = WindowPlacement.Floating
                state.size = size
                state.position = pos
                state.placement = pl
            }
            before = null
            mini = false
        }
    }
}

val LocalWindowControl = staticCompositionLocalOf<WindowControl?> { null }

/** true mientras se escribe en un campo de texto: los atajos de teclado no deben actuar. */
object Typing {
    var active by mutableStateOf(false)
}
