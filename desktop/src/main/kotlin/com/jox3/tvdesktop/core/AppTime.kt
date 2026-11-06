package com.jox3.tvdesktop.core

import com.jox3.tvdesktop.data.CloudSync
import okhttp3.Request
import org.json.JSONObject
import java.util.TimeZone

/**
 * Hora del programa (igual que Android, tv/data/AppTime.kt).
 *
 * - "Del equipo" (por defecto en el PC): la zona horaria de Windows, que casi siempre está bien.
 * - "Automática": se pregunta a nuestro servidor en qué zona está la conexión (Colombia →
 *   America/Bogota) y se usa esa sin tocar Windows. Sirve si el PC tiene mal la zona.
 */
object AppTime {
    /** Zona que tenía el equipo al abrir el programa. */
    val deviceZone: TimeZone = TimeZone.getDefault()

    fun isAuto() = Settings.string("time.mode") == "auto"
    fun detected(): String = Settings.string("time.detected")
    fun current(): TimeZone = TimeZone.getDefault()

    fun setAuto(auto: Boolean) {
        Settings.put("time.mode", if (auto) "auto" else "device")
        apply()
        if (auto) refresh()
    }

    /** Al abrir: aplica la zona guardada al instante y la vuelve a consultar en segundo plano. */
    fun init() {
        apply()
        if (isAuto()) refresh()
    }

    private fun apply() {
        val id = detected()
        val tz = if (isAuto() && id.isNotBlank()) TimeZone.getTimeZone(id).takeIf { it.id == id } else null
        TimeZone.setDefault(tz ?: deviceZone)
    }

    private fun refresh() {
        Thread {
            runCatching {
                val req = Request.Builder().url("${CloudSync.SERVER}/v1/tz").build()
                CloudSync.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val id = JSONObject(r.body?.string().orEmpty()).optString("tz")
                    if (id.isNotBlank() && TimeZone.getTimeZone(id).id == id) {
                        Settings.put("time.detected", id)
                        apply()
                        Log.i("Hora automática: $id")
                    }
                }
            }.onFailure { Log.w("Hora automática: no se pudo consultar (${it.message})") }
        }.apply { isDaemon = true; name = "hora" }.start()
    }
}
