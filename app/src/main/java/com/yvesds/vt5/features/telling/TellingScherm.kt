@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.VT5App
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
import com.yvesds.vt5.features.network.DataUploader
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.ServerTellingEnvelope
import com.yvesds.vt5.net.TrektellenApi
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.jvm.Volatile
import androidx.core.content.edit

/**
 * TellingScherm.kt
 *
 * Preserves existing functionality (ASR parsing, tiles, logs, aliases) and adds:
 * - Optional ViewModel mirroring so UI state survives rotation
 * - Confirmation before Afronden
 * - Dialog text styling helper to force white text for readability in sunlight
 *
 * I did not remove any original logic; I only added mirroring calls to the ViewModel
 * and the dialog styling/confirmation.
 */
class TellingScherm : AppCompatActivity() {

    companion object {
        private const val TAG = "TellingScherm"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 101

        // JSON's
        private val PRETTY_JSON: Json by lazy { Json { prettyPrint = true } }

        // Preferences keys
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_ASR_SILENCE_MS = "pref_asr_silence_ms"

        // Keys used across app
        private const val PREF_ONLINE_ID = "pref_online_id"
        private const val PREF_TELLING_ID = "pref_telling_id"
        private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"

        // Default silence ms: set low for testing; make configurable in prefs
        private const val DEFAULT_SILENCE_MS = 1000

        private const val MAX_LOG_ROWS = 600
    }

    // UI & adapters
    private lateinit var binding: SchermTellingBinding
    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var partialsAdapter: SpeechLogAdapter
    private lateinit var finalsAdapter: SpeechLogAdapter

    // ViewModel (optional mirror for rotation persistence) - ensure TellingViewModel.kt is present
    private lateinit var viewModel: TellingViewModel

    // Speech components
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private var speechInitialized = false

    // Parser instance
    private lateinit var aliasParser: AliasSpeechParser

    // Repos/helpers
    private lateinit var aliasEditor: AliasEditor
    private val safHelper by lazy { SaFStorageHelper(this) }

    // NEW: Helper classes for refactored code
    private lateinit var logManager: TellingLogManager
    private lateinit var dialogHelper: TellingDialogHelper
    private lateinit var backupManager: TellingBackupManager
    private lateinit var dataProcessor: TellingDataProcessor
    private lateinit var uiManager: TellingUiManager
    private lateinit var afrondHandler: TellingAfrondHandler
    private lateinit var tegelBeheer: TegelBeheer

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

    // Local pendingRecords (legacy) — we mirror to ViewModel for persistence but keep this for compatibility
    private val pendingRecords = mutableListOf<ServerTellingDataItem>()

    // Track backup files created per-record (DocumentFile or internal path strings)
    private val pendingBackupDocs = mutableListOf<DocumentFile>()
    private val pendingBackupInternalPaths = mutableListOf<String>()

