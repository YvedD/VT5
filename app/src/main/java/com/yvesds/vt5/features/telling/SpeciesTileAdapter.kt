package com.yvesds.vt5.features.telling

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.databinding.ItemSpeciesTileBinding

class SpeciesTileAdapter(
    private val onTileClick: (position: Int) -> Unit
) : ListAdapter<TellingScherm.SoortRow, SpeciesTileAdapter.VH>(Diff) {

    private val TAG = "SpeciesTileAdapter"

    init {
        setHasStableIds(true)
    }

    object Diff : DiffUtil.ItemCallback<TellingScherm.SoortRow>() {
        override fun areItemsTheSame(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Boolean = oldItem.soortId == newItem.soortId

        override fun areContentsTheSame(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Boolean {
            val sameContent = oldItem.soortId == newItem.soortId &&
                    oldItem.naam == newItem.naam &&
                    oldItem.count == newItem.count

            // Extra logging om te zien of inhoud verschilt
            if (!sameContent) {
                Log.d("SpeciesTileAdapter", "Content changed for ${oldItem.naam}: count ${oldItem.count} -> ${newItem.count}")
            }

            return sameContent
        }

        override fun getChangePayload(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Any? {
            // Als alleen het aantal is veranderd, geef dan een payload terug
            if (oldItem.soortId == newItem.soortId &&
                oldItem.naam == newItem.naam &&
                oldItem.count != newItem.count) {
                return newItem.count
            }
            return null
        }
    }

    class VH(val vb: ItemSpeciesTileBinding) : RecyclerView.ViewHolder(vb.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemSpeciesTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.vb.tvName.text = row.naam
        holder.vb.tvCount.text = row.count.toString()

        // Log bij elke binding
        Log.d(TAG, "Binding ${row.naam} with count ${row.count}")

        holder.vb.tileRoot.setOnClickListener {
            onTileClick(holder.bindingAdapterPosition)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Als er een payload is, update dan alleen het aantal
            val newCount = payloads[0] as? Int
            if (newCount != null) {
                holder.vb.tvCount.text = newCount.toString()
                Log.d(TAG, "Updated count via payload: $newCount")
                return
            }
        }

        // Anders doe een volledige binding
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemId(position: Int): Long {
        // stabiel ID op basis van soortId
        return getItem(position).soortId.hashCode().toLong()
    }
}