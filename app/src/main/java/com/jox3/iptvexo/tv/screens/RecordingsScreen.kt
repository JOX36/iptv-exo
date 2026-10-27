package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.Recorder
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay

/** Lista de grabaciones: ver, detener, borrar, compartir (celular) y dónde se guardan. */
@Composable
fun RecordingsScreen(tv: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val v = Recorder.version.intValue
    // Mientras graba, se refresca el tamaño cada 5 s
    val tick by produceState(0) { while (true) { delay(5000); value++ } }
    val list = remember(v, tick) { Recorder.all(ctx).sortedByDescending { it.start } }
    val places = remember(v) { Recorder.places(ctx) }
    val placeIdx = remember(v) { Recorder.placeIndex(ctx) }
    var confirm by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    val firstReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) { delay(150); runCatching { firstReq.requestFocus() } } }
    val dfmt = remember { java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale("es")) }

    Column(Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = if (tv) 40.dp else 14.dp, vertical = if (tv) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tx("⏺ Grabaciones", if (tv) 26 else 22, Jx.Text, bold = true, modifier = Modifier.weight(1f))
            Btn2(tv, "Listo", primary = true, modifier = Modifier.focusRequester(firstReq), onClick = onClose)
        }
        Tx("Se graban en .ts (la misma calidad del canal): se abren aquí, en VLC, MX Player, Kodi o la PC. " +
            "Para grabar: en un canal, opción «Grabar»; o en la guía mantén OK sobre un programa.", 12, Jx.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tx("Guardar en:", 13, Jx.TextSoft)
            places.forEachIndexed { i, p ->
                Btn2(tv, "${p.label} · ${gb(p.freeBytes)} libres", selected = i == placeIdx) { Recorder.setPlace(ctx, i) }
            }
        }
        if (list.isEmpty()) Tx("Todavía no hay grabaciones.", 14, Jx.Faint, modifier = Modifier.padding(top = 20.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(list, key = { _, r -> r.id }) { _, r ->
                val (badge, color) = when (r.status) {
                    Recorder.Status.RECORDING -> "⏺ Grabando · ${mb(r.bytes)}" to Jx.Live
                    Recorder.Status.SCHEDULED -> "⏰ Programada" to Jx.Gold
                    Recorder.Status.DONE -> "✓ ${r.durationMin} min · ${mb(java.io.File(r.file).length())}" to Jx.Ok
                    Recorder.Status.FAILED -> "✗ Falló" to Jx.Live
                    Recorder.Status.CANCELED -> "Cancelada" to Jx.Faint
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteImage(r.logo, r.chName, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), scale = ContentScale.Fit)
                    Column(Modifier.weight(1f)) {
                        Tx(r.title, if (tv) 15 else 14, Jx.Text, bold = true, maxLines = 1)
                        Tx("${r.chName} · ${dfmt.format(java.util.Date(r.start))}", 12, Jx.Muted, maxLines = 1)
                        Tx(badge, 12, color, maxLines = 1)
                        if (r.error.isNotBlank()) Tx(r.error, 11, Jx.Faint, maxLines = 2)
                    }
                    val canPlay = (r.status == Recorder.Status.DONE || r.status == Recorder.Status.RECORDING) && java.io.File(r.file).length() > 0
                    if (canPlay) Btn2(tv, "▶ Ver") { onClose(); PlayerLauncher.current?.playRecording(r) }
                    if (r.status == Recorder.Status.RECORDING || r.status == Recorder.Status.SCHEDULED)
                        Btn2(tv, if (r.status == Recorder.Status.RECORDING) "■ Detener" else "Cancelar") { Recorder.stop(ctx, r.id) }
                    if (!tv && r.status == Recorder.Status.DONE) Btn2(tv, "Compartir") { share(ctx, r) }
                    Btn2(tv, if (confirm == r.id) "¿Seguro?" else "🗑") {
                        if (confirm == r.id) { Recorder.delete(ctx, r.id); confirm = null } else confirm = r.id
                    }
                }
            }
        }
    }
}

private fun share(ctx: android.content.Context, r: Recorder.Rec) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", java.io.File(r.file))
        ctx.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/mp2t").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir grabación")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(ctx, "Esta grabación está en la USB: cópiala desde ahí", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun mb(b: Long) = if (b >= 1_000_000_000) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else "${b / 1_000_000} MB"
private fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1e9)

@Composable
private fun Btn2(tv: Boolean, label: String, selected: Boolean = false, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Jx.Text; primary -> Jx.Accent; selected -> Jx.SurfaceHi; else -> Jx.SurfaceHi }
    val fg = when { focused || primary -> Jx.Bg; selected -> Jx.Accent; else -> Jx.TextSoft }
    val inner: @Composable () -> Unit = {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (selected && !focused) Modifier.border(1.dp, Jx.Accent, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicText(label, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
    if (tv) FocusSurface(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
        focusedScale = 1.05f, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false) { inner() }
    else Box(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) { inner() }
}

@Composable
private fun Tx(text: String, size: Int, color: Color, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
