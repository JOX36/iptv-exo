package com.jox3.iptvexo.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.jox3.iptvexo.tv.player.TOGETHER_REACTIONS
import com.jox3.iptvexo.tv.player.VodPlayback
import com.jox3.iptvexo.tv.player.WatchTogether
import com.jox3.iptvexo.tv.player.peopleLine
import com.jox3.iptvexo.tv.player.prettyRoomCode
import com.jox3.iptvexo.tv.ui.Jx

/** Hoja "Ver juntos" en el reproductor del celular: crear sala, compartir código, reacciones. */
@Composable
fun PhoneTogetherSheet(vod: VodPlayback, onDismiss: () -> Unit) {
    val wt = WatchTogether
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    PlayerSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("👥 Ver juntos", style = PType.H2, color = Jx.Text)
            when (wt.phase) {
                WatchTogether.Phase.Off, WatchTogether.Phase.Error -> {
                    Text(if (wt.phase == WatchTogether.Phase.Error) wt.message
                        else "Mira esto al mismo tiempo con alguien que no está contigo. Si uno pausa o adelanta, al otro le pasa igual.",
                        style = PType.Small, color = if (wt.phase == WatchTogether.Phase.Error) Jx.Live else Jx.Muted)
                    PButton("Crear sala y compartir código", Modifier.fillMaxWidth()) { wt.create(vod) }
                }
                WatchTogether.Phase.Connecting -> {
                    Text(wt.message, style = PType.Small, color = Jx.Muted)
                    PButton("Cancelar", Modifier.fillMaxWidth(), primary = false) { wt.leave() }
                }
                WatchTogether.Phase.On -> {
                    Text("CÓDIGO DE LA SALA", style = PType.Eyebrow, color = Jx.Muted)
                    Text(prettyRoomCode(wt.code), style = PType.Hero, color = Jx.Accent)
                    Text(peopleLine(), style = PType.Small, color = Jx.TextSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton(if (sharing) "Preparando…" else "Invitar", Modifier.weight(1f)) {
                            if (sharing) return@PButton
                            sharing = true
                            scope.launch { shareInvite(ctx); sharing = false }
                        }
                        PButton("Copiar", Modifier.weight(1f), primary = false) { clip.setText(AnnotatedString(wt.code)) }
                    }
                    Text("Reacciones", style = PType.Small, color = Jx.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TOGETHER_REACTIONS.forEach { e ->
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Jx.Surface).clickable { wt.react(e) },
                                contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                        }
                    }
                    PButton("Salir de Ver juntos", Modifier.fillMaxWidth(), primary = false) { wt.leave(); onDismiss() }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Invitación por WhatsApp (o cualquier app): póster de la película como imagen y, como texto,
 * año, calificación, género, sinopsis, reparto y el enlace que abre la app directo en la sala.
 */
suspend fun shareInvite(ctx: android.content.Context) {
    val wt = WatchTogether
    val text = wt.inviteText()
    val poster = wt.info?.poster?.takeIf { it.startsWith("https://") }
    val file = poster?.let { url ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(ctx.cacheDir, "share").apply { mkdirs() }
                val f = java.io.File(dir, "invitacion.jpg")
                com.jox3.iptvexo.NetClient.api().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    r.body?.byteStream()?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                }
                f.takeIf { it.length() > 1000 }
            }.getOrNull()
        }
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, text)
    if (file != null) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        send.setType("image/jpeg").putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else send.setType("text/plain")
    runCatching {
        ctx.startActivity(android.content.Intent.createChooser(send, "Invitar a ver juntos")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Celular: unirse a una sala con el código. */
@Composable
fun PhoneTogetherJoin(onDismiss: () -> Unit) {
    val wt = WatchTogether
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Jx.Surface,
        title = { Text("👥 Ver juntos", color = Jx.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wt.phase == WatchTogether.Phase.On || wt.phase == WatchTogether.Phase.Connecting) {
                    Text("Sala ${prettyRoomCode(wt.code)}", style = PType.H2, color = Jx.Accent)
                    Text(if (wt.phase == WatchTogether.Phase.Connecting) wt.message else peopleLine(), color = Jx.TextSoft)
                    if (wt.message.isNotEmpty() && wt.phase == WatchTogether.Phase.On) Text(wt.message, style = PType.Small, color = Jx.Muted)
                } else {
                    Text("Escribe el código de 6 números que te mandaron. Se abre la misma película y se reproduce al mismo tiempo.",
                        style = PType.Small, color = Jx.Muted)
                    OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("Código de la sala") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jx.Accent, unfocusedBorderColor = Jx.Line,
                            focusedTextColor = Jx.Text, unfocusedTextColor = Jx.Text, cursorColor = Jx.Accent,
                            focusedLabelColor = Jx.Accent, unfocusedLabelColor = Jx.Muted),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    if (wt.phase == WatchTogether.Phase.Error) Text(wt.message, style = PType.Small, color = Jx.Live)
                    Text("Para crear una sala: abre la película y toca «Ver juntos».", style = PType.Tiny, color = Jx.Faint)
                }
            }
        },
        confirmButton = {
            if (wt.phase == WatchTogether.Phase.On || wt.phase == WatchTogether.Phase.Connecting)
                TextButton(onClick = onDismiss) { Text("Listo", color = Jx.Accent) }
            else TextButton(onClick = { if (typed.length == 6) wt.join(typed) }) { Text("Unirme", color = Jx.Accent) }
        },
        dismissButton = {
            if (wt.phase == WatchTogether.Phase.On || wt.phase == WatchTogether.Phase.Connecting)
                TextButton(onClick = { wt.leave() }) { Text("Salir de la sala", color = Jx.Live) }
            else TextButton(onClick = onDismiss) { Text("Cerrar", color = Jx.Muted) }
        },
    )
}
