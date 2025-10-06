@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.CodeItem
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MetadataScherm : AppCompatActivity() {

    private lateinit var binding: SchermMetadataBinding
    private var snapshot: DataSnapshot = DataSnapshot()

    // Gekozen waarden
    private var gekozenTelpostId: String? = null
    private var gekozenBewolking: String? = null        // "0".."8"
    private var gekozenWindkracht: String? = null       // "0".."12"
    private var gekozenWindrichtingCode: String? = null // codes.value
    private var gekozenNeerslagCode: String? = null     // codes.value
    private var gekozenTypeTellingCode: String? = null  // codes.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // geen soft keyboard op datum/tijd
        binding.etDatum.inputType = InputType.TYPE_NULL
        binding.etTijd.inputType = InputType.TYPE_NULL

        initDateTimePickers()
        prefillCurrentDateTime()

        // Off-main alles laden
        lifecycleScope.launch {
            val repo = ServerDataRepository(this@MetadataScherm)
            snapshot = withContext(Dispatchers.IO) { repo.loadAllFromSaf() }
            bindTelpostDropdown()
            bindWeatherAndTypeDropdowns()
        }

        binding.btnVerder.setOnClickListener {
            val payload = buildMetadataHeader()
            Toast.makeText(this, "OK: $payload", Toast.LENGTH_SHORT).show()
        }
        binding.btnAnnuleer.setOnClickListener { finish() }
    }

    /* ---------------- Datum & Tijd ---------------- */

    private fun initDateTimePickers() {
        binding.etDatum.setOnClickListener { openDatePicker() }
        binding.etTijd.setOnClickListener { openTimeSpinnerDialog() } // twee NumberPickers
    }

    private fun prefillCurrentDateTime() {
        val cal = Calendar.getInstance()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.etDatum.setText(dateFmt.format(cal.time))
        binding.etTijd.setText(timeFmt.format(cal.time))
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
            this,
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

    /** Tijdkiezer met 2 NumberPickers + overflow/underflow (59→00 = uur+1, 00→59 = uur-1). */
    private fun openTimeSpinnerDialog() {
        val cal = Calendar.getInstance()
        runCatching {
            val parts = binding.etTijd.text?.toString()?.split(":") ?: emptyList()
            if (parts.size == 2) {
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                cal.set(Calendar.MINUTE, parts[1].toInt())
            }
        }
        val startHour = cal.get(Calendar.HOUR_OF_DAY)
        val startMinute = cal.get(Calendar.MINUTE)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 8)
        }

        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = startHour
            wrapSelectorWheel = true
        }
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = startMinute
            wrapSelectorWheel = true
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

        AlertDialog.Builder(this)
            .setTitle("Tijd kiezen")
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val hh = hourPicker.value.toString().padStart(2, '0')
                val mm = minutePicker.value.toString().padStart(2, '0')
                binding.etTijd.setText("$hh:$mm")
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /* ---------------- Dropdowns ---------------- */

    private fun bindTelpostDropdown() {
        val sites = snapshot.sitesById.values
            .sortedBy { it.telpostnaam.lowercase(Locale.getDefault()) }

        val labels = sites.map { it.telpostnaam }
        val ids    = sites.map { it.telpostid }

        binding.acTelpost.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.acTelpost.setOnItemClickListener { _, _, pos, _ ->
            gekozenTelpostId = ids[pos]
        }
    }

    private fun bindWeatherAndTypeDropdowns() {
        // WINDRICHTING (veld == "wind") – direct uit codesByCategory
        snapshot.codesByCategory["wind"]?.let { codes ->
            val sorted = codes.sortedWith(
                compareBy(
                    { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                )
            )
            val labels = sorted.mapNotNull { it.tekst }
            val values = sorted.map { it.value }
            if (labels.isNotEmpty()) {
                binding.acWindrichting.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                )
                binding.acWindrichting.setOnItemClickListener { _, _, pos, _ ->
                    gekozenWindrichtingCode = values[pos]
                }
            }
        }

        // BEWOLKING 0/8..8/8 -> intern "0".."8"
        val cloudDisplays = (0..8).map { "$it/8" }
        val cloudValues   = (0..8).map { it.toString() }
        binding.acBewolking.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, cloudDisplays)
        )
        binding.acBewolking.setOnItemClickListener { _, _, pos, _ ->
            gekozenBewolking = cloudValues[pos]
        }

        // WINDKRACHT <1bf, 1..12bf -> "0".."12"
        val windForceDisplays = buildList {
            add("<1bf")
            addAll((1..12).map { "${it}bf" })
        }
        val windForceValues = buildList {
            add("0")
            addAll((1..12).map { it.toString() })
        }
        binding.acWindkracht.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, windForceDisplays)
        )
        binding.acWindkracht.setOnItemClickListener { _, _, pos, _ ->
            gekozenWindkracht = windForceValues[pos]
        }

        // NEERSLAG (veld == "neerslag")
        snapshot.codesByCategory["neerslag"]?.let { codes ->
            val sorted = codes.sortedWith(
                compareBy(
                    { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                )
            )
            val labels = sorted.mapNotNull { it.tekst }
            val values = sorted.map { it.value }
            if (labels.isNotEmpty()) {
                binding.acNeerslag.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                )
                binding.acNeerslag.setOnItemClickListener { _, _, pos, _ ->
                    gekozenNeerslagCode = values[pos]
                }
            }
        }

        // TYPE TELLING (veld == "typetelling_trek") + filters op tekstkey
        snapshot.codesByCategory["typetelling_trek"]?.let { all ->
            val filtered = all.filterNot { c ->
                val key = c.key ?: ""
                key.contains("_sound") ||
                        key.contains("_ringen") ||
                        key.startsWith("samplingrate_") ||
                        key.startsWith("gain_") ||
                        key.startsWith("verstoring_")
            }
            val sorted = filtered.sortedWith(
                compareBy(
                    { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                )
            )
            val labels = sorted.mapNotNull { it.tekst }
            val values = sorted.map { it.value }
            if (labels.isNotEmpty()) {
                binding.acTypeTelling.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
                )
                binding.acTypeTelling.setOnItemClickListener { _, _, pos, _ ->
                    gekozenTypeTellingCode = values[pos]
                }
            }
        }

        Toast.makeText(this, "Metadata geladen.", Toast.LENGTH_SHORT).show()
    }

    /* ---------------- Payload ---------------- */

    private fun buildMetadataHeader(): Map<String, String?> = mapOf(
        "datum" to binding.etDatum.text?.toString(),
        "tijd" to binding.etTijd.text?.toString(),
        "telpostid" to gekozenTelpostId,
        "windrichting_code" to gekozenWindrichtingCode,
        "windkracht_bft" to gekozenWindkracht,
        "bewolking_achtsten" to gekozenBewolking,
        "neerslag_code" to gekozenNeerslagCode,
        "typetelling_code" to gekozenTypeTellingCode,
        "tellers" to binding.etTellers.text?.toString(),
        "weer_opmerking" to binding.etWeerOpmerking.text?.toString(),
        "zicht_m" to binding.etZicht.text?.toString(),
        "temperatuur_c" to binding.etTemperatuur.text?.toString(),
        "luchtdruk_hpa" to binding.etLuchtdruk.text?.toString()
    )
}
