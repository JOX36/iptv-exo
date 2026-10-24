package com.jox3.iptvexo.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.R
import com.jox3.iptvexo.tv.data.Account
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.JxIcon
import com.jox3.iptvexo.tv.ui.Type
import kotlinx.coroutines.delay

/** Color del punto de estado: verde bien, naranja lento o por vencer, rojo no responde. */
fun statusColor(st: ServerHub.Status?): Color = when (st?.level) {
    null -> Jx.Faint
    0 -> Jx.Ok
    1 -> Jx.Gold
    else -> Jx.Live
}

/** Texto corto del estado de un servidor. */
fun statusText(st: ServerHub.Status?): String = when {
    st == null -> "Consultando…"
    !st.ok -> "No responde"
    !st.active -> "Cuenta inactiva"
    else -> buildList {
        st.daysLeft?.let { add(if (it == 0L) "Vence hoy" else "Vence en $it días") } ?: add("Activo")
        add("${st.pingMs} ms")
    }.joinToString(" · ")
}

/**
 * Panel lateral de servidores (se abre desde la barra lateral): cada servidor con su
 * estado; OK cambia a ese servidor sin cerrar sesión. Abajo: agregar otro servidor y
 * activar o no la búsqueda en todos.
 */
@Composable
fun ServerPanel(
    accounts: List<Account>, current: Account,
    onPick: (Account) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var searchAll by remember { mutableStateOf(ServerHub.searchAllEnabled(ctx)) }
    val firstReq = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { ServerHub.refresh(accounts) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Jx.Rail).padding(horizontal = 18.dp, vertical = 26.dp)
                .verticalScroll(rememberScrollState()).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SERVIDORES", style = Type.Eyebrow, color = Jx.Muted)
            Text("OK: cambiar de servidor. Cada uno guarda sus favoritos e historial.", style = Type.Small, color = Jx.Faint)
            Spacer(Modifier.height(4.dp))
            accounts.forEach { acc ->
                val st = ServerHub.status[acc.key]
                val isCurrent = acc.key == current.key
                FocusSurface(
                    onClick = { if (isCurrent) onDismiss() else onPick(acc) },
                    modifier = Modifier.fillMaxWidth().height(62.dp).let { if (isCurrent) it.focusRequester(firstReq) else it },
                    focusedScale = 1.02f,
                    container = if (isCurrent) Jx.SurfaceHi else Jx.Surface,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(30.dp).background(if (isCurrent) Jx.Accent else Jx.Line, CircleShape), contentAlignment = Alignment.Center) {
                            Text(ServerHub.letter(accounts, acc), style = Type.Label, color = if (isCurrent) Jx.OnAccent else Jx.Text)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(ServerHub.name(accounts, acc), style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).background(statusColor(st), CircleShape))
                                Text(if (isCurrent) "En uso · " + statusText(st) else statusText(st), style = Type.Small,
                                    color = if (st?.level == 1) Jx.Gold else Jx.Muted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            FocusSurface(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp), focusedScale = 1.02f) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("+", style = Type.H2, color = Jx.Accent)
                    Text("Agregar servidor", style = Type.Label)
                }
            }
            if (accounts.size > 1) FocusSurface(
                onClick = { searchAll = !searchAll; ServerHub.setSearchAll(ctx, searchAll) },
                modifier = Modifier.fillMaxWidth().height(56.dp), focusedScale = 1.02f, container = Jx.Surface,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Buscar en todos los servidores", style = Type.Label, maxLines = 1)
                        Text("El buscador muestra dónde está cada título", style = Type.Small, color = Jx.Faint, maxLines = 1)
                    }
                    Text(if (searchAll) "SÍ" else "NO", style = Type.Label, color = if (searchAll) Jx.Accent else Jx.Muted)
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { firstReq.requestFocus() } }
}

/**
 * Al elegir un resultado que está en varios servidores: de cuál verlo. Cada opción
 * muestra la calidad o idioma de esa copia y cómo responde el servidor.
 */
@Composable
fun ServerChooser(
    hit: ServerHub.Hit, accounts: List<Account>, current: Account,
    onPick: (ServerHub.Copy) -> Unit, onDismiss: () -> Unit,
) {
    val firstReq = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { ServerHub.refresh(hit.servers) }
    // Una copia por servidor (la primera); el servidor actual va primero
    val options = hit.copies.distinctBy { it.account.key }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).background(Jx.Rail, RoundedCornerShape(16.dp)).padding(22.dp).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(hit.name, style = Type.H2, color = Jx.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("¿De qué servidor lo quieres ver?", style = Type.Small, color = Jx.Muted)
            options.forEachIndexed { i, c ->
                val st = ServerHub.status[c.account.key]
                val isCurrent = c.account.key == current.key
                FocusSurface(
                    onClick = { onPick(c) },
                    modifier = Modifier.fillMaxWidth().height(if (c.kind == "live") 74.dp else 58.dp).let { if (i == 0) it.focusRequester(firstReq) else it },
                    focusedScale = 1.02f, container = Jx.Surface,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).background(Jx.Line, CircleShape), contentAlignment = Alignment.Center) {
                            Text(ServerHub.letter(accounts, c.account), style = Type.Label)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(ServerHub.name(accounts, c.account) + (if (c.tag.isNotBlank()) " · ${c.tag}" else ""),
                                style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (isCurrent) "Servidor actual · " else "Cambia a este servidor · ") + statusText(st),
                                style = Type.Small, color = Jx.Muted, maxLines = 1)
                            if (c.kind == "live") {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                val now by androidx.compose.runtime.produceState<String?>(null, c) { value = ServerHub.nowOn(ctx, c) }
                                Text(when (now) { null -> "Buscando la guía…"; "" -> "Sin guía en este servidor"; else -> "▶ Ahora: $now" },
                                    style = Type.Small, color = if (now.isNullOrEmpty()) Jx.Faint else Jx.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(Modifier.size(8.dp).background(statusColor(st), CircleShape))
                        JxIcon(R.drawable.tv_ic_play, 14.dp)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { firstReq.requestFocus() } }
}
