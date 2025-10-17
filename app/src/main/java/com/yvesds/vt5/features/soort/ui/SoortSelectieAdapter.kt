package com.yvesds.vt5.features.soort.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.databinding.RijSoortSelectieBinding

/**
 * Geoptimaliseerde adapter voor soortenselectie met payloads voor efficiënte updates
 */
class SoortSelectieAdapter(
    private val isSelected: (String) -> Boolean,
    private val onToggle: (id: String, checked: Boolean, position: Int) -> Unit
) : ListAdapter<SoortSelectieScherm.Row, SoortSelectieAdapter.VH>(Diff) {

    companion object {
        const val PAYLOAD_SELECTION = "selection"
    }

    init {
        setHasStableIds(true)
    }

    object Diff : DiffUtil.ItemCallback<SoortSelectieScherm.Row>() {
        override fun areItemsTheSame(oldItem: SoortSelectieScherm.Row, newItem: SoortSelectieScherm.Row): Boolean =
            oldItem.soortId == newItem.soortId

        override fun areContentsTheSame(oldItem: SoortSelectieScherm.Row, newItem: SoortSelectieScherm.Row): Boolean =
            oldItem.soortId == newItem.soortId && oldItem.naam == newItem.naam

        override fun getChangePayload(oldItem: SoortSelectieScherm.Row, newItem: SoortSelectieScherm.Row): Any? {
            // Geen payloads nodig voor content changes, want namen veranderen niet
            return null
        }
    }

    class VH(val vb: RijSoortSelectieBinding) : RecyclerView.ViewHolder(vb.root) {
        val cb: CheckBox = vb.cbSoort
    }

    override fun getItemId(position: Int): Long = getItem(position).soortId.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = RijSoortSelectieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindFull(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            val item = getItem(position)
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = isSelected(item.soortId)
            setupListener(holder.cb, item, position)
        } else {
            bindFull(holder, position)
        }
    }

    private fun bindFull(holder: VH, position: Int) {
        val item = getItem(position)
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.text = item.naam
        holder.cb.isChecked = isSelected(item.soortId)
        setupListener(holder.cb, item, position)
    }

    private fun setupListener(cb: CheckBox, item: SoortSelectieScherm.Row, position: Int) {
        cb.setOnCheckedChangeListener { _, checked ->
            val pos = position
            if (pos != RecyclerView.NO_POSITION) onToggle(item.soortId, checked, pos)
        }
    }
}