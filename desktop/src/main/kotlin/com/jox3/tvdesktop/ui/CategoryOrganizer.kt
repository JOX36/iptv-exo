package com.jox3.tvdesktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.Category
import com.jox3.tvdesktop.data.LibraryStore
import com.jox3.tvdesktop.data.Section
import com.jox3.tvdesktop.data.TvRepo

/**
 * Organizar categorías (por perfil): ocultar las que no se usan, ponerlas en el orden que se
 * quiera y cambiarles el nombre. Para En vivo, Películas y Series. Igual que en la app Android.
 */
@Composable
fun CategoryOrganizer(repo: TvRepo, onClose: () -> Unit) {
    val lib = repo.library
    var section by remember { mutableStateOf(Section.LIVE) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    val catV = lib.catVersion.intValue
    val all by produceState<List<Category>?>(null, section) { value = runCatching { repo.categories(section) }.getOrDefault(emptyList()) }
    val prefs = remember(section, catV) { lib.catPrefs(section) }

    // Esc: cerrar el diálogo o volver a Ajustes
    DisposableEffect(renaming) {
        val prev = KeyRouter.handler
        KeyRouter.handler = { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) { if (renaming != null) renaming = null else onClose(); true }
            else false
        }
        onDispose { KeyRouter.handler = prev }
    }

    // Orden actual de TODAS (también las ocultas)
    val ordered = remember(all, prefs) {
        val list = all.orEmpty()
        val pos = prefs.order.withIndex().associate { it.value to it.index }
        list.sortedBy { pos[it.id] ?: (100_000 + list.indexOf(it)) }
    }
    fun save(order: List<String> = ordered.map { it.id }, hidden: Set<String> = prefs.hidden, names: Map<String, String> = prefs.names) =
        lib.saveCatPrefs(section, LibraryStore.CatPrefs(hidden, order, names))
    fun move(i: Int, to: Int) {
        if (to !in ordered.indices) return
        val ids = ordered.map { it.id }.toMutableList()
        val id = ids.removeAt(i); ids.add(to, id)
        save(order = ids)
    }

    Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Organizar categorías", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
                    modifier = Modifier.weight(1f))
                PillButton("Listo") { onClose() }
            }
            Text("Oculta las que no usas, ordénalas y cámbiales el nombre. Solo para tu perfil.", color = Jx.Muted, fontFamily = Exo2, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Section.LIVE to "En vivo", Section.VOD to "Películas", Section.SERIES to "Series").forEach { (s, label) ->
                    Chip(label, selected = section == s) { section = s }
                }
                Spacer(Modifier.width(12.dp))
                GhostButton("Restablecer") { lib.resetCatPrefs(section) }
            }
            val visible = ordered.count { it.id !in prefs.hidden }
            Text(if (all == null) "Cargando…" else "${ordered.size} categorías · $visible visibles · ${ordered.size - visible} ocultas",
                color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            val state = rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), state = state, verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, end = 14.dp)) {
                    itemsIndexed(ordered, key = { _, c -> c.id }) { i, c ->
                        val hidden = c.id in prefs.hidden
                        val name = prefs.names[c.id]?.takeIf { it.isNotBlank() } ?: c.name
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${i + 1}", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp, modifier = Modifier.width(32.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = if (hidden) Jx.Faint else Jx.Text, fontFamily = Exo2, fontSize = 14.sp,
                                    fontWeight = if (hidden) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (prefs.names[c.id] != null) Text("Original: ${c.raw}", color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp, maxLines = 1)
                                else if (hidden) Text("Oculta", color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp)
                            }
                            IconBtn(if (hidden) "🚫" else "👁", if (hidden) "Mostrar" else "Ocultar") {
                                save(hidden = if (hidden) prefs.hidden - c.id else prefs.hidden + c.id)
                            }
                            IconBtn("⤒", "Al principio") { move(i, 0) }
                            IconBtn("▲", "Subir") { move(i, i - 1) }
                            IconBtn("▼", "Bajar") { move(i, i + 1) }
                            IconBtn("✏️", "Renombrar") { renaming = c }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        renaming?.let { c ->
            RenameDialog(prefs.names[c.id] ?: c.name, c.raw,
                onSave = { n -> save(names = if (n.isBlank()) prefs.names - c.id else prefs.names + (c.id to n.trim())); renaming = null },
                onReset = { save(names = prefs.names - c.id); renaming = null },
                onCancel = { renaming = null })
        }
    }
}

@Composable
private fun RenameDialog(current: String, original: String, onSave: (String) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(16.dp)).background(Jx.Rail)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Renombrar categoría", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text("Original: $original", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp)
            GateField("Nombre", text, { text = it.take(40) }, "Nombre de la categoría", onEnter = { onSave(text) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Guardar") { onSave(text) }
                GhostButton("Nombre original") { onReset() }
                GhostButton("Cancelar") { onCancel() }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IconBtn(glyph: String, description: String, onClick: () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.background(Jx.SurfaceHi, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(description, color = Jx.Text, fontFamily = Exo2, fontSize = 12.sp)
            }
        },
        delayMillis = 300,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Jx.SurfaceHi)
                .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { BasicText(glyph, style = TextStyle(color = Jx.TextSoft, fontSize = 15.sp)) }
    }
}
