package com.jox3.tvdesktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Log
import com.jox3.tvdesktop.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Imágenes de internet (logos, carátulas) con doble caché:
 * - en memoria (las últimas 400, para que la lista no parpadee al desplazarse),
 * - en disco por 7 días (%APPDATA%\JOX3 TV\cache\img), para abrir rápido aunque el servidor tarde.
 * Si una imagen falla, no se reintenta en 10 minutos (no se satura la red con enlaces rotos).
 */
object Images {
    private const val MAX_MEMORY = 400
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val MAX_DISK_BYTES = 200L * 1024 * 1024

    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_MEMORY
    }
    private val failed = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(6)
    private val dir: File by lazy { File(AppDirs.data, "cache/img").apply { mkdirs() }.also { trimDisk(it) } }

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        cached(url)?.let { return it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 10 * 60_000) return null }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val f = File(dir, hash(url))
                    val bytes = if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) f.readBytes()
                    else download(url)?.also { b -> runCatching { f.writeBytes(b) } }
                    val img = bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
                    if (img == null) failed[url] = System.currentTimeMillis()
                    else synchronized(memory) { memory[url] = img }
                    img
                } catch (e: Throwable) {
                    failed[url] = System.currentTimeMillis()
                    null
                }
            }
        }
    }

    private fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", Net.API_USER_AGENT).build()
        Net.api.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            if ((body.contentLength()) > 8L * 1024 * 1024) return null // nada de imágenes gigantes
            return body.bytes().takeIf { it.size > 32 }
        }
    }

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Si la caché pasa de 200 MB, se borran las imágenes más viejas. */
    private fun trimDisk(d: File) {
        try {
            val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_DISK_BYTES && System.currentTimeMillis() - f.lastModified() < 4 * MAX_AGE_MS) break
                total -= f.length(); f.delete()
            }
        } catch (e: Exception) { Log.w("No se pudo limpiar la caché de imágenes", e) }
    }
}

/** Imagen de internet; mientras carga (o si falla) muestra la inicial del nombre. */
@Composable
fun RemoteImage(
    url: String,
    name: String,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Fit,
) {
    val img by produceState(Images.cached(url), url) { if (value == null) value = Images.load(url) }
    Box(modifier.background(Jx.SurfaceHi), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, contentDescription = name, contentScale = scale, modifier = Modifier.matchParentSize())
        else Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
            color = Jx.Faint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFamily = Exo2,
        )
    }
}
