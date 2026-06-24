package org.fossify.gallery.helpers

import android.content.SharedPreferences
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.views.MyRecyclerView

// Pinch-zoom v mriežke = mení počet stĺpcov (ako štandardné albumy). Pamätá si počet v prefs.
object GridZoom {
    const val MIN = 1
    const val MAX = 8

    fun setup(grid: MyRecyclerView, lm: GridLayoutManager, prefs: SharedPreferences, key: String) {
        grid.setupZoomListener(object : MyRecyclerView.MyZoomListener {
            override fun zoomIn() = change(grid, lm, prefs, key, -1)
            override fun zoomOut() = change(grid, lm, prefs, key, 1)
        })
    }

    private fun change(grid: MyRecyclerView, lm: GridLayoutManager, prefs: SharedPreferences, key: String, delta: Int) {
        val c = (lm.spanCount + delta).coerceIn(MIN, MAX)
        if (c != lm.spanCount) {
            lm.spanCount = c
            prefs.edit().putInt(key, c).apply()
            grid.adapter?.notifyDataSetChanged()
        }
    }
}
