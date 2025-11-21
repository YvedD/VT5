package com.yvesds.vt5.features.metadata.helpers

import android.app.DatePickerDialog
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import com.yvesds.vt5.R
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.CodeItemSlim
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * MetadataFormManager: Manages form field initialization and user interactions.
 * 
 * Responsibilities:
 * - Initialize date/time pickers
 * - Bind dropdown menus (telpost, weather fields)
 * - Manage form field state
 * - Handle date/time picker dialogs
 * 
 * This separates UI initialization logic from business logic.
 */
class MetadataFormManager(
    private val context: Context,
    private val binding: SchermMetadataBinding
) {
    
    companion object {
        private const val TAG = "MetadataFormManager"
    }
    
    // Form state
    var gekozenTelpostId: String? = null
    var gekozenBewolking: String? = null
    var gekozenWindkracht: String? = null
    var gekozenWindrichtingCode: String? = null
    var gekozenNeerslagCode: String? = null
    var gekozenTypeTellingCode: String? = null
    var startEpochSec: Long = System.currentTimeMillis() / 1000L
    
    /**
     * Initialize date and time pickers.
     */
    fun initDateTimePickers() {
        binding.etDatum.setOnClickListener { openDatePicker() }
        binding.etTijd.setOnClickListener { openTimeSpinnerDialog() }
    }
    
    /**
     * Prefill current date and time in the form.
     */
    fun prefillCurrentDateTime() {
        val cal = Calendar.getInstance()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.etDatum.setText(dateFmt.format(cal.time))
        binding.etTijd.setText(timeFmt.format(cal.time))
        startEpochSec = System.currentTimeMillis() / 1000L
    }
    
    /**
     * Prefill the Tellers field with the fullname from checkuser.json.
     * Only sets the field if it's currently empty and fullname is available.
     */
    fun prefillTellersFromSnapshot(snapshot: DataSnapshot) {
        // Only prefill if the field is empty
        val currentText = binding.etTellers.text?.toString()?.trim().orEmpty()
        if (currentText.isEmpty()) {
            val fullname = snapshot.currentUser?.fullname
            if (!fullname.isNullOrBlank()) {
                binding.etTellers.setText(fullname)
            }
        }
    }
    
    /**
     * Bind telpost dropdown with site data.
     */
    fun bindTelpostDropdown(snapshot: DataSnapshot) {
        val sites = snapshot.sitesById.values
            .sortedBy { it.telpostnaam.lowercase(Locale.getDefault()) }
        
        val labels = sites.map { it.telpostnaam }
        val ids = sites.map { it.telpostid }
        
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)
        binding.acTelpost.setAdapter(adapter)
        
        binding.acTelpost.setOnItemClickListener { _, _, pos, _ ->
            gekozenTelpostId = ids[pos]
        }
    }
    
    /**
     * Bind weather-related dropdowns (wind, cloud, rain, type).
     */
    fun bindWeatherDropdowns(snapshot: DataSnapshot) {
        // WINDRICHTING (veld == "wind")
        runCatching {
            val windCodes = getCodesForField(snapshot, "wind")
            val labels = windCodes.map { it.text }
            val values = windCodes.map { it.value }
            binding.acWindrichting.setAdapter(
                ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)
            )
            binding.acWindrichting.setOnItemClickListener { _, _, pos, _ ->
                gekozenWindrichtingCode = values[pos]
            }
        }
        
        // BEWOLKING 0/8..8/8 → "0".."8"
        val cloudDisplays = (0..8).map { "$it/8" }
        val cloudValues = (0..8).map { it.toString() }
        binding.acBewolking.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, cloudDisplays)
        )
        binding.acBewolking.setOnItemClickListener { _, _, pos, _ ->
            gekozenBewolking = cloudValues[pos]
        }
        
        // WINDKRACHT <1bf, 1..12bf → "0".."12"
        val windForceDisplays = buildList { add("<1bf"); addAll((1..12).map { "${it}bf" }) }
        val windForceValues = buildList { add("0"); addAll((1..12).map { it.toString() }) }
        binding.acWindkracht.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, windForceDisplays)
        )
        binding.acWindkracht.setOnItemClickListener { _, _, pos, _ ->
            gekozenWindkracht = windForceValues[pos]
        }
        
        // NEERSLAG (veld == "neerslag")
        runCatching {
            val rainCodes = getCodesForField(snapshot, "neerslag")
            val labels = rainCodes.map { it.text }
            val values = rainCodes.map { it.value }
            binding.acNeerslag.setAdapter(
                ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)
            )
            binding.acNeerslag.setOnItemClickListener { _, _, pos, _ ->
                gekozenNeerslagCode = values[pos]
            }
        }
        
        // TYPE TELLING (veld == "typetelling_trek") met filters op tekstkey
        runCatching {
            val all = getCodesForField(snapshot, "typetelling_trek")
            val filtered = all.filterNot { c ->
                val key = c.key ?: ""
                key.contains("_sound") ||
                        key.contains("_ringen") ||
                        key.startsWith("samplingrate_") ||
                        key.startsWith("gain_") ||
                        key.startsWith("verstoring_")
            }
            val labels = filtered.map { it.text }
            val values = filtered.map { it.value }
            binding.acTypeTelling.setAdapter(
                ArrayAdapter(context, android.R.layout.simple_list_item_1, labels)
            )
            binding.acTypeTelling.setOnItemClickListener { _, _, pos, _ ->
                gekozenTypeTellingCode = values[pos]
            }
        }
    }
    
    /**
     * Compute begin epoch seconds from UI date+time inputs.
     * Falls back to startEpochSec if parse fails.
     */
    fun computeBeginEpochSec(): Long {
        runCatching {
            val dateStr = binding.etDatum.text?.toString()?.trim().orEmpty()
            val timeStr = binding.etTijd.text?.toString()?.trim().orEmpty()
            if (dateStr.isBlank() || timeStr.isBlank()) return startEpochSec
            
            val dt = "$dateStr $timeStr"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val parsed = sdf.parse(dt) ?: return startEpochSec
            return parsed.time / 1000L
        }.onFailure {
            android.util.Log.w(TAG, "computeBeginEpochSec failed: ${it.message}")
        }
        return startEpochSec
    }
    
    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        runCatching {
            val parts = binding.etDatum.text?.toString()?.split("-") ?: emptyList()
            if (parts.size == 3) {
                cal.set(Calendar.YEAR, parts[0].toInt())
                cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
        }
        
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val mm = (m + 1).toString().padStart(2, '0')
                val dd = d.toString().padStart(2, '0')
                binding.etDatum.setText("$y-$mm-$dd")
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    private fun openTimeSpinnerDialog() {
        val cal = Calendar.getInstance()
        val startHour = cal.get(Calendar.HOUR_OF_DAY)
        val startMinute = cal.get(Calendar.MINUTE)
        
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 8)
        }
        
        val hourPicker = NumberPicker(context).apply {
            minValue = 0; maxValue = 23; value = startHour; wrapSelectorWheel = true
        }
        val minutePicker = NumberPicker(context).apply {
            minValue = 0; maxValue = 59; value = startMinute; wrapSelectorWheel = true
            setFormatter { v -> v.toString().padStart(2, '0') }
        }
        
        var lastMinute = startMinute
        minutePicker.setOnValueChangedListener { _, _, newVal ->
            if (newVal == 0 && lastMinute == 59) {
                hourPicker.value = (hourPicker.value + 1) % 24
            } else if (newVal == 59 && lastMinute == 0) {
                hourPicker.value = if (hourPicker.value == 0) 23 else hourPicker.value - 1
            }
            lastMinute = newVal
        }
        
        row.addView(hourPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(minutePicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.metadata_dialog_time_title))
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val hh = hourPicker.value.toString().padStart(2, '0')
                val mm = minutePicker.value.toString().padStart(2, '0')
                binding.etTijd.setText("$hh:$mm")
            }
            .setNegativeButton("Annuleer", null)
            .show()
    }
    
    private fun getCodesForField(snapshot: DataSnapshot, field: String): List<CodeItemSlim> {
        val items = snapshot.codesByCategory[field].orEmpty()
        return items.sortedBy { it.text.lowercase(Locale.getDefault()) }
    }
}
