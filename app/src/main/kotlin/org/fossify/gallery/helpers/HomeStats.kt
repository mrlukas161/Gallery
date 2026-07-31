package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.faces.PhashDatabase
import org.fossify.gallery.faces.PhashGrouper
import java.io.File

// Podklad pre kartu „Na upratanie" na Domove. Počíta sa z UŽ existujúceho pHash indexu, takže
// netreba znovu prehľadávať knižnicu — série a duplikáty sú skupiny perceptuálne podobných fotiek.
//
// POZOR: groupBySimilarity() prechádza celý index (pri veľkej knižnici tisíce položiek) a čítanie
// veľkostí súborov siaha na disk — compute() sa preto SMIE volať len z vlákna na pozadí
// (ensureBackgroundThread), nikdy z hlavného vlákna.
object HomeStats {

    data class Result(
        val bursts: Int,
        val duplicates: Int,
        val wastedBytes: Long,
        val hasPhashIndex: Boolean,
    )

    fun compute(context: Context): Result {
        val hashes = try {
            PhashDatabase.getInstance(context).PhashDao().getAllHashes()
        } catch (e: Throwable) {
            emptyList()
        }

        if (hashes.isEmpty()) {
            return Result(0, 0, 0L, false)
        }

        val groups = PhashGrouper.groupBySimilarity(hashes)
        var dupes = 0
        var wasted = 0L
        for (group in groups) {
            // v každej skupine ostane jedna fotka, zvyšok je na zmazanie
            val extra = group.drop(1)
            dupes += extra.size
            extra.forEach { path ->
                wasted += try {
                    File(path).length()
                } catch (e: Throwable) {
                    0L
                }
            }
        }

        return Result(
            bursts = groups.size,
            duplicates = dupes,
            wastedBytes = wasted,
            hasPhashIndex = true,
        )
    }
}
