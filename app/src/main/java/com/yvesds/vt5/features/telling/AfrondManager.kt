package com.yvesds.vt5.features.telling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AfrondManager: lightweight helper to enqueue the AfrondWorker via WorkManager
 * and to expose convenience functions for the Activity.
 */
object AfrondManager {
    private const val TAG = "AfrondManager"

    fun enqueueAfrond(context: Context): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<AfrondWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    fun getWorkInfoLiveData(context: Context, workId: UUID) =
        WorkManager.getInstance(context).getWorkInfoByIdLiveData(workId)

    suspend fun getWorkInfo(context: Context, workId: UUID): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfoById(workId).get()
}