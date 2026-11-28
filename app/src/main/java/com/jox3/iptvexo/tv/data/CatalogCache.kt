package com.jox3.iptvexo.tv.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Catálogo de cada servidor (canales, películas, series) guardado en el equipo, para que el
 * buscador responda al instante: al abrir la app se lee del disco (1–2 s) en vez de descargarlo
 * (puede tardar un minuto con catálogos grandes). Se renueva en segundo plano cuando tiene
 * más de 6 horas. Solo nombres, imágenes y números: nada de usuarios ni claves.
 */
object CatalogCache {
    private const val VERSION = 1
    /** Más viejo que esto ya no se usa (se descarga de nuevo). */
    const val MAX_AGE = 3 * 24 * 3600_000L
    /** Más viejo que esto se usa, pero se renueva en segundo plano. */
    const val REFRESH_AGE = 6 * 3600_000L

    private fun file(ctx: Context, acc: Account) =
        File(File(ctx.filesDir, "catalog").apply { mkdirs() }, acc.key.hashCode().toUInt().toString(36) + ".bin.gz")

    /** Cuánto tiene el guardado (ms), o null si no hay. */
    fun age(ctx: Context, acc: Account): Long? = file(ctx, acc).takeIf { it.exists() && it.length() > 0 }
        ?.let { System.currentTimeMillis() - it.lastModified() }

    fun forget(ctx: Context, acc: Account) { file(ctx, acc).delete() }

    fun save(ctx: Context, acc: Account, idx: TvRepo.Index) {
        val f = file(ctx, acc)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(tmp)), 64 * 1024)).use { o ->
                fun s(v: String) = o.writeUTF(if (v.length > 4000) v.take(4000) else v)
                o.writeInt(VERSION)
                o.writeInt(idx.channels.size)
                idx.channels.forEach { c -> s(c.id); s(c.name); s(c.logo); s(c.categoryId); o.writeInt(c.num); s(c.epgId); o.writeInt(c.archiveDays) }
                o.writeInt(idx.movies.size)
                idx.movies.forEach { m -> s(m.id); s(m.name); s(m.poster); s(m.rating); s(m.ext); s(m.categoryId); o.writeLong(m.added) }
                o.writeInt(idx.series.size)
                idx.series.forEach { r -> s(r.id); s(r.name); s(r.poster); s(r.rating); s(r.categoryId); o.writeLong(r.updated) }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { tmp.delete() }
    }

    fun load(ctx: Context, acc: Account): TvRepo.Index? {
        val f = file(ctx, acc)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE) return null
        return runCatching {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f), 64 * 1024))).use { i ->
                if (i.readInt() != VERSION) return@runCatching null
                val ch = List(i.readInt()) { Channel(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readInt(), i.readUTF(), i.readInt()) }
                val mv = List(i.readInt()) { Movie(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readLong()) }
                val sr = List(i.readInt()) { Series(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readLong()) }
                TvRepo.Index(ch, mv, sr)
            }
        }.getOrNull()
    }
}
