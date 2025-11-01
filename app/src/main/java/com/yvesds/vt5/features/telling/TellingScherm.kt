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
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.telling.AliasEditor
import com.yvesds.vt5.features.telling.AddAliasDialog
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.features.speech.SpeechRecognitionManager
import com.yvesds.vt5.features.speech.VolumeKeyHandler
import com.yvesds.vt5.features.speech.AliasSpeechParser
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.features.speech.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Locale
import java.time.Instant

/**
 * TellingScherm.kt
 *
 * Full activity for the counting screen. Integrates AliasManager hot-patch flow,
 * AddAliasDialog usage, multi-match handling, ASR N-best orchestration and the UI.
 *
 * Performance and lifecycle improvements:
 * - Preload and heavy IO/CPU moved off Main via explicit dispatchers.
 * - Aliases preload guarded (idempotent).
 * - SpeechRecognitionManager.loadAliases invoked on IO.
 * - onDestroy flush uses an independent background scope so flush can run after Activity is destroyed.
 * - Minor defensive checks and UI-thread marshaling for shared state updates.
 */
class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101

        // SharedPreferences keys for ASR silence ms
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_ASR_SILENCE_MS = "pref_asr_silence_ms"
        private const val DEFAULT_SILENCE_MS = 1500

        // Limit for log rows to avoid very large lists
        private const val MAX_LOG_ROWS = 600
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

    // Alias repository (legacy, kept for compatibility)
    private val aliasRepository by lazy { AliasRepository.getInstance(this) }

    // Alias editor (buffer + persist helper)
    private lateinit var aliasEditor: AliasEditor

    // SAF helper
    private val safHelper by lazy { SaFStorageHelper(this) }

    // Datamodellen
    data class SoortRow(val soortId: String, val naam: String, val count: Int = 0)
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    // For species chooser in AddAliasDialog: flattened "id||name"
    private var availableSpeciesFlat: List<String> = emptyList()

    // Keep last raw partial (optional)
    private var lastRawPartial: String? = null

    // Simple guard to avoid duplicate alias preloads
    @Volatile
    private var aliasesPreloaded = false

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
        aliasEditor = AliasEditor(this, safHelper)

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

    /*═══════════════════════════════════════════════════════════════
     * SETUP: Log RecyclerView with IN-FIELD ALIAS TRAINING
     *═══════════════════════════════════════════════════════════════*/
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

                            val extractedCount = extractCountFromText(row.tekst)
                            val dlg = AddAliasDialog.newInstance(listOf(row.tekst), availableSpeciesFlat)

                            dlg.listener = object : AddAliasDialog.AddAliasListener {
                                override fun onAliasAssigned(speciesId: String, aliasText: String) {
                                    lifecycleScope.launch {
                                        val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                                        val canonical = snapshot.speciesById[speciesId]?.soortnaam ?: aliasText
                                        val tilename = snapshot.speciesById[speciesId]?.soortkey

                                        // 1) addAlias hotpatches in-memory and enqueues for batched persistence
                                        val added = AliasManager.addAlias(
                                            context = this@TellingScherm,
                                            saf = safHelper,
                                            speciesId = speciesId,
                                            aliasText = aliasText.trim(),
                                            canonical = canonical,
                                            tilename = tilename
                                        )

                                        if (added) {
                                            // Immediate UI feedback (alias is active in-memory)
                                            addSpeciesToTilesIfNeeded(speciesId, canonical, extractCountFromText(aliasText))
                                            updateSoortCount(speciesId, extractCountFromText(aliasText))
                                            addLog("Alias toegevoegd: '$aliasText' → $canonical (in geheugen)", "alias")
                                            Toast.makeText(this@TellingScherm, "Alias actief en opgeslagen (buffer).", Toast.LENGTH_SHORT).show()

                                            // 2) Best-effort: flush write-queue to SAF in background so alias becomes persistent now
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                try {
                                                    AliasManager.forceFlush(this@TellingScherm, safHelper)
                                                    // notify user on main thread
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@TellingScherm, "Alias permanent opgeslagen voor soort \"$canonical\"", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (ex: Exception) {
                                                    // on failure, still inform user (we already have the in-memory alias)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@TellingScherm, "Alias toegevoegd (buffer). Opslaan naar schijf mislukt: ${ex.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                    Log.w(TAG, "Immediate flush after addAlias failed: ${ex.message}", ex)
                                                }
                                            }
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

    /**
     * Extract count from text (e.g., "ali 5" → 5)
     */
    private fun extractCountFromText(text: String): Int {
        val m = Regex("\\b(\\d+)\\b").find(text)
        return m?.groups?.get(1)?.value?.toIntOrNull() ?: 1
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

        // long-press CSV conversion path removed (CSV no longer supported in runtime)

        binding.btnSaveClose.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Data opslaan?")
                .setMessage("Wil je de toegevoegde aliassen opslaan en terugkeren naar Metadata?")
                .setPositiveButton("Opslaan") { _, _ ->
                    lifecycleScope.launch {
                        AliasManager.forceFlush(this@TellingScherm, safHelper)
                        Toast.makeText(this@TellingScherm, "Opslaan afgerond", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@TellingScherm, com.yvesds.vt5.features.metadata.ui.MetadataScherm::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
                        finish()
                    }
                }
                .setNegativeButton("Annuleren", null)
                .show()
        }
    }

    private fun setupSilenceSeekBar() {
        try {
            seekBarSilenceCompact = binding.seekBarSilenceCompact

            val savedMs = prefs.getInt(PREF_ASR_SILENCE_MS, DEFAULT_SILENCE_MS)
            val progress = ((savedMs - 2000) / 100).coerceIn(0, 30)
            seekBarSilenceCompact.max = 30
            seekBarSilenceCompact.progress = progress

            if (speechInitialized) {
                speechRecognitionManager.setSilenceStopMillis(savedMs.toLong())
            }

            seekBarSilenceCompact.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = 2000 + progress * 100
                    if (speechInitialized) {
                        speechRecognitionManager.setSilenceStopMillis(ms.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val p = seekBar?.progress ?: 0
                    val ms = 2000 + p * 100
                    prefs.edit { putInt(PREF_ASR_SILENCE_MS, ms) }
                    if (speechInitialized) {
                        speechRecognitionManager.setSilenceStopMillis(ms.toLong())
                    }
                }
            })
        } catch (ex: Exception) {
            Log.w(TAG, "setupSilenceSeekBar failed: ${ex.message}", ex)
        }
    }

    private fun preloadAliases() {
        // Idempotent and off-main
        if (aliasesPreloaded) return
        aliasesPreloaded = true

        lifecycleScope.launch(Dispatchers.IO) {
            val messages = mutableListOf<String>()
            try {
                // Ensure alias seed/cache exists and load it in AliasManager (AliasManager handles IO internally)
                try {
                    AliasManager.initialize(this@TellingScherm, safHelper)
                } catch (ex: Exception) {
                    messages += "AliasManager.initialize failed: ${ex.message}"
                    Log.w(TAG, "AliasManager.initialize failed: ${ex.message}", ex)
                }

                // Ensure internal index is loaded for fast lookup (AliasMatcher)
                try {
                    AliasManager.ensureIndexLoadedSuspend(this@TellingScherm, safHelper)
                } catch (ex: Exception) {
                    messages += "ensureIndexLoadedSuspend failed: ${ex.message}"
                    Log.w(TAG, "preloadAliases: ensureIndexLoadedSuspend failed: ${ex.message}", ex)
                }

                // Populate availableSpeciesFlat for AddAliasDialog dropdown (fetch snapshot on IO)
                val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                val flat = snapshot.speciesById.map { (id, s) -> "$id||${s.soortnaam}" }.toList()

                // Publish to UI thread
                withContext(Dispatchers.Main) {
                    availableSpeciesFlat = flat
                    Log.d(TAG, "Aliases preloaded successfully (${availableSpeciesFlat.size})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading aliases: ${e.message}", e)
            }
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

        val tiles = tilesAdapter.currentList.map { it.soortId }.toSet()

        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val siteAllowed = telpostId?.let { id ->
            snapshot.siteSpeciesBySite[id]?.map { it.soortid }?.toSet() ?: emptySet()
        } ?: snapshot.speciesById.keys

        val recents = RecentSpeciesStore.getRecents(this@TellingScherm).map { it.first }.toSet()

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

            val savedMs = prefs.getInt(PREF_ASR_SILENCE_MS, DEFAULT_SILENCE_MS)
            speechRecognitionManager.setSilenceStopMillis(savedMs.toLong())

            updateSelectedSpeciesMap()

            // loadAliases may perform IO — run on IO
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed: ${ex.message}", ex)
                }
            }

            // --- IMPORTANT: heavy parsing moved to background to avoid UI blocking ---
            speechRecognitionManager.setOnHypothesesListener { hypotheses, partials ->
                // Launch background parse job to avoid blocking UI thread
                lifecycleScope.launch(Dispatchers.Default) {
                    Log.d(TAG, "Hypotheses received: $hypotheses")
                    try {
                        // buildMatchContext is suspend and uses IO internally
                        val matchContext = buildMatchContext()

                        // Parser creation is cheap; the parse call can be heavy
                        val parser = AliasSpeechParser(this@TellingScherm, safHelper)
                        val result = parser.parseSpokenWithHypotheses(hypotheses, matchContext, partials, asrWeight = 0.4)

                        // Post result handling back to Main
                        withContext(Dispatchers.Main) {
                            try {
                                when (result) {
                                    is MatchResult.AutoAccept -> {
                                        updateSoortCount(result.candidate.speciesId, result.amount)
                                        addLog("Herkend: ${result.candidate.displayName} ${result.amount} (auto)", "spraak")
                                        RecentSpeciesStore.recordUse(this@TellingScherm, result.candidate.speciesId, maxEntries = 25)
                                    }
                                    is MatchResult.AutoAcceptAddPopup -> {
                                        val cnt = result.amount
                                        val prettyName = result.candidate.displayName
                                        val msg = "Soort \"$prettyName\" herkend met aantal $cnt.\n\nToevoegen?"
                                        AlertDialog.Builder(this@TellingScherm)
                                            .setTitle("Soort toevoegen?")
                                            .setMessage(msg)
                                            .setPositiveButton("Ja") { _, _ ->
                                                addSpeciesToTiles(result.candidate.speciesId, result.candidate.displayName, cnt)
                                            }
                                            .setNegativeButton("Nee", null)
                                            .show()
                                    }
                                    is MatchResult.MultiMatch -> {
                                        result.matches.forEach { match ->
                                            if (match.candidate.isInTiles) {
                                                updateSoortCount(match.candidate.speciesId, match.amount)
                                                addLog("Herkend: ${match.candidate.displayName} ${match.amount} (multi)", "spraak")
                                                RecentSpeciesStore.recordUse(this@TellingScherm, match.candidate.speciesId, maxEntries = 25)
                                            } else {
                                                val prettyName = match.candidate.displayName
                                                val msg = "Soort \"$prettyName\" (${match.amount}x) herkend.\n\nToevoegen?"
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
                                    is MatchResult.SuggestionList -> {
                                        val cnt = extractCountFromText(result.hypothesis)
                                        showSuggestionBottomSheet(result.candidates, cnt)
                                    }
                                    is MatchResult.NoMatch -> {
                                        addLog(result.hypothesis, "raw")
                                    }
                                }
                            } catch (ex: Exception) {
                                Log.w(TAG, "Hypotheses handling (UI) failed: ${ex.message}", ex)
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Hypotheses handling (background) failed: ${ex.message}", ex)
                    }
                }
            }

            speechRecognitionManager.setOnRawResultListener { rawText ->
                // Keep UI update minimal and on Main
                lifecycleScope.launch(Dispatchers.Main) {
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

    private fun addSpeciesToTilesIfNeeded(speciesId: String, canonical: String, extractedCount: Int) {
        val current = tilesAdapter.currentList
        if (current.any { it.soortId == speciesId }) {
            updateSoortCount(speciesId, extractedCount)
            return
        }
        addSpeciesToTiles(speciesId, canonical, extractedCount)
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

                // submit on Main
                withContext(Dispatchers.Main) {
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

    /**
     * Append a log row. Do list-building off the UI thread to avoid blocking RecyclerView.
     */
    private fun addLog(msgIn: String, bron: String) {
        val now = System.currentTimeMillis() / 1000L
        val cleaned = run {
            var m = msgIn.replace(Regex("(?i)^\\s*asr:\\s*"), "")
            if (bron == "raw") {
                m = m.replace(Regex("\\s+\\d+(?:[.,]\\d+)?$"), "")
            }
            m.trim()
        }
        val newRow = SpeechLogRow(ts = now, tekst = cleaned, bron = bron)

        lifecycleScope.launch {
            // Build new list off-main
            val newList = withContext(Dispatchers.Default) {
                val current = logAdapter.currentList
                val list = ArrayList(current)
                list.add(newRow)
                // limit growth
                if (list.size > MAX_LOG_ROWS) {
                    val drop = list.size - MAX_LOG_ROWS
                    repeat(drop) { list.removeAt(0) }
                }
                list
            }

            // Submit on main and scroll
            logAdapter.submitList(newList) {
                binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
            }
        }
    }

    private fun initializeVolumeKeyHandler() {
        try {
            volumeKeyHandler = VolumeKeyHandler(this)
            volumeKeyHandler.setOnVolumeUpListener {
                startSpeechRecognition()
            }
            // Do not assume a volume-down listener exists; keep compatibility with your handler implementation.
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
            // no ad-hoc coroutine timeout — rely on engine hints and forceStopNow() when needed
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
        lifecycleScope.launch {
            val currentList = tilesAdapter.currentList
            val pos = currentList.indexOfFirst { it.soortId == soortId }
            if (pos == -1) {
                Log.e(TAG, "Species with ID $soortId not found in the list!")
                return@launch
            }

            val item = currentList[pos]
            val oldCount = item.count
            val newCount = oldCount + count

            // Build updated list off-main if heavy
            val updatedList = withContext(Dispatchers.Default) {
                val tmp = ArrayList(currentList)
                tmp[pos] = item.copy(count = newCount)
                tmp
            }

            // Submit once on main
            withContext(Dispatchers.Main) {
                tilesAdapter.submitList(updatedList)
                addLog("Bijgewerkt: ${item.naam} $oldCount → $newCount", "spraak")
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
            }
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
                    lifecycleScope.launch {
                        val updated = withContext(Dispatchers.Default) {
                            val cur = tilesAdapter.currentList
                            val tmp = ArrayList(cur)
                            tmp[position] = row.copy(count = n)
                            tmp
                        }
                        withContext(Dispatchers.Main) {
                            tilesAdapter.submitList(updated)
                            addLog("Set ${row.naam} = $n", "manueel")
                            RecentSpeciesStore.recordUse(this@TellingScherm, row.soortId, maxEntries = 25)
                        }
                    }
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

        // Ensure pending alias writes are flushed. Use an independent background scope
        // so flush continues even if this Activity's lifecycle is ending.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                AliasManager.forceFlush(this@TellingScherm, safHelper)
            } catch (ex: Exception) {
                Log.w(TAG, "forceFlush in onDestroy failed: ${ex.message}", ex)
            }
        }
    }
}