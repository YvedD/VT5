@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.CodeItem
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.utils.weather.WeatherManager
import com.yvesds.vt5.net.StartTellingApi
import com.yvesds.vt5.net.TrektellenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.content.res.ColorStateList
import android.graphics.Color
import kotlin.math.roundToInt
import kotlinx.coroutines.async


class MetadataScherm : AppCompatActivity() {

    private lateinit var binding: SchermMetadataBinding
    private var snapshot: DataSnapshot = DataSnapshot()
    private var startEpochSec: Long = 0L

    // Gekozen waarden
    private var gekozenTelpostId: String? = null
    private var gekozenBewolking: String? = null        // "0".."8"
    private var gekozenWindkracht: String? = null       // "0".."12"
    private var gekozenWindrichtingCode: String? = null // codes.waarde
    private var gekozenNeerslagCode: String? = null     // codes.waarde
    private var gekozenTypeTellingCode: String? = null  // codes.waarde

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
        startEpochSec = System.currentTimeMillis() / 1000L

        // Off-main: snapshot laden en dropdowns binden
        lifecycleScope.launch {
            val repo = ServerDataRepository.getInstance(applicationContext)

            // parallelle, gerichte loads (IO)
            val sitesDeferred = async(Dispatchers.IO) { repo.loadSites() } // leest alleen sites.json (streaming)
            val windDeferred  = async(Dispatchers.IO) { repo.loadCodesFor("wind") }
            val rainDeferred  = async(Dispatchers.IO) { repo.loadCodesFor("neerslag") }
            val typeDeferred  = async(Dispatchers.IO) { repo.loadCodesFor("typetelling_trek") }

            // wacht per stuk en bind UI zodra beschikbaar (niet alles blokkeren)
            snapshot = snapshot.copy(
                sitesById = sitesDeferred.await(),
                codesByCategory = mapOf(
                    "wind" to windDeferred.await(),
                    "neerslag" to rainDeferred.await(),
                    "typetelling_trek" to typeDeferred.await()
                )
            )

            bindTelpostDropdown()
            bindWeatherDropdowns()
        }
// Acties
        binding.btnVerder.setOnClickListener {
            // 1) Validatie basis
            val telpostId = gekozenTelpostId
            if (telpostId.isNullOrBlank()) {
                Toast.makeText(this, "Kies eerst een telpost.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2) UI-waarden ophalen (strings!):
            val windrichtingLabel = binding.acWindrichting.text?.toString()?.trim().orEmpty()
            val windkrachtOnly    = (gekozenWindkracht ?: "").trim()          // "0".."12"
            val temperatuurC      = binding.etTemperatuur.text?.toString()?.trim().orEmpty() // server verwacht String
            val bewolkingOnly     = (gekozenBewolking ?: "").trim()           // "0".."8"
            val neerslagCode      = (gekozenNeerslagCode ?: "").trim()        // bv. "regen"
            val zichtMeters       = binding.etZicht.text?.toString()?.trim().orEmpty()       // geen decimalen
            val typetellingCode   = (gekozenTypeTellingCode ?: "all").trim()  // default "all"
            val telers            = binding.etTellers.text?.toString()?.trim().orEmpty()
            val weerOpmerking     = binding.etWeerOpmerking.text?.toString()?.trim().orEmpty()
            val opmerkingen       = binding.etOpmerkingen.text?.toString()?.trim().orEmpty()
            val luchtdrukRaw      = binding.etLuchtdruk.text?.toString()?.trim().orEmpty()    // we sturen String door

            // 3) Telling-id & eindtijd
            val tellingId = com.yvesds.vt5.VT5App.nextTellingId()
            val eindEpochSec = System.currentTimeMillis() / 1000L

            // 4) Envelope bouwen (metadata + lege data-array)
            val envelope = StartTellingApi.buildEnvelopeFromUi(
                tellingId = tellingId.toLong(),
                telpostId = telpostId,
                begintijdEpochSec = startEpochSec,   // bestaand veld in jouw klasse
                eindtijdEpochSec = eindEpochSec,
                windrichtingLabel = windrichtingLabel,
                windkrachtBftOnly = windkrachtOnly,
                temperatuurC = temperatuurC,
                bewolkingAchtstenOnly = bewolkingOnly,
                neerslagCode = neerslagCode,
                zichtMeters = zichtMeters,
                typetellingCode = typetellingCode,
                telers = telers,
                weerOpmerking = weerOpmerking,
                opmerkingen = opmerkingen,
                luchtdrukHpaRaw = luchtdrukRaw
            )

            // 5) Credentials (zoals jouw json-download flow: Basic Auth)
            val username = snapshot.currentUser?.userid
            val password = snapshot.currentUser?.password ?: ""
            if (username.isNullOrBlank()) {
                Toast.makeText(this, "Geen user (check login).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 6) Upload off-main
            binding.btnVerder.isEnabled = false
            lifecycleScope.launch {
                try {
                    val (ok, body) = com.yvesds.vt5.net.TrektellenApi.postCountsSave(
                        baseUrl = "https://trektellen.nl",
                        language = "dutch",         // server verwacht dit zo
                        versie = "1845",            // volgens jouw voorbeeld-URL
                        username = username,
                        password = password,
                        envelope = envelope         // List<ServerTellingEnvelope>
                    )

                    // Toon ALTIJD het volledige serverantwoord (voor debug & om onlineid te zien)
                    showFullServerResponseToast("TrektellenUpload", body)

                    if (ok) {
                        Toast.makeText(this@MetadataScherm, "Telling gestart (upload OK).", Toast.LENGTH_SHORT).show()
                        // TODO (optioneel): parse 'onlineid' uit 'body' en bewaren (prefs/DB) gekoppeld aan 'tellingId'
                    } else {
                        Toast.makeText(this@MetadataScherm, "Start mislukt.", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    binding.btnVerder.isEnabled = true
                }
            }
        }
        binding.btnAnnuleer.setOnClickListener { finish() }
        binding.btnWeerAuto.setOnClickListener { ensureLocationPermissionThenFetch() }
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
        lifecycleScope.launch {
            // 1) Locatie ophalen (off-main)
            val loc = withContext(Dispatchers.IO) { WeatherManager.getLastKnownLocation(this@MetadataScherm) }
            if (loc == null) {
                Toast.makeText(this@MetadataScherm, "Geen locatie beschikbaar.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 2) Huidig weer ophalen (off-main)
            val cur = withContext(Dispatchers.IO) { WeatherManager.fetchCurrent(loc.latitude, loc.longitude) }
            if (cur == null) {
                Toast.makeText(this@MetadataScherm, "Kon weer niet ophalen.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 3) Mapping naar UI

            // Windrichting: graden → 16-sectie label → code via codes("wind")
            val windLabel = WeatherManager.degTo16WindLabel(cur.windDirection10m)
            val windCodes = snapshot.codesByCategory["wind"].orEmpty()
            val valueByLabel = windCodes.associateBy(
                { (it.tekst ?: "").uppercase(Locale.getDefault()) },
                { it.value ?: "" }
            )
            val foundWindCode = valueByLabel[windLabel] ?: valueByLabel["N"] ?: "n"
            gekozenWindrichtingCode = foundWindCode
            binding.acWindrichting.setText(windLabel, false)

            // Windkracht (Beaufort uit m/s): "<1bf" = 0, anders "1bf".. "12bf"
            val bft = WeatherManager.msToBeaufort(cur.windSpeed10m)
            gekozenWindkracht = bft.toString()
            val windForceDisplay = if (bft == 0) "<1bf" else "${bft}bf"
            binding.acWindkracht.setText(windForceDisplay, false)

            // Bewolking: % → achtsten ("0".."8")
            val achtsten = WeatherManager.cloudPercentToAchtsten(cur.cloudCover)
            gekozenBewolking = achtsten
            binding.acBewolking.setText("$achtsten/8", false)

            // Neerslag: intensiteit → code; toon bijbehorend NL-label uit codes("neerslag")
            val rainCode = WeatherManager.precipitationToCode(cur.precipitation)
            gekozenNeerslagCode = rainCode
            val rainCodes = snapshot.codesByCategory["neerslag"].orEmpty()
            val rainLabelByValue = rainCodes.associateBy(
                { it.value ?: "" },
                { it.tekst ?: (it.value ?: "") }
            )
            val rainLabel = rainLabelByValue[rainCode] ?: rainCode
            binding.acNeerslag.setText(rainLabel, false)

            // Temperatuur (°C, int), Zicht (meters, int), Luchtdruk (hPa, int)
            cur.temperature2m?.let { binding.etTemperatuur.setText(it.roundToInt().toString()) }

            val visMeters = WeatherManager.toVisibilityMeters(cur.visibility)
            visMeters?.let { binding.etZicht.setText(it.toString()) }

            cur.pressureMsl?.let { binding.etLuchtdruk.setText(it.roundToInt().toString()) }

            // 4) Knop markeren: definitief blauw + disabled
            markWeatherAutoApplied()
        }
    }

    private fun markWeatherAutoApplied() {
        binding.btnWeerAuto.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#117CAF"))
        binding.btnWeerAuto.isEnabled = false
    }

    /* ---------------- Datum & Tijd ---------------- */

    private fun initDateTimePickers() {
        binding.etDatum.setOnClickListener { openDatePicker() }
        binding.etTijd.setOnClickListener { openTimeSpinnerDialog() } // 2x NumberPicker
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

    /** Custom tijdkiezer met 2 NumberPickers (uur + minuut) en overflow/underflow. */
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

        // BEWOLKING 0/8..8/8 -> "0".."8"
        val cloudDisplays = (0..8).map { "$it/8" }
        val cloudValues   = (0..8).map { it.toString() }
        binding.acBewolking.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, cloudDisplays)
        )
        binding.acBewolking.setOnItemClickListener { _, _, pos, _ ->
            gekozenBewolking = cloudValues[pos]
        }

        // WINDKRACHT <1bf, 1..12bf -> "0".."12"
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

        Toast.makeText(this, "Metadata geladen.", Toast.LENGTH_SHORT).show()
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
    private fun showFullServerResponseToast(tag: String, response: String) {
        // Log alles voor debug
        android.util.Log.d(tag, "Server response:\n$response")

        // Toasts snijden te lange teksten af; we knippen in stukken van max ~3500 chars
        val chunkSize = 3500
        if (response.length <= chunkSize) {
            Toast.makeText(this, response, Toast.LENGTH_LONG).show()
        } else {
            var i = 0
            while (i < response.length) {
                val end = (i + chunkSize).coerceAtMost(response.length)
                val part = response.substring(i, end)
                Toast.makeText(this, part, Toast.LENGTH_LONG).show()
                i = end
            }
        }
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
