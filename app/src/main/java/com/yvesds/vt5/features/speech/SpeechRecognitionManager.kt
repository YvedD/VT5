package com.yvesds.vt5.features.speech

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.yvesds.vt5.features.alias.AliasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min

/**
 * Verbeterde spraakherkenningsmanager met fonetische indexering voor nauwkeurigere matching
 * Geoptimaliseerd voor performance met:
 * - Efficiënte HashMap pre-allocaties
 * - Minder garbage collection door objecten te hergebruiken
 * - Directe regex matching voor standaard patronen
 * - Cache van fonetische indexering
 * - Alias ondersteuning voor betere herkenning van varianten
 */
class SpeechRecognitionManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        private const val MAX_RESULTS = 5

        // Regex om aantallen te herkennen in tekst - pre-compiled voor betere performance
        private val NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b")

        // Direct matching pattern voor "Soortnaam Aantal" - pre-compiled
        private val SPECIES_COUNT_PATTERN = Pattern.compile("([a-zA-Z\\s]+)\\s+(\\d+)")

        // NL telwoorden - ingeladen als constante map voor betere performance
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

    // Fonetische index voor snelle en nauwkeurige matching
    private var phoneticIndex: PhoneticIndex? = null

    // Parsing buffer voor stabielere herkenning
    private val parsingBuffer = SpeechParsingBuffer()

    // Callbacks
    private var onSpeciesCountListener: ((soortId: String, name: String, count: Int) -> Unit)? = null
    private var onRawResultListener: ((rawText: String) -> Unit)? = null

    // Herbruikbare StringBuilder voor normalisatie om GC-druk te verminderen
    private val normalizeStringBuilder = StringBuilder(100)

    // Alias repository voor alias matching
    private val aliasRepository: AliasRepository by lazy {
        AliasRepository.getInstance(activity)
    }

    // Status van alias loading
    private var aliasesLoaded = false

    /**
     * Data class die een herkende soort met aantal voorstelt
     */
    data class SpeciesCount(val speciesId: String, val speciesName: String, val count: Int)

    /**
     * Initialiseert de spraakherkenning
     */
    fun initialize() {
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())

            // Start het laden van aliassen asynchroon
            CoroutineScope(Dispatchers.IO).launch {
                loadAliases()
            }

            Log.d(TAG, "Speech recognizer initialized")
        } else {
            Log.e(TAG, "Speech recognition is not available on this device")
        }
    }

    /**
     * Laad alias data voor betere speech matching
     */
    suspend fun loadAliases() {
        if (!aliasesLoaded) {
            aliasesLoaded = aliasRepository.loadAliasData()
            Log.d(TAG, "Aliases loaded: $aliasesLoaded")
        }
    }

    /**
     * Start het luisteren naar spraak
     */
    fun startListening() {
        if (speechRecognizer == null) {
            initialize()
        }

        if (isListening) {
            stopListening()
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "nl")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
            isListening = false
        }
    }

    /**
     * Stopt het luisteren naar spraak
     */
    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition: ${e.message}", e)
            } finally {
                isListening = false
            }
        }
    }

    /**
     * Zet de lijst met beschikbare soorten en bouwt de fonetische index
     * Geoptimaliseerd voor snelheid en geheugenefficiëntie
     */
    fun setAvailableSpecies(speciesMap: Map<String, String>) {
        // Maak nieuwe maps voor thread-safety
        val newMap = HashMap<String, String>(speciesMap.size * 2)
        val newNormalizedMap = HashMap<String, String>(speciesMap.size * 2)

        // Clone de map
        newMap.putAll(speciesMap)

        // Build lookup caches
        for ((name, id) in speciesMap) {
            val normalized = normalizeSpeciesName(name)
            newNormalizedMap[normalized] = name
        }

        // Assign de maps atomair
        availableSpecies = newMap
        normalizedToOriginal = newNormalizedMap

        try {
            // Bouw de fonetische index
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

    fun isCurrentlyListening(): Boolean = isListening

    fun destroy() {
        try {
            stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            parsingBuffer.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer: ${e.message}", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Optioneel: toon visuele feedback over geluidsniveau
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Niet nodig voor onze implementatie
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
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
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()

                if (matches.isNotEmpty()) {
                    // Neem beste match
                    val bestMatch = matches[0]

                    // Voeg toe aan buffer voor stabiliteit
                    parsingBuffer.add(bestMatch)

                    // Stuur ruwe tekst door voor debugging
                    onRawResultListener?.invoke(bestMatch)

                    // Probeer eerst directe regex matching voor "Soortnaam Aantal" patroon
                    val matchResult = SPECIES_COUNT_PATTERN.matcher(bestMatch)
                    if (matchResult.find()) {
                        // Veilig ophalen van groepen met null-check
                        val speciesNameRaw = matchResult.group(1)
                        val countText = matchResult.group(2)

                        if (speciesNameRaw != null && countText != null) {
                            val speciesName = speciesNameRaw.trim()
                            val count = countText.toIntOrNull() ?: 1

                            // Zoek de soortId op basis van de naam - eerst directe match
                            val speciesId = findSpeciesIdEfficient(speciesName)

                            if (speciesId != null) {
                                onSpeciesCountListener?.invoke(speciesId, speciesName, count)
                            } else {
                                // Als directe match mislukt, probeer complexe parsing
                                val recognizedItems = parseSpeciesWithCounts(bestMatch)
                                if (recognizedItems.isNotEmpty()) {
                                    for (item in recognizedItems) {
                                        onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                                    }
                                }
                            }
                        } else {
                            // Als regex groepen null zijn, val terug op complexe parsing
                            val recognizedItems = parseSpeciesWithCounts(bestMatch)
                            if (recognizedItems.isNotEmpty()) {
                                for (item in recognizedItems) {
                                    onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                                }
                            }
                        }
                    } else {
                        // Als regex niet matcht, probeer complexe parsing
                        val recognizedItems = parseSpeciesWithCounts(bestMatch)
                        if (recognizedItems.isNotEmpty()) {
                            for (item in recognizedItems) {
                                onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                            }
                        }
                    }
                }

                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Niet gebruikt in deze implementatie
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Niet gebruikt in deze implementatie
            }
        }
    }

    /**
     * Helper functie om een soort-ID te vinden op basis van een soortnaam
     * Geoptimaliseerd voor snelheid met alias ondersteuning
     */
    private fun findSpeciesIdEfficient(speciesName: String): String? {
        // 1. Directe match
        availableSpecies[speciesName]?.let { return it }

        // 2. Case-insensitive match
        for ((key, value) in availableSpecies) {
            if (key.equals(speciesName, ignoreCase = true)) {
                return value
            }
        }

        // 3. Genormaliseerde match
        val normalized = normalizeSpeciesName(speciesName)
        availableSpecies[normalized]?.let { return it }

        // 4. Alias match - NIEUW
        if (aliasesLoaded) {
            val aliasId = aliasRepository.findSpeciesIdByAlias(speciesName)
            if (aliasId != null && availableSpecies.containsValue(aliasId)) {
                Log.d(TAG, "Found match via alias: '$speciesName' -> $aliasId")
                return aliasId
            }
        }

        return null
    }

    /**
     * Parseert spraakresultaat volgens protocol "Soortnaam Aantal Soortnaam Aantal"
     * met fonetische matching voor betere herkenning
     * Geoptimaliseerd voor performance
     */
    private fun parseSpeciesWithCounts(spokenText: String): List<SpeciesCount> {
        val result = ArrayList<SpeciesCount>(3) // Pre-allocate capacity
        val normalizedInput = normalize(spokenText)
        val tokens = tokenize(normalizedInput)
        val splitTokens = splitEmbeddedNumbers(tokens)

        var i = 0
        while (i < splitTokens.size) {
            val numIdx = findNextNumber(splitTokens, i)

            if (numIdx == -1) {
                // Geen getal meer gevonden, check of er nog een soort overblijft
                val remaining = splitTokens.subList(i, splitTokens.size).joinToString(" ")
                val speciesMatch = findSpeciesMatch(remaining)

                if (speciesMatch != null) {
                    // Gevonden soort zonder getal krijgt count 1
                    result.add(SpeciesCount(speciesMatch.first, speciesMatch.second, 1))
                }
                break
            }

            // We hebben een getal gevonden
            val countToken = splitTokens[numIdx]
            val count = parseCount(countToken)

            if (numIdx > i) {
                // Er staan woorden voor dit getal, mogelijk een soortnaam
                val potentialSpecies = splitTokens.subList(i, numIdx).joinToString(" ")
                val speciesMatch = findSpeciesMatch(potentialSpecies)

                if (speciesMatch != null) {
                    result.add(SpeciesCount(speciesMatch.first, speciesMatch.second, count))
                    i = numIdx + 1
                    continue
                }

                // Probeer ASR-reparatie voor "eend" (vaak herkend als "1")
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

            // Geen match gevonden of geen woorden voor het getal
            i = numIdx + 1
        }

        return result
    }

    /**
     * Zoekt een match voor de opgegeven tekst in de beschikbare soorten
     * met fonetische indexering voor betere resultaten
     * Geoptimaliseerd voor performance, nu ook met alias ondersteuning
     */
    private fun findSpeciesMatch(text: String): Pair<String, String>? {
        if (text.isBlank()) return null

        val normalized = normalizeSpeciesName(text)

        // 1. Directe match (exacte match op naam)
        availableSpecies[normalized]?.let { id ->
            // Gebruik originele naam indien beschikbaar
            val originalName = normalizedToOriginal[normalized] ?: normalized
            return id to originalName
        }

        // 2. Alias match (nieuw!)
        if (aliasesLoaded) {
            val aliasId = aliasRepository.findSpeciesIdByAlias(text)
            if (aliasId != null && availableSpecies.containsValue(aliasId)) {
                // Zoek originele naam voor deze soortId
                val originalName = availableSpecies.entries
                    .firstOrNull { it.value == aliasId }?.key ?: text

                Log.d(TAG, "Found match via alias: '$text' -> $aliasId ($originalName)")
                return aliasId to originalName
            }
        }

        // 3. Fonetische kandidaten zoeken via de index
        val index = phoneticIndex
        if (index != null) {
            // Maak een set van alle beschikbare soortnamen
            val activeSpeciesNames = availableSpecies.keys.toSet()

            // Gebruik de juiste parameters: (query, activeSpeciesSet, maxResults)
            val candidates = index.findCandidates(normalized, activeSpeciesNames, 5)

            if (candidates.isNotEmpty()) {
                // Neem de hoogst scorende kandidaat
                val bestCandidate = candidates.first()
                return bestCandidate.sourceId to bestCandidate.sourceName
            }
        }

        // 4. Fallback: zoek op basis van levenshtein afstand
        var bestMatch: Pair<String, String>? = null
        var bestScore = 0.0

        for ((name, id) in availableSpecies) {
            val similarity = calculateSimilarity(normalized, name)
            if (similarity > 0.85 && similarity > bestScore) {  // Threshold voor fuzzy matching
                bestScore = similarity
                bestMatch = id to name
            }
        }

        return bestMatch
    }

    /**
     * Berekent similariteit tussen twee strings voor fuzzy matching
     * Geoptimaliseerd
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val s1Norm = normalizeSpeciesName(s1)
        val s2Norm = normalizeSpeciesName(s2)

        val levDistance = levenshteinDistance(s1Norm, s2Norm)
        val maxLen = kotlin.math.max(s1Norm.length, s2Norm.length)
        val levSimilarity = if (maxLen > 0) 1.0 - (levDistance.toDouble() / maxLen.toDouble()) else 0.0

        val phoneticSimilarity = ColognePhonetic.similarity(s1Norm, s2Norm)

        // Combineer scores (70% fonetisch, 30% levenshtein)
        return (0.7 * phoneticSimilarity + 0.3 * levSimilarity).coerceIn(0.0, 1.0)
    }

    /**
     * Berekent Levenshtein afstand tussen twee strings
     * Geoptimaliseerd
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        // Early exit conditions
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        // Gebruik arrays van primitives voor betere performance
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i-1] == s2[j-1]) dp[i-1][j-1] else {
                    1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
                }
            }
        }

        return dp[s1.length][s2.length]
    }

    private fun isDigitOne(token: String): Boolean = token == "1"

    private fun findNextNumber(tokens: List<String>, startIndex: Int): Int {
        for (i in startIndex until tokens.size) {
            if (isNumeric(tokens[i])) {
                return i
            }
        }
        return -1
    }

    private fun isNumeric(token: String): Boolean {
        if (token.all { it.isDigit() }) return true
        return DUTCH_NUMBER_WORDS[token] != null
    }

    private fun parseCount(token: String): Int {
        if (token.all { it.isDigit() }) {
            return token.toIntOrNull() ?: 1
        }
        return DUTCH_NUMBER_WORDS[token] ?: 1
    }

    /**
     * Normalize text for processing - geoptimaliseerd met StringBuilder hergebruik
     */
    private fun normalize(text: String): String {
        normalizeStringBuilder.setLength(0) // Clear without re-allocation

        val lowercase = text.lowercase(Locale.ROOT)
        for (c in lowercase) {
            when {
                c.isLetterOrDigit() -> normalizeStringBuilder.append(c)
                c.isWhitespace() -> normalizeStringBuilder.append(' ')
            }
        }

        // Normalize whitespace without regex
        var i = 0
        while (i < normalizeStringBuilder.length - 1) {
            if (normalizeStringBuilder[i] == ' ' && normalizeStringBuilder[i + 1] == ' ') {
                normalizeStringBuilder.deleteCharAt(i)
            } else {
                i++
            }
        }

        // Trim without creating new string
        var start = 0
        var end = normalizeStringBuilder.length - 1

        while (start <= end && normalizeStringBuilder[start] == ' ') {
            start++
        }

        while (end >= start && normalizeStringBuilder[end] == ' ') {
            end--
        }

        return if (start > 0 || end < normalizeStringBuilder.length - 1) {
            normalizeStringBuilder.substring(start, end + 1)
        } else {
            normalizeStringBuilder.toString()
        }
    }

    /**
     * Tokenize text into words
     */
    private fun tokenize(text: String): List<String> {
        return text.split(' ').filter { it.isNotEmpty() }
    }

    /**
     * Split tokens with embedded numbers
     */
    private fun splitEmbeddedNumbers(tokens: List<String>): List<String> {
        val result = ArrayList<String>(tokens.size + 5) // Pre-allocate with extra space
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

    /**
     * Normalize species names for better matching
     */
    private fun normalizeSpeciesName(name: String): String {
        normalizeStringBuilder.setLength(0) // Clear without re-allocation

        // Lowercase and replace special chars
        val lowercase = name.lowercase(Locale.ROOT)
        for (c in lowercase) {
            when {
                c.isLetterOrDigit() -> normalizeStringBuilder.append(c)
                c.isWhitespace() -> normalizeStringBuilder.append(' ')
                else -> normalizeStringBuilder.append(' ')
            }
        }

        // Normalize whitespace
        var i = 0
        while (i < normalizeStringBuilder.length - 1) {
            if (normalizeStringBuilder[i] == ' ' && normalizeStringBuilder[i + 1] == ' ') {
                normalizeStringBuilder.deleteCharAt(i)
            } else {
                i++
            }
        }

        // Trim
        var start = 0
        var end = normalizeStringBuilder.length - 1

        while (start <= end && normalizeStringBuilder[start] == ' ') {
            start++
        }

        while (end >= start && normalizeStringBuilder[end] == ' ') {
            end--
        }

        val trimmed = if (start > 0 || end < normalizeStringBuilder.length - 1) {
            normalizeStringBuilder.substring(start, end + 1)
        } else {
            normalizeStringBuilder.toString()
        }

        // Singularize words
        val words = trimmed.split(' ')
        val result = StringBuilder(trimmed.length)

        for (i in words.indices) {
            if (i > 0) result.append(' ')
            result.append(singularizeNl(words[i]))
        }

        return result.toString()
    }

    /**
     * Convert Dutch plural to singular form
     */
    private fun singularizeNl(word: String): String {
        if (word.length <= 3) return word
        if (word == "ganzen") return "gans"
        if (word.endsWith("en")) {
            return if (word.endsWith("zen")) word.dropLast(3) + "s" else word.dropLast(2)
        }
        if (word.endsWith("s")) return word.dropLast(1)
        return word
    }
}