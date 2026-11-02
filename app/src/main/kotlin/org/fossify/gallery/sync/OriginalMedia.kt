package org.fossify.gallery.sync

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.InputStream

// Originál fotky s GPS pre PC. Android 10+ pri čítaní cez ContentResolver bez MediaStore.setRequireOriginal
// z fotky vymaže polohu. Lukáš (2. 11. 2026): fotky v PC majú mať polohu ako v telefóne.
// Vyžaduje ACCESS_MEDIA_LOCATION (appka ho pýta pri štarte spolu s prístupom k médiám). Bez neho
// otvorenie originálu hodí výnimku a otvorí sa bežná kópia bez polohy (ako doteraz).
object OriginalMedia {
    fun openFd(resolver: ContentResolver, uri: Uri): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return resolver.openFileDescriptor(MediaStore.setRequireOriginal(uri), "r")
            } catch (ignored: SecurityException) {
            } catch (ignored: UnsupportedOperationException) {
            }
        }
        return resolver.openFileDescriptor(uri, "r")
    }

    fun openStream(resolver: ContentResolver, uri: Uri): InputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return resolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (ignored: SecurityException) {
            } catch (ignored: UnsupportedOperationException) {
            }
        }
        return resolver.openInputStream(uri)
    }
}
