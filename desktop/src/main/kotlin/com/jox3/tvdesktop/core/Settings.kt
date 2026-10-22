package com.jox3.tvdesktop.core

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Ajustes pequeños del programa (volumen, formato de imagen, última categoría…)
 * en settings.json. Se leen al instante desde memoria y se guardan en segundo plano,
 * de forma segura ([SafeFile]), sin frenar la interfaz.
 */
object Settings {
    private val file get() = File(AppDirs.data, "settings.json")
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-save").apply { isDaemon = true } }
    private val data: JSONObject by lazy { SafeFile.read(file) { JSONObject(it) } ?: JSONObject() }

    @Synchronized fun string(key: String, def: String = ""): String = data.optString(key, def)
    @Synchronized fun int(key: String, def: Int): Int = data.optInt(key, def)
    @Synchronized fun bool(key: String, def: Boolean): Boolean = data.optBoolean(key, def)

    fun put(key: String, value: Any?) {
        val text = synchronized(this) {
            if (value == null) data.remove(key) else data.put(key, value)
            data.toString(2)
        }
        saver.execute {
            try { SafeFile.write(file, text) } catch (e: Exception) { Log.w("No se pudieron guardar los ajustes", e) }
        }
    }
}
