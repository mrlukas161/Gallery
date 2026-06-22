package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.R
import org.fossify.gallery.models.Medium

// Smart albums = virtuálne albumy zoskupené podľa kritéria (typ média alebo vzor cesty),
// vyhodnocované lokálne bez ML. Správajú sa ako FAVORITES/RECYCLE_BIN (path = konštanta).

const val SHOW_SMART_ALBUMS = "show_smart_albums"
const val SMART_ALBUM_VIDEOS = "smart_album_videos"
const val SMART_ALBUM_GIFS = "smart_album_gifs"
const val SMART_ALBUM_SCREENSHOTS = "smart_album_screenshots"
const val SMART_ALBUM_DOWNLOADS = "smart_album_downloads"
const val SMART_ALBUM_CAMERA = "smart_album_camera"
const val SMART_ALBUM_RAW = "smart_album_raw"

// Len typové (zlučujú naprieč priečinkami) — path-based (Screenshoty/Stiahnuté/Kamera)
// boli odstránené, lebo duplikovali existujúce reálne priečinky-albumy.
val SMART_ALBUM_PATHS = arrayListOf(
    SMART_ALBUM_VIDEOS, SMART_ALBUM_GIFS, SMART_ALBUM_RAW
)

fun isSmartAlbumPath(path: String) = path in SMART_ALBUM_PATHS

fun matchesSmartAlbum(albumPath: String, medium: Medium): Boolean = when (albumPath) {
    SMART_ALBUM_VIDEOS -> medium.isVideo()
    SMART_ALBUM_GIFS -> medium.isGIF()
    SMART_ALBUM_RAW -> medium.isRaw()
    SMART_ALBUM_SCREENSHOTS -> medium.path.contains("/screenshots", ignoreCase = true)
    SMART_ALBUM_DOWNLOADS -> medium.path.contains("/download", ignoreCase = true)
    SMART_ALBUM_CAMERA -> medium.path.contains("/dcim/camera", ignoreCase = true)
    else -> false
}

fun Context.getSmartAlbumName(path: String): String = when (path) {
    SMART_ALBUM_VIDEOS -> getString(R.string.smart_album_videos)
    SMART_ALBUM_GIFS -> getString(R.string.smart_album_gifs)
    SMART_ALBUM_SCREENSHOTS -> getString(R.string.smart_album_screenshots)
    SMART_ALBUM_DOWNLOADS -> getString(R.string.smart_album_downloads)
    SMART_ALBUM_CAMERA -> getString(R.string.smart_album_camera)
    SMART_ALBUM_RAW -> getString(R.string.smart_album_raw)
    else -> ""
}
