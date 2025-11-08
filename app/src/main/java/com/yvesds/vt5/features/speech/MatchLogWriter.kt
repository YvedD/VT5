package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight background logger for parser match entries.
 *
 * Behaviour additions:
 * - Background writer with single consumer coroutine (idempotent start).
 * - Periodical cleanup of internal "match_logs" folder: only keep files from the last 7 days.
 *   Cleanup runs at most once per calendar day (compares LocalDate) and the last-cleanup date
 *   + list of deleted files are persisted to SharedPreferences so they can be inspected.
 *
 * Usage:
 *  - MatchLogWriter.start(context)           // optional, safe to call multiple times
 *  - suspend MatchLogWriter.enqueue(line)    // suspending, respects backpressure
 *  - MatchLogWriter.enqueueFireAndForget(context, line) // non-suspending, may drop on full
 *
 * Notes:
 * - Cleanup uses the file lastModified timestamp and an age cutoff (now - 7 days).
 * - The preferences keys used:
 *     PREFS_NAME = "matchlog_prefs"
 *     KEY_LAST_CLEANUP_ISO = "matchlog_last_cleanup_iso"
 *     KEY_LAST_CLEANUP_DELETED = "matchlog_last_cleanup_deleted" (pipe-separated filenames)
 */
object MatchLogWriter {
    private const val TAG = "MatchLogWriter"

    // Small bounded buffer to provide modest burst-absorption without unbounded memory use.
    private val channel = Channel<String>(capacity = 4096)

    // Single shared scope for the writer coroutine(s)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Ensure the writer is started only once
    private val started = AtomicBoolean(false)

    // Cleanup prefs
    private const val PREFS_NAME = "matchlog_prefs"
    private const val KEY_LAST_CLEANUP_ISO = "matchlog_last_cleanup_iso"
    private const val KEY_LAST_CLEANUP_DELETED = "matchlog_last_cleanup_deleted"

    // Keep N days (inclusive) of logs (last seven 24-hour windows)
    private const val KEEP_DAYS = 7L

    /**
     * Start the background writer if not already running.
     * Idempotent and cheap to call.
     *
     * This will also perform a best-effort cleanup of old match_logs once per calendar day
     * (the cleanup itself runs on the IO dispatcher and will not block the caller).
     */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            try {
                val dir = File(context.filesDir, "match_logs")
                if (!dir.exists()) dir.mkdirs()

                // Run cleanup if needed before we start writing to today's file
                try {
                    performCleanupIfNeeded(dir, context)
                } catch (ex: Exception) {
                    Log.w(TAG, "match_logs cleanup failed: ${ex.message}", ex)
                }

                val fname = "match_log_${DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")}.ndjson"
                val out = File(dir, fname)
                BufferedWriter(FileWriter(out, true)).use { writer ->
                    for (entry in channel) {
                        try {
                            writer.append(entry)
                            writer.newLine()
                            writer.flush()
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed writing match log entry: ${ex.message}", ex)
                        }
                    }
                    // channel closed -> exit write loop
                }
            } catch (ex: Exception) {
                Log.w(TAG, "MatchLogWriter background writer failed: ${ex.message}", ex)
                // keep started = true to avoid repeated immediate restarts; manual restart requires app-level logic
            }
        }
    }

    /**
     * Suspend and enqueue a line. This will suspend when the channel is full (backpressure).
     * Use this when you want guaranteed delivery (at the cost of suspension).
     *
     * Kept as part of the API even if currently unused in your codebase.
     */
    @Suppress("unused")
    suspend fun enqueue(line: String) {
        channel.send(line)
    }

    /**
     * Fire-and-forget enqueue for non-suspending callers.
     * May drop the log line if the channel buffer is full (best-effort).
     */
    fun enqueueFireAndForget(context: Context, line: String) {
        // Ensure writer started (idempotent)
        start(context)
        val res = channel.trySend(line)
        if (!res.isSuccess) {
            // Drop the entry, but log so we can spot persistent overload
            Log.w(TAG, "MatchLogWriter buffer full — dropping log line")
        }
    }

    /**
     * Optional: call to shut down the writer and close channel (not strictly required).
     * After calling stop(), start() will not auto-restart the writer unless you recreate the object or restart the process.
     *
     * Kept as part of the API even if currently unused; suppressed unused warning.
     */
    @Suppress("unused")
    fun stop() {
        runCatching {
            channel.close()
        }.onFailure {
            Log.w(TAG, "MatchLogWriter.stop() failed to close channel: ${it.message}", it)
        }
    }

    // -------------------------
    // Cleanup helpers
    // -------------------------

    private fun performCleanupIfNeeded(dir: File, context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastIso = prefs.getString(KEY_LAST_CLEANUP_ISO, null)
            val today = LocalDate.now(ZoneId.systemDefault())

            val doCleanup = if (lastIso.isNullOrBlank()) {
                true
            } else {
                try {
                    val last = LocalDate.parse(lastIso)
                    today.isAfter(last)
                } catch (ex: Exception) {
                    true
                }
            }

            if (!doCleanup) {
                // Already cleaned today; nothing to do
                return
            }

            // Perform cleanup on this thread (we are already running on IO dispatcher in start())
            val cutoffMillis = System.currentTimeMillis() - (KEEP_DAYS * 24 * 60 * 60 * 1000L)
            val deleted = mutableListOf<String>()

            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: emptyArray()
                for (f in files) {
                    try {
                        if (!f.isFile) continue
                        val lm = f.lastModified()
                        if (lm < cutoffMillis) {
                            val name = f.name
                            if (f.delete()) {
                                deleted.add(name)
                            } else {
                                Log.w(TAG, "Failed deleting old match_log file: ${f.absolutePath}")
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Error during cleanup evaluating file ${f.absolutePath}: ${ex.message}", ex)
                    }
                }
            }

            // Persist cleanup metadata: date + deleted filenames (pipe-separated)
            prefs.edit()
                .putString(KEY_LAST_CLEANUP_ISO, today.toString())
                .putString(KEY_LAST_CLEANUP_DELETED, deleted.joinToString("|"))
                .apply()

            Log.i(TAG, "match_logs cleanup completed: deleted=${deleted.size} files")
        } catch (ex: Exception) {
            Log.w(TAG, "performCleanupIfNeeded failed: ${ex.message}", ex)
        }
    }

    /**
     * Helper: read last cleanup metadata for inspection.
     * Returns Pair<lastCleanupLocalDateOrNull, listOfDeletedFiles>
     */
    fun getLastCleanupInfo(context: Context): Pair<LocalDate?, List<String>> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val iso = prefs.getString(KEY_LAST_CLEANUP_ISO, null)
            val deleted = prefs.getString(KEY_LAST_CLEANUP_DELETED, null)
            val date = try { if (!iso.isNullOrBlank()) LocalDate.parse(iso) else null } catch (_: Exception) { null }
            val list = deleted?.takeIf { it.isNotBlank() }?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            Pair(date, list)
        } catch (ex: Exception) {
            Log.w(TAG, "getLastCleanupInfo failed: ${ex.message}", ex)
            Pair(null, emptyList())
        }
    }
}