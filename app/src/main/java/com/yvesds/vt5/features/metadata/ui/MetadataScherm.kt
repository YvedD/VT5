@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Metadatascherm : AppCompatActivity() {

    private lateinit var binding: SchermMetadataBinding

    /** Huidige serverdata (geladen off-main) */
    private var snapshot: DataSnapshot = DataSnapshot()

    // Interne gekozen waarden (UI → payload)
    private var gekozenBewolking: String? = null   // "0".."8"
    private var gekozenWindkracht: String? = null  // "0".."12"
    private var gekozenWindrichtingLabel: String? = null
    private var gekozenNeerslagLabel: String? = null
    private var gekozenTypeTellingLabel: String? = null

    // Telpost-keuze + gekoppelde info uit 'sites'
    private var gekozenTelpostId: String? = null        // "5177"
    private var gekozenTelpostNaam: String? = null      // "VoiceTally Testsite"
    private var gekozenRichtingNajaar: String? = null   // r1 (bv. "ZW")
    private var gekozenRichtingVoorjaar: String? = null // r2 (bv. "NO")
    private var gekozenTypeTelpost: String? = "1"       // app-breed default
    private var gekozenProtocolId: String? = null       // bv. "1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Geen soft keyboard voor tapbare datum/tijd velden
        binding.etDatum.inputType = InputType.TYPE_NULL
        binding.etTijd.inputType = InputType.TYPE_NULL

        // Pickers + defaults
        initDateTimePickers()
        prefillCurrentDateTime()

        // Off-main: snapshot laden en dan UI binden
        lifecycleScope.launch {
            val repo = ServerDataRepository(this@Metadatascherm)
            snapshot = withContext(Dispatchers.IO) { repo.loadAllFromSaf() }
            withContext(Dispatchers.Main) {
                bindDropdownsFromCodes()
                bindTelpostFromSites()
            }
        }

        // Acties
        binding.btnVerder.setOnClickListener {
            val payload = buildMetadataHeader()
            Toast.makeText(this, "OK: $payload", Toast.LENGTH_SHORT).show()
            // TODO: hier kan je valideren / navigeren
        }
        binding.btnAnnuleer.setOnClickListener { finish() }
    }

    /* =========================================================
     * Datum & tijd
     * ========================================================= */

    private fun initDateTimePickers() {
        binding.etDatum.setOnClickListener { openDatePicker() }
        binding.etTijd.setOnClickListener { openTimeSpinnerDialog() }
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

    /**
     * Custom tijd-chooser met 2 NumberPickers (uur + min), met rollover:
     * 59 → 00 ⇒ uur+1  (mod 24)
     * 00 → 59 ⇒ uur-1  (mod 24)
     */
    private fun openTimeSpinnerDialog() {
        // huidige waarden uit het veld (of now)
        val cal = Calendar.getInstance()
        var uur = cal.get(Calendar.HOUR_OF_DAY)
        var min = cal.get(Calendar.MINUTE)

        runCatching {
            val parts = binding.etTijd.text?.toString()?.split(":") ?: emptyList()
            if (parts.size == 2) {
                uur = parts[0].toInt()
                min = parts[1].toInt()
            }
        }

        // Layout met 2 pickers
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(8, 0, 8, 0)
        }

        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = uur
            setFormatter { v -> v.toString().padStart(2, '0') }
            layoutParams = lp
        }

        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = min
            setFormatter { v -> v.toString().padStart(2, '0') }
            layoutParams = lp
        }

        container.addView(hourPicker)
        container.addView(minutePicker)

        // Rollover logic
        var vorigeMin = min
        minutePicker.setOnValueChangedListener { _, oldVal, newVal ->
            // Detecteer wrap: 59 -> 00 (vooruit) of 00 -> 59 (achteruit)
            if (oldVal == 59 && newVal == 0) {
                hourPicker.value = (hourPicker.value + 1) % 24
            } else if (oldVal == 0 && newVal == 59) {
                hourPicker.value = (hourPicker.value + 23) % 24 // -1 mod 24
            }
            vorigeMin = newVal
        }

        AlertDialog.Builder(this)
            .setTitle("Kies tijd")
            .setView(container)
            .setPositiveButton("OK") { _: DialogInterface, _: Int ->
                val hh = hourPicker.value.toString().padStart(2, '0')
                val mm = minutePicker.value.toString().padStart(2, '0')
                binding.etTijd.setText("$hh:$mm")
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /* =========================================================
     * Dropdowns uit codes
     * ========================================================= */

    private fun bindDropdownsFromCodes() {
        fun labelsFor(category: String): List<String> {
            val items = snapshot.codesByCategory[category].orEmpty()
            return items
                .sortedWith(
                    compareBy(
                        { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                        { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                    )
                )
                .mapNotNull { it.tekst }
        }

        // Zelfde als labelsFor, maar met blokkade op bepaalde tekstkey-patronen
        fun filteredLabelsFor(category: String): List<String> {
            val blockedSubstrings = listOf("_sound", "_ringen", "samplingrate_", "gain_", "verstoring_")
            val items = snapshot.codesByCategory[category].orEmpty()
                .filter { item ->
                    val key = item.tekstkey?.lowercase(Locale.getDefault()) ?: return@filter true
                    blockedSubstrings.none { key.contains(it) }
                }
            return items
                .sortedWith(
                    compareBy(
                        { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                        { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
                    )
                )
                .mapNotNull { it.tekst }
        }

        // Windrichting (categorie "wind")
        labelsFor("wind").also { windLabels ->
            if (windLabels.isNotEmpty()) {
                binding.acWindrichting.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, windLabels)
                )
                binding.acWindrichting.setOnItemClickListener { parent, _, pos, _ ->
                    gekozenWindrichtingLabel = parent.getItemAtPosition(pos)?.toString()
                }
            }
        }

        // Neerslag (categorie "neerslag")
        labelsFor("neerslag").also { rainLabels ->
            if (rainLabels.isNotEmpty()) {
                binding.acNeerslag.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, rainLabels)
                )
                binding.acNeerslag.setOnItemClickListener { parent, _, pos, _ ->
                    gekozenNeerslagLabel = parent.getItemAtPosition(pos)?.toString()
                }
            }
        }

        // Type telling (categorie "typetelling_trek") met filtering op tekstkey
        filteredLabelsFor("typetelling_trek").also { typeLabels ->
            if (typeLabels.isNotEmpty()) {
                binding.acTypeTelling.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, typeLabels)
                )
                binding.acTypeTelling.setOnItemClickListener { parent, _, pos, _ ->
                    gekozenTypeTellingLabel = parent.getItemAtPosition(pos)?.toString()
                }
            }
        }

        // Bewolking 0/8..8/8 → intern "0".."8"
        val cloudDisplays = (0..8).map { "$it/8" }
        val cloudValues = (0..8).map { it.toString() }
        binding.acBewolking.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, cloudDisplays)
        )
        binding.acBewolking.setOnItemClickListener { _, _, pos, _ ->
            gekozenBewolking = cloudValues[pos]
        }

        // Windkracht "<1bf", "1bf".. "12bf" → intern "0".."12"
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
    }

    /* =========================================================
     * Telpost uit 'sites'
     * ========================================================= */

    private fun bindTelpostFromSites() {
        val sites = snapshot.sitesById.values.toList()
        if (sites.isEmpty()) return

        data class Row(
            val id: String,
            val naam: String,
            val r1: String?,
            val r2: String?,
            val typetelpost: String?,
            val protocolid: String?
        )

        val rows: List<Row> = sites.map { s ->
            Row(
                id = (s.telpostid ?: "").toString(),
                naam = s.telpostnaam?.takeIf { it.isNotBlank() } ?: (s.telpostid ?: "").toString(),
                r1 = s.r1,
                r2 = s.r2,
                typetelpost = s.typetelpost ?: "1",
                protocolid = s.protocolid
            )
        }.sortedBy { it.naam.lowercase(Locale.getDefault()) }

        val labels = rows.map { it.naam }

        binding.acTelpost.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )

        binding.acTelpost.setOnItemClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos) ?: return@setOnItemClickListener
            gekozenTelpostId = row.id
            gekozenTelpostNaam = row.naam
            gekozenRichtingNajaar = row.r1
            gekozenRichtingVoorjaar = row.r2
            gekozenTypeTelpost = row.typetelpost // default blijft "1"
            gekozenProtocolId = row.protocolid
        }

        // (optioneel) Set default eerste item:
        // rows.firstOrNull()?.let { first ->
        //     binding.acTelpost.setText(first.naam, false)
        //     gekozenTelpostId = first.id
        //     gekozenTelpostNaam = first.naam
        //     gekozenRichtingNajaar = first.r1
        //     gekozenRichtingVoorjaar = first.r2
        //     gekozenTypeTelpost = first.typetelpost
        //     gekozenProtocolId = first.protocolid
        // }
    }

    /* =========================================================
     * Payload
     * ========================================================= */

    private fun buildMetadataHeader(): Map<String, String?> = mapOf(
        "datum" to binding.etDatum.text?.toString(),
        "tijd" to binding.etTijd.text?.toString(),

        // Telpost
        "telpost_id" to gekozenTelpostId,
        "telpost_naam" to gekozenTelpostNaam,
        "r1_najaar" to gekozenRichtingNajaar,
        "r2_voorjaar" to gekozenRichtingVoorjaar,
        "typetelpost" to gekozenTypeTelpost,     // praktisch "1"
        "protocolid" to gekozenProtocolId,

        // Weer
        "windrichting_label" to gekozenWindrichtingLabel,
        "windkracht_bft" to gekozenWindkracht,
        "bewolking_achtsten" to gekozenBewolking,
        "neerslag_label" to gekozenNeerslagLabel,
        "weer_opmerking" to binding.etWeerOpmerking.text?.toString(),
        "temperatuur_c" to binding.etTemperatuur.text?.toString(),
        "zicht_m" to binding.etZicht.text?.toString(),
        "luchtdruk_hpa" to binding.etLuchtdruk.text?.toString(),

        // Overige
        "typetelling_label" to gekozenTypeTellingLabel,
        "tellers" to binding.etTellers.text?.toString()
    )
}
