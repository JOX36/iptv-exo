package com.jox3.tvdesktop.core

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Caché pequeña de texto (clave → valor) guardada en un archivo JSON, para cosas que no
 * vale la pena pedir dos veces (por ejemplo, la ficha de TMDB de cada título).
 * Se lee una vez, vive en memoria y se guarda en segundo plano, con un tope de entradas.
 */
class DiskCache(name: String, private val maxEntries: Int = 3000) {
    private val file = File(AppDirs.data, "cache/$name.json")
    private val saver = Executors.newSingleThreadExecutor { r -> Thread(r, "cache-$name").apply { isDaemon = true } }
    private val map: LinkedHashMap<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        SafeFile.read(file) { JSONObject(it) }?.let { o -> o.keys().forEach { k -> m[k] = o.optString(k) } }
        m
    }
    @Volatile private var pendingSave = false

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun put(key: String, value: String) {
        map.remove(key); map[key] = value
        while (map.size > maxEntries) map.remove(map.keys.first())
        scheduleSave()
    }

    @Synchronized fun clear() { map.clear(); scheduleSave() }

    /** Junta varios cambios seguidos en un solo guardado (cada 2 s como mucho). */
    private fun scheduleSave() {
        if (pendingSave) return
        pendingSave = true
        saver.execute {
            Thread.sleep(2000)
            val text = synchronized(this) { pendingSave = false; JSONObject(map as Map<*, *>).toString() }
            try { SafeFile.write(file, text) } catch (e: Exception) { Log.w("No se pudo guardar la caché ${file.name}", e) }
        }
    }
}
