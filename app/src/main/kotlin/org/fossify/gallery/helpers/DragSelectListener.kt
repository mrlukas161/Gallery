package org.fossify.gallery.helpers

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

// Intervalový výber ťahaním: podrž na tvári (long-press) -> aktivuje sa, ťahaj prstom -> označí rozsah.
// Viac ťahaní = viac intervalov (pridáva sa). Počas ťahania RecyclerView neroluje (intercept).
class DragSelectListener(
    private val onRangeSelected: (from: Int, to: Int) -> Unit,
) : RecyclerView.OnItemTouchListener {
    private var active = false
    private var start = RecyclerView.NO_POSITION

    fun startDrag(position: Int) {
        active = true
        start = position
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!active) return false
        handle(rv, e)
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        handle(rv, e)
    }

    private fun handle(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!active || start == RecyclerView.NO_POSITION) return
                val child = rv.findChildViewUnder(e.x, e.y) ?: return
                val pos = rv.getChildAdapterPosition(child)
                if (pos != RecyclerView.NO_POSITION) onRangeSelected(start, pos)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                start = RecyclerView.NO_POSITION
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}
