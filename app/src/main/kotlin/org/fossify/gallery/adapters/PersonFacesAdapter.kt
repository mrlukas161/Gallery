package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.databinding.ItemPersonPhotoBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.FaceEntity

// Mriežka výrezov tvárí jednej osoby. Ťuknutie = otvor fotku, podržanie = oprava (presun / "nie je to ona").
class PersonFacesAdapter(
    val activity: Activity,
    val faces: MutableList<FaceEntity>,
    val onClick: (FaceEntity) -> Unit,
    val onLongClick: (FaceEntity) -> Unit,
) : RecyclerView.Adapter<PersonFacesAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = faces.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(faces[position])
    }

    // okamžitá spätná väzba po oprave — vyhoď tvár z mriežky (autoritatívny prepočet príde po návrate späť)
    fun removeFace(face: FaceEntity) {
        val idx = faces.indexOfFirst { it.id == face.id }
        if (idx >= 0) {
            faces.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    inner class ViewHolder(val binding: ItemPersonPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(face: FaceEntity) {
            FaceCropLoader.load(face, binding.photoImage)
            binding.root.setOnClickListener { onClick(face) }
            binding.root.setOnLongClickListener {
                onLongClick(face)
                true
            }
        }
    }
}
