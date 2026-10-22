package com.jox3.tvdesktop.data

import java.util.Locale

/**
 * Qué guías de internet descargar: los países que aparecen en las categorías
 * de En vivo ("CO | NACIONALES", "MÉXICO", ...) más el país del equipo.
 * Copiado tal cual de la app Android (misma lógica que JOX3 Verifier).
 */
object EpgCountries {
    /** Archivo de epgshare01, país, código, ¿país grande? (solo se toman los 3 grandes con más canales). */
    private data class C(val file: String, val name: String, val code: String, val big: Boolean = false)

    private val LIST = listOf(
        C("CO1", "Colombia", "CO"), C("EC1", "Ecuador", "EC"), C("MX1", "México", "MX"), C("AR1", "Argentina", "AR"),
        C("CL1", "Chile", "CL"), C("PE1", "Perú", "PE"), C("CR1", "Costa Rica", "CR"), C("PA1", "Panamá", "PA"),
        C("DO1", "República Dominicana", "DO"), C("SV1", "El Salvador", "SV"), C("UY1", "Uruguay", "UY", true),
        C("BR1", "Brasil", "BR"), C("JM1", "Jamaica", "JM"), C("ES1", "España", "ES", true), C("US2", "Estados Unidos", "US", true),
        C("CA2", "Canadá", "CA", true), C("UK1", "Reino Unido", "UK", true), C("IE1", "Irlanda", "IE", true), C("PT1", "Portugal", "PT"),
        C("FR1", "Francia", "FR", true), C("IT1", "Italia", "IT"), C("DE1", "Alemania", "DE", true), C("NL1", "Países Bajos", "NL"),
        C("BE2", "Bélgica", "BE", true), C("CH1", "Suiza", "CH", true), C("AT1", "Austria", "AT", true), C("TR1", "Turquía", "TR"),
        C("IN1", "India", "IN", true), C("AU1", "Australia", "AU", true), C("PH2", "Filipinas", "PH"), C("JP1", "Japón", "JP"),
        C("KR1", "Corea", "KR"),
    )
    private val FILES: Map<String, String> = LIST.associate { it.code to it.file } + mapOf(
        "USA" to "US2", "GB" to "UK1", "COL" to "CO1", "ARG" to "AR1", "MEX" to "MX1", "CHI" to "CL1",
        "ESP" to "ES1", "BRA" to "BR1", "POR" to "PT1",
    )
    private val BIG = LIST.filter { it.big }.map { it.file }.toSet()
    /** Guías de canales deportivos internacionales (siempre se incluyen). */
    private val SPORTS = listOf("US_SPORTS1", "BEIN1", "DIRECTVSPORTS1")

    private val CAT_CODES = ("CO MX AR ES US USA LAT LATAM CL PE VE EC BR UK GB FR IT DE PT DO PR CR PA GT HN SV NI BO PY UY CA CU " +
        "EU IN TR RU NL BE CH SE NO DK PL JM IE AT AU PH JP KR COL ARG MEX CHI ESP BRA").split(' ').toSet()

    private val WORDS = listOf(
        Regex("mexic") to "MX", Regex("argentin") to "AR", Regex("chile") to "CL", Regex("peru") to "PE",
        Regex("ecuador") to "EC", Regex("costa rica") to "CR", Regex("panama") to "PA", Regex("dominican") to "DO",
        Regex("uruguay") to "UY", Regex("salvador") to "SV", Regex("brasil|brazil") to "BR", Regex("espan|spain") to "ES",
        Regex("estados unidos|eeuu|\\busa\\b") to "US", Regex("canada") to "CA", Regex("reino unido|\\buk\\b|united kingdom") to "UK",
        Regex("portugal") to "PT", Regex("francia|france") to "FR", Regex("italia|italy") to "IT",
        Regex("alemania|germany|deutsch") to "DE", Regex("turqu|turk") to "TR", Regex("jamaica") to "JM",
        Regex("colombia") to "CO",
    )

    private val PREFIX = Regex("^([A-Za-z]{2,5})\\s*[-:.|/]?\\s+(.+)$")

    /** País de una categoría ("EC | DEPORTES" → "EC", "MÉXICO" → "MX"), o "" si no se sabe. */
    fun ofCategory(name: String): String {
        val clean = name.replace(Regex("[^\\p{L}\\p{N}\\s&+'./!?,:|-]"), " ").replace(Regex("\\s+"), " ").trim()
        PREFIX.find(clean)?.let { m ->
            val code = m.groupValues[1].uppercase(Locale.ROOT)
            if (code in CAT_CODES && Regex("\\p{L}{3}").containsMatchIn(m.groupValues[2])) return normalize(code)
        }
        val n = TvRepo.normalize(clean)
        for ((rx, code) in WORDS) if (rx.containsMatchIn(n)) return code
        return ""
    }

    private fun normalize(code: String) = when (code) { "USA" -> "US"; "COL" -> "CO"; else -> code }

    /** Archivos de guía a descargar para estas categorías de En vivo. */
    fun filesFor(categories: List<Category>): List<String> {
        val count = HashMap<String, Double>()
        fun add(cc: String, w: Double = 1.0) { FILES[cc]?.let { f -> count[f] = (count[f] ?: 0.0) + w } }
        categories.forEach { c -> ofCategory(c.name).takeIf { it.isNotEmpty() }?.let { add(it) } }
        // También el país del equipo (es-CO → Colombia)
        Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let { add(it.uppercase(Locale.ROOT), 2.0) }
        val detected = count.entries.sortedByDescending { it.value }.map { it.key }
        val small = detected.filter { it !in BIG }
        val big = detected.filter { it in BIG }.take(3)
        return (listOf("CO1") + small + big + SPORTS).distinct()
    }
}
