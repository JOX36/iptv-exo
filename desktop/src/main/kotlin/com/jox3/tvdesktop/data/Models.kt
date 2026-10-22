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

data class Category(val id: String, val name: String)

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
