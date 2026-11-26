package com.jox3.iptvexo.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Oído" del canal en vivo para "¿Qué estoy viendo?": guarda los últimos 25 segundos de lo que
 * está sonando (mono, 16 kHz) para mandárselos a la IA. Los diálogos identifican un capítulo mucho
 * mejor que una foto. No descarga nada aparte y no cambia el sonido: es una copia.
 * Si el equipo manda el sonido sin decodificar al teatro en casa (Dolby/DTS directo), no hay copia.
 */
object LiveAudio {
    const val RATE = 16_000
    private const val SECONDS = 25
    private val ring = ShortArray(RATE * SECONDS)
    private var pos = 0
    private var filled = 0

    @Synchronized fun clear() { pos = 0; filled = 0 }

    @Synchronized internal fun push(s: Short) {
        ring[pos] = s
        pos = (pos + 1) % ring.size
        if (filled < ring.size) filled++
    }

    /** Los últimos [sec] segundos como archivo WAV (null si hay menos de 6 s guardados). */
    @Synchronized fun lastWav(sec: Int): ByteArray? {
        val n = minOf(filled, sec * RATE)
        if (n < 6 * RATE) return null
        val pcm = ByteArray(n * 2)
        var i = (pos - n + ring.size) % ring.size
        for (k in 0 until n) {
            val v = ring[i].toInt()
            pcm[2 * k] = (v and 0xff).toByte(); pcm[2 * k + 1] = ((v shr 8) and 0xff).toByte()
            i = (i + 1) % ring.size
        }
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(x: Int) { out.write(x and 0xff); out.write((x shr 8) and 0xff); out.write((x shr 16) and 0xff); out.write((x shr 24) and 0xff) }
        fun i16(x: Int) { out.write(x and 0xff); out.write((x shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}

/** Fábrica del reproductor en vivo con el "oído" de [LiveAudio]. */
@OptIn(UnstableApi::class)
class LiveRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return LiveTapSink(base)
    }
}

@OptIn(UnstableApi::class)
private class LiveTapSink(base: AudioSink) : ForwardingAudioSink(base) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var lastPts = Long.MIN_VALUE
    // Bajar a 16 kHz promediando (filtro simple: suficiente para entender voces)
    private var acc = 0f
    private var cnt = 0
    private var phase = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
        encoding = if (pcm) inputFormat.pcmEncoding else C.ENCODING_INVALID
        channels = inputFormat.channelCount
        rate = inputFormat.sampleRate
        acc = 0f; cnt = 0; phase = 0
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != lastPts) {
            lastPts = presentationTimeUs
            runCatching { tap(buffer) }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() { lastPts = Long.MIN_VALUE; LiveAudio.clear(); super.flush() }

    private fun tap(src: ByteBuffer) {
        if (rate <= 0 || channels <= 0) return
        val bytesPer = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            else -> return
        }
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = b.remaining() / (bytesPer * channels)
        val inv = 1f / channels
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) {
                s += when {
                    bytesPer == 2 -> b.short.toFloat() / 32768f
                    encoding == C.ENCODING_PCM_FLOAT -> b.float
                    else -> b.int / 2147483648f
                }
            }
            acc += s * inv; cnt++
            phase += LiveAudio.RATE
            if (phase >= rate) {
                phase -= rate
                val v = (acc / cnt).coerceIn(-1f, 1f)
                LiveAudio.push((v * 32767f).toInt().toShort())
                acc = 0f; cnt = 0
            }
        }
    }
}
