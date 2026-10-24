package com.jox3.iptvexo.phone

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jox3.iptvexo.tv.ui.Display
import com.jox3.iptvexo.tv.ui.Jx
import com.jox3.iptvexo.tv.ui.RemoteImage

/** Tipografía del celular: las mismas fuentes de la TV, a tamaño de mano. */
object PType {
    val Hero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 31.sp)
    val H1 = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp)
    val H2 = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val Eyebrow = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
    val Body = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val Small = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontSize = 12.sp)
    val Tiny = TextStyle(fontFamily = com.jox3.iptvexo.tv.ui.Body, fontWeight = FontWeight.Medium, fontSize = 11.sp)
}

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Jx.Accent, onPrimary = Jx.OnAccent,
            background = Jx.Bg, onBackground = Jx.Text,
            surface = Jx.Surface, onSurface = Jx.Text,
            surfaceVariant = Jx.SurfaceHi, onSurfaceVariant = Jx.Muted,
            surfaceContainer = Jx.Surface, surfaceContainerHigh = Jx.SurfaceHi,
            surfaceContainerLow = Jx.Rail, outline = Jx.Line,
        ),
        content = content,
    )
}

/** Pastilla seleccionable (categorías, temporadas, opciones rápidas). */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null,
         glass: Boolean = false, onClick: () -> Unit) {
    // glass: semitransparente, como las opciones del reproductor de la TV
    val bg = when {
        glass && selected -> Color(0x5900D4FF)
        glass -> Color(0x33FFFFFF)
        selected -> Jx.Text
        else -> Jx.Line
    }
    val fg = when {
        glass -> Jx.Text
        selected -> Jx.Bg
        else -> Jx.TextSoft
    }
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(15.dp), tint = fg)
        Text(text, style = PType.Label, color = fg, maxLines = 1)
    }
}

/** Botón redondo con icono (área táctil de 44dp). */
@Composable
fun RoundIcon(@DrawableRes icon: Int, description: String, modifier: Modifier = Modifier, size: Dp = 44.dp,
              bg: Color = Color.Transparent, tint: Color = Jx.Text, iconSize: Dp = 20.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), description, Modifier.size(iconSize), tint = tint) }
}

/** Botón principal (relleno) o secundario. */
@Composable
fun PButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, primary: Boolean = true,
            progress: Float? = null, onClick: () -> Unit) {
    Column(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (primary) Jx.Accent else Jx.SurfaceHi)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp), tint = if (primary) Jx.OnAccent else Jx.Text)
            Text(text, style = PType.Label, color = if (primary) Jx.OnAccent else Jx.Text, maxLines = 1)
        }
        if (progress != null) {
            Box(Modifier.padding(top = 4.dp).width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(if (primary) Jx.OnAccent else Jx.Accent))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PType.H2, color = Jx.Text, modifier = modifier.padding(horizontal = 18.dp))
}

@Composable
fun CenterText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = PType.Body, color = Jx.Muted, textAlign = TextAlign.Center)
    }
}

/** Póster vertical (películas y series). */
@Composable
fun PosterTile(title: String, image: String, modifier: Modifier = Modifier, subtitle: String = "", favorite: Boolean = false,
               badges: List<String> = emptyList(), onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            RemoteImage(image, title, Modifier.fillMaxSize(), tmdbFallback = true)
            if (favorite) Icon(painterResource(com.jox3.iptvexo.R.drawable.tv_ic_star_filled), "Favorito",
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp), tint = Jx.Gold)
            BadgeRow(badges, Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        Text(title, style = PType.Small, color = Jx.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = PType.Tiny, color = Jx.Faint, maxLines = 1)
    }
}

/** Tarjeta horizontal con el nombre sobre la imagen (Continuar viendo). */
@Composable
fun WideTile(title: String, subtitle: String, image: String, progress: Float?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.width(220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        RemoteImage(image, title, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 9.dp),
        ) {
            Text(title, style = PType.Label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = PType.Tiny, color = Color(0xFFD0DCE6), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Jx.Accent))
        }
    }
}

/** Canal en círculo (Tus canales). */
@Composable
fun ChannelBubble(name: String, logo: String, modifier: Modifier = Modifier, now: String = "", onClick: () -> Unit) {
    Column(modifier.width(76.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Jx.SurfaceHi), contentAlignment = Alignment.Center) {
            RemoteImage(logo, name, Modifier.size(64.dp).padding(10.dp), scale = androidx.compose.ui.layout.ContentScale.Fit)
        }
        Text(name, style = PType.Tiny, color = Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now.isNotEmpty()) Text(now, style = PType.Tiny, color = Jx.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Todas las categorías en lista vertical (con buscador si son muchas). */
@Composable
fun CategorySheet(cats: List<com.jox3.iptvexo.tv.data.Category>, selectedId: String?, onPick: (com.jox3.iptvexo.tv.data.Category) -> Unit, onDismiss: () -> Unit) {
    var filter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val q = com.jox3.iptvexo.tv.data.TvRepo.normalize(filter.trim())
    val shown = if (q.isEmpty()) cats else cats.filter { com.jox3.iptvexo.tv.data.TvRepo.normalize(it.name).contains(q) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Jx.Surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Text("Categorías", style = PType.H2, color = Jx.Text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            if (cats.size > 12) androidx.compose.material3.OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Buscar categoría", style = PType.Body) }, textStyle = PType.Body,
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Jx.Accent, unfocusedBorderColor = Jx.Line,
                    focusedTextColor = Jx.Text, unfocusedTextColor = Jx.Text, cursorColor = Jx.Accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val start = shown.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
            androidx.compose.foundation.lazy.LazyColumn(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(shown.size) { k ->
                    val c = shown[k]
                    val sel = c.id == selectedId
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c); onDismiss() }
                            .background(if (sel) Jx.SurfaceHi else Color.Transparent).padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, style = PType.Body, color = if (sel) Jx.Accent else Jx.Text, modifier = Modifier.weight(1f))
                        if (sel) Icon(painterResource(com.jox3.iptvexo.R.drawable.tv_ic_check), "Elegida", Modifier.size(18.dp), tint = Jx.Ok)
                    }
                }
            }
        }
    }
}


/** Letras de los servidores donde está un título (buscador universal). */
@Composable
fun BadgeRow(letters: List<String>, modifier: Modifier = Modifier) {
    if (letters.isEmpty()) return
    Row(modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        letters.forEach { l ->
            Text(l, style = PType.Tiny, color = Jx.Text, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xE6070A0F))
                .border(1.dp, Jx.Accent, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}
