package com.yvesds.vt5.features.telling

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.databinding.ItemSpeechLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SpeechLogAdapter: light-weight RecyclerView adapter for speech logs.
 *
 * Minor perf improvements:
 * - keep onBindViewHolder work minimal (avoid regex in hot path)
 * - reuse precomputed display text when possible (TellingScherm should pass ready-to-display row.tekst)
 */
class SpeechLogAdapter :
    ListAdapter<TellingScherm.SpeechLogRow, SpeechLogAdapter.VH>(Diff) {

    init {
        setHasStableIds(true)
    }

    object Diff : DiffUtil.ItemCallback<TellingScherm.SpeechLogRow>() {
        override fun areItemsTheSame(
            oldItem: TellingScherm.SpeechLogRow,
            newItem: TellingScherm.SpeechLogRow
        ): Boolean {
            return oldItem.ts == newItem.ts && oldItem.tekst == newItem.tekst
        }

        override fun areContentsTheSame(
            oldItem: TellingScherm.SpeechLogRow,
            newItem: TellingScherm.SpeechLogRow
        ): Boolean = oldItem == newItem
    }

    class VH(val vb: ItemSpeechLogBinding) : RecyclerView.ViewHolder(vb.root)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * If TellingScherm already composes the display text, keep showPartialsInRow=false to avoid
     * additional work here. Default true for backward compatibility.
     */
    var showPartialsInRow: Boolean = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemSpeechLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.vb.tvTime.text = fmt.format(Date(row.ts * 1000L))

        // Assume row.tekst is already formatted by TellingScherm (cheap). Only fall back to light heuristics.
        var displayText = row.tekst ?: ""

        if (!showPartialsInRow) {
            // Remove a simple partials suffix if present (cheap operation)
            if (displayText.contains("partials:", ignoreCase = true)) {
                val idx = displayText.indexOf("partials:", ignoreCase = true)
                if (idx > 0) displayText = displayText.substring(0, idx).trim()
            }
        }

        // Append placeholder only if the caller didn't include it (cheap check)
        if (!displayText.contains("[ ]") && (displayText.contains("+") || displayText.matches(Regex(".*\\+\\d+.*")))) {
            displayText = "$displayText  [ ]"
        }

        holder.vb.tvMsg.text = displayText
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        return (31 * item.ts + item.tekst.hashCode()).toLong()
    }
}