package com.yvesds.vt5.features.speech.helpers

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.MatchLogWriter
import com.yvesds.vt5.features.speech.MatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * SpeechMatchLogger
 *
 * Centralized logging for speech recognition match results.
 * Handles:
 * - Building structured log entries
 * - Writing to SAF (Storage Access Framework) in NDJSON format
 * - Integration with MatchLogWriter for buffering
 * - Non-blocking background writes
 */
class SpeechMatchLogger(
    private val context: Context,
    private val saf: SaFStorageHelper
) {
    companion object {
        private const val TAG = "SpeechMatchLogger"
        private val json = Json { prettyPrint = true }
    }

    // Background scope for SAF writes
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Log a match result with all associated data
     */
    fun logMatchResult(
        rawInput: String,
        result: MatchResult,
        filteredPartials: List<String>,
        asrHypotheses: List<Pair<String, Float>>? = null
    ) {
        try {
            val entry = buildLogEntry(rawInput, result, filteredPartials, asrHypotheses)
            val logLine = json.encodeToString(entry)

            // Enqueue to in-memory buffer (fast)
            MatchLogWriter.enqueueFireAndForget(context, logLine)

            // Background SAF write
            writeToSAFAsync(logLine)
        } catch (ex: Exception) {
            Log.e(TAG, "logMatchResult failed: ${ex.message}", ex)
        }
    }

    private fun buildLogEntry(
        rawInput: String,
        result: MatchResult,
        filteredPartials: List<String>,
        asrHypotheses: List<Pair<String, Float>>?
    ): MatchLogEntry {
        val candidateLog = buildCandidateLog(result)
        val multiMatchesLog = buildMultiMatchLog(result)
        val asrHyps = asrHypotheses?.map { (text, conf) -> AsrHypothesis(text, conf) }

        return MatchLogEntry(
            timestampIso = Instant.now().toString(),
            rawInput = rawInput,
            resultType = result::class.simpleName ?: "Unknown",
            hypothesis = result.hypothesis,
            candidate = candidateLog,
            multiMatches = multiMatchesLog,
            partials = filteredPartials,
            asr_hypotheses = asrHyps
        )
    }

    private fun buildCandidateLog(result: MatchResult): CandidateLog? {
        return when (result) {
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
            else -> null
        }
    }

    private fun buildMultiMatchLog(result: MatchResult): List<MultiMatchLog>? {
        return if (result is MatchResult.MultiMatch) {
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
    }

    private fun writeToSAFAsync(logLine: String) {
        bgScope.launch {
            try {
                val date = Instant.now().toString().substring(0, 10).replace("-", "")
                val filename = "match_log_$date.ndjson"

                val vt5 = saf.getVt5DirIfExists() ?: return@launch
                val exports = vt5.findFile("exports")?.takeIf { it.isDirectory }
                    ?: vt5.createDirectory("exports") ?: return@launch

                val file = exports.findFile(filename)
                val bytes = (logLine + "\n").toByteArray(Charsets.UTF_8)

                if (file == null || !file.exists()) {
                    createNewLogFile(exports, filename, bytes)
                } else {
                    appendToLogFile(file, exports, filename, logLine, bytes)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Background SAF write failed: ${ex.message}", ex)
            }
        }
    }

    private fun createNewLogFile(
        exports: androidx.documentfile.provider.DocumentFile,
        filename: String,
        bytes: ByteArray
    ) {
        try {
            val newFile = exports.createFile("application/x-ndjson", filename) ?: return
            context.contentResolver.openOutputStream(newFile.uri, "w")?.use { os ->
                os.write(bytes)
                os.flush()
            }
            Log.d(TAG, "Match log written to SAF: ${newFile.uri}")
        } catch (ex: Exception) {
            Log.w(TAG, "createNewLogFile failed: ${ex.message}", ex)
        }
    }

    private fun appendToLogFile(
        file: androidx.documentfile.provider.DocumentFile,
        exports: androidx.documentfile.provider.DocumentFile,
        filename: String,
        logLine: String,
        bytes: ByteArray
    ) {
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
            fallbackRewriteLogFile(exports, filename, logLine)
        }
    }

    private fun fallbackRewriteLogFile(
        exports: androidx.documentfile.provider.DocumentFile,
        filename: String,
        logLine: String
    ) {
        try {
            val tailLines = MatchLogWriter.getTailSnapshot()
            val prefix = if (tailLines.isNotEmpty()) tailLines.joinToString("\n") + "\n" else ""
            val newContent = prefix + logLine + "\n"

            exports.findFile(filename)?.delete()
            val recreated = exports.createFile("application/x-ndjson", filename) ?: return
            context.contentResolver.openOutputStream(recreated.uri, "w")?.use { os ->
                os.write(newContent.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            Log.d(TAG, "Match log rewritten to SAF (in-memory tail)")
        } catch (ex: Exception) {
            Log.w(TAG, "SAF fallback rewrite failed: ${ex.message}", ex)
        }
    }

    // Serializable data classes
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
}
