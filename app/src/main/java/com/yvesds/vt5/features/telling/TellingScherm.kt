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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermTellingBinding
import com.yvesds.vt5.features.alias.AliasRepository
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yvesds.vt5.features.speech.SpeechRecognitionManager
import com.yvesds.vt5.features.speech.VolumeKeyHandler
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * TellingScherm - Hoofdscherm voor het tellen van soorten met spraakherkenning.
 * Geoptimaliseerd met:
 * - lifecycleScope voor coroutines gekoppeld aan de activity lifecycle
 * - Verbeterde caching en UI-updates
 * - Meer efficiënte spraakherkenning integratie
 * - Alias ondersteuning voor betere spraakherkenning
 */
class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101
    }

    // Spraakherkenning componenten
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private val selectedSpeciesMap = HashMap<String, String>(100) // Pre-allocate capacity
    private var speechInitialized = false

    // UI componenten
    private lateinit var binding: SchermTellingBinding

    // Adapters
    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var logAdapter: SpeechLogAdapter

    // Alias repository
    private val aliasRepository by lazy { AliasRepository.getInstance(this) }

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
                lifecycleScope.launch {
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

        // Log-venster setup met efficient recycler view
        setupLogRecyclerView()

        // Tiles setup met flexbox voor adaptieve layout
        setupSpeciesTilesRecyclerView()

        // Buttons setup
        setupButtons()

        // Voorselectie inladen
        loadPreselection()

        // Preload aliassen voor spraakherkenning (nieuw)
        preloadAliases()
    }

    /**
     * Preload aliassen voor betere spraakherkenning
     */
    private fun preloadAliases() {
        lifecycleScope.launch {
            try {
                val success = aliasRepository.loadAliasData()

                if (success) {
                    Log.d(TAG, "Aliases preloaded successfully")
                } else {
                    Log.w(TAG, "Failed to preload aliases")

                    // Probeer CSV naar JSON te converteren als laden mislukt
                    tryConvertCsvToJson()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading aliases", e)
            }
        }
    }

    /**
     * Probeer CSV naar JSON te converteren
     */
    private fun tryConvertCsvToJson() {
        lifecycleScope.launch {
            try {
                val success = aliasRepository.convertCsvToJson()
                if (success) {
                    Log.d(TAG, "Successfully converted CSV to JSON")
                    // Probeer opnieuw te laden
                    aliasRepository.loadAliasData()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error converting CSV to JSON", e)
            }
        }
    }

    private fun setupLogRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechLog.layoutManager = layoutManager
        binding.recyclerViewSpeechLog.setHasFixedSize(true)
        logAdapter = SpeechLogAdapter()
        binding.recyclerViewSpeechLog.adapter = logAdapter
    }

    private fun setupSpeciesTilesRecyclerView() {
        val flm = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.FLEX_START
        }
        binding.recyclerViewSpecies.layoutManager = flm
        binding.recyclerViewSpecies.setHasFixedSize(true)
        binding.recyclerViewSpecies.itemAnimator?.changeDuration = 0 // Disable animations for better performance

        tilesAdapter = SpeciesTileAdapter(
            onTileClick = { pos -> showNumberInputDialog(pos) }
        )
        binding.recyclerViewSpecies.adapter = tilesAdapter
    }

    private fun setupButtons() {
        binding.btnAddSoorten.setOnClickListener { openSoortSelectieForAdd() }

        // Normaal gedrag
        binding.btnAfronden.setOnClickListener {
            Toast.makeText(this, "Afronden (batch-upload) volgt later.", Toast.LENGTH_LONG).show()
        }

        // Long click voor conversie van CSV naar JSON (voor admins)
        binding.btnAfronden.setOnLongClickListener {
            tryConvertCsvToJson()
            Toast.makeText(this, "CSV naar JSON conversie gestart...", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun loadPreselection() {
        lifecycleScope.launch {
            try {
                // Toon progressdialog
                val dialog = ProgressDialogHelper.show(this@TellingScherm, "Soorten laden...")

                try {
                    // Load data in background
                    val (snapshot, initial) = withContext(Dispatchers.IO) {
                        val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                        val pre = TellingSessionManager.preselectState.value
                        val ids = pre.selectedSoortIds

                        if (ids.isEmpty()) {
                            return@withContext null to emptyList()
                        }

                        val speciesById = snapshot.speciesById
                        val initialList = ids.mapNotNull { sid ->
                            val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                            SoortRow(sid, naam, 0)
                        }.sortedBy { it.naam.lowercase() }

                        snapshot to initialList
                    }

                    if (initial.isEmpty()) {
                        dialog.dismiss()
                        Toast.makeText(this@TellingScherm, "Geen voorselectie. Keer terug en selecteer soorten.", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }

                    // Update UI on main thread
                    tilesAdapter.submitList(initial)
                    addLog("Telling gestart met ${initial.size} soorten.", "systeem")
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

            // Laad aliassen in de spraakherkenningsmanager
            lifecycleScope.launch {
                speechRecognitionManager.loadAliases()
            }

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
            // Zorg ervoor dat de soorten-map up-to-date is vóór elke luistersessie
            updateSelectedSpeciesMap()

            speechRecognitionManager.startListening()

            // Visuele feedback dat spraakherkenning actief is
            addLog("Luisteren...", "systeem")

            // Start een timer om automatic timeout te simuleren
            lifecycleScope.launch {
                kotlinx.coroutines.delay(8000) // 8 seconden timeout
                if (speechInitialized && speechRecognitionManager.isCurrentlyListening()) {
                    speechRecognitionManager.stopListening()

                    // Update UI op main thread
                    withContext(Dispatchers.Main) {
                        addLog("Timeout na 8 seconden", "systeem")
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::volumeKeyHandler.isInitialized && volumeKeyHandler.isVolumeUpEvent(keyCode)) {
            // Start spraakherkenning
            startSpeechRecognition()
            return true // Consumeer het event
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Update de map met beschikbare soorten voor spraakherkenning
     */
    private fun updateSelectedSpeciesMap() {
        selectedSpeciesMap.clear()

        val soorten = tilesAdapter.currentList
        if (soorten.isEmpty()) {
            Log.w(TAG, "Species list is empty! Cannot update selectedSpeciesMap")
            return
        }

        // Itereer over de soorten en vul de map
        for (soort in soorten) {
            // Sla zowel de originele naam als een lowercase versie op voor case-insensitive matching
            selectedSpeciesMap[soort.naam] = soort.soortId
            selectedSpeciesMap[soort.naam.lowercase()] = soort.soortId
        }

        if (speechInitialized) {
            speechRecognitionManager.setAvailableSpecies(selectedSpeciesMap)
        }
    }

    /**
     * Telt het opgegeven aantal bij bij het huidige aantal voor een soort
     * Geoptimaliseerd voor snelheid en betere UI-updates
     */
    private fun updateSoortCount(soortId: String, count: Int) {
        val currentList = tilesAdapter.currentList
        val position = currentList.indexOfFirst { it.soortId == soortId }

        if (position == -1) {
            Log.e(TAG, "Species with ID $soortId not found in the list!")
            return
        }

        val item = currentList[position]
        val oldCount = item.count
        val newCount = oldCount + count

        // Create a new list with the updated item
        val updatedList = ArrayList(currentList) // More efficient than toMutableList()
        updatedList[position] = item.copy(count = newCount)

        // Mark as recently used
        RecentSpeciesStore.recordUse(this, soortId, maxEntries = 25)

        // Use a diff-based approach for updating the adapter
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            // First submit null to force a complete refresh
            tilesAdapter.submitList(null)

            // Then submit the new list
            tilesAdapter.submitList(updatedList)

            // Force notification for the specific item
            tilesAdapter.notifyItemChanged(position)

            // Log the update
            addLog("Bijgewerkt: ${item.naam} $oldCount → $newCount", "spraak")
        }
    }

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
                    // Create a new list directly
                    val updated = ArrayList(current)
                    updated[position] = row.copy(count = n)

                    // Submit the update
                    tilesAdapter.submitList(updated)
                    addLog("Set ${row.naam} = $n", "manueel")

                    // Mark as recently used
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

        // Use efficient list update with pre-allocation
        val currentSize = logAdapter.currentList.size
        val newList = ArrayList<SpeechLogRow>(currentSize + 1)
        newList.addAll(logAdapter.currentList)
        newList.add(newRow)

        logAdapter.submitList(newList) {
            binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
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