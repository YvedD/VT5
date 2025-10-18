package com.yvesds.vt5.features.speech

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min

/**
 * Verbeterde spraakherkenningsmanager met fonetische indexering voor nauwkeurigere matching
 */
class SpeechRecognitionManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        private const val MAX_RESULTS = 5

        // Regex om aantallen te herkennen in tekst
        private val NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b")

        // NL telwoorden
        private val DUTCH_NUMBER_WORDS: Map<String, Int> = mapOf(
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
    private var availableSpecies = emptyMap<String, String>() // naam -> id

    // Fonetische index voor snelle en nauwkeurige matching
    private var phoneticIndex: PhoneticIndex? = null

    // Parsing buffer voor stabielere herkenning
    private var parsingBuffer = SpeechParsingBuffer()

    // Callbacks
    private var onSpeciesCountListener: ((soortId: String, name: String, count: Int) -> Unit)? = null
    private var onRawResultListener: ((rawText: String) -> Unit)? = null

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
            Log.d(TAG, "Speech recognizer initialized")
        } else {
            Log.e(TAG, "Speech recognition is not available on this device")
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
            Log.d(TAG, "Started listening for speech")
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
            Log.d(TAG, "Stopped listening for speech")
        }
    }

    /**
     * Zet de lijst met beschikbare soorten en bouwt de fonetische index
     */
    /**
     * Zet de lijst met beschikbare soorten en bouwt de fonetische index
     */
    fun setAvailableSpecies(speciesMap: Map<String, String>) {
        Log.d(TAG, "setAvailableSpecies called with ${speciesMap.size} entries")

        // Check voor lege map
        if (speciesMap.isEmpty()) {
            Log.e(TAG, "Warning: Empty species map provided!")
        }

        // Clone de map om thread-safety te garanderen
        this.availableSpecies = HashMap(speciesMap)

        // Log enkele voorbeelden
        for (entry in speciesMap.entries.take(5)) {
            Log.d(TAG, "Species entry: ${entry.key} -> ${entry.value}")
        }

        try {
            // Bouw de fonetische index
            val entries = speciesMap.map { (name, id) ->
                val words = name.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val phoneticWords = words.map { word ->
                    PhoneticWord(word, ColognePhonetic.encode(word))
                }
                PhoneticEntry(id, name, phoneticWords)
            }

            this.phoneticIndex = PhoneticIndex(entries)
            Log.d(TAG, "Built phonetic index with ${entries.size} species")
        } catch (e: Exception) {
            Log.e(TAG, "Error building phonetic index", e)
        }
    }
    fun setOnSpeciesCountListener(listener: (soortId: String, name: String, count: Int) -> Unit) {
        this.onSpeciesCountListener = listener
    }

    fun setOnRawResultListener(listener: (rawText: String) -> Unit) {
        this.onRawResultListener = listener
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
                Log.d(TAG, "Speech recognition results: $matches")

                if (matches.isNotEmpty()) {
                    // Neem beste match
                    val bestMatch = matches[0]

                    // Voeg toe aan buffer voor stabiliteit
                    parsingBuffer.add(bestMatch)

                    // Stuur ruwe tekst door voor debugging
                    onRawResultListener?.invoke(bestMatch)

                    // DIRECTE VERWERKING VAN "SOORTNAAM AANTAL" PATROON
                    val pattern = Regex("([a-zA-Z\\s]+)\\s+(\\d+)")
                    val matchResult = pattern.find(bestMatch)

                    if (matchResult != null) {
                        val (speciesNameRaw, countText) = matchResult.destructured
                        val speciesName = speciesNameRaw.trim()
                        val count = countText.toIntOrNull() ?: 1

                        Log.d(TAG, "Direct regex match: Species='$speciesName', Count=$count")

                        // Zoek de soortId op basis van de naam
                        val speciesId = findSpeciesId(speciesName)

                        if (speciesId != null) {
                            Log.d(TAG, "Species ID found: $speciesId")
                            onSpeciesCountListener?.invoke(speciesId, speciesName, count)
                        } else {
                            Log.d(TAG, "No species ID found for: $speciesName")
                        }
                    } else {
                        // Val terug op de normale parsing als de regex niet matcht
                        val recognizedItems = parseSpeciesWithCounts(bestMatch)

                        if (recognizedItems.isNotEmpty()) {
                            for (item in recognizedItems) {
                                Log.d(TAG, "Found species with count: ${item.speciesName} (${item.speciesId})")
                                onSpeciesCountListener?.invoke(item.speciesId, item.speciesName, item.count)
                            }
                        } else {
                            Log.d(TAG, "No valid species and count combinations found in: $bestMatch")
                        }
                    }
                }

                isListening = false
            }

            /**
             * Helper functie om een soort-ID te vinden op basis van een soortnaam
             * Probeert zowel exacte als case-insensitive matching
             */
            private fun findSpeciesId(speciesName: String): String? {
                // 1. Probeer directe match
                availableSpecies[speciesName]?.let { return it }

                // 2. Probeer case-insensitive match
                val lowerCaseName = speciesName.lowercase()
                availableSpecies.entries.find { it.key.equals(speciesName, ignoreCase = true) }?.let {
                    return it.value
                }

                // 3. Probeer met genormaliseerde naam
                val normalized = normalizeSpeciesName(speciesName)
                availableSpecies[normalized]?.let { return it }

                // Log alle beschikbare soorten voor debug
                Log.d(TAG, "Available species (first 10): ${availableSpecies.entries.take(10).map { it.key }}")

                return null
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
     * Parseert spraakresultaat volgens protocol "Soortnaam Aantal Soortnaam Aantal"
     * met fonetische matching voor betere herkenning
     */
    private fun parseSpeciesWithCounts(spokenText: String): List<SpeciesCount> {
        val result = mutableListOf<SpeciesCount>()
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
                    val repairedTokens = splitTokens.toMutableList()
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
     */
    /**
     * Zoekt een match voor de opgegeven tekst in de beschikbare soorten
     * met fonetische indexering voor betere resultaten
     */
    /**
     * Zoekt een match voor de opgegeven tekst in de beschikbare soorten
     * met fonetische indexering voor betere resultaten
     */
    /**
     * Zoekt een match voor de opgegeven tekst in de beschikbare soorten
     * met fonetische indexering voor betere resultaten
     */
    private fun findSpeciesMatch(text: String): Pair<String, String>? {
        if (text.isBlank()) return null

        val normalized = normalizeSpeciesName(text)

        Log.d(TAG, "Finding match for '$text' (normalized: '$normalized')")

        // Log de beschikbare soorten voor debug
        Log.d(TAG, "Available species keys: ${availableSpecies.keys.take(5)}...")

        // 1. Probeer exacte match met originele en genormaliseerde tekst
        availableSpecies[text]?.let { id ->
            Log.d(TAG, "Found exact match with original text: $text -> $id")
            return id to text
        }

        availableSpecies[normalized]?.let { id ->
            Log.d(TAG, "Found exact match with normalized text: $normalized -> $id")
            return id to normalized
        }

        // Extra: Probeer case-insensitive matching
        val lowerCaseText = text.lowercase()
        val lowerCaseSpecies = availableSpecies.entries
            .find { it.key.lowercase() == lowerCaseText }

        lowerCaseSpecies?.let {
            Log.d(TAG, "Found case-insensitive match: ${it.key} -> ${it.value}")
            return it.value to it.key
        }

        // 2. Fonetische kandidaten zoeken via de index
        val index = phoneticIndex
        if (index != null) {
            // Maak een set van alle beschikbare soortnamen
            val activeSpeciesNames = availableSpecies.keys.toSet()

            // Gebruik de juiste parameters: (query, activeSpeciesSet, maxResults)
            val candidates = index.findCandidates(normalized, activeSpeciesNames, 5)

            if (candidates.isNotEmpty()) {
                // Neem de hoogst scorende kandidaat
                val bestCandidate = candidates.first()
                Log.d(TAG, "Found phonetic match: ${bestCandidate.sourceName} -> ${bestCandidate.sourceId}")
                return bestCandidate.sourceId to bestCandidate.sourceName
            }
        }

        // 3. Fallback: zoek op basis van levenshtein afstand als fonetisch niets vond
        var bestMatch: Pair<String, String>? = null
        var bestScore = 0.0

        for ((name, id) in availableSpecies) {
            val similarity = calculateSimilarity(normalized, name)
            if (similarity > 0.85 && similarity > bestScore) {  // Threshold voor fuzzy matching
                bestScore = similarity
                bestMatch = id to name
            }
        }

        bestMatch?.let {
            Log.d(TAG, "Found Levenshtein match: ${it.second} -> ${it.first} (score: $bestScore)")
        }

        return bestMatch
    }    /**
     * Berekent similariteit tussen twee strings voor fuzzy matching
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        // Combineer Levenshtein afstand met fonetische gelijkenis
        val s1Norm = normalizeSpeciesName(s1)
        val s2Norm = normalizeSpeciesName(s2)

        val levDistance = levenshteinDistance(s1Norm, s2Norm)
        val maxLen = kotlin.math.max(s1Norm.length, s2Norm.length)
        val levSimilarity = 1.0 - (levDistance.toDouble() / maxLen.toDouble())

        val phoneticSimilarity = ColognePhonetic.similarity(s1Norm, s2Norm)

        // Combineer scores (70% fonetisch, 30% levenshtein)
        return (0.7 * phoneticSimilarity + 0.3 * levSimilarity).coerceIn(0.0, 1.0)
    }

    /**
     * Berekent Levenshtein afstand tussen twee strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
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

    private fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(text: String): List<String> {
        return text.split(" ").filter { it.isNotEmpty() }
    }

    private fun splitEmbeddedNumbers(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
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



    private fun normalizeSpeciesName(name: String): String {
        val normalized = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Meervoud naar enkelvoud omzetten
        val words = normalized.split(" ")
        val singularized = words.map { singularizeNl(it) }

        return singularized.joinToString(" ")
    }

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