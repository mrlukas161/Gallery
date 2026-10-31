package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.commons.extensions.formatSize
import org.fossify.gallery.R

// Podklad pre kartu „Na upratanie" na Domove — tie isté skupiny ako zoznam „Podobné a duplikáty“
// (CompareRepository, bez skrytých skupín, len fotky v rozsahu IndexScope, ktoré ešte existujú):
//  - bursts = počet skupín v záložke Série,
//  - duplicates = počet skupín v záložkách Presné kópie + Podobné,
//  - wastedBytes = koľko by uvoľnilo ponechanie jednej fotky v každej skupine (každá fotka raz),
//  - preferredTab = záložka, ktorú otvorí ťuk na kartu (tá s najviac skupinami).
//
// POZOR: compute() číta MediaStore, pHash index a disk — SMIE sa volať len z vlákna na pozadí
// (ensureBackgroundThread), nikdy z hlavného vlákna. Výsledok zoskupenia sa cachuje (CompareRepository).
object HomeStats {

    data class Result(
        val bursts: Int,
        val duplicates: Int,
        val wastedBytes: Long,
        val hasPhashIndex: Boolean,
        val preferredTab: Int = CompareGroups.TAB_EXACT,
    )

    fun compute(context: Context): Result {
        val snapshot = CompareRepository.load(context)
        val hidden = CompareHiddenStore.get(context)
        val visible = snapshot.groups.filter { !hidden.isHidden(it.paths) }
        return Result(
            bursts = visible.count { it.kind == CompareKind.BURST },
            duplicates = visible.count { it.kind != CompareKind.BURST },
            wastedBytes = CompareGroups.reclaimableTotal(visible) { snapshot.sizes[it] ?: 0L },
            hasPhashIndex = snapshot.hasPhashIndex,
            preferredTab = CompareGroups.preferredTab(visible),
        )
    }

    // text karty: bez indexu výzva na spustenie, inak počty skupín zo záložiek a uvoľniteľné miesto
    fun summary(context: Context, stats: Result): String = when {
        !stats.hasPhashIndex -> context.getString(R.string.home_cleanup_no_index)
        stats.duplicates == 0 && stats.bursts == 0 -> context.getString(R.string.home_cleanup_none)
        else -> context.getString(
            R.string.pb_home_cleanup_summary,
            stats.bursts, stats.duplicates, stats.wastedBytes.formatSize(),
        )
    }
}
