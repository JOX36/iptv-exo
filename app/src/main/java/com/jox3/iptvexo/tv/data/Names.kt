package com.jox3.iptvexo.tv.data

/**
 * Nombres limpios para mostrar: quita los prefijos de código que ponen los servidores
 * ("TV | Noticias" → "Noticias", "N | Telemundo Noticias" → "Telemundo Noticias",
 * "Rel | EWTN" → "EWTN", "CO: Deportes" → "Deportes"). Los emojis y banderas se quedan.
 *
 * Si el prefijo era de calidad o idioma (4K, FHD, LAT, SUB…) no se pierde: pasa al final
 * entre paréntesis ("4K | Dune" → "Dune (4K)"), porque esa información sí sirve.
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
}
