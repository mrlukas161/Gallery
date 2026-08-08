package org.fossify.gallery.faces

import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_DESCENDING

// Triedenie podľa rovnakých konštánt ako zvyšok apky (ChangeSortingDialog). Default = dátum vytvorenia.
object FaceSorter {

    // Triedenie s PREDPOČÍTANÝM kľúčom: sortedBy volá selektor pri KAŽDOM porovnaní, takže
    // lowercase/lookup do mapy pri tisíckach položiek bežal O(n log n)-krát. Takto sa kľúč
    // spočíta raz na položku; triedenie ostáva stabilné, poradie rovnaké ako predtým.
    private inline fun <T, K : Comparable<K>> List<T>.sortedByKey(crossinline key: (T) -> K): List<T> =
        map { key(it) to it }.sortedBy { it.first }.map { it.second }

    fun sortPaths(paths: List<String>, meta: Map<String, FaceMediaMeta.Meta>, sorting: Int): List<String> {
        if (sorting and SORT_BY_RANDOM != 0) return paths.shuffled()
        val base = when {
            sorting and SORT_BY_NAME != 0 -> paths.sortedByKey { (meta[it]?.name ?: it.substringAfterLast('/')).lowercase() }
            sorting and SORT_BY_PATH != 0 -> paths.sortedByKey { it.lowercase() }
            sorting and SORT_BY_SIZE != 0 -> paths.sortedByKey { meta[it]?.size ?: 0L }
            sorting and SORT_BY_DATE_MODIFIED != 0 -> paths.sortedByKey { meta[it]?.modified ?: 0L }
            else -> paths.sortedByKey { meta[it]?.taken ?: 0L }
        }
        return if (sorting and SORT_DESCENDING != 0) base.reversed() else base
    }

    fun sortFaces(faces: List<FaceEntity>, meta: Map<String, FaceMediaMeta.Meta>, sorting: Int): List<FaceEntity> {
        if (sorting and SORT_BY_RANDOM != 0) return faces.shuffled()
        val base = when {
            sorting and SORT_BY_NAME != 0 -> faces.sortedByKey { (meta[it.mediaFullPath]?.name ?: it.mediaFullPath.substringAfterLast('/')).lowercase() }
            sorting and SORT_BY_PATH != 0 -> faces.sortedByKey { it.mediaFullPath.lowercase() }
            sorting and SORT_BY_SIZE != 0 -> faces.sortedByKey { meta[it.mediaFullPath]?.size ?: 0L }
            sorting and SORT_BY_DATE_MODIFIED != 0 -> faces.sortedByKey { meta[it.mediaFullPath]?.modified ?: 0L }
            else -> faces.sortedByKey { meta[it.mediaFullPath]?.taken ?: 0L }
        }
        return if (sorting and SORT_DESCENDING != 0) base.reversed() else base
    }
}
