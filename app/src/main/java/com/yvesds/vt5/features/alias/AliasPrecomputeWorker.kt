package com.yvesds.vt5.features.alias

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker that triggers ensureComputedSafOnly and returns SAF paths in output data.
 */
class AliasPrecomputeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(applicationContext)
        try {
            setProgressAsync(workDataOf("progress" to 1, "message" to "Start pré-computen (SAF-only)"))
            val result = AliasIndexWriter.ensureComputedSafOnly(applicationContext, saf, q = 3)

            val out = workDataOf(
                "safAliasJsonPath" to (result.safAliasJsonPath ?: ""),
                "safCborPath" to (result.safCborPath ?: ""),
                "safSpeciesCborPath" to (result.safSpeciesCborPath ?: ""),
                "safManifestPath" to (result.safManifestPath ?: ""),
                "safExportLogPath" to (result.safExportLog ?: ""),
                "aliasCount" to result.aliasCount,
                "messages" to result.messages.joinToString("\n"),
                "summary" to "Aliases: ${result.aliasCount}; aliasJson=${result.safAliasJsonPath}; cbor=${result.safCborPath}"
            )

            setProgressAsync(workDataOf("progress" to 100, "message" to "Klaar"))
            Result.success(out)
        } catch (ex: Exception) {
            Result.failure(workDataOf("error" to (ex.message ?: ex.toString())))
        }
    }
}