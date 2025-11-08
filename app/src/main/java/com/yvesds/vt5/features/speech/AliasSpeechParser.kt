@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale

/**
 * AliasSpeechParser (UPDATED)
 *
 * - parseSpokenWithContext / parseSpokenWithHypotheses (N-best) as before.
 * - Fast-path exact lookup added: scans top N ASR hypotheses for normalized exact matches.
 * - Logging (writeMatchLog / writeLog) made robust and non-blocking via MatchLogWriter.
 * - Respects coroutine cancellation (ensureActive).
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
        private val json = Json { prettyPrint = true }
        private val FILTER_WORDS = setOf("luisteren", "luisteren...", "luister")
        private const val SAF_TAIL_LINES = 1000

        // Fast-path configuration (user choice: threshold=0.99, scan top 3)
        private const val FAST_ASR_CONF_THRESHOLD = 0.99  // site-accept threshold
        private const val FAST_N_HYPOTHESES = 3          // scan top N hypotheses for fast-path

        // Precompiled regexes (avoid reallocation)
        private val RE_TRAILING_NUMBER = Regex("""^(.*?)(?:\s+(\d+)(?:[.,]\d+)?)?$""")
    }

    // Background scope for non-blocking background work (SAF writes etc.)
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Ensure background writer started (idempotent)
        try {
            MatchLogWriter.start(context)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed starting MatchLogWriter: ${ex.message}", ex)
        }
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
            // Offload IO via background writer
            writeMatchLogNonBlocking(rawAsr, result, partials, asrHypotheses = null)

            return@withContext result
        } catch (ex: Exception) {
            Log.w(TAG, "parseSpokenWithContext failed: ${ex.message}", ex)
            writeMatchLogNonBlocking(rawAsr, MatchResult.NoMatch(rawAsr, "exception"), partials, asrHypotheses = null)
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

        // Configuration: only run heavy matcher for top K hypotheses and add per-hypothesis timeout.
        val HEAVY_HYP_COUNT = 3             // only run heavy matcher for top 3 hypotheses
        val PER_HYP_TIMEOUT_MS = 800L       // ms timeout per heavy match

        // Pre-normalize hypotheses: trim + lowercase once to avoid casing-driven slow paths
        val normalizedHyps = hypotheses.map { (text, conf) ->
            text.trim().lowercase(Locale.getDefault()) to conf
        }

        // FAST-PATH: try inexpensive exact lookups on top N hypotheses to provide near-instant accepts.
        for ((hyp, asrConfFloat) in normalizedHyps.take(FAST_N_HYPOTHESES)) {
            ensureActive()
            val raw = hyp
            if (raw.isEmpty()) continue

            // extract trailing integer if present (e.g., "aalscholver 3")
            val m = RE_TRAILING_NUMBER.find(raw)
            val nameOnly = m?.groups?.get(1)?.value?.trim().orEmpty()
            val extractedCount = m?.groups?.get(2)?.value?.toIntOrNull() ?: 0

            val norm = normalizeLowerNoDiacritics(nameOnly)
            if (norm.isBlank()) continue

            try {
                val tFind0 = System.currentTimeMillis()
                val records = AliasMatcher.findExact(norm, context, saf) // non-blocking read
                val tFind1 = System.currentTimeMillis()
                if (tFind1 - tFind0 > 100) {
                    Log.d(TAG, "findExact slow for '$norm' timeMs=${tFind1 - tFind0}")
                }

                if (records.isEmpty()) continue

                // reduce to unique species ids
                val speciesSet = records.map { it.speciesid }.toSet()
                var chosenSid: String? = null

                if (speciesSet.size == 1) {
                    chosenSid = speciesSet.first()
                } else {
                    // prefer any species that is currently in tiles
                    val inTiles = speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
                    if (inTiles != null) chosenSid = inTiles
                }

                if (chosenSid == null) {
                    // ambiguous, let heavy matcher handle it
                    continue
                }

                val amount = if (extractedCount > 0) extractedCount else 1
                val isInTiles = chosenSid in matchContext.tilesSpeciesIds
                val isSiteAllowed = chosenSid in matchContext.siteAllowedIds
                val asrConf = asrConfFloat.toDouble().coerceIn(0.0, 1.0)

                if (isInTiles || (isSiteAllowed && asrConf >= FAST_ASR_CONF_THRESHOLD)) {
                    val display = matchContext.speciesById[chosenSid]?.first ?: nameOnly
                    val candidate = Candidate(
                        speciesId = chosenSid,
                        displayName = display,
                        score = 1.0,
                        isInTiles = isInTiles,
                        source = if (isInTiles) "fast_tiles" else "fast_site"
                    )
                    val mr = MatchResult.AutoAccept(candidate, raw, "fastpath", amount)
                    // Log the fastpath accept with full ASR hypotheses for telemetry (non-blocking)
                    writeMatchLogNonBlocking(raw, mr, partials, asrHypotheses = hypotheses)
                    Log.d(TAG, "FASTPATH accept: species=$chosenSid source=${mr.source} amount=$amount hyp='$raw' asrConf=$asrConf")
                    return@withContext mr
                }
            } catch (ex: Exception) {
                Log.w(TAG, "FASTPATH lookup error for '$raw': ${ex.message}", ex)
                // on error, just continue to heavy path
            }
        }

        // No fast-path hit — proceed with heavy matching but only for top HEAVY_HYP_COUNT hypotheses.
        var bestCombined = Double.NEGATIVE_INFINITY
        var bestResult: MatchResult = MatchResult.NoMatch(hypotheses.first().first, "none")
        var idx = 0

        for ((hyp, asrConfFloat) in normalizedHyps.take(HEAVY_HYP_COUNT)) {
            ensureActive()
            val rawTrim = hyp
            val normalized = normalizeLowerNoDiacritics(rawTrim)
            if (normalized.isBlank()) {
                idx++; continue
            }

            try {
                // Per-hypothesis timeout to avoid one slow match blocking everything
                val maybeMr = withTimeoutOrNull(PER_HYP_TIMEOUT_MS) {
                    AliasPriorityMatcher.match(normalized, matchContext, context, saf)
                }

                if (maybeMr == null) {
                    Log.w(TAG, "AliasPriorityMatcher.match timed out for '$normalized' after ${PER_HYP_TIMEOUT_MS}ms")
                    idx++; continue
                }
                val mr = maybeMr

                // If matcher strongly auto-accepts, return immediately
                if (mr is MatchResult.AutoAccept || mr is MatchResult.MultiMatch) {
                    writeMatchLogNonBlocking(rawTrim, mr, partials, asrHypotheses = hypotheses)
                    return@withContext mr
                }

                val matcherScore = when (mr) {
                    is MatchResult.AutoAcceptAddPopup -> mr.candidate.score
                    is MatchResult.SuggestionList -> mr.candidates.firstOrNull()?.score ?: 0.0
                    is MatchResult.NoMatch -> 0.0
                    else -> 0.0
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

        // If we haven't found anything promising from top heavy hypotheses, consider a light pass over remaining hyps:
        if (bestCombined == Double.NEGATIVE_INFINITY) {
            for ((hyp, asrConfFloat) in normalizedHyps.drop(HEAVY_HYP_COUNT)) {
                ensureActive()
                // quick, cheap checks only: maybe fast exact or very cheap fuzzy check via AliasMatcher.findExact
                val quickNorm = normalizeLowerNoDiacritics(hyp)
                if (quickNorm.isBlank()) continue
                val recs = AliasMatcher.findExact(quickNorm, context, saf)
                if (recs.isNotEmpty()) {
                    // prefer single result or tile-presence
                    val speciesSet = recs.map { it.speciesid }.toSet()
                    val chosen = when {
                        speciesSet.size == 1 -> speciesSet.first()
                        else -> speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
                    }
                    if (chosen != null) {
                        val candidate = Candidate(speciesId = chosen, displayName = matchContext.speciesById[chosen]?.first ?: hyp, score = 0.9, isInTiles = chosen in matchContext.tilesSpeciesIds, source = "quick_exact")
                        val mr = MatchResult.AutoAccept(candidate, hyp, "quick_exact", 1)
                        writeMatchLogNonBlocking(hyp, mr, partials, asrHypotheses = hypotheses)
                        return@withContext mr
                    }
                }
            }
        }

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithHypotheses finished: bestHyp='${bestResult.hypothesis}' type=${bestResult::class.simpleName} timeMs=${t1 - t0}")

        // Log chosen result including ASR N-best hypotheses (non-blocking)
        val firstHypText = hypotheses.firstOrNull()?.first ?: ""
        writeMatchLogNonBlocking(firstHypText, bestResult, partials, asrHypotheses = hypotheses)

        return@withContext bestResult
    }

    /**
     * Non-blocking match log writer:
     * - Builds serializable entry (cheap)
     * - Enqueues to MatchLogWriter for reliable internal file write (background)
     * - Kicks off a best-effort SAF write on IO dispatcher without blocking the caller
     */
    private fun writeMatchLogNonBlocking(
        rawInput: String,
        result: MatchResult,
        partials: List<String>,
        asrHypotheses: List<Pair<String, Float>>? = null
    ) {
        try {
            // Build candidate log (cheap)
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
                else -> null
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

            // Build ASR hypotheses list (cheap map)
            val asrHyps = asrHypotheses?.map { (text, conf) -> AsrHypothesis(text, conf) }

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

            // 1) Enqueue internal background write (fast, non-blocking)
            MatchLogWriter.enqueueFireAndForget(context, logLine)

            // 2) Best-effort SAF export in background (do not block caller)
            bgScope.launch {
                try {
                    val date = Instant.now().toString().substring(0, 10).replace("-", "")
                    val filename = "match_log_$date.ndjson"

                    val vt5 = saf.getVt5DirIfExists()
                    if (vt5 == null) {
                        // No SAF root; nothing to do
                        return@launch
                    }

                    val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports")
                    ?: return@launch

                    val file = exports.findFile(filename)
                    if (file == null || !file.exists()) {
                        val newFile = exports.createFile("application/x-ndjson", filename) ?: return@launch
                        context.contentResolver.openOutputStream(newFile.uri, "w")?.use { os ->
                            os.write((logLine + "\n").toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                        Log.d(TAG, "Match log written to SAF: ${newFile.uri}")
                    } else {
                        // Try append
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
                            try {
                                // Fallback bounded rewrite
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
                                val recreated = exports.createFile("application/x-ndjson", filename) ?: return@launch
                                context.contentResolver.openOutputStream(recreated.uri, "w")?.use { os ->
                                    os.write(newContent.toByteArray(Charsets.UTF_8))
                                    os.flush()
                                }
                                Log.d(TAG, "Match log rewritten to SAF (fallback)")
                            } catch (ex: Exception) {
                                Log.w(TAG, "SAF match log fallback failed: ${ex.message}", ex)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Background SAF match log failed: ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "writeMatchLogNonBlocking failed building/enqueueing log: ${ex.message}", ex)
        }
    }

    /**
     * Parsing log (ParseResult) — non-blocking write via MatchLogWriter + best-effort SAF write
     */
    fun writeLogNonBlocking(result: ParseResult, partials: List<String> = emptyList()) {
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

            // internal write
            MatchLogWriter.enqueueFireAndForget(context, logLine)

            // best-effort SAF write in background
            bgScope.launch {
                try {
                    val date = Instant.now().toString().substring(0, 10).replace("-", "")
                    val filename = "parsing_log_$date.ndjson"
                    val vt5 = saf.getVt5DirIfExists() ?: return@launch
                    val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return@launch
                    var file = exports.findFile(filename)
                    if (file == null) {
                        file = exports.createFile("application/x-ndjson", filename) ?: return@launch
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
                                val recreated = exports.createFile("application/x-ndjson", filename) ?: return@launch
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
                    Log.e(TAG, "Failed background parsing log SAF write: ${ex.message}", ex)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write parse log (non-blocking): ${ex.message}", ex)
        }
    }
}