package com.jox3.iptvexo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.jox3.iptvexo.tv.data.LocalServers
import com.jox3.iptvexo.tv.data.ServerHub
import com.jox3.iptvexo.tv.data.Tmdb
import com.jox3.iptvexo.tv.data.TvRepo

/** Ficha de TMDB de una película o serie (null mientras carga, sin clave o si no se encontró). */
@Composable
fun rememberTmdb(kind: String, name: String): Tmdb.Info? {
    val ctx = LocalContext.current
    val v by Tmdb.version
    val info by produceState<Tmdb.Info?>(null, kind, name, v) {
        value = if (kind == "movie") Tmdb.movie(ctx, name) else Tmdb.series(ctx, name)
    }
    return info
}

/**
 * Otras copias del mismo título: en otros servidores, o en otra carpeta del mismo
 * (p. ej. la versión 4K). Solo con más de un servidor guardado o si hay copias.
 */
@Composable
fun rememberOtherCopies(kind: String, name: String, currentId: String, repo: TvRepo): List<ServerHub.Copy> {
    val sc = LocalServers.current ?: return emptyList()
    val v by ServerHub.indexVersion
    val list by produceState(emptyList<ServerHub.Copy>(), kind, name, v) {
        if (sc.all.size < 2) return@produceState
        val idx = runCatching { repo.searchIndex() }.getOrNull()
        value = ServerHub.copiesOf(kind, name, sc.account, idx, sc.all)
            .filter { !(it.account.key == sc.account.key && it.id == currentId) }
            .distinctBy { it.account.key + it.tag }
    }
    return list
}

/** Calificación (amarillo) y clasificación por edad (con borde). */
@Composable
fun RatingBadges(info: Tmdb.Info?, fallbackRating: String = "") {
    val rating = info?.ratingText?.ifBlank { null } ?: fallbackRating.ifBlank { null }
    val cert = info?.certification.orEmpty()
    if (rating == null && cert.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rating != null) Text("★ $rating", style = Type.Label, color = Color(0xFF070A0F),
            modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
        if (cert.isNotBlank()) Text(cert, style = Type.Small, color = Jx.TextSoft,
            modifier = Modifier.border(1.dp, Jx.Muted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** Reparto con fotos (sin foco: solo se muestra). */
@Composable
fun CastRow(cast: List<Tmdb.Person>, max: Int = 6, photo: androidx.compose.ui.unit.Dp = 48.dp) {
    if (cast.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        cast.take(max).forEach { p ->
            Column(Modifier.width(photo + 22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RemoteImage(p.photo, p.name, Modifier.size(photo).clip(CircleShape))
                Text(p.name, style = Type.Small, color = Jx.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (p.role.isNotBlank()) Text(p.role, style = Type.Small, color = Jx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/** Carátulas al día para una lista guardada ("tipo:id" → imagen). Ver [TvRepo.freshImages]. */
@Composable
fun rememberFreshImages(repo: TvRepo, items: List<com.jox3.iptvexo.tv.data.SavedItem>): Map<String, String> {
    val fresh by produceState(emptyMap<String, String>(), items) {
        value = runCatching { repo.freshImages(items) }.getOrDefault(emptyMap())
    }
    return fresh
}

/** La imagen a mostrar: la del catálogo de hoy si cambió, si no la guardada. */
fun Map<String, String>.imageOf(item: com.jox3.iptvexo.tv.data.SavedItem): String = this[item.kind + ":" + item.id] ?: item.image
