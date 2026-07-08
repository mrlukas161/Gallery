package org.fossify.gallery.helpers

// Prenos veľkých zoznamov ciest medzi obrazovkami BEZ intent extra (to by pri tisícoch fotiek
// spadlo na TransactionTooLargeException – presne to spôsobilo pád pri clusteri ~4800 fotiek).
object PathTransfer {
    @Volatile
    var forGrid: List<String>? = null

    @Volatile
    var forMap: List<String>? = null

    @Volatile
    var forCompare: List<String>? = null

    // uzavretý set fotiek pre prehliadač (swipe ostane len v tomto zozname — Ľudia/hľadanie/cluster)
    @Volatile
    var forViewer: List<String>? = null
}
