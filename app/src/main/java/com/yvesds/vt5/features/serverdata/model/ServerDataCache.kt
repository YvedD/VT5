@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Eenvoudige in-memory cache voor DataSnapshot.
 * - Laadt 1x van SAF via ServerDataRepository, daarna uit geheugen.
 * - Call invalidate() na "JSONs downloaden" of wanneer je data verversd hebt.
 */
object ServerDataCache {
    @Volatile
    private var cached: DataSnapshot? = null

    fun invalidate() {
        cached = null
    }

    suspend fun getOrLoad(context: Context): DataSnapshot {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            cached ?: run {
                val repo = ServerDataRepository(context.applicationContext)
                val snap = repo.loadAllFromSaf()
                cached = snap
                snap
            }
        }
    }
}