package org.fossify.gallery.safety

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

// Núdzová obnova surovej kópie DB z automatickej zálohy (A3, len po poškodení podľa A1).
//
// Otvorenú Room DB nemožno vymeniť za behu, preto prebieha v dvoch krokoch:
//  1. stage(): po potvrdení používateľom sa kópia zo ZIP rozbalí vedľa DB ako `<db>.restore-pending`
//     (overí sa hlavička SQLite a verzia schémy) a zapíše sa do DbSafetyState; appka sa potom zavrie;
//  2. applyPending(): pri ďalšom štarte (App.onCreate, skôr než sa ktorákoľvek DB otvorí) sa súčasná
//     DB premenuje na `<db>.replaced-<čas>` (nič sa nemaže) a na jej miesto sa presunie obnovená kópia.
object DbRestore {
    private const val TAG = "DbRestore"
    private const val MAX_DB_BYTES = 512L * 1024 * 1024

    // Rozbalí zo ZIP zálohy požadované DB. Vráti tie, ktoré sú pripravené; ostatné v zálohe nie sú.
    @Throws(IOException::class)
    fun stage(context: Context, store: AutoBackupStore, zip: AutoBackupStore.Item, names: Set<String>): Set<String> {
        val app = context.applicationContext
        val wanted = names.intersect(DbFiles.BACKED_UP.toSet())
        val staged = HashSet<String>()
        store.open(zip).use { input ->
            val z = ZipInputStream(input)
            var entry = z.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name in wanted && name !in staged) {
                    stageEntry(app, z, name)
                    staged.add(name)
                }
                entry = z.nextEntry
            }
        }
        if (staged.isNotEmpty()) {
            val state = DbSafetyState(app)
            state.setPendingRestores(state.pendingRestores() + staged)
        }
        return staged
    }

    // rozbalí jednu DB vedľa pôvodnej ako `<db>.restore-pending` (cez dočasný súbor)
    @Suppress("ThrowsCount")
    private fun stageEntry(app: Context, z: ZipInputStream, name: String) {
        val target = File(app.getDatabasePath(name).path + DbFiles.RESTORE_PENDING_SUFFIX)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { out -> copyLimited(z, out) }
        val usable = isUsable(name, tmp)
        // sprievodné súbory, ktoré mohla vytvoriť kontrola verzie (patria len našej dočasnej kópii)
        DbFiles.SIDE_SUFFIXES.forEach { File(tmp.path + it).delete() }
        if (!usable) {
            tmp.delete()
            throw IOException("kópia $name v zálohe nie je použiteľná")
        }
        if (target.exists()) target.delete() // len naša staršia pripravená kópia
        if (!tmp.renameTo(target)) throw IOException("kópiu $name sa nepodarilo pripraviť")
    }

    // Volá App.onCreate pred akýmkoľvek prístupom k DB. Nikdy nevyhodí výnimku.
    @Suppress("TooGenericExceptionCaught")
    fun applyPending(context: Context) {
        try {
            val app = context.applicationContext
            val state = DbSafetyState(app)
            val pending = state.pendingRestores()
            if (pending.isEmpty()) return
            val done = ArrayList<String>()
            val left = HashSet<String>()
            val now = System.currentTimeMillis()
            pending.forEach { name ->
                val db = app.getDatabasePath(name)
                val staged = File(db.path + DbFiles.RESTORE_PENDING_SUFFIX)
                when {
                    !staged.exists() -> Unit
                    db.exists() && SafeOpenHelperFactory.moveAside(db.path, DbFiles.REPLACED, now) == null ->
                        left.add(name)

                    staged.renameTo(db) -> done.add(name)
                    else -> left.add(name)
                }
            }
            state.setPendingRestores(left)
            if (done.isNotEmpty()) {
                state.clearCorruptions()
                state.restoredNotice = done.sorted().joinToString(", ")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "pripravenú obnovu databázy sa nepodarilo nasadiť", e)
        }
    }

    private fun isUsable(name: String, file: File): Boolean {
        val head = ByteArray(DbFiles.SQLITE_HEADER_SIZE)
        val read = file.inputStream().use { it.read(head) }
        if (read != head.size || !DbFiles.isSqliteHeader(head)) return false
        val version = DbVersionGuard.onDiskVersion(file) ?: return false
        val expected = DbVersionGuard.expected()[name] ?: return false
        return version in 1..expected
    }

    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_DB_BYTES) throw IOException("kópia v zálohe je príliš veľká")
            out.write(buffer, 0, n)
        }
    }

    private const val BUFFER = 64 * 1024
}
