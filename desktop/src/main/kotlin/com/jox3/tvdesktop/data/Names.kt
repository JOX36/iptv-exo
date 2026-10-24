package com.jox3.tvdesktop.data

/**
 * Nombres limpios para mostrar: quita los prefijos de código que ponen los servidores
 * ("TV | Noticias" → "Noticias", "N | Telemundo Noticias" → "Telemundo Noticias",
 * "Rel | EWTN" → "EWTN", "CO: Deportes" → "Deportes"). Los emojis y banderas se quedan.
 *
 * Si el prefijo era de calidad o idioma (4K, FHD, LAT, SUB…) no se pierde: pasa al final
 * entre paréntesis ("4K | Dune" → "Dune (4K)"), porque esa información sí sirve.
 *
 * Copia exacta de la app Android (tv/data/Names.kt): los nombres salen iguales en PC, TV y celular.
 */
object Names {
    /** Separadores "tipo barra" entre el código y el nombre. */
    private val BAR = Regex("^\\s*([^|│┃¦]{1,8}?)\\s*[|│┃¦]\\s*(.+)$")
    /** "CO: Deportes", "LAT - Acción", "ES » Cine": código corto en MAYÚSCULAS + separador. */
    private val CODE = Regex("^\\s*([A-Z0-9+]{1,4})\\s*(?::|»|›|\\s-\\s)\\s*(.+)$")
    /** Prefijos genéricos que no son parte del nombre (tipo, país, sección). */
    private val GENERIC = setOf(
        "TV", "REL", "NOT", "NEWS", "DEP", "INF", "DOC", "MUS", "VIP", "PPV", "VOD", "SER", "PEL",
        "USA", "LAT", "ARG", "COL", "MEX", "ESP", "PER", "CHI", "VEN", "ECU", "URU", "PAR", "BOL", "BRA", "CAN", "EUR",
    )
    private val KEEP = setOf(
        "4K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "8K",
        "LAT", "LATINO", "CAST", "ESP", "SUB", "SUBT", "VOSE", "DUAL", "ENG", "EN",
    )

    /**
     * @param titles true para películas y series: solo se quitan prefijos con barra
     *   (un título como "DC: La Liga…" no se toca).
     */
    fun clean(raw: String, titles: Boolean = false): String {
        var s = raw.trim()
        val kept = ArrayList<String>(2)
        repeat(3) {
            val m = BAR.find(s) ?: (if (titles) null else CODE.find(s)) ?: return@repeat
            val code = m.groupValues[1].trim()
            val rest = m.groupValues[2].trim()
            // Solo códigos cortos de una palabra ("TV", "N", "Rel", "CO", "24/7" no: tiene números y barra)
            if (code.isEmpty() || code.contains(' ') || rest.length < 2) return@repeat
            // Solo prefijos de 1–2 letras (N, TV, CO, MX…) o códigos genéricos conocidos;
            // "ESPN | 1 ARG" se queda tal cual (ESPN es parte del nombre).
            val up = code.uppercase()
            if (code.length > 2 && up !in GENERIC && up !in KEEP) return@repeat
            if (code.uppercase() in KEEP) kept += code.uppercase()
            s = rest
        }
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        if (s.isEmpty()) return raw.trim()
        return if (kept.isEmpty() || kept.all { s.contains(it, ignoreCase = true) }) s
        else "$s (${kept.distinct().joinToString(" · ")})"
    }

    private val ES = java.util.Locale.forLanguageTag("es")

    /** Nombre de canal para mostrar: limpio y en MAYÚSCULAS (se lee mejor a distancia). */
    fun channel(raw: String): String = clean(raw).uppercase(ES)

    /**
     * Nombre de categoría para mostrar: limpio, en MAYÚSCULAS y con un emoji según el tema
     * o el país ("TV | Deportes" → "⚽ DEPORTES", "CO | Canales" → "🇨🇴 CANALES").
     * Si el servidor ya trae un emoji, se respeta y no se agrega otro.
     */
    fun category(raw: String): String {
        val name = clean(raw).uppercase(ES)
        if (startsWithEmoji(name)) return name
        val e = emojiFor(plain(name)) ?: prefixFlag(raw) ?: return name
        return "$e $name"
    }

    private fun startsWithEmoji(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        return cp >= 0x2190 && !Character.isLetterOrDigit(cp)
    }

    /** Sin tildes, para comparar palabras clave. */
    private fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    private fun flag(iso: String) = iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** Si el prefijo que se quitó era un país ("CO |", "MX:"), se usa su bandera. */
    private fun prefixFlag(raw: String): String? {
        val m = Regex("^\\s*([A-Za-z]{2,3})\\s*(?:[|│┃¦:»›]|\\s-\\s)").find(raw) ?: return null
        return CODES[m.groupValues[1].uppercase()]?.let(::flag)
    }

    private val CODES = mapOf(
        "CO" to "CO", "COL" to "CO", "MX" to "MX", "MEX" to "MX", "AR" to "AR", "ARG" to "AR", "ES" to "ES", "ESP" to "ES",
        "US" to "US", "USA" to "US", "PE" to "PE", "PER" to "PE", "CL" to "CL", "CHI" to "CL", "VE" to "VE", "VEN" to "VE",
        "EC" to "EC", "ECU" to "EC", "UY" to "UY", "URU" to "UY", "PY" to "PY", "PAR" to "PY", "BO" to "BO", "BOL" to "BO",
        "BR" to "BR", "BRA" to "BR", "CA" to "CA", "CAN" to "CA", "PA" to "PA", "CR" to "CR", "GT" to "GT", "HN" to "HN",
        "SV" to "SV", "NI" to "NI", "CU" to "CU", "DO" to "DO", "PR" to "PR", "IT" to "IT", "FR" to "FR", "DE" to "DE",
        "PT" to "PT", "UK" to "GB", "GB" to "GB",
    )

