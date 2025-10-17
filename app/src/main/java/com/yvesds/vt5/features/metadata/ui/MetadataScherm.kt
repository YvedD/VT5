@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.CodeItem
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.features.telling.TellingScherm
import com.yvesds.vt5.features.telling.TellingSessionManager
import com.yvesds.vt5.utils.weather.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Metadata invoerscherm met verbeterde performance:
 * - Selectieve data-preloading (eerst codes.json, later site_species.json)
 * - Verminderde visuele blokkering
 * - Parallelle data verwerking
 */
class MetadataScherm : AppCompatActivity() {
    companion object {
        private const val TAG = "MetadataScherm"
        const val EXTRA_SELECTED_SOORT_IDS = "selected_soort_ids"
    }

    private lateinit var binding: SchermMetadataBinding
    private var snapshot: DataSnapshot = DataSnapshot()
    private var dataLoaded = false

    // Scope voor achtergrondtaken
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)
    private var backgroundLoadJob: Job? = null

    // Gekozen waarden
    private var gekozenTelpostId: String? = null
    private var gekozenBewolking: String? = null        // "0".."8"
    private var gekozenWindkracht: String? = null       // "0".."12"
    private var gekozenWindrichtingCode: String? = null // codes.value
    private var gekozenNeerslagCode: String? = null     // codes.value
    private var gekozenTypeTellingCode: String? = null  // codes.value

    // Startmoment van de header (epoch sec)
    private var startEpochSec: Long = System.currentTimeMillis() / 1000L

    private val requestLocationPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) onWeerAutoClicked()
        else Toast.makeText(this, "Locatierechten geweigerd.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // geen keyboard voor datum/tijd
        binding.etDatum.inputType = InputType.TYPE_NULL
        binding.etTijd.inputType = InputType.TYPE_NULL

        // pickers + defaults
        initDateTimePickers()
        prefillCurrentDateTime()

        // Acties
        binding.btnVerder.setOnClickListener { onVerderClicked() }
        binding.btnAnnuleer.setOnClickListener { finish() }
        binding.btnWeerAuto.setOnClickListener { ensureLocationPermissionThenFetch() }

        // Start het laden in stappen:
        // 1. Eerst de essentiële codes (snel)
        // 2. Later, terwijl de gebruiker bezig is, de rest van de data
        loadEssentialData()
    }

    /**
     * Eerste fase: laad alleen de noodzakelijke data voor het vullen van de dropdown menus
     * Dit zorgt voor een veel snellere initiële lading
     */
    private fun loadEssentialData() {
        uiScope.launch {
            try {
                // Check eerst of we al volledige data in cache hebben
                val cachedData = ServerDataCache.getCachedOrNull()
                if (cachedData != null) {
                    Log.d(TAG, "Using fully cached data")
                    snapshot = cachedData
                    initializeDropdowns()

                    // Start het laden van de volledige data in de achtergrond
                    scheduleBackgroundLoading()
                    return@launch
                }

                // Toon de progress dialog
                ProgressDialogHelper.withProgress(this@MetadataScherm, "Bezig met laden van gegevens...") {
                    // Laad de minimale data
                    val repo = ServerDataRepository(this@MetadataScherm)
                    val minimal = repo.loadMinimalData()
                    snapshot = minimal

                    withContext(Dispatchers.Main) {
                        initializeDropdowns()
                    }

                    // Start het laden van de volledige data in de achtergrond
                    scheduleBackgroundLoading()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading essential data: ${e.message}")
                Toast.makeText(this@MetadataScherm, "Fout bij laden essentiële gegevens", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Plant het laden van de volledige data in de achtergrond
     * terwijl de gebruiker bezig is met het formulier in te vullen
     */
    private fun scheduleBackgroundLoading() {
        // Cancel bestaande job als die er is
        backgroundLoadJob?.cancel()

        // Start nieuwe job met vertraging om eerst de UI te laten renderen
        backgroundLoadJob = uiScope.launch {
            try {
                // Korte vertraging zodat de UI eerst kan renderen
                delay(500)

                // Haal volledige data als die er nog niet is
                withContext(Dispatchers.IO) {
                    if (isActive) {
                        val fullData = ServerDataCache.getOrLoad(this@MetadataScherm)
                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                snapshot = fullData
                                Log.d(TAG, "Background data loading complete")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background data loading failed: ${e.message}")
                // Geen toast hier, het is een achtergrondtaak die de gebruiker niet hoeft te storen
            }
        }
    }

    /**
     * Initialiseert de dropdowns met de beschikbare gegevens
     */
    private fun initializeDropdowns() {
        bindTelpostDropdown()
        bindWeatherDropdowns()
        dataLoaded = true
    }

    /* ---------- Permissie → weer auto ---------- */

    private fun ensureLocationPermissionThenFetch() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) onWeerAutoClicked()
        else requestLocationPerms.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun onWeerAutoClicked() {
        // Toon subtiele visuele feedback
        binding.btnWeerAuto.isEnabled = false

        uiScope.launch {
            try {
                // 1) Locatie ophalen (off-main)
                val loc = withContext(Dispatchers.IO) { WeatherManager.getLastKnownLocation(this@MetadataScherm) }
                if (loc == null) {
                    Toast.makeText(this@MetadataScherm, "Geen locatie beschikbaar.", Toast.LENGTH_SHORT).show()
                    binding.btnWeerAuto.isEnabled = true
                    return@launch
                }

                // 2) Huidig weer ophalen (off-main)
                val cur = withContext(Dispatchers.IO) { WeatherManager.fetchCurrent(loc.latitude, loc.longitude) }
                if (cur == null) {
                    Toast.makeText(this@MetadataScherm, "Kon weer niet ophalen.", Toast.LENGTH_SHORT).show()
                    binding.btnWeerAuto.isEnabled = true
                    return@launch
                }

                // 3) Mapping naar UI
                val windLabel = WeatherManager.degTo16WindLabel(cur.windDirection10m)
                val windCodes = snapshot.codesByCategory["wind"].orEmpty()
                val valueByLabel = windCodes.associateBy(
                    { (it.tekst ?: "").uppercase(Locale.getDefault()) },
                    { it.value ?: "" }
                )
                val foundWindCode = valueByLabel[windLabel] ?: valueByLabel["N"] ?: "n"
                gekozenWindrichtingCode = foundWindCode
                binding.acWindrichting.setText(windLabel, false)

                val bft = WeatherManager.msToBeaufort(cur.windSpeed10m)
                gekozenWindkracht = bft.toString()
                val windForceDisplay = if (bft == 0) "<1bf" else "${bft}bf"
                binding.acWindkracht.setText(windForceDisplay, false)

                val achtsten = WeatherManager.cloudPercentToAchtsten(cur.cloudCover)
                gekozenBewolking = achtsten
                binding.acBewolking.setText("$achtsten/8", false)

                val rainCode = WeatherManager.precipitationToCode(cur.precipitation)
                gekozenNeerslagCode = rainCode
                val rainCodes = snapshot.codesByCategory["neerslag"].orEmpty()
                val rainLabelByValue = rainCodes.associateBy(
                    { it.value ?: "" },
                    { it.tekst ?: (it.value ?: "") }
                )
                val rainLabel = rainLabelByValue[rainCode] ?: rainCode
                binding.acNeerslag.setText(rainLabel, false)

                cur.temperature2m?.let { binding.etTemperatuur.setText(it.roundToInt().toString()) }
                val visMeters = WeatherManager.toVisibilityMeters(cur.visibility)
                visMeters?.let { binding.etZicht.setText(it.toString()) }
                cur.pressureMsl?.let { binding.etLuchtdruk.setText(it.roundToInt().toString()) }

                markWeatherAutoApplied()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather: ${e.message}")
                Toast.makeText(this@MetadataScherm, "Kon weer niet ophalen", Toast.LENGTH_SHORT).show()
                binding.btnWeerAuto.isEnabled = true
            }
        }
    }

    private fun markWeatherAutoApplied() {
        binding.btnWeerAuto.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#117CAF"))
        binding.btnWeerAuto.isEnabled = false
    }

    /* ---------------- Datum & Tijd ---------------- */

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
        startEpochSec = System.currentTimeMillis() / 1000L
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
            minValue = 0; maxValue = 23; value = startHour; wrapSelectorWheel = true
        }
        val minutePicker = NumberPicker(this).apply {
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
        // Sorteer en map telposten
        val sites = snapshot.sitesById.values
            .sortedBy { it.telpostnaam.lowercase(Locale.getDefault()) }

        val labels = sites.map { it.telpostnaam }
        val ids    = sites.map { it.telpostid }

        // Creëer adapter
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        // Stel adapter in
        binding.acTelpost.setAdapter(adapter)

        // Voeg listener toe
        binding.acTelpost.setOnItemClickListener { _, _, pos, _ ->
            gekozenTelpostId = ids[pos]
        }
    }

    private fun bindWeatherDropdowns() {
        // WINDRICHTING (veld == "wind")
        runCatching {
            val windCodes = getCodesForField("wind")
            val labels = windCodes.mapNotNull { it.tekst }
            val values = windCodes.map { it.value ?: "" }
            binding.acWindrichting.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            )
            binding.acWindrichting.setOnItemClickListener { _, _, pos, _ ->
                gekozenWindrichtingCode = values[pos]
            }
        }

        // BEWOLKING 0/8..8/8 → "0".."8"
        val cloudDisplays = (0..8).map { "$it/8" }
        val cloudValues   = (0..8).map { it.toString() }
        binding.acBewolking.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, cloudDisplays)
        )
        binding.acBewolking.setOnItemClickListener { _, _, pos, _ ->
            gekozenBewolking = cloudValues[pos]
        }

        // WINDKRACHT <1bf, 1..12bf → "0".."12"
        val windForceDisplays = buildList { add("<1bf"); addAll((1..12).map { "${it}bf" }) }
        val windForceValues   = buildList { add("0"); addAll((1..12).map { it.toString() }) }
        binding.acWindkracht.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, windForceDisplays)
        )
        binding.acWindkracht.setOnItemClickListener { _, _, pos, _ ->
            gekozenWindkracht = windForceValues[pos]
        }

        // NEERSLAG (veld == "neerslag")
        runCatching {
            val rainCodes = getCodesForField("neerslag")
            val labels = rainCodes.mapNotNull { it.tekst }
            val values = rainCodes.map { it.value ?: "" }
            binding.acNeerslag.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            )
            binding.acNeerslag.setOnItemClickListener { _, _, pos, _ ->
                gekozenNeerslagCode = values[pos]
            }
        }

        // TYPE TELLING (veld == "typetelling_trek") met filters op tekstkey
        runCatching {
            val all = getCodesForField("typetelling_trek")
            val filtered = all.filterNot { c ->
                val key = c.key ?: ""
                key.contains("_sound") ||
                        key.contains("_ringen") ||
                        key.startsWith("samplingrate_") ||
                        key.startsWith("gain_") ||
                        key.startsWith("verstoring_")
            }
            val labels = filtered.mapNotNull { it.tekst }
            val values = filtered.map { it.value ?: "" }
            binding.acTypeTelling.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            )
            binding.acTypeTelling.setOnItemClickListener { _, _, pos, _ ->
                gekozenTypeTellingCode = values[pos]
            }
        }
    }

    /** Haal codes per veld uit snapshot en sorteer op sortering (numeriek) + tekst. */
    private fun getCodesForField(field: String): List<CodeItem> {
        val items = snapshot.codesByCategory[field].orEmpty()
        return items.sortedWith(
            compareBy(
                { it.sortering?.toIntOrNull() ?: Int.MAX_VALUE },
                { it.tekst?.lowercase(Locale.getDefault()) ?: "" }
            )
        )
    }

    /* ---------------- Verder → counts_save (header) → SoortSelectieScherm ---------------- */

    private fun onVerderClicked() {
        val telpostId = gekozenTelpostId
        if (telpostId.isNullOrBlank()) {
            Toast.makeText(this, "Kies eerst een telpost.", Toast.LENGTH_SHORT).show()
            return
        }

        // Credentials ophalen
        val creds = TrektellenAuth.getSavedCredentials(this)
        val username = creds?.first
        val password = creds?.second
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, "Geen user (check login).", Toast.LENGTH_SHORT).show()
            return
        }

        // Keuze aan gebruiker: live-modus => eindtijd leeg
        AlertDialog.Builder(this)
            .setTitle("Live gaan?")
            .setMessage("Wil je deze telling in live-modus starten? (eindtijd blijft dan leeg)")
            .setPositiveButton("Ja (live)") { _, _ -> startTellingAndOpenSoortSelectie(liveMode = true, username = username, password = password) }
            .setNegativeButton("Nee") { _, _ -> startTellingAndOpenSoortSelectie(liveMode = false, username = username, password = password) }
            .setNeutralButton("Annuleer", null)
            .show()
    }

    // Launcher voor SoortSelectieScherm (multi-select)
    private val soortSelectieLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val ids = res.data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            gekozenTelpostId?.let { TellingSessionManager.setTelpost(it) }
            TellingSessionManager.setPreselectedSoorten(ids)
            startActivity(Intent(this, TellingScherm::class.java))
        }
    }

    private fun startTellingAndOpenSoortSelectie(liveMode: Boolean, username: String, password: String) {
        val telpostId = gekozenTelpostId ?: return

        uiScope.launch {
            try {
                ProgressDialogHelper.withProgress(this@MetadataScherm, "Bezig met voorbereiden...") {
                    // Laad soorten per telpost
                    val snapshot = ServerDataCache.getOrLoad(this@MetadataScherm)

                    // Verzamel geselecteerde soorten
                    val speciesForTelpost = snapshot.siteSpeciesBySite[telpostId]?.mapNotNull {
                        it.soortid
                    } ?: emptyList()

                    // Stel de telpost in
                    TellingSessionManager.setTelpost(telpostId)

                    // Stel de geselecteerde soorten in
                    TellingSessionManager.setPreselectedSoorten(speciesForTelpost)

                    // Start het selectiescherm
                    val intent = Intent(this@MetadataScherm, SoortSelectieScherm::class.java)
                    intent.putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)

                    // Optioneel: Als je deze waarden later nodig hebt, kun je ze als extra's meegeven
                    intent.putExtra("EXTRA_LIVE_MODE", liveMode)
                    intent.putExtra("EXTRA_USERNAME", username)
                    intent.putExtra("EXTRA_PASSWORD", password)

                    soortSelectieLauncher.launch(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing species selection: ${e.message}")
                Toast.makeText(this@MetadataScherm, "Fout bij voorbereiden soortenlijst", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Annuleer alle achtergrondtaken
        backgroundLoadJob?.cancel()
    }
}