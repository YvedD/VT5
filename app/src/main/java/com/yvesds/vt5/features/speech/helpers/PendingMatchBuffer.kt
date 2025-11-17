package com.yvesds.vt5.features.speech.helpers

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasPriorityMatcher
import com.yvesds.vt5.features.speech.MatchContext
import com.yvesds.vt5.features.speech.MatchResult
import com.yvesds.vt5.utils.RingBuffer
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.util.UUID

/**
 * PendingMatchBuffer
 *
 * Manages a bounded queue of pending ASR hypotheses that require heavy matching.
 * Background worker processes the queue with timeout and retry logic.
 *
 * Features:
 * - Ring buffer (8 items, overwrites oldest)
 * - Background coroutine worker
 * - Timeout handling (1200ms per item)
 * - Single retry attempt
 * - Result callbacks
 */
class PendingMatchBuffer(
    private val context: Context,
    private val saf: SaFStorageHelper,
    private val logger: SpeechMatchLogger
) {
    companion object {
        private const val TAG = "PendingMatchBuffer"
        private const val BUFFER_CAPACITY = 8
        private const val PER_ITEM_TIMEOUT_MS = 1200L
        private const val MAX_RETRY_ATTEMPTS = 1
        private const val WORKER_POLL_DELAY_MS = 50L
    }

    private data class PendingAsr(
        val id: String,
        val text: String,
        val confidence: Float,
        val matchContext: MatchContext,
        val partials: List<String>,
        val attempts: Int = 0,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val pendingBuffer = RingBuffer<PendingAsr>(capacity = BUFFER_CAPACITY, overwriteOldest = true)
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var workerStarted = false

    private var resultListener: ((id: String, result: MatchResult) -> Unit)? = null

    /**
     * Register a listener to receive results for pending items
     */
    fun setResultListener(listener: (id: String, result: MatchResult) -> Unit) {
        resultListener = listener
    }

    /**
     * Enqueue a pending ASR hypothesis for heavy matching
     * @return true if enqueued successfully, false if buffer full
     */
    fun enqueuePending(
        text: String,
        confidence: Float,
        matchContext: MatchContext,
        partials: List<String>
    ): String? {
        val pending = PendingAsr(
            id = UUID.randomUUID().toString(),
            text = text,
            confidence = confidence,
            matchContext = matchContext,
            partials = partials
        )

        ensureWorkerRunning()

        return if (pendingBuffer.add(pending)) {
            Log.d(TAG, "Enqueued pending id=${pending.id} text='${pending.text}'")
            pending.id
        } else {
            Log.w(TAG, "Buffer full, dropped pending text='$text'")
            null
        }
    }

    private fun ensureWorkerRunning() {
        if (workerStarted) return
        workerStarted = true

        bgScope.launch {
            try {
                runWorkerLoop()
            } catch (ex: CancellationException) {
                Log.i(TAG, "Pending worker cancelled")
            } catch (ex: Exception) {
                Log.w(TAG, "Pending worker failed: ${ex.message}", ex)
            } finally {
                workerStarted = false
            }
        }
    }

    private suspend fun runWorkerLoop() {
        while (true) {
            coroutineContext.ensureActive()

            val item = pendingBuffer.poll()
            if (item == null) {
                delay(WORKER_POLL_DELAY_MS)
                continue
            }

            processPendingItem(item)
        }
    }

    private suspend fun processPendingItem(item: PendingAsr) {
        try {
            val normalizedText = TextUtils.normalizeLowerNoDiacritics(item.text)
            val maybeResult = withTimeoutOrNull(PER_ITEM_TIMEOUT_MS) {
                AliasPriorityMatcher.match(normalizedText, item.matchContext, context, saf)
            }

            if (maybeResult == null) {
                handleTimeout(item)
            } else {
                handleSuccess(item, maybeResult)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "Error processing pending id=${item.id}: ${ex.message}", ex)
        }
    }

    private fun handleTimeout(item: PendingAsr) {
        Log.w(TAG, "Pending heavy match timed out for id=${item.id} text='${item.text}'")

        if (item.attempts < MAX_RETRY_ATTEMPTS) {
            val retryItem = item.copy(attempts = item.attempts + 1)
            pendingBuffer.add(retryItem)
            Log.d(TAG, "Retrying pending id=${item.id} attempts=${retryItem.attempts}")
        } else {
            val result = MatchResult.NoMatch(item.text, "pending_timed_out")
            logger.logMatchResult(
                item.text,
                result,
                item.partials,
                asrHypotheses = listOf(item.text to item.confidence)
            )
        }
    }

    private fun handleSuccess(item: PendingAsr, result: MatchResult) {
        logger.logMatchResult(
            item.text,
            result,
            item.partials,
            asrHypotheses = listOf(item.text to item.confidence)
        )

        try {
            resultListener?.invoke(item.id, result)
        } catch (ex: Exception) {
            Log.w(TAG, "Result listener failed for id=${item.id}: ${ex.message}", ex)
        }
    }
}
