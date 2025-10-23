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
 * SpeechLogAdapter
 *
 * Kleine aanpassing: toegevoegde toggle `showPartialsInRow` die bepaalt of partials-informatie
 * (indien opgenomen in de row.tekst string) getoond wordt. Dit is een lichte, backward-compatible
 * wijziging: standaard gedragen blijft hetzelfde.
 *
 * Note:
 * - De adapter verwacht dat TellingScherm.SpeechLogRow.tekst een leesbare weergave bevat.
 * - De parser schrijft NDJSON logs en TellingScherm moet deze omzetten naar SpeechLogRow.tekst
 *   in de gewenste presentatie-vorm (bijv. "<timestamp>[<soortid>] <soortnaam> +<aantal>  [ ]").
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
            // ts + tekst is voldoende uniek voor sessielogs
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
     * Toggle: show partials inside the row text if available.
     * Zet op false als je tijdens tests of in productie geen partials wilt laten zien.
     */
    var showPartialsInRow: Boolean = true

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vb = ItemSpeechLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(vb)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.vb.tvTime.text = fmt.format(Date(row.ts * 1000L))

        // If row.tekst already contains the desired formatted string, show it.
        // Optionally append a visual "[ ]" checkbox representation if row indicates a parsed item.
        // The actual interactive checkbox control is not added here to avoid layout coupling;
        // if interactive checkboxes are required, add a CheckBox to item_speech_log.xml and bind here.
        var displayText = row.tekst

        if (showPartialsInRow) {
            // If the row contains explicit partials marker (e.g. "partials:"), keep them,
            // otherwise do nothing. TellingScherm is responsible for crafting row.tekst with partials.
            displayText = row.tekst
        } else {
            // remove any partials markers if present (simple heuristic)
            displayText = row.tekst.replace(Regex("(?i)partials?:\\s*\\[.*\\]\$"), "").trim()
        }

        // Append a visual checkbox placeholder for parsed lines if not already present.
        // The placeholder "[ ]" appears after the message; downstream UI can replace with an interactive widget.
        if (!displayText.contains("[ ]") && (displayText.contains("+") || displayText.matches(Regex(".*\\+\\d+.*")))) {
            displayText = "$displayText  [ ]"
        }

        holder.vb.tvMsg.text = displayText
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        // samenstelling voor stabiel-ish id
        return (31 * item.ts + item.tekst.hashCode()).toLong()
    }
}