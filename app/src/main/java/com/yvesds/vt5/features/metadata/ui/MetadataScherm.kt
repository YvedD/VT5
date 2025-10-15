@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.metadata.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.databinding.SchermMetadataBinding
import com.yvesds.vt5.features.serverdata.model.CodeItem
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.utils.weather.WeatherManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.content.res.ColorStateList
import kotlin.math.roundToInt

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

    // starttijd telling (voor metadata)
    private var startEpochSec: Long = System.currentTimeMillis() / 1000L

    private val httpClient by lazy { OkHttpClient() }
    private val jsonMedia by lazy { "application/json; charset=utf-8".toMediaType() }

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
            val repo = ServerDataRepository(this@MetadataScherm)
            snapshot = withContext(Dispatchers.IO) { repo.loadAllFromSaf() }
            bindTelpostDropdown()
            bindWeatherDropdowns()
        }

        // Acties
        binding.btnVerder.setOnClickListener { onVerderClicked() }
        binding.btnAnnuleer.setOnClickListener { finish() }
        binding.btnWeerAuto.setOnClickListener { ensureLocationPermissionThenFetch() }
    }

    /* ---------- VERDER → upload metadata ---------- */

    private fun onVerderClicked() {
        val telpostId = gekozenTelpostId
        if (telpostId.isNullOrBlank()) {
            Toast.makeText(this, "Kies eerst een telpost.", Toast.LENGTH_SHORT).show()
            return
        }

        // UI-waarden
        val windrichtingLabel = binding.acWindrichting.text?.toString() ?: ""
        val windkrachtOnly    = gekozenWindkracht ?: ""
        val temperatuurC      = (binding.etTemperatuur.text?.toString() ?: "").trim()
        val bewolkingOnly     = gekozenBewolking ?: ""
        val neerslagCode      = gekozenNeerslagCode ?: ""
        val zichtMeters       = (binding.etZicht.text?.toString() ?: "").trim()
        val typetellingCode   = gekozenTypeTellingCode ?: ""
        val tellers           = binding.etTellers.text?.toString() ?: ""
        val weerOpmerking     = binding.etWeerOpmerking.text?.toString() ?: ""
        val opmerkingen       = binding.etOpmerkingen.text?.toString() ?: ""
        val luchtdrukRaw      = (binding.etLuchtdruk.text?.toString() ?: "").trim()

        val username = com.yvesds.vt5.core.secure.CredentialsStore.getUsername(applicationContext)
        val password = com.yvesds.vt5.core.secure.CredentialsStore.getPassword(applicationContext)

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, "Geen login gevonden. Ga naar Installatie en log in.", Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }

        // telling-id en eindtijd
        val tellingId = VT5App.nextTellingId()
        val eindEpochSec = System.currentTimeMillis() / 1000L

        // Bouw JSON-array payload (1 item) exact zoals server verwacht (alles als String)
        val bodyString = buildCountsSaveJson(
            externId = "Android App VT5",
            timezoneId = "Europe/Brussels",
            bron = "4",
            idLocal = "", // _id leeg bij init
            tellingId = tellingId.toString(),
            telpostId = telpostId,
            beginEpoch = startEpochSec.toString(),
            eindEpoch = eindEpochSec.toString(),
            tellers = tellers,
            weer = weerOpmerking,
            windrichting = windrichtingLabel,     // label (bv. "WNW")
            windkracht = windkrachtOnly,          // "0".."12"
            temperatuur = temperatuurC.noDecimals(),
            bewolking = bewolkingOnly,            // "0".."8"
            bewolkinghoogte = "",
            neerslag = neerslagCode,              // bv. "regen"
            duurneerslag = "",
            zicht = zichtMeters.noDecimals(),
            tellersactief = "",
            tellersaanwezig = "",
            typetelling = typetellingCode,        // uit codes.json (bv. "all")
            metersnet = "",
            geluid = "",
            opmerkingen = opmerkingen,
            onlineId = "",                        // leeg bij init; server geeft dit terug
            hydro = "",
            hpa = luchtdrukRaw.take(4),           // eerste 4 cijfers
            equipment = "",
            uuid = "Trektellen_Android_VT5_${System.currentTimeMillis()}",
            uploadTs = nowTimestamp(),
            nrec = "0",
            nsoort = "0",
            userid = user.userid // extra veld; sommige backends verwachten dit
        )

        // Fire off-main
        lifecycleScope.launch {
            binding.btnVerder.isEnabled = false
            Toast.makeText(this@MetadataScherm, "Metadata verzenden…", Toast.LENGTH_SHORT).show()
            val (ok, respText) = withContext(Dispatchers.IO) {
                postCountsSave(
                    baseUrl = "https://trektellen.nl",
                    language = "dutch",
                    versie = "1845",
                    username = username,
                    password = password,
                    bodyJson = bodyString
                )
            }

            // Altijd wegschrijven naar serverdata/… zodat we kunnen debuggen
            withContext(Dispatchers.IO) {
                saveDebugResponseToServerdata(respText)
            }

            // Feedback
            val toastText = if (ok) {
                "Telling gestart! Server antwoord: ${respText.truncate(800)}"
            } else {
                "Start mislukt: ${respText.truncate(800)}"
            }
            Toast.makeText(this@MetadataScherm, toastText, Toast.LENGTH_LONG).show()

            binding.btnVerder.isEnabled = true
        }
    }

    /** POST /api/counts_save met Basic Auth. */
    private fun postCountsSave(
        baseUrl: String,
        language: String,
        versie: String,
        username: String,
        password: String,
        bodyJson: String
    ): Pair<Boolean, String> {
        val url = "$baseUrl/api/counts_save?language=$language&versie=$versie"
        val reqBody: RequestBody = bodyJson.toRequestBody(jsonMedia)
        val auth = Credentials.basic(username, password, Charsets.UTF_8)

        val req = Request.Builder()
            .url(url)
            .post(reqBody)
            .addHeader("Authorization", auth)
            .addHeader("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                (resp.isSuccessful) to txt
            }
        } catch (e: Exception) {
            false to ("EXCEPTION: ${e.message}")
        }
    }

    /** Debug dump van serverantwoord in Documents/VT5/serverdata/… */
    private fun saveDebugResponseToServerdata(text: String) {
        try {
            val saf = SaFStorageHelper(this)
            val vt5Root = saf.getVt5DirIfExists() ?: return
            val serverdata = vt5Root.findFile("serverdata") ?: vt5Root.createDirectory("serverdata")
            serverdata ?: return
            val fname = "counts_save_response_${System.currentTimeMillis()}.json"
            val df: DocumentFile = serverdata.createFile("application/json", fname) ?: return
            contentResolver.openOutputStream(df.uri)?.use { out ->
                out.write(text.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // stil debug-bestand — geen crash risk
        }
    }

    private fun String.truncate(n: Int): String =
        if (length <= n) this else substring(0, n) + "…"

    private fun String.noDecimals(): String {
        val t = trim()
        if (t.isEmpty()) return ""
        // strip eventuele . of , en neem enkel integer-deel
        val dot = t.indexOf('.')
        val comma = t.indexOf(',')
        val cut = listOf(dot, comma).filter { it >= 0 }.minOrNull() ?: -1
        return if (cut >= 0) t.substring(0, cut) else t
    }

    private fun nowTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(System.currentTimeMillis())
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

        // TYPE TELLING (veld == "typetelling_trek") met filters op key
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

    /* ---------------- Payload (voor debug/toast) ---------------- */

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

    /* ---------------- JSON builder ---------------- */

    private fun buildCountsSaveJson(
        externId: String,
        timezoneId: String,
        bron: String,
        idLocal: String,
        tellingId: String,
        telpostId: String,
        beginEpoch: String,
        eindEpoch: String,
        tellers: String,
        weer: String,
        windrichting: String,
        windkracht: String,
        temperatuur: String,
        bewolking: String,
        bewolkinghoogte: String,
        neerslag: String,
        duurneerslag: String,
        zicht: String,
        tellersactief: String,
        tellersaanwezig: String,
        typetelling: String,
        metersnet: String,
        geluid: String,
        opmerkingen: String,
        onlineId: String,
        hydro: String,
        hpa: String,
        equipment: String,
        uuid: String,
        uploadTs: String,
        nrec: String,
        nsoort: String,
        userid: String?
    ): String {
        // Bouw een string zonder externe models om conflicts te vermijden.
        // JSON array met één object, veldvolgorde vergelijkbaar met je voorbeeld.
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val sb = StringBuilder(2048)
        sb.append("[{")
        sb.append("\"externid\":\"").append(esc(externId)).append("\",")
        sb.append("\"timezoneid\":\"").append(esc(timezoneId)).append("\",")
        sb.append("\"bron\":\"").append(esc(bron)).append("\",")
        sb.append("\"_id\":\"").append(esc(idLocal)).append("\",")
        sb.append("\"tellingid\":\"").append(esc(tellingId)).append("\",")
        sb.append("\"telpostid\":\"").append(esc(telpostId)).append("\",")
        sb.append("\"begintijd\":\"").append(esc(beginEpoch)).append("\",")
        sb.append("\"eindtijd\":\"").append(esc(eindEpoch)).append("\",")
        sb.append("\"tellers\":\"").append(esc(tellers)).append("\",")
        sb.append("\"weer\":\"").append(esc(weer)).append("\",")
        sb.append("\"windrichting\":\"").append(esc(windrichting)).append("\",")
        sb.append("\"windkracht\":\"").append(esc(windkracht)).append("\",")
        sb.append("\"temperatuur\":\"").append(esc(temperatuur)).append("\",")
        sb.append("\"bewolking\":\"").append(esc(bewolking)).append("\",")
        sb.append("\"bewolkinghoogte\":\"").append(esc(bewolkinghoogte)).append("\",")
        sb.append("\"neerslag\":\"").append(esc(neerslag)).append("\",")
        sb.append("\"duurneerslag\":\"").append(esc(duurneerslag)).append("\",")
        sb.append("\"zicht\":\"").append(esc(zicht)).append("\",")
        sb.append("\"tellersactief\":\"").append(esc(tellersactief)).append("\",")
        sb.append("\"tellersaanwezig\":\"").append(esc(tellersaanwezig)).append("\",")
        sb.append("\"typetelling\":\"").append(esc(typetelling)).append("\",")
        sb.append("\"metersnet\":\"").append(esc(metersnet)).append("\",")
        sb.append("\"geluid\":\"").append(esc(geluid)).append("\",")
        sb.append("\"opmerkingen\":\"").append(esc(opmerkingen)).append("\",")
        sb.append("\"onlineid\":\"").append(esc(onlineId)).append("\",")
        sb.append("\"HYDRO\":\"").append(esc(hydro)).append("\",")
        sb.append("\"hpa\":\"").append(esc(hpa)).append("\",")
        sb.append("\"equipment\":\"").append(esc(equipment)).append("\",")
        sb.append("\"uuid\":\"").append(esc(uuid)).append("\",")
        sb.append("\"uploadtijdstip\":\"").append(esc(uploadTs)).append("\",")
        sb.append("\"nrec\":\"").append(esc(nrec)).append("\",")
        sb.append("\"nsoort\":\"").append(esc(nsoort)).append("\",")

        // Indien userid mee moet:
        if (!userid.isNullOrBlank()) {
            sb.append("\"userid\":\"").append(esc(userid)).append("\",")
        }

        // lege data-array bij init
        sb.append("\"data\":[]")
        sb.append("}]")
        return sb.toString()
    }
}

/* ---------- Kleine helpers op DocumentFile ---------- */
private fun DocumentFile.findFile(name: String): DocumentFile? =
    this.listFiles().firstOrNull { it.name == name }
