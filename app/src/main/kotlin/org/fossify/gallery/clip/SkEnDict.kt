package org.fossify.gallery.clip

import java.text.Normalizer

// Jednoduchý SK->EN preklad dopytu pre CLIP (model je anglický). Prekladá po slovách; neznáme slová
// nechá tak (CLIP niekedy chytí aj medzinárodné slová). Kľúče bez diakritiky a malými písmenami.
object SkEnDict {
    fun translate(query: String): String {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return query
        val out = words.map { w ->
            val key = strip(w)
            MAP[key] ?: w
        }
        return out.joinToString(" ")
    }

    private fun strip(s: String): String {
        val lower = s.lowercase().trim()
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    private val MARKS = Regex("\\p{Mn}+")

    private val MAP: Map<String, String> = mapOf(
        // zvieratá
        "pes" to "dog", "psy" to "dogs", "psa" to "dog", "mačka" to "cat", "macka" to "cat", "kocka" to "cat",
        "kon" to "horse", "kôň" to "horse", "krava" to "cow", "ovca" to "sheep", "prasa" to "pig",
        "vtak" to "bird", "vták" to "bird", "ryba" to "fish", "medved" to "bear", "medveď" to "bear",
        "srna" to "deer", "jelen" to "deer", "líška" to "fox", "liska" to "fox", "zajac" to "rabbit",
        "motyl" to "butterfly", "motýľ" to "butterfly", "vcela" to "bee", "včela" to "bee", "pavuk" to "spider",
        "had" to "snake", "korytnacka" to "turtle", "slon" to "elephant", "lev" to "lion", "tiger" to "tiger",
        "opica" to "monkey", "kura" to "chicken", "kacica" to "duck", "hus" to "goose",
        // príroda / miesta
        "plaz" to "beach", "pláž" to "beach", "more" to "sea", "ocean" to "ocean", "jazero" to "lake",
        "rieka" to "river", "potok" to "stream", "hory" to "mountains", "hora" to "mountain", "vrch" to "hill",
        "les" to "forest", "strom" to "tree", "stromy" to "trees", "kvet" to "flower", "kvety" to "flowers",
        "tráva" to "grass", "trava" to "grass", "pole" to "field", "lúka" to "meadow", "luka" to "meadow",
        "sneh" to "snow", "lad" to "ice", "ľad" to "ice", "dazd" to "rain", "dážď" to "rain",
        "obloha" to "sky", "oblaky" to "clouds", "mrak" to "cloud", "slnko" to "sun", "mesiac" to "moon",
        "hviezdy" to "stars", "zapad" to "sunset", "západ slnka" to "sunset", "vychod" to "sunrise",
        "vodopad" to "waterfall", "vodopád" to "waterfall", "jaskyna" to "cave", "pust" to "desert",
        "zahrada" to "garden", "záhrada" to "garden", "park" to "park", "cesta" to "road", "most" to "bridge",
        // mesto / budovy
        "mesto" to "city", "dedina" to "village", "dom" to "house", "budova" to "building", "byt" to "apartment",
        "kostol" to "church", "hrad" to "castle", "zamok" to "castle", "veza" to "tower", "veža" to "tower",
        "ulica" to "street", "namestie" to "square", "obchod" to "shop", "trh" to "market",
        "kancelaria" to "office", "skola" to "school", "škola" to "school", "nemocnica" to "hospital",
        // doprava
        "auto" to "car", "auta" to "cars", "vlak" to "train", "lietadlo" to "airplane", "lod" to "boat",
        "loď" to "boat", "bicykel" to "bicycle", "motorka" to "motorcycle", "autobus" to "bus", "kamion" to "truck",
        "traktor" to "tractor", "helikoptera" to "helicopter",
        // ľudia / telo
        "clovek" to "person", "človek" to "person", "ludia" to "people", "ľudia" to "people",
        "dieta" to "child", "dieťa" to "child", "deti" to "children", "muz" to "man", "muž" to "man",
        "zena" to "woman", "žena" to "woman", "tvar" to "face", "tvár" to "face", "usmev" to "smile",
        "svadba" to "wedding", "oslava" to "party", "narodeniny" to "birthday",
        // jedlo
        "jedlo" to "food", "chlieb" to "bread", "pizza" to "pizza", "kava" to "coffee", "káva" to "coffee",
        "caj" to "tea", "čaj" to "tea", "pivo" to "beer", "vino" to "wine", "víno" to "wine",
        "ovocie" to "fruit", "jablko" to "apple", "zelenina" to "vegetables", "torta" to "cake", "kolac" to "cake",
        "polievka" to "soup", "salat" to "salad", "maso" to "meat", "mäso" to "meat", "syr" to "cheese",
        // dokumenty / práca
        "dokument" to "document", "faktura" to "invoice document", "faktúra" to "invoice document",
        "papier" to "paper document", "text" to "text document", "tabulka" to "table spreadsheet",
        "graf" to "chart", "podpis" to "signature", "peciatka" to "stamp", "pečiatka" to "stamp",
        "zmluva" to "contract document", "ucet" to "receipt", "účet" to "receipt", "blocek" to "receipt",
        "kniha" to "book", "noviny" to "newspaper", "mapa" to "map", "obrazovka" to "screen",
        "pocitac" to "computer", "počítač" to "computer", "telefon" to "phone", "telefón" to "phone",
        // predmety
        "kniha" to "book", "hodinky" to "watch", "okuliare" to "glasses", "topanky" to "shoes",
        "oblecenie" to "clothes", "oblečenie" to "clothes", "taska" to "bag", "taška" to "bag",
        "stol" to "table", "stôl" to "table", "stolicka" to "chair", "postel" to "bed", "posteľ" to "bed",
        "lampa" to "lamp", "zrkadlo" to "mirror", "dvere" to "door", "okno" to "window", "kluc" to "key",
        "kľúč" to "key", "hracka" to "toy", "hračka" to "toy", "lopta" to "ball",
        // aktivity / šport
        "futbal" to "football", "hokej" to "hockey", "beh" to "running", "turistika" to "hiking",
        "lyzovanie" to "skiing", "lyžovanie" to "skiing", "plavanie" to "swimming", "tanec" to "dancing",
        "koncert" to "concert", "dovolenka" to "vacation", "vylet" to "trip", "výlet" to "trip",
    )
}
