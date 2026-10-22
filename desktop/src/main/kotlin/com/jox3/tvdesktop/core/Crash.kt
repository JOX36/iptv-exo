package com.jox3.tvdesktop.core

import java.awt.Desktop
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Manejo de fallos inesperados: todo queda en el registro y, si el fallo es de
 * la ventana, se muestra un aviso amable en vez de que el programa desaparezca sin más.
 */
object Crash {
    @Volatile private var showing = false
    /** Hubo un fallo ya avisado y el programa se cierra por eso (para el informe del próximo inicio). */
    @Volatile var handledCrash = false

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("Fallo no controlado en el hilo ${t.name}", e)
            // Fallos de hilos de fondo: solo se registran (el programa sigue).
            // Si es el hilo de la ventana, se avisa.
            if (SwingUtilities.isEventDispatchThread()) showDialog(e, fatal = false)
        }
    }

    /** Aviso con opción de abrir la carpeta del registro para enviarlo. */
    fun showDialog(e: Throwable, fatal: Boolean) {
        if (showing) return
        showing = true
        val show = {
            try {
                val msg = buildString {
                    append(if (fatal) "JOX3 TV tuvo un problema y debe cerrarse." else "Ocurrió un problema inesperado.")
                    append("\n\nDetalle: ").append(Log.mask(e.message ?: e.javaClass.simpleName).take(300))
                    append("\n\nEl registro de errores ayuda a corregirlo.")
                }
                val opts = arrayOf("Abrir registro", "Aceptar")
                val r = JOptionPane.showOptionDialog(
                    null, msg, "JOX3 TV", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, opts, opts[1],
                )
                if (r == 0) openLogs()
            } catch (_: Throwable) { } finally { showing = false }
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeAndWait(show)
    }

    fun openLogs() {
        try { Desktop.getDesktop().open(Log.folder) } catch (e: Exception) { Log.w("No se pudo abrir la carpeta del registro", e) }
    }
}
