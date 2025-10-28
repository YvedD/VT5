package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermTellingBinding
import com.yvesds.vt5.features.alias.AliasRepository
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.features.speech.SpeechRecognitionManager
import com.yvesds.vt5.features.speech.VolumeKeyHandler
import com.yvesds.vt5.features.speech.AliasSpeechParser
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.features.speech.Candidate
import com.yvesds.vt5.features.speech.AliasMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * TellingScherm - Hoofdscherm voor het tellen van soorten met spraakherkenning.
 *
 * UPDATED:
 *  - N-best orchestration: listen to SRM hypotheses and iterate them via parser until a MatchResult
 *    is found (AutoAccept / AutoAcceptAddPopup / SuggestionList / MultiMatch).
 *  - Hot-reload: when an alias is added, persist and hot-patch AliasMatcher so the alias is active immediately.
 *  - ASR silence timeout slider wiring (real-time update + persist on stop).
 *  - Multi-species support: "aalscholver 5 boertjes 3" -> Aalscholver +5, Boerenzwaluw +3
 */
class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101

        // SharedPreferences keys for ASR silence ms
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_ASR_SILENCE_MS = "pref_asr_silence_ms"
        private const val DEFAULT_SILENCE_MS = 2000
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

    // Alias editor (buffer + persist helper)
    private lateinit var aliasEditor: AliasEditor

    // Datamodellen
    data class SoortRow(val soortId: String, val naam: String, val count: Int = 0)
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    // For species chooser in AddAliasDialog: flattened "id||name"
    private var availableSpeciesFlat: List<String> = emptyList()

    // Keep last raw partial (optional)
    private var lastRawPartial: String? = null

    // Activity Result Launcher for soortenselectie
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
                        val merged = (existing + additions).sortedBy { it.naam.lowercase(Locale.getDefault()) }
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

    // Preferences and seekbar view
    private lateinit var prefs: SharedPreferences
    private lateinit var seekBarSilenceCompact: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermTellingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init prefs
        prefs = this.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Init alias editor (SAF)
        aliasEditor = AliasEditor(this, SaFStorageHelper(this))

        // Log-venster setup with RecyclerView and inline alias tap
        setupLogRecyclerView()

        // Tiles setup with flexbox for adaptieve layout
        setupSpeciesTilesRecyclerView()

        // Buttons setup
        setupButtons()

        // Wire the compact horizontal seekbar (between log and buttons)
        setupSilenceSeekBar()

        // Voorselectie inladen
        loadPreselection()

        // Preload aliassen for spraakherkenning
        preloadAliases()
    }

    /**
     * Setup the small horizontal seekbar (seekBarSilenceCompact) placed between the log and buttons.
     * - Real-time: update SpeechRecognitionManager on every change
     * - Persist: save to SharedPreferences when user stops sliding (onStopTrackingTouch)
     */
    private fun setupSilenceSeekBar() {
        try {
            // viewBinding: binding.seekBarSilenceCompact moet bestaan in layout
            seekBarSilenceCompact = binding.seekBarSilenceCompact

            // read saved ms (default 2000)
            val savedMs = prefs.getInt(PREF_ASR_SILENCE_MS, DEFAULT_SILENCE_MS)
            val progress = ((savedMs - 2000) / 100).coerceIn(0, 30)
            seekBarSilenceCompact.max = 30
            seekBarSilenceCompact.progress = progress

            // if SRM already initialized, apply immediately
            if (speechInitialized) {
                speechRecognitionManager.setSilenceStopMillis(savedMs.toLong())
            }

            // realtime update during sliding
            seekBarSilenceCompact.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = 2000 + progress * 100
                    if (speechInitialized) {
                        speechRecognitionManager.setSilenceStopMillis(ms.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val p = seekBar?.progress ?: 0
                    val ms = 2000 + p * 100
                    prefs.edit().putInt(PREF_ASR_SILENCE_MS, ms).apply()
                    if (speechInitialized) {
                        speechRecognitionManager.setSilenceStopMillis(ms.toLong())
                    }
                }
            })
        } catch (ex: Exception) {
            Log.w(TAG, "setupSilenceSeekBar failed: ${ex.message}", ex)
        }
    }

    /**
     * Preload aliassen for better speech recognition
     */
    private fun preloadAliases() {
        lifecycleScope.launch {
            try {
                val success = aliasRepository.loadAliasData()

                if (success) {
                    Log.d(TAG, "Aliases preloaded successfully")
                } else {
                    Log.w(TAG, "Failed to preload aliases")
                    tryConvertCsvToJson()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading aliases", e)
            }
        }
    }

    private fun tryConvertCsvToJson() {
        lifecycleScope.launch {
            try {
                val success = aliasRepository.convertCsvToJson()
                if (success) {
                    Log.d(TAG, "Successfully converted CSV to JSON")
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

        // Inline alias assignment: tap on a log item with bron == "raw" to open AddAliasDialog.
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechLog.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechLog.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = logAdapter.currentList.getOrNull(pos)
                        if (row != null && row.bron == "raw" && row.tekst.isNotBlank()) {
                            // open AddAliasDialog inline with this partial
                            if (availableSpeciesFlat.isEmpty()) {
                                Toast.makeText(this@TellingScherm, "Soortenlijst nog niet beschikbaar", Toast.LENGTH_SHORT).show()
                                return true
                            }
                            val dlg = AddAliasDialog.newInstance(listOf(row.tekst), availableSpeciesFlat)
                            dlg.listener = object : AddAliasDialog.AddAliasListener {
                                override fun onAliasAssigned(speciesId: String, aliasText: String) {
                                    lifecycleScope.launch {
                                        val added = aliasEditor.addAliasInMemory(speciesId, aliasText)
                                        if (added) {
                                            try {
                                                // hot-reload to AliasRepository as well (in-memory)
                                                aliasRepository.addAliasInMemory(speciesId, aliasText)
                                                addLog("Alias toegevoegd in-memory: $aliasText -> $speciesId", "alias")
                                            } catch (ex: Exception) {
                                                Log.w(TAG, "AliasRepository.addAliasInMemory failed: ${ex.message}")
                                                addLog("Alias toegevoegd in buffer: $aliasText -> $speciesId", "alias")
                                            }

                                            // Persist and hot-patch the matcher so the alias is active immediately
                                            val progress = ProgressDialogHelper.show(this@TellingScherm, "Alias opslaan...")
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    // Persist user aliases to SAF (merge)
                                                    aliasEditor.persistUserAliasesSaf()
                                                } catch (ex: Exception) {
                                                    Log.w(TAG, "persistUserAliasesSaf failed: ${ex.message}", ex)
                                                }
                                            }

                                            // Hot-patch AliasMatcher (in-memory) so next recognition uses it immediately.
                                            try {
                                                // Try to obtain canonical/tilename for nicer display
                                                val snapshot = ServerDataCache.getCachedOrNull() ?: ServerDataCache.getOrLoad(this@TellingScherm)
                                                val canonical = snapshot.speciesById[speciesId]?.soortnaam ?: aliasText
                                                val tilename = snapshot.speciesById[speciesId]?.soortkey
                                                AliasMatcher.addAliasHotpatch(speciesId, aliasText, canonical, tilename)
                                            } catch (ex: Exception) {
                                                Log.w(TAG, "AliasMatcher.addAliasHotpatch failed: ${ex.message}", ex)
                                            } finally {
                                                // fix: dismiss the AlertDialog instance returned by ProgressDialogHelper.show
                                                try {
                                                    progress.dismiss()
                                                } catch (_: Exception) {
                                                    // ignore dismiss errors
                                                }
                                            }

                                            Toast.makeText(this@TellingScherm, "Alias opgeslagen en direct actief", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@TellingScherm, "Alias niet toegevoegd (duplicaat of ongeldig)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            dlg.show(supportFragmentManager, "addAlias")
                        }
                    }
                }
                return true
            }
        })

        binding.recyclerViewSpeechLog.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                // no-op
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                // no-op
            }
        })
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

        binding.btnAfronden.setOnClickListener {
            Toast.makeText(this, "Afronden (batch-upload) volgt later.", Toast.LENGTH_LONG).show()
        }

        // Correcte aanroep
        binding.btnAfronden.setOnLongClickListener {
            tryConvertCsvToJson()
            Toast.makeText(this@TellingScherm, "CSV naar JSON conversie gestart...", Toast.LENGTH_SHORT).show()
            true
        }

        // Save & close button (Afsluiten / Opslaan)
        binding.btnSaveClose.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Data opslaan?")
                .setMessage("Wil je de toegevoegde aliassen opslaan en terugkeren naar Metadata?")
                .setPositiveButton("Opslaan") { _, _ ->
                    lifecycleScope.launch {
                        val ok = aliasEditor.persistUserAliasesSaf()
                        if (ok) {
                            Toast.makeText(this@TellingScherm, "Aliassen opgeslagen", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@TellingScherm, "Opslaan mislukt", Toast.LENGTH_LONG).show()
                        }
                        // Navigeer daarna naar MetadataScherm
                        startActivity(Intent(this@TellingScherm, com.yvesds.vt5.features.metadata.ui.MetadataScherm::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                        finish()
                    }
                }
                .setNegativeButton("Annuleren", null)
                .show()
        }
    }

    private fun loadPreselection() {
        lifecycleScope.launch {
            try {
                val dialog = ProgressDialogHelper.show(this@TellingScherm, "Soorten laden...")
                try {
                    val (snapshot, initial) = withContext(Dispatchers.IO) {
                        val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                        val pre = TellingSessionManager.preselectState.value
                        val ids = pre.selectedSoortIds

                        availableSpeciesFlat = snapshot.speciesById.map { (id, s) -> "$id||${s.soortnaam}" }.toList()

                        if (ids.isEmpty()) {
                            return@withContext null to emptyList()
                        }

                        val speciesById = snapshot.speciesById
                        val initialList = ids.mapNotNull { sid ->
                            val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                            SoortRow(sid, naam, 0)
                        }.sortedBy { it.naam.lowercase(Locale.getDefault()) }

                        snapshot to initialList
                    }

                    if (initial.isEmpty()) {
                        dialog.dismiss()
                        Toast.makeText(this@TellingScherm, "Geen voorselectie. Keer terug en selecteer soorten.", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }

                    tilesAdapter.submitList(initial)
                    addLog("Telling gestart met ${initial.size} soorten.", "systeem")
                    dialog.dismiss()

                    checkAndRequestPermissions()
                } finally {
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading species: ${e.message}")
                Toast.makeText(this@TellingScherm, "Fout bij laden van soorten", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO)
        } else {
            initializeSpeechRecognition()
            initializeVolumeKeyHandler()
        }
    }

    private suspend fun buildMatchContext(): MatchContext = withContext(Dispatchers.IO) {
        val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)

        // Tiles species IDs
        val tiles = tilesAdapter.currentList.map { it.soortId }.toSet()

        // Site allowed species IDs
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val siteAllowed = telpostId?.let { id ->
            snapshot.siteSpeciesBySite[id]?.map { it.soortid }?.toSet() ?: emptySet()
        } ?: snapshot.speciesById.keys

        // Recent species IDs
        val recents = RecentSpeciesStore.getRecents(this@TellingScherm).map { it.first }.toSet()

        // Species lookup map
        val speciesById = snapshot.speciesById.mapValues { (_, sp) ->
            sp.soortnaam to sp.soortkey
        }

        MatchContext(
            tilesSpeciesIds = tiles,
            siteAllowedIds = siteAllowed,
            recentIds = recents,
            speciesById = speciesById
        )
    }

    private fun initializeSpeechRecognition() {
        try {
            speechRecognitionManager = SpeechRecognitionManager(this)
            speechRecognitionManager.initialize()

            // Apply saved silence ms if present
            val savedMs = prefs.getInt(PREF_ASR_SILENCE_MS, DEFAULT_SILENCE_MS)
            speechRecognitionManager.setSilenceStopMillis(savedMs.toLong())

            updateSelectedSpeciesMap()

            lifecycleScope.launch {
                speechRecognitionManager.loadAliases()
            }

            // Register hypotheses listener: iterate N-best hypotheses and call parser until a useable MatchResult is found
            speechRecognitionManager.setOnHypothesesListener { hypotheses, partials ->
                lifecycleScope.launch {
                    Log.d(TAG, "Hypotheses received: $hypotheses")
                    try {
                        val matchContext = buildMatchContext()
                        val parser = AliasSpeechParser(this@TellingScherm, SaFStorageHelper(this@TellingScherm))

                        // Centralized N-best handling in parser:
                        val result = parser.parseSpokenWithHypotheses(hypotheses, matchContext, partials, asrWeight = 0.4)
                        Log.d(TAG, "Parse result: $result")

                        when (result) {
                            is MatchResult.AutoAccept -> {
                                // FIXED: Use extracted amount from result
                                updateSoortCount(result.candidate.speciesId, result.amount)
                                addLog("Herkend: ${result.candidate.displayName} ${result.amount} (auto)", "spraak")
                                RecentSpeciesStore.recordUse(this@TellingScherm, result.candidate.speciesId, maxEntries = 25)
                            }
                            is MatchResult.AutoAcceptAddPopup -> {
                                // FIXED: Use extracted amount from result
                                val cnt = result.amount
                                runOnUiThread {
                                    val prettyName = result.candidate.displayName
                                    val msg = "Soort \"$prettyName\" werd herkend met aantal $cnt maar staat nog niet in de lijst.\n\nWil je deze soort toevoegen en meteen $cnt noteren?"
                                    AlertDialog.Builder(this@TellingScherm)
                                        .setTitle("Soort toevoegen?")
                                        .setMessage(msg)
                                        .setPositiveButton("Ja") { _, _ ->
                                            addSpeciesToTiles(result.candidate.speciesId, result.candidate.displayName, cnt)
                                        }
                                        .setNegativeButton("Nee", null)
                                        .show()
                                }
                            }
                            is MatchResult.MultiMatch -> {
                                // NEW: Handle multiple species in one query (e.g., "aalscholver 5 boertjes 3")
                                result.matches.forEach { match ->
                                    if (match.candidate.isInTiles) {
                                        updateSoortCount(match.candidate.speciesId, match.amount)
                                        addLog("Herkend: ${match.candidate.displayName} ${match.amount} (multi)", "spraak")
                                        RecentSpeciesStore.recordUse(this@TellingScherm, match.candidate.speciesId, maxEntries = 25)
                                    } else {
                                        // Species not in tiles -> ask to add
                                        runOnUiThread {
                                            val prettyName = match.candidate.displayName
                                            val msg = "Soort \"$prettyName\" (${match.amount}x) herkend maar niet in lijst.\n\nToevoegen?"
                                            AlertDialog.Builder(this@TellingScherm)
                                                .setTitle("Soort toevoegen?")
                                                .setMessage(msg)
                                                .setPositiveButton("Ja") { _, _ ->
                                                    addSpeciesToTiles(match.candidate.speciesId, prettyName, match.amount)
                                                }
                                                .setNegativeButton("Nee", null)
                                                .show()
                                        }
                                    }
                                }
                            }
                            is MatchResult.SuggestionList -> {
                                // Extract amount from hypothesis (fallback for unclear matches)
                                val cnt = extractCountFromHypothesis(result.hypothesis) ?: 1
                                runOnUiThread { showSuggestionBottomSheet(result.candidates, cnt) }
                            }
                            is MatchResult.NoMatch -> {
                                addLog(result.hypothesis, "raw")
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Hypotheses handling failed: ${ex.message}", ex)
                    }
                }
            }

            // Raw ASR callback: capture last partial / raw best match
            speechRecognitionManager.setOnRawResultListener { rawText ->
                runOnUiThread {
                    lastRawPartial = rawText
                    addLog(rawText, "raw")
                }
            }

            addLog("Spraakherkenning geactiveerd - protocol: 'Soortnaam Aantal'", "systeem")
            speechInitialized = true

            Log.d(TAG, "Speech recognition initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognition", e)
            Toast.makeText(this, "Kon spraakherkenning niet initialiseren: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper: try to extract a numeric count from hypothesis text, naive but useful
    private fun extractCountFromHypothesis(hyp: String): Int? {
        val m = Regex("\\b(\\d+)\\b").find(hyp)
        return m?.groups?.get(1)?.value?.toIntOrNull()
    }

    private fun showSuggestionBottomSheet(candidates: List<Candidate>, count: Int) {
        val items = candidates.map { "${it.displayName} (score: ${"%.2f".format(it.score)})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Kies soort")
            .setItems(items) { _, which ->
                val chosen = candidates[which]
                if (chosen.isInTiles) {
                    updateSoortCount(chosen.speciesId, count)
                    addLog("Geselecteerd: ${chosen.displayName} $count", "spraak")
                } else {
                    val msg = "Soort \"${chosen.displayName}\" toevoegen en $count noteren?"
                    AlertDialog.Builder(this)
                        .setTitle("Soort toevoegen?")
                        .setMessage(msg)
                        .setPositiveButton("Ja") { _, _ ->
                            addSpeciesToTiles(chosen.speciesId, chosen.displayName, count)
                        }
                        .setNegativeButton("Nee", null)
                        .show()
                }
                RecentSpeciesStore.recordUse(this, chosen.speciesId, maxEntries = 25)
            }
            .setNegativeButton("Annuleer", null)
            .show()
    }

    private fun addSpeciesToTiles(soortId: String, naam: String, initialCount: Int) {
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { ServerDataCache.getOrLoad(this@TellingScherm) }
                val canonical = snapshot.speciesById[soortId]?.soortnaam ?: naam

                val current = tilesAdapter.currentList
                if (current.any { it.soortId == soortId }) {
                    updateSoortCount(soortId, initialCount)
                    return@launch
                }

                val newRow = SoortRow(soortId, canonical, initialCount)
                val updated = ArrayList(current)
                updated.add(newRow)

                lifecycleScope.launch(Dispatchers.Main) {
                    tilesAdapter.submitList(updated)
                    updateSelectedSpeciesMap()
                    RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
                    addLog("Soort toegevoegd: $canonical ($initialCount)", "systeem")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "addSpeciesToTiles failed: ${ex.message}", ex)
                addLog("Fout bij toevoegen soort ${naam}", "systeem")
            }
        }
    }

    private fun addLog(msgIn: String, bron: String) {
        var msg = msgIn
        msg = msg.replace(Regex("(?i)^\\s*asr:\\s*"), "")
        if (bron == "raw") {
            msg = msg.replace(Regex("\\s+\\d+(?:[.,]\\d+)?$"), "")
            msg = msg.trim()
        }
        val now = System.currentTimeMillis() / 1000L
        val newRow = SpeechLogRow(ts = now, tekst = msg, bron = bron)

        val currentSize = logAdapter.currentList.size
        val newList = ArrayList<SpeechLogRow>(currentSize + 1)
        newList.addAll(logAdapter.currentList)
        newList.add(newRow)

        logAdapter.submitList(newList) {
            binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
        }
    }

    private fun initializeVolumeKeyHandler() {
        try {
            volumeKeyHandler = VolumeKeyHandler(this)
            volumeKeyHandler.setOnVolumeUpListener {
                startSpeechRecognition()
            }
            volumeKeyHandler.register()

            Log.d(TAG, "Volume key handler initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing volume key handler", e)
        }
    }

    private fun startSpeechRecognition() {
        if (speechInitialized && !speechRecognitionManager.isCurrentlyListening()) {
            updateSelectedSpeciesMap()
            speechRecognitionManager.startListening()
            addLog("Luisteren...", "systeem")

            lifecycleScope.launch {
                kotlinx.coroutines.delay(8000)
                if (speechInitialized && speechRecognitionManager.isCurrentlyListening()) {
                    speechRecognitionManager.stopListening()
                    withContext(Dispatchers.Main) {
                        addLog("Timeout na 8 seconden", "systeem")
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::volumeKeyHandler.isInitialized && volumeKeyHandler.isVolumeUpEvent(keyCode)) {
            startSpeechRecognition()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateSelectedSpeciesMap() {
        selectedSpeciesMap.clear()

        val soorten = tilesAdapter.currentList
        if (soorten.isEmpty()) {
            Log.w(TAG, "Species list is empty! Cannot update selectedSpeciesMap")
            return
        }

        for (soort in soorten) {
            selectedSpeciesMap[soort.naam] = soort.soortId
            selectedSpeciesMap[soort.naam.lowercase(Locale.getDefault())] = soort.soortId
        }

        if (speechInitialized) {
            speechRecognitionManager.setAvailableSpecies(selectedSpeciesMap)
        }
    }

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

        val updatedList = ArrayList(currentList)
        updatedList[position] = item.copy(count = newCount)

        RecentSpeciesStore.recordUse(this, soortId, maxEntries = 25)

        lifecycleScope.launch(Dispatchers.Main.immediate) {
            tilesAdapter.submitList(null)
            tilesAdapter.submitList(updatedList)
            tilesAdapter.notifyItemChanged(position)
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
                    initializeSpeechRecognition()
                    initializeVolumeKeyHandler()
                } else {
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
                    val updated = ArrayList(current)
                    updated[position] = row.copy(count = n)
                    tilesAdapter.submitList(updated)
                    addLog("Set ${row.naam} = $n", "manueel")
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