package com.jox3.iptvexo.tv.player.skip

/**
 * Comparar huellas: encontrar el tramo que dos capítulos tienen en común (la intro o los
 * créditos) y ubicar una intro ya conocida mientras se reproduce otro capítulo.
 *
 * Valores ajustados con un prototipo y audio de prueba recodificado (AAC, MP3, Vorbis;
 * 44,1 y 48 kHz; volúmenes distintos): el mismo audio da en promedio ~5,6 bits distintos
 * de 32, y audio distinto ~16 (nunca por debajo de 13 en las pruebas).
 *
 * Intros con una parte que cambia en cada capítulo (como Los Simpson: la pizarra, el solo de
 * saxo y el sofá cambian, y hasta duran distinto) dan varios pedazos iguales separados por
 * partes distintas. Esos pedazos se unen en una sola intro si van en orden en los dos
 * capítulos y los separa poco (hasta [JOIN] cuadros).
 */
object SkipMatch {
    /** Ventana del suavizado (~2 s). */
    const val W = 21
    /** Promedio máximo de bits distintos para decir "es el mismo audio". */
    const val T = 10.0
    /** Huecos (cuadros) que se toleran dentro de un pedazo común. */
    const val GAP = 12
    /** Un pedazo común cuenta si dura al menos ~4 s. */
    const val PIECE_MIN = 43
    /** Pedazos separados por hasta ~25 s (la parte que cambia) se unen… */
    const val JOIN = 269
    /** …si los dos miden al menos ~8 s (así un logo corto no se une con la intro y se salta una escena). */
    const val JOIN_PIECE = 86
    /** Para ubicar una intro conocida: cuántos cuadros recientes se comparan (~3,7 s)… */
    const val K = 40
    /** …y el promedio máximo para aceptar (más estricto: decide saltar). */
    const val LOCATE_MAX = 9.0

    /**
     * Tramo común: en A va de [aStart] a [aEnd) y en B de [bStart] a [bEnd) (cuadros, relativos a
     * cada arreglo). [covered] = cuántos cuadros coinciden de verdad (sin contar las partes que cambian).
     */
    data class Segment(val aStart: Int, val aEnd: Int, val bStart: Int, val bEnd: Int, val covered: Int) {
        val length get() = aEnd - aStart
    }

    private class Piece(val aS: Int, val aE: Int, val d: Int) {
        val bS get() = aS + d
        val bE get() = aE + d
        val len get() = aE - aS
    }

