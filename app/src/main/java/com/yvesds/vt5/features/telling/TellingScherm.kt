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
import com.yvesds.vt5.R

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
        
        // Auto-dismiss delay for success dialog (ms)
        private const val SUCCESS_DIALOG_DELAY_MS = 2000L
        
        // Intent extra key for hourly alarm trigger
        const val EXTRA_SHOW_HUIDIGE_STAND = "SHOW_HUIDIGE_STAND"
    }

    // UI & adapters
    private lateinit var binding: SchermTellingBinding
    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var partialsAdapter: SpeechLogAdapter
    private lateinit var finalsAdapter: SpeechLogAdapter

    // ViewModel (optional mirror for rotation persistence) - ensure TellingViewModel.kt is present
    private lateinit var viewModel: TellingViewModel

    // Helper classes for refactored code
    private lateinit var logManager: TellingLogManager
    private lateinit var dialogHelper: TellingDialogHelper
    private lateinit var backupManager: TellingBackupManager
    private lateinit var dataProcessor: TellingDataProcessor
    private lateinit var uiManager: TellingUiManager
    private lateinit var afrondHandler: TellingAfrondHandler
    private lateinit var tegelBeheer: TegelBeheer
    private lateinit var speechHandler: TellingSpeechHandler
    private lateinit var matchResultHandler: TellingMatchResultHandler
    private lateinit var speciesManager: TellingSpeciesManager
    private lateinit var annotationHandler: TellingAnnotationHandler
    private lateinit var initializer: TellingInitializer
    private lateinit var alarmHandler: TellingAlarmHandler
    
    // Continuous envelope persistence - saves full envelope after each observation
    private lateinit var envelopePersistence: TellingEnvelopePersistence

    // Legacy helpers
    private lateinit var aliasEditor: AliasEditor
    private val safHelper by lazy { SaFStorageHelper(this) }

    // Prefs
    private lateinit var prefs: android.content.SharedPreferences

    // Partial UI debounce + only keep last partial
    private var lastPartialUiUpdateMs: Long = 0L
    private val PARTIAL_UI_DEBOUNCE_MS = 200L

    // Local pendingRecords (legacy) — we mirror to ViewModel for persistence but keep this for compatibility
    private val pendingRecords = mutableListOf<ServerTellingDataItem>()

    // Track backup files created per-record (DocumentFile or internal path strings)
    private val pendingBackupDocs = mutableListOf<DocumentFile>()
    private val pendingBackupInternalPaths = mutableListOf<String>()
    
    // Flag to show HuidigeStandScherm after tiles are loaded (triggered by hourly alarm)
    @Volatile
    private var pendingShowHuidigeStand = false

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
                    val tiles = tegelBeheer.getTiles().map { it.soortId }.toSet()
                    val mc = initializer.buildMatchContext(tiles)
                    speechHandler.updateCachedMatchContext(mc)
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed rebuilding cachedMatchContext after alias reload: ${ex.message}", ex)
                }

                // Reload speech recognition aliases
                speechHandler.loadAliases()
            }
        }
    }

    // Data models
    data class SoortRow(
        val soortId: String, 
        val naam: String, 
        val countMain: Int = 0,
        val countReturn: Int = 0
    ) {
        // Backwards compatible total count property
        val count: Int get() = countMain + countReturn
    }
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermTellingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if this is triggered by the hourly alarm (from HourlyAlarmManager)
        // Store the flag to show HuidigeStandScherm after tiles are loaded
        if (intent.getBooleanExtra(EXTRA_SHOW_HUIDIGE_STAND, false)) {
            pendingShowHuidigeStand = true
        }
        
        // Initialize helper classes BEFORE registering launchers
        // (partial initialization for those that need it)
        backupManager = TellingBackupManager(this, safHelper)
        
        // Initialize TegelBeheer early
        tegelBeheer = TegelBeheer(object : TegelUi {
            override fun submitTiles(list: List<SoortTile>) {
                val rows = list.map { SoortRow(it.soortId, it.naam, it.countMain, it.countReturn) }
                tilesAdapter.submitList(rows)
                if (::viewModel.isInitialized) {
                    viewModel.setTiles(rows)
                }
            }
            override fun onTileCountUpdated(soortId: String, newCount: Int) {}
        })
        
        // Initialize handlers that need launcher registration
        speciesManager = TellingSpeciesManager(this, this, safHelper, backupManager, tegelBeheer, PREFS_NAME)
        annotationHandler = TellingAnnotationHandler(this, backupManager, PREFS_NAME)
        
        // Register launchers before super.onCreate (required by ActivityResultContracts)
        speciesManager.registerLaunchers()
        annotationHandler.registerLauncher()
        
        // Initialize remaining helpers
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
                        val tilesIds = tiles.map { it.soortId }.toSet()
                        val mc = initializer.buildMatchContext(tilesIds)
                        speechHandler.updateCachedMatchContext(mc)
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
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to register aliasReloadReceiver: ${ex.message}", ex)
        }

        // Preload tiles (if preselected) then initialize ASR
        initializer.loadPreselection()
    }

    /**
     * Initialize all helper classes for refactored code.
     */
    private fun initializeHelpers() {
        logManager = TellingLogManager(MAX_LOG_ROWS)
        dialogHelper = TellingDialogHelper(this, this, safHelper)
        // backupManager already initialized before super.onCreate()
        dataProcessor = TellingDataProcessor()
        uiManager = TellingUiManager(this, binding)
        
        // Initialize envelope persistence for continuous backup
        envelopePersistence = TellingEnvelopePersistence(this, safHelper)
        
        // Initialize afrondHandler with envelope persistence for cleanup on success
        afrondHandler = TellingAfrondHandler(this, backupManager, dataProcessor, envelopePersistence)
        
        // tegelBeheer already initialized before super.onCreate()

        // Initialize new helper classes
        speechHandler = TellingSpeechHandler(this, this, safHelper, prefs)
        matchResultHandler = TellingMatchResultHandler(this)
        // speciesManager and annotationHandler already initialized before super.onCreate()
        initializer = TellingInitializer(this)
        
        // Initialize alarm handler for hourly alarm at minute 59
        alarmHandler = TellingAlarmHandler(this, lifecycleScope)
        alarmHandler.onAlarmTriggered = {
            showHuidigeStandScherm()
        }

        // Setup callbacks for new helpers
        setupHelperCallbacks()
    }

    /**
     * Setup callbacks for helper classes.
     */
    private fun setupHelperCallbacks() {
        // Speech handler callbacks
        speechHandler.onHypothesesReceived = { hypotheses, partials ->
            handleSpeechHypotheses(hypotheses, partials)
        }
        speechHandler.onRawResult = { rawText ->
            lifecycleScope.launch(Dispatchers.Main) {
                addLog(rawText, "raw")
            }
        }
        speechHandler.onListeningStarted = {
            addLog("Luisteren...", "systeem")
        }

        // Match result handler callbacks
        matchResultHandler.onAutoAccept = { speciesId, displayName, amount ->
            recordSpeciesCount(speciesId, displayName, amount)
        }
        matchResultHandler.onAutoAcceptWithPopup = { speciesId, displayName, amount, isInTiles ->
            if (isInTiles) {
                recordSpeciesCount(speciesId, displayName, amount)
            } else {
                showAddSpeciesConfirmationDialog(speciesId, displayName, amount)
            }
        }
        matchResultHandler.onMultiMatch = { matches ->
            matches.forEach { match ->
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
        matchResultHandler.onSuggestionList = { candidates, count ->
            showSuggestionBottomSheet(candidates, count)
        }
        matchResultHandler.onNoMatch = { hypothesis ->
            val now = System.currentTimeMillis()
            if (now - lastPartialUiUpdateMs >= PARTIAL_UI_DEBOUNCE_MS) {
                upsertPartialLog(hypothesis)
                lastPartialUiUpdateMs = now
            } else {
                upsertPartialLog(hypothesis)
            }
        }

        // Species manager callbacks
        speciesManager.onLogMessage = { msg, source -> addLog(msg, source) }
        speciesManager.onTilesUpdated = { updateSelectedSpeciesMap() }
        speciesManager.onRecordCollected = { item ->
            synchronized(pendingRecords) { pendingRecords.add(item) }
            if (::viewModel.isInitialized) viewModel.addPendingRecord(item)
            
            // Save full envelope after each observation to prevent data loss on crash.
            // Runs async on IO dispatcher; failures are logged but don't interrupt the UI flow.
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val records = synchronized(pendingRecords) { pendingRecords.toList() }
                    envelopePersistence.saveEnvelopeWithRecords(records)
                } catch (ex: Exception) {
                    Log.w(TAG, "Envelope persistence failed after record collected: ${ex.message}", ex)
                }
            }
        }

        // Annotation handler callbacks
        annotationHandler.onAnnotationApplied = { summary -> addLog(summary, "annotatie") }
        annotationHandler.onGetFinalsList = {
            if (::viewModel.isInitialized) viewModel.finals.value.orEmpty() else finalsAdapter.currentList
        }
        annotationHandler.onGetPendingRecords = { synchronized(pendingRecords) { pendingRecords.toList() } }
        annotationHandler.onUpdatePendingRecord = { idx, updated ->
            synchronized(pendingRecords) {
                if (idx in pendingRecords.indices) {
                    pendingRecords[idx] = updated
                    if (::viewModel.isInitialized) viewModel.setPendingRecords(pendingRecords.toList())
                    
                    // Recalculate tile counts from all pending records
                    tegelBeheer.recalculateCountsFromRecords(pendingRecords.toList())
                }
            }
            
            // Save full envelope after annotation update to preserve changes.
            // Runs async on IO dispatcher; failures are logged but don't interrupt the UI flow.
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val records = synchronized(pendingRecords) { pendingRecords.toList() }
                    envelopePersistence.saveEnvelopeWithRecords(records)
                } catch (ex: Exception) {
                    Log.w(TAG, "Envelope persistence failed after record update: ${ex.message}", ex)
                }
            }
        }

        // Initializer callbacks
        initializer.onTilesLoaded = { tiles ->
            tegelBeheer.setTiles(tiles)
            
            // Check if we need to show HuidigeStandScherm (triggered by hourly alarm)
            if (pendingShowHuidigeStand) {
                pendingShowHuidigeStand = false
                showHuidigeStandScherm()
            }
        }
        initializer.onMatchContextBuilt = { context ->
            speechHandler.updateCachedMatchContext(context)
        }
        initializer.onPermissionsGranted = {
            initializeSpeechRecognition()
            speechHandler.initializeVolumeKeyHandler()
        }
        initializer.onLogMessage = { msg, source -> addLog(msg, source) }
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
                            Toast.makeText(this, getString(R.string.telling_alias_saved_buffer), Toast.LENGTH_SHORT).show()
                            
                            lifecycleScope.launch {
                                Toast.makeText(this@TellingScherm, getString(R.string.telling_index_updating), Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(this@TellingScherm, getString(R.string.telling_alias_saved_index_updated), Toast.LENGTH_SHORT).show()
                                    speciesManager.refreshAliasesRuntimeAsync(speechHandler) { context ->
                                        speechHandler.updateCachedMatchContext(context)
                                    }
                                } else {
                                    Toast.makeText(this@TellingScherm, getString(R.string.telling_alias_saved_index_later), Toast.LENGTH_LONG).show()
                                }
                            }
                            
                            if (count > 0) {
                                lifecycleScope.launch {
                                    // Add species to tiles (creates tile if not present, or increases count)
                                    speciesManager.addSpeciesToTilesIfNeeded(speciesId, canonical, count)
                                    // Add to finals log (green text) and collect record for server upload
                                    addFinalLog("$canonical -> +$count")
                                    speciesManager.collectFinalAsRecord(speciesId, count)
                                }
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
            annotationHandler.launchAnnotatieScherm(row.tekst, row.ts, pos)
        }
    }

    /**
     * Handle Afronden button with confirmation dialog.
     * Shows a popup to allow editing begintijd, eindtijd, and opmerkingen.
     */
    private fun handleAfrondenWithConfirmation() {
        // Get current envelope data to show as defaults in dialog
        val envelopeData = getCurrentEnvelopeData()
        
        val dialog = AfrondConfirmDialog.newInstance(
            begintijdEpoch = envelopeData.begintijd,
            eindtijdEpoch = envelopeData.eindtijd,
            opmerkingen = envelopeData.opmerkingen
        )
        
        dialog.listener = object : AfrondConfirmDialog.AfrondConfirmListener {
            override fun onAfrondConfirmed(begintijdEpoch: String, eindtijdEpoch: String, opmerkingen: String) {
                lifecycleScope.launch {
                    val progressDialog = ProgressDialogHelper.show(this@TellingScherm, "Bezig met afronden upload...")
                    try { 
                        handleAfronden(
                            MetadataUpdates(
                                begintijd = begintijdEpoch,
                                eindtijd = eindtijdEpoch,
                                opmerkingen = opmerkingen
                            )
                        )
                    } finally { 
                        progressDialog.dismiss() 
                    }
                }
            }
            
            override fun onAfrondCancelled() {
                // User cancelled - do nothing
            }
        }
        
        dialog.show(supportFragmentManager, "afrondConfirm")
    }
    
    /**
     * Data class to hold current envelope metadata for the confirm dialog.
     */
    private data class EnvelopeData(
        val begintijd: String,
        val eindtijd: String,
        val opmerkingen: String
    )
    
    /**
     * Get current envelope data from saved preferences.
     */
    private fun getCurrentEnvelopeData(): EnvelopeData {
        return try {
            val savedEnvelopeJson = prefs.getString("pref_saved_envelope_json", null)
            if (savedEnvelopeJson.isNullOrBlank()) {
                // Return defaults with current time
                val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
                return EnvelopeData(nowEpoch, nowEpoch, "")
            }
            
            val envelopeList = VT5App.json.decodeFromString(
                ListSerializer(ServerTellingEnvelope.serializer()),
                savedEnvelopeJson
            )
            
            if (envelopeList.isEmpty()) {
                val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
                return EnvelopeData(nowEpoch, nowEpoch, "")
            }
            
            val envelope = envelopeList[0]
            EnvelopeData(
                begintijd = envelope.begintijd,
                eindtijd = envelope.eindtijd.ifBlank { (System.currentTimeMillis() / 1000L).toString() },
                opmerkingen = envelope.opmerkingen
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get envelope data: ${e.message}")
            val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
            EnvelopeData(nowEpoch, nowEpoch, "")
        }
    }

    /**
     * Handle Save/Close button - show current status screen.
     */
    private fun handleSaveClose(tiles: List<SoortRow>) {
        val ids = ArrayList<String>(tiles.size)
        val names = ArrayList<String>(tiles.size)
        val countsMain = ArrayList<String>(tiles.size)
        val countsReturn = ArrayList<String>(tiles.size)
        for (row in tiles) {
            ids.add(row.soortId)
            names.add(row.naam)
            countsMain.add(row.countMain.toString())
            countsReturn.add(row.countReturn.toString())
        }

        // Get the telling begintijd (epoch seconds) for correct season determination
        val begintijdEpoch = getTellingBegintijdEpoch()

        val intent = Intent(this, HuidigeStandScherm::class.java).apply {
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_IDS, ids)
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_NAMEN, names)
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_AANTALLEN_MAIN, countsMain)
            putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_AANTALLEN_RETURN, countsReturn)
            if (begintijdEpoch > 0) {
                putExtra(HuidigeStandScherm.EXTRA_TELLING_BEGINTIJD_EPOCH, begintijdEpoch)
            }
        }
        startActivity(intent)
    }
    
    /**
     * Get the telling begintijd (start time) as epoch seconds from the saved envelope.
     * This is needed to correctly determine the season for column mapping in HuidigeStandScherm.
     * 
     * @return The begintijd as epoch seconds, or -1L if not available
     */
    private fun getTellingBegintijdEpoch(): Long {
        return try {
            val savedEnvelopeJson = prefs.getString("pref_saved_envelope_json", null)
            if (savedEnvelopeJson.isNullOrBlank()) return -1L
            
            val envelopeList = VT5App.json.decodeFromString(
                ListSerializer(ServerTellingEnvelope.serializer()),
                savedEnvelopeJson
            )
            
            if (envelopeList.isNullOrEmpty()) return -1L
            
            // The begintijd field is a string containing epoch seconds
            envelopeList[0].begintijd.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get telling begintijd: ${e.message}")
            -1L
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Check if this is triggered by the hourly alarm
        if (intent.getBooleanExtra(EXTRA_SHOW_HUIDIGE_STAND, false)) {
            showHuidigeStandScherm()
        }
    }
    
    /**
     * Show the HuidigeStandScherm with the current tile data.
     * Called by hourly alarm (via onCreate or onNewIntent).
     */
    private fun showHuidigeStandScherm() {
        if (::tegelBeheer.isInitialized) {
            val tiles = tegelBeheer.getTiles()
            val rows = tiles.map { tile ->
                SoortRow(
                    soortId = tile.soortId,
                    naam = tile.naam,
                    countMain = tile.countMain,
                    countReturn = tile.countReturn
                )
            }
            handleSaveClose(rows)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::initializer.isInitialized) {
            initializer.onPermissionResult(requestCode, grantResults)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Start alarm monitoring when the screen is visible
        if (::alarmHandler.isInitialized) {
            alarmHandler.startMonitoring()
        }
        
        // Update lettergrootte in adapters als instellingen zijn gewijzigd
        val logTextSizeSp = com.yvesds.vt5.hoofd.InstellingenScherm.getLettergrootteLogSp(this)
        val tegelsTextSizeSp = com.yvesds.vt5.hoofd.InstellingenScherm.getLettergroottTegelsSp(this)
        
        if (::partialsAdapter.isInitialized) {
            partialsAdapter.updateTextSize(logTextSizeSp)
            partialsAdapter.notifyDataSetChanged()
        }
        if (::finalsAdapter.isInitialized) {
            finalsAdapter.updateTextSize(logTextSizeSp)
            finalsAdapter.notifyDataSetChanged()
        }
        if (::tilesAdapter.isInitialized) {
            tilesAdapter.updateTextSize(tegelsTextSizeSp)
            tilesAdapter.notifyDataSetChanged()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop alarm monitoring when the screen is not visible
        if (::alarmHandler.isInitialized) {
            alarmHandler.stopMonitoring()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(aliasReloadReceiver)
        } catch (_: Exception) {}
        try {
            if (::speechHandler.isInitialized) {
                speechHandler.cleanup()
            }
        } catch (_: Exception) {}
        try {
            if (::alarmHandler.isInitialized) {
                alarmHandler.cleanup()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /* ---------- UI setup (now delegated to UiManager) ---------- */

    // Ensure availableSpeciesFlat is loaded; onReady is invoked on Main with the flat list.
    private fun ensureAvailableSpeciesFlat(onReady: (List<String>) -> Unit) {
        speciesManager.ensureAvailableSpeciesFlat(onReady)
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
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 30)
                speciesManager.collectFinalAsRecord(soortId, delta)
            }
        }
    }





    private fun initializeSpeechRecognition() {
        try {
            speechHandler.initialize()
            updateSelectedSpeciesMap()
            addLog("Spraakherkenning geactiveerd - protocol: 'Soortnaam Aantal'", "systeem")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognition", e)
            Toast.makeText(this, getString(R.string.telling_speech_init_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handle speech recognition hypotheses and process match results.
     */
    private fun handleSpeechHypotheses(hypotheses: List<Pair<String, Float>>, partials: List<String>) {
        val receivedAt = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.Default) {
            val parseStartAt = System.currentTimeMillis()
            try {
                val matchContext = speechHandler.getCachedMatchContext() ?: run {
                    val t0 = System.currentTimeMillis()
                    val tiles = tegelBeheer.getTiles().map { it.soortId }.toSet()
                    val mc = initializer.buildMatchContext(tiles)
                    speechHandler.updateCachedMatchContext(mc)
                    mc
                }

                val result = speechHandler.parseSpokenWithHypotheses(hypotheses, matchContext, partials, asrWeight = 0.4)
                val parseEndAt = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    val uiStartAt = System.currentTimeMillis()
                    try {
                        matchResultHandler.handleMatchResult(result)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Hypotheses handling (UI) failed: ${ex.message}", ex)
                    } finally {
                        val uiEndAt = System.currentTimeMillis()
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Hypotheses handling (background) failed: ${ex.message}", ex)
            }
        }
    }



    /**
     * Record a species count (add final log, update count, collect record).
     */
    private fun recordSpeciesCount(speciesId: String, displayName: String, count: Int) {
        addFinalLog("$displayName -> +$count")
        lifecycleScope.launch {
            speciesManager.updateSoortCountInternal(speciesId, count)
            speciesManager.collectFinalAsRecord(speciesId, count)
        }
        RecentSpeciesStore.recordUse(this, speciesId, maxEntries = 30)
    }

    /**
     * Show confirmation dialog for adding a new species to tiles.
     */
    private fun showAddSpeciesConfirmationDialog(speciesId: String, displayName: String, count: Int) {
        val msg = "Soort \"$displayName\" herkend met aantal $count.\n\nToevoegen?"
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_add_species))
            .setMessage(msg)
            .setPositiveButton("Ja") { _, _ ->
                lifecycleScope.launch {
                    speciesManager.addSpeciesToTiles(speciesId, displayName, count)
                    addFinalLog("$displayName -> +$count")
                    speciesManager.collectFinalAsRecord(speciesId, count)
                }
            }
            .setNegativeButton("Nee", null)
            .show()
        dialogHelper.styleAlertDialogTextToWhite(dlg)
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
                    lifecycleScope.launch {
                        speciesManager.updateSoortCountInternal(chosen.speciesId, count)
                        speciesManager.collectFinalAsRecord(chosen.speciesId, count)
                    }
                } else {
                    val msg = "Soort \"${chosen.displayName}\" toevoegen en $count noteren?"
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Soort toevoegen?")
                        .setMessage(msg)
                        .setPositiveButton("Ja") { _, _ ->
                            lifecycleScope.launch {
                                speciesManager.addSpeciesToTiles(chosen.speciesId, chosen.displayName, count)
                                addFinalLog("${chosen.displayName} -> +$count")
                                speciesManager.collectFinalAsRecord(chosen.speciesId, count)
                            }
                        }
                        .setNegativeButton("Nee", null)
                        .show()
                    dialogHelper.styleAlertDialogTextToWhite(dlg)
                }
                RecentSpeciesStore.recordUse(this, chosen.speciesId, maxEntries = 30)
            }
            .setNegativeButton("Annuleer", null)
            .show()
    }





    // Afronden: build counts_save envelope with saved metadata + pendingRecords, POST and handle response
    /**
     * Handle Afronden (finalize and upload) using afrondHandler.
     * 
     * @param metadataUpdates Optional updates to begintijd, eindtijd, and opmerkingen
     */
    private suspend fun handleAfronden(metadataUpdates: MetadataUpdates? = null) {
        val result = afrondHandler.handleAfronden(
            pendingRecords = synchronized(pendingRecords) { ArrayList(pendingRecords) },
            pendingBackupDocs = pendingBackupDocs,
            pendingBackupInternalPaths = pendingBackupInternalPaths,
            metadataUpdates = metadataUpdates
        )

        withContext(Dispatchers.Main) {
            when (result) {
                is TellingAfrondHandler.AfrondResult.Success -> {
                    // Cleanup local state
                    synchronized(pendingRecords) { pendingRecords.clear() }
                    pendingBackupDocs.clear()
                    pendingBackupInternalPaths.clear()
                    if (::viewModel.isInitialized) viewModel.clearPendingRecords()

                    // Store the eindtijd for potential vervolgtelling
                    val eindtijdForVervolg = metadataUpdates?.eindtijd 
                        ?: (System.currentTimeMillis() / 1000L).toString()

                    // Show auto-dismissing success toast (like other popups in the app)
                    showAutoDissmissSuccessAndThenVervolg(eindtijdForVervolg)
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
    
    /**
     * Show auto-dismissing success popup for 2 seconds, then show vervolgtelling dialog.
     * 
     * @param eindtijdEpoch The eindtijd of the finished telling (epoch seconds string)
     */
    private fun showAutoDissmissSuccessAndThenVervolg(eindtijdEpoch: String) {
        // Create and show auto-dismissing success dialog
        val successDialog = AlertDialog.Builder(this@TellingScherm)
            .setTitle(getString(R.string.dialog_finish_success))
            .setMessage(getString(R.string.afrond_upload_success))
            .setCancelable(false)
            .create()
        
        successDialog.show()
        dialogHelper.styleAlertDialogTextToWhite(successDialog)
        
        // Auto-dismiss after SUCCESS_DIALOG_DELAY_MS and show vervolgtelling dialog
        lifecycleScope.launch {
            kotlinx.coroutines.delay(SUCCESS_DIALOG_DELAY_MS)
            if (successDialog.isShowing) {
                successDialog.dismiss()
            }
            showVervolgtellingDialog(eindtijdEpoch)
        }
    }
    
    /**
     * Show dialog asking if user wants to create a follow-up telling.
     * 
     * @param eindtijdEpoch The eindtijd to use as begintijd for the follow-up telling
     */
    private fun showVervolgtellingDialog(eindtijdEpoch: String) {
        val dlg = AlertDialog.Builder(this@TellingScherm)
            .setTitle(getString(R.string.afrond_vervolgtelling_titel))
            .setMessage(getString(R.string.afrond_vervolgtelling_msg))
            .setPositiveButton("OK") { _, _ ->
                // Navigate to MetadataScherm with begintijd preset to eindtijd of previous telling
                val intent = Intent(this@TellingScherm, MetadataScherm::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.putExtra(MetadataScherm.EXTRA_VERVOLG_BEGINTIJD_EPOCH, eindtijdEpoch)
                startActivity(intent)
                finish()
            }
            .setNegativeButton(getString(R.string.dlg_cancel)) { _, _ ->
                // Navigate to MetadataScherm without preset begintijd
                val intent = Intent(this@TellingScherm, MetadataScherm::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
        dialogHelper.styleAlertDialogTextToWhite(dlg)
    }

    // Write pretty envelope JSON to SAF as "<timestamp>_count_<onlineid>.json"




    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::speechHandler.isInitialized && speechHandler.handleKeyDown(keyCode, event)) {
            updateSelectedSpeciesMap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateSelectedSpeciesMap() {
        val speciesMap = tegelBeheer.buildSelectedSpeciesMap()
        if (::speechHandler.isInitialized) {
            speechHandler.buildSelectedSpeciesMap(speciesMap)
        }
    }

    // Launch soort selectie
    private fun openSoortSelectieForAdd() {
        speciesManager.launchSpeciesSelection()
    }


}