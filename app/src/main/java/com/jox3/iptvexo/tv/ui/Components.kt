package com.jox3.iptvexo.tv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jox3.iptvexo.tv.ui.safeFocusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage

// ──────────────────────────────────────────────────────────────────────────
// Piezas reutilizables de la interfaz TV. Regla común: el elemento enfocado
// SIEMPRE se distingue (anillo cian + un poco más grande) y nada depende del
// toque; todo se activa con OK y, cuando aplica, con OK mantenido.
// ──────────────────────────────────────────────────────────────────────────

@Composable
fun JxIcon(@DrawableRes res: Int, size: Dp = 20.dp, tint: Color = Color.Unspecified, description: String? = null) {
    Icon(
        painter = painterResource(res),
        contentDescription = description,
        modifier = Modifier.size(size),
        tint = if (tint == Color.Unspecified) androidx.tv.material3.LocalContentColor.current else tint,
    )
}

/** Superficie enfocable con el estilo de la app. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.06f,
    container: Color = Color.Transparent,
    focusedContainer: Color = Jx.SurfaceHi,
    content: Color = Jx.TextSoft,
    focusedContent: Color = Jx.Text,
    ring: Boolean = true,
    body: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = focusedContainer,
            pressedContentColor = focusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale * 0.97f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (ring) Border(BorderStroke(2.dp, Jx.Accent), inset = 0.dp, shape = shape) else Border.None,
        ),
        content = body,
    )
}

/**
 * Imagen remota con fondo de color y, mientras carga o si falla, las iniciales
 * del título. Usa AsyncImage (sin subcomposición): ligera en grillas grandes.
 */
@Composable
fun RemoteImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    showInitials: Boolean = true,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.background(Jx.tone(title)), contentAlignment = Alignment.Center) {
        if (showInitials && !loaded) Initials(title)
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = scale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
            )
        }
    }
}

@Composable
private fun Initials(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            initials(title),
            style = Type.Number,
            color = Jx.TextSoft,
        )
    }
}

fun initials(name: String): String =
    name.split(' ', '-', '|', '.').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

/** Póster vertical (películas y series). */
@Composable
fun PosterCard(
    title: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
    subtitle: String = "",
    onLongClick: (() -> Unit)? = null,
    favorite: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused },
            focusedScale = 1.08f,
            container = Jx.Surface,
        ) {
            RemoteImage(image, title, Modifier.fillMaxSize())
            if (favorite) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .background(Color(0xB3070A0F), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { JxIcon(com.jox3.iptvexo.R.drawable.tv_ic_star_filled, 12.dp, Jx.Gold) }
            }
        }
        Text(
            title, style = Type.Small, color = if (focused) Jx.Text else Jx.Muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) Text(subtitle, style = Type.Small, color = Jx.Gold, maxLines = 1)
    }
}

/** Tarjeta horizontal 16:9 con barra de progreso opcional (continuar viendo). */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
            focusedScale = 1.08f,
            container = Jx.Surface,
        ) {
            RemoteImage(image, title, Modifier.fillMaxSize())
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(if (focused) Jx.Accent else Jx.Muted))
                }
            }
        }
        Text(title, style = Type.Label, color = if (focused) Jx.Text else Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, style = Type.Small, color = Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Canal en filas del inicio: logo cuadrado + nombre. */
@Composable
fun ChannelTile(name: String, logo: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).onFocusChanged { focused = it.isFocused },
            focusedScale = 1.08f,
            container = Jx.Surface,
        ) {
            RemoteImage(logo, name, Modifier.fillMaxSize().padding(12.dp), scale = ContentScale.Fit)
        }
        Text(name, style = Type.Small, color = if (focused) Jx.Text else Jx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Botón de acción (relleno = principal, con borde = secundario). */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    progress: Float? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Jx.Accent else Color(0x99101722),
            contentColor = if (primary) Jx.OnAccent else Jx.Text,
            focusedContainerColor = if (primary) Jx.Accent else Jx.Text,
            focusedContentColor = if (primary) Jx.OnAccent else Jx.Bg,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (primary) Border.None else Border(BorderStroke(1.dp, Color(0xFF3A4B5E)), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(BorderStroke(2.dp, Jx.Text), inset = (-3).dp, shape = RoundedCornerShape(12.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) JxIcon(icon, 16.dp)
                Text(text, style = Type.Label)
            }
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x40000000), RoundedCornerShape(1.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(androidx.tv.material3.LocalContentColor.current))
                }
            }
        }
    }
}

/** Fila horizontal con título; recuerda la tarjeta enfocada al volver a ella. */
@Composable
fun ShelfRow(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = Type.H2, color = Jx.Text, modifier = Modifier.padding(start = Safe.H))
        LazyRow(
            modifier = Modifier.safeFocusRestorer(),
            contentPadding = PaddingValues(horizontal = Safe.H, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Etiqueta pequeña con borde (4K, FHD, 5.1...). */
@Composable
fun Tag(text: String, color: Color = Jx.TextSoft) {
    Box(
        Modifier
            .background(Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 0.dp),
    ) {
        Text(
            text, style = Type.Small, color = color,
            modifier = Modifier
                .background(Color(0x22FFFFFF), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Barra de progreso fina. */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = Jx.Accent, height: Dp = 3.dp) {
    Box(modifier.height(height).background(Color(0xFF2A3747), RoundedCornerShape(height))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color, RoundedCornerShape(height)))
    }
}

/** Mensaje centrado de carga / error con botón para reintentar. */
@Composable
fun StatusBox(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = Type.Body, color = Jx.Muted)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            ActionButton("Reintentar", onRetry, icon = com.jox3.iptvexo.R.drawable.tv_ic_restart)
        }
    }
}
