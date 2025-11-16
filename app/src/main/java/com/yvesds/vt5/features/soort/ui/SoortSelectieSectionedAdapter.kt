package com.yvesds.vt5.features.soort.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.R
import com.yvesds.vt5.databinding.RijSoortRecentsFooterBinding
import com.yvesds.vt5.databinding.RijSoortRecentsHeaderBinding
import com.yvesds.vt5.databinding.RijSoortRecenteBinding
import com.yvesds.vt5.databinding.RijSoortSelectieBinding

/**
 * Verbeterde sectioned adapter met duidelijke visuele scheiding:
 * - RecentsHeader (divider + checkbox 'Alle recente')
 * - Recente soorten (met andere achtergrond/visuele stijl)
 * - RecentsFooter (divider als afsluiting)
 * - Normale soort items
 */
class SoortSelectieSectionedAdapter(
    private val isSelected: (String) -> Boolean,
    private val onToggleSpecies: (id: String, checked: Boolean, position: Int) -> Unit,
    private val onToggleAllRecents: (checked: Boolean) -> Unit
) : ListAdapter<SoortSelectieSectionedAdapter.RowUi, RecyclerView.ViewHolder>(Diff) {

    companion object {
        const val TYPE_SPECIES = 0
        const val TYPE_HEADER = 1
        const val TYPE_FOOTER = 2
        const val TYPE_RECENTE = 3
        const val PAYLOAD_SELECTION = "selection"
        const val PAYLOAD_HEADER_STATE = "header_state"
    }

    sealed class RowUi {
        data class Species(val item: SoortSelectieScherm.Row) : RowUi()
        data class RecenteSpecies(val item: SoortSelectieScherm.Row) : RowUi()
        data class RecentsHeader(val recentsCount: Int, val allSelected: Boolean) : RowUi()
        object RecentsFooter : RowUi()
    }

    object Diff : DiffUtil.ItemCallback<RowUi>() {
        override fun areItemsTheSame(oldItem: RowUi, newItem: RowUi): Boolean {
            return when {
                oldItem is RowUi.Species && newItem is RowUi.Species ->
                    oldItem.item.soortId == newItem.item.soortId
                oldItem is RowUi.RecenteSpecies && newItem is RowUi.RecenteSpecies ->
                    oldItem.item.soortId == newItem.item.soortId
                oldItem is RowUi.RecentsHeader && newItem is RowUi.RecentsHeader -> true
                oldItem is RowUi.RecentsFooter && newItem is RowUi.RecentsFooter -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RowUi, newItem: RowUi): Boolean = when {
            oldItem is RowUi.Species && newItem is RowUi.Species ->
                oldItem.item.soortId == newItem.item.soortId && oldItem.item.naam == newItem.item.naam
            oldItem is RowUi.RecenteSpecies && newItem is RowUi.RecenteSpecies ->
                oldItem.item.soortId == newItem.item.soortId && oldItem.item.naam == newItem.item.naam
            oldItem is RowUi.RecentsHeader && newItem is RowUi.RecentsHeader ->
                oldItem.recentsCount == newItem.recentsCount && oldItem.allSelected == newItem.allSelected
            oldItem is RowUi.RecentsFooter && newItem is RowUi.RecentsFooter -> true
            else -> false
        }

        override fun getChangePayload(oldItem: RowUi, newItem: RowUi): Any? {
            return when {
                oldItem is RowUi.RecentsHeader && newItem is RowUi.RecentsHeader &&
                        oldItem.allSelected != newItem.allSelected ->
                    PAYLOAD_HEADER_STATE
                else -> null
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return when (val row = getItem(position)) {
            is RowUi.Species -> row.item.soortId.hashCode().toLong()
            is RowUi.RecenteSpecies -> row.item.soortId.hashCode().toLong() + 1000000000L // offset to prevent collisions
            is RowUi.RecentsHeader -> Long.MIN_VALUE + 1
            is RowUi.RecentsFooter -> Long.MIN_VALUE + 2
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RowUi.Species -> TYPE_SPECIES
            is RowUi.RecenteSpecies -> TYPE_RECENTE
            is RowUi.RecentsHeader -> TYPE_HEADER
            is RowUi.RecentsFooter -> TYPE_FOOTER
        }
    }

    class SpeciesVH(val vb: RijSoortSelectieBinding) : RecyclerView.ViewHolder(vb.root) {
        val cb: CheckBox = vb.cbSoort
    }

    class RecenteVH(val vb: RijSoortRecenteBinding) : RecyclerView.ViewHolder(vb.root) {
        val cb: CheckBox = vb.cbSoort
    }

    class HeaderVH(val vb: RijSoortRecentsHeaderBinding) : RecyclerView.ViewHolder(vb.root) {
        val cb: CheckBox = vb.cbAlleRecente
    }

    class FooterVH(val vb: RijSoortRecentsFooterBinding) : RecyclerView.ViewHolder(vb.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(
                RijSoortRecentsHeaderBinding.inflate(inflater, parent, false)
            )
            TYPE_FOOTER -> FooterVH(
                RijSoortRecentsFooterBinding.inflate(inflater, parent, false)
            )
            TYPE_RECENTE -> RecenteVH(
                RijSoortRecenteBinding.inflate(inflater, parent, false)
            )
            else -> SpeciesVH(
                RijSoortSelectieBinding.inflate(inflater, parent, false)
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

                if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_HEADER_STATE)) {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.isChecked = header.allSelected
                    setupHeaderListener(holder.cb)
                } else {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.text = holder.itemView.context.getString(R.string.recents_header, header.recentsCount)
                    holder.cb.isChecked = header.allSelected
                    setupHeaderListener(holder.cb)
                }
            }
            is FooterVH -> {
                // Niets nodig, puur visuele divider
            }
            is RecenteVH -> {
                val row = getItem(position) as RowUi.RecenteSpecies
                val id = row.item.soortId

                if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.isChecked = isSelected(id)
                    setupSpeciesListener(holder.cb, id, position)
                } else {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.text = row.item.naam
                    holder.cb.isChecked = isSelected(id)
                    setupSpeciesListener(holder.cb, id, position)
                }
            }
            is SpeciesVH -> {
                val row = getItem(position) as RowUi.Species
                val id = row.item.soortId

                if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.isChecked = isSelected(id)
                    setupSpeciesListener(holder.cb, id, position)
                } else {
                    holder.cb.setOnCheckedChangeListener(null)
                    holder.cb.text = row.item.naam
                    holder.cb.isChecked = isSelected(id)
                    setupSpeciesListener(holder.cb, id, position)
                }
            }
        }
    }

    private fun setupHeaderListener(cb: CheckBox) {
        cb.setOnCheckedChangeListener { _, checked ->
            onToggleAllRecents(checked)
        }
    }

    private fun setupSpeciesListener(cb: CheckBox, id: String, position: Int) {
        cb.setOnCheckedChangeListener { _, checked ->
            onToggleSpecies(id, checked, position)
        }
    }

    fun isHeader(position: Int): Boolean = getItemViewType(position) == TYPE_HEADER

    fun isFooter(position: Int): Boolean = getItemViewType(position) == TYPE_FOOTER

    fun notifyHeaderStateChanged() {
        val idx = currentList.indexOfFirst { it is RowUi.RecentsHeader }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_HEADER_STATE)
    }

    fun notifyRecentsSelectionChanged(recentIds: Set<String>) {
        currentList.forEachIndexed { index, row ->
            if ((row is RowUi.RecenteSpecies && row.item.soortId in recentIds) ||
                (row is RowUi.Species && row.item.soortId in recentIds)) {
                notifyItemChanged(index, PAYLOAD_SELECTION)
            }
        }
    }
}