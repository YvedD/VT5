package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale

/**
 * AliasSpeechParser (UPDATED voor AliasPriorityMatcher integratie)
 *
 * Implements the protocol:
 *  - input is a free text ASR result containing one or more occurrences of "<soortnaam> [<aantal>]"
 *  - default amount = 1
 *  - multiple pairs in a single utterance are supported (sliding window)
 *
 * NEW:
 *  - Uses AliasPriorityMatcher.match() for 9-step priority cascade
 *  - Accepts MatchContext parameter with tiles/site/recents
 *  - Returns MatchResult instead of ParseResult
 */

class AliasSpeechParser(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "AliasSpeechParser"
        private val json = Json { prettyPrint = false }
        private val FILTER_WORDS = setOf("luisteren", "luisteren...", "luister") // lowercased forms to filter
    }

    // normalization function matching PrecomputeAliasIndex.normalizeLowerNoDiacritics
    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    private fun parseIntToken(token: String): Int? {
        return token.toIntOrNull()
    }

    /**
     * Public API: parse spoken raw ASR string with full context and return MatchResult.
     * NEW: Accepts MatchContext parameter for priority cascade matching.
     */
    suspend fun parseSpokenWithContext(
        rawAsr: String,
        matchContext: MatchContext,
        partials: List<String> = emptyList()
    ): MatchResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // Filter out "Luisteren..." system prompt
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = normalizeLowerNoDiacritics(rawTrim)
        if (rawLowerNoPunct.isBlank() || FILTER_WORDS.contains(rawLowerNoPunct)) {
            Log.d(TAG, "parseSpokenWithContext ignored system prompt: '$rawAsr'")
            return@withContext MatchResult.NoMatch(rawAsr)
        }

        val normalizedInput = rawLowerNoPunct
        val tokens = normalizedInput.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return@withContext MatchResult.NoMatch(rawAsr)
        }

        // For simplicity: match entire hypothesis as single phrase (no sliding window for now)
        // Future enhancement: sliding window with multi-species detection
        val result = AliasPriorityMatcher.match(normalizedInput, matchContext, context, saf)

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithContext finished: input='${rawAsr}' result=${result::class.simpleName} timeMs=${t1 - t0}")

        // Write NDJSON log
        writeMatchLog(rawAsr, result, partials)

        return@withContext result
    }

    /**
     * DEPRECATED: Old parseSpoken() method for backward compatibility.
     * Use parseSpokenWithContext() instead.
     */
    @Deprecated("Use parseSpokenWithContext() with MatchContext", ReplaceWith("parseSpokenWithContext(rawAsr, matchContext, partials)"))
    suspend fun parseSpoken(rawAsr: String, partials: List<String> = emptyList()): ParseResult = withContext(Dispatchers.IO) {
        // Fallback to old behavior (no context, use AliasMatcher directly)
        val t0 = System.currentTimeMillis()
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = normalizeLowerNoDiacritics(rawTrim)
        if (rawLowerNoPunct.isBlank() || FILTER_WORDS.contains(rawLowerNoPunct)) {
            val resFiltered = ParseResult(success = false, rawInput = rawAsr, items = emptyList(), message = "Filtered system prompt")
            Log.d(TAG, "parseSpoken ignored system prompt: '$rawAsr'")
            return@withContext resFiltered
        }

        val normalizedInput = rawLowerNoPunct
        val tokens = normalizedInput.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            val res = ParseResult(success = false, rawInput = rawAsr, items = emptyList(), message = "Empty input after normalization")
            writeLog(res, partials)
            return@withContext res
        }

        // Ensure alias index loaded
        AliasMatcher.ensureLoaded(context, saf)

        val items = mutableListOf<ParsedItem>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            val maxWindow = minOf(6, tokens.size - i)
            var chosenWindowEnd = -1
            var chosenRecords: List<com.yvesds.vt5.features.alias.AliasRecord> = emptyList()
            var chosenPhrase = ""
            for (w in maxWindow downTo 1) {
                val phrase = tokens.subList(i, i + w).joinToString(" ")
                val exact = AliasMatcher.findExact(phrase, context, saf)
                if (exact.isNotEmpty()) {
                    chosenRecords = exact
                    chosenWindowEnd = i + w - 1
                    chosenPhrase = phrase
                    break
                }
            }

            if (chosenWindowEnd >= 0) {
                val nextIndex = chosenWindowEnd + 1
                var amount = 1
                if (nextIndex < tokens.size) {
                    val maybeNum = parseIntToken(tokens[nextIndex])
                    if (maybeNum != null) {
                        amount = maybeNum
                        i = nextIndex + 1
                    } else {
                        i = nextIndex
                    }
                } else {
                    i = nextIndex
                }

                val record = chosenRecords.maxByOrNull { it.weight }!!
                val candidate = CandidateScore(record.aliasid, record.speciesid, record.alias, 1.0, "exact")
                val parsed = ParsedItem(
                    rawPhrase = chosenPhrase,
                    normalized = normalizeLowerNoDiacritics(chosenPhrase),
                    amount = amount,
                    chosenAliasId = record.aliasid,
                    chosenSpeciesId = record.speciesid,
                    chosenAliasText = record.alias,
                    score = 1.0,
                    candidates = listOf(candidate)
                )
                items += parsed
                matched = true
            } else {
                var fuzzyFound: Pair<com.yvesds.vt5.features.alias.AliasRecord, Double>? = null
                var fuzzyWindowPhrase: String? = null
                for (w in maxWindow downTo 1) {
                    val phrase = tokens.subList(i, i + w).joinToString(" ")
                    val candidates = AliasMatcher.findFuzzyCandidates(phrase, context, saf, topN = 5, threshold = 0.6)
                    if (candidates.isNotEmpty()) {
                        fuzzyFound = candidates.first()
                        fuzzyWindowPhrase = phrase
                        chosenWindowEnd = i + w - 1
                        break
                    }
                }
                if (fuzzyFound != null && fuzzyWindowPhrase != null) {
                    val (record, score) = fuzzyFound
                    val nextIndex = chosenWindowEnd + 1
                    var amount = 1
                    if (nextIndex < tokens.size) {
                        val maybeNum = parseIntToken(tokens[nextIndex])
                        if (maybeNum != null) {
                            amount = maybeNum
                            i = nextIndex + 1
                        } else {
                            i = nextIndex
                        }
                    } else {
                        i = nextIndex
                    }
                    val candidatesList = AliasMatcher.findFuzzyCandidates(fuzzyWindowPhrase, context, saf, topN = 5, threshold = 0.0)
                        .map { (r, s) -> CandidateScore(r.aliasid, r.speciesid, r.alias, s, "fuzzy") }

                    val parsed = ParsedItem(
                        rawPhrase = fuzzyWindowPhrase,
                        normalized = normalizeLowerNoDiacritics(fuzzyWindowPhrase),
                        amount = amount,
                        chosenAliasId = record.aliasid,
                        chosenSpeciesId = record.speciesid,
                        chosenAliasText = record.alias,
                        score = score,
                        candidates = candidatesList
                    )
                    items += parsed
                    matched = true
                } else {
                    i += 1
                }
            }
        }

        val success = items.isNotEmpty()
        val result = ParseResult(success = success, rawInput = rawAsr, items = items, message = if (success) null else "No matches")
        writeLog(result, partials)
        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpoken finished: input='${rawAsr}' items=${items.size} timeMs=${t1 - t0}")
        return@withContext result
    }

    /**
     * Write NDJSON match log for MatchResult.
     */
    private suspend fun writeMatchLog(rawInput: String, result: MatchResult, partials: List<String>) = withContext(Dispatchers.IO) {
        try {
            val entry = mapOf(
                "timestampIso" to Instant.now().toString(),
                "rawInput" to rawInput,
                "resultType" to result::class.simpleName,
                "hypothesis" to result.hypothesis,
                "candidate" to when (result) {
                    is MatchResult.AutoAccept -> mapOf(
                        "speciesId" to result.candidate.speciesId,
                        "displayName" to result.candidate.displayName,
                        "score" to result.candidate.score,
                        "source" to result.source
                    )
                    is MatchResult.AutoAcceptAddPopup -> mapOf(
                        "speciesId" to result.candidate.speciesId,
                        "displayName" to result.candidate.displayName,
                        "score" to result.candidate.score,
                        "source" to result.source
                    )
                    is MatchResult.SuggestionList -> mapOf(
                        "candidatesCount" to result.candidates.size,
                        "topScore" to (result.candidates.firstOrNull()?.score ?: 0.0),
                        "source" to result.source
                    )
                    is MatchResult.NoMatch -> null
                },
                "partials" to partials.filter { p ->
                    val n = normalizeLowerNoDiacritics(p)
                    !FILTER_WORDS.contains(n)
                }
            )
            val logLine = json.encodeToString(entry)
            val date = Instant.now().toString().substring(0, 10).replace("-", "")
            val filename = "match_log_$date.ndjson"
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext
            val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return@withContext
            var file = exports.findFile(filename)
            if (file == null) {
                file = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                context.contentResolver.openOutputStream(file.uri, "w")?.use { os ->
                    os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } else {
                val existing = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                val newContent = existing + logLine + "\n"
                exports.findFile(filename)?.delete()
                val created = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                context.contentResolver.openOutputStream(created.uri, "w")?.use { os ->
                    os.write(newContent.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to write match log: ${ex.message}", ex)
        }
    }

    /**
     * Write NDJSON parse log (old format for backward compatibility).
     */
    suspend fun writeLog(result: ParseResult, partials: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        try {
            val entry = ParseLogEntry(
                timestampIso = Instant.now().toString(),
                rawInput = result.rawInput,
                parseResult = result,
                partials = partials.filter { p ->
                    val n = normalizeLowerNoDiacritics(p)
                    !FILTER_WORDS.contains(n)
                }
            )
            val logLine = json.encodeToString(entry)
            val date = Instant.now().toString().substring(0, 10).replace("-", "")
            val filename = "parsing_log_$date.ndjson"
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext
            val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return@withContext
            var file = exports.findFile(filename)
            if (file == null) {
                file = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                context.contentResolver.openOutputStream(file.uri, "w")?.use { os ->
                    os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } else {
                val existing = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                val newContent = existing + logLine + "\n"
                exports.findFile(filename)?.delete()
                val created = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                context.contentResolver.openOutputStream(created.uri, "w")?.use { os ->
                    os.write(newContent.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to write parse log: ${ex.message}", ex)
        }
    }
}