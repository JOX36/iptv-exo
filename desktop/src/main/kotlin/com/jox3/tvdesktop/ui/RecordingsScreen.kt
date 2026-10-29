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
import com.jox3.tvdesktop.data.Recorder
import kotlinx.coroutines.delay
import java.io.File

/** Lista de grabaciones: ver, detener, borrar, abrir la carpeta y elegir dónde se guardan. */
@Composable
fun RecordingsScreen(live: LiveController, onClose: () -> Unit) {
    val v = Recorder.version.intValue
    // Mientras graba, se refresca el tamaño cada 5 s
    val tick by produceState(0) { while (true) { delay(5000); value++ } }
    val list = remember(v, tick) { Recorder.all().sortedByDescending { it.start } }
    val folder = remember(v) { Recorder.folder() }
    val free = remember(v, tick) { Recorder.freeBytes() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val dfmt = remember { java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.forLanguageTag("es")) }

    DisposableEffect(Unit) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { onClose(); true } else false }
        onDispose { KeyRouter.handler = prev }
    }

    Column(Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⏺ Grabaciones", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, modifier = Modifier.weight(1f))
            PillButton("Listo") { onClose() }
        }
        Text("Se graban en .ts (la misma calidad del canal): se abren aquí, en VLC o en cualquier reproductor. " +
            "Para grabar: en un canal, botón ⏺ «Grabar»; o en la guía, clic derecho sobre un programa. " +
            "El programa tiene que estar abierto mientras graba (puede estar minimizado).", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Guardar en: ${folder.absolutePath} · ${gb(free)} libres", color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            GhostButton("Cambiar carpeta") { chooseFolder(folder)?.let { Recorder.setFolder(it) } }
            GhostButton("Abrir carpeta") {
                runCatching { folder.mkdirs(); java.awt.Desktop.getDesktop().open(folder) }
            }
        }
        if (list.isEmpty()) Text("Todavía no hay grabaciones.", color = Jx.Faint, fontFamily = Exo2, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
        val state = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 1000.dp), state = state, verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp, end = 14.dp)) {
                items(list, key = { it.id }) { r ->
                    val size = File(r.file).length()
                    val (badge, color) = when (r.status) {
                        Recorder.Status.RECORDING -> "⏺ Grabando · ${mb(size)}" to Jx.Live
                        Recorder.Status.SCHEDULED -> "⏰ Programada" to Jx.Gold
                        Recorder.Status.DONE -> "✓ ${r.durationMin} min · ${mb(size)}" to Jx.Ok
                        Recorder.Status.FAILED -> "✗ Falló" to Jx.Live
                        Recorder.Status.CANCELED -> "Cancelada" to Jx.Faint
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteImage(r.logo, r.chName, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), scale = ContentScale.Fit)
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.chName} · ${dfmt.format(java.util.Date(r.start))}", color = Jx.Muted, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
                            Text(badge, color = color, fontFamily = Exo2, fontSize = 12.sp, maxLines = 1)
                            if (r.error.isNotBlank()) Text(r.error, color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp, maxLines = 2)
                        }
                        val canPlay = (r.status == Recorder.Status.DONE || r.status == Recorder.Status.RECORDING) && size > 0
                        if (canPlay) PillButton("▶ Ver") { onClose(); live.playRecording(r) }
                        if (r.status == Recorder.Status.DONE && size > 0) GhostButton("Mostrar archivo") {
                            runCatching { Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", r.file)) }
                        }
                        if (r.status == Recorder.Status.RECORDING || r.status == Recorder.Status.SCHEDULED)
                            GhostButton(if (r.status == Recorder.Status.RECORDING) "■ Detener" else "Cancelar") { Recorder.stop(r.id) }
                        if (confirm == r.id) DangerButton("¿Borrar?") { Recorder.delete(r.id); confirm = null }
                        else GhostButton("🗑") { confirm = r.id }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Elegir carpeta con el diálogo de Windows. */
private fun chooseFolder(current: File): File? = runCatching {
    val ch = javax.swing.JFileChooser(current.takeIf { it.exists() } ?: File(System.getProperty("user.home"))).apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "¿Dónde guardar las grabaciones?"
        approveButtonText = "Elegir"
    }
    if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}.getOrNull()

private fun mb(b: Long) = if (b >= 1_000_000_000) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else "${b / 1_000_000} MB"
private fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
