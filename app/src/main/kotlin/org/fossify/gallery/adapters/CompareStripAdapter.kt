package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.compare.CompareSession
import org.fossify.gallery.databinding.ItemCompareStripBinding
import org.fossify.gallery.helpers.PhotoScore
import java.io.File

// Pás fotiek skupiny porovnávača: ★ = najlepšia, skóre, 😑 = zavreté oči, A/B = v ktorom paneli je,
// označená do Koša = červené prekrytie + ikona koša. Značky a panely číta z CompareSession (podľa cesty).
// Ťuk = fotka do panela A (vo výberovom režime prepne značku), podržanie = výberový režim.
class CompareStripAdapter(
    val activity: Activity,
    private val session: CompareSession,
    val onTap: (String) -> Unit,
    val onLong: (String) -> Unit,
) : RecyclerView.Adapter<CompareStripAdapter.ViewHolder>() {

    var scores: Map<String, PhotoScore> = emptyMap()

    // ★ až po dokončení analýzy (priebežné poradie by skákalo)
    var bestKnown = false

    fun refresh(vararg paths: String?) {
        paths.mapNotNull { p -> p?.let { session.paths.indexOf(it) } }
            .filter { it >= 0 }
            .forEach { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemCompareStripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = session.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    inner class ViewHolder(val binding: ItemCompareStripBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pos: Int) {
            val path = session.paths[pos]
            Glide.with(activity).load(File(path)).centerCrop().into(binding.stripImage)
            val score = scores[path]
            val best = bestKnown && score?.isBest == true
            binding.stripBest.visibility = if (best) View.VISIBLE else View.GONE
            val closedEyes = score != null && score.faces > score.eyesOpen
            binding.stripWarn.visibility = if (closedEyes) View.VISIBLE else View.GONE
            binding.stripScore.visibility = if (score == null) View.GONE else View.VISIBLE
            binding.stripScore.text = score?.let { "${it.percent} %" }
            val marked = path in session.marked
            binding.stripOverlay.visibility = if (marked) View.VISIBLE else View.GONE
            binding.stripTrash.visibility = binding.stripOverlay.visibility
            bindPane(path)
            val descRes = if (marked) R.string.pa_strip_item_marked else R.string.pa_strip_item
            val desc = activity.getString(descRes, pos + 1, session.size)
            binding.root.contentDescription = if (best) "$desc, ${activity.getString(R.string.pa_strip_best)}" else desc
            binding.root.setOnClickListener { onTap(path) }
            binding.root.setOnLongClickListener {
                onLong(path)
                true
            }
        }

        // ktorá fotka je práve v paneli A (modrá) a B (oranžová)
        private fun bindPane(path: String) {
            val pane = when (path) {
                session.a -> PaneMark(R.drawable.border_pane_left, R.string.pa_pane_a, COLOR_A)
                session.b -> PaneMark(R.drawable.border_pane_right, R.string.pa_pane_b, COLOR_B)
                else -> null
            }
            binding.stripBorder.visibility = if (pane == null) View.GONE else View.VISIBLE
            binding.stripBadge.visibility = binding.stripBorder.visibility
            if (pane != null) {
                binding.stripBorder.setBackgroundResource(pane.border)
                binding.stripBadge.setText(pane.label)
                binding.stripBadge.setBackgroundColor(pane.color)
            }
        }
    }

    private class PaneMark(val border: Int, val label: Int, val color: Int)

    private companion object {
        const val COLOR_A = 0xFF1976D2.toInt()
        const val COLOR_B = 0xFFFB8C00.toInt()
    }
}
