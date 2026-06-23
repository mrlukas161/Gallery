package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.databinding.ItemTagFaceBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceEntity
import kotlin.math.max
import kotlin.math.min

// Mriežka tvárí s viacnásobným výberom: ťuk = jednotlivo, podrž+ťahaj = interval, + Vybrať všetko / Zrušiť.
class FaceTagAdapter(
    val activity: Activity,
    val faces: MutableList<FaceEntity>,
    val onSelectionChanged: (Int) -> Unit,
    val onLongPress: (Int) -> Unit,
) : RecyclerView.Adapter<FaceTagAdapter.ViewHolder>() {

    private val selected = HashSet<Long>()

    fun selectedIds(): List<Long> = selected.toList()

    fun selectRange(from: Int, to: Int) {
        val a = max(0, min(from, to))
        val b = min(faces.size - 1, max(from, to))
        if (a > b) return
        for (i in a..b) faces[i].id?.let { selected.add(it) }
        notifyItemRangeChanged(a, b - a + 1)
        onSelectionChanged(selected.size)
    }

    fun selectAll() {
        faces.forEach { f -> f.id?.let { selected.add(it) } }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun removeSelected() {
        faces.removeAll { it.id != null && selected.contains(it.id) }
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTagFaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = faces.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(faces[position])
    }

    inner class ViewHolder(val binding: ItemTagFaceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(face: FaceEntity) {
            FaceCropLoader.load(face, binding.tagFaceImage)
            val isSel = face.id != null && selected.contains(face.id)
            binding.tagFaceCheck.visibility = if (isSel) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                val id = face.id ?: return@setOnClickListener
                if (selected.contains(id)) selected.remove(id) else selected.add(id)
                notifyItemChanged(bindingAdapterPosition)
                onSelectionChanged(selected.size)
            }
            binding.root.setOnLongClickListener {
                onLongPress(bindingAdapterPosition)
                true
            }
        }
    }
}
