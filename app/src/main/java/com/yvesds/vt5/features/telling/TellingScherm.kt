package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermTellingBinding
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.yvesds.vt5.features.speech.SpeechRecognitionManager
import com.yvesds.vt5.features.speech.VolumeKeyHandler
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101
    }

    // Spraakherkenning componenten
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private var selectedSpeciesMap = mutableMapOf<String, String>() // naam -> id
    private var speechInitialized = false

    // UI componenten
    private lateinit var binding: SchermTellingBinding
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    // Adapters
    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var logAdapter: SpeechLogAdapter

    // Datamodellen
    data class SoortRow(val soortId: String, val naam: String, val count: Int = 0)
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    // Activity Result Launcher voor soortenselectie
    private val addSoortenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val newIds = res.data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            if (newIds.isNotEmpty()) {
                uiScope.launch {
                    val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                    val speciesById = snapshot.speciesById

                    val existing = tilesAdapter.currentList
                    val existingIds = existing.asSequence().map { it.soortId }.toMutableSet()

                    val additions = newIds.asSequence()
                        .filterNot { it in existingIds }
                        .mapNotNull { sid ->
                            val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                            SoortRow(sid, naam, 0)
                        }
                        .toList()

                    if (additions.isNotEmpty()) {
                        val merged = (existing + additions).sortedBy { it.naam.lowercase() }
                        tilesAdapter.submitList(merged)
                        addLog("Soorten toegevoegd: ${additions.size}", "manueel")
                        Toast.makeText(this@TellingScherm, "Toegevoegd: ${additions.size}", Toast.LENGTH_SHORT).show()

                        // Update beschikbare soorten voor spraakherkenning
                        if (speechInitialized) {
                            updateSelectedSpeciesMap()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermTellingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Log-venster
        binding.recyclerViewSpeechLog.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerViewSpeechLog.setHasFixedSize(true)
        logAdapter = SpeechLogAdapter()
        binding.recyclerViewSpeechLog.adapter = logAdapter

        // Tiles: flexbox layout (wrap)
        val flm = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.FLEX_START
        }
        binding.recyclerViewSpecies.layoutManager = flm
        binding.recyclerViewSpecies.setHasFixedSize(true)

        tilesAdapter = SpeciesTileAdapter(
            onTileClick = { pos -> showNumberInputDialog(pos) }
        )
        binding.recyclerViewSpecies.adapter = tilesAdapter

        binding.btnAddSoorten.setOnClickListener { openSoortSelectieForAdd() }
        binding.btnAfronden.setOnClickListener {
            Toast.makeText(this, "Afronden (batch-upload) volgt later.", Toast.LENGTH_LONG).show()
        }

        // Voorselectie inladen
        uiScope.launch {
            try {
                // Toon progressdialog
                val dialog = ProgressDialogHelper.show(this@TellingScherm, "Soorten laden...")

                try {
                    val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                    val pre = TellingSessionManager.preselectState.value
                    val ids = pre.selectedSoortIds

                    if (ids.isEmpty()) {
                        dialog.dismiss()
                        Toast.makeText(this@TellingScherm, "Geen voorselectie. Keer terug en selecteer soorten.", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }

                    val speciesById = snapshot.speciesById
                    val initial = ids.mapNotNull { sid ->
                        val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                        SoortRow(sid, naam, 0)
                    }.sortedBy { it.naam.lowercase() }

                    // Update UI op main thread
                    tilesAdapter.submitList(initial)
                    addLog("Telling gestart met ${initial.size} soorten.", "systeem")

                    // Verberg progressdialog
                    dialog.dismiss()

                    // Controleer en vraag permissies voor spraakherkenning
                    checkAndRequestPermissions()
                } finally {
                    // Zorg ervoor dat dialog altijd gesloten wordt
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading species: ${e.message}")
                Toast.makeText(this@TellingScherm, "Fout bij laden van soorten", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Controleert en vraagt microfoon permissies aan
     */
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO)
        } else {
            // Permissies zijn al toegekend, initialiseer spraakherkenning
            initializeSpeechRecognition()
            initializeVolumeKeyHandler()
        }
    }

    /**
     * Initialiseert de spraakherkenning
     */
    private fun initializeSpeechRecognition() {
        try {
            speechRecognitionManager = SpeechRecognitionManager(this)
            speechRecognitionManager.initialize()

            // Vul de map met beschikbare soorten
            updateSelectedSpeciesMap()

            // Stel de callback in voor als een soort wordt herkend met aantal
            speechRecognitionManager.setOnSpeciesCountListener { soortId, name, count ->
                // Tel deze soort met het opgegeven aantal
                runOnUiThread {
                    updateSoortCount(soortId, count)
                    addLog("Herkend: $name $count", "spraak")
                }
            }

            // Voeg een debug callback toe voor ruwe ASR-resultaten
            speechRecognitionManager.setOnRawResultListener { rawText ->
                runOnUiThread {
                    addLog("ASR: $rawText", "raw")
                }
            }

            // Log dat spraakherkenning is geïnitialiseerd
            addLog("Spraakherkenning geactiveerd - protocol: 'Soortnaam Aantal'", "systeem")
            speechInitialized = true

            Log.d(TAG, "Speech recognition initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognition", e)
            Toast.makeText(this, "Kon spraakherkenning niet initialiseren: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initialiseert de volume key handler
     */
    private fun initializeVolumeKeyHandler() {
        try {
            volumeKeyHandler = VolumeKeyHandler(this)
            volumeKeyHandler.setOnVolumeUpListener {
                // Start spraakherkenning wanneer volume-up wordt ingedrukt
                startSpeechRecognition()
            }
            volumeKeyHandler.register()

            Log.d(TAG, "Volume key handler initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing volume key handler", e)
        }
    }

    /**
     * Start de spraakherkenning met visuele feedback
     */
    private fun startSpeechRecognition() {
        if (speechInitialized && !speechRecognitionManager.isCurrentlyListening()) {
            speechRecognitionManager.startListening()

            // Visuele feedback dat spraakherkenning actief is
            addLog("Luisteren...", "systeem")

            // Start een timer om automatic timeout te simuleren
            uiScope.launch {
                kotlinx.coroutines.delay(8000) // 8 seconden timeout
                if (speechInitialized && speechRecognitionManager.isCurrentlyListening()) {
                    speechRecognitionManager.stopListening()

                    // Update UI op main thread
                    runOnUiThread {
                        addLog("Timeout na 8 seconden", "systeem")
                    }
                }
            }
        }
    }

    /**
     * Update de map met beschikbare soorten voor spraakherkenning
     */
    private fun updateSelectedSpeciesMap() {
        selectedSpeciesMap.clear()

        val soorten = tilesAdapter.currentList
        for (soort in soorten) {
            selectedSpeciesMap[soort.naam] = soort.soortId
        }

        if (speechInitialized) {
            speechRecognitionManager.setAvailableSpecies(selectedSpeciesMap)
            Log.d(TAG, "Updated speech recognition with ${selectedSpeciesMap.size} species")
        }
    }

    /**
     * Telt het opgegeven aantal bij bij het huidige aantal voor een soort
     */
    private fun updateSoortCount(soortId: String, count: Int) {
        // Vind de positie van het item in de lijst
        val current = tilesAdapter.currentList
        val position = current.indexOfFirst { it.soortId == soortId }

        if (position == -1) {
            Log.e(TAG, "Species with ID $soortId not found in the list!")
            return
        }

        val item = current[position]
        val newCount = item.count + count

        Log.d(TAG, "Updating ${item.naam} count from ${item.count} to $newCount")

        // Maak een nieuwe lijst met de bijgewerkte waarde
        val updatedList = ArrayList(current)
        updatedList[position] = item.copy(count = newCount)

        // Markeer als recent gebruikt
        RecentSpeciesStore.recordUse(this, soortId, maxEntries = 25)

        // Submit de nieuwe lijst en zorg dat de UI wordt bijgewerkt
        tilesAdapter.submitList(updatedList)

        // Force UI update voor het specifieke item
        tilesAdapter.notifyItemChanged(position)

        // Log de update
        addLog("Bijgewerkt: ${item.naam} ${item.count} → $newCount", "spraak")
    }

    // Override onKeyDown om volume events af te handelen
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::volumeKeyHandler.isInitialized && volumeKeyHandler.isVolumeUpEvent(keyCode)) {
            // Start spraakherkenning
            startSpeechRecognition()
            return true // Consumeer het event
        }
        return super.onKeyDown(keyCode, event)
    }

    // Override de onRequestPermissionsResult methode
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_RECORD_AUDIO -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permissie toegekend, initialiseer spraakherkenning
                    initializeSpeechRecognition()
                    initializeVolumeKeyHandler()
                } else {
                    // Permissie geweigerd, toon een bericht aan de gebruiker
                    Toast.makeText(this,
                        "Spraakherkenning werkt niet zonder microfoonrechten",
                        Toast.LENGTH_LONG).show()
                }
                return
            }
        }
    }

    private fun showNumberInputDialog(position: Int) {
        val current = tilesAdapter.currentList
        if (position !in current.indices) return
        val row = current[position]

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row.count.toString())
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }

        AlertDialog.Builder(this)
            .setTitle(row.naam)
            .setMessage("Aantal invoeren:")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text?.toString()?.trim()
                val n = v?.toIntOrNull()
                if (n == null || n < 0) {
                    Toast.makeText(this, "Ongeldig aantal.", Toast.LENGTH_SHORT).show()
                } else {
                    val updated = current.map {
                        if (it.soortId == row.soortId) it.copy(count = n) else it
                    }
                    tilesAdapter.submitList(updated)
                    addLog("Set ${row.naam} = $n", "manueel")
                    // Markeer als recent gebruikt (max 25)
                    RecentSpeciesStore.recordUse(this, row.soortId, maxEntries = 25)
                }
            }
            .show()
    }

    private fun openSoortSelectieForAdd() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(this, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }

    private fun addLog(msg: String, bron: String) {
        val now = System.currentTimeMillis() / 1000L
        val newRow = SpeechLogRow(ts = now, tekst = msg, bron = bron)
        val newList = logAdapter.currentList + newRow
        logAdapter.submitList(newList) {
            binding.recyclerViewSpeechLog.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    // Lifecycle methodes
    override fun onPause() {
        super.onPause()
        if (speechInitialized) {
            speechRecognitionManager.stopListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (speechInitialized) {
            speechRecognitionManager.destroy()
        }

        if (::volumeKeyHandler.isInitialized) {
            volumeKeyHandler.unregister()
        }
    }
}

