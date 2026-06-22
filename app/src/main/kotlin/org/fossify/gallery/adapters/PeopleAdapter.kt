package org.fossify.gallery.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.gallery.databinding.ItemPersonBinding
import org.fossify.gallery.faces.FaceCropLoader
import org.fossify.gallery.faces.Person

class PeopleAdapter(
    val activity: Activity,
    val people: List<Person>,
    val onClick: (Person) -> Unit,
    val onLongClick: (Person) -> Unit,
) : RecyclerView.Adapter<PeopleAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = people.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(people[position])
    }

    inner class ViewHolder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(person: Person) {
            val cover = person.cover
            if (cover != null) {
                FaceCropLoader.load(cover, binding.personImage)
            } else {
                binding.personImage.setImageBitmap(null)
            }
            // potvrdená osoba ukáže meno, návrh ukáže počet tvárí
            binding.personCount.text = person.name ?: person.faceCount.toString()
            binding.root.setOnClickListener { onClick(person) }
            binding.root.setOnLongClickListener {
                onLongClick(person)
                true
            }
        }
    }
}
