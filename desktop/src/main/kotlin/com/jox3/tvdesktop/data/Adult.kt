package com.jox3.tvdesktop.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Reconoce canales y categorías para adultos, para que no queden en "Canales recientes"
 * ni se muestren en el inicio.
 */
object Adult {
    private val WORDS = Regex(
        "(?<![a-z0-9])(x{3,}|adult[a-z]*|porn[a-z]*|erotic[a-z]*|sexy?|hentai|playboy|brazzers|hustler|" +
            "penthouse|dorcel|redlight|red light|venus|onlyfans|private ?tv)(?![a-z0-9])"
    )
    private val PLUS18 = Regex("(?<![0-9])(\\+ ?18|18 ?\\+)(?![0-9])")
    /** Nombres que contienen esas palabras pero no son para adultos. */
    private val SAFE = Regex("adult ?swim|adultos mayores|young adult")

    /** Categorías de adultos de En vivo, por servidor (se aprenden al cargar las categorías). */
    private val cats = ConcurrentHashMap<String, Set<String>>()

    fun isAdultName(name: String): Boolean {
        val s = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        if (SAFE.containsMatchIn(s)) return false
        return WORDS.containsMatchIn(s) || PLUS18.containsMatchIn(s)
    }

    fun learn(accountKey: String, live: List<Category>) {
        cats[accountKey] = live.filter { isAdultName(it.raw) || isAdultName(it.name) }.map { it.id }.toSet()
    }

    fun isAdult(accountKey: String, item: SavedItem): Boolean =
        isAdultName(item.name) || (item.categoryId.isNotEmpty() && item.categoryId in cats[accountKey].orEmpty())
}
