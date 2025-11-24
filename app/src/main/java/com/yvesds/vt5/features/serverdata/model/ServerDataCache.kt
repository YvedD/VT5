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
        cached = null
    }

    fun getCachedOrNull(): DataSnapshot? = cached

    /**
     * Two-phase startup:
     * Phase 1: Load ONLY codes (ultra-fast, ~50ms)
     * Phase 2: Load everything else in background when idle
     *
     * This allows MetadataScherm to open instantly with just codes,
     * while heavy data (species, sites, protocols) loads in background.
     */
    fun preload(context: Context) {
        // Fast-path: already cached -> nothing to do
        if (cached != null) {
            return
        }

        // If there's already a loader, don't spawn another
        if (loadingDeferred != null && loadingDeferred?.isActive == true) {
            return
        }

        // Phase 1: Load ONLY codes immediately (ultra-fast)
        synchronized(this) {
            if (loadingDeferred == null || loadingDeferred?.isCompleted == true) {
                loadingDeferred = loaderScope.async {
                    loadCodesOnly(context)
                }
                
                // Phase 2: Schedule full data load in background (delayed start)
                loaderScope.launch {
                    delay(500) // Wait 500ms to ensure app is idle
                    try {
                        val fullSnapshot = loadFromSaf(context)
                        cached = fullSnapshot
                        Log.i(TAG, "Phase 2 complete: all data loaded in background")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Phase 2 background load failed: ${ex.message}", ex)
                    }
                }
            } else {
            }
        }
    }
    
    /**
     * Phase 1: Load ONLY codes - ultra-fast startup
     * Creates a minimal DataSnapshot with just codes (55 records, 3 fields = ~4KB)
     */
    private suspend fun loadCodesOnly(context: Context): DataSnapshot = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val repo = ServerDataRepository(context.applicationContext)
            val codesByCategory = repo.loadCodesOnly()
            
            // Create minimal snapshot with only codes
            val snap = DataSnapshot(codesByCategory = codesByCategory)
            cached = snap
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "loadCodesOnly: loaded codes in ${elapsed}ms (instant startup!)")
            return@withContext snap
        } catch (ex: Exception) {
            Log.e(TAG, "loadCodesOnly failed: ${ex.message}", ex)
            throw ex
        } finally {
            synchronized(this@ServerDataCache) {
                val cur = loadingDeferred
                if (cur != null && cur.isCompleted) {
                    loadingDeferred = null
                }
            }
        }
    }

    /**
     * Get cached snapshot or load synchronously (suspending) if not present.
     * If a background preload is running, this will await that loader rather than spawn a second.
     */
    suspend fun getOrLoad(context: Context): DataSnapshot = coroutineScope {
        cached?.let {
            return@coroutineScope it
        }

        // If a loader exists, await it
        val existing = loadingDeferred
        if (existing != null) {
            try {
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