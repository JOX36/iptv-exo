package com.jox3.iptvexo.tv.player.skip

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Oído" del reproductor: deja pasar el audio sin tocarlo y le pasa una copia (en mono y a
 * 5512 Hz) a [SkipDetector]. No descarga nada aparte: es el mismo audio que está sonando.
 *
 * Si el equipo manda el sonido sin decodificar al teatro en casa (Dolby/DTS directo), aquí
 * no llega audio que escuchar y simplemente no se detecta nada.
 */
@OptIn(UnstableApi::class)
class SkipRenderersFactory(context: Context, private val sink: (Int, Double, FloatArray, Int) -> Unit, private val onReset: () -> Unit) :
    DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return TapSink(base, sink, onReset)
    }
}

@OptIn(UnstableApi::class)
private class TapSink(
    base: AudioSink,
    /** (frecuencia de entrada, ms del video de la 1.ª muestra, muestras a 5512 Hz, cuántas) */
    private val out: (Int, Double, FloatArray, Int) -> Unit,
    private val onReset: () -> Unit,
) : ForwardingAudioSink(base) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var offsetUs = 0L
    private var lastPts = Long.MIN_VALUE
    private var expectUs = Long.MIN_VALUE
    private var resampler: Resampler? = null
    private var chunkStartMs = 0.0
    private var outBuf = FloatArray(4096)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
        encoding = if (pcm) inputFormat.pcmEncoding else C.ENCODING_INVALID
        channels = inputFormat.channelCount
        rate = inputFormat.sampleRate
        resampler = if (pcm && rate > 0 && channels > 0) Resampler(rate) else null
        expectUs = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != lastPts) {
            lastPts = presentationTimeUs
            runCatching { tap(buffer, presentationTimeUs - offsetUs) }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() { expectUs = Long.MIN_VALUE; lastPts = Long.MIN_VALUE; onReset(); super.flush() }
    override fun reset() { expectUs = Long.MIN_VALUE; lastPts = Long.MIN_VALUE; onReset(); super.reset() }

    private fun tap(src: ByteBuffer, mediaUs: Long) {
        val rs = resampler ?: return
        val bytesPer = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> return
        }
        // Tiempo fuera de lo razonable (p. ej. sin el desfase del reproductor): no escuchar
        if (mediaUs < 0 || mediaUs > 6 * 3600 * 1_000_000L) return
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = b.remaining() / (bytesPer * channels)
        if (frames <= 0) return
        // ¿Salto (adelantar, retroceder, otro capítulo)? → empezar de nuevo en la grilla
        val jump = expectUs == Long.MIN_VALUE || kotlin.math.abs(mediaUs - expectUs) > 40_000
        if (jump) { rs.reset(); chunkStartMs = mediaUs / 1000.0 }
        expectUs = mediaUs + frames * 1_000_000L / rate
        var n = 0
        if (outBuf.size < frames / 4 + 16) outBuf = FloatArray(frames / 4 + 16)
        val inv = 1f / channels
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) {
                s += when (bytesPer) {
                    2 -> b.short.toFloat() / 32768f
                    3 -> { val lo = b.get().toInt() and 0xFF; val mid = b.get().toInt() and 0xFF; val hi = b.get().toInt(); ((hi shl 16) or (mid shl 8) or lo) / 8388608f }
                    else -> if (encoding == C.ENCODING_PCM_FLOAT) b.float else b.int / 2147483648f
                }
            }
            val y = rs.pushOne(s * inv) ?: continue
            if (n >= outBuf.size) outBuf = outBuf.copyOf(outBuf.size * 2)
            outBuf[n++] = y
        }
        if (n > 0) out(rate, if (jump) chunkStartMs else -1.0, outBuf.copyOf(n), n)
    }
}
