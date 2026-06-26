package org.fossify.gallery.helpers

// Prenos veľkých zoznamov ciest medzi obrazovkami BEZ intent extra (to by pri tisícoch fotiek
// spadlo na TransactionTooLargeException – presne to spôsobilo pád pri clusteri ~4800 fotiek).
object PathTransfer {
    @Volatile
    var forGrid: List<String>? = null

    @Volatile
    var forMap: List<String>? = null
}
