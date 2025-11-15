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

    // Prefs
    private lateinit var prefs: android.content.SharedPreferences

    // ASR hints
    private val selectedSpeciesMap = HashMap<String, String>(100)

    // Flattened species (for AddAliasDialog) - loaded on demand or during preload
    private var availableSpeciesFlat: List<String> = emptyList()

    // Partial UI debounce + only keep last partial
    private var lastPartialUiUpdateMs: Long = 0L
    private val PARTIAL_UI_DEBOUNCE_MS = 200L

    private val RE_ASR_PREFIX = Regex("(?i)^\\s*asr:\\s*")
    private val RE_TRIM_RAW_NUMBER = Regex("\\s+\\d+(?:[.,]\\d+)?\$")
    private val RE_TRAILING_NUMBER = Regex("^(.*?)(?:\\s+(\\d+)(?:[.,]\\d+)?)?\$")
    //private val RE_PLUS_NUMBER = Regex("\\+?(\\d+)")

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
        aliasEditor = AliasEditor(this, safHelper)

        setupPartialsRecyclerView()
        setupFinalsRecyclerView()
        setupSpeciesTilesRecyclerView()

        // Initialize ViewModel (if you have TellingViewModel in project)
        try {
            viewModel = ViewModelProvider(this).get(TellingViewModel::class.java)
            // Observe VM lists and keep adapters in sync (this ensures rotation preserves UI)
            viewModel.tiles.observe(this) { tiles ->
                // update adapter (observer will call submitList callback if configured)
                tilesAdapter.submitList(tiles)

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
                partialsAdapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        binding.recyclerViewSpeechPartials.scrollToPosition(list.size - 1)
                    }
                }
            }
            //viewModel.finals.observe(this) { finalsAdapter.submitList(it) }
            viewModel.finals.observe(this) { list ->
                finalsAdapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        binding.recyclerViewSpeechFinals.scrollToPosition(list.size - 1)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "TellingViewModel not available or failed to init: ${ex.message}")
        }

        setupButtons()

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

    /* ---------- UI setup ---------- */
    private fun setupPartialsRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechPartials.layoutManager = layoutManager
        binding.recyclerViewSpeechPartials.setHasFixedSize(true)
        partialsAdapter = SpeechLogAdapter()
        partialsAdapter.showPartialsInRow = true
        binding.recyclerViewSpeechPartials.adapter = partialsAdapter

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechPartials.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechPartials.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = partialsAdapter.currentList.getOrNull(pos) ?: return true
                        when (row.bron) {
                            "partial" -> {
                                val (nameOnly, cnt) = parseNameAndCountFromDisplay(row.tekst)
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
                                                            // Zorg dat runtime alias index en ASR herladen worden zodat de nieuwe alias meteen actief is
                                                            refreshAliasesRuntimeAsync()
                                                        } else {
                                                            Toast.makeText(this@TellingScherm, "Alias opgeslagen (index update later)", Toast.LENGTH_LONG).show()
                                                        }
                                                    }

                                                    if (cnt > 0) {
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
                            else -> {
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
                                                                // Herlaad runtime index en ASR zodat alias direct gebruikt wordt
                                                                refreshAliasesRuntimeAsync()
                                                            } else {
                                                                Toast.makeText(this@TellingScherm, "Alias opgeslagen (index update later)", Toast.LENGTH_LONG).show()
                                                            }
                                                        }

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

        binding.recyclerViewSpeechPartials.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupFinalsRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.recyclerViewSpeechFinals.layoutManager = layoutManager
        binding.recyclerViewSpeechFinals.setHasFixedSize(true)
        finalsAdapter = SpeechLogAdapter()
        finalsAdapter.showPartialsInRow = false
        binding.recyclerViewSpeechFinals.adapter = finalsAdapter

        // Gesture handling for finals: open AnnotatieScherm on tap (now for result)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = binding.recyclerViewSpeechFinals.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = binding.recyclerViewSpeechFinals.getChildAdapterPosition(child)
                    if (pos != RecyclerView.NO_POSITION) {
                        val row = finalsAdapter.currentList.getOrNull(pos) ?: return true
                        if (row.bron == "final") {
                            // Start AnnotatieScherm for result. Pass row position so we can update correct row later.
                            val intent = Intent(this@TellingScherm, AnnotatieScherm::class.java).apply {
                                putExtra(AnnotatieScherm.EXTRA_TEXT, row.tekst) // legacy compatibility
                                putExtra(AnnotatieScherm.EXTRA_TS, row.ts)
                                putExtra("extra_row_pos", pos)
                            }
                            annotationLauncher.launch(intent)
                        }
                    }
                }
                return true
            }
        })

        binding.recyclerViewSpeechFinals.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
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

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Aantal om toe te voegen"
            setText("")
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
                if (delta <= 0) {
                    Toast.makeText(this, "Geen verandering.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    // Update tile count internally (no separate manual log)
                    updateSoortCountInternal(row.soortId, delta)

                    // Behave exactly like an ASR final:
                    addFinalLog("${row.naam} -> +$delta")
                    RecentSpeciesStore.recordUse(this@TellingScherm, row.soortId, maxEntries = 30)
                    collectFinalAsRecord(row.soortId, delta)
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

        // Afronden: confirmation popup before proceeding
        binding.btnAfronden.setOnClickListener {
            val builder = AlertDialog.Builder(this@TellingScherm)
                .setTitle("Afronden bevestigen")
                .setMessage("Weet je zeker dat je wilt afronden en de telling uploaden?")
                .setPositiveButton("Ja") { _, _ ->
                    lifecycleScope.launch {
                        val dialog = ProgressDialogHelper.show(this@TellingScherm, "Bezig met afronden upload...")
                        try { handleAfronden() } finally { dialog.dismiss() }
                    }
                }
                .setNegativeButton("Nee", null)

            val dlg = builder.show()
            styleAlertDialogTextToWhite(dlg)
        }

        binding.btnSaveClose.setOnClickListener {
            // Repurposed: show current status screen with current tile data (no popup)
            val current = tilesAdapter.currentList
            val ids = ArrayList<String>(current.size)
            val names = ArrayList<String>(current.size)
            val counts = ArrayList<String>(current.size)
            for (row in current) {
                ids.add(row.soortId)
                names.add(row.naam)
                counts.add(row.count.toString())
            }

            val intent = Intent(this@TellingScherm, HuidigeStandScherm::class.java).apply {
                putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_IDS, ids)
                putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_NAMEN, names)
                putStringArrayListExtra(HuidigeStandScherm.EXTRA_SOORT_AANTALLEN, counts)
            }
            startActivity(intent)
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
                    if (::viewModel.isInitialized) viewModel.setTiles(initial)

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
                                        val formatted = "${result.candidate.displayName} -> +${result.amount}"
                                        addFinalLog(formatted)
                                        updateSoortCountInternal(result.candidate.speciesId, result.amount)
                                        RecentSpeciesStore.recordUse(this@TellingScherm, result.candidate.speciesId, maxEntries = 30)

                                        // Collect record (do NOT auto-upload: we save pendingRecords for Afronden)
                                        collectFinalAsRecord(result.candidate.speciesId, result.amount)
                                    }
                                    is MatchResult.AutoAcceptAddPopup -> {
                                        val cnt = result.amount
                                        val prettyName = result.candidate.displayName
                                        val speciesId = result.candidate.speciesId

                                        // NEW: if species already present in tiles, bypass popup and directly count it
                                        val presentInTiles = tilesAdapter.currentList.any { it.soortId == speciesId }
                                        if (presentInTiles) {
                                            addFinalLog("$prettyName -> +$cnt")
                                            updateSoortCountInternal(speciesId, cnt)
                                            RecentSpeciesStore.recordUse(this@TellingScherm, speciesId, maxEntries = 30)

                                            // Collect record
                                            collectFinalAsRecord(speciesId, cnt)
                                        } else {
                                            val msg = "Soort \"$prettyName\" herkend met aantal $cnt.\n\nToevoegen?"
                                            val dlg = AlertDialog.Builder(this@TellingScherm)
                                                .setTitle("Soort toevoegen?")
                                                .setMessage(msg)
                                                .setPositiveButton("Ja") { _, _ ->
                                                    addSpeciesToTiles(result.candidate.speciesId, result.candidate.displayName, cnt)
                                                    addFinalLog("${result.candidate.displayName} -> +$cnt")
                                                    // record collected in addSpeciesToTiles? ensure collected as well:
                                                    collectFinalAsRecord(result.candidate.speciesId, cnt)
                                                }
                                                .setNegativeButton("Nee", null)
                                                .show()
                                            styleAlertDialogTextToWhite(dlg)
                                        }
                                    }
                                    is MatchResult.MultiMatch -> {
                                        result.matches.forEach { match ->
                                            val sid = match.candidate.speciesId
                                            val cnt = match.amount
                                            val present = tilesAdapter.currentList.any { it.soortId == sid }
                                            if (present) {
                                                addFinalLog("${match.candidate.displayName} -> +${cnt}")
                                                updateSoortCountInternal(sid, cnt)
                                                RecentSpeciesStore.recordUse(this@TellingScherm, sid, maxEntries = 30)

                                                // Collect each recognized match
                                                collectFinalAsRecord(sid, cnt)
                                            } else {
                                                val prettyName = match.candidate.displayName
                                                val msg = "Soort \"$prettyName\" (${cnt}x) herkend.\n\nToevoegen?"
                                                val dlg = AlertDialog.Builder(this@TellingScherm)
                                                    .setTitle("Soort toevoegen?")
                                                    .setMessage(msg)
                                                    .setPositiveButton("Ja") { _, _ ->
                                                        addSpeciesToTiles(match.candidate.speciesId, prettyName, cnt)
                                                        addFinalLog("$prettyName -> +${cnt}")
                                                        collectFinalAsRecord(match.candidate.speciesId, cnt)
                                                    }
                                                    .setNegativeButton("Nee", null)
                                                    .show()
                                                styleAlertDialogTextToWhite(dlg)
                                            }
                                        }
                                    }
                                    is MatchResult.SuggestionList -> {
                                        val cnt = extractCountFromText(result.hypothesis)
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

    /* ---------- Helper log functions (routed to partials/finals adapters) ---------- */

    // Primary addLog implementation: append a row (used by callers)
    private fun addLog(msgIn: String, bron: String) {
        // Filter system messages: only include "Luisteren..." in partials. Other system messages are ignored here.
        if (bron == "systeem" && !msgIn.contains("Luisteren", ignoreCase = true)) {
            return
        }

        val now = System.currentTimeMillis() / 1000L
        val cleaned = run {
            var m = msgIn.replace(RE_ASR_PREFIX, "")
            if (bron == "raw") {
                m = m.replace(RE_TRIM_RAW_NUMBER, "")
            }
            m.trim()
        }

        val newRow = SpeechLogRow(ts = now, tekst = cleaned, bron = bron)

        lifecycleScope.launch {
            val newList = withContext(Dispatchers.Default) {
                if (bron == "final") {
                    val current = if (::viewModel.isInitialized) viewModel.finals.value.orEmpty() else finalsAdapter.currentList
                    val list = ArrayList(current)
                    list.add(newRow)
                    if (list.size > MAX_LOG_ROWS) {
                        repeat(list.size - MAX_LOG_ROWS) { list.removeAt(0) }
                    }
                    list
                } else {
                    val current = if (::viewModel.isInitialized) viewModel.partials.value.orEmpty() else partialsAdapter.currentList
                    val list = ArrayList(current)
                    list.add(newRow)
                    if (list.size > MAX_LOG_ROWS) {
                        repeat(list.size - MAX_LOG_ROWS) { list.removeAt(0) }
                    }
                    list
                }
            }

            withContext(Dispatchers.Main) {
                if (::viewModel.isInitialized) {
                    // UPDATE VIEWMODEL ONLY — observers will update adapters once.
                    if (bron == "final") {
                        viewModel.setFinals(newList)
                        // remove 'partial' rows from partials (keep non-partial logs)
                        val preserved = viewModel.partials.value?.filter { it.bron != "partial" }.orEmpty()
                        viewModel.setPartials(preserved)
                    } else {
                        viewModel.setPartials(newList)
                    }
                } else {
                    // Fallback: no ViewModel — update adapter directly (legacy behavior)
                    if (bron == "final") {
                        finalsAdapter.submitList(newList) {
                            binding.recyclerViewSpeechFinals.scrollToPosition(newList.size - 1)
                        }
                        // clear partials from UI
                        val preserved = partialsAdapter.currentList.filter { it.bron != "partial" }
                        partialsAdapter.submitList(preserved)
                    } else {
                        partialsAdapter.submitList(newList) {
                            binding.recyclerViewSpeechPartials.scrollToPosition(newList.size - 1)
                        }
                    }
                }
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

        // Parse name and count from raw hypothesis — use precompiled regex to avoid allocations
        val (nameOnly, cnt) = run {
            val m = RE_TRAILING_NUMBER.find(cleanedRaw)
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
                val current = if (::viewModel.isInitialized) viewModel.partials.value.orEmpty() else partialsAdapter.currentList
                val filtered = current.filter { it.bron != "partial" }.toMutableList()
                val now = System.currentTimeMillis() / 1000L
                filtered.add(SpeechLogRow(ts = now, tekst = display, bron = "partial"))
                if (filtered.size > MAX_LOG_ROWS) {
                    repeat(filtered.size - MAX_LOG_ROWS) { filtered.removeAt(0) }
                }
                filtered
            }

            withContext(Dispatchers.Main) {
                if (::viewModel.isInitialized) {
                    viewModel.setPartials(newList)
                } else {
                    partialsAdapter.submitList(newList) {
                        binding.recyclerViewSpeechPartials.scrollToPosition(newList.size - 1)
                    }
                }
            }
        }
    }

    // Append a final log entry (bron = "final")
    // Also remove any existing partials so final doesn't sit next to a stale partial.
    // Vervang volledige addFinalLog(...) functie door onderstaande versie.
// Ongeveer vervangt: functie die begon rond regel ~997

    private fun addFinalLog(text: String) {
        val now = System.currentTimeMillis() / 1000L
        val newRow = SpeechLogRow(ts = now, tekst = text, bron = "final")

        lifecycleScope.launch {
            val updatedFinals = withContext(Dispatchers.Default) {
                val cur = if (::viewModel.isInitialized) viewModel.finals.value.orEmpty() else finalsAdapter.currentList
                val finalList = ArrayList(cur)
                finalList.add(newRow)
                if (finalList.size > MAX_LOG_ROWS) repeat(finalList.size - MAX_LOG_ROWS) { finalList.removeAt(0) }
                finalList
            }
            val updatedPartials = withContext(Dispatchers.Default) {
                val cur = if (::viewModel.isInitialized) viewModel.partials.value.orEmpty() else partialsAdapter.currentList
                val filtered = cur.filter { it.bron != "partial" }.toMutableList()
                if (filtered.size > MAX_LOG_ROWS) repeat(filtered.size - MAX_LOG_ROWS) { filtered.removeAt(0) }
                filtered
            }

            withContext(Dispatchers.Main) {
                if (::viewModel.isInitialized) {
                    viewModel.setFinals(updatedFinals)
                    viewModel.setPartials(updatedPartials)
                } else {
                    finalsAdapter.submitList(updatedFinals) {
                        binding.recyclerViewSpeechFinals.scrollToPosition(updatedFinals.size - 1)
                    }
                    partialsAdapter.submitList(updatedPartials)
                }
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
                    styleAlertDialogTextToWhite(dlg)
                }
                RecentSpeciesStore.recordUse(this, chosen.speciesId, maxEntries = 30)
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

                withContext(Dispatchers.Main) {
                    tilesAdapter.submitList(updated)
                    if (::viewModel.isInitialized) viewModel.setTiles(updated)
                    updateSelectedSpeciesMap()
                    RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 30)
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
                if (::viewModel.isInitialized) viewModel.updateTiles(updatedList)
                addLog("${item.naam} -> +$count", "manueel")
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 30)
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
                if (::viewModel.isInitialized) viewModel.updateTiles(updatedList)
                RecentSpeciesStore.recordUse(this@TellingScherm, soortId, maxEntries = 30)
                updateSelectedSpeciesMap()
            }
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

                // Try SAF backup, else internal fallback
                try {
                    val doc = writeRecordBackupSaf(this@TellingScherm, tellingId, item)
                    if (doc != null) {
                        pendingBackupDocs.add(doc)
                    } else {
                        val internal = writeRecordBackupInternal(this@TellingScherm, tellingId, item)
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

    // Write a single record backup via SAF into Documents/VT5/exports
    private fun writeRecordBackupSaf(context: Context, tellingId: String, item: ServerTellingDataItem): DocumentFile? {
        try {
            val saf = SaFStorageHelper(context)
            var vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { saf.ensureFolders() } catch (_: Exception) {}
                vt5Dir = saf.getVt5DirIfExists()
            }
            if (vt5Dir != null) {
                val exportsDir = saf.findOrCreateDirectory(vt5Dir, "exports") ?: vt5Dir
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = "session_${tellingId}_${item.idLocal}_$nowStr.json"
                val created = exportsDir.createFile("application/json", safeName) ?: return null
                context.contentResolver.openOutputStream(created.uri)?.bufferedWriter(Charsets.UTF_8).use { w ->
                    val payloadJson = VT5App.json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
                    w?.write(payloadJson)
                    w?.flush()
                }
                return created
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeRecordBackupSaf failed: ${e.message}", e)
        }
        return null
    }

    // Fallback internal write
    private fun writeRecordBackupInternal(context: Context, tellingId: String, item: ServerTellingDataItem): String? {
        return try {
            val root = java.io.File(context.filesDir, "VT5")
            if (!root.exists()) root.mkdirs()
            val exports = java.io.File(root, "exports"); if (!exports.exists()) exports.mkdirs()
            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "session_${tellingId}_${item.idLocal}_$nowStr.json"
            val f = java.io.File(exports, filename)
            val payloadJson = VT5App.json.encodeToString(ListSerializer(ServerTellingDataItem.serializer()), listOf(item))
            f.writeText(payloadJson, Charsets.UTF_8)
            f.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "writeRecordBackupInternal failed: ${e.message}", e)
            null
        }
    }

    // Afronden: build counts_save envelope with saved metadata + pendingRecords, POST and handle response
    private suspend fun handleAfronden() {
        withContext(Dispatchers.IO) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedEnvelopeJson = prefs.getString(PREF_SAVED_ENVELOPE_JSON, null)
            if (savedEnvelopeJson.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Geen metadata")
                        .setMessage("Er is geen opgeslagen metadata (counts_save header). Keer terug naar metadata en start een telling.")
                        .setPositiveButton("OK", null)
                        .show()
                    styleAlertDialogTextToWhite(dlg)
                }
                return@withContext
            }

            val envelopeList = try {
                VT5App.json.decodeFromString(ListSerializer(ServerTellingEnvelope.serializer()), savedEnvelopeJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed decoding saved envelope JSON: ${e.message}", e)
                null
            }
            if (envelopeList.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Ongeldige envelope")
                        .setMessage("Opgeslagen envelope ongeldig.")
                        .setPositiveButton("OK", null)
                        .show()
                    styleAlertDialogTextToWhite(dlg)
                }
                return@withContext
            }

            // Inject saved onlineId (if present) into the envelope BEFORE building finalEnv/POST.
            val envelopeWithOnline = applySavedOnlineIdToEnvelope(envelopeList)
            // Persist the saved envelope (now containing onlineId) so prefs remains consistent.
            persistSavedEnvelopeJson(envelopeWithOnline)

            val nowEpoch = (System.currentTimeMillis() / 1000L)
            val nowEpochStr = nowEpoch.toString()
            val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val baseEnv = envelopeWithOnline[0]
            val envWithTimes = baseEnv.copy(eindtijd = nowEpochStr, uploadtijdstip = nowFormatted)

            val recordsSnapshot = synchronized(pendingRecords) { ArrayList(pendingRecords) }
            val nrec = recordsSnapshot.size
            val nsoort = recordsSnapshot.map { it.soortid }.toSet().size

            val finalEnv = envWithTimes.copy(nrec = nrec.toString(), nsoort = nsoort.toString(), data = recordsSnapshot)
            val envelopeToSend = listOf(finalEnv)

            // Pretty print and save to SAF (or fallback)
            val onlineIdPref = prefs.getString(PREF_ONLINE_ID, "") ?: ""
            val prettyJson = try { PRETTY_JSON.encodeToString(ListSerializer(ServerTellingEnvelope.serializer()), envelopeToSend) } catch (e: Exception) { Log.w(TAG, "pretty encode failed: ${e.message}", e); null }

            var savedPrettyPath: String? = null
            if (prettyJson != null) {
                try {
                    savedPrettyPath = writePrettyEnvelopeToSaf(this@TellingScherm, onlineIdPref.ifBlank { "unknown" }, prettyJson)
                } catch (e: Exception) {
                    Log.w(TAG, "writePrettyEnvelopeToSaf failed: ${e.message}", e)
                    try {
                        val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val fname = "${nowStr}_count_${onlineIdPref.ifBlank { "unknown" }}.json"
                        val root = java.io.File(this@TellingScherm.filesDir, "VT5/exports"); if (!root.exists()) root.mkdirs()
                        val f = java.io.File(root, fname); f.writeText(prettyJson, Charsets.UTF_8)
                        savedPrettyPath = "internal:${f.absolutePath}"
                    } catch (ex: Exception) { Log.w(TAG, "fallback pretty write failed: ${ex.message}", ex) }
                }
            }

            // Credentials & POST
            val creds = CredentialsStore(this@TellingScherm)
            val user = creds.getUsername().orEmpty()
            val pass = creds.getPassword().orEmpty()
            if (user.isBlank() || pass.isBlank()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@TellingScherm, "Geen credentials beschikbaar voor upload.", Toast.LENGTH_LONG).show() }
                return@withContext
            }

            val baseUrl = "https://trektellen.nl"
            val language = "dutch"
            val versie = "1845"

            val (ok, resp) = try {
                TrektellenApi.postCountsSave(baseUrl, language, versie, user, pass, envelopeToSend)
            } catch (ex: Exception) {
                Log.w(TAG, "postCountsSave exception: ${ex.message}", ex)
                false to (ex.message ?: "exception")
            }

            val auditPath = writeEnvelopeResponseToSaf(this@TellingScherm, finalEnv.tellingid, prettyJson ?: "{}", resp)

            if (!ok) {
                withContext(Dispatchers.Main) {
                    val dlg = AlertDialog.Builder(this@TellingScherm)
                        .setTitle("Upload mislukt")
                        .setMessage("Kon telling niet uploaden:\n$resp\n\nEnvelope opgeslagen: ${savedPrettyPath ?: "niet beschikbaar"}\nAuditbestand: ${auditPath ?: "niet beschikbaar"}")
                        .setPositiveButton("OK", null)
                        .show()
                    styleAlertDialogTextToWhite(dlg)
                }
                return@withContext
            }

            // On success: try to parse the onlineId the server returned and persist it back to prefs.
            try {
                val returnedOnlineId = parseOnlineIdFromResponse(resp)
                if (!returnedOnlineId.isNullOrBlank()) {
                    prefs.edit { putString(PREF_ONLINE_ID, returnedOnlineId) }

                    // Update saved envelope JSON (ensure saved envelope reflects server-confirmed onlineId)
                    try {
                        val updated = envelopeWithOnline.toMutableList()
                        if (updated.isNotEmpty()) {
                            val first = updated[0].copy(onlineid = returnedOnlineId)
                            updated[0] = first
                            persistSavedEnvelopeJson(updated)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed persisting envelope with returned onlineId: ${ex.message}", ex)
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Parsing/handling returned onlineId failed: ${ex.message}", ex)
            }

            // On success: cleanup and return to MetadataScherm
            try {
                pendingBackupDocs.forEach { doc -> try { doc.delete() } catch (_: Exception) {} }
                pendingBackupDocs.clear()
                pendingBackupInternalPaths.forEach { path -> try { java.io.File(path).delete() } catch (_: Exception) {} }
                pendingBackupInternalPaths.clear()
                synchronized(pendingRecords) { pendingRecords.clear() }
                if (::viewModel.isInitialized) viewModel.clearPendingRecords()

                // Remove keys (we completed the telling)
                prefs.edit {
                    remove(PREF_ONLINE_ID).remove(PREF_TELLING_ID).remove(PREF_SAVED_ENVELOPE_JSON)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup after successful Afronden failed: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val dlg = AlertDialog.Builder(this@TellingScherm)
                    .setTitle("Afronden geslaagd")
                    .setMessage("Afronden upload geslaagd. Envelope opgeslagen: ${savedPrettyPath ?: "n.v.t."}")
                    .setPositiveButton("OK") { _, _ ->
                        val it = Intent(this@TellingScherm, MetadataScherm::class.java)
                        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(it)
                        finish()
                    }
                    .show()
                styleAlertDialogTextToWhite(dlg)
            }
        }
    }

    // Write pretty envelope JSON to SAF as "<timestamp>_count_<onlineid>.json"
    private fun writePrettyEnvelopeToSaf(context: Context, onlineId: String, prettyJson: String): String? {
        try {
            val saf = SaFStorageHelper(context)
            var vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { saf.ensureFolders() } catch (_: Exception) {}
                vt5Dir = saf.getVt5DirIfExists()
            }
            if (vt5Dir != null) {
                val exportsDir = saf.findOrCreateDirectory(vt5Dir, "exports") ?: vt5Dir
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = "${nowStr}_count_${onlineId}.json"
                val created = exportsDir.createFile("application/json", safeName) ?: return null
                context.contentResolver.openOutputStream(created.uri)?.bufferedWriter(Charsets.UTF_8).use { w ->
                    w?.write(prettyJson)
                    w?.flush()
                }
                return "Documents/VT5/exports/$safeName"
            } else {
                // fallback internal
                val root = java.io.File(context.filesDir, "VT5")
                if (!root.exists()) root.mkdirs()
                val exports = java.io.File(root, "exports")
                if (!exports.exists()) exports.mkdirs()
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "${nowStr}_count_${onlineId}.json"
                val f = java.io.File(exports, filename)
                f.writeText(prettyJson, Charsets.UTF_8)
                return "internal:${f.absolutePath}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "writePrettyEnvelopeToSaf failed: ${e.message}", e)
            return null
        }
    }

    /* ---------- Remaining helpers (unchanged) ---------- */
    private fun applySavedOnlineIdToEnvelope(envelopeList: List<com.yvesds.vt5.net.ServerTellingEnvelope>): List<com.yvesds.vt5.net.ServerTellingEnvelope> {
        try {
            // read saved onlineId from the same prefs file used elsewhere in the activity
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedOnlineId = prefs.getString("pref_online_id", null) ?: prefs.getString("pref_onlineid", null) // tolerance for key variants
            if (savedOnlineId.isNullOrBlank()) return envelopeList

            // copy the list and replace the first envelope's onlineid field
            val updated = envelopeList.toMutableList()
            if (updated.isNotEmpty()) {
                val first = updated[0]
                // create a copy with the server online id filled in
                val replaced = first.copy(onlineid = savedOnlineId)
                updated[0] = replaced
            }
            return updated.toList()
        } catch (ex: Exception) {
            // on any error, fall back to original envelope (do not block the upload)
            android.util.Log.w("TellingScherm", "applySavedOnlineIdToEnvelope failed: ${ex.message}", ex)
            return envelopeList
        }
    }

    private fun persistSavedEnvelopeJson(envelopeList: List<com.yvesds.vt5.net.ServerTellingEnvelope>) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonText = PRETTY_JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.yvesds.vt5.net.ServerTellingEnvelope.serializer()), envelopeList)
            prefs.edit { putString(PREF_SAVED_ENVELOPE_JSON, jsonText) }
        } catch (ex: Exception) {
            Log.w("TellingScherm", "persistSavedEnvelopeJson failed: ${ex.message}", ex)
        }
    }

    // Parse a numeric count from string fallback to 1
    private fun extractCountFromText(text: String): Int {
        val m = Regex("\\b(\\d+)\\b").find(text)
        return m?.groups?.get(1)?.value?.toIntOrNull() ?: 1
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
            Log.d(TAG, "Selected species map updated")
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

    // Write envelope + response to SAF exports (Documents/VT5/exports) - audit file
    private fun writeEnvelopeResponseToSaf(context: Context, tellingId: String, envelopeJson: String, responseText: String): String? {
        try {
            val saf = SaFStorageHelper(context)
            var vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { saf.ensureFolders() } catch (_: Exception) {}
                vt5Dir = saf.getVt5DirIfExists()
            }
            if (vt5Dir != null) {
                val exportsDir = saf.findOrCreateDirectory(vt5Dir, "exports") ?: vt5Dir
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = "counts_save_response_${tellingId}_$nowStr.txt"
                val created = exportsDir.createFile("text/plain", safeName) ?: return null
                context.contentResolver.openOutputStream(created.uri)?.bufferedWriter(Charsets.UTF_8).use { w ->
                    w?.write("=== Envelope ===\n")
                    w?.write(envelopeJson)
                    w?.write("\n\n=== Server response ===\n")
                    w?.write(responseText)
                    w?.flush()
                }
                return "Documents/VT5/exports/$safeName"
            } else {
                // fallback internal
                val root = java.io.File(context.filesDir, "VT5")
                if (!root.exists()) root.mkdirs()
                val exports = java.io.File(root, "exports")
                if (!exports.exists()) exports.mkdirs()
                val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "counts_save_response_${tellingId}_$nowStr.txt"
                val f = java.io.File(exports, filename)
                f.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write("=== Envelope ===\n")
                    w.write(envelopeJson)
                    w.write("\n\n=== Server response ===\n")
                    w.write(responseText)
                    w.flush()
                }
                return "internal:${f.absolutePath}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeEnvelopeResponseToSaf failed: ${e.message}", e)
            return null
        }
    }

    // Launch soort selectie
    private fun openSoortSelectieForAdd() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(this, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }
    private fun parseOnlineIdFromResponse(resp: String): String? {
        try {
            if (resp.isBlank()) return null

            // 1) Try JSON object
            try {
                val trimmed = resp.trim()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    // Use org.json for flexible parsing of unknown shapes
                    val jsonRoot = org.json.JSONTokener(trimmed).nextValue()
                    when (jsonRoot) {
                        is org.json.JSONObject -> {
                            val jo = jsonRoot
                            if (jo.has("onlineid")) return jo.get("onlineid").toString()
                            if (jo.has("onlineId")) return jo.get("onlineId").toString()
                            if (jo.has("online_id")) return jo.get("online_id").toString()
                        }
                        is org.json.JSONArray -> {
                            val ja = jsonRoot
                            if (ja.length() > 0) {
                                val first = ja.optJSONObject(0)
                                if (first != null) {
                                    if (first.has("onlineid")) return first.get("onlineid").toString()
                                    if (first.has("onlineId")) return first.get("onlineId").toString()
                                    if (first.has("online_id")) return first.get("online_id").toString()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // continue to regex fallback
            }

            // 2) Regex fallback: find digits following onlineid keyword
            val re = Regex("""["']?(?:onlineid|onlineId|online_id)["']?\s*[:=]\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
            val m = re.find(resp)
            if (m != null) return m.groupValues[1]

            // 3) Another fallback: any 5-10 digit number that looks like an id (risky; last resort)
            val numRe = Regex("""\b(\d{4,12})\b""")
            val m2 = numRe.find(resp)
            if (m2 != null) return m2.groupValues[1]
        } catch (ex: Exception) {
            Log.w(TAG, "parseOnlineIdFromResponse failed: ${ex.message}", ex)
        }
        return null
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
                        writeRecordBackupSaf(this@TellingScherm, tellingId, updated)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "applyAnnotationsToPendingRecord: backup write failed: ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "applyAnnotationsToPendingRecord failed: ${ex.message}", ex)
        }
    }

    private fun styleAlertDialogTextToWhite(dialog: AlertDialog) {
        try {
            // The AlertDialog title view id is provided by AppCompat, not android.R.
            val title = dialog.findViewById<TextView?>(androidx.appcompat.R.id.alertTitle)
            title?.setTextColor(Color.WHITE)

            // The message id is platform-stable
            val msg = dialog.findViewById<TextView?>(android.R.id.message)
            msg?.setTextColor(Color.WHITE)

            // Buttons are only available after show(); caller already calls this after .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.WHITE)
        } catch (e: Exception) {
            Log.w(TAG, "styleAlertDialogTextToWhite failed: ${e.message}", e)
        }
    }
}