    /**
     * El tramo común entre [a] y [b]: el que más coincide (o, con [latest] = true, el que termina
     * más tarde: para los créditos, que van al final). Debe coincidir al menos [minLen] cuadros y
     * medir como mucho [maxLen]. Prueba todos los desplazamientos (~n×m operaciones simples: con
     * 10 minutos por lado, menos de un segundo en una computadora y unos pocos en un TV Box).
     */
    fun common(a: IntArray, b: IntArray, minLen: Int, maxLen: Int, latest: Boolean = false, stop: () -> Boolean = { false }): Segment? {
        if (a.size < W || b.size < W) return null
        val pieces = ArrayList<Piece>()
        val n = minOf(a.size, b.size) + 1
        val h = IntArray(n); val v = IntArray(n)
        val cs = IntArray(n + 1); val cv = IntArray(n + 1)
        val half = W / 2
        for (d in -(a.size - 1) until b.size) {
            if ((d and 255) == 0 && stop()) break
            val i0 = maxOf(0, -d)
            val i1 = minOf(a.size, b.size - d)
            val len = i1 - i0
            if (len < maxOf(W, PIECE_MIN)) continue
            for (k in 0 until len) {
                val x = a[i0 + k]; val y = b[i0 + k + d]
                if (x != 0 && y != 0) { h[k] = Integer.bitCount(x xor y); v[k] = 1 } else { h[k] = 0; v[k] = 0 }
                cs[k + 1] = cs[k] + h[k]; cv[k + 1] = cv[k] + v[k]
            }
            var start = -1; var last = -1
            for (k in 0..len) {
                val good = if (k < len) {
                    val lo = maxOf(0, k - half); val hi = minOf(len, k + half + 1)
                    val cnt = cv[hi] - cv[lo]
                    cnt * 2 >= W && (cs[hi] - cs[lo]) <= T * maxOf(cnt, 1)
                } else false
                if (good) { if (start < 0) start = k; last = k }
                else if (start >= 0 && (k == len || k - last > GAP)) {
                    val s = i0 + start; val e = i0 + last + 1
                    if (e - s >= PIECE_MIN) pieces += refine(a, b, Piece(s, e, d))
                    start = -1
                }
            }
        }
        if (pieces.isEmpty()) return null
        // El mismo pedazo aparece en desplazamientos vecinos (±1 cuadro): quedarse con el más largo
        pieces.sortByDescending { it.len }
        val kept = ArrayList<Piece>()
        for (p in pieces) {
            val dup = kept.any { k -> kotlin.math.abs(k.d - p.d) <= 3 && minOf(k.aE, p.aE) - maxOf(k.aS, p.aS) > p.len / 2 }
            if (!dup) kept += p
            if (kept.size >= 400) break
        }
        kept.sortBy { it.aS }
        // Cadenas de pedazos en orden en los dos capítulos (la parte del medio puede cambiar)
        val m = kept.size
        val cov = IntArray(m); val prev = IntArray(m) { -1 }; val head = IntArray(m) { it }
        for (i in 0 until m) {
            val pi = kept[i]
            cov[i] = pi.len
            for (j in 0 until i) {
                val pj = kept[j]
                if (pi.len < JOIN_PIECE || pj.len < JOIN_PIECE) continue
                val gapA = pi.aS - pj.aE; val gapB = pi.bS - pj.bE
                if (gapA < -5 || gapB < -5 || gapA > JOIN || gapB > JOIN) continue
                val h0 = kept[head[j]]
                if (pi.aE - h0.aS > maxLen || pi.bE - h0.bS > maxLen) continue
                val c = cov[j] + pi.len
                if (c > cov[i]) { cov[i] = c; prev[i] = j; head[i] = head[j] }
            }
        }
        var best = -1
        for (i in 0 until m) {
            if (cov[i] < minLen) continue
            val p = kept[i]; val h0 = kept[head[i]]
            if (p.aE - h0.aS > maxLen || p.bE - h0.bS > maxLen) continue
            if (best < 0) { best = i; continue }
            val bp = kept[best]
            val better = if (latest) p.aE > bp.aE + 10 || (p.aE >= bp.aE - 10 && cov[i] > cov[best]) else cov[i] > cov[best]
            if (better) best = i
        }
        if (best < 0) return null
        val first = kept[head[best]]; val lastP = kept[best]
        return Segment(first.aS, lastP.aE, first.bS, lastP.bE, cov[best])
    }

    /** El suavizado deja los bordes un poco por dentro: se estiran mientras siga coincidiendo. */
    private fun refine(a: IntArray, b: IntArray, s: Piece, k: Int = 5, lim: Int = 40): Piece {
        fun m(i: Int): Double {
            if (i < 0 || i + s.d < 0 || i + k > a.size || i + s.d + k > b.size) return 99.0
            var sum = 0; var cnt = 0
            for (j in 0 until k) {
                val x = a[i + j]; val y = b[i + j + s.d]
                if (x != 0 && y != 0) { sum += Integer.bitCount(x xor y); cnt++ }
            }
            return if (cnt >= 3) sum.toDouble() / cnt else 99.0
        }
        var st = s.aS; var en = s.aE
        var n = lim
        while (n-- > 0 && m(st - 1) <= T) st--
        n = lim
        while (n-- > 0 && m(en - k + 1) <= T) en++
        return Piece(st, en, s.d)
    }

    /**
     * Ubicar los últimos cuadros escuchados ([q], seguidos) dentro de una intro o unos créditos
     * conocidos ([tpl]). Devuelve la posición en [tpl] donde empieza [q] y el promedio de bits
     * distintos, o null si no coincide en ningún lado.
     */
    fun locate(tpl: IntArray, q: IntArray): Pair<Int, Double>? {
        val kq = q.size
        if (kq == 0 || tpl.size < kq) return null
        var bestT = -1; var bestM = 99.0
        for (t in 0..tpl.size - kq) {
            var sum = 0; var cnt = 0
            for (j in 0 until kq) {
                val x = tpl[t + j]; val y = q[j]
                if (x != 0 && y != 0) { sum += Integer.bitCount(x xor y); cnt++ }
            }
            if (cnt < kq * 0.6) continue
            val m = sum.toDouble() / cnt
            if (m < bestM) { bestM = m; bestT = t }
        }
        return if (bestT >= 0 && bestM <= LOCATE_MAX) bestT to bestM else null
    }
}
