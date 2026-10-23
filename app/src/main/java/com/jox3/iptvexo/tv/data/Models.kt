package com.jox3.iptvexo.tv.data

/** Cuenta Xtream Codes guardada. */
data class Account(
    val host: String,
    val user: String,
    val pass: String,
) {
    val key: String get() = "$host|$user"
    /** Nombre corto para mostrar: el dominio del servidor. */
    val label: String
        get() = host.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
}

enum class Section(val apiCategories: String) {
    LIVE("get_live_categories"),
    VOD("get_vod_categories"),
    SERIES("get_series_categories"),
}

/** [name] es el nombre limpio para mostrar; [raw] el original del servidor (sirve para detectar el país). */
data class Category @JvmOverloads constructor(val id: String, val name: String, val raw: String = name)

data class Channel(
    val id: String,
    val name: String,
    val logo: String,
    val categoryId: String,
    val num: Int,
    /** Código de guía (tvg-id / epg_channel_id) para emparejar con la guía de internet. */
    val epgId: String = "",
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

/** Programa de la guía (horas en milisegundos). */
data class EpgItem(val title: String, val description: String, val start: Long, val end: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}

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

data class ServerInfo(
    val status: String,
    val expires: Long?,       // ms, null = sin fecha
    val activeCons: String,
    val maxCons: String,
)

/** Elemento guardado (favorito, reciente, continuar viendo). */
data class SavedItem(
    val kind: String,        // live | movie | series | episode
    val id: String,
    val name: String,
    val image: String,
    val ext: String = "",
    val seriesId: String = "",
    val categoryId: String = "",
    val ts: Long = System.currentTimeMillis(),
)
