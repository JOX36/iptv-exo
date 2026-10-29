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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.iptvexo.tv.data.Category
import com.jox3.iptvexo.tv.data.LibraryStore
import com.jox3.iptvexo.tv.data.Section
import com.jox3.iptvexo.tv.data.TvRepo
import com.jox3.iptvexo.tv.ui.FocusSurface
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.TvTextField
import kotlinx.coroutines.delay

/**
 * Organizar categorías (por perfil): ocultar las que no se usan, ponerlas en el orden que se
 * quiera y cambiarles el nombre. Para En vivo, Películas y Series. TV (control) y celular (táctil).
 */
@Composable
fun CategoryOrganizer(repo: TvRepo, tv: Boolean, onClose: () -> Unit) {
    val lib = repo.library
    var section by remember { mutableStateOf(Section.LIVE) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    val catV = lib.catVersion.intValue
    val all by produceState<List<Category>?>(null, section) { value = runCatching { repo.categories(section) }.getOrDefault(emptyList()) }
    val prefs = remember(section, catV) { lib.catPrefs(section) }
    BackHandler { if (renaming != null) renaming = null else onClose() }

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

    val firstReq = remember { FocusRequester() }
    LaunchedEffect(section) { if (tv) { delay(150); runCatching { firstReq.requestFocus() } } }

    Box(Modifier.fillMaxSize().background(Jx.Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 40.dp else 14.dp, vertical = if (tv) 24.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Organizar categorías", if (tv) 26 else 22, Jx.Text, bold = true)
            T("Oculta las que no usas, ordénalas y cámbiales el nombre. Solo para tu perfil.", 13, Jx.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Section.LIVE to "En vivo", Section.VOD to "Películas", Section.SERIES to "Series").forEachIndexed { i, (s, label) ->
                    Chip(tv, label, selected = section == s, modifier = if (i == 0) Modifier.focusRequester(firstReq) else Modifier) { section = s }
                }
                Spacer(Modifier.width(12.dp))
                Chip(tv, "Restablecer") { lib.resetCatPrefs(section) }
                Chip(tv, "Listo", primary = true, onClick = onClose)
            }
            val visible = ordered.count { it.id !in prefs.hidden }
            T(if (all == null) "Cargando…" else "${ordered.size} categorías · $visible visibles · ${ordered.size - visible} ocultas", 12, Jx.Faint)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(ordered, key = { _, c -> c.id }) { i, c ->
                    val hidden = c.id in prefs.hidden
                    val name = prefs.names[c.id]?.takeIf { it.isNotBlank() } ?: c.name
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        T("${i + 1}", 12, Jx.Faint, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            T(name, if (tv) 15 else 14, if (hidden) Jx.Faint else Jx.Text, bold = !hidden, maxLines = 1)
                            if (prefs.names[c.id] != null) T("Original: ${c.raw}", 11, Jx.Faint, maxLines = 1)
                            else if (hidden) T("Oculta", 11, Jx.Faint)
                        }
                        Icon(tv, if (hidden) "🚫" else "👁", if (hidden) "Mostrar" else "Ocultar") {
                            save(hidden = if (hidden) prefs.hidden - c.id else prefs.hidden + c.id)
                        }
                        Icon(tv, "⤒", "Al principio") { move(i, 0) }
                        Icon(tv, "▲", "Subir") { move(i, i - 1) }
                        Icon(tv, "▼", "Bajar") { move(i, i + 1) }
                        Icon(tv, "✏️", "Renombrar") { renaming = c }
                    }
                }
            }
        }
        renaming?.let { c ->
            RenameDialog(tv, prefs.names[c.id] ?: c.name, c.raw,
                onSave = { n -> save(names = if (n.isBlank()) prefs.names - c.id else prefs.names + (c.id to n.trim())); renaming = null },
                onReset = { save(names = prefs.names - c.id); renaming = null },
                onCancel = { renaming = null })
        }
    }
}

@Composable
private fun RenameDialog(tv: Boolean, current: String, original: String, onSave: (String) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) { delay(150); runCatching { req.requestFocus() } } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Jx.Rail).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Renombrar categoría", 20, Jx.Text, bold = true)
            T("Original: $original", 12, Jx.Faint)
            if (tv) TvTextField("Nombre", text, { text = it.take(40) }, Modifier.fillMaxWidth().focusRequester(req), onDone = { onSave(text) })
            else BasicTextField(text, { text = it.take(40) }, singleLine = true,
                textStyle = TextStyle(color = Jx.Text, fontSize = 16.sp), cursorBrush = SolidColor(Jx.Accent),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Jx.Surface)
                    .border(1.dp, Jx.Line, RoundedCornerShape(12.dp)).padding(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tv, "Guardar", primary = true) { onSave(text) }
                Chip(tv, "Nombre original") { onReset() }
                Chip(tv, "Cancelar", onClick = onCancel)
            }
        }
    }
}

// ── Piezas (TV con foco / celular táctil) ──

@Composable
private fun Chip(tv: Boolean, label: String, selected: Boolean = false, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Jx.Text; primary -> Jx.Accent; selected -> Jx.SurfaceHi; else -> Jx.Surface }
    val fg = when { focused || primary -> Jx.Bg; selected -> Jx.Accent; else -> Jx.TextSoft }
    val inner: @Composable () -> Unit = {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (selected && !focused) Modifier.border(1.dp, Jx.Accent, RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp)) {
            BasicText(label, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        }
    }
    if (tv) FocusSurface(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(12.dp),
        focusedScale = 1.04f, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false) { inner() }
    else Box(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) { inner() }
}

@Composable
private fun Icon(tv: Boolean, glyph: String, description: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val inner: @Composable () -> Unit = {
        Box(Modifier.size(if (tv) 36.dp else 34.dp).clip(RoundedCornerShape(10.dp)).background(if (focused) Jx.Text else Jx.SurfaceHi),
            contentAlignment = Alignment.Center) {
            BasicText(glyph, style = TextStyle(color = if (focused) Jx.Bg else Jx.TextSoft, fontSize = 15.sp))
        }
    }
    if (tv) FocusSurface(onClick = onClick, modifier = Modifier.onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
        focusedScale = 1.1f, container = Color.Transparent, focusedContainer = Color.Transparent, ring = false) { inner() }
    else Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = description, onClick = onClick)) { inner() }
}

@Composable
private fun T(text: String, size: Int, color: Color, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier = modifier, maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
