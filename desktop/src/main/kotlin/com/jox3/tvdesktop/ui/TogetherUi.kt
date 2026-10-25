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
                Text(wt.pretty(wt.code), color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                Text("En el otro equipo: «Ver juntos» y escribe este código, o escanea con el celular:", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                QrCode(wt.inviteLink(), Modifier.size(140.dp))
                // Invitación completa (póster, sinopsis, reparto y enlace) para pegar en WhatsApp
                var copied by remember { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (copied == "inv") "✓ Invitación copiada" else "Copiar invitación") { copyText(wt.inviteText()); copied = "inv" }
                    GhostButton(if (copied == "code") "✓ Copiado" else "Copiar código") { copyText(wt.code); copied = "code" }
                }
                if (copied == "inv") Text("Pégala en WhatsApp: quien la reciba en el celular toca el enlace y entra directo a la sala.",
                    color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
                Text(wt.peopleLine(), color = Jx.TextSoft, fontFamily = Exo2, fontSize = 13.sp)
                Text("Reacciones", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
                ReactionRow()
                Spacer(Modifier.height(4.dp))
                DangerButton("Salir de Ver juntos") { wt.leave() }
            }
        }
    }
}

private fun copyText(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

/** Código QR (negro sobre blanco) dibujado con zxing. */
@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1,
                    com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) drawRect(Color.Black, androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
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
 * Aviso de la sala (arriba al centro): "👥 Ver juntos · 482 913 · 2 personas" y quién pausó.
 * Aparece unos segundos cuando pasa algo (alguien entra, pausa…) y junto con los controles del
 * reproductor; luego se esconde (igual que en la TV). Las reacciones salen siempre y se van solas.
 */
@Composable
fun TogetherBadge(modifier: Modifier = Modifier) {
    val wt = WatchTogether
    if (wt.phase == Phase.Off) return
    var recent by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(wt.peers, wt.message, wt.phase) {
        recent = true; kotlinx.coroutines.delay(5000); recent = false
    }
    val show = recent || wt.controlsVisible || wt.phase != Phase.On
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.animation.AnimatedVisibility(show,
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val people = wt.peers.size.coerceAtLeast(1)
                    BasicText(
                        "👥 Ver juntos · ${wt.pretty(wt.code)}" +
                            if (wt.phase == Phase.On) " · $people ${if (people == 1) "persona" else "personas"}" else "",
                        style = TextStyle(color = Jx.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Exo2),
                    )
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FF5A4E))
                        .clickable { wt.leave() }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        BasicText("Salir", style = TextStyle(color = Jx.Live, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Exo2))
                    }
                }
                if (wt.message.isNotEmpty()) Box(Modifier.background(Color(0xCC070A0F), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    BasicText(wt.message, style = TextStyle(color = if (wt.phase == Phase.Error) Jx.Live else Jx.TextSoft, fontSize = 13.sp, fontFamily = Exo2))
                }
            }
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
