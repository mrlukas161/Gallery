package org.fossify.gallery.clip

import java.text.Normalizer

// SK->EN preklad dopytu pre CLIP (model je anglický). Greedy najdlhšia zhoda: skúsi frázy dĺžky 3, 2,
// potom jedno slovo; neznáme slová nechá tak (CLIP niekedy chytí aj medzinárodné slová).
// Kľúče sú VŽDY bez diakritiky a malými písmenami (vstup sa rovnako normalizuje cez strip()).
object SkEnDict {
    fun translate(query: String): String {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return query
        val keys = words.map { strip(it) }
        val out = StringBuilder()
        var i = 0
        while (i < words.size) {
            var matched = false
            // frázy: najprv 3-slovné, potom 2-slovné (greedy longest)
            var span = minOf(3, words.size - i)
            while (span >= 2) {
                val phrase = keys.subList(i, i + span).joinToString(" ")
                val tr = MAP[phrase]
                if (tr != null) {
                    out.append(tr).append(' ')
                    i += span
                    matched = true
                    break
                }
                span--
            }
            if (matched) continue
            // jedno slovo (preklad alebo pôvodné slovo)
            out.append(MAP[keys[i]] ?: words[i]).append(' ')
            i++
        }
        return out.toString().trim()
    }

    private fun strip(s: String): String {
        val lower = s.lowercase().trim()
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    private val MARKS = Regex("\\p{Mn}+")

    // Kľúče bez diakritiky, malé písmená. Viacslovné kľúče = frázy (matchujú sa pred jednotlivými slovami).
    private val MAP: Map<String, String> = mapOf(
        // --- FRÁZY (viacslovné, majú prednosť) ---
        "zapad slnka" to "sunset", "zapad slnko" to "sunset", "vychod slnka" to "sunrise",
        "nocna obloha" to "night sky", "hviezdna obloha" to "starry sky", "modra obloha" to "blue sky",
        "morske pobrezie" to "beach coast", "piesocna plaz" to "sandy beach", "morska hladina" to "sea",
        "zasnezene hory" to "snowy mountains", "stary dom" to "old house", "stara budova" to "old building",
        "male dieta" to "baby", "novorodenec" to "newborn baby", "rodinna fotka" to "family photo",
        "skupina ludi" to "group of people", "skupinova fotka" to "group photo", "portret cloveka" to "portrait of a person",
        "svadobna fotka" to "wedding photo", "vianocny stromcek" to "christmas tree",
        "vianocne trhy" to "christmas market", "ohnostroj" to "fireworks", "ohnova show" to "fireworks",
        "sportove auto" to "sports car", "stare auto" to "vintage car", "horsky bicykel" to "mountain bike",
        "kvitnuce kvety" to "blooming flowers", "jesenne listie" to "autumn leaves", "padajuci sneh" to "falling snow",
        "obcianski preukaz" to "id card document", "vodicsky preukaz" to "driver license card",
        "rodny list" to "birth certificate document", "ucet blocek" to "receipt", "listok listok" to "ticket",
        "menu jedalny listok" to "restaurant menu", "obrazovka pocitaca" to "computer screen",
        "kod ciarovy" to "barcode", "qr kod" to "qr code", "spz auta" to "license plate",
        "hracie ihrisko" to "playground", "detske ihrisko" to "playground",
        "plavecky bazen" to "swimming pool", "lyziarske stredisko" to "ski resort",

        // --- zvieratá ---
        "pes" to "dog", "psy" to "dogs", "psa" to "dog", "psik" to "puppy", "stena" to "puppy",
        "macka" to "cat", "kocka" to "cat", "macatko" to "kitten", "kon" to "horse", "kone" to "horses",
        "krava" to "cow", "ovca" to "sheep", "prasa" to "pig", "koza" to "goat",
        "vtak" to "bird", "vtaky" to "birds", "ryba" to "fish", "ryby" to "fish", "medved" to "bear",
        "srna" to "deer", "jelen" to "deer", "liska" to "fox", "zajac" to "rabbit", "veverica" to "squirrel",
        "motyl" to "butterfly", "vcela" to "bee", "pavuk" to "spider", "mravec" to "ant",
        "had" to "snake", "jaster" to "lizard", "korytnacka" to "turtle", "zaba" to "frog",
        "slon" to "elephant", "lev" to "lion", "tiger" to "tiger", "zirafa" to "giraffe", "zebra" to "zebra",
        "opica" to "monkey", "kura" to "chicken", "sliepka" to "hen", "kohut" to "rooster",
        "kacica" to "duck", "hus" to "goose", "delfin" to "dolphin", "velryba" to "whale", "zralok" to "shark",

        // --- príroda / krajina ---
        "plaz" to "beach", "more" to "sea", "ocean" to "ocean", "jazero" to "lake", "voda" to "water",
        "rieka" to "river", "potok" to "stream", "hory" to "mountains", "hora" to "mountain", "vrch" to "hill",
        "kopec" to "hill", "les" to "forest", "hora les" to "forest", "strom" to "tree", "stromy" to "trees",
        "kvet" to "flower", "kvety" to "flowers", "ruza" to "rose", "tulipan" to "tulip",
        "trava" to "grass", "pole" to "field", "luka" to "meadow", "krajina" to "landscape", "priroda" to "nature",
        "sneh" to "snow", "lad" to "ice", "dazd" to "rain", "hmla" to "fog", "duha" to "rainbow", "blesk" to "lightning",
        "obloha" to "sky", "oblaky" to "clouds", "mrak" to "cloud", "slnko" to "sun", "mesiac" to "moon",
        "hviezdy" to "stars", "hviezda" to "star", "zapad" to "sunset", "vychod" to "sunrise",
        "vodopad" to "waterfall", "jaskyna" to "cave", "pust" to "desert", "sopka" to "volcano",
        "zahrada" to "garden", "park" to "park", "cesta" to "road", "chodnik" to "path", "most" to "bridge",
        "skala" to "rock", "utes" to "cliff", "ostrov" to "island", "dolina" to "valley",

        // --- ročné obdobia / počasie ---
        "jar" to "spring", "leto" to "summer", "jesen" to "autumn", "zima" to "winter",
        "vecer" to "evening", "noc" to "night", "rano" to "morning", "den" to "day", "sumrak" to "dusk",

        // --- mesto / budovy ---
        "mesto" to "city", "dedina" to "village", "dom" to "house", "budova" to "building", "byt" to "apartment",
        "chata" to "cabin", "kostol" to "church", "hrad" to "castle", "zamok" to "castle", "veza" to "tower",
        "ulica" to "street", "namestie" to "square", "obchod" to "shop", "trh" to "market", "nakupne centrum" to "shopping mall",
        "kancelaria" to "office", "skola" to "school", "nemocnica" to "hospital", "hotel" to "hotel",
        "restauracia" to "restaurant", "kaviaren" to "cafe", "letisko" to "airport", "stanica" to "station",
        "muzeum" to "museum", "kniznica" to "library", "kupalisko" to "swimming pool", "stadion" to "stadium",
        "fontana" to "fountain", "socha" to "statue", "pamiatka" to "monument",

        // --- doprava ---
        "auto" to "car", "auta" to "cars", "vlak" to "train", "lietadlo" to "airplane", "lod" to "boat",
        "clnok" to "boat", "bicykel" to "bicycle", "motorka" to "motorcycle", "autobus" to "bus",
        "kamion" to "truck", "nakladiak" to "truck", "traktor" to "tractor", "helikoptera" to "helicopter",
        "vrtulnik" to "helicopter", "elektricka" to "tram", "metro" to "subway", "taxi" to "taxi",

        // --- ľudia / telo / udalosti ---
        "clovek" to "person", "ludia" to "people", "dieta" to "child", "deti" to "children",
        "babatko" to "baby", "chlapec" to "boy", "dievca" to "girl", "muz" to "man", "chlap" to "man",
        "zena" to "woman", "babka" to "grandmother", "dedko" to "grandfather", "rodina" to "family",
        "tvar" to "face", "usmev" to "smile", "ruka" to "hand", "oko" to "eye", "vlasy" to "hair",
        "svadba" to "wedding", "oslava" to "party", "narodeniny" to "birthday", "vianoce" to "christmas",
        "velka noc" to "easter", "silvester" to "new year party", "promocia" to "graduation",
        "koncert" to "concert", "festival" to "festival", "pohreb" to "funeral",

        // --- jedlo / nápoje ---
        "jedlo" to "food", "chlieb" to "bread", "pizza" to "pizza", "hamburger" to "burger",
        "kava" to "coffee", "caj" to "tea", "pivo" to "beer", "vino" to "wine", "voda napoj" to "water drink",
        "ovocie" to "fruit", "jablko" to "apple", "banan" to "banana", "pomaranc" to "orange",
        "zelenina" to "vegetables", "torta" to "cake", "kolac" to "cake", "zakusok" to "dessert",
        "zmrzlina" to "ice cream", "polievka" to "soup", "salat" to "salad", "maso" to "meat",
        "syr" to "cheese", "vajce" to "egg", "cestoviny" to "pasta", "ryza" to "rice", "cokolada" to "chocolate",

        // --- dokumenty / práca ---
        "dokument" to "document", "faktura" to "invoice document", "papier" to "paper document",
        "text" to "text document", "tabulka" to "table spreadsheet", "graf" to "chart",
        "podpis" to "signature", "peciatka" to "stamp", "zmluva" to "contract document",
        "ucet" to "receipt", "blocek" to "receipt", "listok" to "ticket", "kniha" to "book",
        "noviny" to "newspaper", "casopis" to "magazine", "mapa" to "map", "obrazovka" to "screen",
        "pocitac" to "computer", "notebook" to "laptop", "telefon" to "phone", "tablet" to "tablet",
        "klavesnica" to "keyboard", "formular" to "form document", "certifikat" to "certificate document",
        "diplom" to "diploma document", "vysvedcenie" to "school report document",

        // --- predmety ---
        "hodinky" to "watch", "okuliare" to "glasses", "topanky" to "shoes", "oblecenie" to "clothes",
        "taska" to "bag", "kufor" to "suitcase", "stol" to "table", "stolicka" to "chair",
        "postel" to "bed", "gauc" to "sofa", "lampa" to "lamp", "zrkadlo" to "mirror",
        "dvere" to "door", "okno" to "window", "kluc" to "key", "hracka" to "toy", "lopta" to "ball",
        "kytica" to "bouquet", "svieca" to "candle", "darcek" to "gift", "balon" to "balloon",
        "foto" to "photo", "obraz" to "painting", "socha predmet" to "sculpture", "gitara" to "guitar",
        "klavir" to "piano", "husle" to "violin", "kamera" to "camera", "fotoaparat" to "camera",

        // --- aktivity / šport ---
        "futbal" to "football", "hokej" to "hockey", "basketbal" to "basketball", "tenis" to "tennis",
        "beh" to "running", "turistika" to "hiking", "lyzovanie" to "skiing", "snowboard" to "snowboarding",
        "korculovanie" to "skating", "plavanie" to "swimming", "tanec" to "dancing", "joga" to "yoga",
        "bicyklovanie" to "cycling", "rybarcenie" to "fishing", "kempovanie" to "camping",
        "dovolenka" to "vacation", "vylet" to "trip", "cestovanie" to "travel", "grilovacka" to "barbecue",

        // --- farby (užitočné pre CLIP: „cerveny dom") ---
        "cerveny" to "red", "cervena" to "red", "modry" to "blue", "modra" to "blue",
        "zeleny" to "green", "zelena" to "green", "zlty" to "yellow", "zlta" to "yellow",
        "biely" to "white", "biela" to "white", "cierny" to "black", "cierna" to "black",
        "oranzovy" to "orange", "ruzovy" to "pink", "ruzova" to "pink", "fialovy" to "purple",
        "hnedy" to "brown", "sedy" to "gray", "zlaty" to "golden", "strieborny" to "silver",
    )
}
