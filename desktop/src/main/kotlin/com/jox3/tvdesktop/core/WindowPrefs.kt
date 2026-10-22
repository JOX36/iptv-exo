package com.jox3.tvdesktop.core

import org.json.JSONObject
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File

/**
 * Recuerda tamaño, posición y si la ventana estaba maximizada (window.json).
 * Si la posición guardada ya no cae en ninguna pantalla (se desconectó un
 * monitor), la ventana se centra para que nunca quede "perdida" fuera de vista.
 */
data class WindowPrefs(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int = 1280,
    val height: Int = 780,
    val maximized: Boolean = false,
) {
    /** La posición solo vale si al menos un trozo de la barra de título se ve en alguna pantalla. */
    fun visiblePosition(): Pair<Int, Int>? {
        val px = x ?: return null; val py = y ?: return null
        val title = Rectangle(px + 40, py, (width - 80).coerceAtLeast(40), 30)
        val screens = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        } catch (_: Exception) { return null }
        return if (screens.any { it.intersects(title) }) px to py else null
    }

    companion object {
        private val file get() = File(AppDirs.data, "window.json")

        fun load(): WindowPrefs = SafeFile.read(file) { t ->
            val o = JSONObject(t)
            WindowPrefs(
                x = if (o.has("x")) o.getInt("x") else null,
                y = if (o.has("y")) o.getInt("y") else null,
                width = o.optInt("width", 1280).coerceIn(960, 10000),
                height = o.optInt("height", 780).coerceIn(600, 10000),
                maximized = o.optBoolean("maximized", false),
            )
        } ?: WindowPrefs()

        fun save(p: WindowPrefs) {
            val o = JSONObject().put("width", p.width).put("height", p.height).put("maximized", p.maximized)
            p.x?.let { o.put("x", it) }; p.y?.let { o.put("y", it) }
            try { SafeFile.write(file, o.toString()) } catch (e: Exception) { Log.w("No se guardó la ventana", e) }
        }
    }
}
