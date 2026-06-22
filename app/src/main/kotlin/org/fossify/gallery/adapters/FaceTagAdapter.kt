package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.databinding.ItemTagFaceBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceEntity

// Mriežka tvárí s viacnásobným výberom (označovanie / potvrdzovanie návrhov).
class FaceTagAdapter(
    val activity: Activity,
    val faces: MutableList<FaceEntity>,
    val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<FaceTagAdapter.ViewHolder>() {

    private val selected = HashSet<Long>()

    fun selectedIds(): List<Long> = selected.toList()

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
        }
    }
}
