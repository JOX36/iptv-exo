package com.jox3.tvdesktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Downloads
import java.io.File

/** Ajustes → Descargas: ver, pausar, seguir o borrar; pausar todo y elegir la carpeta. */
@Composable
fun DownloadsScreen(live: LiveController, onClose: () -> Unit) {
    val v = Downloads.version.intValue
    val list = remember(v) { Downloads.all().sortedWith(compareBy({ it.status != Downloads.Status.DOWNLOADING }, { -it.ts })) }
    val folder = remember(v) { Downloads.folder() }
    val free = remember(v) { Downloads.freeBytes() }
    var confirm by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { onClose(); true } else false }
        onDispose { KeyRouter.handler = prev }
    }

    Column(Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⬇ Descargas", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, modifier = Modifier.weight(1f))
            if (list.any { it.status == Downloads.Status.QUEUED || it.status == Downloads.Status.DOWNLOADING })
                GhostButton("⏸ Pausar todo") { Downloads.pauseAll() }
            Box(Modifier.width(8.dp))
            PillButton("Listo") { onClose() }
        }
        Text("Películas y series para ver sin internet. Para descargar: en la ficha, botón «⬇ Descargar» (en series, la temporada, " +
            "la serie completa o el ⬇ de un episodio). Se bajan de a una y siguen donde iban si se corta internet. " +
            "El programa tiene que estar abierto mientras descarga (puede estar minimizado).", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Guardar en: ${folder.absolutePath} · ${gbText(free)} libres", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            GhostButton("Cambiar carpeta") { pickFolder(folder, "¿Dónde guardar las descargas?")?.let { Downloads.setFolder(it) } }
            GhostButton("Abrir carpeta") { runCatching { folder.mkdirs(); java.awt.Desktop.getDesktop().open(folder) } }
        }
        if (list.isEmpty()) Text("Todavía no hay descargas.", color = Jx.Faint, fontFamily = Exo2, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
        val state = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 1000.dp), state = state, verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp, end = 14.dp)) {
                items(list, key = { it.id }) { d ->
                    val (badge, color) = when (d.status) {
                        Downloads.Status.DOWNLOADING -> "⬇ ${d.percent} % · ${mbText(d.bytes)}${if (d.total > 0) " de " + mbText(d.total) else ""}" to Jx.Accent
                        Downloads.Status.QUEUED -> "⏳ En cola" to Jx.Gold
                        Downloads.Status.PAUSED -> "⏸ Pausada · ${d.percent} %" to Jx.Muted
                        Downloads.Status.DONE -> "✓ Lista · ${mbText(d.total.takeIf { it > 0 } ?: d.bytes)}" to Jx.Ok
                        Downloads.Status.FAILED -> "✗ Falló" to Jx.Live
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteImage(d.image, d.label, Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)), scale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) {
                            Text(d.label, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (d.kind == "episode") "Episodio" else "Película", color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp)
                            Text(badge, color = color, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
                            if (d.status == Downloads.Status.DOWNLOADING || d.status == Downloads.Status.PAUSED)
                                ProgressLine(d.fraction, Modifier.width(260.dp).padding(top = 4.dp), height = 3.dp)
                            if (d.error.isNotBlank()) Text(d.error, color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp, maxLines = 2)
                        }
                        when (d.status) {
                            Downloads.Status.DONE -> {
                                PillButton("▶ Ver") { onClose(); live.playDownload(d) }
                                GhostButton("Mostrar archivo") { runCatching { Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", d.location)) } }
                            }
                            Downloads.Status.DOWNLOADING, Downloads.Status.QUEUED -> GhostButton("⏸ Pausar") { Downloads.pause(d.id) }
                            Downloads.Status.PAUSED, Downloads.Status.FAILED -> GhostButton("▶ Seguir") { Downloads.resume(d.id) }
                        }
                        if (confirm == d.id) DangerButton("¿Borrar?") { Downloads.delete(d.id); confirm = null }
                        else GhostButton("🗑") { confirm = d.id }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
