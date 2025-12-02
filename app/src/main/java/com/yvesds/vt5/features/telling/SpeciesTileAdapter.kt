package com.yvesds.vt5.features.telling

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.databinding.ItemSpeciesTileBinding
import com.yvesds.vt5.hoofd.InstellingenScherm

/**
 * Adapter voor soort-tegels met optimalisaties voor efficiente updates:
 * - Efficiënt DiffUtil met payloads voor alleen aantal-wijzigingen
 * - ViewHolder pattern met bindingadapter pattern
 * - Stabiele IDs voor betere animaties
 * - Lettergrootte is nu configureerbaar via InstellingenScherm (gecached voor performance)
 */
class SpeciesTileAdapter(
    private val onTileClick: (position: Int) -> Unit
) : ListAdapter<TellingScherm.SoortRow, SpeciesTileAdapter.VH>(Diff) {

    init {
        setHasStableIds(true)
    }
    
    // Gecachede lettergrootte voor betere performance
    private var cachedTextSizeSp: Float = InstellingenScherm.DEFAULT_LETTERGROOTTE_SP.toFloat()

    object Diff : DiffUtil.ItemCallback<TellingScherm.SoortRow>() {
        override fun areItemsTheSame(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Boolean = oldItem.soortId == newItem.soortId

        override fun areContentsTheSame(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Boolean = oldItem.soortId == newItem.soortId &&
                oldItem.naam == newItem.naam &&
                oldItem.countMain == newItem.countMain &&
                oldItem.countReturn == newItem.countReturn

        override fun getChangePayload(
            oldItem: TellingScherm.SoortRow,
            newItem: TellingScherm.SoortRow
        ): Any? {
            // Als alleen de aantallen zijn veranderd, geef dan de nieuwe counts terug
            if (oldItem.soortId == newItem.soortId &&
                oldItem.naam == newItem.naam &&
                (oldItem.countMain != newItem.countMain || oldItem.countReturn != newItem.countReturn)) {
                return Pair(newItem.countMain, newItem.countReturn)
            }
            return null
        }
    }

    class VH(val vb: ItemSpeciesTileBinding) : RecyclerView.ViewHolder(vb.root)
    
    /**
     * Update de gecachede lettergrootte. Roep dit aan bij onResume van de Activity.
     */
    fun updateTextSize(textSizeSp: Int) {
        cachedTextSizeSp = textSizeSp.toFloat()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemSpeciesTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // Initialiseer cache bij eerste ViewHolder creatie
        cachedTextSizeSp = InstellingenScherm.getLettergrootteSp(parent.context).toFloat()
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.vb.tvName.text = row.naam
        holder.vb.tvCountMain.text = row.countMain.toString()
        holder.vb.tvCountReturn.text = row.countReturn.toString()
        
        // Pas gecachede lettergrootte toe
        holder.vb.tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp)
        holder.vb.tvCountMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp)
        holder.vb.tvCountReturn.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp)

        holder.vb.tileRoot.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onTileClick(pos)
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Als er een payload is, update dan alleen de aantallen
            val payload = payloads[0]
            if (payload is Pair<*, *>) {
                val countMain = payload.first as? Int
                val countReturn = payload.second as? Int
                if (countMain != null && countReturn != null) {
                    holder.vb.tvCountMain.text = countMain.toString()
                    holder.vb.tvCountReturn.text = countReturn.toString()
                    
                    // Pas ook gecachede lettergrootte toe bij payload updates
                    holder.vb.tvCountMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp)
                    holder.vb.tvCountReturn.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedTextSizeSp)
                    return
                }
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