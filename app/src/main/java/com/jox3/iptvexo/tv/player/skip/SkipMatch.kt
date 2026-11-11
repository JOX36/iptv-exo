package com.jox3.iptvexo.tv.player.skip

/**
 * Comparar huellas: encontrar el tramo que dos capítulos tienen en común (la intro o los
 * créditos) y ubicar una intro ya conocida mientras se reproduce otro capítulo.
 *
 * Valores ajustados con un prototipo y audio de prueba recodificado (AAC, MP3, Vorbis;
 * 44,1 y 48 kHz; volúmenes distintos): el mismo audio da en promedio ~5,6 bits distintos
 * de 32, y audio distinto ~16 (nunca por debajo de 13 en las pruebas).
 */
object SkipMatch {
    /** Ventana del suavizado (~2 s). */
    const val W = 21
    /** Promedio máximo de bits distintos para decir "es el mismo audio". */
    const val T = 10.0
    /** Huecos (cuadros) que se toleran dentro de un tramo común. */
    const val GAP = 12
    /** Para ubicar una intro conocida: cuántos cuadros recientes se comparan (~3,7 s)… */
    const val K = 40
    /** …y el promedio máximo para aceptar (más estricto: decide saltar). */
    const val LOCATE_MAX = 9.0

    /** Tramo común: en A va de [aStart] a [aEnd) (cuadros, relativos al arreglo A); en B es lo mismo + [shift]. */
    data class Segment(val aStart: Int, val aEnd: Int, val shift: Int) {
        val length get() = aEnd - aStart
        val bStart get() = aStart + shift
        val bEnd get() = aEnd + shift
    }

    /**
     * El tramo común más largo entre [a] y [b] cuyo largo está entre [minLen] y [maxLen] cuadros
     * (con [latest] = true, el que termina más tarde: para los créditos, que van al final).
     * Prueba todos los desplazamientos (cuesta ~n×m operaciones simples: con 10 minutos por lado,
     * menos de un segundo incluso en un TV Box).
     */
    fun common(a: IntArray, b: IntArray, minLen: Int, maxLen: Int, latest: Boolean = false, stop: () -> Boolean = { false }): Segment? {
        if (a.size < W || b.size < W) return null
        val n = minOf(a.size, b.size) + 1
        val h = IntArray(n); val v = IntArray(n)
        val cs = IntArray(n + 1); val cv = IntArray(n + 1)
        var best: Segment? = null
        val half = W / 2
        for (d in -(a.size - 1) until b.size) {
            if ((d and 255) == 0 && stop()) return best
            val i0 = maxOf(0, -d)
            val i1 = minOf(a.size, b.size - d)
            val len = i1 - i0
            if (len < maxOf(W, minLen)) continue
            // Bits distintos de cada par y sumas acumuladas
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
                    val l = e - s
                    val better = best == null || if (latest) e > best.aEnd + 10 || (e >= best.aEnd - 10 && l > best.length) else l > best.length
                    if (l in minLen..maxLen && better) best = Segment(s, e, d)
                    start = -1
                }
            }
        }
        return best?.let { refine(a, b, it) }
    }

    /** El suavizado deja los bordes un poco por dentro: se estiran mientras siga coincidiendo. */
    fun refine(a: IntArray, b: IntArray, s: Segment, k: Int = 5, lim: Int = 40): Segment {
        fun m(i: Int): Double {
            if (i < 0 || i + s.shift < 0 || i + k > a.size || i + s.shift + k > b.size) return 99.0
            var sum = 0; var cnt = 0
            for (j in 0 until k) {
                val x = a[i + j]; val y = b[i + j + s.shift]
                if (x != 0 && y != 0) { sum += Integer.bitCount(x xor y); cnt++ }
            }
            return if (cnt >= 3) sum.toDouble() / cnt else 99.0
        }
        var st = s.aStart; var en = s.aEnd
        var n = lim
        while (n-- > 0 && m(st - 1) <= T) st--
        n = lim
        while (n-- > 0 && m(en - k + 1) <= T) en++
        return Segment(st, en, s.shift)
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
