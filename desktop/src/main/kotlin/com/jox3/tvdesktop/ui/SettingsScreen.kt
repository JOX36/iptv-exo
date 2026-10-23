package com.jox3.tvdesktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.core.AppDirs
import com.jox3.tvdesktop.core.Crash
import com.jox3.tvdesktop.core.CrashReport
import com.jox3.tvdesktop.core.Settings
import com.jox3.tvdesktop.data.ServerHub
import com.jox3.tvdesktop.data.Tmdb
import com.jox3.tvdesktop.player.Aspect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/** Ajustes del programa: reproductor, En vivo, servidores, almacenamiento y ayuda. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(live: LiveController, version: String) {
    val player = live.player
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                Text("Ajustes", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
                Spacer(Modifier.height(18.dp))

                // ── Reproductor ──
                Card("Reproductor") {
                    Label("Formato de imagen", "Cómo se ajusta el video a la pantalla. Atajo: Z.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Aspect.entries.forEach { a -> Chip(a.label, selected = player.aspect == a) { player.setAspectMode(a) } }
                    }
                    Divider()
                    Toggle("player.pip", true, "Mini reproductor al salir",
                        "Al salir de una película o serie, o al cambiar de sección con un canal sonando, el video sigue en una ventanita en la esquina.")
                    Toggle("player.keepAwake", true, "Mantener la pantalla encendida",
                        "Mientras se reproduce un video, Windows no apaga la pantalla ni pone el protector.")
                    Toggle("trailer.inApp", true, "Ver tráileres dentro del programa",
                        "Si YouTube no lo deja, se ofrece abrirlo en el navegador.")
                    Toggle("player.hw", true, "Aceleración por hardware",
                        "Usa la tarjeta de video para decodificar (menos uso del procesador). Apágala si ves la imagen verde, cortada o si el programa se cierra. Se aplica al volver a abrir.")
                }

                // ── En vivo ──
                Card("En vivo") {
                    var buffer by remember { mutableIntStateOf(Settings.int("live.buffer", 2)) }
                    Label("Búfer de red", "Más segundos = menos cortes en internet lento, pero tarda más en cambiar de canal.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 4, 8).forEach { n -> Chip("$n s", selected = buffer == n) { buffer = n; Settings.put("live.buffer", n) } }
                    }
                    Divider()
                    var format by remember { mutableStateOf(Settings.string("live.format", "auto")) }
                    Label("Formato de los canales", "Automático usa lo que permite tu servidor. Si un canal no abre, prueba otro.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Automático", "ts" to "TS (recomendado)", "m3u8" to "M3U8 (HLS)").forEach { (k, l) ->
                            Chip(l, selected = format == k) { format = k; Settings.put("live.format", k) }
                        }
                    }
                    Divider()
                    Toggle("epg.internet", true, "Guía de internet",
                        "Si tu servidor no trae guía de programación, se descarga de internet (como en la app).")
                }

                // ── Servidores ──
                Card("Servidores") {
                    var all by remember { mutableStateOf(ServerHub.searchAllEnabled()) }
                    ToggleRow("Buscar en todos los servidores", "El buscador incluye tus otros servidores guardados.", all) {
                        all = it; ServerHub.setSearchAll(it)
                    }
                    Text("Los servidores y su estado están en Cuenta (arriba a la derecha).", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                }

                // ── Almacenamiento ──
                Card("Almacenamiento") {
                    var tick by remember { mutableIntStateOf(0) }
                    CacheRow("Imágenes guardadas", File(AppDirs.data, "cache/img"), tick) { File(AppDirs.data, "cache/img").deleteRecursively(); tick++ }
                    CacheRow("Información de TMDB", File(AppDirs.data, "cache/tmdb.json"), tick) { Tmdb.clearCache(); tick++ }
                    CacheRow("Guía de internet descargada", File(AppDirs.data, "epg"), tick) { File(AppDirs.data, "epg").deleteRecursively(); tick++ }
                    Text("Borrar no quita tus cuentas, favoritos ni lo que estás viendo.", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                }

                // ── Atajos ──
                Card("Atajos de teclado") {
                    listOf(
                        "Espacio" to "Pausa / seguir",
                        "F o doble clic" to "Pantalla completa (Esc para salir)",
                        "T" to "Mini reproductor siempre encima",
                        "↑ ↓" to "En vivo: cambiar de canal · Películas: volumen · Listas: desplazarse",
                        "← →" to "Películas: atrás / adelante 10 s · Listas: categoría anterior / siguiente",
                        "Números" to "Ir a un canal por su número",
                        "+ −  /  rueda del mouse" to "Volumen · M: silencio",
                        "A · S · Z" to "Audio · Subtítulos · Formato de imagen",
                        "I · C · V · N" to "Información · Lista de canales · Velocidad · Siguiente episodio",
                        "Ctrl + F" to "Buscador",
                    ).forEach { (k, d) ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(k, color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(190.dp))
                            Text(d, color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                        }
                    }
                }

                // ── Actualizaciones ──
                Card("Actualizaciones") {
                    val st = com.jox3.tvdesktop.core.Updater.state
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    Label("Versión instalada: $version", when (st) {
                        com.jox3.tvdesktop.core.Updater.State.Checking -> "Revisando…"
                        com.jox3.tvdesktop.core.Updater.State.UpToDate -> "Ya tienes la última versión."
                        is com.jox3.tvdesktop.core.Updater.State.Available -> "Hay una versión nueva: ${st.info.version}"
                        is com.jox3.tvdesktop.core.Updater.State.Downloading -> "Descargando ${st.info.version}… ${st.percent} %"
                        is com.jox3.tvdesktop.core.Updater.State.Installing -> "Instalando ${st.info.version}…"
                        is com.jox3.tvdesktop.core.Updater.State.Error -> st.message
                        else -> "El programa revisa solo al abrir y cada 6 horas."
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (st is com.jox3.tvdesktop.core.Updater.State.Available) PillButton("Actualizar a ${st.info.version}") { startUpdate(scope, st.info) }
                        GhostButton("Buscar actualizaciones") {
                            scope.launch { com.jox3.tvdesktop.core.Updater.check(version, manual = true) }
                        }
                    }
                    Toggle("update.auto", true, "Revisar automáticamente", "Al abrir el programa y cada 6 horas. Siempre te pregunta antes de instalar.")
                }

                // ── Ayuda ──
                Card("Ayuda") {
                    var copied by remember { mutableStateOf(false) }
                    Label("Informe de errores", "Si algo falla, copia el informe y envíaselo al desarrollador. No incluye contraseñas.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(if (copied) "¡Copiado!" else "Copiar informe") { copied = CrashReport.copyToClipboard(version) }
                        GhostButton("Abrir carpeta de registros") { Crash.openLogs() }
                    }
                    Divider()
                    Text("JOX3 TV para PC · versión $version", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(16.dp)).background(Jx.Surface)
            .border(1.dp, Jx.Line, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        content()
    }
}

@Composable
private fun Label(title: String, desc: String) {
    Column {
        Text(title, color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(desc, color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
    }
}

@Composable
private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(Jx.Line)) }

/** Interruptor guardado en Ajustes con la clave [key]. */
@Composable
private fun Toggle(key: String, default: Boolean, title: String, desc: String) {
    var on by remember { mutableStateOf(Settings.bool(key, default)) }
    ToggleRow(title, desc, on) { on = it; Settings.put(key, it) }
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!on) }.pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) { Label(title, desc) }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Jx.OnAccent, checkedTrackColor = Jx.Accent, uncheckedTrackColor = Jx.SurfaceHi),
        )
    }
}

/** Una caché con su tamaño y el botón para borrarla. */
@Composable
private fun CacheRow(title: String, file: File, tick: Int, onClear: () -> Unit) {
    val size by produceState(-1L, tick) { value = withContext(Dispatchers.IO) { sizeOf(file) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Jx.Text, fontFamily = Exo2, fontSize = 14.sp)
            Text(if (size < 0) "Calculando…" else formatSize(size), color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
        }
        GhostButton("Borrar") { onClear() }
    }
}

private fun sizeOf(f: File): Long = when {
    !f.exists() -> 0
    f.isFile -> f.length()
    else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun formatSize(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b bytes"
}
