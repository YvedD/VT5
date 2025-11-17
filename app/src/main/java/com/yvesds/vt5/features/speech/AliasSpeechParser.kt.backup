@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.utils.TextUtils
import com.yvesds.vt5.utils.RingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * AliasSpeechParser (pending buffer + central TextUtils)
 *
 * - Uses TextUtils.normalizeLowerNoDiacritics / parseTrailingInteger for consistent behavior.
 * - Keeps a bounded pending buffer for heavy scoring and a background worker to process it.
 * - Avoids leaking private internal types on the public listener API.
 *
 * This is the same behaviour as before but with normalization centralized and fewer duplicated helpers.
 */
class AliasSpeechParser(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "AliasSpeechParser"
        private val json = Json { prettyPrint = true }

        // Fast-path configuration
        private const val FAST_ASR_CONF_THRESHOLD = 0.99
        private const val FAST_N_HYPOTHESES = 3
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

    // Serializable data classes for logging
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

    // ---------------------------
    // Pending buffer + worker (internal types)
    // ---------------------------
    private data class PendingAsr(
        val id: String,
        val text: String,
        val confidence: Float,
        val matchContext: MatchContext,
        val partials: List<String>,
        val attempts: Int = 0,
        val timestampMs: Long = System.currentTimeMillis()
    )

    // Per-parser pending buffer (bounded). Capacity adjustable; default 8.
    private val pendingBuffer = RingBuffer<PendingAsr>(capacity = 8, overwriteOldest = true)

    @Volatile
    private var pendingWorkerStarted = false

    // Public listener uses simple signature to avoid leaking internal PendingAsr type.
    private var pendingResultListener: ((id: String, result: MatchResult) -> Unit)? = null

    /**
     * Register a listener to receive results for pending items.
     * Listener receives the pending item id and the MatchResult.
     */
    fun setPendingResultListener(listener: (id: String, result: MatchResult) -> Unit) {
        pendingResultListener = listener
    }

    private fun onPendingMatchResult(item: PendingAsr, result: MatchResult) {
        try {
            pendingResultListener?.invoke(item.id, result)
        } catch (ex: Exception) {
            Log.w(TAG, "onPendingMatchResult listener failed: ${ex.message}", ex)
        }
    }

    private fun ensurePendingWorkerRunning() {
        if (pendingWorkerStarted) return
        pendingWorkerStarted = true
        bgScope.launch {
            try {
                while (true) {
                    ensureActive()
                    val item = pendingBuffer.poll()
                    if (item == null) {
                        delay(50L)
                        continue
                    }

                    try {
                        val perItemTimeoutMs = 1200L
                        val normalizedText = TextUtils.normalizeLowerNoDiacritics(item.text)
                        val maybeResult = withTimeoutOrNull(perItemTimeoutMs) {
                            AliasPriorityMatcher.match(normalizedText, item.matchContext, context, saf)
                        }

                        if (maybeResult == null) {
                            Log.w(TAG, "Pending heavy match timed out for id=${item.id} text='${item.text}'")
                            if (item.attempts < 1) {
                                val retryItem = item.copy(attempts = item.attempts + 1)
                                pendingBuffer.add(retryItem)
                            } else {
                                writeMatchLogNonBlocking(item.text, MatchResult.NoMatch(item.text, "pending_timed_out"), item.partials, asrHypotheses = listOf(item.text to item.confidence))
                            }
                            continue
                        }

                        val result = maybeResult

                        writeMatchLogNonBlocking(item.text, result, item.partials, asrHypotheses = listOf(item.text to item.confidence))
                        onPendingMatchResult(item, result)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Log.w(TAG, "Error processing pending item id=${item.id}: ${ex.message}", ex)
                    }
                }
            } catch (ex: CancellationException) {
                Log.i(TAG, "Pending worker cancelled")
            } catch (ex: Exception) {
                Log.w(TAG, "Pending worker failed: ${ex.message}", ex)
            } finally {
                pendingWorkerStarted = false
            }
        }
    }

    // ---------------------------
    // End pending buffer + worker
    // ---------------------------

    suspend fun parseSpokenWithContext(
        rawAsr: String,
        matchContext: MatchContext,
        partials: List<String> = emptyList()
    ): MatchResult = withContext(Dispatchers.Default) {
        ensureActive()
        val t0 = System.currentTimeMillis()

        // Filter out "Luisteren..." system prompt
        val rawTrim = rawAsr.trim()
        val rawLowerNoPunct = TextUtils.normalizeLowerNoDiacritics(rawTrim)
        if (rawLowerNoPunct.isBlank() || TextUtils.isFilterWord(rawLowerNoPunct)) {
            Log.d(TAG, "parseSpokenWithContext ignored system prompt: '$rawAsr'")
            return@withContext MatchResult.NoMatch(rawAsr, "filtered-prompt")
        }

        try {
            val result = AliasPriorityMatcher.match(rawLowerNoPunct, matchContext, context, saf)
            val t1 = System.currentTimeMillis()
            Log.i(TAG, "parseSpokenWithContext finished: input='${rawAsr}' result=${result::class.simpleName} timeMs=${t1 - t0}")

            val filteredPartials = partials.filter { p ->
                val n = TextUtils.normalizeLowerNoDiacritics(p)
                !TextUtils.FILTER_WORDS.contains(n)
            }

            writeMatchLogNonBlocking(rawAsr, result, filteredPartials, asrHypotheses = null)
            return@withContext result
        } catch (ex: Exception) {
            Log.w(TAG, "parseSpokenWithContext failed: ${ex.message}", ex)
            val filteredPartials = partials.filter { p ->
                val n = TextUtils.normalizeLowerNoDiacritics(p)
                !TextUtils.FILTER_WORDS.contains(n)
            }
            writeMatchLogNonBlocking(rawAsr, MatchResult.NoMatch(rawAsr, "exception"), filteredPartials, asrHypotheses = null)
            return@withContext MatchResult.NoMatch(rawAsr, "exception")
        }
    }

    suspend fun parseSpokenWithHypotheses(
        hypotheses: List<Pair<String, Float>>,
        matchContext: MatchContext,
        partials: List<String> = emptyList(),
        asrWeight: Double = 0.4
    ): MatchResult = withContext(Dispatchers.Default) {
        ensureActive()
        val t0 = System.currentTimeMillis()
        if (hypotheses.isEmpty()) return@withContext MatchResult.NoMatch("", "empty-hypotheses")

        val HEAVY_HYP_COUNT = 3
        val INLINE_HEAVY_TIMEOUT_MS = 300L

        val normalizedHyps = hypotheses.map { (text, conf) ->
            text.trim().lowercase(Locale.getDefault()) to conf
        }

        val filteredPartials = partials.filter { p ->
            val n = TextUtils.normalizeLowerNoDiacritics(p)
            !TextUtils.FILTER_WORDS.contains(n)
        }

        // FAST-PATH
        for ((hyp, asrConfFloat) in normalizedHyps.take(FAST_N_HYPOTHESES)) {
            ensureActive()
            val raw = hyp
            if (raw.isEmpty()) continue

            val (nameOnly, extractedCount) = TextUtils.parseTrailingInteger(raw)
            val norm = TextUtils.normalizeLowerNoDiacritics(nameOnly)
            if (norm.isBlank()) continue

            try {
                val tFind0 = System.currentTimeMillis()
                val records = AliasMatcher.findExact(norm, context, saf)
                val tFind1 = System.currentTimeMillis()
                if (tFind1 - tFind0 > 100) {
                    Log.d(TAG, "findExact slow for '$norm' timeMs=${tFind1 - tFind0}")
                }

                if (records.isEmpty()) continue

                val speciesSet = records.map { it.speciesid }.toSet()
                var chosenSid: String? = null

                if (speciesSet.size == 1) {
                    chosenSid = speciesSet.first()
                } else {
                    val inTiles = speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
                    if (inTiles != null) chosenSid = inTiles
                }

                if (chosenSid == null) {
                    continue
                }

                val amount = extractedCount ?: 1
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
                    writeMatchLogNonBlocking(raw, mr, filteredPartials, asrHypotheses = hypotheses)
                    Log.d(TAG, "FASTPATH accept: species=$chosenSid source=${mr.source} amount=$amount hyp='$raw' asrConf=$asrConf")
                    return@withContext mr
                }
            } catch (ex: Exception) {
                Log.w(TAG, "FASTPATH lookup error for '$raw': ${ex.message}", ex)
            }
        }

        // Heavy path (non-blocking preference)
        var bestCombined = Double.NEGATIVE_INFINITY
        var bestResult: MatchResult = MatchResult.NoMatch(hypotheses.first().first, "none")
        var idx = 0
        var enqueuedAny = false

        for ((hyp, asrConfFloat) in normalizedHyps.take(HEAVY_HYP_COUNT)) {
            ensureActive()
            val rawTrim = hyp
            val normalized = TextUtils.normalizeLowerNoDiacritics(rawTrim)
            if (normalized.isBlank()) {
                idx++; continue
            }

            try {
                val maybeMrInline = withTimeoutOrNull(INLINE_HEAVY_TIMEOUT_MS) {
                    AliasPriorityMatcher.match(normalized, matchContext, context, saf)
                }

                if (maybeMrInline != null) {
                    val mr = maybeMrInline

                    if (mr is MatchResult.AutoAccept || mr is MatchResult.MultiMatch) {
                        writeMatchLogNonBlocking(rawTrim, mr, filteredPartials, asrHypotheses = hypotheses)
                        return@withContext mr
                    }

                    val matcherScore = when (mr) {
                        is MatchResult.AutoAcceptAddPopup -> mr.candidate.score
                        is MatchResult.SuggestionList -> mr.candidates.firstOrNull()?.score ?: 0.0
                        is MatchResult.NoMatch -> 0.0
                        is MatchResult.AutoAccept -> mr.candidate.score
                        is MatchResult.MultiMatch -> mr.matches.firstOrNull()?.candidate?.score ?: 0.0
                        else -> 0.0
                    }

                    val asrConf = asrConfFloat.toDouble().coerceIn(0.0, 1.0)
                    val combined = asrWeight * asrConf + (1.0 - asrWeight) * matcherScore

                    if (combined > bestCombined) {
                        bestCombined = combined
                        bestResult = mr
                    }
                } else {
                    val pending = PendingAsr(
                        id = UUID.randomUUID().toString(),
                        text = rawTrim,
                        confidence = asrConfFloat,
                        matchContext = matchContext,
                        partials = filteredPartials
                    )
                    ensurePendingWorkerRunning()
                    val added = pendingBuffer.add(pending)
                    enqueuedAny = enqueuedAny || added
                    if (!added) {
                        val inlineFallback = withTimeoutOrNull(250L) {
                            AliasPriorityMatcher.match(TextUtils.normalizeLowerNoDiacritics(pending.text), matchContext, context, saf)
                        }
                        if (inlineFallback != null) {
                            val mr = inlineFallback
                            writeMatchLogNonBlocking(pending.text, mr, pending.partials, asrHypotheses = listOf(pending.text to pending.confidence))
                            return@withContext mr
                        } else {
                            Log.w(TAG, "Pending buffer full; dropped pending utterance id=${pending.id}")
                        }
                    } else {
                        writeMatchLogNonBlocking(rawTrim, MatchResult.NoMatch(rawTrim, "queued"), filteredPartials, asrHypotheses = hypotheses)
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Error matching hypothesis #$idx '${hyp}': ${ex.message}", ex)
            }

            idx++
        }

        if (bestCombined == Double.NEGATIVE_INFINITY) {
            for ((hyp, _) in normalizedHyps.drop(HEAVY_HYP_COUNT)) {
                ensureActive()
                val quickNorm = TextUtils.normalizeLowerNoDiacritics(hyp)
                if (quickNorm.isBlank()) continue
                val recs = AliasMatcher.findExact(quickNorm, context, saf)
                if (recs.isNotEmpty()) {
                    val speciesSet = recs.map { it.speciesid }.toSet()
                    val chosen = when {
                        speciesSet.size == 1 -> speciesSet.first()
                        else -> speciesSet.firstOrNull { it in matchContext.tilesSpeciesIds }
                    }
                    if (chosen != null) {
                        val candidate = Candidate(speciesId = chosen, displayName = matchContext.speciesById[chosen]?.first ?: hyp, score = 0.9, isInTiles = chosen in matchContext.tilesSpeciesIds, source = "quick_exact")
                        val mr = MatchResult.AutoAccept(candidate, hyp, "quick_exact", 1)
                        writeMatchLogNonBlocking(hyp, mr, filteredPartials, asrHypotheses = hypotheses)
                        return@withContext mr
                    }
                }
            }
        }

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithHypotheses finished: bestHyp='${bestResult.hypothesis}' type=${bestResult::class.simpleName} timeMs=${t1 - t0}")

        val firstHypText = hypotheses.firstOrNull()?.first ?: ""
        if (enqueuedAny && bestCombined == Double.NEGATIVE_INFINITY) {
            writeMatchLogNonBlocking(firstHypText, MatchResult.NoMatch(firstHypText, "queued"), filteredPartials, asrHypotheses = hypotheses)
            return@withContext MatchResult.NoMatch(firstHypText, "queued")
        }

        writeMatchLogNonBlocking(firstHypText, bestResult, filteredPartials, asrHypotheses = hypotheses)
        return@withContext bestResult
    }

    private fun writeMatchLogNonBlocking(
        rawInput: String,
        result: MatchResult,
        filteredPartials: List<String>,
        asrHypotheses: List<Pair<String, Float>>? = null
    ) {
        try {
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
                is MatchResult.MultiMatch -> null
                is MatchResult.NoMatch -> null
                else -> null
            }

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

            val asrHyps = asrHypotheses?.map { (text, conf) -> AsrHypothesis(text, conf) }

            val entry = MatchLogEntry(
                timestampIso = Instant.now().toString(),
                rawInput = rawInput,
                resultType = result::class.simpleName ?: "Unknown",
                hypothesis = result.hypothesis,
                candidate = candidateLog,
                multiMatches = multiMatchesLog,
                partials = filteredPartials,
                asr_hypotheses = asrHyps
            )

            val logLine = json.encodeToString(entry)

            MatchLogWriter.enqueueFireAndForget(context, logLine)

            bgScope.launch {
                try {
                    val date = Instant.now().toString().substring(0, 10).replace("-", "")
                    val filename = "match_log_$date.ndjson"

                    val vt5 = saf.getVt5DirIfExists()
                    if (vt5 == null) return@launch

                    val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports")
                    ?: return@launch

                    val file = exports.findFile(filename)
                    val bytes = (logLine + "\n").toByteArray(Charsets.UTF_8)

                    if (file == null || !file.exists()) {
                        val newFile = exports.createFile("application/x-ndjson", filename) ?: return@launch
                        context.contentResolver.openOutputStream(newFile.uri, "w")?.use { os ->
                            os.write(bytes)
                            os.flush()
                        }
                        Log.d(TAG, "Match log written to SAF: ${newFile.uri}")
                    } else {
                        var appended = false
                        try {
                            context.contentResolver.openOutputStream(file.uri, "wa")?.use { os ->
                                os.write(bytes)
                                os.flush()
                                appended = true
                            }
                        } catch (ex: Exception) {
                            Log.w(TAG, "SAF append mode failed: ${ex.message}")
                        }

                        if (!appended) {
                            try {
                                val tailLines = MatchLogWriter.getTailSnapshot()
                                val prefix = if (tailLines.isNotEmpty()) tailLines.joinToString("\n") + "\n" else ""
                                val newContent = prefix + logLine + "\n"

                                exports.findFile(filename)?.delete()
                                val recreated = exports.createFile("application/x-ndjson", filename) ?: return@launch
                                context.contentResolver.openOutputStream(recreated.uri, "w")?.use { os ->
                                    os.write(newContent.toByteArray(Charsets.UTF_8))
                                    os.flush()
                                }
                                Log.d(TAG, "Match log rewritten to SAF (in-memory tail)")
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
}