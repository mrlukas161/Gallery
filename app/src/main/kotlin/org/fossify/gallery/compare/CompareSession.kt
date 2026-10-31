package org.fossify.gallery.compare

// Stav obrazovky porovnania bez Android tried (CompareSessionTest): fotky skupiny, značky „do Koša“
// a ktorá fotka je v paneli A a B. Všetko sa drží podľa CESTY, nie podľa pozície — mazanie, vrátenie
// z Koša ani turnaj tak neposunú značky na iné fotky.
enum class Pane { A, B }

class CompareSession(initial: List<String>) {
    val paths: MutableList<String> = ArrayList(initial.distinct())
    val marked = LinkedHashSet<String>()

    var a: String = paths.getOrElse(0) { "" }
    var b: String = paths.getOrElse(1) { a }

    // používateľ už sám prepol fotky v paneloch → po analýze sa ★ nepresúva
    var userMoved = false
    var best: String? = null

    val size: Int get() = paths.size

    fun pathOf(pane: Pane): String = if (pane == Pane.A) a else b

    fun toggleMark(path: String): Boolean {
        if (!marked.remove(path)) marked.add(path)
        return path in marked
    }

    // „Ponechať len túto“: všetky ostatné do Koša, táto sa odznačí
    fun keepOnly(path: String) {
        if (path !in paths) return
        marked.clear()
        paths.filterTo(marked) { it != path }
    }

    // ťuk v páse: fotka do panela A, doterajšia A do panela B (ťuk na B ich vymení)
    fun tap(path: String) {
        if (path !in paths || path == a) return
        b = a
        a = path
        userMoved = true
    }

    // šípka v paneli: ďalšia/predošlá fotka zaradom; fotku z druhého panela preskočí
    fun step(pane: Pane, dir: Int): Boolean {
        val other = pathOf(if (pane == Pane.A) Pane.B else Pane.A)
        var i = paths.indexOf(pathOf(pane))
        do {
            i += dir
        } while (i in paths.indices && paths[i] == other)
        if (i !in paths.indices) return false
        if (pane == Pane.A) a = paths[i] else b = paths[i]
        userMoved = true
        return true
    }

    // po analýze: ★ do A, druhá najlepšia do B — len kým používateľ nič neprepol
    fun placeBest(bestPath: String?, second: String?): Boolean {
        best = bestPath
        if (userMoved || bestPath == null || bestPath !in paths) return false
        val other = second?.takeIf { it in paths && it != bestPath }
            ?: paths.firstOrNull { it != bestPath }
            ?: return false
        if (a == bestPath && b == other) return false
        a = bestPath
        b = other
        return true
    }

    // fotky zmizli (Kôš); false = ostalo menej ako 2 a porovnávať sa už nedá
    fun remove(removed: Collection<String>): Boolean {
        val gone = removed.toSet()
        val oldA = paths.indexOf(a)
        val oldB = paths.indexOf(b)
        paths.removeAll(gone)
        marked.removeAll(gone)
        if (best in gone) best = null
        if (paths.size < 2) return false
        if (a in gone) a = neighbour(oldA, b)
        if (b in gone || b == a) b = neighbour(oldB, a)
        return true
    }

    // vrátenie z Koša: fotky späť na pôvodné miesta (index z času mazania)
    fun restore(items: List<Pair<String, Int>>) {
        for ((path, index) in items.sortedBy { it.second }) {
            if (path in paths) continue
            paths.add(index.coerceIn(0, paths.size), path)
        }
        if (b == a) b = neighbour(paths.indexOf(a), a)
    }

    private fun neighbour(oldIndex: Int, avoid: String): String {
        val start = oldIndex.coerceIn(0, paths.lastIndex)
        val order = (start until paths.size) + (start - 1 downTo 0)
        return order.map { paths[it] }.firstOrNull { it != avoid } ?: paths[start]
    }
}
