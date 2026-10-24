package com.jox3.tvdesktop.data

/** Cuenta Xtream Codes guardada (igual que en la app Android). */
data class Account(
    val host: String,
    val user: String,
    val pass: String,
) {
    val key: String get() = "$host|$user"

    /** Nombre corto para mostrar: el dominio del servidor. */
    val label: String
        get() = host.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')

    /** Nunca muestra la contraseña (por si la cuenta termina en un registro de errores). */
    override fun toString() = "Account($host, $user, ***)"
}

/** Estado de la cuenta que devuelve el servidor al iniciar sesión. */
data class ServerInfo(
    val status: String,
    val expires: Long?,       // ms, null = sin fecha
    val activeCons: String,
    val maxCons: String,
)

enum class Section(val apiCategories: String) {
    LIVE("get_live_categories"),
    VOD("get_vod_categories"),
    SERIES("get_series_categories"),
}

/** [name] es el nombre limpio para mostrar; [raw] el original del servidor (sirve para detectar el país de la guía). */
data class Category(val id: String, val name: String, val raw: String = name)

data class Channel(
    val id: String,
    val name: String,
    val logo: String,
    val categoryId: String,
    val num: Int,
    /** Código de guía (tvg-id / epg_channel_id) para emparejar con la guía de internet. */
    val epgId: String = "",
)

/** Programa de la guía (horas en milisegundos). */
data class EpgItem(val title: String, val description: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

/** Elemento guardado (favorito, canal reciente). Mismo formato que Android. */
data class SavedItem(
    val kind: String,          // "live", "movie", "series"
    val id: String,
    val name: String,
    val image: String,
    val ext: String = "",
    val seriesId: String = "",
    val categoryId: String = "",
    val ts: Long = 0,
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String,
    val rating: String,
    val ext: String,
    val categoryId: String,
    val added: Long,
)

data class Series(
    val id: String,
    val name: String,
    val poster: String,
    val rating: String,
    val categoryId: String,
    val updated: Long,
)

data class MovieInfo(
    val plot: String,
    val genre: String,
    val director: String,
    val cast: String,
    val duration: String,
    val year: String,
    val rating: String,
    val backdrop: String,
    val poster: String,
    val ext: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val plot: String,
    val image: String,
    val duration: String,
    val ext: String,
)

data class SeriesInfo(
    val name: String,
    val plot: String,
    val genre: String,
    val year: String,
    val rating: String,
    val backdrop: String,
    val poster: String,
    val seasons: Map<Int, List<Episode>>,
)
