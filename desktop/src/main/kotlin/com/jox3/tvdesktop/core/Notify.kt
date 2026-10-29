package com.jox3.tvdesktop.core

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Avisos de Windows (la notificación que sale abajo a la derecha). Sirven para recordatorios y
 * grabaciones cuando el programa está minimizado o detrás de otra ventana.
 */
object Notify {
    @Volatile private var tray: TrayIcon? = null

    /** Al hacer clic en el aviso (por ejemplo, traer la ventana al frente). */
    @Volatile var onClick: () -> Unit = {}

    fun show(title: String, text: String) {
        runCatching {
            if (!SystemTray.isSupported()) return
            val icon = tray ?: synchronized(this) {
                tray ?: run {
                    val img = runCatching {
                        javax.imageio.ImageIO.read(Notify::class.java.classLoader.getResource("icon.png"))
                    }.getOrNull() ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                    TrayIcon(img, "JOX3 TV").apply {
                        isImageAutoSize = true
                        addActionListener { runCatching { onClick() } }
                        SystemTray.getSystemTray().add(this)
                    }.also { tray = it }
                }
            }
            icon.displayMessage(title, text, TrayIcon.MessageType.INFO)
        }.onFailure { Log.w("No se pudo mostrar el aviso de Windows: ${it.message}") }
    }

    /** Quita el icono de la bandeja al cerrar. */
    fun dispose() {
        runCatching { tray?.let { SystemTray.getSystemTray().remove(it) } }
        tray = null
    }
}
