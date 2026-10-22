package org.fossify.gallery.family

// Prenos fotky s balíkom cez frontu (outbox) a /f/bundle (F4). Čistá logika bez Androidu (BundleTransferTest).
//
// Súbor sa NIKDY nenačíta do pamäte celý (videá majú gigabajty): pri pridaní do fronty vznikne
// N položiek KIND_CHUNK (každá najviac CHUNK_SIZE bajtov, číta sa priamo zo súboru až pri odoslaní)
// a za nimi jedna položka KIND_PHOTO s balíkom výsledkov. Fronta sa posiela v poradí id, takže kúsky
// idú pred balíkom. Prijímateľ kúsky zapisuje rovno na disk (na ich miesto podľa indexu) a fotku
// uloží až s balíkom, keď má všetky kúsky. Každý kúsok je samostatne overený (AES-GCM rámec F2),
// prerušený prenos pokračuje od prvého nepotvrdeného kúska. KIND_RESULTS = len balík bez súboru
// (fotka odišla inak, napr. cez WhatsApp alebo Quick Share).
//
// refKey položky fronty sa posiela druhej strane (FamilyClient → meta „ref“), preto v ňom NIE JE cesta
// k súboru: tú pozná len odosielateľ (meta tabuľka family.db, kľúč pathKey(transferId)).
object BundleTransfer {
    const val KIND_CHUNK = "f4.chunk"
    const val KIND_PHOTO = "f4.photo"
    const val KIND_RESULTS = "f4.results"

    // 4 MB: požiadavka sa na serveri číta celá do pamäte (FamilyServer.readBody) a šifruje sa celá,
    // takže na jeden kúsok treba ~3× toľko RAM. Limit tela požiadavky je 8 MB.
    const val CHUNK_SIZE = 4 * 1024 * 1024
    const val MAX_CHUNK_SIZE = 8 * 1024 * 1024

    // najväčší prenášaný súbor (dlhé video); väčší sa do fronty nepridá
    const val MAX_FILE_SIZE = 8L * 1024 * 1024 * 1024

    private const val META_PREFIX = "f4.path."
    private val TRANSFER_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private const val T = "t"
    private const val I = "i"
    private const val N = "n"
    private const val C = "c"
    private const val S = "s"
    private const val M = "m"

    // index = poradie kúska (pri balíku -1), count = počet kúskov súboru (0 = bez súboru),
    // size a mtime = súbor pri pridaní do fronty (zmenený súbor sa neodošle)
    data class Ref(
        val transferId: String,
        val index: Int,
        val count: Int,
        val chunkSize: Int,
        val size: Long,
        val mtime: Long,
    ) {
        val offset: Long get() = index.toLong() * chunkSize

        // dĺžka tohto kúska (posledný býva kratší)
        val length: Int get() = minOf(chunkSize.toLong(), size - offset).toInt()
    }

    fun pathKey(transferId: String): String = META_PREFIX + transferId

    fun chunkCount(size: Long, chunkSize: Int = CHUNK_SIZE): Int {
        require(chunkSize > 0) { "chunkSize" }
        if (size <= 0) return 0
        return ((size + chunkSize - 1) / chunkSize).toInt()
    }

    fun encode(ref: Ref): String = KvCodec.encode(
        mapOf(
            T to ref.transferId,
            I to ref.index.toString(),
            N to ref.count.toString(),
            C to ref.chunkSize.toString(),
            S to ref.size.toString(),
            M to ref.mtime.toString(),
        ),
    )

    // null = neplatný ref (iná verzia, poškodený, kúsok mimo súboru)
    fun decode(text: String): Ref? {
        val map = KvCodec.decode(text) ?: return null
        val ref = Ref(
            transferId = map[T]?.takeIf { TRANSFER_ID.matches(it) } ?: return null,
            index = map[I]?.toIntOrNull() ?: return null,
            count = map[N]?.toIntOrNull() ?: return null,
            chunkSize = map[C]?.toIntOrNull() ?: return null,
            size = map[S]?.toLongOrNull() ?: return null,
            mtime = map[M]?.toLongOrNull() ?: 0L,
        )
        return ref.takeIf { isConsistent(it) }
    }

    // kúsok: 0 ≤ index < count a count sedí s veľkosťou; balík: index = -1 (count = 0 = bez súboru)
    private fun isConsistent(ref: Ref): Boolean {
        val sizeOk = ref.size in 0..MAX_FILE_SIZE && ref.chunkSize in 1..MAX_CHUNK_SIZE
        val withoutFile = ref.count == 0 && ref.index == -1
        val countOk = sizeOk && (withoutFile || ref.count == chunkCount(ref.size, ref.chunkSize))
        return countOk && ref.index in -1 until ref.count
    }

    // Zoznam prijatých kúskov (jeden index na riadok, ako ho zapisuje BundleReceiver). Poškodené riadky sa ignorujú.
    fun parseLedger(text: String, count: Int): Set<Int> =
        text.lineSequence().mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until count }.toSet()

    fun isComplete(received: Set<Int>, count: Int): Boolean = (0 until count).all { it in received }

    // Názov súboru pre MediaStore bez ciest, riadiacich znakov a úvodnej bodky (skrytý súbor);
    // prichádza od druhého telefónu, preto sa mu neverí.
    fun displayName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != ':' }
            .trim()
            .trimStart('.')
            .take(MAX_NAME)
        return clean.ifEmpty { FALLBACK_NAME }
    }

    private const val MAX_NAME = 120
    private const val FALLBACK_NAME = "Galeria_rodina"
}
