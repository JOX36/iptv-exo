package com.jox3.iptvexo.tv.data

import android.content.Context
import com.jox3.iptvexo.NetClient
import okhttp3.Request
import org.json.JSONObject
import java.util.TimeZone

/**
 * Hora de la app.
 *
 * Muchos TV Box traen la zona horaria mal puesta (de fábrica en China o en UTC), y entonces
 * todas las horas de la app salen corridas: "termina a las…", la guía, los recordatorios.
 * En modo automático la app pregunta a nuestro servidor de Cloudflare en qué zona horaria
 * está la conexión (Colombia → America/Bogota) y usa esa, sin tocar los ajustes del equipo.
 * También se puede elegir "la del equipo".
 */
object AppTime {
    private const val PREFS = "jox3_time"
    private const val K_MODE = "mode"        // "auto" (por internet) | "device"
    private const val K_DETECTED = "detected" // última zona detectada por internet

    /** Zona que tenía el equipo al abrir la app (antes de cambiarla). */
    val deviceZone: TimeZone = TimeZone.getDefault()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAuto(ctx: Context) = prefs(ctx).getString(K_MODE, "auto") != "device"
    fun detected(ctx: Context): String = prefs(ctx).getString(K_DETECTED, "") ?: ""

    /** Zona que usa la app ahora mismo. */
    fun current(): TimeZone = TimeZone.getDefault()

    fun setAuto(ctx: Context, auto: Boolean) {
        prefs(ctx).edit().putString(K_MODE, if (auto) "auto" else "device").apply()
        apply(ctx)
        if (auto) refresh(ctx)
    }

    /** Al abrir la app: aplica la zona guardada (al instante) y la vuelve a consultar en segundo plano. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        apply(app)
        if (isAuto(app)) refresh(app)
    }

    private fun apply(ctx: Context) {
        val id = detected(ctx)
        val tz = if (isAuto(ctx) && id.isNotBlank()) TimeZone.getTimeZone(id).takeIf { it.id == id } else null
        TimeZone.setDefault(tz ?: deviceZone)
    }

    private fun refresh(ctx: Context) {
        NetClient.IO.execute {
            runCatching {
                val req = Request.Builder().url("${CloudSync.SERVER}/v1/tz").build()
                NetClient.api().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val id = JSONObject(r.body?.string().orEmpty()).optString("tz")
                    if (id.isNotBlank() && TimeZone.getTimeZone(id).id == id) {
                        prefs(ctx).edit().putString(K_DETECTED, id).apply()
                        apply(ctx)
                    }
                }
            }
        }
    }

    /** Texto corto para Ajustes, p. ej. "Automática · America/Bogota". */
    fun label(ctx: Context): String =
        if (isAuto(ctx)) "Automática · " + (detected(ctx).ifBlank { "detectando…" })
        else "La del equipo · " + deviceZone.id
}
