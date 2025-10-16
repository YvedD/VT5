package com.yvesds.vt5.features.soort.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.databinding.RijSoortRecentsHeaderBinding
import com.yvesds.vt5.databinding.RijSoortSelectieBinding

/**
 * Sectioned adapter:
 * - RecentsHeader (divider + checkbox 'Alle recente')
 * - Species items met checkboxen
 */
class SoortSelectieSectionedAdapter(
    private val isSelected: (String) -> Boolean,
    private val onToggleSpecies: (id: String, checked: Boolean, position: Int) -> Unit,
    private val onToggleAllRecents: (checked: Boolean) -> Unit
) : ListAdapter<SoortSelectieSectionedAdapter.RowUi, RecyclerView.ViewHolder>(Diff) {

    companion object {
        const val TYPE_SPECIES = 0
        const val TYPE_HEADER = 1
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_HEADER_STATE = "header_state"
    }

    sealed class RowUi {
        data class Species(val item: SoortSelectieScherm.Row) : RowUi()
        data class RecentsHeader(val recentsCount: Int, val allSelected: Boolean) : RowUi()
    }

    object Diff : DiffUtil.ItemCallback<RowUi>() {
        override fun areItemsTheSame(oldItem: RowUi, newItem: RowUi): Boolean {
            return when {
                oldItem is RowUi.Species && newItem is RowUi.Species -> oldItem.item.soortId == newItem.item.soortId
                oldItem is RowUi.RecentsHeader && newItem is RowUi.RecentsHeader -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RowUi, newItem: RowUi): Boolean = oldItem == newItem
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return when (val row = getItem(position)) {
            is RowUi.Species -> row.item.soortId.hashCode().toLong()
            is RowUi.RecentsHeader -> Long.MIN_VALUE + 1
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RowUi.Species -> TYPE_SPECIES
            is RowUi.RecentsHeader -> TYPE_HEADER
        }
    }

    class SpeciesVH(val vb: RijSoortSelectieBinding) : RecyclerView.ViewHolder(vb.root) {
        val cb: CheckBox = vb.cbSoort
    }
    class HeaderVH(val vb: RijSoortRecentsHeaderBinding) : RecyclerView.ViewHolder(vb.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                RijSoortRecentsHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> SpeciesVH(
                RijSoortSelectieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        when (holder) {
            is HeaderVH -> {
                val header = getItem(position) as RowUi.RecentsHeader
                // Alleen header-state bijwerken
                holder.vb.cbAlleRecente.setOnCheckedChangeListener(null)
                holder.vb.cbAlleRecente.text = "Alle recente (${header.recentsCount})"
                holder.vb.cbAlleRecente.isChecked = header.allSelected
                holder.vb.cbAlleRecente.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    onToggleAllRecents(checked)
                }
                // divider is puur visueel; geen dynamic gedrag nodig hier
            }
            is SpeciesVH -> {
                val row = getItem(position) as RowUi.Species
                val id = row.item.soortId
                holder.cb.setOnCheckedChangeListener(null)
                holder.cb.text = "${row.item.naam} (id: $id)"
                holder.cb.isChecked = isSelected(id)
                holder.cb.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onToggleSpecies(id, checked, pos)
                    }
                }
            }
        }
    }

    fun isHeader(position: Int): Boolean = getItemViewType(position) == TYPE_HEADER

    fun notifyHeaderStateChanged() {
        val idx = currentList.indexOfFirst { it is RowUi.RecentsHeader }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_HEADER_STATE)
    }

    fun notifyRecentsSelectionChanged(recentIds: Set<String>) {
        currentList.forEachIndexed { index, row ->
            if (row is RowUi.Species && row.item.soortId in recentIds) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }
}