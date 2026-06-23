package org.fossify.gallery.faces

import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_DESCENDING

// Triedenie podľa rovnakých konštánt ako zvyšok apky (ChangeSortingDialog). Default = dátum vytvorenia.
object FaceSorter {
    fun sortPaths(paths: List<String>, meta: Map<String, FaceMediaMeta.Meta>, sorting: Int): List<String> {
        if (sorting and SORT_BY_RANDOM != 0) return paths.shuffled()
        val base = when {
            sorting and SORT_BY_NAME != 0 -> paths.sortedBy { (meta[it]?.name ?: it.substringAfterLast('/')).lowercase() }
            sorting and SORT_BY_PATH != 0 -> paths.sortedBy { it.lowercase() }
            sorting and SORT_BY_SIZE != 0 -> paths.sortedBy { meta[it]?.size ?: 0L }
            sorting and SORT_BY_DATE_MODIFIED != 0 -> paths.sortedBy { meta[it]?.modified ?: 0L }
            else -> paths.sortedBy { meta[it]?.taken ?: 0L }
        }
        return if (sorting and SORT_DESCENDING != 0) base.reversed() else base
    }

    fun sortFaces(faces: List<FaceEntity>, meta: Map<String, FaceMediaMeta.Meta>, sorting: Int): List<FaceEntity> {
        if (sorting and SORT_BY_RANDOM != 0) return faces.shuffled()
        val base = when {
            sorting and SORT_BY_NAME != 0 -> faces.sortedBy { (meta[it.mediaFullPath]?.name ?: it.mediaFullPath.substringAfterLast('/')).lowercase() }
            sorting and SORT_BY_PATH != 0 -> faces.sortedBy { it.mediaFullPath.lowercase() }
            sorting and SORT_BY_SIZE != 0 -> faces.sortedBy { meta[it.mediaFullPath]?.size ?: 0L }
            sorting and SORT_BY_DATE_MODIFIED != 0 -> faces.sortedBy { meta[it.mediaFullPath]?.modified ?: 0L }
            else -> faces.sortedBy { meta[it.mediaFullPath]?.taken ?: 0L }
        }
        return if (sorting and SORT_DESCENDING != 0) base.reversed() else base
    }
}
