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
 * - Fast-path exact lookup added: scans top N ASR hypotheses for normalized exact matches.
 * - Logging (writeMatchLog / writeLog) made robust and non-blocking.
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

        // FAST-PATH: try inexpensive exact lookups on top N hypotheses to provide near-instant accepts.
        // Behavior:
        //  - If exact alias/canonical/tilename maps to a single species in tiles -> immediate AutoAccept
        //  - Else if maps to a single species in siteAllowed and ASR confidence >= FAST_ASR_CONF_THRESHOLD -> immediate AutoAccept
        //  - If ambiguous, prefer species present in tiles; otherwise fall back to heavy matcher
        for ((hyp, asrConfFloat) in hypotheses.take(FAST_N_HYPOTHESES)) {
            ensureActive()
            val raw = hyp.trim()
            if (raw.isEmpty()) continue

            // extract trailing integer if present (e.g., "aalscholver 3")
            val m = Regex("""^(.*?)(?:\s+(\d+)(?:[.,]\d+)?)?$""").find(raw)
            val nameOnly = m?.groups?.get(1)?.value?.trim().orEmpty()
            val extractedCount = m?.groups?.get(2)?.value?.toIntOrNull() ?: 0

            val norm = normalizeLowerNoDiacritics(nameOnly)
            if (norm.isBlank()) continue

            try {
                // findExact is suspend; call it (cheap hash lookup in AliasMatcher)
                val records = AliasMatcher.findExact(norm, context, saf)
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
                    // ambiguous, no tile-preference -> let heavy matcher handle it
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
                    // Log the fastpath accept with full ASR hypotheses for telemetry
                    writeMatchLog(raw, mr, partials, asrHypotheses = hypotheses)
                    Log.d(TAG, "FASTPATH accept: species=$chosenSid source=${mr.source} amount=$amount hyp='$raw' asrConf=$asrConf")
                    return@withContext mr
                }
            } catch (ex: Exception) {
                Log.w(TAG, "FASTPATH lookup error for '$raw': ${ex.message}", ex)
                // on error, just continue to heavy path
            }
        }

        // No fast-path hit — proceed with original heavy matching loop (N-best combined scoring)
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

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "parseSpokenWithHypotheses finished: bestHyp='${bestResult.hypothesis}' type=${bestResult::class.simpleName} timeMs=${t1 - t0}")

        // Log chosen result including ASR N-best hypotheses
        writeMatchLog(hypotheses.firstOrNull()?.first ?: "", bestResult, partials, asrHypotheses = hypotheses)

        return@withContext bestResult
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