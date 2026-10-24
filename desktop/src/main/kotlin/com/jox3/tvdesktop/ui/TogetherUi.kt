package com.jox3.tvdesktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.player.WatchTogether
import com.jox3.tvdesktop.player.WatchTogether.Phase

/** Panel "Ver juntos" dentro del reproductor (películas y episodios). */
@Composable
fun TogetherPanel() {
    val wt = WatchTogether
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (wt.phase) {
            Phase.Off, Phase.Error -> {
                Text(
                    if (wt.phase == Phase.Error) wt.message
                    else "Mira esto al mismo tiempo con alguien que no está contigo (en otro PC, TV Box o celular). Si uno pausa o adelanta, al otro le pasa igual.",
                    color = if (wt.phase == Phase.Error) Jx.Live else Jx.Muted, fontFamily = Exo2, fontSize = 13.sp,
                )
                PillButton("Crear sala y compartir código") { wt.create() }
            }
            Phase.Connecting -> {
                Text(wt.message, color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                GhostButton("Cancelar") { wt.leave() }
            }
            Phase.On -> {
                Text("CÓDIGO DE LA SALA", color = Jx.Muted, fontFamily = Exo2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(wt.pretty(wt.code), color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                    Spacer(Modifier.width(12.dp))
                    GhostButton("Copiar") {
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(wt.code), null)
                        }
                    }
                }
                Text("En el otro equipo: «Ver juntos» y escribe este código.", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                Text(wt.peopleLine(), color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                Text("Reacciones", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                ReactionRow()
                Spacer(Modifier.height(4.dp))
                DangerButton("Salir de Ver juntos") { wt.leave() }
            }
        }
    }
}

@Composable
private fun ReactionRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchTogether.REACTIONS.forEach { e ->
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(21.dp)).background(Color(0x33FFFFFF))
                    .clickable { WatchTogether.react(e) }.pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) { BasicText(e, style = TextStyle(fontSize = 20.sp)) }
        }
    }
}

/**
 * Aviso flotante mientras hay una sala: código, cuántos están, avisos y reacciones.
 * Igual que en la TV: "👥 Ver juntos · 482 913 · 2 personas".
 */
@Composable
fun TogetherBadge(modifier: Modifier = Modifier) {
    val wt = WatchTogether
    if (wt.phase == Phase.Off) return
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            val people = wt.peers.size.coerceAtLeast(1)
            BasicText(
                "👥 Ver juntos · ${wt.pretty(wt.code)}" +
                    if (wt.phase == Phase.On) " · $people ${if (people == 1) "persona" else "personas"}" else "",
                style = TextStyle(color = Jx.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Exo2),
            )
        }
        if (wt.message.isNotEmpty()) Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            BasicText(wt.message, style = TextStyle(color = if (wt.phase == Phase.Error) Jx.Live else Jx.TextSoft, fontSize = 13.sp, fontFamily = Exo2))
        }
        wt.reactions.forEach { r ->
            Box(Modifier.background(Color(0x99070A0F), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                BasicText(r.text, style = TextStyle(color = Jx.Text, fontSize = 20.sp))
            }
        }
    }
}

/** Unirse a una sala con el código que mandó la otra persona (desde la barra de arriba). */
@Composable
fun TogetherJoin(onDismiss: () -> Unit) {
    val wt = WatchTogether
    var typed by remember { mutableStateOf("") }
    fun go() { if (typed.length == 6) wt.join(typed) }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6070A0F))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).background(Jx.Rail, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("👥 Ver juntos", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            if (wt.phase == Phase.Off || wt.phase == Phase.Error) {
                Text("Escribe el código de 6 números que te mandó la otra persona. Se abre la misma película " +
                    "y se reproduce al mismo tiempo.", color = Jx.Muted, fontFamily = Exo2, fontSize = 14.sp)
                GateField("Código de la sala", typed, { typed = it.filter(Char::isDigit).take(6) }, "Ej.: 482913", onEnter = ::go)
                if (wt.phase == Phase.Error) Text(wt.message, color = Jx.Live, fontFamily = Exo2, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Unirme") { go() }
                    GhostButton("Cerrar") { onDismiss() }
                }
                Text("Para crear una sala: abre la película y en el reproductor elige «Ver juntos».",
                    color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            } else {
                Text("Sala ${wt.pretty(wt.code)}", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(if (wt.phase == Phase.Connecting) wt.message else wt.peopleLine(), color = Jx.TextSoft, fontFamily = Exo2, fontSize = 14.sp)
                if (wt.message.isNotEmpty() && wt.phase == Phase.On) Text(wt.message, color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                if (wt.phase == Phase.On) ReactionRow()
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Listo") { onDismiss() }
                    DangerButton("Salir de la sala") { wt.leave() }
                }
            }
        }
    }
}
