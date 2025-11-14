package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.AliasMatcher
import com.yvesds.vt5.features.speech.AliasSpeechParser
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.features.speech.SpeechRecognitionManager
import com.yvesds.vt5.features.speech.VolumeKeyHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * TellingSpeechHandler: Manages speech recognition for TellingScherm.
 * 
 * Responsibilities:
 * - Initializing and managing SpeechRecognitionManager
 * - Handling volume key triggers
 * - Building and caching MatchContext
 * - Processing speech recognition results
 * - Managing alias reloads
 */
class TellingSpeechHandler(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val safHelper: SaFStorageHelper
) {
    companion object {
        private const val TAG = "TellingSpeechHandler"
        private const val DEFAULT_SILENCE_MS = 1000
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private lateinit var aliasParser: AliasSpeechParser
    
    private var speechInitialized = false
    private val selectedSpeciesMap = HashMap<String, String>(100)
    
    @Volatile
    private var cachedMatchContext: MatchContext? = null

    // Callbacks to activity
    var onResultCallback: ((MatchResult) -> Unit)? = null
    var onRawResultCallback: ((String) -> Unit)? = null
    var onLogCallback: ((String, String) -> Unit)? = null

    /**
     * Initialize speech recognition system.
     */
    fun initialize(
        silenceMs: Int = DEFAULT_SILENCE_MS,
        getCurrentTiles: () -> List<TellingScherm.SoortRow>
    ) {
        try {
            speechRecognitionManager = SpeechRecognitionManager(activity)
            speechRecognitionManager.initialize()
            speechRecognitionManager.setSilenceStopMillis(silenceMs.toLong())

            // Initialize alias parser
            if (!::aliasParser.isInitialized) {
                aliasParser = AliasSpeechParser(activity, safHelper)
            }

            // Load aliases for ASR engine
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed: ${ex.message}", ex)
                }
            }

            // Set up hypotheses listener
            speechRecognitionManager.setOnHypothesesListener { hypotheses, partials ->
                val receivedAt = System.currentTimeMillis()
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                    val parseStartAt = System.currentTimeMillis()
                    Log.d(TAG, "Hypotheses received at $receivedAt, starting parse at $parseStartAt")
                    
                    try {
                        val matchContext = cachedMatchContext ?: run {
                            val t0 = System.currentTimeMillis()
                            val mc = buildMatchContext(getCurrentTiles)
                            cachedMatchContext = mc
                            Log.d(TAG, "buildMatchContext (on-the-fly) ms=${System.currentTimeMillis() - t0}")
                            mc
                        }

                        val result = aliasParser.parseSpokenWithHypotheses(
                            hypotheses, matchContext, partials, asrWeight = 0.4
                        )
                        val parseEndAt = System.currentTimeMillis()
                        Log.d(TAG, "Parse finished at $parseEndAt (parseDuration=${parseEndAt - parseStartAt} ms)")

                        withContext(Dispatchers.Main) {
                            onResultCallback?.invoke(result)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Hypotheses handling (background) failed: ${ex.message}", ex)
                    }
                }
            }

            // Set up raw result listener
            speechRecognitionManager.setOnRawResultListener { rawText ->
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    onRawResultCallback?.invoke(rawText)
                }
            }

            onLogCallback?.invoke("Spraakherkenning geactiveerd - protocol: 'Soortnaam Aantal'", "systeem")
            speechInitialized = true

            Log.d(TAG, "Speech recognition initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognition", e)
            throw e
        }
    }

    /**
     * Initialize volume key handler for triggering speech.
     */
    fun initializeVolumeKeyHandler(onVolumeUp: () -> Unit) {
        try {
            volumeKeyHandler = VolumeKeyHandler(activity)
            volumeKeyHandler.setOnVolumeUpListener {
                onVolumeUp()
            }
            volumeKeyHandler.register()

            Log.d(TAG, "Volume key handler initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing volume key handler", e)
        }
    }

    /**
     * Start speech recognition listening.
     */
    fun startListening(getCurrentTiles: () -> List<TellingScherm.SoortRow>) {
        if (speechInitialized && !speechRecognitionManager.isCurrentlyListening()) {
            updateSelectedSpeciesMap(getCurrentTiles())
            speechRecognitionManager.startListening()
            onLogCallback?.invoke("Luisteren...", "systeem")
        }
    }

    /**
     * Check if volume up key event should be handled.
     */
    fun isVolumeUpEvent(keyCode: Int): Boolean {
        return ::volumeKeyHandler.isInitialized && volumeKeyHandler.isVolumeUpEvent(keyCode)
    }

    /**
     * Update the selected species map for ASR hints.
     */
    fun updateSelectedSpeciesMap(tiles: List<TellingScherm.SoortRow>) {
        selectedSpeciesMap.clear()

        if (tiles.isEmpty()) {
            Log.w(TAG, "Species list is empty! Cannot update selectedSpeciesMap")
            return
        }

        for (soort in tiles) {
            selectedSpeciesMap[soort.naam] = soort.soortId
            selectedSpeciesMap[soort.naam.lowercase(Locale.getDefault())] = soort.soortId
        }

        if (speechInitialized) {
            Log.d(TAG, "Selected species map updated")
        }
    }

    /**
     * Build MatchContext for alias parsing.
     */
    suspend fun buildMatchContext(
        getCurrentTiles: () -> List<TellingScherm.SoortRow>
    ): MatchContext = withContext(Dispatchers.IO) {
        val snapshot = ServerDataCache.getOrLoad(activity)

        val tiles = getCurrentTiles().map { it.soortId }.toSet()

        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val siteAllowed = telpostId?.let { id ->
            snapshot.siteSpeciesBySite[id]?.map { it.soortid }?.toSet() ?: emptySet()
        } ?: snapshot.speciesById.keys

        val recents = RecentSpeciesStore.getRecents(activity).map { it.first }.toSet()

        val speciesById = snapshot.speciesById.mapValues { (_, sp) ->
            com.yvesds.vt5.features.speech.SpeciesInfo(
                id = sp.soortid,
                canonical = sp.soortnaam,
                synonyms = emptyList()
            )
        }

        MatchContext(
            inTiles = tiles,
            siteAllowed = siteAllowed,
            recents = recents,
            speciesById = speciesById
        )
    }

    /**
     * Rebuild cached MatchContext.
     */
    suspend fun rebuildCachedMatchContext(getCurrentTiles: () -> List<TellingScherm.SoortRow>) {
        try {
            val t0 = System.currentTimeMillis()
            val mc = buildMatchContext(getCurrentTiles)
            cachedMatchContext = mc
            Log.d(TAG, "Cached MatchContext rebuilt (ms=${System.currentTimeMillis() - t0})")
        } catch (ex: Exception) {
            Log.w(TAG, "Failed rebuilding cachedMatchContext: ${ex.message}", ex)
        }
    }

    /**
     * Refresh aliases runtime after user adds new alias.
     */
    suspend fun refreshAliasesRuntime(getCurrentTiles: () -> List<TellingScherm.SoortRow>) {
        try {
            // 1) AliasMatcher reload
            withContext(Dispatchers.IO) {
                try {
                    AliasMatcher.reloadIndex(activity, safHelper)
                    Log.d(TAG, "AliasMatcher.reloadIndex executed (post addAlias)")
                } catch (ex: Exception) {
                    Log.w(TAG, "AliasMatcher.reloadIndex failed (post addAlias): ${ex.message}", ex)
                }
            }

            // 2) ASR engine reload aliases
            withContext(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                    Log.d(TAG, "speechRecognitionManager.loadAliases executed (post addAlias)")
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed (post addAlias): ${ex.message}", ex)
                }
            }

            // 3) Rebuild cached MatchContext
            withContext(Dispatchers.Default) {
                try {
                    val mc = buildMatchContext(getCurrentTiles)
                    cachedMatchContext = mc
                    Log.d(TAG, "cachedMatchContext refreshed after user alias add")
                } catch (ex: Exception) {
                    Log.w(TAG, "buildMatchContext failed (post addAlias): ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "refreshAliasesRuntime overall failed: ${ex.message}", ex)
        }
    }

    /**
     * Handle alias reload broadcast.
     */
    suspend fun handleAliasReload(getCurrentTiles: () -> List<TellingScherm.SoortRow>) {
        try {
            val t0 = System.currentTimeMillis()
            val mc = buildMatchContext(getCurrentTiles)
            cachedMatchContext = mc
            Log.d(TAG, "cached MatchContext rebuilt after alias reload (ms=${System.currentTimeMillis() - t0})")
        } catch (ex: Exception) {
            Log.w(TAG, "Failed rebuilding cachedMatchContext after alias reload: ${ex.message}", ex)
        }

        // Reload ASR aliases on main
        withContext(Dispatchers.Main) {
            try {
                if (::speechRecognitionManager.isInitialized) {
                    speechRecognitionManager.loadAliases()
                    Log.d(TAG, "speechRecognitionManager.loadAliases invoked after alias reload")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to reload ASR aliases after alias reload: ${ex.message}", ex)
            }
        }
    }

    /**
     * Cleanup when activity is destroyed.
     */
    fun cleanup() {
        try {
            if (::volumeKeyHandler.isInitialized) {
                volumeKeyHandler.unregister()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    /**
     * Check if speech is initialized.
     */
    fun isInitialized(): Boolean = speechInitialized

    /**
     * Check if currently listening.
     */
    fun isCurrentlyListening(): Boolean {
        return ::speechRecognitionManager.isInitialized && 
               speechRecognitionManager.isCurrentlyListening()
    }
}
