package com.jox3.iptvexo

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Registro de errores de la app (cierres y errores que la app esquivó).
 *
 * Guarda los últimos 30 en el equipo, ya LIMPIOS de datos privados: se quitan usuarios,
 * contraseñas y direcciones de servidores antes de guardar. Más adelante se pueden enviar
 * solos para revisarlos y arreglarlos sin que nadie tenga que tomar fotos de la pantalla.
 */
object CrashReporter {
    private const val FILE = "error_log.json"
    private const val MAX = 30

    @JvmStatic
    fun report(ctx: Context, e: Throwable, fatal: Boolean, thread: String = Thread.currentThread().name) {
        runCatching {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            val trace = sanitize(sw.toString()).take(6000)
            val entry = JSONObject()
                .put("time", System.currentTimeMillis())
                .put("fatal", fatal)
                .put("version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .put("tv", ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
                .put("thread", thread)
                .put("type", e.javaClass.name)
                .put("message", sanitize(e.message ?: "").take(300))
                .put("signature", signature(e))
                .put("trace", trace)
            val f = File(ctx.filesDir, FILE)
            val arr = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
            arr.put(entry)
            val keep = JSONArray()
            for (i in maxOf(0, arr.length() - MAX) until arr.length()) keep.put(arr.get(i))
            f.writeText(keep.toString())
        }
    }

    /** Errores guardados (los más nuevos al final). */
    fun entries(ctx: Context): List<JSONObject> = runCatching {
        val arr = JSONArray(File(ctx.filesDir, FILE).readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }

    /** Huella del error: tipo + primeras líneas propias de la app (para agrupar los repetidos). */
    fun signature(e: Throwable): String {
        val frames = e.stackTrace.filter { it.className.startsWith("com.jox3") }.take(3)
            .ifEmpty { e.stackTrace.take(3).toList() }
        return e.javaClass.simpleName + " @ " + frames.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }

    /** Quita usuario, contraseña y servidor de cualquier texto (URLs de Xtream, parámetros…). */
    fun sanitize(s: String): String = s
        .replace(Regex("(?i)(username|password|user|pass)=[^&\\s\"']+"), "$1=***")
        .replace(Regex("(?i)/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/"), "/$1/***/***/")
        .replace(Regex("(?i)\\b(https?)://[^/\\s\"':]+(:\\d+)?"), "$1://<servidor>")
}
