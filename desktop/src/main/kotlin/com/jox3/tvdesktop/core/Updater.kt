package com.jox3.tvdesktop.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Actualizaciones automáticas (como la app Android):
 * 1. Revisa latest.json de la versión fija "desktop-latest" en GitHub (al abrir y cada 6 h).
 * 2. Si hay una versión mayor, la descarga y comprueba su huella SHA-256.
 * 3. Cierra el programa, instala el .msi nuevo encima (Windows reemplaza el anterior, sin
 *    pedir permisos de administrador) y lo vuelve a abrir. Cuentas y datos no se tocan.
 *
 * Seguridad: aquí NO se usa el cliente tolerante de los servidores IPTV; la descarga va con
 * la verificación normal de certificados y solo se acepta un .msi de GitHub con la huella correcta.
 */
object Updater {
    const val LATEST_URL = "https://github.com/JOX36/iptv-exo/releases/download/desktop-latest/latest.json"

    data class Info(val version: String, val url: String, val sha256: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val percent: Int) : State
        data class Installing(val info: Info) : State
        data class Error(val message: String, val info: Info? = null) : State
    }

    var state by mutableStateOf<State>(State.Idle); private set
    /** El aviso se cerró con "Más tarde" (hasta la próxima revisión o hasta abrir de nuevo). */
    var dismissed by mutableStateOf(false)

    fun autoEnabled() = Settings.bool("update.auto", true)

    /** Cierra un aviso de error (se vuelve a revisar en la próxima vuelta). */
    fun reset() { if (state !is State.Downloading && state !is State.Installing) state = State.Idle }

    /** Cliente con verificación normal de certificados (no el de los servidores IPTV). */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    /** Compara versiones "1.0.25" de a números. Positivo si [a] es más nueva que [b]. */
    fun compare(a: String, b: String): Int {
        val pa = a.trim().split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Lee latest.json. Solo acepta enlaces de descarga de este repositorio en GitHub. */
    fun parse(text: String): Info {
        val o = JSONObject(text)
        val info = Info(
            version = o.getString("version"),
            url = o.getString("url"),
            sha256 = o.getString("sha256").lowercase(),
            size = o.optLong("size"),
            notes = o.optString("notes"),
        )
        require(info.url.startsWith("https://github.com/JOX36/iptv-exo/releases/download/") && info.url.endsWith(".msi")) {
            "Enlace de actualización no válido"
        }
        require(info.sha256.matches(Regex("[0-9a-f]{64}"))) { "Huella no válida" }
        return info
    }

    /** Revisa si hay versión nueva. [manual] = lo pidió el usuario (muestra "Ya tienes la última"). */
    suspend fun check(current: String, manual: Boolean = false) {
        if (current == "dev") { if (manual) state = State.Error("Al compilar a mano no hay actualizaciones"); return }
        if (state is State.Downloading || state is State.Installing) return
        state = State.Checking
        state = try {
            val text = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(LATEST_URL).header("Accept", "application/json").build()).execute().use { r ->
                    if (r.code == 404) throw IOException("Todavía no hay versiones publicadas")
                    if (!r.isSuccessful) throw IOException("GitHub respondió HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
            }
            val info = withContext(Dispatchers.Default) { parse(text) }
            Settings.put("update.lastCheck", System.currentTimeMillis())
            if (compare(info.version, current) > 0) {
                Log.i("Hay una versión nueva: ${info.version} (tienes $current)")
                if (manual) dismissed = false
                State.Available(info)
            } else State.UpToDate
        } catch (e: Exception) {
            Log.w("No se pudo revisar si hay actualizaciones: ${e.message}")
            if (manual) State.Error(e.message ?: "No se pudo revisar") else State.Idle
        }
    }

    /**
     * Descarga, verifica e instala. [beforeExit] guarda todo y cierra el programa ordenadamente;
     * el instalador corre cuando el programa ya cerró y luego lo vuelve a abrir.
     */
    suspend fun downloadAndInstall(info: Info, beforeExit: () -> Unit) {
        try {
            state = State.Downloading(info, 0)
            val dir = File(System.getProperty("java.io.tmpdir"), "JOX3TV-update").apply { mkdirs() }
            // Se borran descargas viejas
            dir.listFiles()?.forEach { if (it.name != "JOX3-TV-PC-${info.version}.msi") it.delete() }
            val msi = File(dir, "JOX3-TV-PC-${info.version}.msi")
            withContext(Dispatchers.IO) {
                if (!(msi.exists() && sha256(msi) == info.sha256)) {
                    val part = File(dir, msi.name + ".part")
                    http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("La descarga falló (HTTP ${r.code})")
                        val body = r.body ?: throw IOException("Descarga vacía")
                        val total = body.contentLength().takeIf { it > 0 } ?: info.size
                        body.byteStream().use { inp ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastPct = -1
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                                    if (pct != lastPct) { lastPct = pct; state = State.Downloading(info, pct) }
                                }
                            }
                        }
                    }
                    if (sha256(part) != info.sha256) { part.delete(); throw IOException("El archivo descargado está dañado (huella distinta). Intenta de nuevo.") }
                    msi.delete(); part.renameTo(msi)
                }
            }
            state = State.Installing(info)
            Log.i("Instalando la versión ${info.version}")
            launchInstaller(msi)
            beforeExit()
        } catch (e: Exception) {
            Log.w("Falló la actualización", e)
            state = State.Error(e.message ?: "No se pudo actualizar", info)
        }
    }

    /**
     * PowerShell (oculto) espera a que el programa cierre, instala el .msi (barra de progreso de
     * Windows, sin preguntas) y abre el programa otra vez. Se pasa como texto codificado para que
     * las rutas con tildes o eñes (C:\Users\José…) funcionen siempre.
     */
    private fun launchInstaller(msi: File) {
        val exe = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith(".exe", true) && !it.endsWith("java.exe", true) && !it.endsWith("javaw.exe", true) }
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val ps = buildString {
            append("Wait-Process -Id ${ProcessHandle.current().pid()} -Timeout 60 -ErrorAction SilentlyContinue; ")
            append("Start-Process msiexec.exe -ArgumentList @('/i', '\"' + ${q(msi.absolutePath)} + '\"', '/passive', '/norestart') -Wait; ")
            if (exe != null) append("Start-Process -FilePath ${q(exe)}")
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .directory(msi.parentFile).start()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
