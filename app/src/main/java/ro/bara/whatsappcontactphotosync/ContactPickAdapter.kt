package ro.bara.whatsappcontactphotosync

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactPickAdapter(
    private val onToggle: (PhoneContact) -> Unit,
    private val isSelected: (PhoneContact) -> Boolean
) : RecyclerView.Adapter<ContactPickAdapter.ViewHolder>() {

    private var items: List<PhoneContact> = emptyList()

    fun submitList(list: List<PhoneContact>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_pick, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = items[position]
        holder.name.text = contact.name
        holder.phone.text = contact.phone
        holder.checkBox.isChecked = isSelected(contact)

        holder.photo.setImageDrawable(null)
        holder.photo.setBackgroundColor(holder.photo.context.getColor(R.color.card_stroke))
        val thumb = contact.photoThumbnailUri
        if (thumb != null) {
            try {
                holder.photo.context.contentResolver.openInputStream(Uri.parse(thumb))?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) holder.photo.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                // Missing/broken thumbnail — leave the placeholder background.
            }
        }

        holder.itemView.setOnClickListener {
            onToggle(contact)
            holder.checkBox.isChecked = isSelected(contact)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.photoView)
        val name: TextView = view.findViewById(R.id.nameView)
        val phone: TextView = view.findViewById(R.id.phoneView)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
    }
}
