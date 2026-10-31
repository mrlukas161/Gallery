package org.fossify.gallery.compare

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ItemCompareGridBinding
import org.fossify.gallery.helpers.PhotoScore
import java.io.File

// Mriežka 2×2 / 3×3 pre série nad 4 zábery: celé fotky, v rohu skóre a ★, označená = červený rám + kôš.
// Ťuk prepne značku (po krátkom čakaní na prípadný druhý ťuk), dvojťuk alebo podržanie otvorí porovnanie.
class CompareGridAdapter(
    private val activity: Activity,
    private val session: CompareSession,
    private val onToggle: (String) -> Unit,
    private val onOpen: (String) -> Unit,
) : RecyclerView.Adapter<CompareGridAdapter.Holder>() {
    var scores: Map<String, PhotoScore> = emptyMap()
    var bestKnown = false
    var cellHeight = 0

    private val handler = Handler(Looper.getMainLooper())
    private var pending: String? = null
    private val single = Runnable {
        pending?.let(onToggle)
        pending = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCompareGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    override fun getItemCount() = session.size

    private fun onClick(path: String) {
        handler.removeCallbacks(single)
        if (pending == path) {
            pending = null
            onOpen(path)
            return
        }
        pending?.let(onToggle)
        pending = path
        handler.postDelayed(single, ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    // pri odchode z mriežky nech čakajúci ťuk nič neprepne
    fun cancel() {
        handler.removeCallbacks(single)
        pending = null
    }

    inner class Holder(private val b: ItemCompareGridBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(pos: Int) {
            val path = session.paths[pos]
            if (cellHeight > 0) b.root.layoutParams = b.root.layoutParams.apply { height = cellHeight }
            Glide.with(activity).load(File(path)).fitCenter().into(b.gridImage)
            val marked = path in session.marked
            b.gridOverlay.visibility = if (marked) View.VISIBLE else View.GONE
            b.gridBorder.visibility = b.gridOverlay.visibility
            b.gridTrash.visibility = b.gridOverlay.visibility
            val score = scores[path]
            b.gridScore.visibility = if (score == null) View.GONE else View.VISIBLE
            val star = if (bestKnown && score?.isBest == true) "★ " else ""
            b.gridScore.text = score?.let { "$star${it.percent} %" }
            b.gridPosition.text = activity.getString(R.string.pa_position, pos + 1, session.size)
            b.root.contentDescription = activity.getString(
                if (marked) R.string.pa_strip_item_marked else R.string.pa_strip_item,
                pos + 1,
                session.size,
            )
            b.root.setOnClickListener { onClick(path) }
            b.root.setOnLongClickListener {
                cancel()
                onOpen(path)
                true
            }
        }
    }
}
