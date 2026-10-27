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
    /** Días que el servidor guarda de este canal para ver programas pasados (0 = no guarda). */
    val archiveDays: Int = 0,
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
data class EpgItem(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    /** Imagen del programa (si la guía la trae). */
    val icon: String = "",
    /** Género o tipo ("Fútbol", "Película", "Noticias"…). */
    val category: String = "",
    /** EN VIVO según la guía. */
    val liveFlag: Boolean = false,
    /** ESTRENO / nuevo según la guía. */
    val newFlag: Boolean = false,
    /** Temporada y episodio ("T2 E5"). */
    val episode: String = "",
) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f

    /** EN VIVO: lo dice la guía o el título ("(EN VIVO)", "LIVE", "DIRECTO"). */
    val isLive: Boolean get() = liveFlag || LIVE_RE.containsMatchIn(title)
    /** ESTRENO: lo dice la guía o el título. */
    val isNew: Boolean get() = newFlag || NEW_RE.containsMatchIn(title)

    /** Título con sus etiquetas, para mostrar: "🔴 EN VIVO · Nacional vs. Junior". */
    val label: String get() = buildString {
        if (isLive) append("🔴 EN VIVO · ") else if (isNew) append("🆕 ESTRENO · ")
        append(cleanTitle)
    }

    /** Título sin "(EN VIVO)" ni "ESTRENO" repetidos. */
    val cleanTitle: String get() = title.replace(TAGS_RE, " ").replace(Regex("\\s{2,}"), " ").trim().trim('-', '·', ':', ' ').ifBlank { title }

    /** "T2 E5 · ⚽ Fútbol" */
    val meta: String get() = listOfNotNull(
        episode.ifBlank { null },
        category.ifBlank { null }?.let { c -> (categoryEmoji(c)?.let { "$it " } ?: "") + c },
    ).joinToString(" · ")

    companion object {
        private val LIVE_RE = Regex("(?i)(\\ben vivo\\b|\\bvivo\\b\\)|\\(vivo|\\blive\\b|\\bdirecto\\b)")
        private val NEW_RE = Regex("(?i)\\b(estreno|premiere|nuevo episodio)\\b")
        // Solo la etiqueta entre paréntesis o al principio/final ("(EN VIVO)", "ESTRENO: …"), no palabras del título
        private val TAGS_RE = Regex("(?i)[\\[(]\\s*(en vivo|vivo|live|directo|estreno|premiere)\\s*[\\])]|^\\s*(en vivo|estreno|live)\\s*[:|-]\\s*|\\s*[:|-]\\s*(en vivo|estreno|live)\\s*$")
        fun categoryEmoji(c: String): String? {
            val n = java.text.Normalizer.normalize(c.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return when {
                Regex("futbol|soccer|football").containsMatchIn(n) -> "⚽"
                Regex("deport|sport").containsMatchIn(n) -> "🏆"
                Regex("pelicul|movie|film|cine").containsMatchIn(n) -> "🎬"
                Regex("serie|series|drama|novela").containsMatchIn(n) -> "📺"
                Regex("notici|news|informativ").containsMatchIn(n) -> "📰"
                Regex("infantil|kids|anima|children").containsMatchIn(n) -> "🧸"
                Regex("music|musica").containsMatchIn(n) -> "🎵"
                Regex("documental|docu|nature").containsMatchIn(n) -> "🌍"
                Regex("entreten|reality|show|variedad").containsMatchIn(n) -> "🎭"
                else -> null
            }
        }
    }
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
    /** Zona horaria del servidor ("America/Bogota"): las repeticiones se piden en su hora. */
    val timezone: String = "",
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
