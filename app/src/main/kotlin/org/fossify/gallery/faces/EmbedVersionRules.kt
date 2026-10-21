package org.fossify.gallery.faces

// Pravidlá prechodu odtlačkov tvárí v1 → v2 bez Android tried (JUnit testy). Stav drží FaceEmbedVersion,
// prepočet robí FaceReembedV2.
//
// Zásada: rozpoznávanie NIKDY neporovnáva vektory rôznych verzií. Preto sa v2 vektory počas prepočtu
// odkladajú bokom (faces_v2.db) a do faces.db sa zapíšu naraz v jednej transakcii („prepnutie").
// Do prepnutia všetko porovnáva v1 s v1, po ňom v2 s v2.
object EmbedVersionRules {
    const val MODEL_ID_V1 = "mobilefacenet-112-l2-v1"
    const val MODEL_ID_V2 = "mobilefacenet-112-l2-v2"

    // stav odložených tvárí vo faces_v2.db
    const val STATE_DONE = 1      // v2 vektor je pripravený
    const val STATE_MISSING = 2   // súbor fotky neexistuje (zmazaná/presunutá fotka)
    const val STATE_FAILED = 3    // súbor existuje, ale výpočet zlyhal

    // po toľkých neúspešných pokusoch (v rôznych behoch) sa tvár pri prepnutí vzdá
    const val MAX_TRIES = 3

    // keď chýba viac ako polovica fotiek, je to skôr odobraté povolenie alebo nepripojená karta než
    // zmazané fotky — prepnutie počká
    const val MAX_MISSING_FRACTION = 0.5f

    // vektory dvoch verzií sa nesmú porovnávať (ani pri prenose medzi telefónmi)
    fun compatible(modelA: String?, modelB: String?): Boolean = modelA != null && modelA == modelB

    // Vzor (anchor_embeddings) vznikol pred prepnutím = je vo v1 priestore a po prepnutí sa nepoužíva.
    // Do prepnutia sa nový import z Picasy nespustí a rodinné vzory sa prijímajú len vo v1 (MODEL_ID),
    // takže hranica času prepnutia oddeľuje verzie presne.
    fun isAnchorV1(createdAt: Long, switchedAt: Long): Boolean = createdAt < switchedAt

    // Import z Picasy pred prepnutím vytvoril v1 vzory — taký manifest sa nepočíta ako „už naimportované".
    fun manifestStillValid(importedAt: Long, switchedAt: Long?): Boolean =
        switchedAt == null || importedAt >= switchedAt

    // Skúsiť tvár v tomto behu? Bez záznamu raz, chýbajúci súbor raz za beh (relinker ho mohol nájsť),
    // zlyhaný výpočet najviac MAX_TRIES-krát spolu; hotová nikdy.
    fun needsAttempt(state: Int?, tries: Int, attemptsThisRun: Int): Boolean = when (state) {
        null, STATE_MISSING -> attemptsThisRun == 0
        STATE_DONE -> false
        else -> tries < MAX_TRIES && attemptsThisRun < MAX_TRIES
    }

    class Tally(
        val total: Int,
        val done: Int,
        val missing: Int,
        val failedExhausted: Int,
        val transientMissing: Int,
    )

    // Smie sa prepnúť? Každá tvár musí mať v2 vektor alebo byť natrvalo neprepočítateľná; nepripojené
    // úložisko alebo podozrivo veľa chýbajúcich súborov prepnutie odloží.
    fun readyToSwitch(t: Tally): Boolean {
        if (t.transientMissing > 0) return false
        if (t.done + t.missing + t.failedExhausted < t.total) return false
        if (t.total > 0 && t.missing > t.total * MAX_MISSING_FRACTION) return false
        return true
    }

    // Koreň úložiska cesty (/storage/emulated/0, /storage/XXXX-XXXX). Keď koreň chýba, karta nie je
    // pripojená a chýbajúce súbory sú len dočasne nedostupné.
    @Suppress("MagicNumber") // počty a poradie úsekov cesty
    fun volumeRoot(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return when {
            parts[0] == "storage" && parts.size >= 3 && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
            parts[0] == "storage" && parts.size >= 2 -> "/storage/${parts[1]}"
            parts[0] == "sdcard" -> "/sdcard"
            parts[0] == "mnt" && parts.size >= 3 -> "/mnt/${parts[1]}/${parts[2]}"
            else -> "/${parts[0]}"
        }
    }
}
