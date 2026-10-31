package org.fossify.gallery.helpers

// Hromadné „Ponechať najlepšiu“ (zoznam „Podobné a duplikáty“ v mobile, web cez /api/groups a /api/quality):
// ktorá fotka v skupine ostane, čo pôjde do Koša a ktoré skupiny sa preskočia ako neisté.
// Čistá logika bez Androidu (CompareBulkTest).
//
// ZÁRUKY (invariant, overuje CompareBulkTest aj náhodným testom):
//  - žiadna skupina nepríde o všetky fotky — ani rozhodnutá, ani iná, ktorej sa mazanie dotkne (prekryv
//    sérií a duplikátov),
//  - ★ (ponechaná fotka) rozhodnutej skupiny sa nezmaže nikdy, ani keď ju iná skupina navrhuje na zmazanie,
//  - fotka bez skóre (nedala sa prečítať alebo zmerať) sa nemaže,
//  - neistá skupina (malý náskok ★ pred druhou, tváre sa nehodnotili) sa celá preskočí.
object CompareBulk {
    // ★ musí mať aspoň taký náskok skóre (0–1) pred druhou najlepšou, inak je skupina neistá
    const val MIN_MARGIN = 0.05

    // tváre sa nehodnotili (model obsadený spracovaním knižnice alebo nedostupný): ★ mohla vyhrať len
    // ostrosťou a pritom mať zatvorené oči — treba väčší náskok
    const val MIN_MARGIN_NO_FACES = 0.15

    // jedno kolo v mobile: viac fotiek naraz sa nevyhodnocuje (čas, HeavyGate)
    const val MAX_PHOTOS_PER_RUN = 1500

    enum class Skip { UNCERTAIN, NOT_MEASURED }

    // best = ★, second = druhá najlepšia, confidence = náskok skóre ★ pred druhou (0–1; záporný = ★ vyhrala
    // len rozhodnutím pri zhode), facesChecked = tváre sa hodnotili na všetkých fotkách skupiny
    data class Verdict(
        val best: String?,
        val second: String?,
        val confidence: Double,
        val uncertain: Boolean,
        val facesChecked: Boolean,
    )

    // druhá = najvyššie skóre spomedzi ostatných (pri zhode môže mať vyššie skóre než ★)
    fun verdict(scores: Map<String, PhotoScore>): Verdict {
        val best = scores.entries.minByOrNull { it.value.rank }
        val second = scores.entries.filter { it !== best }.maxByOrNull { it.value.score }
        val facesChecked = scores.isNotEmpty() && scores.values.all { it.facesChecked }
        if (best == null || second == null) {
            // jedna zmeraná fotka nemá s čím porovnať
            return Verdict(best?.key, null, 0.0, uncertain = true, facesChecked = facesChecked)
        }
        val margin = best.value.score - second.value.score
        val needed = if (facesChecked) MIN_MARGIN else MIN_MARGIN_NO_FACES
        return Verdict(best.key, second.key, margin, uncertain = margin < needed, facesChecked = facesChecked)
    }

    // presné kópie: jednoznačné pravidlo bez analýzy tvárí — vyššie rozlíšenie, potom Fotoaparát/DCIM pred
    // ostatnými a tie pred WhatsApp/Download, potom starší súbor (originál); rovnako ako CompareScoring.prefer
    fun exactKeeper(photos: List<PhotoMeasure>): String? =
        photos.minWithOrNull { a, b -> CompareScoring.prefer(a, 0.0, b, 0.0) }?.path

    // Rozhodnutie pre jednu skupinu. paths = celá skupina, keep = fotka, ktorá ostane (null = skupina sa
    // preskočí), removable = fotky, ktoré sa smú zmazať (keep a cudzie cesty sa vyradia).
    class Decision(val paths: List<String>, keep: String?, removable: Collection<String>, val skip: Skip? = null) {
        val keep: String? = keep?.takeIf { it in paths }
        val removable: List<String> = if (this.keep == null) {
            emptyList()
        } else {
            val inGroup = paths.toSet()
            removable.distinct().filter { it != this.keep && it in inGroup }
        }
    }

