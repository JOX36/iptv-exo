package com.jox3.iptvexo.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.jox3.iptvexo.PlayerSafety
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Información técnica del video que se está viendo (traída de JOX3 Verifier):
 * resolución, códecs, bitrate, búfer, servidor y formato. La usan los
 * reproductores de TV y de celular.
 */
@OptIn(UnstableApi::class)
fun techInfo(ctx: Context, p: ExoPlayer?, url: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val v = p?.videoFormat
    val a = p?.audioFormat
    if (v != null) out += "Video" to listOfNotNull(
        if (v.width > 0) "${v.width}×${v.height}" else null,
        codec(v.sampleMimeType),
        if (v.frameRate > 0) "${v.frameRate.roundToInt()} fps" else null,
        bitrate(v.bitrate),
    ).joinToString("  ·  ")
    if (a != null) out += "Audio" to listOfNotNull(
        codec(a.sampleMimeType),
        when (a.channelCount) { 0 -> null; 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${a.channelCount} canales" },
        if (a.sampleRate > 0) "${a.sampleRate / 1000} kHz" else null,
        bitrate(a.bitrate),
        a.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).getDisplayLanguage(Locale("es")) },
    ).joinToString("  ·  ")
    if (v == null && a == null) out += "Estado" to "Espera a que el video empiece a reproducirse"
    if (p != null) out += "Búfer" to "${p.totalBufferedDuration / 1000} s cargados"
    val host = try { java.net.URI(url).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } } catch (e: Exception) { "" }
    if (host.isNotBlank()) out += "Servidor" to host
    val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
    if (ext.isNotEmpty() && ext.length <= 5) out += "Formato" to ext.uppercase()
    out += "Audio avanzado" to if (PlayerSafety.ffmpegAllowed(ctx)) "FFmpeg activo" else "FFmpeg desactivado"
    return out
}

private fun codec(mime: String?): String? = when (mime) {
    null -> null
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/mpeg2" -> "MPEG-2"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    "audio/mp4a-latm" -> "AAC"
    "audio/ac3" -> "Dolby Digital"
    "audio/eac3" -> "Dolby Digital+"
    "audio/true-hd" -> "Dolby TrueHD"
    "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
    "audio/mpeg", "audio/mpeg-L2" -> "MP3/MP2"
    "audio/opus" -> "Opus"
    else -> mime.substringAfter('/').uppercase()
}

private fun bitrate(b: Int): String? =
    if (b <= 0) null else if (b >= 1_000_000) String.format(Locale.US, "%.1f Mbps", b / 1_000_000f) else "${b / 1000} kbps"
