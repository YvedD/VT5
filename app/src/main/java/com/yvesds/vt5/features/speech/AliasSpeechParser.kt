@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale

/**
 * AliasSpeechParser (UPDATED)
 *
 * - parseSpokenWithContext / parseSpokenWithHypotheses (N-best) as before.
 * - Logging (writeMatchLog / writeLog) made more robust and non-blocking.
 * - SAF append fallback rewritten to stream-only tail lines (no full-file in-memory copy).
 * - Respects coroutine cancellation (ensureActive) so long-running logging can be cancelled.
 *
 * Note: relies on external model/data classes (MatchResult, ParseResult, ParseLogEntry, Candidate, etc.)
 * which must be defined elsewhere in the project as before.
 */

class AliasSpeechParser(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "AliasSpeechParser"
        // Pretty print enabled for readability
        private val json = Json { prettyPrint = true }
        private val FILTER_WORDS = setOf("luisteren", "luisteren...", "luister")
        private const val SAF_TAIL_LINES = 1000
    }

    // Serializable data classes for logging (fixes kotlinx.serialization crash)
    @Serializable
    data class MatchLogEntry(
        val timestampIso: String,
        val rawInput: String,
        val resultType: String,
        val hypothesis: String,
        val candidate: CandidateLog? = null,
        val multiMatches: List<MultiMatchLog>? = null,
        val partials: List<String> = emptyList(),
        val asr_hypotheses: List<AsrHypothesis>? = null
    )

    @Serializable
    data class CandidateLog(
        val speciesId: String? = null,
        val displayName: String? = null,
        val score: Double? = null,
        val source: String? = null,
        val amount: Int? = null,
        val candidatesCount: Int? = null,
        val topScore: Double? = null
    )

    @Serializable
    data class MultiMatchLog(
        val speciesId: String,
        val displayName: String,
        val amount: Int,
        val score: Double,
        val source: String
    )

    @Serializable
    data class AsrHypothesis(
        val text: String,
        val confidence: Float
    )

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

    suspend fun parseSpokenWithContext(
        rawAsr: String,
        matchContext: MatchContext,
        partials: List<String> = emptyList()
    ): MatchResult = withContext(Dispatchers.IO) {
        ensureActive()
        val t0 = System.currentTimeMillis()

        // Filter out "Luisteren..." system prompt
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = normalizeLowerNoDiacritics(rawTrim)
        if (rawLowerNoPunct.isBlank() || FILTER_WORDS.contains(rawLowerNoPunct)) {
            Log.d(TAG, "parseSpokenWithContext ignored system prompt: '$rawAsr'")
            return@withContext MatchResult.NoMatch(rawAsr, "filtered-prompt")
        }

        try {
            val result = AliasPriorityMatcher.match(rawLowerNoPunct, matchContext, context, saf)
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "parseSpokenWithContext finished: input='${rawAsr}' result=${result::class.simpleName} timeMs=${t1 - t0}")

            // Write NDJSON log (no ASR hypotheses provided for single-hypothesis calls)
            writeMatchLog(rawAsr, result, partials, asrHypotheses = null)

            return@withContext result
        } catch (ex: Exception) {
            Log.w(TAG, "parseSpokenWithContext failed: ${ex.message}", ex)
            writeMatchLog(rawAsr, MatchResult.NoMatch(rawAsr, "exception"), partials, asrHypotheses = null)
            return@withContext MatchResult.NoMatch(rawAsr, "exception")
        }
    }

    suspend fun parseSpokenWithHypotheses(
        hypotheses: List<Pair<String, Float>>,
        matchContext: MatchContext,
        partials: List<String> = emptyList(),
        asrWeight: Double = 0.4 // weight of ASR confidence vs matcher score
    ): MatchResult = withContext(Dispatchers.IO) {
        ensureActive()
        val t0 = System.currentTimeMillis()
        if (hypotheses.isEmpty()) return@withContext MatchResult.NoMatch("", "empty-hypotheses")

        var bestCombined = Double.NEGATIVE_INFINITY
        var bestResult: MatchResult = MatchResult.NoMatch(hypotheses.first().first, "none")
        var idx = 0

        for ((hyp, asrConfFloat) in hypotheses) {
            ensureActive()
            val rawTrim = hyp.trim()
            val normalized = normalizeLowerNoDiacritics(rawTrim)
            if (normalized.isBlank()) {
                idx++; continue
            }

            try {
                val mr = AliasPriorityMatcher.match(normalized, matchContext, context, saf)

                // If matcher strongly auto-accepts, return immediately
                if (mr is MatchResult.AutoAccept || mr is MatchResult.MultiMatch) {
                    // Log immediately with full ASR hypotheses
                    writeMatchLog(rawTrim, mr, partials, asrHypotheses = hypotheses)
                    return@withContext mr
                }

                // Extract matcherScore: prefer candidate.score when available
                val matcherScore = when (mr) {
                    is MatchResult.AutoAccept -> mr.candidate.score
                    is MatchResult.AutoAcceptAddPopup -> mr.candidate.score
                    is MatchResult.SuggestionList -> mr.candidates.firstOrNull()?.score ?: 0.0
                    is MatchResult.MultiMatch -> {
                        if (mr.matches.isNotEmpty()) mr.matches.map { it.candidate.score }.average() else 0.0
                    }
                    is MatchResult.NoMatch -> 0.0
                }

                val asrConf = asrConfFloat.toDouble().coerceIn(0.0, 1.0)
                val combined = asrWeight * asrConf + (1.0 - asrWeight) * matcherScore

                if (combined > bestCombined) {
                    bestCombined = combined
                    bestResult = mr
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Error matching hypothesis #$idx '${hyp}': ${ex.message}", ex)
            }

            idx++
        }

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithHypotheses finished: bestHyp='${bestResult.hypothesis}' type=${bestResult::class.simpleName} timeMs=${t1 - t0}")

        // Log chosen result including ASR N-best hypotheses
        writeMatchLog(hypotheses.firstOrNull()?.first ?: "", bestResult, partials, asrHypotheses = hypotheses)

        return@withContext bestResult
    }

    @Deprecated("Use parseSpokenWithContext() with MatchContext", ReplaceWith("parseSpokenWithContext(rawAsr, matchContext, partials)"))
    suspend fun parseSpoken(rawAsr: String, partials: List<String> = emptyList()): ParseResult = withContext(Dispatchers.IO) {
        ensureActive()
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
            ensureActive()
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
            } else {
                var fuzzyFound: Pair<com.yvesds.vt5.features.alias.AliasRecord, Double>? = null
                var fuzzyWindowPhrase: String? = null
                for (w in maxWindow downTo 1) {
                    ensureActive()
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

    private suspend fun writeMatchLog(
        rawInput: String,
        result: MatchResult,
        partials: List<String>,
        asrHypotheses: List<Pair<String, Float>>? = null
    ) = withContext(Dispatchers.IO) {
        ensureActive()
        try {
            // Build candidate log
            val candidateLog = when (result) {
                is MatchResult.AutoAccept -> CandidateLog(
                    speciesId = result.candidate.speciesId,
                    displayName = result.candidate.displayName,
                    score = result.candidate.score,
                    source = result.source,
                    amount = result.amount
                )
                is MatchResult.AutoAcceptAddPopup -> CandidateLog(
                    speciesId = result.candidate.speciesId,
                    displayName = result.candidate.displayName,
                    score = result.candidate.score,
                    source = result.source,
                    amount = result.amount
                )
                is MatchResult.SuggestionList -> CandidateLog(
                    candidatesCount = result.candidates.size,
                    topScore = result.candidates.firstOrNull()?.score,
                    source = result.source
                )
                is MatchResult.MultiMatch -> null  // Use multiMatches field instead
                is MatchResult.NoMatch -> null
            }

            // Build multi-match log (if applicable)
            val multiMatchesLog = if (result is MatchResult.MultiMatch) {
                result.matches.map { match ->
                    MultiMatchLog(
                        speciesId = match.candidate.speciesId,
                        displayName = match.candidate.displayName,
                        amount = match.amount,
                        score = match.candidate.score,
                        source = match.source
                    )
                }
            } else null

            // Build ASR hypotheses list
            val asrHyps = asrHypotheses?.map { (text, conf) -> AsrHypothesis(text, conf) }

            // Build entry (now fully serializable)
            val entry = MatchLogEntry(
                timestampIso = Instant.now().toString(),
                rawInput = rawInput,
                resultType = result::class.simpleName ?: "Unknown",
                hypothesis = result.hypothesis,
                candidate = candidateLog,
                multiMatches = multiMatchesLog,
                partials = partials.filter { p ->
                    val n = normalizeLowerNoDiacritics(p)
                    !FILTER_WORDS.contains(n)
                },
                asr_hypotheses = asrHyps
            )

            val logLine = json.encodeToString(entry)
            val date = Instant.now().toString().substring(0, 10).replace("-", "")
            val filename = "match_log_$date.ndjson"

            // ========== 1. INTERNAL STORAGE (reliable, real-time) ==========
            var internalSuccess = false
            try {
                val internalDir = java.io.File(context.filesDir, "match_logs")
                if (!internalDir.exists()) internalDir.mkdirs()
                val internalFile = java.io.File(internalDir, filename)

                java.io.FileWriter(internalFile, true).use { writer ->
                    writer.write(logLine + "\n")
                    writer.flush()
                }
                internalSuccess = true
                Log.d(TAG, "Match log written to internal: ${internalFile.absolutePath}")
            } catch (ex: Exception) {
                Log.e(TAG, "CRITICAL: Internal match log failed: ${ex.message}", ex)
            }

            // ========== 2. SAF STORAGE (export, best-effort) ==========
            try {
                val vt5 = saf.getVt5DirIfExists()
                if (vt5 == null) {
                    Log.w(TAG, "SAF match log skipped: VT5 dir not set")
                    return@withContext
                }

                val exports = vt5.findFile("exports")?.takeIf { it.isDirectory }
                    ?: vt5.createDirectory("exports")
                if (exports == null) {
                    Log.w(TAG, "SAF match log skipped: exports dir creation failed")
                    return@withContext
                }

                val file = exports.findFile(filename)
                if (file == null || !file.exists()) {
                    val newFile = exports.createFile("application/x-ndjson", filename)
                    if (newFile == null) {
                        Log.w(TAG, "SAF match log: file creation failed")
                        return@withContext
                    }
                    context.contentResolver.openOutputStream(newFile.uri, "w")?.use { os ->
                        os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    Log.d(TAG, "Match log written to SAF: ${newFile.uri}")
                } else {
                    // Try append mode first
                    var appended = false
                    try {
                        context.contentResolver.openOutputStream(file.uri, "wa")?.use { os ->
                            os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                            os.flush()
                            appended = true
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "SAF append mode failed: ${ex.message}")
                    }

                    if (!appended) {
                        // Fallback: stream last SAF_TAIL_LINES lines only (memory bounded)
                        try {
                            val tailDeque = ArrayDeque<String>(SAF_TAIL_LINES)
                            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                                for (ln in lines) {
                                    tailDeque.add(ln)
                                    if (tailDeque.size > SAF_TAIL_LINES) tailDeque.removeFirst()
                                }
                            }
                            val prefix = if (tailDeque.isNotEmpty()) tailDeque.joinToString("\n") + "\n" else ""
                            val newContent = prefix + logLine + "\n"

                            // Rewrite file
                            exports.findFile(filename)?.delete()
                            val recreated = exports.createFile("application/x-ndjson", filename)
                            if (recreated != null) {
                                context.contentResolver.openOutputStream(recreated.uri, "w")?.use { os ->
                                    os.write(newContent.toByteArray(Charsets.UTF_8))
                                    os.flush()
                                }
                                Log.d(TAG, "Match log rewritten to SAF (fallback)")
                            } else {
                                Log.w(TAG, "SAF match log fallback recreate failed")
                            }
                        } catch (ex: Exception) {
                            Log.w(TAG, "SAF match log fallback failed: ${ex.message}", ex)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "SAF match log failed (non-critical): ${ex.message}", ex)
            }

            // ========== 3. SUCCESS CHECK ==========
            if (!internalSuccess) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "⚠️ ASR logging FAILED — check permissions", Toast.LENGTH_LONG).show()
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "writeMatchLog CRITICAL FAILURE: ${ex.message}", ex)
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "ASR log fatal error: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) { /* swallow UI errors */ }
        }
    }

    suspend fun writeLog(result: ParseResult, partials: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        ensureActive()
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
                var appended = false
                try {
                    context.contentResolver.openOutputStream(file.uri, "wa")?.use { os ->
                        os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                        os.flush()
                        appended = true
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "SAF append for parsing_log failed: ${ex.message}")
                }
                if (!appended) {
                    // fallback bounded rewrite
                    try {
                        val tailDeque = ArrayDeque<String>(SAF_TAIL_LINES)
                        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                            for (ln in lines) {
                                tailDeque.add(ln)
                                if (tailDeque.size > SAF_TAIL_LINES) tailDeque.removeFirst()
                            }
                        }
                        val prefix = if (tailDeque.isNotEmpty()) tailDeque.joinToString("\n") + "\n" else ""
                        val newContent = prefix + logLine + "\n"

                        exports.findFile(filename)?.delete()
                        val recreated = exports.createFile("application/x-ndjson", filename) ?: return@withContext
                        context.contentResolver.openOutputStream(recreated.uri, "w")?.use { os ->
                            os.write(newContent.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "SAF rewrite for parsing_log failed: ${ex.message}", ex)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write parse log: ${ex.message}", ex)
        }
    }
}