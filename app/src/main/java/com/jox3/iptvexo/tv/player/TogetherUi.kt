package com.jox3.iptvexo.tv.player

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
                Text("En el otro equipo: «Ver juntos» y escribe este código.", style = Type.Small, color = Jx.Muted)
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
 * Aviso flotante mientras hay una sala: código, cuántos están y las reacciones.
 * No recibe foco (solo informa). Sirve para TV y celular.
 */
@Composable
fun TogetherBadge(modifier: Modifier = Modifier, big: Boolean = true) {
    val wt = WatchTogether
    if (wt.phase == WatchTogether.Phase.Off) return
    val fs = if (big) 13.sp else 12.sp
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            val people = wt.peers.size.coerceAtLeast(1)
            BasicText(
                "👥 Ver juntos · ${prettyRoomCode(wt.code)}" + if (wt.phase == WatchTogether.Phase.On) " · $people ${if (people == 1) "persona" else "personas"}" else "",
                style = TextStyle(color = Jx.Text, fontSize = fs, fontWeight = FontWeight.SemiBold),
            )
        }
        if (wt.message.isNotEmpty()) Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            BasicText(wt.message, style = TextStyle(color = if (wt.phase == WatchTogether.Phase.Error) Jx.Live else Jx.TextSoft, fontSize = fs))
        }
        wt.reactions.forEach { r ->
            Box(Modifier.background(Color(0x99070A0F), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                BasicText(r.text, style = TextStyle(color = Jx.Text, fontSize = if (big) 20.sp else 18.sp))
            }
        }
    }
}
