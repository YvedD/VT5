package com.yvesds.vt5.features.speech

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.yvesds.vt5.features.alias.AliasRepository
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.math.min

/**
 * SpeechRecognitionManager
 *
 * Noise-robust listening and non-blocking parsing.
 *
 * Key points:
 * - RMS-based silence watcher removed (no false stops in wind/noise).
 * - RecognizerIntent silence hints still provided (silenceStopMillis).
 * - stopListening() uses cancel() aggressively and cancels parse jobs.
 * - Parsing is serialized on a dedicated single-thread dispatcher and is cancellable.
 * - All heavy work is off main thread.
 */
class SpeechRecognitionManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        private const val MAX_RESULTS = 5

        private val SPECIES_COUNT_PATTERN = Pattern.compile("([a-zA-Z\\s]+)\\s+(\\d+)")
        private val DUTCH_NUMBER_WORDS: Map<String, Int> = hashMapOf(
            "nul" to 0,
            "een" to 1, "één" to 1, "ene" to 1, "eens" to 1,
            "twee" to 2,
            "drie" to 3,
            "vier" to 4,
            "vijf" to 5,
            "zes" to 6,
            "zeven" to 7,
            "acht" to 8,
            "negen" to 9,
            "tien" to 10,
            "elf" to 11,
            "twaalf" to 12
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var availableSpecies = HashMap<String, String>()
    private var normalizedToOriginal = HashMap<String, String>()

    private var phoneticIndex: PhoneticIndex? = null
    private val parsingBuffer = SpeechParsingBuffer()

    private var onSpeciesCountListener: ((soortId: String, name: String, count: Int) -> Unit)? = null
    private var onRawResultListener: ((rawText: String) -> Unit)? = null
    private var onHypothesesListener: ((List<Pair<String, Float>>, List<String>) -> Unit)? = null

    private val normalizeStringBuilder = StringBuilder(100)

    private val aliasRepository: AliasRepository by lazy {
        AliasRepository.getInstance(activity)
    }
    private var aliasesLoaded = false
    var enablePartialsLogging: Boolean = true
    private val asrPartials = ArrayList<String>(32)

    // Default silence threshold (used to hint the ASR engine)
    @Volatile
    var silenceStopMillis: Long = 2000L
        private set

    // RMS threshold kept for compatibility but not used because RMS watcher removed
    @Volatile
    var rmsSilenceThreshold: Float = 2.0f
        private set

    // Single-threaded parsing dispatcher (serializes heavy parsing work) + scope
    private val parsingExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sr-parsing-thread").apply { isDaemon = true }
    }
    private val parsingDispatcher = parsingExecutor.asCoroutineDispatcher()
    private val parsingScope = CoroutineScope(parsingDispatcher + SupervisorJob())

    // session id and current parse job for cancelling/ignoring stale parses
    private val lastSessionId = AtomicInteger(0)
    @Volatile
    private var currentParseJob: Job? = null

    fun setSilenceStopMillis(ms: Long) {
        if (ms <= 0) return
        silenceStopMillis = ms
    }

    fun updateRmsSilenceThreshold(threshold: Float) {
        if (threshold < 0f) return
        rmsSilenceThreshold = threshold
    }

    data class SpeciesCount(val speciesId: String, val speciesName: String, val count: Int)

    fun initialize() {
        try {
            // Ensure match log writer is running before any ASR work/logging
            MatchLogWriter.start(activity) // 'activity' is the Activity passed to the manager
        } catch (ex: Exception) {
            Log.w(TAG, "MatchLogWriter.start() failed in initialize(): ${ex.message}", ex)
        }

        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())

            // Start loading aliases in background; do not block initialize()
            parsingScope.launch {
                loadAliases()
            }

        } else {
            Log.e(TAG, "Speech recognition is not available on this device")
        }
    }

    suspend fun loadAliases() {
        if (!aliasesLoaded) {
            aliasesLoaded = aliasRepository.loadAliasData()
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            initialize()
        }

        if (isListening) {
            stopListening()
        }

        try {
            val sessionId = lastSessionId.incrementAndGet()
            currentParseJob?.cancel()
            currentParseJob = null

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

                // Engine hints for silence - engines may or may not respect them
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceStopMillis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (silenceStopMillis * 0.8).toLong())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, (silenceStopMillis / 2).coerceAtLeast(250L))
            }

            synchronized(asrPartials) { asrPartials.clear() }

            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
            isListening = false
        }
    }

    /**
     * Stop listening quickly and cancel parsing.
     */
    fun stopListening() {
        if (isListening) {
            try {
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition: ${e.message}", e)
            } finally {
                isListening = false
                currentParseJob?.cancel()
                currentParseJob = null
            }
        }
    }

    /**
     * Immediate aggressive stop - use from UI for manual abort.
     */
    fun forceStopNow() {
        try {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            currentParseJob?.cancel()
            currentParseJob = null
        } catch (ex: Exception) {
            Log.w(TAG, "forceStopNow failed: ${ex.message}", ex)
        } finally {
            isListening = false
        }
    }

    fun setAvailableSpecies(speciesMap: Map<String, String>) {
        val newMap = HashMap<String, String>(speciesMap.size * 2)
        val newNormalizedMap = HashMap<String, String>(speciesMap.size * 2)
        newMap.putAll(speciesMap)
        for ((name, id) in speciesMap) {
            val normalized = normalizeSpeciesName(name)
            newNormalizedMap[normalized] = name
        }
        availableSpecies = newMap
        normalizedToOriginal = newNormalizedMap

        try {
            val entries = ArrayList<PhoneticEntry>(speciesMap.size)
            for ((name, id) in speciesMap) {
                val words = name.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val phoneticWords = words.map { word ->
                    PhoneticWord(word, ColognePhonetic.encode(word))
                }
                entries.add(PhoneticEntry(id, name, phoneticWords))
            }
            phoneticIndex = PhoneticIndex(entries)
        } catch (e: Exception) {
            Log.e(TAG, "Error building phonetic index", e)
        }
    }

    fun setOnSpeciesCountListener(listener: (soortId: String, name: String, count: Int) -> Unit) {
        onSpeciesCountListener = listener
    }

    fun setOnRawResultListener(listener: (rawText: String) -> Unit) {
        onRawResultListener = listener
    }

    fun setOnHypothesesListener(listener: (hypotheses: List<Pair<String, Float>>, partials: List<String>) -> Unit) {
        onHypothesesListener = listener
    }

    fun isCurrentlyListening(): Boolean = isListening

    fun destroy() {
        try {
            stopListening()
            currentParseJob?.cancel()
            parsingScope.cancel()

            try { parsingDispatcher.close() } catch (ex: Exception) { try { parsingExecutor.shutdownNow() } catch (_: Exception) {} }

            speechRecognizer?.destroy()
            speechRecognizer = null
            parsingBuffer.clear()
            synchronized(asrPartials) { asrPartials.clear() }
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer: ${e.message}", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
            }

            override fun onBeginningOfSpeech() {
            }

            override fun onRmsChanged(rmsdB: Float) {
                // NO-OP (RMS watcher removed)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio opnamefout"
                    SpeechRecognizer.ERROR_CLIENT -> "Client fout"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Onvoldoende rechten"
                    SpeechRecognizer.ERROR_NETWORK -> "Netwerkfout"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Netwerk timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Geen match gevonden"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Herkenner bezet"
                    SpeechRecognizer.ERROR_SERVER -> "Serverfout"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Geen spraak gedetecteerd"
                    else -> "Onbekende fout"
                }
                Log.e(TAG, "Error during speech recognition: $errorMessage ($error)")
                isListening = false
                synchronized(asrPartials) { asrPartials.clear() }
            }

            override fun onResults(results: Bundle?) {
                val tResults = System.currentTimeMillis()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
                if (matches.isNotEmpty()) {
                    val bestMatch = matches[0]
                    parsingBuffer.add(bestMatch)
                    onRawResultListener?.invoke(bestMatch)

                    val partialsCopy: List<String> = synchronized(asrPartials) {
                        val copy = ArrayList(asrPartials)
                        asrPartials.clear()
                        copy
                    }

                    val hypotheses: List<Pair<String, Float>> = run {
                        val confArray = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        if (confArray != null && confArray.isNotEmpty()) {
                            matches.mapIndexed { idx, m ->
                                val score = confArray.getOrNull(idx)?.coerceIn(0.0f, 1.0f) ?: (1.0f - idx * 0.25f).coerceAtLeast(0.0f)
                                m to score
                            }
                        } else {
                            matches.mapIndexed { idx, m ->
                                val score = when (idx) {
                                    0 -> 1.0f
                                    1 -> 0.65f
                                    2 -> 0.45f
                                    3 -> 0.25f
                                    else -> 0.10f
                                }
                                m to score
                            }
                        }
                    }

                    try {
                        onHypothesesListener?.invoke(hypotheses, partialsCopy)
                    } catch (ex: Exception) {
                        Log.w(TAG, "onHypothesesListener failed: ${ex.message}", ex)
                    }

                    val mySession = lastSessionId.get()

                    // cancel prior parse job for this session
                    currentParseJob?.cancel()

                    currentParseJob = parsingScope.launch {
                        val tStart = System.currentTimeMillis()
                        try {
                            if (onHypothesesListener == null) {
                                val matchResult = SPECIES_COUNT_PATTERN.matcher(bestMatch)
                                if (matchResult.find()) {
                                    val speciesNameRaw = matchResult.group(1)
                                    val countText = matchResult.group(2)
                                    if (speciesNameRaw != null && countText != null) {
                                        val speciesName = speciesNameRaw.trim()
                                        val count = countText.toIntOrNull() ?: 1
                                        val speciesId = fastFindSpeciesId(speciesName)
                                        if (speciesId != null) {
                                            if (mySession == lastSessionId.get()) {
                                                withContext(Dispatchers.Main) {
                                                    onSpeciesCountListener?.invoke(speciesId, speciesName, count)
                                                }
                                            } else return@launch
                                        } else {
                                            val recognizedItems = parseSpeciesWithCounts(bestMatch)
                                            if (recognizedItems.isNotEmpty()) {
                                                if (mySession == lastSessionId.get()) {
                                                    withContext(Dispatchers.Main) {
                                                        for (item in recognizedItems) {
                                                            onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                                                        }
                                                    }
                                                } else return@launch
                                            } else {
                                                if (mySession == lastSessionId.get()) {
                                                    withContext(Dispatchers.Main) {
                                                        onRawResultListener?.invoke(bestMatch)
                                                    }
                                                } else return@launch
                                            }
                                        }
                                    }
                                } else {
                                    val recognizedItems = parseSpeciesWithCounts(bestMatch)
                                    if (recognizedItems.isNotEmpty()) {
                                        if (mySession == lastSessionId.get()) {
                                            withContext(Dispatchers.Main) {
                                                for (item in recognizedItems) {
                                                    onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                                                }
                                            }
                                        } else return@launch
                                    } else {
                                        if (mySession == lastSessionId.get()) {
                                            withContext(Dispatchers.Main) {
                                                onRawResultListener?.invoke(bestMatch)
                                            }
                                        } else return@launch
                                    }
                                }
                            } else {
                                // caller handles hypotheses
                            }
                        } catch (ex: CancellationException) {
                            // cancelled - ignore
                        } catch (ex: Exception) {
                            Log.w(TAG, "Background parsing failed: ${ex.message}", ex)
                        } finally {
                            val tEnd = System.currentTimeMillis()
                        }
                    }
                }

                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!enablePartialsLogging) return

                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
                if (partials.isNotEmpty()) {
                    synchronized(asrPartials) {
                        for (p in partials) {
                            val normalized = p.trim()
                            if (normalized.isNotBlank()) {
                                asrPartials.add(normalized)
                                if (asrPartials.size > 40) {
                                    while (asrPartials.size > 40) asrPartials.removeAt(0)
                                }
                            }
                        }
                    }
                    onRawResultListener?.invoke(partials[0])

                    val quick = partials[0]
                    parsingScope.launch {
                        try {
                            val maybe = quickPartialParse(quick)
                            if (maybe != null) {
                                val current = lastSessionId.get()
                                if (current == lastSessionId.get()) {
                                    withContext(Dispatchers.Main) {
                                        onSpeciesCountListener?.invoke(maybe.speciesId, maybe.speciesName, maybe.count)
                                    }
                                }
                            }
                        } catch (_: CancellationException) {
                        } catch (ex: Exception) {
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun quickPartialParse(text: String): SpeciesCount? {
        val m = SPECIES_COUNT_PATTERN.matcher(text)
        if (m.find()) {
            val name = m.group(1)?.trim() ?: return null
            val count = m.group(2)?.toIntOrNull() ?: 1
            val sid = fastFindSpeciesId(name) ?: return null
            return SpeciesCount(sid, availableSpecies.entries.firstOrNull { it.value == sid }?.key ?: name, count)
        }

        val tokens = tokenize(normalize(text))
        if (tokens.size >= 2 && isNumeric(tokens.last())) {
            val count = parseCount(tokens.last())
            val candidate = tokens.subList(0, tokens.size - 1).joinToString(" ")
            val sid = fastFindSpeciesId(candidate)
            if (sid != null) return SpeciesCount(sid, availableSpecies.entries.firstOrNull { it.value == sid }?.key ?: candidate, count)
        }
        return null
    }

    private fun fastFindSpeciesId(speciesName: String): String? {
        if (speciesName.isBlank()) return null
        availableSpecies[speciesName]?.let { return it }
        for ((key, value) in availableSpecies) {
            if (key.equals(speciesName, ignoreCase = true)) return value
        }
        val normalized = normalizeSpeciesName(speciesName)
        availableSpecies[normalized]?.let { return it }
        if (aliasesLoaded) {
            val aliasId = aliasRepository.findSpeciesIdByAlias(speciesName)
            if (aliasId != null && availableSpecies.containsValue(aliasId)) return aliasId
        }
        val index = phoneticIndex ?: return null
        val activeSpeciesNames = availableSpecies.keys.toSet()
        val candidates = index.findCandidates(normalized, activeSpeciesNames, 1)
        if (candidates.isNotEmpty()) {
            return candidates.first().sourceId
        }
        return null
    }


    private fun parseSpeciesWithCounts(spokenText: String): List<SpeciesCount> {
        val result = ArrayList<SpeciesCount>(3)
        val normalizedInput = normalize(spokenText)
        val tokens = tokenize(normalizedInput)
        val splitTokens = splitEmbeddedNumbers(tokens)

        var i = 0
        while (i < splitTokens.size) {
            val numIdx = findNextNumber(splitTokens, i)
            if (numIdx == -1) {
                val remaining = splitTokens.subList(i, splitTokens.size).joinToString(" ")
                val speciesMatch = findSpeciesMatch(remaining)
                if (speciesMatch != null) {
                    result.add(SpeciesCount(speciesMatch.first, speciesMatch.second, 1))
                }
                break
            }

            val countToken = splitTokens[numIdx]
            val count = parseCount(countToken)

            if (numIdx > i) {
                val potentialSpecies = splitTokens.subList(i, numIdx).joinToString(" ")
                val speciesMatch = findSpeciesMatch(potentialSpecies)
                if (speciesMatch != null) {
                    result.add(SpeciesCount(speciesMatch.first, speciesMatch.second, count))
                    i = numIdx + 1
                    continue
                }

                if (isDigitOne(countToken) && numIdx + 1 < splitTokens.size && isNumeric(splitTokens[numIdx + 1])) {
                    val secondNum = numIdx + 1
                    val repairedTokens = ArrayList(splitTokens)
                    repairedTokens[numIdx] = "eend"

                    val repairedPhrase = repairedTokens.subList(i, secondNum).joinToString(" ")
                    val repairedMatch = findSpeciesMatch(repairedPhrase)

                    if (repairedMatch != null) {
                        val secondCount = parseCount(splitTokens[secondNum])
                        result.add(SpeciesCount(repairedMatch.first, repairedMatch.second, secondCount))
                        i = secondNum + 1
                        continue
                    }
                }
            }
            i = numIdx + 1
        }

        return result
    }

    private fun findSpeciesMatch(text: String): Pair<String, String>? {
        if (text.isBlank()) return null
        val normalized = normalizeSpeciesName(text)
        availableSpecies[normalized]?.let { id ->
            val originalName = normalizedToOriginal[normalized] ?: normalized
            return id to originalName
        }

        if (aliasesLoaded) {
            val aliasId = aliasRepository.findSpeciesIdByAlias(text)
            if (aliasId != null && availableSpecies.containsValue(aliasId)) {
                val originalName = availableSpecies.entries.firstOrNull { it.value == aliasId }?.key ?: text
                return aliasId to originalName
            }
        }

        val index = phoneticIndex
        if (index != null) {
            val activeSpeciesNames = availableSpecies.keys.toSet()
            val candidates = index.findCandidates(normalizeSpeciesName(text), activeSpeciesNames, 5)
            if (candidates.isNotEmpty()) {
                val bestCandidate = candidates.first()
                return bestCandidate.sourceId to bestCandidate.sourceName
            }
        }

        var bestMatch: Pair<String, String>? = null
        var bestScore = 0.0
        for ((name, id) in availableSpecies) {
            val similarity = calculateSimilarity(normalize(text), name)
            if (similarity > 0.85 && similarity > bestScore) {
                bestScore = similarity
                bestMatch = id to name
            }
        }
        return bestMatch
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val s1Norm = normalizeSpeciesName(s1)
        val s2Norm = normalizeSpeciesName(s2)
        val levDistance = levenshteinDistance(s1Norm, s2Norm)
        val maxLen = kotlin.math.max(s1Norm.length, s2Norm.length)
        val levSimilarity = if (maxLen > 0) 1.0 - (levDistance.toDouble() / maxLen.toDouble()) else 0.0
        val phoneticSimilarity = ColognePhonetic.similarity(s1Norm, s2Norm)
        return (0.7 * phoneticSimilarity + 0.3 * levSimilarity).coerceIn(0.0, 1.0)
    }

    // ------------------------
    // Helper functions (normalization, tokenization, levenshtein, etc.)
    // These are the same implementations as in your original file.
    // ------------------------

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) dp[i - 1][j - 1] else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun normalize(text: String): String {
        normalizeStringBuilder.setLength(0)
        val lowercase = text.lowercase(Locale.ROOT)
        for (c in lowercase) {
            when {
                c.isLetterOrDigit() -> normalizeStringBuilder.append(c)
                c.isWhitespace() -> normalizeStringBuilder.append(' ')
                else -> {}
            }
        }
        var i = 0
        while (i < normalizeStringBuilder.length - 1) {
            if (normalizeStringBuilder[i] == ' ' && normalizeStringBuilder[i + 1] == ' ') {
                normalizeStringBuilder.deleteCharAt(i)
            } else {
                i++
            }
        }
        var start = 0
        var end = normalizeStringBuilder.length - 1
        while (start <= end && normalizeStringBuilder[start] == ' ') start++
        while (end >= start && normalizeStringBuilder[end] == ' ') end--
        return if (start > 0 || end < normalizeStringBuilder.length - 1) {
            normalizeStringBuilder.substring(start, end + 1)
        } else {
            normalizeStringBuilder.toString()
        }
    }

    private fun tokenize(text: String): List<String> {
        return text.split(' ').filter { it.isNotEmpty() }
    }

    private fun splitEmbeddedNumbers(tokens: List<String>): List<String> {
        val result = ArrayList<String>(tokens.size + 5)
        val pattern = Regex("([a-z]+)(\\d+)")
        for (token in tokens) {
            val match = pattern.matchEntire(token)
            if (match != null) {
                val (text, number) = match.destructured
                result.add(text)
                result.add(number)
            } else {
                result.add(token)
            }
        }
        return result
    }

    private fun findNextNumber(tokens: List<String>, startIndex: Int): Int {
        for (i in startIndex until tokens.size) {
            if (isNumeric(tokens[i])) return i
        }
        return -1
    }

    private fun isNumeric(token: String): Boolean {
        if (token.all { it.isDigit() }) return true
        return DUTCH_NUMBER_WORDS[token] != null
    }

    private fun parseCount(token: String): Int {
        if (token.all { it.isDigit() }) return token.toIntOrNull() ?: 1
        return DUTCH_NUMBER_WORDS[token] ?: 1
    }

    private fun isDigitOne(token: String): Boolean = token == "1"

    private fun normalizeSpeciesName(name: String): String {
        normalizeStringBuilder.setLength(0)
        val lowercase = name.lowercase(Locale.ROOT)
        for (c in lowercase) {
            when {
                c.isLetterOrDigit() -> normalizeStringBuilder.append(c)
                c.isWhitespace() -> normalizeStringBuilder.append(' ')
                else -> normalizeStringBuilder.append(' ')
            }
        }
        var i = 0
        while (i < normalizeStringBuilder.length - 1) {
            if (normalizeStringBuilder[i] == ' ' && normalizeStringBuilder[i + 1] == ' ') {
                normalizeStringBuilder.deleteCharAt(i)
            } else {
                i++
            }
        }
        var start = 0
        var end = normalizeStringBuilder.length - 1
        while (start <= end && normalizeStringBuilder[start] == ' ') start++
        while (end >= start && normalizeStringBuilder[end] == ' ') end--
        val trimmed = if (start > 0 || end < normalizeStringBuilder.length - 1) {
            normalizeStringBuilder.substring(start, end + 1)
        } else {
            normalizeStringBuilder.toString()
        }
        val words = trimmed.split(' ')
        val result = StringBuilder(trimmed.length)
        for (i2 in words.indices) {
            if (i2 > 0) result.append(' ')
            result.append(singularizeNl(words[i2]))
        }
        return result.toString()
    }

    private fun singularizeNl(word: String): String {
        if (word.length <= 3) return word
        if (word == "ganzen") return "gans"
        if (word.endsWith("en")) return if (word.endsWith("zen")) word.dropLast(3) + "s" else word.dropLast(2)
        if (word.endsWith("s")) return word.dropLast(1)
        return word
    }
}