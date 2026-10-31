package org.fossify.gallery.compare

// Turnaj „vyber lepšiu z dvojice“ nad CompareSession (TournamentTest): víťaz ostáva v paneli A a ak bol
// označený, odznačí sa; porazená sa označí do Koša a nastúpi ďalšia NEoznačená fotka. Poradie súperov
// sa drží podľa ciest, takže mazanie počas turnaja nič neposunie.
class Tournament(private val session: CompareSession) {
    private val queue = ArrayDeque<String>()

    var active = false
        private set

    // odohrané zápasy; aktuálny zápas = played + 1, spolu = played + 1 + čakajúci
    var played = 0
        private set

    val total: Int get() = if (active) played + 1 + queue.size else played
    val waiting: List<String> get() = queue.toList()

    // false = na turnaj treba aspoň 2 neoznačené fotky; šampión na štart = ★, ak je medzi nimi
    fun start(): Boolean {
        val candidates = session.paths.filter { it !in session.marked }
        if (candidates.size < 2) return false
        val champion = session.best?.takeIf { it in candidates } ?: candidates.first()
        queue.clear()
        candidates.filterTo(queue) { it != champion }
        session.a = champion
        session.b = queue.removeFirst()
        session.userMoved = true
        played = 0
        active = true
        return true
    }

    // true = pokračuje ďalší zápas, false = turnaj skončil (víťaz ostáva v A)
    fun pick(winner: Pane): Boolean {
        if (!active) return false
        val win = session.pathOf(winner)
        val lose = session.pathOf(if (winner == Pane.A) Pane.B else Pane.A)
        session.marked.add(lose)
        session.marked.remove(win)
        session.a = win
        played++
        return advance()
    }

    fun stop() {
        active = false
        queue.clear()
    }

    // fotky zmizli (Kôš) — aj počas turnaja; vráti výsledok CompareSession.remove
    fun remove(gone: Collection<String>): Boolean {
        val champion = session.a
        val challenger = session.b
        val enough = session.remove(gone)
        if (!active) return enough
        queue.removeAll(gone.toSet())
        if (!enough) {
            stop()
            return false
        }
        val newChampion = listOf(champion, challenger).firstOrNull { it !in gone } ?: nextChallenger(null)
        if (newChampion == null) {
            stop()
            return true
        }
        session.a = newChampion
        val keep = challenger.takeIf { it !in gone && it != newChampion }
        if (keep != null) session.b = keep else advance()
        return true
    }

    // obnova po zabití procesu
    fun restore(waiting: List<String>, playedBefore: Int) {
        queue.clear()
        waiting.filterTo(queue) { it in session.paths }
        played = playedBefore
        active = session.a != session.b
    }

    private fun advance(): Boolean {
        val next = nextChallenger(session.a)
        if (next == null) {
            active = false
            return false
        }
        session.b = next
        return true
    }

    private fun nextChallenger(champion: String?): String? {
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (p in session.paths && p !in session.marked && p != champion) return p
        }
        return null
    }
}
