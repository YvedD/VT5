package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.core.opslag.SaFStorageHelper
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
 * - Initialize and manage SpeechRecognitionManager
 * - Handle volume key input for speech activation
 * - Process speech hypotheses and match results
 * - Manage speech-to-species matching context
 */
class TellingSpeechHandler(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val safHelper: SaFStorageHelper,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "TellingSpeechHandler"
        private const val PREF_ASR_SILENCE_MS = "pref_asr_silence_ms"
        private const val DEFAULT_SILENCE_MS = 1000
    }

    // Speech components
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    private lateinit var volumeKeyHandler: VolumeKeyHandler
    private lateinit var aliasParser: AliasSpeechParser
    private var speechInitialized = false

    // Cached match context
    @Volatile
    private var cachedMatchContext: MatchContext? = null

    // Callbacks
    var onHypothesesReceived: ((List<Pair<String, Float>>, List<String>) -> Unit)? = null
    var onRawResult: ((String) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null

    /**
     * Initialize speech recognition system.
     */
    fun initialize() {
        try {
            speechRecognitionManager = SpeechRecognitionManager(activity)
            speechRecognitionManager.initialize()

            val savedMs = prefs.getInt(PREF_ASR_SILENCE_MS, DEFAULT_SILENCE_MS)
            speechRecognitionManager.setSilenceStopMillis(savedMs.toLong())

            // Ensure alias parser is ready
            if (!::aliasParser.isInitialized) {
                aliasParser = AliasSpeechParser(activity, safHelper)
            }

            // Load alias internals for ASR engine
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed: ${ex.message}", ex)
                }
            }

            // Set up listeners
            speechRecognitionManager.setOnHypothesesListener { hypotheses, partials ->
                onHypothesesReceived?.invoke(hypotheses, partials)
            }

            speechRecognitionManager.setOnRawResultListener { rawText ->
                onRawResult?.invoke(rawText)
            }

            speechInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognition", e)
            throw e
        }
    }

    /**
     * Initialize volume key handler for speech activation.
     */
    fun initializeVolumeKeyHandler() {
        try {
            volumeKeyHandler = VolumeKeyHandler(activity)
            volumeKeyHandler.setOnVolumeUpListener {
                startListening()
            }
            volumeKeyHandler.register()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing volume key handler", e)
        }
    }

    /**
     * Start listening for speech input.
     */
    fun startListening() {
        if (speechInitialized && !speechRecognitionManager.isCurrentlyListening()) {
            speechRecognitionManager.startListening()
            onListeningStarted?.invoke()
        }
    }

    /**
     * Handle key down events for volume key detection.
     */
    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::volumeKeyHandler.isInitialized && volumeKeyHandler.isVolumeUpEvent(keyCode)) {
            startListening()
            return true
        }
        return false
    }

    /**
     * Load aliases for speech recognition.
     */
    suspend fun loadAliases() {
        if (::speechRecognitionManager.isInitialized) {
            withContext(Dispatchers.IO) {
                try {
                    speechRecognitionManager.loadAliases()
                } catch (ex: Exception) {
                    Log.w(TAG, "speechRecognitionManager.loadAliases failed: ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * Parse speech hypotheses using the alias parser.
     */
    suspend fun parseSpokenWithHypotheses(
        hypotheses: List<Pair<String, Float>>,
        matchContext: MatchContext,
        partials: List<String>,
        asrWeight: Double = 0.4
    ): MatchResult {
        return withContext(Dispatchers.Default) {
            aliasParser.parseSpokenWithHypotheses(hypotheses, matchContext, partials, asrWeight)
        }
    }

    /**
     * Update cached match context.
     */
    fun updateCachedMatchContext(context: MatchContext) {
        cachedMatchContext = context
    }

    /**
     * Get cached match context.
     */
    fun getCachedMatchContext(): MatchContext? = cachedMatchContext

    /**
     * Check if speech recognition is initialized.
     */
    fun isInitialized(): Boolean = speechInitialized

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        try {
            if (::volumeKeyHandler.isInitialized) {
                volumeKeyHandler.unregister()
            }
        } catch (_: Exception) {}
    }

    /**
     * Build selected species map for speech recognition hints.
     */
    fun buildSelectedSpeciesMap(speciesMap: Map<String, String>): HashMap<String, String> {
        val selectedSpeciesMap = HashMap<String, String>(100)
        
        if (speciesMap.isEmpty()) {
            Log.w(TAG, "Species list is empty! Cannot build selectedSpeciesMap")
            return selectedSpeciesMap
        }

        for ((soortId, naam) in speciesMap) {
            selectedSpeciesMap[naam] = soortId
            selectedSpeciesMap[naam.lowercase(Locale.getDefault())] = soortId
        }

        if (speechInitialized) {
        }
        
        return selectedSpeciesMap
    }
}
