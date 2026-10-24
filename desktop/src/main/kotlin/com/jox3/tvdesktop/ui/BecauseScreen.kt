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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.tvdesktop.data.SavedItem
import com.jox3.tvdesktop.data.TvRepo
import kotlinx.coroutines.delay

/**
 * "Porque viste X, te preparé estos títulos": sale al terminar una película o la serie
 * (igual que en el TV Box y el celular). Clic abre el título; Esc o "Volver" cierra.
 * Si nadie mueve el ratón ni toca nada en 30 s, se cierra sola.
 */
@Composable
fun BecauseScreen(repo: TvRepo, source: SavedItem, modifier: Modifier, onOpen: (SavedItem) -> Unit, onClose: () -> Unit) {
    val recs by produceState<List<SavedItem>?>(null, source.kind, source.id) {
        value = runCatching { repo.becauseYouWatched(source) }.getOrDefault(emptyList())
    }
    // Se guarda para la fila "Porque viste…" de Películas y Series
    LaunchedEffect(recs) { recs?.let { repo.library.setBecause(source.name, it) } }

    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var left by remember { mutableIntStateOf(30) }
    LaunchedEffect(lastTouch, recs) {
        val r = recs ?: return@LaunchedEffect
        val total = if (r.isEmpty()) 4 else 30
        for (s in total downTo 1) { left = s; delay(1000) }
        onClose()
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid.isScrollInProgress) { lastTouch = System.currentTimeMillis() }

    Box(
        modifier.background(Jx.Bg)
            // Bloquea los clics hacia la sección de abajo y reinicia la cuenta al mover el ratón
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Move || e.type == PointerEventType.Press || e.type == PointerEventType.Scroll) {
                            lastTouch = System.currentTimeMillis()
                        }
                    }
                }
            }
    ) {
        // Fondo: la carátula de lo que se terminó, muy tenue
        RemoteImage(source.image, source.name, Modifier.fillMaxSize().alpha(0.16f), scale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99070A0F), Jx.Bg))))

        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 36.dp, top = 30.dp)) {
            val r = recs
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("TERMINASTE", color = Jx.Accent, fontFamily = Exo2, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Porque viste ${source.name}", color = Jx.Text, fontFamily = Exo2, fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            r == null -> "Preparando recomendaciones…"
                            r.isEmpty() -> "Esta vez no encontré títulos parecidos en tu servidor."
                            else -> "Te preparé estos títulos que puedes ver aquí"
                        },
                        color = Jx.Muted, fontFamily = Exo2, fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    PillButton("Volver") { onClose() }
                    if (r != null) Text("Se cierra en $left s", color = Jx.Faint, fontFamily = Exo2, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            if (!r.isNullOrEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(r, key = { it.kind + it.id }) { rec ->
                            Column {
                                PosterCard(rec.name, rec.image, "", repo.library.isFavorite(rec.kind, rec.id), null) { onOpen(rec) }
                                Text(if (rec.kind == "series") "Serie" else "Película", color = Jx.Faint, fontFamily = Exo2, fontSize = 11.sp)
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}