    /** País por nombre completo (palabra entera, sin tildes). */
    private val COUNTRIES = listOf(
        "COLOMBIA" to "CO", "MEXICO" to "MX", "ARGENTINA" to "AR", "ESPANA" to "ES", "ESTADOS UNIDOS" to "US", "USA" to "US",
        "PERU" to "PE", "CHILE" to "CL", "VENEZUELA" to "VE", "ECUADOR" to "EC", "URUGUAY" to "UY", "PARAGUAY" to "PY",
        "BOLIVIA" to "BO", "BRASIL" to "BR", "BRAZIL" to "BR", "CANADA" to "CA", "PANAMA" to "PA", "COSTA RICA" to "CR",
        "GUATEMALA" to "GT", "HONDURAS" to "HN", "EL SALVADOR" to "SV", "NICARAGUA" to "NI", "CUBA" to "CU",
        "REPUBLICA DOMINICANA" to "DO", "DOMINICANA" to "DO", "PUERTO RICO" to "PR", "ITALIA" to "IT", "FRANCIA" to "FR",
        "ALEMANIA" to "DE", "PORTUGAL" to "PT", "REINO UNIDO" to "GB",
    )

    /** Tema → emoji. Va en orden: lo más específico primero (BOXEO antes que DEPORTES). */
    private val TOPICS: List<Pair<List<String>, String>> = listOf(
        listOf("ADULT", "XXX", "+18", "18+") to "🔞",
        listOf("BOX", "BOXEO", "UFC", "WWE", "LUCHA", "PELEA", "MMA") to "🥊",
        listOf("TENIS", "TENNIS") to "🎾",
        listOf("NBA", "BALONCESTO", "BASKET") to "🏀",
        listOf("NFL", "FUTBOL AMERICANO") to "🏈",
        listOf("MLB", "BEISBOL", "BASEBALL") to "⚾",
        listOf("F1", "FORMULA 1", "MOTOR", "AUTOMOVIL", "NASCAR", "MOTO GP", "MOTOGP") to "🏎️",
        listOf("GOLF") to "⛳",
        listOf("CICLISMO") to "🚴",
        listOf("DEPORT", "SPORT", "FUTBOL", "SOCCER", "LIGA", "ESPN", "WIN", "COPA", "CHAMPIONS", "GOL") to "⚽",
        listOf("EVENTO", "PPV") to "🎟️",
        listOf("MUSIC", "RADIO", "CONCIERTO") to "🎵",
        listOf("NOTICI", "NEWS", "INFORMATIV") to "📰",
        listOf("INFANTIL", "KIDS", "NINOS", "ANIMADO", "CARTOON", "DIBUJOS") to "🧸",
        listOf("ANIME") to "🍥",
        listOf("DOCUMENTAL", "DOCU", "NATURALEZA", "HISTORIA", "DISCOVERY") to "🌍",
        listOf("RELIGI", "CRISTIAN", "CATOLIC", "EVANGEL", "IGLESIA", "FE") to "🙏",
        listOf("NOVELA") to "💕",
        listOf("COCINA", "GASTRONOM") to "🍳",
        listOf("CULTURA", "EDUCA") to "📚",
        listOf("NAVIDAD") to "🎄",
        listOf("TERROR", "HORROR", "MIEDO") to "👻",
        listOf("ACCION", "ACTION") to "💥",
        listOf("COMEDIA", "COMEDY", "HUMOR") to "😂",
        listOf("ROMANCE", "ROMANTIC") to "❤️",
        listOf("CIENCIA FICCION", "SCI-FI", "SCIFI", "FANTASIA") to "🚀",
        listOf("SUSPENSO", "THRILLER", "CRIMEN", "POLICIA") to "🔪",
        listOf("AVENTURA") to "🗺️",
        listOf("FAMILIA", "FAMILY") to "👨‍👩‍👧",
        listOf("GUERRA", "BELIC") to "🎖️",
        listOf("WESTERN", "OESTE") to "🤠",
        listOf("DRAMA") to "🎭",
        listOf("ESTRENO", "NUEV", "RECIENTE") to "🆕",
        listOf("24/7") to "🔁",
        listOf("4K", "UHD") to "🖥️",
        listOf("ENTRETEN", "VARIEDAD", "REALITY") to "🎭",
        listOf("CINE", "PELICULA", "MOVIE") to "🎬",
        listOf("SERIE") to "📺",
    )

    /** Clave al inicio de una palabra ("DEPORT" → "DEPORTES"); claves de 1–3 letras: palabra entera. */
    private fun word(key: String): Regex {
        val k = Regex.escape(key)
        return Regex(if (key.length <= 3) "(?<![A-Z0-9])$k(?![A-Z0-9])" else "(?<![A-Z0-9])$k")
    }
    private val TOPIC_RE by lazy { TOPICS.map { (keys, e) -> keys.map(::word) to e } }
    private val COUNTRY_RE by lazy { COUNTRIES.map { (n, iso) -> word(n) to iso } }

    private fun emojiFor(s: String): String? {
        TOPIC_RE.firstOrNull { (res, _) -> res.any { it.containsMatchIn(s) } }?.let { return it.second }
        COUNTRY_RE.firstOrNull { (re, _) -> re.containsMatchIn(s) }?.let { return flag(it.second) }
        return null
    }
}
