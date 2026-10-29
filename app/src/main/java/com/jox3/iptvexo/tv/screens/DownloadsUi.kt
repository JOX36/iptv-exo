package com.jox3.iptvexo.tv.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jox3.iptvexo.tv.PlayerLauncher
import com.jox3.iptvexo.tv.data.Downloads
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage
import kotlinx.coroutines.delay

/**
 * Antes de descargar: en Android 9 o menor se pide permiso para guardar en la carpeta pública
 * Películas (si no se da, se guarda en la carpeta de la app). Devuelve "hacer esto con permiso".
 */
@Composable
fun rememberDownloadStarter(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke(); pending = null
    }
    return { action ->
        val needs = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needs) { pending = action; runCatching { launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.onFailure { action() } }
        else action()
    }
}

fun dlToast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

/** Texto del botón de descarga según cómo va. */
fun downloadLabel(d: Downloads.Dl?): String = when (d?.status) {
    null -> "Descargar"
    Downloads.Status.QUEUED -> "En cola…"
    Downloads.Status.DOWNLOADING -> "Descargando ${d.percent}%"
    Downloads.Status.PAUSED -> "Pausada · ${d.percent}%"
    Downloads.Status.DONE -> "Ver descargada"
    Downloads.Status.FAILED -> "Reintentar descarga"
}

/** Lo que hace el botón: descargar, pausar, seguir o ver la descargada. */
fun downloadClick(ctx: android.content.Context, d: Downloads.Dl?, start: () -> Unit) {
    when (d?.status) {
        null, Downloads.Status.FAILED -> start()
        Downloads.Status.QUEUED, Downloads.Status.DOWNLOADING -> { Downloads.pause(ctx, d.id); dlToast(ctx, "Descarga pausada") }
        Downloads.Status.PAUSED -> { Downloads.resume(ctx, d.id); dlToast(ctx, "La descarga sigue") }
        Downloads.Status.DONE -> PlayerLauncher.current?.playDownload(d, Downloads.all(ctx))
    }
}

/** Marca corta para un episodio: "⬇ 45%", "✓ Descargado"… (vacío si no se descargó). */
fun downloadBadge(d: Downloads.Dl?): String = when (d?.status) {
    null -> ""
    Downloads.Status.QUEUED -> "⬇ En cola"
    Downloads.Status.DOWNLOADING -> "⬇ ${d.percent}%"
    Downloads.Status.PAUSED -> "⏸ ${d.percent}%"
    Downloads.Status.DONE -> "✓ Descargado"
    Downloads.Status.FAILED -> "✗ Descarga falló"
}

