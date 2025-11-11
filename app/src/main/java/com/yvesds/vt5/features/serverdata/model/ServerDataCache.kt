@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

/**
 * Improved in-memory cache for DataSnapshot with safe, single-loader semantics.
 *
 * Key changes vs your original:
 * - Replaced busy-wait loop / Thread.sleep with a single Deferred loader that callers can await.
 * - preload() now starts a best-effort background loader and returns immediately (non-blocking).
 * - getOrLoad() will await an in-progress loader (if any) or start and await a loader itself.
 * - Loader lifecycle: on success cached is set; on failure the deferred is cleared so future calls can retry.
 * - All IO runs on Dispatchers.IO; CPU-bound merging (if any) can run in Default inside the loader.
 * - Simpler and more robust concurrency (no manual isLoading flags or sleep loops).
 *
 * Usage:
 * - Call preload(context) early (e.g. Application.onCreate) to warm the cache (best-effort).
 * - Call getOrLoad(context) in suspending code to get the snapshot (will await loader if needed).
 * - Call invalidate() after you update server JSONs to force reload on next getOrLoad().
 */
object ServerDataCache {
    private const val TAG = "ServerDataCache"

    @Volatile
    private var cached: DataSnapshot? = null

    @Volatile
    private var lastLoadTimeMs: Long = 0

    // Single loader Deferred; volatile so we can read without locking.
    @Volatile
    private var loadingDeferred: Deferred<DataSnapshot>? = null

    // Dedicated scope for loading work
    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun invalidate() {
        Log.d(TAG, "Cache invalidated")
        cached = null
    }

    fun getCachedOrNull(): DataSnapshot? = cached

    /**
     * Start a best-effort background preload without suspending the caller.
     * If a loader is already running or cache exists this returns immediately.
     */
    fun preload(context: Context) {
        // Fast-path: already cached -> nothing to do
        if (cached != null) {
            Log.d(TAG, "Preload skipped - data already cached")
            return
        }

        // If there's already a loader, don't spawn another
        if (loadingDeferred != null && loadingDeferred?.isActive == true) {
            Log.d(TAG, "Preload skipped - loader already running")
            return
        }

        // Start loader but don't await it (best-effort)
        synchronized(this) {
            if (loadingDeferred == null || loadingDeferred?.isCompleted == true) {
                Log.d(TAG, "Starting background preload (best-effort)")
                loadingDeferred = loaderScope.async {
                    loadFromSaf(context)
                }
                // do not await here
            } else {
                Log.d(TAG, "Preload: loader already present")
            }
        }
    }

    /**
     * Get cached snapshot or load synchronously (suspending) if not present.
     * If a background preload is running, this will await that loader rather than spawn a second.
     */
    suspend fun getOrLoad(context: Context): DataSnapshot = coroutineScope {
        cached?.let {
            Log.d(TAG, "getOrLoad - returning cached data")
            return@coroutineScope it
        }

        // If a loader exists, await it
        val existing = loadingDeferred
        if (existing != null) {
            try {
                Log.d(TAG, "getOrLoad - awaiting active loader")
                val snap = existing.await()
                return@coroutineScope snap
            } catch (ex: CancellationException) {
                // Propagate coroutine cancellation
                throw ex
            } catch (ex: Exception) {
                // Loader failed — fall through and try to load directly
                Log.w(TAG, "getOrLoad - background loader failed: ${ex.message}; will try direct load", ex)
            }
        }

        // No loader or it failed -> create and await a loader ourselves
        val loader = synchronized(this@ServerDataCache) {
            // re-check inside lock
            val cur = loadingDeferred
            if (cur != null && cur.isActive) {
                cur
            } else {
                loaderScope.async {
                    loadFromSaf(context)
                }.also { loadingDeferred = it }
            }
        }

        try {
            return@coroutineScope loader.await()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // ensure we clear the failed deferred so future calls can retry
            synchronized(this@ServerDataCache) {
                if (loadingDeferred === loader) loadingDeferred = null
            }
            Log.e(TAG, "getOrLoad failed loading data: ${ex.message}", ex)
            throw ex
        }
    }

    /**
     * Internal loader that actually reads from SAF (ServerDataRepository).
     * Runs on the loaderScope (Dispatchers.IO) and sets cached on success.
     */
    private suspend fun loadFromSaf(context: Context): DataSnapshot = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Log.d(TAG, "loadFromSaf: loading snapshot from SAF via ServerDataRepository")
            val repo = ServerDataRepository(context.applicationContext)
            val snap = repo.loadAllFromSaf()
            cached = snap
            lastLoadTimeMs = System.currentTimeMillis() - start
            Log.i(TAG, "loadFromSaf: loaded snapshot in ${lastLoadTimeMs}ms")
            return@withContext snap
        } catch (ex: Exception) {
            Log.e(TAG, "loadFromSaf failed: ${ex.message}", ex)
            throw ex
        } finally {
            // Clear the deferred reference if this coroutine corresponds to current deferred.
            // This is safe because callers check cached first.
            synchronized(this@ServerDataCache) {
                // find any completed deferred and clear it so next load can retry
                val cur = loadingDeferred
                if (cur != null && cur.isCompleted) {
                    loadingDeferred = null
                }
            }
        }
    }

    /**
     * Performance statistic: last load duration in milliseconds (0 if never loaded).
     */
    //fun getLastLoadTimeMs(): Long = lastLoadTimeMs
}