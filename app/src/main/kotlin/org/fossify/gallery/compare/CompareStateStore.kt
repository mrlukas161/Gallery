package org.fossify.gallery.compare

import android.os.Bundle
import java.io.File

// Stav obrazovky mimo CompareSession: režimy a priblíženie panelov.
data class CompareUiState(
    val sync: Boolean = true,
    val stripHidden: Boolean = false,
    // 0 = dva panely, 2 / 3 = mriežka 2×2 / 3×3
    val gridSpan: Int = 0,
    val selection: Boolean = false,
    val infoOpen: Boolean = false,
    val overlays: Boolean = true,
    val viewA: RelativeView? = null,
    val viewB: RelativeView? = null,
)

// Uloženie a obnova porovnávača do Bundle (otočenie, zabitie procesu): cesty, značky, panely, turnaj,
// režimy a priblíženie. PathTransfer po zabití procesu už nič nemá, preto sa cesty ukladajú sem.
// Obrovská skupina (nad MAX_CHARS znakov ciest) sa neukladá, aby Bundle nespadol na TransactionTooLarge.
object CompareStateStore {
    private const val MAX_CHARS = 250_000
    private const val K_PATHS = "pa_paths"
    private const val K_MARKED = "pa_marked"
    private const val K_A = "pa_a"
    private const val K_B = "pa_b"
    private const val K_BEST = "pa_best"
    private const val K_MOVED = "pa_moved"
    private const val K_DUEL = "pa_duel"
    private const val K_DUEL_QUEUE = "pa_duel_queue"
    private const val K_DUEL_PLAYED = "pa_duel_played"
    private const val K_UI = "pa_ui"
    private const val K_VIEW_A = "pa_view_a"
    private const val K_VIEW_B = "pa_view_b"
    private const val UI_SYNC = 0
    private const val UI_STRIP_HIDDEN = 1
    private const val UI_SELECTION = 2
    private const val UI_INFO = 3
    private const val UI_OVERLAYS = 4
    private const val UI_FLAGS = 5
    private const val K_GRID = "pa_grid"
    private const val VIEW_FIELDS = 3

    fun save(out: Bundle, session: CompareSession, tournament: Tournament, ui: CompareUiState) {
        if (session.paths.sumOf { it.length } * 2 > MAX_CHARS) return
        out.putStringArrayList(K_PATHS, ArrayList(session.paths))
        out.putStringArrayList(K_MARKED, ArrayList(session.marked))
        out.putString(K_A, session.a)
        out.putString(K_B, session.b)
        out.putString(K_BEST, session.best)
        out.putBoolean(K_MOVED, session.userMoved)
        out.putBoolean(K_DUEL, tournament.active)
        out.putStringArrayList(K_DUEL_QUEUE, ArrayList(tournament.waiting))
        out.putInt(K_DUEL_PLAYED, tournament.played)
        val flags = BooleanArray(UI_FLAGS)
        flags[UI_SYNC] = ui.sync
        flags[UI_STRIP_HIDDEN] = ui.stripHidden
        flags[UI_SELECTION] = ui.selection
        flags[UI_INFO] = ui.infoOpen
        flags[UI_OVERLAYS] = ui.overlays
        out.putBooleanArray(K_UI, flags)
        out.putInt(K_GRID, ui.gridSpan)
        ui.viewA?.let { out.putFloatArray(K_VIEW_A, floatArrayOf(it.centerX, it.centerY, it.zoom)) }
        ui.viewB?.let { out.putFloatArray(K_VIEW_B, floatArrayOf(it.centerX, it.centerY, it.zoom)) }
    }

    // null = nič uložené; fotky, ktoré medzitým zmizli, sa vynechajú
    fun restoreSession(saved: Bundle): CompareSession? {
        val paths = saved.getStringArrayList(K_PATHS)?.filter { File(it).exists() } ?: return null
        val s = CompareSession(paths)
        saved.getStringArrayList(K_MARKED)?.filterTo(s.marked) { it in s.paths }
        saved.getString(K_A)?.takeIf { it in s.paths }?.let { s.a = it }
        saved.getString(K_B)?.takeIf { it in s.paths && it != s.a }?.let { s.b = it }
        s.best = saved.getString(K_BEST)?.takeIf { it in s.paths }
        s.userMoved = saved.getBoolean(K_MOVED)
        return s
    }

    fun restoreTournament(saved: Bundle, tournament: Tournament) {
        if (!saved.getBoolean(K_DUEL)) return
        tournament.restore(saved.getStringArrayList(K_DUEL_QUEUE).orEmpty(), saved.getInt(K_DUEL_PLAYED))
    }

    fun restoreUi(saved: Bundle): CompareUiState {
        val f = saved.getBooleanArray(K_UI)?.takeIf { it.size == UI_FLAGS } ?: return CompareUiState()
        return CompareUiState(
            sync = f[UI_SYNC],
            stripHidden = f[UI_STRIP_HIDDEN],
            gridSpan = saved.getInt(K_GRID),
            selection = f[UI_SELECTION],
            infoOpen = f[UI_INFO],
            overlays = f[UI_OVERLAYS],
            viewA = view(saved.getFloatArray(K_VIEW_A)),
            viewB = view(saved.getFloatArray(K_VIEW_B)),
        )
    }

    private fun view(v: FloatArray?): RelativeView? =
        if (v != null && v.size == VIEW_FIELDS) RelativeView(v[0], v[1], v[2]) else null
}
