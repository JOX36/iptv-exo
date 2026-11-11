package com.jox3.iptvexo.tv.player.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pruebas del detector de intro y créditos con audio sintético: "canciones" (acordes, bajo,
 * melodía, batería) y "diálogo" (voz simulada con formantes, ambiente y música de fondo).
 * Cada capítulo se genera a otra frecuencia (44,1 / 48 kHz), con otro volumen y un poco de
 * ruido, y con la intro en otro momento: como dos copias reales de capítulos de una serie.
 */
class SkipMatchTest {

    // ── Audio de prueba ──

    private fun music(seconds: Double, seed: Long, rate: Int): FloatArray {
        val r = Random(seed)
        val n = (seconds * rate).toInt(); val out = FloatArray(n)
        val beat = 60.0 / (80 + r.nextInt(60))
        val root = 110.0 + r.nextDouble() * 110
        var t = 0.0
        fun note(f: Double, start: Double, dur: Double, amp: Double, harm: Int) {
            val i0 = (start * rate).toInt(); val len = (dur * rate).toInt()
            val ph = DoubleArray(harm) { r.nextDouble() * 6.28 }
            val w = DoubleArray(harm) { Math.pow(0.6, it.toDouble()) }
            for (k in 0 until len) {
                val i = i0 + k; if (i >= n) break
                val tt = k.toDouble() / rate
                val env = minOf(1.0, tt / 0.02) * minOf(1.0, (len - k).toDouble() / rate / 0.15)
                var v = 0.0
                for (h in 0 until harm) v += w[h] * sin(2 * PI * f * (h + 1) * tt + ph[h])
                out[i] += (v * env * amp).toFloat()
            }
        }
        while (t < seconds) {
            val chord = root * Math.pow(2.0, intArrayOf(0, 3, 5, 7, 8, 10)[r.nextInt(6)] / 12.0)
            for (k in intArrayOf(0, 4, 7)) note(chord * Math.pow(2.0, k / 12.0), t, beat * 4, 0.12, 4)
            note(chord / 2, t, beat * 4, 0.2, 3)
            for (b in 0 until 8) if (r.nextDouble() < 0.8)
                note(chord * 2 * Math.pow(2.0, intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)[r.nextInt(8)] / 12.0), t + b * beat / 2, beat / 2, 0.18, 5)
            for (b in 0 until 4) {
                val i0 = ((t + b * beat) * rate).toInt(); val m = (0.12 * rate).toInt()
                for (k in 0 until m) { val i = i0 + k; if (i < n) out[i] += (r.nextGaussian() * exp(-k / (0.02 * rate)) * 0.6).toFloat() }
            }
            t += beat * 4
        }
        val peak = out.maxOf { abs(it) } + 1e-9f
        for (i in out.indices) out[i] = out[i] / peak * 0.7f
        return out
    }

    private fun dialogue(seconds: Double, seed: Long, rate: Int): FloatArray {
        val r = Random(seed)
        val n = (seconds * rate).toInt(); val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val len = ((0.6 + r.nextDouble() * 2.4) * rate).toInt(); val pause = ((0.1 + r.nextDouble() * 0.7) * rate).toInt()
            val f0 = 90 + r.nextDouble() * 140
            val formants = DoubleArray(3) { 300 + r.nextDouble() * 2500 }
            var phase = 0.0
            val y1 = DoubleArray(3); val y2 = DoubleArray(3)
            for (k in 0 until len) {
                if (i + k >= n) break
                val f = f0 * (1 + 0.15 * sin(k.toDouble() / len * 6))
                phase += f / rate
                var x = if (phase >= 1) { phase -= 1; 1.0 } else 0.0
                x += r.nextGaussian() * 0.05
                for (j in 0 until 3) {
                    val rr = exp(-PI * 120 / rate); val c = 2 * rr * Math.cos(2 * PI * formants[j] / rate)
                    val y = (1 - rr) * x + c * y1[j] - rr * rr * y2[j]
                    y2[j] = y1[j]; y1[j] = y; x = y
                }
                out[i + k] += (x * 0.5 * (0.5 + 0.5 * abs(sin(k.toDouble() / rate * 9)))).toFloat()
            }
            i += len + pause
        }
        val peak = out.maxOf { abs(it) } + 1e-9f
        for (k in out.indices) out[k] = out[k] / peak * 0.5f + (r.nextGaussian() * 0.01).toFloat()
        return out
    }

    private fun scene(seconds: Double, seed: Long, rate: Int): FloatArray {
        val d = dialogue(seconds, seed, rate)
        if (seed % 2 == 0L) { val m = music(seconds, seed + 7, rate); for (i in d.indices) d[i] += m[i] * 0.25f }
        return d
    }

    private class Ep(val audio: FloatArray, val rate: Int, val introStart: Double, val introEnd: Double, val credits: Double) {
        val dur get() = audio.size.toDouble() / rate
    }

    /** Pasa de 48 kHz a otra frecuencia (interpolación lineal): como otra copia del mismo audio. */
    private fun resample(x: FloatArray, to: Int): FloatArray {
        if (to == BASE) return x
        val n = (x.size.toLong() * to / BASE).toInt()
        return FloatArray(n) { i ->
            val pos = i.toDouble() * BASE / to; val k = pos.toInt(); val f = (pos - k).toFloat()
            if (k + 1 < x.size) x[k] * (1 - f) + x[k + 1] * f else x[x.size - 1]
        }
    }

    /**
     * [escena][INTRO][escena][CRÉDITOS][avance opcional]: todo se genera a 48 kHz (así la intro es
     * idéntica en todos) y luego se pasa a [rate], con otro volumen y un poco de ruido.
     */
    private fun episode(seed: Long, rate: Int, cold: Double, mid: Double, tail: Double, introSeed: Long = 1234, creditsSeed: Long = 999): Ep {
        val parts = (listOf(scene(cold, seed, BASE), music(55.0, introSeed, BASE), scene(mid, seed + 10, BASE), music(40.0, creditsSeed, BASE)) +
            (if (tail > 0) listOf(scene(tail, seed + 20, BASE)) else emptyList())).map { resample(it, rate) }
        val all = FloatArray(parts.sumOf { it.size })
        var o = 0; for (p in parts) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
        val r = Random(seed * 31); val gain = 0.5f + r.nextFloat() * 0.7f
        for (i in all.indices) all[i] = all[i] * gain + (r.nextGaussian() * 0.003).toFloat()
        val s0 = parts[0].size.toDouble() / rate
        val s1 = s0 + parts[1].size.toDouble() / rate
        return Ep(all, rate, s0, s1, s1 + parts[2].size.toDouble() / rate)
    }

    private fun prints(e: Ep, phase: Int = 0): Prints {
        val p = Prints(); val fp = Fingerprinter(phase) { f, v -> p.put(f, v) }; val rs = Resampler(e.rate)
        fp.start(0.0)
        for (x in e.audio) { val y = rs.pushOne(x); if (y != null) fp.feed(y) }
        return p
    }

    private fun sec(f: Int) = f * AudioPrint.HOP_MS / 1000
    private companion object { const val BASE = 48000 }
    private val report = StringBuilder()
    private fun note(s: String) { println(s); report.append(s).append('\n') }
    private fun save() = runCatching { File("build/skip-report.txt").appendText(report.toString()) }

    // ── Pruebas ──

    @Test fun intro_y_creditos_entre_dos_capitulos() {
        val a = episode(100, 48000, cold = 31.3, mid = 90.0, tail = 0.0)
        val b = episode(200, 44100, cold = 74.7, mid = 70.0, tail = 20.0)
        val pa = prints(a); val pb = prints(b)
        val win = AudioPrint.frames(600_000)
        val t0 = System.nanoTime()
        val s = SkipMatch.common(pa.slice(0, win), pb.slice(0, win), AudioPrint.frames(15_000), AudioPrint.frames(200_000))
        note("intro: ${s?.let { "A ${"%.2f".format(sec(it.aStart))}-${"%.2f".format(sec(it.aEnd))} (real ${"%.2f".format(a.introStart)}-${"%.2f".format(a.introEnd)}), B ${"%.2f".format(sec(it.bStart))}-${"%.2f".format(sec(it.bEnd))} (real ${"%.2f".format(b.introStart)}-${"%.2f".format(b.introEnd)})" }} en ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertNotNull("no encontró la intro", s); s!!
        assertEquals(a.introStart, sec(s.aStart), 1.0); assertEquals(a.introEnd, sec(s.aEnd), 1.0)
        assertEquals(b.introStart, sec(s.bStart), 1.0); assertEquals(b.introEnd, sec(s.bEnd), 1.0)

        // Como el detector: los créditos se buscan en el último 30 % (máx. 6 min), prefiriendo lo que termina más tarde.
        // (Estos capítulos de prueba duran ~3,5 min: la ventana incluye parte de la intro, y aun así debe elegir los créditos.)
        val fa = AudioPrint.frameOf(((a.dur * 0.6) * 1000).toLong()); val fb = AudioPrint.frameOf(((b.dur * 0.6) * 1000).toLong())
        val c = SkipMatch.common(pa.slice(fa, pa.last), pb.slice(fb, pb.last), AudioPrint.frames(15_000), AudioPrint.frames(360_000), latest = true)
        note("créditos: ${c?.let { "A ${"%.2f".format(sec(fa + it.aStart))} (real ${"%.2f".format(a.credits)}), B ${"%.2f".format(sec(fb + it.bStart))} (real ${"%.2f".format(b.credits)})" }}")
        assertNotNull("no encontró los créditos", c); c!!
        assertEquals(a.credits, sec(fa + c.aStart), 1.0); assertEquals(b.credits, sec(fb + c.bStart), 1.0)
        save()
    }

    @Test fun reconoce_la_intro_en_vivo_en_otro_capitulo() {
        val a = episode(300, 48000, cold = 40.2, mid = 60.0, tail = 0.0)
        val b = episode(400, 44100, cold = 22.9, mid = 60.0, tail = 0.0)
        val win = AudioPrint.frames(600_000)
        val s = SkipMatch.common(prints(a).slice(0, win), prints(b).slice(0, win), AudioPrint.frames(15_000), AudioPrint.frames(200_000))!!
        val tpl = prints(b).slice(s.bStart, s.bEnd)
        val c = episode(500, 48000, cold = 63.37, mid = 60.0, tail = 0.0)
        val ph = (0..3).map { prints(c, it) }
        var misses = 0; var n = 0
        // Desde 4 s después de empezar la intro hasta 3 s antes de terminar: siempre debe reconocerla
        var t = c.introStart + 4.0
        while (t < c.introEnd - 3.0) {
            n++
            var best: Triple<Double, Double, Double>? = null
            for (p in 0..3) {
                val lf = ((t * 1000 - p * AudioPrint.HOP_MS / 4) / AudioPrint.HOP_MS).toInt()
                val r = SkipMatch.locate(tpl, ph[p].slice(lf - SkipMatch.K + 1, lf + 1)) ?: continue
                val start = ((lf - SkipMatch.K + 1 - r.first) + p / 4.0) * AudioPrint.HOP_MS / 1000
                val prev = best
                if (prev == null || r.second < prev.first) best = Triple(r.second, start, start + tpl.size * AudioPrint.HOP_MS / 1000)
            }
            val found = best
            if (found == null) misses++
            else { assertEquals(c.introStart, found.second, 1.2); assertEquals(c.introEnd, found.third, 1.2) }
            t += 1.7
        }
        note("en vivo: reconocida en ${n - misses} de $n momentos")
        assertEquals(0, misses)
        // Fuera de la intro no debe reconocer nada
        var falsos = 0
        t = 5.0
        while (t < c.dur) {
            if (t > c.introStart - 1 && t < c.introEnd + 4) { t += 2.3; continue }
            for (p in 0..3) {
                val lf = ((t * 1000) / AudioPrint.HOP_MS).toInt()
                if (SkipMatch.locate(tpl, ph[p].slice(lf - SkipMatch.K + 1, lf + 1)) != null) falsos++
            }
            t += 2.3
        }
        note("en vivo: reconocimientos falsos fuera de la intro: $falsos")
        assertEquals(0, falsos)
        save()
    }

    @Test fun series_distintas_no_se_confunden() {
        val a = episode(600, 48000, cold = 50.0, mid = 60.0, tail = 0.0, introSeed = 1, creditsSeed = 2)
        val b = episode(700, 44100, cold = 30.0, mid = 60.0, tail = 0.0, introSeed = 3, creditsSeed = 4)
        val win = AudioPrint.frames(600_000)
        val s = SkipMatch.common(prints(a).slice(0, win), prints(b).slice(0, win), AudioPrint.frames(15_000), AudioPrint.frames(200_000))
        note("series distintas: $s")
        assertNull(s)
        save()
    }

    @Test fun adelantar_cae_en_la_misma_grilla() {
        val a = episode(800, 44100, cold = 20.0, mid = 30.0, tail = 0.0)
        val full = prints(a)
        val part = Prints(); val fp = Fingerprinter(0) { f, v -> part.put(f, v) }; val rs = Resampler(a.rate)
        val from = (47.31 * a.rate).toInt()
        fp.start(from * 1000.0 / a.rate)
        for (i in from until a.audio.size) { val y = rs.pushOne(a.audio[i]); if (y != null) fp.feed(y) }
        var both = 0; var diff = 0L
        for (f in part.first until part.last) { val x = part[f]; val y = full[f]; if (x != 0 && y != 0) { both++; diff += Integer.bitCount(x xor y) } }
        note("adelantar: ${both} cuadros, bits distintos promedio ${"%.2f".format(diff.toDouble() / both)}")
        assertTrue(both > 500)
        assertTrue(diff.toDouble() / both < 4.0)
        save()
    }

    @Test fun base64_y_tramos() {
        val r = Random(5); val arr = IntArray(1234) { r.nextInt() }
        assertTrue(Prints.decode(Prints.encode(arr)).contentEquals(arr))
        for (k in 0..5) { val x = IntArray(k) { it * 77 + 1 }; assertTrue(Prints.decode(Prints.encode(x)).contentEquals(x)) }
        val p = Prints()
        p.put(500, 7); p.put(100, 3); p.put(2000, 9)
        assertEquals(3, p[100]); assertEquals(7, p[500]); assertEquals(9, p[2000]); assertEquals(0, p[101]); assertEquals(0, p[5])
        assertEquals(listOf(3, 0), p.slice(100, 102).toList())
        assertEquals(64, SkipDetector.srvKey("http://Example.com:8080/").length.let { it * 2 })
        assertEquals(SkipDetector.srvKey("http://example.com:8080"), SkipDetector.srvKey("HTTP://EXAMPLE.COM:8080/"))
    }
}
