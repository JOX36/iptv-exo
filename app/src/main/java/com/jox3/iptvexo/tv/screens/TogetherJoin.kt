package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.player.WatchTogether
import com.jox3.iptvexo.tv.player.peopleLine
import com.jox3.iptvexo.tv.player.prettyRoomCode
import com.jox3.iptvexo.tv.ui.ActionButton
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.TvTextField
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay

/** TV: unirse a una sala de "Ver juntos" con el código que mandó la otra persona. */
@Composable
fun TogetherJoin(onDismiss: () -> Unit) {
    val wt = WatchTogether
    var typed by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xE6070A0F)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).background(Jx.Rail, RoundedCornerShape(18.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("👥 Ver juntos", style = Type.H1, color = Jx.Text)
            if (wt.phase == WatchTogether.Phase.Off || wt.phase == WatchTogether.Phase.Error) {
                Text("Escribe el código de 6 números que te mandó la otra persona. Se abre la misma película " +
                    "y se reproduce al mismo tiempo.", style = Type.Body, color = Jx.Muted)
                TvTextField("Código de la sala", typed, { typed = it.filter(Char::isDigit).take(6) },
                    Modifier.fillMaxWidth().focusRequester(first), placeholder = "Ej.: 482 913",
                    keyboardType = KeyboardType.Number, onDone = { if (typed.length == 6) wt.join(typed) })
                if (wt.phase == WatchTogether.Phase.Error) Text(wt.message, style = Type.Label, color = Jx.Live)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Unirme", { if (typed.length == 6) wt.join(typed) }, primary = true)
                    ActionButton("Cerrar", onDismiss)
                }
                Text("Para crear una sala: abre la película y en el reproductor elige «Ver juntos».",
                    style = Type.Small, color = Jx.Faint)
            } else {
                Text("Sala ${prettyRoomCode(wt.code)}", style = Type.H2, color = Jx.Accent)
                Text(if (wt.phase == WatchTogether.Phase.Connecting) wt.message else peopleLine(), style = Type.Body, color = Jx.TextSoft)
                if (wt.message.isNotEmpty() && wt.phase == WatchTogether.Phase.On) Text(wt.message, style = Type.Small, color = Jx.Muted)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Listo", onDismiss, Modifier.focusRequester(first), primary = true)
                    ActionButton("Salir de la sala", { wt.leave() })
                }
            }
        }
    }
}
