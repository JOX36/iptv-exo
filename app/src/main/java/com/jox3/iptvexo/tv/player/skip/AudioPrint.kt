package com.jox3.iptvexo.tv.player.skip

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Huella del audio (para encontrar la intro y los créditos que se repiten entre capítulos).
 *
 * Es el método clásico de Haitsma y Kalker (el de los reconocedores de canciones):
 * el audio se baja a 5512 Hz en mono, se corta en cuadros de 0,37 s cada 0,093 s, se mide
 * la energía de 33 bandas entre 300 y 2000 Hz y cada cuadro queda en 32 bits: si la diferencia
 * entre bandas vecinas sube o baja respecto al cuadro anterior. Dos copias del mismo audio
 * (aunque una venga en AAC y la otra en MP3, a otro volumen o en otra calidad) dan casi los
 * mismos bits; audios distintos difieren en la mitad (~16 de 32).
 *
 * Los cuadros van en una grilla fija sobre el tiempo del video: el cuadro i empieza en
 * i × [HOP_MS]. Así, aunque se adelante o se retroceda, los cuadros siempre caen en su lugar.
 * Un 0 significa "sin dato" (silencio o una parte que no se escuchó).
 *
 * Sin nada de Android: se prueba en la computadora (ver las pruebas en src/test).
 */
object AudioPrint {
    const val SR = 5512
    const val FRAME = 2048
    const val HOP = 512
    const val HOP_MS = HOP * 1000.0 / SR
    private const val NB = 33
    /** Por debajo de esto (RMS, audio de -1 a 1) el cuadro es silencio y no se usa. */
    const val SILENCE_RMS = 0.002f

    /** Bordes de las bandas (en posiciones de la FFT). */
    internal val BINS: IntArray = IntArray(NB + 1) { b ->
        (300.0 * (2000.0 / 300.0).pow(b.toDouble() / NB) * FRAME / SR).roundToInt()
    }
    internal val WIN: FloatArray = FloatArray(FRAME) { i -> (0.5 - 0.5 * cos(2 * PI * i / FRAME)).toFloat() }

    fun frameOf(ms: Long): Int = (ms / HOP_MS).toInt()
    fun msOf(frame: Int): Long = (frame * HOP_MS).toLong()
    fun frames(ms: Long): Int = (ms / HOP_MS).roundToInt()

    /** Cuántos bits distintos (0…32). */
    fun ham(a: Int, b: Int): Int = Integer.bitCount(a xor b)
}

/**
 * Baja cualquier frecuencia (44,1 / 48 kHz…) a [AudioPrint.SR] promediando bloques
 * (con un acumulador fraccional, igual al prototipo con el que se ajustó).
 */
class Resampler(inRate: Int) {
    private val ratio = inRate.toDouble() / AudioPrint.SR
    private var sum = 0.0
    private var count = 0
    private var consumed = 0L      // muestras de entrada ya usadas
    private var produced = 0L      // muestras de salida entregadas

    fun reset() { sum = 0.0; count = 0; consumed = 0; produced = 0 }

    /** Agrega una muestra mono; devuelve una muestra nueva a 5512 Hz cuando se completa un bloque. */
    fun pushOne(x: Float): Float? {
        sum += x; count++; consumed++
        val boundary = kotlin.math.floor((produced + 1) * ratio).toLong()
        if (consumed >= boundary) {
            val v = (sum / count).toFloat()
            sum = 0.0; count = 0; produced++
            return v
        }
        return null
    }
}

/**
 * Convierte muestras (ya a 5512 Hz) en cuadros de huella sobre la grilla fija.
 * [start] marca un comienzo nuevo (abrir, adelantar, retroceder): el tiempo del video
 * de la primera muestra que llegará después.
 */
class Fingerprinter(
    /** Desfase de la grilla en cuartos de cuadro (0…3): el cuadro i empieza en (i + phase/4) × HOP_MS. */
    private val phase: Int = 0,
    private val onFrame: (frame: Int, print: Int) -> Unit,
) {
    private val buf = FloatArray(AudioPrint.FRAME)
    private var filled = 0
    private var skip = 0
    private var nextFrame = 0
    private var prev: DoubleArray? = null
    private var started = false
    private val re = DoubleArray(AudioPrint.FRAME)
    private val im = DoubleArray(AudioPrint.FRAME)
    private val energy = DoubleArray(34)

    fun start(mediaMs: Double) {
        val shift = phase * AudioPrint.HOP_MS / 4
        val first = ceil((mediaMs - shift) / AudioPrint.HOP_MS).toInt()
        skip = ((first * AudioPrint.HOP_MS + shift - mediaMs) * AudioPrint.SR / 1000.0).roundToInt().coerceAtLeast(0)
        nextFrame = first
        filled = 0
        prev = null
        started = true
    }

    fun stop() { started = false; prev = null; filled = 0 }

    fun feed(x: Float) {
        if (!started) return
        if (skip > 0) { skip--; return }
        buf[filled++] = x
        if (filled == AudioPrint.FRAME) {
            frame()
            // El siguiente cuadro empieza HOP muestras después (se solapan 3/4)
            System.arraycopy(buf, AudioPrint.HOP, buf, 0, AudioPrint.FRAME - AudioPrint.HOP)
            filled = AudioPrint.FRAME - AudioPrint.HOP
        }
    }

    private fun frame() {
        val idx = nextFrame++
        var ss = 0.0
        for (i in 0 until AudioPrint.FRAME) { val v = buf[i]; ss += v * v }
        if (sqrt(ss / AudioPrint.FRAME) < AudioPrint.SILENCE_RMS) { prev = null; return }
        for (i in 0 until AudioPrint.FRAME) { re[i] = (buf[i] * AudioPrint.WIN[i]).toDouble(); im[i] = 0.0 }
        Fft.transform(re, im)
        val bins = AudioPrint.BINS
        val e = energy
        for (b in 0 until 33) {
            var s = 0.0
            val to = maxOf(bins[b] + 1, bins[b + 1])
            for (k in bins[b] until to) s += re[k] * re[k] + im[k] * im[k]
            e[b] = s
        }
        val p = prev
        if (p != null) {
            var v = 0
            for (m in 0 until 32) {
                val d = (e[m] - e[m + 1]) - (p[m] - p[m + 1])
                if (d > 0) v = v or (1 shl m)
            }
            onFrame(idx, if (v == 0) 1 else v)
            System.arraycopy(e, 0, p, 0, 33)
        } else prev = e.copyOf(33)
    }
}