    // séria / podobné: ★ podľa skóre, zmazať sa smú len zmerané fotky (rank > 0); neistá skupina sa preskočí
    fun qualityDecision(paths: List<String>, scores: Map<String, PhotoScore>): Decision {
        val v = verdict(scores)
        if (v.best == null || v.uncertain) return Decision(paths, null, emptyList(), Skip.UNCERTAIN)
        val removable = paths.filter { p -> (scores[p]?.rank ?: 0) > 0 }
        return Decision(paths, v.best, removable)
    }

    // presné kópie: photos = fotky skupiny, o ktorých vieme rozlíšenie, priečinok a vek (ostatné sa nemažú)
    fun exactDecision(paths: List<String>, photos: List<PhotoMeasure>): Decision {
        val known = photos.filter { it.path in paths }
        val keep = exactKeeper(known) ?: return Decision(paths, null, emptyList(), Skip.NOT_MEASURED)
        if (known.size < 2) return Decision(paths, null, emptyList(), Skip.NOT_MEASURED)
        return Decision(paths, keep, known.map { it.path })
    }

    // delete = v poradí rozhodnutí; affected = rozhodnuté skupiny, z ktorých niečo pôjde preč;
    // spared = fotky ponechané navyše, aby iná skupina neprišla o všetko
    class Plan(
        val delete: List<String>,
        val keeps: Set<String>,
        val affected: Int,
        val uncertain: Int,
        val notMeasured: Int,
        val spared: Set<String>,
    ) {
        val isEmpty: Boolean get() = delete.isEmpty()
    }

    // otherGroups = ostatné skupiny zoznamu (prvá cesta = tá, ktorá sa ponechá, keby skupina mala prísť
    // o všetky fotky — napr. CompareGroup.keeper); rozhodnuté skupiny tu môžu byť znova, nevadí to
    fun plan(decisions: List<Decision>, otherGroups: List<List<String>> = emptyList()): Plan {
        val keeps = decisions.mapNotNullTo(LinkedHashSet()) { it.keep }
        val delete = LinkedHashSet<String>()
        for (d in decisions) {
            if (d.keep != null) d.removable.filterTo(delete) { it !in keeps }
        }
        // jedno prejdenie stačí: vrátenie fotky zo zoznamu na zmazanie žiadnej inej skupine neublíži
        val spared = LinkedHashSet<String>()
        for (g in decisions.map { listOfNotNull(it.keep) + it.paths } + otherGroups) {
            if (g.isNotEmpty() && g.all { it in delete }) {
                val save = g.first()
                delete.remove(save)
                spared.add(save)
            }
        }
        return Plan(
            delete = delete.toList(),
            keeps = keeps,
            affected = decisions.count { d -> d.keep != null && d.removable.any { it in delete } },
            uncertain = decisions.count { it.skip == Skip.UNCERTAIN },
            notMeasured = decisions.count { it.skip == Skip.NOT_MEASURED },
            spared = spared,
        )
    }

    // poradie pre plan(): fotka, ktorá ostane, keby skupina mala prísť o všetko (titulka dlaždice), je prvá
    fun keeperFirst(paths: List<String>, keeper: String): List<String> =
        if (keeper in paths) listOf(keeper) + paths.filter { it != keeper } else paths

    // koľko skupín zo začiatku zoznamu sa zmestí do jedného kola (fotky vo viacerých skupinách sa rátajú raz);
    // aspoň prvá skupina, ak sa zmestí sama
    fun fitRun(groups: List<List<String>>, maxPhotos: Int = MAX_PHOTOS_PER_RUN): Int {
        val seen = HashSet<String>()
        var n = 0
        for (g in groups) {
            val added = g.count { it !in seen }
            if (seen.size + added > maxPhotos) break
            seen.addAll(g)
            n++
        }
        return n
    }
}
