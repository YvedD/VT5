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
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.CodeItemSlim
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.features.telling.TellingScherm
import com.yvesds.vt5.features.telling.TellingSessionManager
import com.yvesds.vt5.net.StartTellingApi
import com.yvesds.vt5.net.TrektellenApi
import com.yvesds.vt5.net.ServerTellingEnvelope
import com.yvesds.vt5.utils.weather.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Metadata invoerscherm met verbeterde performance:
 * - Selectieve data-preloading (eerst codes.json, later site_species.json)
 * - Verminderde visuele blokkering
 * - Parallelle data verwerking
 *
 * Belangrijk: deze versie stuurt bij "Verder" eerst een counts_save naar de server
 * (StartTellingApi.buildEnvelopeFromUi + TrektellenApi.postCountsSave). Bij succes
 * wordt het teruggegeven onlineId opgeslagen in prefs onder key PREF_ONLINE_ID en
 * pas dan wordt het SoortSelectieScherm gestart.
 *
 * Aanpassing: bij het aanmaken van de telling zorgen we dat "eindtijd" in de
 * verstuurde envelope een lege string ("") is (dus live-mode gedrag voor eindtijd).
 * Tevens wordt de gemaakte envelope (zonder data) persist in prefs opgeslagen
 * zodat TellingScherm bij 'Afronden' die metadata kan hergebruiken.
 */
class MetadataScherm : AppCompatActivity() {
    companion object {
        private const val TAG = "MetadataScherm"

        // Key for onlineId stored in prefs (used elsewhere, e.g. TellingScherm)
        private const val PREF_ONLINE_ID = "pref_online_id"
        // Key for internal telling id used later by DataUploader / TellingScherm
        private const val PREF_TELLING_ID = "pref_telling_id"
        // Key for saved envelope JSON (metadata only, no data)
        private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"
    }

    private lateinit var binding: SchermMetadataBinding
    private var snapshot: DataSnapshot = DataSnapshot()
    private var dataLoaded = false

