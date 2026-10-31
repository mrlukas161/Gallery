package org.fossify.gallery.helpers

// Model zoznamu „Podobné a duplikáty“ (skupiny, poradie, skryté skupiny, ďalšia skupina) — čistá logika
// bez Androidu (CompareGroupsTest). Skupiny skladá CompareRepository, zobrazuje CompareListActivity,
// tie isté čísla ukazuje karta „Na upratanie“ (HomeStats).

enum class CompareKind { BURST, EXACT, SIMILAR }

// paths = poradie pre porovnávač (séria chronologicky, duplikáty od najväčšieho rozlíšenia), keeper = fotka,
// ktorá by ostala (titulka dlaždice), reclaimableBytes = súčet veľkostí okrem nej, newestMs = najnovšia fotka
class CompareGroup(
    val kind: CompareKind,
    val paths: List<String>,
    val keeper: String,
    val reclaimableBytes: Long,
    val newestMs: Long,
) {
    val key: String = CompareGroups.keyOf(paths)
}

object CompareGroups {
    // záložky zoznamu (poradie = pozícia záložky)
    const val TAB_BURSTS = 0
    const val TAB_EXACT = 1
    const val TAB_SIMILAR = 2
    const val TAB_COUNT = 3

    enum class Sort { NEWEST, SPACE, LARGEST }

    // údaje o fotke z MediaStore: dateMs = dátum nasnímania, inak zmeny súboru
    data class Info(val path: String, val sizeBytes: Long, val width: Int, val height: Int, val dateMs: Long)

    fun tabOf(kind: CompareKind): Int = when (kind) {
        CompareKind.BURST -> TAB_BURSTS
        CompareKind.EXACT -> TAB_EXACT
        CompareKind.SIMILAR -> TAB_SIMILAR
    }

    fun keyOf(paths: Collection<String>): String = paths.sorted().joinToString("\n")

    // Fotka, ktorá ostane, kým nie je skóre kvality: najväčšie rozlíšenie, potom väčší súbor, potom novšia.
    fun keeperOf(infos: List<Info>): Info = infos.sortedWith(KEEPER_ORDER).first()

    // null = menej než 2 fotky, ktoré ešte existujú (info == null)
    fun build(kind: CompareKind, paths: List<String>, info: (String) -> Info?): CompareGroup? {
        val infos = paths.mapNotNull(info)
        if (infos.size < 2) return null
        val keeper = keeperOf(infos)
        val ordered = if (kind == CompareKind.BURST) infos else infos.sortedWith(KEEPER_ORDER)
        return CompareGroup(
            kind = kind,
            paths = ordered.map { it.path },
            keeper = keeper.path,
            reclaimableBytes = infos.sumOf { it.sizeBytes } - keeper.sizeBytes,
            newestMs = infos.maxOf { it.dateMs },
        )
    }

    fun sort(groups: List<CompareGroup>, sort: Sort): List<CompareGroup> = when (sort) {
        Sort.NEWEST -> groups.sortedWith(compareByDescending<CompareGroup> { it.newestMs }.thenBy { it.key })
        Sort.SPACE -> groups.sortedWith(compareByDescending<CompareGroup> { it.reclaimableBytes }.thenBy { it.key })
        Sort.LARGEST -> groups.sortedWith(
            compareByDescending<CompareGroup> { it.paths.size }.thenByDescending { it.newestMs }.thenBy { it.key },
        )
    }

    // Koľko sa dá uvoľniť naprieč skupinami: každá fotka, ktorá v niektorej skupine nie je tá ponechaná,
    // sa počíta raz (séria a duplikáty sa môžu prekrývať).
    fun reclaimableTotal(groups: List<CompareGroup>, sizeOf: (String) -> Long): Long {
        val removable = HashSet<String>()
        for (g in groups) g.paths.filterTo(removable) { it != g.keeper }
        return removable.sumOf(sizeOf)
    }

    // záložka, ktorú otvorí karta Domova: tá s najviac skupinami (pri zhode presné kópie, podobné, série)
    fun preferredTab(groups: List<CompareGroup>): Int {
        val counts = IntArray(TAB_COUNT)
        groups.forEach { counts[tabOf(it.kind)]++ }
        return listOf(TAB_EXACT, TAB_SIMILAR, TAB_BURSTS).maxByOrNull { counts[it] } ?: TAB_EXACT
    }

    // Ďalšia skupina po návrate z porovnávača: prvá skupina z pôvodného poradia za otvorenou (queue),
    // ktorá v aktuálnom zozname ešte je. Otvorená skupina medzitým mohla zmiznúť (zmazané fotky).
    fun nextGroup(queue: List<String>, current: List<CompareGroup>): CompareGroup? {
        val byKey = current.associateBy { it.key }
        return queue.firstNotNullOfOrNull { byKey[it] }
    }

    private val KEEPER_ORDER = compareByDescending<Info> { it.width.toLong() * it.height }
        .thenByDescending { it.sizeBytes }
        .thenByDescending { it.dateMs }
        .thenBy { it.path }
}

// Skupiny, ktoré používateľ označil „Ponechať všetky“ / „Nie sú to duplikáty“. Uložená je množina ciest
// a skupina je skrytá, keď sú VŠETKY jej fotky v jednej uloženej množine: po zmazaní niektorej z nich
// ostane skrytá, pribudnutím novej podobnej fotky sa ukáže znova (je tam niečo nové).
class HiddenGroups(sets: List<Set<String>>) {
    val sets: List<Set<String>> = sets.filter { it.size >= 2 }
    private val byPath = HashMap<String, MutableList<Set<String>>>()

    init {
        for (set in this.sets) set.forEach { byPath.getOrPut(it) { ArrayList() }.add(set) }
    }

    val isEmpty: Boolean get() = sets.isEmpty()

    fun isHidden(paths: Collection<String>): Boolean {
        val first = paths.firstOrNull() ?: return false
        return byPath[first]?.any { set -> paths.all { it in set } } == true
    }

    fun plus(groups: Collection<Collection<String>>): HiddenGroups =
        HiddenGroups(sets + groups.map { it.toSet() }.filter { it.size >= 2 && !isHidden(it) })

    // vráti skupiny: zmaže každú uloženú množinu, ktorá niektorú z nich skrývala
    fun minus(groups: Collection<Collection<String>>): HiddenGroups =
        HiddenGroups(sets.filter { set -> groups.none { g -> g.isNotEmpty() && g.all { it in set } } })
}
