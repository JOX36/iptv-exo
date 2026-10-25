package com.jox3.iptvexo.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.Type

val TOGETHER_REACTIONS = listOf("😂", "❤️", "😱", "👏", "🔥")

fun prettyRoomCode(c: String) = if (c.length == 6) c.take(3) + " " + c.drop(3) else c

/** Panel "Ver juntos" dentro del reproductor de la TV. */
@Composable
internal fun TogetherPanel(vod: VodPlayback, req: FocusRequester) {
    val wt = WatchTogether
    PanelBox("Ver juntos") {
        when (wt.phase) {
            WatchTogether.Phase.Off, WatchTogether.Phase.Error -> {
                Text(if (wt.phase == WatchTogether.Phase.Error) wt.message
                    else "Mira esto al mismo tiempo con alguien que no está contigo. Si uno pausa o adelanta, al otro le pasa igual.",
                    style = Type.Small, color = if (wt.phase == WatchTogether.Phase.Error) Jx.Live else Jx.Muted)
                Spacer(Modifier.height(6.dp))
                ChoiceRow("Crear sala y compartir código", false, Modifier.focusRequester(req)) { wt.create(vod) }
            }
            WatchTogether.Phase.Connecting -> {
                Text(wt.message, style = Type.Small, color = Jx.Muted)
                ChoiceRow("Cancelar", false, Modifier.focusRequester(req)) { wt.leave() }
            }
            WatchTogether.Phase.On -> {
                Text("CÓDIGO DE LA SALA", style = Type.Eyebrow, color = Jx.Muted)
                Text(prettyRoomCode(wt.code), style = Type.Hero, color = Jx.Accent)
                Text("En el otro equipo: «Ver juntos» y escribe este código, o escanea con el celular:", style = Type.Small, color = Jx.Muted)
                val link = wt.inviteLink()
                val qr = androidx.compose.runtime.remember(link) {
                    runCatching { com.jox3.iptvexo.tv.data.RemoteAddServer.qrBitmap(link, 300) }.getOrNull()
                }
                if (qr != null) androidx.compose.foundation.Image(
                    qr.asImageBitmap(), "Código QR para unirse",
                    Modifier.size(130.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp))
                Spacer(Modifier.height(4.dp))
                Text(peopleLine(), style = Type.Small, color = Jx.TextSoft)
                Spacer(Modifier.height(6.dp))
                Text("Reacciones", style = Type.Small, color = Jx.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TOGETHER_REACTIONS.forEachIndexed { i, e ->
                        FocusSurface(onClick = { wt.react(e) }, modifier = Modifier.size(40.dp).then(if (i == 0) Modifier.focusRequester(req) else Modifier),
                            shape = RoundedCornerShape(20.dp), focusedScale = 1.15f, container = Color(0x33FFFFFF), focusedContainer = Jx.Text) {
                            BasicText(e, style = TextStyle(fontSize = 20.sp), modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                ChoiceRow("Salir de Ver juntos", false, Modifier) { wt.leave() }
            }
        }
    }
}

fun peopleLine(): String {
    val p = WatchTogether.peers
    return if (p.size <= 1) "Esperando a que alguien entre…"
    else "${p.size} en la sala: " + p.joinToString(" · ") { "${it.emoji} ${it.name}" }
}

/**
 * Aviso de la sala (arriba al centro): código, cuántos están y quién pausó. Aparece unos
 * segundos cuando pasa algo (alguien entra, pausa…) y junto con los controles del reproductor;
 * luego se esconde. Las reacciones salen siempre y se van solas. Sirve para TV y celular.
 * [onLeave]: en el celular, botón para salir de la sala.
 */
@Composable
fun TogetherBadge(modifier: Modifier = Modifier, big: Boolean = true, onLeave: (() -> Unit)? = null) {
    val wt = WatchTogether
    if (wt.phase == WatchTogether.Phase.Off) return
    var recent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(wt.peers, wt.message, wt.phase) {
        recent = true; kotlinx.coroutines.delay(5000); recent = false
    }
    val show = recent || wt.controlsVisible || wt.phase != WatchTogether.Phase.On
    val fs = if (big) 13.sp else 12.sp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.animation.AnimatedVisibility(show,
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(16.dp)).padding(start = 12.dp, end = if (onLeave != null) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val people = wt.peers.size.coerceAtLeast(1)
                    BasicText(
                        "👥 Ver juntos · ${prettyRoomCode(wt.code)}" + if (wt.phase == WatchTogether.Phase.On) " · $people ${if (people == 1) "persona" else "personas"}" else "",
                        style = TextStyle(color = Jx.Text, fontSize = fs, fontWeight = FontWeight.SemiBold),
                    )
                    if (onLeave != null) Box(Modifier.background(Color(0x33FF5A4E), RoundedCornerShape(12.dp))
                        .then(Modifier.clickableNoTv(onLeave)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        BasicText("Salir", style = TextStyle(color = Jx.Live, fontSize = fs, fontWeight = FontWeight.SemiBold))
                    }
                }
                if (wt.message.isNotEmpty()) Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    BasicText(wt.message, style = TextStyle(color = if (wt.phase == WatchTogether.Phase.Error) Jx.Live else Jx.TextSoft, fontSize = fs))
                }
            }
        }
        wt.reactions.forEach { r ->
            Box(Modifier.background(Color(0x99070A0F), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                BasicText(r.text, style = TextStyle(color = Jx.Text, fontSize = if (big) 20.sp else 18.sp))
            }
        }
    }
}

private fun Modifier.clickableNoTv(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
