@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.jvm.Volatile

/**
 * TellingScherm.kt
 *
 * Adjustments:
 * - partials show counts if present (format "Naam -> +N")
 * - ignore empty partials
 * - when adding alias from partial/raw, the extracted count is applied to the tile
 * - keeps cached MatchContext + parser reuse etc.
 */
class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101

        // Preferences keys
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_ASR_SILENCE_MS = "pref_asr_silence_ms"

        // Default silence ms: set low for testing; make configurable in prefs
        private const val DEFAULT_SILENCE_MS = 1000

        private const val MAX_LOG_ROWS = 600
    }

    // UI & adapters
    private lateinit var binding: SchermTellingBinding
    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var logAdapter: SpeechLogAdapter

    // Speech components
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private var speechInitialized = false

    // Parser instance
    private lateinit var aliasParser: AliasSpeechParser

    // Repos/helpers
    private val aliasRepository by lazy { AliasRepository.getInstance(this) }
    private lateinit var aliasEditor: AliasEditor
    private val safHelper by lazy { SaFStorageHelper(this) }

    // Prefs
    private lateinit var prefs: android.content.SharedPreferences

    // ASR hints
    private val selectedSpeciesMap = HashMap<String, String>(100)

    // Flattened species (for AddAliasDialog) - loaded on demand or during preload
    private var availableSpeciesFlat: List<String> = emptyList()

    // Partial UI debounce + only keep last partial
    private var lastPartialUiUpdateMs: Long = 0L
    private val PARTIAL_UI_DEBOUNCE_MS = 200L

    // Cached MatchContext to avoid rebuilding on every parse
    @Volatile
    private var cachedMatchContext: MatchContext? = null

    // Activity result launcher for soortselectie
    private val addSoortenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val newIds = res.data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            if (newIds.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
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

                            // update ASR hints + cached context
                            updateSelectedSpeciesMap()
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "addSoortenLauncher handling failed: ${ex.message}", ex)
                    }
                }
            }
        }
    }

    // Data models
    data class SoortRow(val soortId: String, val naam: String, val count: Int = 0)
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermTellingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        aliasEditor = AliasEditor(this, safHelper)

        setupLogRecyclerView()
        setupSpeciesTilesRecyclerView()
        setupButtons()

        // Preload tiles (if preselected) then initialize ASR
        loadPreselection()
    }

    /* ---------- UI setup ---------- */
    private fun setupLogRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechLog.layoutManager = layoutManager
        binding.recyclerViewSpeechLog.setHasFixedSize(true)
        logAdapter = SpeechLogAdapter()
        binding.recyclerViewSpeechLog.adapter = logAdapter

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechLog.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechLog.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = logAdapter.currentList.getOrNull(pos) ?: return true
                        when (row.bron) {
                            "partial" -> {
                                // parse name & count from the stored partial (which can be either raw hypothesis or formatted)
                                val (nameOnly, cnt) = parseNameAndCountFromDisplay(row.tekst)
                                // ensure species list available, then open AddAliasDialog to create alias from partial's nameOnly
                                ensureAvailableSpeciesFlat { flat ->
                                    val dlg = AddAliasDialog.newInstance(listOf(nameOnly), flat)
                                    dlg.listener = object : AddAliasDialog.AddAliasListener {
                                        override fun onAliasAssigned(speciesId: String, aliasText: String) {
                                            lifecycleScope.launch {
                                                val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                                                val canonical = snapshot.speciesById[speciesId]?.soortnaam ?: aliasText
                                                val tilename = snapshot.speciesById[speciesId]?.soortkey

                                                val added = AliasManager.addAlias(
                                                    context = this@TellingScherm,
                                                    saf = safHelper,
                                                    speciesId = speciesId,
                                                    aliasText = aliasText.trim(),
                                                    canonical = canonical,
                                                    tilename = tilename
                                                )

                                                if (added) {
                                                    addLog("Alias toegevoegd: '$aliasText' → $canonical", "alias")
                                                    Toast.makeText(this@TellingScherm, "Alias opgeslagen (buffer).", Toast.LENGTH_SHORT).show()
                                                    // best-effort flush
                                                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                                                        try { AliasManager.forceFlush(this@TellingScherm, safHelper) } catch (_: Exception) {}
                                                    }

                                                    // Apply the partial's count to the species/tile immediately.
                                                    if (cnt > 0) {
                                                        // addSpeciesToTilesIfNeeded handles both add and update
                                                        addSpeciesToTilesIfNeeded(speciesId, canonical, cnt)
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
                            "final" -> {
                                // Final entry tapped: quick annotate dialog (placeholder)
                                AlertDialog.Builder(this@TellingScherm)
                                    .setTitle("Final waarneming")
                                    .setMessage("Acties voor: ${row.tekst}")
                                    .setPositiveButton("Annoteren") { _, _ ->
                                        Toast.makeText(this@TellingScherm, "Annotatie (nog niet geïmplementeerd)", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("Sluiten", null)
                                    .show()
                            }
                            else -> {
                                // Other types: e.g. raw can create alias and honor count
                                if (row.bron == "raw") {
                                    val (nameOnly, cnt) = parseNameAndCountFromDisplay(row.tekst)
                                    ensureAvailableSpeciesFlat { flat ->
                                        val dlg = AddAliasDialog.newInstance(listOf(nameOnly), flat)
                                        dlg.listener = object : AddAliasDialog.AddAliasListener {
                                            override fun onAliasAssigned(speciesId: String, aliasText: String) {
                                                lifecycleScope.launch {
                                                    val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                                                    val canonical = snapshot.speciesById[speciesId]?.soortnaam ?: aliasText
                                                    val added = AliasManager.addAlias(
                                                        context = this@TellingScherm,
                                                        saf = safHelper,
                                                        speciesId = speciesId,
                                                        aliasText = aliasText.trim(),
                                                        canonical = canonical,
                                                        tilename = snapshot.speciesById[speciesId]?.soortkey
                                                    )

                                                    if (added) {
                                                        addLog("Alias toegevoegd: '$aliasText' → $canonical", "alias")
                                                        Toast.makeText(this@TellingScherm, "Alias opgeslagen (buffer).", Toast.LENGTH_SHORT).show()
                                                        if (cnt > 0) addSpeciesToTilesIfNeeded(speciesId, canonical, cnt)
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
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    // Ensure availableSpeciesFlat is loaded; onReady is invoked on Main with the flat list.
    private fun ensureAvailableSpeciesFlat(onReady: (List<String>) -> Unit) {
        if (availableSpeciesFlat.isNotEmpty()) {
            onReady(availableSpeciesFlat)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                val flat = snapshot.speciesById.map { (id, s) -> "$id||${s.soortnaam}" }.toList()
                withContext(Dispatchers.Main) {
                    availableSpeciesFlat = flat
                    onReady(flat)
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    Log.w(TAG, "ensureAvailableSpeciesFlat failed: ${ex.message}", ex)
                    Toast.makeText(this@TellingScherm, "Soortenlijst niet beschikbaar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /* ---------- TILE click dialog (adds to existing count) ---------- */
    private fun showNumberInputDialog(position: Int) {
        val current = tilesAdapter.currentList
        if (position !in current.indices) return
        val row = current[position]

        // Empty default so user types a number to ADD
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Aantal om toe te voegen"
            setText("")
            setSelection(0)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }

        AlertDialog.Builder(this)
            .setTitle(row.naam)
            .setMessage("Aantal toevoegen aan bestaand aantal (${row.count}):")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text?.toString()?.trim()
                val delta = v?.toIntOrNull() ?: 0
                if (delta < 0) {
                    Toast.makeText(this, "Ongeldig aantal.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val updated = withContext(Dispatchers.Default) {
                        val cur = tilesAdapter.currentList
                        val tmp = ArrayList(cur)
                        val currentRow = tmp[position]
                        val newCount = currentRow.count + delta
                        tmp[position] = currentRow.copy(count = newCount)
                        tmp
                    }
                    withContext(Dispatchers.Main) {
                        tilesAdapter.submitList(updated)
                        // Manual update -> log as manual using the desired format
                        addLog("${row.naam} -> +$delta", "manueel")
                        RecentSpeciesStore.recordUse(this@TellingScherm, row.soortId, maxEntries = 25)
                        // Refresh ASR hints and cached context
                        updateSelectedSpeciesMap()
                    }
                }
            }
            .show()
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
        binding.recyclerViewSpecies.itemAnimator?.changeDuration = 0

        tilesAdapter = SpeciesTileAdapter(onTileClick = { pos -> showNumberInputDialog(pos) })
        binding.recyclerViewSpecies.adapter = tilesAdapter
    }

    private fun setupButtons() {
        binding.btnAddSoorten.setOnClickListener { openSoortSelectieForAdd() }

        binding.btnAfronden.setOnClickListener {
            Toast.makeText(this, "Afronden (batch-upload) volgt later.", Toast.LENGTH_LONG).show()
        }

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

    /* Preselection: load initial tiles (if app passed a preselection state) */
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

                    // build and cache MatchContext now that tiles are set
                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            val t0 = System.currentTimeMillis()
                            val mc = buildMatchContext()
                            cachedMatchContext = mc
                            Log.d(TAG, "Initial cached MatchContext built (ms=${System.currentTimeMillis() - t0})")
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to build initial cached MatchContext: ${ex.message}", ex)
                        }
                    }

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

    /**
     * Build MatchContext (expensive) — still available for on-the-fly builds but we cache it
     * and refresh the cache when tile selection changes.
     */
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

            // Ensure alias parser is ready; do it on Main thread
            if (!::aliasParser.isInitialized) {
                aliasParser = AliasSpeechParser(this@TellingScherm, safHelper)
            }

            // load alias internals for ASR engine
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed: ${ex.message}", ex)
                }
            }

            // heavy parsing on Default; reuse aliasParser instance
            speechRecognitionManager.setOnHypothesesListener { hypotheses, partials ->
                val receivedAt = System.currentTimeMillis()
                lifecycleScope.launch(Dispatchers.Default) {
                    val parseStartAt = System.currentTimeMillis()
                    Log.d(TAG, "Hypotheses received at $receivedAt, starting parse at $parseStartAt (hypotheses=${hypotheses.size}, partials=${partials.size})")
                    try {
                        // Use cached MatchContext when available to save time
                        val matchContext = cachedMatchContext ?: run {
                            val t0 = System.currentTimeMillis()
                            val mc = buildMatchContext()
                            cachedMatchContext = mc
                            Log.d(TAG, "buildMatchContext (on-the-fly) ms=${System.currentTimeMillis() - t0}")
                            mc
                        }

                        val result = aliasParser.parseSpokenWithHypotheses(hypotheses, matchContext, partials, asrWeight = 0.4)
                        val parseEndAt = System.currentTimeMillis()
                        Log.d(TAG, "Parse finished at $parseEndAt (parseDuration=${parseEndAt - parseStartAt} ms)")

                        withContext(Dispatchers.Main) {
                            val uiStartAt = System.currentTimeMillis()
                            try {
                                when (result) {
                                    is MatchResult.AutoAccept -> {
                                        // Final recognized match -> add final log (formatted) and update counts without extra 'Bijgewerkt' log
                                        val formatted = "${result.candidate.displayName} -> +${result.amount}"
                                        addFinalLog(formatted)
                                        updateSoortCountInternal(result.candidate.speciesId, result.amount)
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
                                                // when user confirms, treat as final
                                                addSpeciesToTiles(result.candidate.speciesId, result.candidate.displayName, cnt)
                                                addFinalLog("${result.candidate.displayName} -> +$cnt")
                                            }
                                            .setNegativeButton("Nee", null)
                                            .show()
                                    }
                                    is MatchResult.MultiMatch -> {
                                        result.matches.forEach { match ->
                                            if (match.candidate.isInTiles) {
                                                addFinalLog("${match.candidate.displayName} -> +${match.amount}")
                                                updateSoortCountInternal(match.candidate.speciesId, match.amount)
                                                RecentSpeciesStore.recordUse(this@TellingScherm, match.candidate.speciesId, maxEntries = 25)
                                            } else {
                                                val prettyName = match.candidate.displayName
                                                val msg = "Soort \"$prettyName\" (${match.amount}x) herkend.\n\nToevoegen?"
                                                AlertDialog.Builder(this@TellingScherm)
                                                    .setTitle("Soort toevoegen?")
                                                    .setMessage(msg)
                                                    .setPositiveButton("Ja") { _, _ ->
                                                        addSpeciesToTiles(match.candidate.speciesId, prettyName, match.amount)
                                                        addFinalLog("$prettyName -> +${match.amount}")
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
                                        // Treat as a partial hypothesis: show only last partial in log (ignore blanks)
                                        val now = System.currentTimeMillis()
                                        if (now - lastPartialUiUpdateMs >= PARTIAL_UI_DEBOUNCE_MS) {
                                            // Format and upsert partial display (includes count when present)
                                            upsertPartialLog(result.hypothesis)
                                            lastPartialUiUpdateMs = now
                                        } else {
                                            upsertPartialLog(result.hypothesis)
                                        }
                                    }
                                }
                            } catch (ex: Exception) {
                                Log.w(TAG, "Hypotheses handling (UI) failed: ${ex.message}", ex)
                            } finally {
                                val uiEndAt = System.currentTimeMillis()
                                Log.d(TAG, "UI handling finished at $uiEndAt (uiDuration=${uiEndAt - uiStartAt} ms)")
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Hypotheses handling (background) failed: ${ex.message}", ex)
                    }
                }
            }

            speechRecognitionManager.setOnRawResultListener { rawText ->
                lifecycleScope.launch(Dispatchers.Main) {
                    // final raw result — append as raw (non-partial)
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

    /* ---------- Helper log functions ---------- */

    // Primary addLog implementation: append a row (used by callers)
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
            val newList = withContext(Dispatchers.Default) {
                val current = logAdapter.currentList
                val list = ArrayList(current)
                list.add(newRow)
                if (list.size > MAX_LOG_ROWS) {
                    val drop = list.size - MAX_LOG_ROWS
                    repeat(drop) { list.removeAt(0) }
                }
                list
            }

            logAdapter.submitList(newList) {
                binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
            }
        }
    }

    // Parse a text that can be either:
    // - raw hypothesis like "atalanta 3" or "atalanta"
    // - formatted display like "Atalanta -> +3"
    // Returns Pair(nameOnly, count)
    private fun parseNameAndCountFromDisplay(text: String): Pair<String, Int> {
        val t = text.trim()
        if (t.isEmpty()) return "" to 0

        // Try formatted "Name -> +N"
        val arrowIdx = t.indexOf("->")
        if (arrowIdx >= 0) {
            val left = t.substring(0, arrowIdx).trim()
            val right = t.substring(arrowIdx + 2).trim()
            val m = Regex("""\+?(\d+)""").find(right)
            val cnt = m?.groups?.get(1)?.value?.toIntOrNull() ?: 0
            return left to cnt
        }

        // Try trailing number pattern e.g. "Name 3" or "Name 3.0" etc.
        val m = Regex("""^(.*?)(?:\s+(\d+)(?:[.,]\d+)?)?$""").find(t)
        return if (m != null) {
            val name = m.groups[1]?.value?.trim().orEmpty()
            val cnt = m.groups[2]?.value?.toIntOrNull() ?: 0
            name to cnt
        } else {
            t to 0
        }
    }

    // Insert or replace the single partial log entry (bron = "partial")
    // Now ignores blanks and formats partial display to include count when present.
    private fun upsertPartialLog(text: String) {
        val cleanedRaw = text.trim()
        // Ignore empty partials (common at start of capture)
        if (cleanedRaw.isBlank()) return

        // Parse name and count from raw hypothesis
        val (nameOnly, cnt) = run {
            val m = Regex("""^(.*?)(?:\s+(\d+)(?:[.,]\d+)?)?$""").find(cleanedRaw)
            if (m != null) {
                val name = m.groups[1]?.value?.trim().orEmpty()
                val c = m.groups[2]?.value?.toIntOrNull() ?: 0
                name to c
            } else {
                cleanedRaw to 0
            }
        }

        // Compose display text: if count present, format "Name -> +N", else plain name
        val display = if (cnt > 0) "$nameOnly -> +$cnt" else nameOnly

        lifecycleScope.launch {
            val newList = withContext(Dispatchers.Default) {
                val current = logAdapter.currentList
                // Remove existing partial entries
                val filtered = current.filter { it.bron != "partial" }.toMutableList()

                // If previous partial existed and text identical (display), only update timestamp by adding new row
                val existingPartialIndex = current.indexOfFirst { it.bron == "partial" }
                val now = System.currentTimeMillis() / 1000L
                val newRow = SpeechLogRow(ts = now, tekst = display, bron = "partial")

                if (existingPartialIndex != -1) {
                    val prev = current[existingPartialIndex]
                    if (prev.tekst == display) {
                        // identical — update timestamp by adding the newRow (filtered already removed old)
                        filtered.add(newRow)
                    } else {
                        filtered.add(newRow)
                    }
                } else {
                    filtered.add(newRow)
                }

                if (filtered.size > MAX_LOG_ROWS) {
                    val drop = filtered.size - MAX_LOG_ROWS
                    repeat(drop) { filtered.removeAt(0) }
                }
                filtered
            }
            logAdapter.submitList(newList) {
                binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
            }
        }
    }

    // Append a final log entry (bron = "final") formatted for quick recognition
    // Also remove any existing partials so final doesn't sit next to a stale partial.
    private fun addFinalLog(text: String) {
        val now = System.currentTimeMillis() / 1000L
        val newRow = SpeechLogRow(ts = now, tekst = text, bron = "final")

        lifecycleScope.launch {
            val newList = withContext(Dispatchers.Default) {
                val current = logAdapter.currentList
                // Remove partials (we show only final after recognition)
                val filtered = current.filter { it.bron != "partial" }.toMutableList()
                filtered.add(newRow)
                if (filtered.size > MAX_LOG_ROWS) {
                    val drop = filtered.size - MAX_LOG_ROWS
                    repeat(drop) { filtered.removeAt(0) }
                }
                filtered
            }
            logAdapter.submitList(newList) {
                binding.recyclerViewSpeechLog.scrollToPosition(newList.size - 1)
            }
        }
    }

    /* ---------- Suggestion / Add / Tiles helpers ---------- */
    private fun showSuggestionBottomSheet(candidates: List<Candidate>, count: Int) {
        val items = candidates.map { "${it.displayName} (score: ${"%.2f".format(it.score)})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Kies soort")
            .setItems(items) { _, which ->
                val chosen = candidates[which]
                if (chosen.isInTiles) {
                    // speech flow: final formatted log + internal update (no separate 'Bijgewerkt' log)
                    addFinalLog("${chosen.displayName} -> +$count")
                    updateSoortCountInternal(chosen.speciesId, count)
                } else {
                    val msg = "Soort \"${chosen.displayName}\" toevoegen en $count noteren?"
                    AlertDialog.Builder(this)
                        .setTitle("Soort toevoegen?")
                        .setMessage(msg)
                        .setPositiveButton("Ja") { _, _ ->
                            addSpeciesToTiles(chosen.speciesId, chosen.displayName, count)
                            addFinalLog("${chosen.displayName} -> +$count")
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
            // manual flow — use updateSoortCount to also create a manual log
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
                    // manual flow uses updateSoortCount (which logs)
                    updateSoortCount(soortId, initialCount)
                    return@launch
                }

                val newRow = SoortRow(soortId, canonical, initialCount)
                val updated = ArrayList(current)
                updated.add(newRow)

                withContext(Dispatchers.Main) {
                    tilesAdapter.submitList(updated)
                    // refresh cached MatchContext and ASR hints
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

    // Updates tile count and logs as 'manueel' by default (keeps previous behaviour).
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

            val updatedList = withContext(Dispatchers.Default) {
                val tmp = ArrayList(currentList)
                tmp[pos] = item.copy(count = newCount)
                tmp
            }

            withContext(Dispatchers.Main) {
                tilesAdapter.submitList(updatedList)
                // Manual / UI flows want a readable manual log
                addLog("${item.naam} -> +$count", "manueel")
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
                // Keep cached context up to date
                updateSelectedSpeciesMap()
            }
        }
    }

    // Internal update used by parser flows, does NOT create a 'Bijgewerkt' log line.
    private fun updateSoortCountInternal(soortId: String, count: Int) {
        lifecycleScope.launch {
            val currentList = tilesAdapter.currentList
            val pos = currentList.indexOfFirst { it.soortId == soortId }
            if (pos == -1) {
                Log.e(TAG, "Species with ID $soortId not found in the list!")
                return@launch
            }

            val item = currentList[pos]
            val newCount = item.count + count

            val updatedList = withContext(Dispatchers.Default) {
                val tmp = ArrayList(currentList)
                tmp[pos] = item.copy(count = newCount)
                tmp
            }

            withContext(Dispatchers.Main) {
                tilesAdapter.submitList(updatedList)
                // Do not add a 'Bijgewerkt' log — caller (parser) will create addFinalLog when appropriate.
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
                // Keep cached context up to date
                updateSelectedSpeciesMap()
            }
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

        // Rebuild cached MatchContext in background (safe: buildMatchContext uses Dispatchers.IO internally)
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val t0 = System.currentTimeMillis()
                val mc = buildMatchContext()
                cachedMatchContext = mc
                Log.d(TAG, "Rebuilt cached MatchContext (ms=${System.currentTimeMillis() - t0})")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to rebuild cached MatchContext: ${ex.message}", ex)
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

        // Ensure pending alias writes are flushed
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                AliasManager.forceFlush(this@TellingScherm, safHelper)
            } catch (ex: Exception) {
                Log.w(TAG, "forceFlush in onDestroy failed: ${ex.message}", ex)
            }
        }
    }

    // Extract a numeric count from a string, fallback to 1
    private fun extractCountFromText(text: String): Int {
        val m = Regex("\\b(\\d+)\\b").find(text)
        return m?.groups?.get(1)?.value?.toIntOrNull() ?: 1
    }

    // Launch soort selectie
    private fun openSoortSelectieForAdd() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(this, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }
}