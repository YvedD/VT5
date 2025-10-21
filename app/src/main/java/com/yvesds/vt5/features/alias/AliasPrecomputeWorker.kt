package com.yvesds.vt5.features.alias

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream
import java.io.File

/**
 * Worker that triggers AliasIndexWriter.ensureComputedDetailed(...)
 * Provides structured output:
 *  - internalJsonGzPath (String)
 *  - internalCborGzPath (String)
 *  - internalWritten (boolean 0/1)
 *  - safJsonWritten (boolean 0/1)
 *  - safCborWritten (boolean 0/1)
 *  - exportLogInternalPath (String)
 *  - exportLogSafPath (String)
 *  - aliasCount (int)
 *  - messages (single string, newline separated)
 */
class AliasPrecomputeWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(applicationContext)
        try {
            setProgressAsync(workDataOf("progress" to 1, "message" to "Start pré-computen"))

            val result = AliasIndexWriter.ensureComputedDetailed(applicationContext, saf, q = 3, minhashK = 64)

            val messagesJoined = result.messages.joinToString("\n")

            val out = workDataOf(
                "internalJsonGzPath" to (result.internalJsonGzPath ?: ""),
                "internalCborGzPath" to (result.internalCborGzPath ?: ""),
                "internalWritten" to if (result.internalWritten) 1 else 0,
                "safJsonWritten" to if (result.safJsonWritten) 1 else 0,
                "safCborWritten" to if (result.safCborWritten) 1 else 0,
                "exportLogInternalPath" to (result.exportLogInternalPath ?: ""),
                "exportLogSafPath" to (result.exportLogSafPath ?: ""),
                "aliasCount" to result.aliasCount,
                "messages" to messagesJoined,
                "summary" to "Aliases computed: ${result.aliasCount}. internal=${result.internalWritten}, safJson=${result.safJsonWritten}, safCbor=${result.safCborWritten}"
            )

            setProgressAsync(workDataOf("progress" to 100, "message" to "Klaar"))
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }
}