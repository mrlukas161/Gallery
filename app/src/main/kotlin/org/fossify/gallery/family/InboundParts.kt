package org.fossify.gallery.family

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// Prijímané kúsky súborov (F4) na disku: noBackupFilesDir/family_in/<tag telefónu>_<transferId>.part
// a zoznam prijatých kúskov v .got. Súbor sa nikdy nedrží celý v pamäti. Volá sa pod zámkom BundleReceiver.
class InboundParts(private val dir: File) {
    // zapíše kúsok na jeho miesto; false = chyba disku alebo málo miesta na celý súbor
    fun write(peer: PeerEntity, ref: BundleTransfer.Ref, data: ByteArray): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val part = part(peer, ref)
        // miesto na celý súbor sa overí pri prvom kúsku, aby sa prenos nezasekol uprostred
        if (!part.exists() && dir.usableSpace < ref.size + FREE_SPACE_MARGIN) return false
        return try {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(ref.offset)
                raf.write(data)
            }
            ledger(peer, ref).appendText("${ref.index}\n")
            true
        } catch (ignored: IOException) {
            false
        }
    }

    // Celý a overený súbor (všetky kúsky, správna veľkosť, obsahový kľúč), inak null. Poškodený sa zahodí.
    fun complete(peer: PeerEntity, ref: BundleTransfer.Ref, contentKey: String): File? {
        val part = part(peer, ref)
        val ledger = ledger(peer, ref)
        val received = if (ledger.isFile) BundleTransfer.parseLedger(ledger.readText(), ref.count) else emptySet()
        // chýbajúci kúsok: balík ostane vo fronte odosielateľa a skúsi sa znova
        if (!BundleTransfer.isComplete(received, ref.count) || part.length() < ref.size) return null
        if (part.length() > ref.size) RandomAccessFile(part, "rw").use { it.setLength(ref.size) }
        val valid = try {
            ContentKey.compute(part) == contentKey
        } catch (ignored: IOException) {
            false
        }
        // poškodený alebo zmiešaný súbor: zahodiť, inak by sa uložil nesprávny obsah
        if (!valid) discard(peer, ref)
        return part.takeIf { valid }
    }

    fun discard(peer: PeerEntity, ref: BundleTransfer.Ref) {
        part(peer, ref).delete()
        ledger(peer, ref).delete()
    }

    // nedokončené prenosy (druhý telefón prestal posielať) sa po týždni zmažú
    fun cleanupStale(now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.filter { it.lastModified() < now - STALE_PART_MS }?.forEach { it.delete() }
    }

    private fun part(peer: PeerEntity, ref: BundleTransfer.Ref) = File(dir, baseName(peer, ref) + ".part")

    private fun ledger(peer: PeerEntity, ref: BundleTransfer.Ref) = File(dir, baseName(peer, ref) + ".got")

    // transferId je overené UUID (BundleTransfer.decode); deviceId sa do názvu súboru nedáva priamo
    private fun baseName(peer: PeerEntity, ref: BundleTransfer.Ref) =
        FamilyCrypto.publicTag(peer.deviceId) + "_" + ref.transferId

    private companion object {
        const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
        const val STALE_PART_MS = 7L * 24 * 60 * 60 * 1000
    }
}