/** Menú de opciones con el control (TV): una lista que se elige con OK. */
@Composable
fun TvChoiceMenu(title: String, subtitle: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val req = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { delay(120); runCatching { req.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(Jx.Rail).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tx(title, 20, Jx.Text, bold = true)
            if (subtitle.isNotBlank()) Tx(subtitle, 12, Jx.Muted)
            options.forEachIndexed { i, (label, action) ->
                Btn(true, label, modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(req) else Modifier)) {
                    onDismiss(); action()
                }
            }
            Btn(true, "Cancelar", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

/** Lista de descargas: ver sin internet, pausar, seguir y borrar. */
@Composable
fun DownloadsScreen(tv: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val v = Downloads.version.intValue
    val list = remember(v) {
        Downloads.all(ctx).sortedWith(compareBy<Downloads.Dl>({ if (it.kind == "episode") it.series.lowercase() else it.title.lowercase() },
            { it.season }, { it.num }))
    }
    var confirm by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    val firstReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) { delay(150); runCatching { firstReq.requestFocus() } } }
    val active = list.any { it.status == Downloads.Status.QUEUED || it.status == Downloads.Status.DOWNLOADING }
    val paused = list.any { it.status == Downloads.Status.PAUSED }

    Column(Modifier.fillMaxSize().background(Jx.Bg).padding(horizontal = if (tv) 40.dp else 14.dp, vertical = if (tv) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tx("⬇ Descargas", if (tv) 26 else 22, Jx.Text, bold = true, modifier = Modifier.weight(1f))
            if (active) Btn(tv, "⏸ Pausar todo") { list.forEach { Downloads.pause(ctx, it.id) } }
            else if (paused) Btn(tv, "▶ Seguir todo") { list.filter { it.status == Downloads.Status.PAUSED }.forEach { Downloads.resume(ctx, it.id) } }
            Btn(tv, "Listo", primary = true, modifier = Modifier.focusRequester(firstReq), onClick = onClose)
        }
        Tx("Para ver sin internet. Se guardan en: ${Downloads.whereLabel(ctx)} · ${gb(Downloads.freeBytes())} libres.", 12, Jx.Muted)
        Tx("Se bajan de a uno. Mientras descarga se usa una conexión de tu cuenta: si tu cuenta permite solo 1, pausa la descarga para ver otra cosa.", 11, Jx.Faint)
        if (list.isEmpty()) Tx("Todavía no hay descargas. En una película o serie elige «Descargar».", 14, Jx.Faint, modifier = Modifier.padding(top = 20.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { d ->
                val (badge, color) = when (d.status) {
                    Downloads.Status.DOWNLOADING -> "⬇ Descargando ${d.percent}% · ${mb(d.bytes)}" + (if (d.total > 0) " de ${mb(d.total)}" else "") to Jx.Accent
                    Downloads.Status.QUEUED -> "En cola" to Jx.Gold
                    Downloads.Status.PAUSED -> "⏸ Pausada · ${d.percent}%" to Jx.Muted
                    Downloads.Status.DONE -> "✓ Lista · ${mb(d.total.takeIf { it > 0 } ?: d.bytes)}" to Jx.Ok
                    Downloads.Status.FAILED -> "✗ Falló" to Jx.Live
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteImage(d.image, d.series.ifBlank { d.title }, Modifier.width(if (tv) 46.dp else 40.dp).height(if (tv) 66.dp else 58.dp)
                        .clip(RoundedCornerShape(8.dp)), tmdbFallback = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Tx(d.label, if (tv) 15 else 14, Jx.Text, bold = true, maxLines = 2)
                        Tx(badge, 12, color, maxLines = 1)
                        if (d.status == Downloads.Status.DOWNLOADING || d.status == Downloads.Status.PAUSED) {
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Jx.Line)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(d.fraction).background(if (d.status == Downloads.Status.PAUSED) Jx.Muted else Jx.Accent))
                            }
                        }
                        if (d.error.isNotBlank() && d.status != Downloads.Status.DONE) Tx(d.error, 11, Jx.Faint, maxLines = 2)
                    }
                    when (d.status) {
                        Downloads.Status.DONE -> Btn(tv, "▶ Ver") { onClose(); PlayerLauncher.current?.playDownload(d, list) }
                        Downloads.Status.QUEUED, Downloads.Status.DOWNLOADING -> Btn(tv, "⏸") { Downloads.pause(ctx, d.id) }
                        Downloads.Status.PAUSED, Downloads.Status.FAILED -> Btn(tv, if (d.status == Downloads.Status.FAILED) "↻ Reintentar" else "▶ Seguir") { Downloads.resume(ctx, d.id) }
                    }
                    Btn(tv, if (confirm == d.id) "¿Borrar?" else "🗑", modifier = Modifier.onFocusChanged { if (!it.hasFocus && confirm == d.id) confirm = null }) {
                        if (confirm == d.id) { Downloads.delete(ctx, d.id); confirm = null } else confirm = d.id
                    }
                }
            }
        }
    }
}

private fun mb(b: Long) = if (b >= 1_000_000_000) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else "${b / 1_000_000} MB"
private fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f GB", b / 1e9)

@Composable
private fun Btn(tv: Boolean, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Jx.Text; primary -> Jx.Accent; else -> Jx.SurfaceHi }
    val fg = if (focused || primary) Jx.Bg else Jx.TextSoft
    val inner: @Composable () -> Unit = {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (focused) Modifier.border(1.dp, Jx.Text, RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp)) {
            BasicText(label, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
    if (tv) FocusSurface(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
        focusedScale = 1.04f, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false) { inner() }
    else Box(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) { inner() }
}

@Composable
private fun Tx(text: String, size: Int, color: Color, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