    // BroadcastReceiver: listen for alias-reload events from AliasManager
    private val aliasReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(AliasRepository.EXTRA_RELOAD_SUCCESS, true) ?: true
            if (!success) {
                Log.w(TAG, "Received alias reload broadcast but success flag=false")
                return
            }

            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val t0 = System.currentTimeMillis()
                    val mc = buildMatchContext()
                    cachedMatchContext = mc
                    Log.d(TAG, "cached MatchContext rebuilt after alias reload (ms=${System.currentTimeMillis() - t0})")
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed rebuilding cachedMatchContext after alias reload: ${ex.message}", ex)
                }

                // Ensure SpeechRecognitionManager (if present) reloads alias hints on main
                withContext(Dispatchers.Main) {
                    try {
                        if (this@TellingScherm::speechRecognitionManager.isInitialized) {
                            speechRecognitionManager.loadAliases()
                            Log.d(TAG, "speechRecognitionManager.loadAliases invoked after alias reload")
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to reload ASR aliases after alias reload: ${ex.message}", ex)
                    }
                }
            }
        }
    }

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
                            // Mirror to ViewModel if present
                            if (::viewModel.isInitialized) viewModel.setTiles(merged)

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

    // ActivityResultLauncher for AnnotatieScherm results; prefer new JSON payload, fallback to legacy fields
    // Full ActivityResult callback (replace the existing annotationLauncher registration body).
    // This is the function that processes the result Intent from AnnotatieScherm and calls the helper above.
    // Full ActivityResult callback (replace existing annotationLauncher body with this).
    private val annotationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult

        val annotationsJson = data.getStringExtra(AnnotatieScherm.EXTRA_ANNOTATIONS_JSON)
        val legacyText = data.getStringExtra(AnnotatieScherm.EXTRA_TEXT)
        val legacyTs = data.getLongExtra(AnnotatieScherm.EXTRA_TS, 0L)
        val rowPos = data.getIntExtra("extra_row_pos", -1)

        if (!annotationsJson.isNullOrBlank()) {
            try {
                applyAnnotationsToPendingRecord(annotationsJson, rowTs = legacyTs, rowPos = rowPos)
            } catch (ex: Exception) {
                Log.w(TAG, "annotationLauncher: applyAnnotationsToPendingRecord failed: ${ex.message}", ex)
            }
        } else {
            // Legacy fallback: if legacyText present, store as opmerkingen if we can match record by timestamp
            if (!legacyText.isNullOrBlank() && legacyTs > 0L) {
                try {
                    val singleMapJson = kotlinx.serialization.json.Json.encodeToString(mapOf("opmerkingen" to legacyText))
                    applyAnnotationsToPendingRecord(singleMapJson, rowTs = legacyTs, rowPos = rowPos)
                } catch (ex: Exception) {
                    Log.w(TAG, "annotationLauncher: legacy apply failed: ${ex.message}", ex)
                }
            }
        }

        // UI feedback: keep existing log behaviour
        runCatching {
            val summary = if (!annotationsJson.isNullOrBlank()) {
                val map = kotlinx.serialization.json.Json.decodeFromString<Map<String, String?>>(annotationsJson)
                map.entries.joinToString(", ") { (k, v) -> "$k=${v ?: ""}" }
            } else {
                legacyText ?: ""
            }
            if (summary.isNotBlank()) addLog("Annotatie toegepast: $summary", "annotatie")
        }.onFailure { ex ->
            Log.w(TAG, "annotationLauncher: summarizing annotation failed: ${ex.message}", ex)
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
        
        // Initialize helper classes
        initializeHelpers()

        // Initialize legacy helpers
        aliasEditor = AliasEditor(this, safHelper)

        // Setup UI using UiManager
        setupUiWithManager()

        // Initialize ViewModel (if you have TellingViewModel in project)
        try {
            viewModel = ViewModelProvider(this).get(TellingViewModel::class.java)
            // Observe VM lists and keep adapters in sync (this ensures rotation preserves UI)
            viewModel.tiles.observe(this) { tiles ->
                // update adapter (observer will call submitList callback if configured)
                uiManager.updateTiles(tiles)

                // Rebuild cachedMatchContext asynchronously on Default to avoid blocking parse path
                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        val t0 = System.currentTimeMillis()
                        val mc = buildMatchContext()
                        cachedMatchContext = mc
                        Log.d(TAG, "cachedMatchContext refreshed after tiles change (ms=${System.currentTimeMillis() - t0})")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed rebuilding cachedMatchContext after tiles change: ${ex.message}", ex)
                    }
                }
            }
            viewModel.partials.observe(this) { list ->
                uiManager.updatePartials(list)
            }
            viewModel.finals.observe(this) { list ->
                uiManager.updateFinals(list)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "TellingViewModel not available or failed to init: ${ex.message}")
        }

        // Register receiver to keep cached context and ASR in sync when AliasManager reloads index
        try {
            val filter = IntentFilter(AliasRepository.ACTION_ALIAS_RELOAD_COMPLETED)
            // minSdk is Android 13+, so use the API-33 overload and explicitly mark NOT_EXPORTED
            registerReceiver(aliasReloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "Registered aliasReloadReceiver (NOT_EXPORTED)")
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to register aliasReloadReceiver: ${ex.message}", ex)
        }

        // Preload tiles (if preselected) then initialize ASR
        loadPreselection()
    }

    /**
     * Initialize all helper classes for refactored code.
     */
    private fun initializeHelpers() {
        logManager = TellingLogManager(MAX_LOG_ROWS)
        dialogHelper = TellingDialogHelper(this, this, safHelper)
        backupManager = TellingBackupManager(this, safHelper)
        dataProcessor = TellingDataProcessor()
        uiManager = TellingUiManager(this, this, binding)
        afrondHandler = TellingAfrondHandler(this, backupManager, dataProcessor)
        
        // Initialize TegelBeheer with UI callback
        tegelBeheer = TegelBeheer(object : TegelUi {
            override fun submitTiles(list: List<SoortTile>) {
                // Convert SoortTile to SoortRow for adapter
                val rows = list.map { SoortRow(it.soortId, it.naam, it.count) }
                tilesAdapter.submitList(rows)
                if (::viewModel.isInitialized) {
                    viewModel.setTiles(rows)
                }
            }
            
            override fun onTileCountUpdated(soortId: String, newCount: Int) {
                // Optional: trigger any additional actions on count update
            }
        })
    }

    /**
     * Setup UI using the UiManager helper.
     */
    private fun setupUiWithManager() {
        // Setup RecyclerViews
        uiManager.setupPartialsRecyclerView()
        uiManager.setupFinalsRecyclerView()
        uiManager.setupSpeciesTilesRecyclerView()

        // Store adapter references for backward compatibility
        partialsAdapter = uiManager.getCurrentPartials().let { SpeechLogAdapter().apply { submitList(it) } }
        finalsAdapter = uiManager.getCurrentFinals().let { SpeechLogAdapter().apply { submitList(it) } }
        tilesAdapter = SpeciesTileAdapter { position -> 
            showNumberInputDialog(position) 
        }
        
        // Setup callbacks for UI manager
        uiManager.onPartialTapCallback = { pos, row -> handlePartialTap(pos, row) }
        uiManager.onFinalTapCallback = { pos, row -> handleFinalTap(pos, row) }
        uiManager.onTileTapCallback = { pos -> showNumberInputDialog(pos) }
        uiManager.onAddSoortenCallback = { openSoortSelectieForAdd() }
        uiManager.onAfrondenCallback = { handleAfrondenWithConfirmation() }
        uiManager.onSaveCloseCallback = { tiles -> handleSaveClose(tiles) }

        // Setup buttons
        uiManager.setupButtons()
    }

    /* ---------- UI Callback Handlers ---------- */

    /**
     * Handle tap on partial log entry - show alias dialog.
     */
    private fun handlePartialTap(pos: Int, row: SpeechLogRow) {
        when (row.bron) {
            "partial", "raw" -> {
                val (nameOnly, cnt) = parseNameAndCountFromDisplay(row.tekst)
                ensureAvailableSpeciesFlat { flat ->
                    dialogHelper.showAddAliasDialog(nameOnly, cnt, flat, 
                        onAliasAdded = { speciesId, canonical, count ->
                            addLog("Alias toegevoegd: '$nameOnly' → $canonical", "alias")
                            Toast.makeText(this, "Alias opgeslagen (buffer).", Toast.LENGTH_SHORT).show()
                            
                            lifecycleScope.launch {
                                Toast.makeText(this@TellingScherm, "Index wordt bijgewerkt...", Toast.LENGTH_SHORT).show()
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        AliasManager.forceRebuildCborNow(this@TellingScherm, safHelper)
                                        true
                                    } catch (ex: Exception) {
                                        Log.w(TAG, "forceRebuildCborNow failed: ${ex.message}", ex)
                                        false
                                    }
                                }
                                if (ok) {
                                    Toast.makeText(this@TellingScherm, "Alias opgeslagen en index bijgewerkt", Toast.LENGTH_SHORT).show()
                                    refreshAliasesRuntimeAsync()
                                } else {
                                    Toast.makeText(this@TellingScherm, "Alias opgeslagen (index update later)", Toast.LENGTH_LONG).show()
                                }
                            }
                            
                            if (count > 0) {
                                addSpeciesToTilesIfNeeded(speciesId, canonical, count)
                            }
                        },
                        fragmentManager = supportFragmentManager
                    )
                }
            }
        }
    }

    /**
     * Handle tap on final log entry - open annotation screen.
     */
    private fun handleFinalTap(pos: Int, row: SpeechLogRow) {
        if (row.bron == "final") {
            val intent = Intent(this, AnnotatieScherm::class.java).apply {
                putExtra(AnnotatieScherm.EXTRA_TEXT, row.tekst)
                putExtra(AnnotatieScherm.EXTRA_TS, row.ts)
                putExtra("extra_row_pos", pos)
            }
            annotationLauncher.launch(intent)
        }
    }

    /**
     * Handle Afronden button with confirmation dialog.
     */
    private fun handleAfrondenWithConfirmation() {
        val builder = AlertDialog.Builder(this)
            .setTitle("Afronden bevestigen")
            .setMessage("Weet je zeker dat je wilt afronden en de telling uploaden?")
            .setPositiveButton("Ja") { _, _ ->
                lifecycleScope.launch {
                    val dialog = ProgressDialogHelper.show(this@TellingScherm, "Bezig met afronden upload...")
                    try { 
                        handleAfronden() 
                    } finally { 
                        dialog.dismiss() 
                    }
                }
            }
            .setNegativeButton("Nee", null)
        
        val dlg = builder.show()
        dialogHelper.styleAlertDialogTextToWhite(dlg)
    }

    /**
     * Handle Save/Close button - show current status screen.
     */
    private fun handleSaveClose(tiles: List<SoortRow>) {
        val ids = ArrayList<String>(tiles.size)
        val names = ArrayList<String>(tiles.size)
        val counts = ArrayList<String>(tiles.size)
        for (row in tiles) {
            ids.add(row.soortId)
            names.add(row.naam)
            counts.add(row.count.toString())
        }

        val intent = Intent(this, HuidigeStandScherm::class.java).apply {
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_IDS, ids)
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_NAMEN, names)
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_AANTALLEN, counts)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(aliasReloadReceiver)
        } catch (_: Exception) {}
        try {
            if (::volumeKeyHandler.isInitialized) {
                volumeKeyHandler.unregister()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /* ---------- UI setup (now delegated to UiManager) ---------- */

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
        dialogHelper.showNumberInputDialog(position, current) { soortId, delta ->
            lifecycleScope.launch {
                // Use tegelBeheer to update tile count
                val naam = tegelBeheer.findNaamBySoortId(soortId) ?: "Unknown"
                tegelBeheer.verhoogSoortAantal(soortId, delta)

                // Behave exactly like an ASR final:
                addFinalLog("$naam -> +$delta")
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
                collectFinalAsRecord(soortId, delta)
            }
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
                            SoortTile(sid, naam, 0)
                        }.sortedBy { it.naam.lowercase(Locale.getDefault()) }

                        snapshot to initialList
                    }

                    if (initial.isEmpty()) {
                        dialog.dismiss()
                        Toast.makeText(this@TellingScherm, "Geen voorselectie. Keer terug en selecteer soorten.", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }

                    // Use tegelBeheer to set tiles
                    tegelBeheer.setTiles(initial)
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

        // Use tegelBeheer to get current tiles
        val tiles = tegelBeheer.getTiles().map { it.soortId }.toSet()

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
                handleSpeechHypotheses(hypotheses, partials)
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

    /**
     * Handle speech recognition hypotheses and process match results.
     */
    private fun handleSpeechHypotheses(hypotheses: List<Pair<String, Float>>, partials: List<String>) {
        val receivedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.Default) {
            val parseStartAt = System.currentTimeMillis()
            Log.d(TAG, "Hypotheses received at $receivedAt, starting parse at $parseStartAt (hypotheses=${hypotheses.size}, partials=${partials.size})")
            try {
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
                        handleMatchResult(result)
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

    /**
     * Handle different types of match results from speech parsing.
     */
    private fun handleMatchResult(result: MatchResult) {
        when (result) {
            is MatchResult.AutoAccept -> {
                handleAutoAcceptMatch(result)
            }
            is MatchResult.AutoAcceptAddPopup -> {
                handleAutoAcceptAddPopup(result)
            }
            is MatchResult.MultiMatch -> {
                handleMultiMatch(result)
            }
            is MatchResult.SuggestionList -> {
                val cnt = logManager.extractCountFromText(result.hypothesis)
                showSuggestionBottomSheet(result.candidates, cnt)
            }
            is MatchResult.NoMatch -> {
                val now = System.currentTimeMillis()
                if (now - lastPartialUiUpdateMs >= PARTIAL_UI_DEBOUNCE_MS) {
                    upsertPartialLog(result.hypothesis)
                    lastPartialUiUpdateMs = now
                } else {
                    upsertPartialLog(result.hypothesis)
                }
            }
        }
    }

    private fun handleAutoAcceptMatch(result: MatchResult.AutoAccept) {
        recordSpeciesCount(result.candidate.speciesId, result.candidate.displayName, result.amount)
    }

    private fun handleAutoAcceptAddPopup(result: MatchResult.AutoAcceptAddPopup) {
        val cnt = result.amount
        val prettyName = result.candidate.displayName
        val speciesId = result.candidate.speciesId

        // Check if species is already in tiles using tegelBeheer
        val presentInTiles = tegelBeheer.findIndexBySoortId(speciesId) >= 0
        if (presentInTiles) {
            recordSpeciesCount(speciesId, prettyName, cnt)
        } else {
            showAddSpeciesConfirmationDialog(speciesId, prettyName, cnt)
        }
    }

    /**
     * Record a species count (add final log, update count, collect record).
     */
    private fun recordSpeciesCount(speciesId: String, displayName: String, count: Int) {
        addFinalLog("$displayName -> +$count")
        updateSoortCountInternal(speciesId, count)
        RecentSpeciesStore.recordUse(this, speciesId, maxEntries = 25)
        collectFinalAsRecord(speciesId, count)
    }

    /**
     * Show confirmation dialog for adding a new species to tiles.
     */
    private fun showAddSpeciesConfirmationDialog(speciesId: String, displayName: String, count: Int) {
        val msg = "Soort \"$displayName\" herkend met aantal $count.\n\nToevoegen?"
        val dlg = AlertDialog.Builder(this)
            .setTitle("Soort toevoegen?")
            .setMessage(msg)
            .setPositiveButton("Ja") { _, _ ->
                addSpeciesToTiles(speciesId, displayName, count)
                addFinalLog("$displayName -> +$count")
                collectFinalAsRecord(speciesId, count)
            }
            .setNegativeButton("Nee", null)
            .show()
        dialogHelper.styleAlertDialogTextToWhite(dlg)
    }

    private fun handleMultiMatch(result: MatchResult.MultiMatch) {
        result.matches.forEach { match ->
            val sid = match.candidate.speciesId
            val cnt = match.amount
            val present = tegelBeheer.findIndexBySoortId(sid) >= 0
            if (present) {
                recordSpeciesCount(sid, match.candidate.displayName, cnt)
            } else {
                showAddSpeciesConfirmationDialog(sid, match.candidate.displayName, cnt)
            }
        }
    }

    /* ---------- Helper log functions (delegated to logManager) ---------- */

    // Primary addLog implementation: delegate to logManager then update UI
    private fun addLog(msgIn: String, bron: String) {
        val newList = logManager.addLog(msgIn, bron)
        if (newList != null) {
            updateLogsUi(newList, bron)
        }
    }

    // Parse a text using logManager
    private fun parseNameAndCountFromDisplay(text: String): Pair<String, Int> {
        return logManager.parseNameAndCountFromDisplay(text)
    }

    // Update logs UI after changes
    // Routing: "final" → finals adapter, everything else → partials adapter (matching 4e5359e behavior)
    private fun updateLogsUi(newList: List<SpeechLogRow>, bron: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (::viewModel.isInitialized) {
                // UPDATE VIEWMODEL ONLY — observers will update adapters once.
                if (bron == "final") {
                    viewModel.setFinals(newList)
                    // remove 'partial' rows from partials (keep non-partial logs)
                    val preserved = logManager.getPartials().filter { it.bron != "partial" }
                    viewModel.setPartials(preserved)
                } else {
                    // raw, partial, systeem all go to partials
                    viewModel.setPartials(newList)
                }
            } else {
                // Fallback: no ViewModel — update adapter via uiManager
                if (bron == "final") {
                    uiManager.updateFinals(newList)
                    // clear partials from UI
                    val preserved = logManager.getPartials().filter { it.bron != "partial" }
                    uiManager.updatePartials(preserved)
                } else {
                    // raw, partial, systeem all go to partials
                    uiManager.updatePartials(newList)
                }
            }
        }
    }

    // Insert or replace the single partial log entry (bron = "partial")
    // Now ignores blanks and formats partial display to include count when present.
    // Delegate to logManager for upsertPartialLog
    private fun upsertPartialLog(text: String) {
        val newList = logManager.upsertPartialLog(text)
        lifecycleScope.launch(Dispatchers.Main) {
            if (::viewModel.isInitialized) {
                viewModel.setPartials(newList)
            } else {
                uiManager.updatePartials(newList)
            }
        }
    }

    // Delegate to logManager for addFinalLog
    private fun addFinalLog(text: String) {
        val updatedFinals = logManager.addFinalLog(text)
        val updatedPartials = logManager.getPartials().filter { it.bron != "partial" }
        
        lifecycleScope.launch(Dispatchers.Main) {
            if (::viewModel.isInitialized) {
                viewModel.setFinals(updatedFinals)
                viewModel.setPartials(updatedPartials)
            } else {
                uiManager.updateFinals(updatedFinals)
                uiManager.updatePartials(updatedPartials)
            }
        }
    }

    /* ---------- Suggestion / Add / Tiles helpers (unchanged flows) ---------- */
    private fun showSuggestionBottomSheet(candidates: List<Candidate>, count: Int) {
        val items = candidates.map { "${it.displayName} (score: ${"%.2f".format(it.score)})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Kies soort")
            .setItems(items) { _, which ->
                val chosen = candidates[which]
                if (chosen.isInTiles) {
                    addFinalLog("${chosen.displayName} -> +$count")
                    updateSoortCountInternal(chosen.speciesId, count)
                    collectFinalAsRecord(chosen.speciesId, count)
                } else {
                    val msg = "Soort \"${chosen.displayName}\" toevoegen en $count noteren?"
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Soort toevoegen?")
                        .setMessage(msg)
                        .setPositiveButton("Ja") { _, _ ->
                            addSpeciesToTiles(chosen.speciesId, chosen.displayName, count)
                            addFinalLog("${chosen.displayName} -> +$count")
                            collectFinalAsRecord(chosen.speciesId, count)
                        }
                        .setNegativeButton("Nee", null)
                        .show()
                    dialogHelper.styleAlertDialogTextToWhite(dlg)
                }
                RecentSpeciesStore.recordUse(this, chosen.speciesId, maxEntries = 25)
            }
            .setNegativeButton("Annuleer", null)
            .show()
    }

    private fun addSpeciesToTilesIfNeeded(speciesId: String, canonical: String, extractedCount: Int) {
        lifecycleScope.launch {
            val added = tegelBeheer.voegSoortToeIndienNodig(speciesId, canonical, extractedCount)
            if (!added) {
                // Species already exists, just increase count
                tegelBeheer.verhoogSoortAantal(speciesId, extractedCount)
            }
            updateSelectedSpeciesMap()
            RecentSpeciesStore.recordUse(this@TellingScherm, speciesId, maxEntries = 25)
            addLog("Soort ${if (added) "toegevoegd" else "bijgewerkt"}: $canonical ($extractedCount)", "systeem")
        }
    }

    private fun addSpeciesToTiles(soortId: String, naam: String, initialCount: Int) {
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { ServerDataCache.getOrLoad(this@TellingScherm) }
                val canonical = snapshot.speciesById[soortId]?.soortnaam ?: naam

                tegelBeheer.voegSoortToe(soortId, canonical, initialCount, mergeIfExists = true)
                updateSelectedSpeciesMap()
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
                addLog("Soort toegevoegd: $canonical ($initialCount)", "systeem")
            } catch (ex: Exception) {
                Log.w(TAG, "addSpeciesToTiles failed: ${ex.message}", ex)
                addLog("Fout bij toevoegen soort ${naam}", "systeem")
            }
        }
    }

    // Updates tile count and logs as 'manueel' by default (keeps previous behaviour).
    private fun updateSoortCount(soortId: String, count: Int) {
        lifecycleScope.launch {
            val naam = tegelBeheer.findNaamBySoortId(soortId)
            if (naam == null) {
                Log.e(TAG, "Species with ID $soortId not found in the list!")
                return@launch
            }

            tegelBeheer.verhoogSoortAantal(soortId, count)
            addLog("$naam -> +$count", "manueel")
            RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
            updateSelectedSpeciesMap()
        }
    }

    // Internal update used by parser flows, does NOT create a 'Bijgewerkt' log line.
    private fun updateSoortCountInternal(soortId: String, count: Int) {
        lifecycleScope.launch {
            tegelBeheer.verhoogSoortAantal(soortId, count)
            RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 25)
            updateSelectedSpeciesMap()
        }
    }

    // Collect a final into pendingRecords and write per-final backup to SAF exports
    private fun collectFinalAsRecord(soortId: String, amount: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val tellingId = prefs.getString(PREF_TELLING_ID, null)
                if (tellingId.isNullOrBlank()) {
                    Log.w(TAG, "No PREF_TELLING_ID available - cannot collect final as record")
                    return@launch
                }

                val idLocal = DataUploader.getAndIncrementRecordId(this@TellingScherm, tellingId)
                val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
                val item = ServerTellingDataItem(
                    idLocal = idLocal,
                    tellingid = tellingId,
                    soortid = soortId,
                    aantal = amount.toString(),
                    richting = "",
                    aantalterug = "0",
                    richtingterug = "",
                    sightingdirection = "",
                    lokaal = "0",
                    aantal_plus = "0",
                    aantalterug_plus = "0",
                    lokaal_plus = "0",
                    markeren = "0",
                    markerenlokaal = "0",
                    geslacht = "",
                    leeftijd = "",
                    kleed = "",
                    opmerkingen = "",
                    trektype = "",
                    teltype = "",
                    location = "",
                    height = "",
                    tijdstip = nowEpoch,
                    groupid = idLocal,
                    uploadtijdstip = "",
                    totaalaantal = amount.toString()
                )

                // Add to in-memory list on main
                withContext(Dispatchers.Main) {
                    pendingRecords.add(item)
                    if (::viewModel.isInitialized) viewModel.addPendingRecord(item)
                }

                // Try SAF backup, else internal fallback using backupManager
                try {
                    val doc = backupManager.writeRecordBackupSaf(tellingId, item)
                    if (doc != null) {
                        pendingBackupDocs.add(doc)
                    } else {
                        val internal = backupManager.writeRecordBackupInternal(tellingId, item)
                        if (internal != null) pendingBackupInternalPaths.add(internal)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Record backup failed: ${ex.message}", ex)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TellingScherm, "Waarneming opgeslagen (buffer)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "collectFinalAsRecord failed: ${e.message}", e)
            }
        }
    }



    // Afronden: build counts_save envelope with saved metadata + pendingRecords, POST and handle response
    /**
     * Handle Afronden (finalize and upload) using afrondHandler.
     */
    private suspend fun handleAfronden() {
        val result = afrondHandler.handleAfronden(
            pendingRecords = synchronized(pendingRecords) { ArrayList(pendingRecords) },
            pendingBackupDocs = pendingBackupDocs,
            pendingBackupInternalPaths = pendingBackupInternalPaths
        )

        withContext(Dispatchers.Main) {
            when (result) {
                is TellingAfrondHandler.AfrondResult.Success -> {
                    // Cleanup local state
                    synchronized(pendingRecords) { pendingRecords.clear() }
                    pendingBackupDocs.clear()
                    pendingBackupInternalPaths.clear()
                    if (::viewModel.isInitialized) viewModel.clearPendingRecords()

                    // Show success dialog
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Afronden geslaagd")
                        .setMessage("Afronden upload geslaagd. Envelope opgeslagen: ${result.savedPrettyPath ?: "n.v.t."}")
                        .setPositiveButton("OK") { _, _ ->
                            val intent = Intent(this@TellingScherm, MetadataScherm::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            startActivity(intent)
                            finish()
                        }
                        .show()
                    dialogHelper.styleAlertDialogTextToWhite(dlg)
                }
                is TellingAfrondHandler.AfrondResult.Failure -> {
                    // Show failure dialog
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle(result.title)
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                    dialogHelper.styleAlertDialogTextToWhite(dlg)
                }
            }
        }
    }

    // Write pretty envelope JSON to SAF as "<timestamp>_count_<onlineid>.json"




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

        val speciesMap = tegelBeheer.buildSelectedSpeciesMap()
        if (speciesMap.isEmpty()) {
            Log.w(TAG, "Species list is empty! Cannot update selectedSpeciesMap")
            return
        }

        for ((soortId, naam) in speciesMap) {
            selectedSpeciesMap[naam] = soortId
            selectedSpeciesMap[naam.lowercase(Locale.getDefault())] = soortId
        }

        if (speechInitialized) {
            Log.d(TAG, "Selected species map updated (${speciesMap.size} species)")
        }
    }

    // Helper: asynchroon runtime alias index en ASR opnieuw laden na user-alias toevoeging
    private fun refreshAliasesRuntimeAsync() {
        lifecycleScope.launch {
            try {
                // 1) AliasMatcher reload (IO)
                withContext(Dispatchers.IO) {
                    try {
                        com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(this@TellingScherm, safHelper)
                        Log.d(TAG, "AliasMatcher.reloadIndex executed (post addAlias)")
                    } catch (ex: Exception) {
                        Log.w(TAG, "AliasMatcher.reloadIndex failed (post addAlias): ${ex.message}", ex)
                    }
                }

                // 2) ASR engine reload aliases (IO)
                withContext(Dispatchers.IO) {
                    try {
                        speechRecognitionManager.loadAliases()
                        Log.d(TAG, "speechRecognitionManager.loadAliases executed (post addAlias)")
                    } catch (ex: Exception) {
                        Log.w(TAG, "speechRecognitionManager.loadAliases failed (post addAlias): ${ex.message}", ex)
                    }
                }

                // 3) Rebuild cached MatchContext (Default)
                withContext(Dispatchers.Default) {
                    try {
                        val mc = buildMatchContext()
                        cachedMatchContext = mc
                        Log.d(TAG, "cachedMatchContext refreshed after user alias add")
                    } catch (ex: Exception) {
                        Log.w(TAG, "buildMatchContext failed (post addAlias): ${ex.message}", ex)
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "refreshAliasesRuntimeAsync overall failed: ${ex.message}", ex)
            }
        }
    }

    // Launch soort selectie
    private fun openSoortSelectieForAdd() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(this, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }
    // Helper: apply annotations JSON to the matching pending record in-memory (and write a single-record backup).
// Paste this function into TellingScherm (near other helpers).
    // Helper: apply annotations JSON to the matching pending record in-memory (and write a single-record backup).
    private fun applyAnnotationsToPendingRecord(
        annotationsJson: String,
        rowTs: Long = 0L,
        rowPos: Int = -1
    ) {
        try {
            // Json parser WITHOUT allowTrailingCommas (per jouw wens); ignoreUnknownKeys helps resilience.
            val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            // Decode to a map of storeKey -> value using the parser variable (fixes "unused variable" warning)
            val map: Map<String, String?> = try {
                parser.decodeFromString(annotationsJson)
            } catch (ex: Exception) {
                Log.w(TAG, "applyAnnotationsToPendingRecord: failed to decode annotations JSON: ${ex.message}", ex)
                return
            }

            synchronized(pendingRecords) {
                if (pendingRecords.isEmpty()) {
                    Log.w(TAG, "applyAnnotationsToPendingRecord: no pendingRecords to apply annotations to")
                    return
                }

                // Try to find matching pending record by rowPos -> finals timestamp -> pending.tijdstip
                var idx = -1
                if (rowPos >= 0) {
                    val finalsList = if (::viewModel.isInitialized) viewModel.finals.value.orEmpty() else finalsAdapter.currentList
                    val finalRowTs = finalsList.getOrNull(rowPos)?.ts
                    if (finalRowTs != null) {
                        idx = pendingRecords.indexOfFirst { it.tijdstip == finalRowTs.toString() }
                    }
                }

                // fallback: try by explicit rowTs if provided
                if (idx == -1 && rowTs > 0L) {
                    idx = pendingRecords.indexOfFirst { it.tijdstip == rowTs.toString() }
                }

                if (idx == -1) {
                    Log.w(TAG, "applyAnnotationsToPendingRecord: no matching pending record found (rowPos=$rowPos, rowTs=$rowTs)")
                    return
                }

                if (idx < 0 || idx >= pendingRecords.size) {
                    Log.w(TAG, "applyAnnotationsToPendingRecord: computed index out of bounds: $idx")
                    return
                }

                val old = pendingRecords[idx]

                // Create updated copy: only overwrite fields present in the annotations map,
                // otherwise retain existing values. Adjust field names if your ServerTellingDataItem differs.
                val updated = old.copy(
                    leeftijd = map["leeftijd"] ?: old.leeftijd,
                    geslacht = map["geslacht"] ?: old.geslacht,
                    kleed = map["kleed"] ?: old.kleed,
                    location = map["location"] ?: old.location,
                    height = map["height"] ?: old.height,
                    lokaal = map["lokaal"] ?: old.lokaal,
                    markeren = map["markeren"] ?: old.markeren,
                    opmerkingen = map["opmerkingen"] ?: map["remarks"] ?: old.opmerkingen
                )

                // Replace in-memory pending record
                pendingRecords[idx] = updated

                // Mirror to ViewModel if present
                if (::viewModel.isInitialized) {
                    viewModel.setPendingRecords(pendingRecords.toList())
                }

                Log.d(TAG, "Applied annotations to pendingRecords[$idx]: $map")

                // Attempt to write single-record backup so change is persisted to SAF (best-effort)
                try {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val tellingId = prefs.getString(PREF_TELLING_ID, null)
                    if (!tellingId.isNullOrBlank()) {
                        backupManager.writeRecordBackupSaf(tellingId, updated)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "applyAnnotationsToPendingRecord: backup write failed: ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "applyAnnotationsToPendingRecord failed: ${ex.message}", ex)
        }
    }

}