package com.jox3.iptvexo.tv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.jox3.iptvexo.R

/**
 * Clic suave al mover el cursor con el control (solo TV, como TiviMate).
 * Suena cuando una tecla de dirección movió de verdad el foco a otro elemento;
 * en el reproductor (adelantar, cambiar canal) no suena.
 */
object NavSound {
    private const val PREFS = "jox3_ui"
    private var pool: SoundPool? = null
    private var soundId = 0
    private var ready = false
    @Volatile private var armedAt = 0L
    private var lastPlay = 0L

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("nav_sound", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("nav_sound", on).apply()
        if (on) init(ctx) else release()
    }

    fun init(ctx: Context) {
        if (pool != null || !enabled(ctx)) return
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        soundId = p.load(ctx.applicationContext, R.raw.nav_click, 1)
        pool = p
    }

    fun release() { pool?.release(); pool = null; ready = false }

    /** Se pulsó una flecha del control: el próximo cambio de foco suena. */
    fun arm() { armedAt = SystemClock.uptimeMillis() }

    /** Un elemento recibió el foco. */
    fun onFocusMoved() {
        val now = SystemClock.uptimeMillis()
        if (now - armedAt > 350) return
        armedAt = 0L
        if (now - lastPlay < 35) return
        lastPlay = now
        if (ready) pool?.play(soundId, 0.35f, 0.35f, 1, 0, 1f)
    }
}