/** FFT de radix 2 (en el lugar). */
internal object Fft {
    private val cache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    private fun twiddles(n: Int) = synchronized(cache) {
        cache.getOrPut(n) { DoubleArray(n / 2) { cos(-2 * PI * it / n) } to DoubleArray(n / 2) { sin(-2 * PI * it / n) } }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val (cs, sn) = twiddles(n)
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = cs[k * step]; val wi = sn[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Cuadros de un tramo del video: [data] empieza en el cuadro [first] (0 = sin dato).
 * Crece solo al agregar cuadros más allá del final.
 */
class Prints(var first: Int = 0, var data: IntArray = IntArray(0)) {
    val size get() = data.size
    val last get() = first + data.size

    operator fun get(frame: Int): Int = if (frame < first || frame >= last) 0 else data[frame - first]

    fun put(frame: Int, v: Int) {
        if (frame < 0) return
        if (data.isEmpty()) { first = frame; data = IntArray(256) }
        if (frame < first) {
            val grow = first - frame
            data = IntArray(grow) + data
            first = frame
        }
        val i = frame - first
        if (i >= data.size) data = data.copyOf(maxOf(i + 1, data.size * 2))
        data[i] = v
    }

    /** Copia del tramo [from, to) (cuadros), con 0 donde no hay dato. */
    fun slice(from: Int, to: Int): IntArray = IntArray((to - from).coerceAtLeast(0)) { this[from + it] }

    /** Qué parte del tramo [from, to) tiene dato (0…1). */
    fun coverage(from: Int, to: Int): Float {
        if (to <= from) return 0f
        var n = 0
        for (f in from until to) if (this[f] != 0) n++
        return n.toFloat() / (to - from)
    }

    companion object {
        /** Para guardar o mandar: enteros de 32 bits en base64. */
        fun encode(a: IntArray): String {
            val b = ByteArray(a.size * 4)
            for (i in a.indices) {
                val v = a[i]
                b[i * 4] = (v and 0xFF).toByte(); b[i * 4 + 1] = (v shr 8 and 0xFF).toByte()
                b[i * 4 + 2] = (v shr 16 and 0xFF).toByte(); b[i * 4 + 3] = (v ushr 24).toByte()
            }
            return B64.encode(b)
        }

        fun decode(s: String): IntArray = runCatching {
            val b = B64.decode(s)
            IntArray(b.size / 4) { i ->
                (b[i * 4].toInt() and 0xFF) or (b[i * 4 + 1].toInt() and 0xFF shl 8) or
                    (b[i * 4 + 2].toInt() and 0xFF shl 16) or (b[i * 4 + 3].toInt() and 0xFF shl 24)
            }
        }.getOrDefault(IntArray(0))
    }
}

/** Base64 propio (java.util.Base64 no existe en Android 7 o menor; android.util no corre en las pruebas). */
internal object B64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REV = IntArray(128) { -1 }.also { r -> A.forEachIndexed { i, c -> r[c.code] = i } }

    fun encode(b: ByteArray): String {
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 0xFF shl 16) or
                ((if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0)
            sb.append(A[n shr 18 and 63]).append(A[n shr 12 and 63])
            sb.append(if (i + 1 < b.size) A[n shr 6 and 63] else '=')
            sb.append(if (i + 2 < b.size) A[n and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filter { it.code < 128 && REV[it.code] >= 0 }
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var i = 0
        while (i + 1 < clean.length) {
            val c0 = REV[clean[i].code]; val c1 = REV[clean[i + 1].code]
            val c2 = if (i + 2 < clean.length) REV[clean[i + 2].code] else -1
            val c3 = if (i + 3 < clean.length) REV[clean[i + 3].code] else -1
            out.write(c0 shl 2 or (c1 shr 4))
            if (c2 >= 0) out.write((c1 and 15) shl 4 or (c2 shr 2))
            if (c3 >= 0) out.write((c2 and 3) shl 6 or c3)
            i += 4
        }
        return out.toByteArray()
    }
}