    // Scope voor achtergrondtaken
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)
    private var backgroundLoadJob: Job? = null

    // SAF helper for alias manager access
    private lateinit var saf: SaFStorageHelper

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
        else Toast.makeText(this, getString(R.string.metadata_error_location_denied), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SAF helper
        saf = SaFStorageHelper(this)

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
     *
     * OPTIMIZATION: Added progress feedback during minimal load
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

                // OPTIMIZATION: Detailed progress feedback during load
                val progressDialog = ProgressDialogHelper.show(this@MetadataScherm, "Codes laden...")
                try {
                    // Laad de minimale data
                    val repo = ServerDataRepository(this@MetadataScherm)

                    // Update progress: loading codes
                    withContext(Dispatchers.Main) {
                        ProgressDialogHelper.updateMessage(progressDialog, "Telposten laden...")
                    }

                    val minimal = repo.loadMinimalData()
                    snapshot = minimal

                    // Update progress: initializing UI
                    withContext(Dispatchers.Main) {
                        ProgressDialogHelper.updateMessage(progressDialog, "Interface voorbereiden...")
                        initializeDropdowns()
                    }

                    // Start het laden van de volledige data in de achtergrond
                    scheduleBackgroundLoading()
                } finally {
                    progressDialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading essential data: ${e.message}")
                Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_loading_data), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Plant het laden van de volledige data in de achtergrond
     * terwijl de gebruiker bezig is met het formulier in te vullen
     *
     * Performance: Gebruikt low-priority dispatcher en delay om UI responsiveness te behouden
     *
     * UPDATE: Laadt nu ook de alias index voor SoortSelectieScherm, zodat de complete
     * soortenlijst (alle species uit site_species.json) beschikbaar is voor snelle toegang.
     */
    private fun scheduleBackgroundLoading() {
        // Cancel bestaande job als die er is
        backgroundLoadJob?.cancel()

        // Start nieuwe job met vertraging om eerst de UI te laten renderen
        backgroundLoadJob = uiScope.launch {
            try {
                // Korte vertraging zodat de UI eerst kan renderen
                delay(50)

                // Haal volledige data als die er nog niet is
                // Performance: gebruik IO dispatcher voor file I/O operaties
                withContext(Dispatchers.IO) {
                    if (isActive) {
                        val fullData = ServerDataCache.getOrLoad(this@MetadataScherm)
                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                snapshot = fullData
                                Log.d(TAG, "Background data loading complete - cache warmed for next screen")
                            }
                        }

                        // Also preload alias index for SoortSelectieScherm
                        // This ensures the complete species list is ready
                        if (isActive) {
                            try {
                                AliasManager.ensureIndexLoadedSuspend(this@MetadataScherm, saf)
                                Log.d(TAG, "Alias index preloaded in background - ready for species selection")
                            } catch (ex: Exception) {
                                Log.w(TAG, "Alias index preload failed (non-critical): ${ex.message}")
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
                    Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_no_location), Toast.LENGTH_SHORT).show()
                    binding.btnWeerAuto.isEnabled = true
                    return@launch
                }

                // 2) Huidig weer ophalen (off-main)
                val cur = withContext(Dispatchers.IO) { WeatherManager.fetchCurrent(loc.latitude, loc.longitude) }
                if (cur == null) {
                    Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_weather_fetch), Toast.LENGTH_SHORT).show()
                    binding.btnWeerAuto.isEnabled = true
                    return@launch
                }

                // 3) Mapping naar UI
                val windLabel = WeatherManager.degTo16WindLabel(cur.windDirection10m)
                val windCodes = snapshot.codesByCategory["wind"].orEmpty()
                
                // Map abbreviated labels to code values (N->n, NNO->nno, etc.)
                val windLabelLower = windLabel.lowercase(Locale.getDefault())
                val codeForLabel = windCodes.find { it.value == windLabelLower } 
                    ?: windCodes.find { it.value == "n" } // fallback to North
                
                gekozenWindrichtingCode = codeForLabel?.value ?: "n"
                binding.acWindrichting.setText(codeForLabel?.text ?: "Noord", false)

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
                    { it.value },
                    { it.text }
                )
                val rainLabel = rainLabelByValue[rainCode] ?: rainCode
                binding.acNeerslag.setText(rainLabel, false)

                cur.temperature2m?.let { binding.etTemperatuur.setText(it.roundToInt().toString()) }
                val visMeters = WeatherManager.toVisibilityMeters(cur.visibility)
                visMeters?.let { binding.etZicht.setText(it.toString()) }
                cur.pressureMsl?.let { binding.etLuchtdruk.setText(it.roundToInt().toString()) }

                // Bouw een weer-samenvatting voor het opmerkingenveld
                val windDisplayText = codeForLabel?.text ?: "Noord"
                val weerSamenvatting = buildString {
                    append("Auto: ")
                    cur.temperature2m?.let { append("${it.roundToInt()}°C, ") }
                    append("$windDisplayText ${windForceDisplay}, ")
                    append("bewolking ${achtsten}/8")
                    if (rainCode != "geen") append(", $rainLabel")
                }
                binding.etWeerOpmerking.setText(weerSamenvatting)

                markWeatherAutoApplied()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather: ${e.message}")
                Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_weather_fetch_short), Toast.LENGTH_SHORT).show()
                binding.btnWeerAuto.isEnabled = true
            }
        }
    }

    private fun markWeatherAutoApplied() {
        binding.btnWeerAuto.backgroundTintList = ColorStateList.valueOf("#117CAF".toColorInt())
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
        // Start altijd met de huidige tijd (ongeacht eerder ingevulde waarde)
        val cal = Calendar.getInstance()
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
            .setTitle(getString(R.string.metadata_dialog_time_title))
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val hh = hourPicker.value.toString().padStart(2, '0')
                val mm = minutePicker.value.toString().padStart(2, '0')
                binding.etTijd.setText("$hh:$mm")
            }
            .setNegativeButton("Annuleer", null)
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
            val labels = windCodes.map { it.text }
            val values = windCodes.map { it.value }
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
            val labels = rainCodes.map { it.text }
            val values = rainCodes.map { it.value }
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
            val labels = filtered.map { it.text }
            val values = filtered.map { it.value }
            binding.acTypeTelling.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            )
            binding.acTypeTelling.setOnItemClickListener { _, _, pos, _ ->
                gekozenTypeTellingCode = values[pos]
            }
        }
    }

    /** Haal codes per veld uit snapshot en sorteer op tekst. */
    private fun getCodesForField(field: String): List<CodeItemSlim> {
        val items = snapshot.codesByCategory[field].orEmpty()
        return items.sortedBy { it.text.lowercase(Locale.getDefault()) }
    }

    /* ---------------- Verder → counts_save (header) → SoortSelectieScherm ---------------- */

    private fun onVerderClicked() {
        val telpostId = gekozenTelpostId
        if (telpostId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.metadata_error_no_telpost), Toast.LENGTH_SHORT).show()
            return
        }

        // Credentials ophalen
        val creds = TrektellenAuth.getSavedCredentials(this)
        val username = creds?.first
        val password = creds?.second
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.metadata_error_no_credentials), Toast.LENGTH_SHORT).show()
            return
        }

        // Start directly in live mode (no dialog)
        startTellingAndOpenSoortSelectie(username = username, password = password)
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

    /**
     * Start telling:
     *  - Bouw envelope met StartTellingApi
     *  - POST via TrektellenApi.postCountsSave
     *  - Bij succes: parse onlineId en opslaan in prefs (PREF_ONLINE_ID)
     *  - Daarna: stel session (telpost + preselected soorten) in en open SoortSelectieScherm
     *
     * Als counts_save faalt wordt er een foutmelding getoond en wordt het scherm NIET geopend.
     */
    private fun startTellingAndOpenSoortSelectie(username: String, password: String) {
        val telpostId = gekozenTelpostId ?: return

        uiScope.launch {
            try {
                ProgressDialogHelper.withProgress(this@MetadataScherm, getString(R.string.metadata_preparing)) {
                    // Zorg dat volledige snapshot beschikbaar is
                    val snapshot = ServerDataCache.getOrLoad(this@MetadataScherm)

                    // tellingId: gebruik VT5App.nextTellingId() (advances counter)
                    val tellingIdLong = try {
                        VT5App.nextTellingId().toLong()
                    } catch (ex: Exception) {
                        // Fallback: currentTime seconds as id (shouldn't happen)
                        Log.w(TAG, "nextTellingId->Long failed: ${ex.message}")
                        (System.currentTimeMillis() / 1000L)
                    }

                    // Gebruik de huidige gekozen datum/tijd (prefilled/edited)
                    val begintijdEpoch = computeBeginEpochSec()

                    // FORCE: Eindtijd altijd leeg string in envelope (we pass liveMode = true)
                    val eindtijdEpoch = 0L

                    // Prepare values for StartTellingApi
                    // Use the chosen wind direction CODE if available, otherwise fallback to displayed label.
                    val windrichtingForServer = gekozenWindrichtingCode?.takeIf { it.isNotBlank() }
                        ?: binding.acWindrichting.text?.toString()?.takeIf { it.isNotBlank() } ?: ""

                    val windkrachtBft = gekozenWindkracht ?: ""
                    val temperatuurC = binding.etTemperatuur.text?.toString()?.trim().orEmpty()
                    val bewolkingAchtsten = gekozenBewolking ?: ""
                    val neerslagCode = gekozenNeerslagCode ?: ""
                    val zichtMeters = binding.etZicht.text?.toString()?.trim().orEmpty()
                    val typetellingCode = gekozenTypeTellingCode ?: ""

                    // Teller(s): fallback to empty string (optional field not in current UI)
                    val tellersFromUi = ""

                    val weerOpmerking = binding.etWeerOpmerking.text?.toString()?.trim().orEmpty()
                    val opmerkingen = "" // optional
                    val luchtdrukHpaRaw = binding.etLuchtdruk.text?.toString()?.trim().orEmpty()

                    // Build envelope (list of 1) -- pass liveMode = true so eindtijd == ""
                    val envelope = StartTellingApi.buildEnvelopeFromUi(
                        tellingId = tellingIdLong,
                        telpostId = telpostId,
                        begintijdEpochSec = begintijdEpoch,
                        eindtijdEpochSec = eindtijdEpoch,
                        windrichtingLabel = windrichtingForServer,
                        windkrachtBftOnly = windkrachtBft,
                        temperatuurC = temperatuurC,
                        bewolkingAchtstenOnly = bewolkingAchtsten,
                        neerslagCode = neerslagCode,
                        zichtMeters = zichtMeters,
                        typetellingCode = typetellingCode,
                        telers = tellersFromUi,
                        weerOpmerking = weerOpmerking,
                        opmerkingen = opmerkingen,
                        luchtdrukHpaRaw = luchtdrukHpaRaw,
                        liveMode = true
                    )

                    // Post counts_save
                    val baseUrl = "https://trektellen.nl"
                    val language = "dutch"
                    val versie = "1845"

                    val (ok, resp) = withContext(Dispatchers.IO) {
                        try {
                            TrektellenApi.postCountsSave(baseUrl, language, versie, username, password, envelope)
                        } catch (ex: Exception) {
                            Log.w(TAG, "postCountsSave exception: ${ex.message}", ex)
                            false to (ex.message ?: "exception")
                        }
                    }

                    if (!ok) {
                        // show failure dialog with server response and do not proceed
                        Log.w(TAG, "counts_save failed: $resp")
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MetadataScherm)
                                .setTitle(getString(R.string.metadata_error_start_failed_title))
                                .setMessage(getString(R.string.metadata_error_start_failed_msg, resp))
                                .setPositiveButton(getString(R.string.dlg_ok), null)
                                .show()
                        }
                        return@withProgress
                    }

                    // Try to extract onlineId from response
                    val onlineId = parseOnlineIdFromResponse(resp)
                    if (onlineId.isNullOrBlank()) {
                        // Could not parse onlineId — treat as error
                        Log.w(TAG, "Could not parse onlineId from response: $resp")
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@MetadataScherm)
                                .setTitle(getString(R.string.metadata_error_unclear_title))
                                .setMessage(getString(R.string.metadata_error_unclear_msg, resp))
                                .setPositiveButton(getString(R.string.dlg_ok), null)
                                .show()
                        }
                        return@withProgress
                    }

                    // Persist onlineId and tellingId in prefs so TellingScherm / DataUploader can use them
                    val prefs = getSharedPreferences("vt5_prefs", MODE_PRIVATE)
                    prefs.edit {
                        putString(PREF_ONLINE_ID, onlineId)
                        putString(PREF_TELLING_ID, tellingIdLong.toString())
                    }

                    // Initialize per-telling record counter starting at 1
                    try {
                        prefs.edit {
                            putLong("pref_next_record_id_$tellingIdLong", 1L)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed initializing next record id: ${ex.message}")
                    }

                    // Persist the envelope JSON (metadata only, without data) for later reuse at Afronden
                    try {
                        val envelopeJson = VT5App.json.encodeToString(ListSerializer(ServerTellingEnvelope.serializer()), envelope)
                        prefs.edit {
                            putString(PREF_SAVED_ENVELOPE_JSON, envelopeJson)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed saving envelope JSON to prefs: ${ex.message}")
                    }

                    // Also set session state and preselected species (as before)
                    val speciesForTelpost = snapshot.siteSpeciesBySite[telpostId]?.mapNotNull { it.soortid } ?: emptyList()
                    TellingSessionManager.setTelpost(telpostId)
                    TellingSessionManager.setPreselectedSoorten(speciesForTelpost)

                    // Success — open SoortSelectieScherm
                    // Note: snapshot is already loaded and cached, so SoortSelectieScherm will use fast-path
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MetadataScherm, getString(R.string.metadata_success_started, onlineId), Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@MetadataScherm, SoortSelectieScherm::class.java)
                        intent.putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
                        intent.putExtra("EXTRA_LIVE_MODE", true)
                        intent.putExtra("EXTRA_USERNAME", username)
                        intent.putExtra("EXTRA_PASSWORD", password)
                        soortSelectieLauncher.launch(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing species selection or starting counts_save: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MetadataScherm, getString(R.string.metadata_error_preparing, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Compute epoch seconds from UI date+time inputs. Fallback to startEpochSec if parse fails. */
    private fun computeBeginEpochSec(): Long {
        runCatching {
            val dateStr = binding.etDatum.text?.toString()?.trim().orEmpty() // "YYYY-MM-DD"
            val timeStr = binding.etTijd.text?.toString()?.trim().orEmpty() // "HH:mm"
            if (dateStr.isBlank() || timeStr.isBlank()) return startEpochSec

            val dt = "${dateStr} ${timeStr}"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val parsed = sdf.parse(dt) ?: return startEpochSec
            return parsed.time / 1000L
        }.onFailure {
            Log.w(TAG, "computeBeginEpochSec failed: ${it.message}")
        }
        return startEpochSec
    }

    /**
     * Tries to find an onlineId in server response.
     * Accepts JSON object/array with "onlineid" key or a plain numeric id inside the response.
     */
    private fun parseOnlineIdFromResponse(resp: String): String? {
        try {
            val el = VT5App.json.parseToJsonElement(resp)
            // If it's an array take first object
            val obj = when {
                el.jsonArrayOrNull() != null && el.jsonArray.size > 0 -> el.jsonArray[0]
                el.jsonObjectOrNull() != null -> el
                else -> null
            }
            if (obj != null) {
                val jo = if (obj.jsonObjectOrNull() != null) obj.jsonObject else el.jsonArray[0].jsonObject
                // Common keys
                listOf("onlineid", "onlineId", "id", "result", "online_id").forEach { key ->
                    if (jo.containsKey(key)) {
                        val v = jo[key]?.toString()?.replace("\"", "") ?: ""
                        if (v.isNotBlank()) return v
                    }
                }
            }
        } catch (_: Exception) {
            // ignore JSON parse errors and fall back to regex
        }

        // Fallback: find a sequence of 4-10 digits in the response
        val regex = Regex("""\b(\d{4,10})\b""")
        val m = regex.find(resp)
        return m?.groups?.get(1)?.value
    }

    // Extension helpers to check json element types without crashing if not present
    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
        runCatching { this.jsonArray }.getOrNull()
    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        runCatching { this.jsonObject }.getOrNull()



    override fun onDestroy() {
        super.onDestroy()
        // Annuleer alle achtergrondtaken
        backgroundLoadJob?.cancel()
    }
}