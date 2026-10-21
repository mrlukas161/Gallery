package org.fossify.gallery.safety

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

// Názvy a rotácia automatických záloh (A3). Čistá logika bez Androidu (AutoBackupNamesTest).
//
// Jedna záloha = dva súbory s rovnakým časom v Documents/Galeria+/zalohy/:
//   galeria-osoby-auto-20261021-031500.galeria-family   F6 záloha osôb (obnova cez existujúcu F6 obnovu)
//   galeria-databazy-auto-20261021-031500.zip           VACUUM INTO kópie people/extras/family (núdzová obnova)
// Ponechajú sa 4 najnovšie z každého druhu; staršie sa zmažú až po úspešnom zápise novej.
object AutoBackupNames {
    const val RELATIVE_DIR = "Documents/Galeria+/zalohy/"
    const val DIR_NAME = "Galeria+"
    const val SUB_DIR = "zalohy"
    const val KEEP = 4

    private const val F6_PREFIX = "galeria-osoby-auto-"
    private const val F6_SUFFIX = ".galeria-family"
    private const val DB_PREFIX = "galeria-databazy-auto-"
    private const val DB_SUFFIX = ".zip"
    private const val STAMP = "yyyyMMdd-HHmmss"

    enum class Kind { F6, DB }

    data class Parsed(val name: String, val kind: Kind, val createdAt: Long)

    fun f6Name(stamp: String) = "$F6_PREFIX$stamp$F6_SUFFIX"

    fun dbName(stamp: String) = "$DB_PREFIX$stamp$DB_SUFFIX"

    fun stamp(millis: Long): String = DbFiles.stamp(millis)

    // null = súbor nie je automatická záloha (napr. ručná záloha alebo iný súbor v priečinku)
    fun parse(name: String): Parsed? {
        val (kind, stamp) = when {
            name.startsWith(F6_PREFIX) && name.endsWith(F6_SUFFIX) ->
                Kind.F6 to name.removePrefix(F6_PREFIX).removeSuffix(F6_SUFFIX)

            name.startsWith(DB_PREFIX) && name.endsWith(DB_SUFFIX) ->
                Kind.DB to name.removePrefix(DB_PREFIX).removeSuffix(DB_SUFFIX)

            else -> return null
        }
        if (stamp.length != STAMP.length) return null
        val millis = try {
            SimpleDateFormat(STAMP, Locale.US).apply { isLenient = false }.parse(stamp)?.time
        } catch (ignored: ParseException) {
            null
        } ?: return null
        return Parsed(name, kind, millis)
    }

    // najnovšie prvé, len automatické zálohy daného druhu
    fun sorted(names: Collection<String>, kind: Kind): List<Parsed> =
        names.mapNotNull { parse(it) }.filter { it.kind == kind }.distinctBy { it.name }
            .sortedWith(compareByDescending<Parsed> { it.createdAt }.thenByDescending { it.name })

    // súbory na zmazanie po zápise novej zálohy: všetko okrem `keep` najnovších z každého druhu
    fun toRotate(names: Collection<String>, keep: Int = KEEP): List<String> =
        Kind.entries.flatMap { kind -> sorted(names, kind).drop(keep).map { it.name } }
}
