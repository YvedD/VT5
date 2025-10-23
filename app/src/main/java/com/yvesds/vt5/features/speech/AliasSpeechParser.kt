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
 * AliasSpeechParser
 *
 * Implements the protocol:
 *  - input is a free text ASR result containing one or more occurrences of "<soortnaam> [<aantal>]"
 *    in sequence (soort first, optional amount following)
 *  - default amount = 1
 *  - multiple pairs in a single utterance are supported (sliding window)
 *
 * Behavior:
 *  - fast path: exact match against alias strings (from aliases_flat.cbor.gz). Exact match is guaranteed.
 *  - fuzzy fallback: tries normalized Levenshtein match across aliases and accepts high-confidence candidates
 *
 * This implementation aims for correctness first; performance: index is loaded in memory and maps are used for O(1) exact lookup.
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
     * Public API: parse spoken raw ASR string and return ParseResult.
     * Optional parameter partials (ASR intermediate partials) can be provided; if omitted parser will log an empty partials list.
     * This function is suspend and performs IO (loads index) on Dispatchers.IO.
     */
    suspend fun parseSpoken(rawAsr: String, partials: List<String> = emptyList()): ParseResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // Filter out "Luisteren..." system prompt if ASR relays it as a partial/final
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = normalizeLowerNoDiacritics(rawTrim)
        if (rawLowerNoPunct.isBlank()) {
            val resEmpty = ParseResult(success = false, rawInput = rawAsr, items = emptyList(), message = "Empty input")
            writeLog(resEmpty, partials)
            return@withContext resEmpty
        }
        // If the raw (after normalization) equals any of the filter words, do not log it; return empty parse
        if (FILTER_WORDS.contains(rawLowerNoPunct)) {
            val resFiltered = ParseResult(success = false, rawInput = rawAsr, items = emptyList(), message = "Filtered system prompt")
            // Do not add to user-visible logs; still write a minimal line for diagnostics? We skip writing to exports here.
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

        // ensure alias index loaded
        AliasMatcher.ensureLoaded(context, saf)

        val items = mutableListOf<ParsedItem>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            // try longest windows first (max 6 tokens for species names)
            val maxWindow = minOf(6, tokens.size - i)
            var chosenWindowEnd = -1
            var chosenRecords: List<com.yvesds.vt5.features.alias.AliasRecord> = emptyList()
            var chosenPhrase = ""
            for (w in maxWindow downTo 1) {
                val phrase = tokens.subList(i, i + w).joinToString(" ")
                // try exact lookup
                val exact = AliasMatcher.findExact(phrase, context, saf)
                if (exact.isNotEmpty()) {
                    chosenRecords = exact
                    chosenWindowEnd = i + w - 1
                    chosenPhrase = phrase
                    break
                }
            }

            if (chosenWindowEnd >= 0) {
                // found exact match
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

                // pick best record by weight (if multiple)
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
                // no exact found: try fuzzy on windows (prefer longer windows)
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
                    // no match for any window beginning at i: skip token i
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
     * Write NDJSON parse log. Accepts optional partials list (ordered, earliest-first).
     * The file will be Documents/VT5/exports/parsing_log_<YYYYMMDD>.ndjson.
     */
    suspend fun writeLog(result: ParseResult, partials: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        try {
            val entry = ParseLogEntry(
                timestampIso = Instant.now().toString(),
                rawInput = result.rawInput,
                parseResult = result,
                partials = partials.filter { p ->
                    // filter system prompts like "Luisteren..."
                    val n = normalizeLowerNoDiacritics(p)
                    !FILTER_WORDS.contains(n)
                }
            )
            val logLine = json.encodeToString(entry)
            // write to Documents/VT5/exports/parsing_log_<date>.ndjson (append)
            val date = Instant.now().toString().substring(0, 10).replace("-", "")
            val filename = "parsing_log_$date.ndjson"
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext
            val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return@withContext
            // find or create file
            var file = exports.findFile(filename)
            if (file == null) {
                file = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                // write initial line
                context.contentResolver.openOutputStream(file.uri, "w")?.use { os ->
                    os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } else {
                // append: SAF lacks append. For small logs we rewrite whole file.
                val existing = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                val newContent = existing + logLine + "\n"
                // overwrite
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