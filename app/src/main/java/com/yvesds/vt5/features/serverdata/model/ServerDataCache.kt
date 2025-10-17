@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verbeterde in-memory cache voor DataSnapshot met preloading.
 * - Laadt 1x van SAF via ServerDataRepository, daarna uit geheugen.
 * - Call invalidate() na "JSONs downloaden" of wanneer je data verversd hebt.
 * - Ondersteunt preloading in achtergrond voor betere app-prestaties
 */
object ServerDataCache {
    private const val TAG = "ServerDataCache"

    @Volatile
    private var cached: DataSnapshot? = null
    private var lastLoadTimeMs: Long = 0
    @Volatile
    private var isLoading = false

    fun invalidate() {
        Log.d(TAG, "Cache invalidated")
        cached = null
    }

    fun getCachedOrNull(): DataSnapshot? = cached

    /**
     * Preloads data in background zonder blokkeren.
     * Aanroepen vroeg in de app-levenscyclus voor betere performance.
     */
    suspend fun preload(context: Context) {
        if (cached != null) {
            Log.d(TAG, "Preload skipped - data already cached")
            return
        }

        if (isLoading) {
            Log.d(TAG, "Preload skipped - already loading")
            return
        }

        Log.d(TAG, "Starting preload...")

        try {
            isLoading = true
            val startTime = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                Log.d(TAG, "Preload - loading from SAF...")
                val repo = ServerDataRepository(context.applicationContext)
                val snap = repo.loadAllFromSaf()
                cached = snap
                lastLoadTimeMs = System.currentTimeMillis() - startTime

                Log.d(TAG, "Preload complete - loaded data in ${lastLoadTimeMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during preload: ${e.message}", e)
        } finally {
            isLoading = false
        }
    }

    suspend fun getOrLoad(context: Context): DataSnapshot {
        cached?.let {
            Log.d(TAG, "getOrLoad - returning cached data")
            return it
        }

        if (isLoading) {
            Log.d(TAG, "getOrLoad - waiting for active loading")
            // Wacht op actieve preloading (max 2.5 sec)
            var waitTime = 0
            while (isLoading && cached == null && waitTime < 2500) {
                withContext(Dispatchers.Default) {
                    Thread.sleep(50)
                }
                waitTime += 50
            }

            cached?.let {
                Log.d(TAG, "getOrLoad - preload complete while waiting, returning cached data")
                return it
            }
        }

        Log.d(TAG, "getOrLoad - loading data from SAF")
        return withContext(Dispatchers.IO) {
            try {
                isLoading = true
                val startTime = System.currentTimeMillis()

                val repo = ServerDataRepository(context.applicationContext)
                val snap = repo.loadAllFromSaf()
                cached = snap
                lastLoadTimeMs = System.currentTimeMillis() - startTime

                Log.d(TAG, "getOrLoad - loaded data in ${lastLoadTimeMs}ms")
                snap
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data: ${e.message}", e)
                throw e
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Performance statistieken over laatste laadtijd
     */
    fun getLastLoadTimeMs(): Long = lastLoadTimeMs
}