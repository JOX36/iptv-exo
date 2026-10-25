package com.jox3.tvdesktop.data

import java.text.Normalizer

/**
 * Junta la guía del servidor con la de internet para UN canal, para que no falte información:
 *
 * 1. Base: la del servidor (usa los nombres del proveedor). Si trae muy poco (menos de 6 h
 *    de las próximas 12) y la de internet bastante más, la base es la de internet.
 * 2. Completar datos: si las dos tienen el mismo programa a la misma hora, al de la base se
 *    le agrega lo que le falte (descripción, imagen, tipo, episodio, EN VIVO / ESTRENO).
 * 3. Rellenar huecos: los programas de la otra guía que caen donde la base no tiene nada
 *    (más tarde, o entre dos programas) se agregan.
 */
object EpgMerge {
    private const val H = 3_600_000L

    fun merge(server: List<EpgItem>, internet: List<EpgItem>, now: Long = System.currentTimeMillis()): List<EpgItem> {
        if (server.isEmpty()) return internet.sortedBy { it.start }
        if (internet.isEmpty()) return server.sortedBy { it.start }
        val covS = coverage(server, now, now + 12 * H)
        val covI = coverage(internet, now, now + 12 * H)
        val serverIsBase = !(covS < 6 * H && covI > covS + 2 * H)
        val base = (if (serverIsBase) server else internet).sortedBy { it.start }
        val other = (if (serverIsBase) internet else server).sortedBy { it.start }

        // 2. Completar datos del mismo programa
        val enriched = base.map { b ->
            val o = other.firstOrNull { sameProgram(b, it) } ?: return@map b
            b.copy(
                description = b.description.ifBlank { o.description },
                icon = b.icon.ifBlank { o.icon },
                category = b.category.ifBlank { o.category },
                episode = b.episode.ifBlank { o.episode },
                liveFlag = b.liveFlag || o.liveFlag,
                newFlag = b.newFlag || o.newFlag,
            )
        }
        // 3. Rellenar huecos: antes del primero, entre dos programas (≥ 30 min) y después del último.
        //    Un programa de la otra guía solo entra si cae casi entero (≥ 85 %) dentro de un hueco:
        //    así no se duplican programas cuando las dos guías tienen la hora un poco corrida.
        val gaps = ArrayList<LongRange>()
        gaps.add(Long.MIN_VALUE until enriched.first().start)
        for (i in 0 until enriched.size - 1) {
            val g0 = enriched[i].end; val g1 = enriched[i + 1].start
            if (g1 - g0 >= 30 * 60_000) gaps.add(g0 until g1)
        }
        gaps.add(enriched.maxOf { it.end } until Long.MAX_VALUE)
        val out = ArrayList(enriched)
        for (o in other) {
            val dur = (o.end - o.start).coerceAtLeast(1)
            val inside = gaps.maxOf { g -> (minOf(o.end, g.last) - maxOf(o.start, g.first)).coerceAtLeast(0) }
            if (inside.toDouble() / dur >= 0.85) out.add(o)
        }
        return out.sortedBy { it.start }
    }

    private fun overlap(a: EpgItem, b: EpgItem): Long = (minOf(a.end, b.end) - maxOf(a.start, b.start)).coerceAtLeast(0)

    /** Cuánto tiempo de [from, to] tiene programación. */
    private fun coverage(list: List<EpgItem>, from: Long, to: Long): Long {
        var total = 0L; var cursor = from
        for (p in list.sortedBy { it.start }) {
            val s = maxOf(p.start, cursor); val e = minOf(p.end, to)
            if (e > s) { total += e - s; cursor = e }
        }
        return total
    }

    /** Mismo programa: casi la misma hora y título parecido (o la hora idéntica). */
    private fun sameProgram(a: EpgItem, b: EpgItem): Boolean {
        val dur = minOf(a.end - a.start, b.end - b.start).coerceAtLeast(1)
        val ov = overlap(a, b)
        if (ov.toDouble() / dur < 0.6) return false
        val exactTime = kotlin.math.abs(a.start - b.start) <= 5 * 60_000 && kotlin.math.abs(a.end - b.end) <= 5 * 60_000
        return exactTime || similarTitle(a.cleanTitle, b.cleanTitle)
    }

    private fun words(t: String): Set<String> =
        Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()

    private fun similarTitle(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa.containsAll(wb) || wb.containsAll(wa)) return true
        return (wa intersect wb).size.toDouble() / (wa union wb).size >= 0.5
    }
}
