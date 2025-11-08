package com.yvesds.vt5.features.telling

import android.graphics.Color
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
 * Changes:
 * - Default showPartialsInRow = false (TellingScherm composes the partial text; adapter stays cheap).
 * - No regex allocations in onBindViewHolder anymore.
 * - Rows are colorized by row.bron:
 *     - "final" -> bright green
 *     - "partial" -> subtle gray
 *     - "alias"  -> amber (example)
 *     - others  -> default text color
 * - Keeps minimal work in onBindViewHolder and relies on TellingScherm to provide already-processed row.tekst.
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
            // Timestamp + text is a reasonable identity for log rows
            return oldItem.ts == newItem.ts && oldItem.tekst == newItem.tekst && oldItem.bron == newItem.bron
        }

        override fun areContentsTheSame(
            oldItem: TellingScherm.SpeechLogRow,
            newItem: TellingScherm.SpeechLogRow
        ): Boolean = oldItem == newItem
    }

    class VH(val vb: ItemSpeechLogBinding) : RecyclerView.ViewHolder(vb.root)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * If TellingScherm already composes the display text, set to false to avoid
     * additional work here. Default false for cheaper binds.
     */
    var showPartialsInRow: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemSpeechLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        // time formatting (cheap)
        holder.vb.tvTime.text = fmt.format(Date(row.ts * 1000L))

        // Use the already-prepared text from TellingScherm; keep adapter logic minimal.
        val displayText = row.tekst ?: ""
        holder.vb.tvMsg.text = displayText

        // Precomputed color constants (no parseColor allocations per bind)
        val COLOR_FINAL = 0xFF00C853.toInt() // bright green
        val COLOR_WHITE = Color.WHITE
        val defaultColor = holder.vb.tvMsg.currentTextColor

        when (row.bron) {
            "final" -> holder.vb.tvMsg.setTextColor(COLOR_FINAL)
            "partial" -> holder.vb.tvMsg.setTextColor(COLOR_WHITE)
            "alias" -> holder.vb.tvMsg.setTextColor(COLOR_WHITE)
            "raw" -> holder.vb.tvMsg.setTextColor(COLOR_WHITE)
            "systeem", "manueel" -> holder.vb.tvMsg.setTextColor(COLOR_WHITE)
            else -> holder.vb.tvMsg.setTextColor(defaultColor)
        }
    }
    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        // stable id based on ts and tekst hash and bron - avoid collisions as best effort
        return (31L * item.ts + item.tekst.hashCode() + item.bron.hashCode()).toLong()
    }
}