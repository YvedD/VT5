package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasIndex
import com.yvesds.vt5.features.alias.AliasMaster
import com.yvesds.vt5.features.alias.toAliasIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * AliasCborRebuilder: Manages CBOR cache rebuilding with debouncing.
 * 
 * Responsibilities:
 * - Schedule debounced CBOR rebuilds (coalesce multiple requests)
 * - Read master.json and regenerate CBOR
 * - Update both SAF and internal caches
 * - Write exports copy for user accessibility
 * 
 * This prevents excessive rebuilds when adding multiple aliases.
 */
@OptIn(ExperimentalSerializationApi::class)
object AliasCborRebuilder {
    
    private const val TAG = "AliasCborRebuilder"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val MASTER_FILE = "alias_master.json"
    private const val BINARIES = "binaries"
    private const val ASSETS = "assets"
    private const val CBOR_REBUILD_DEBOUNCE_MS = 30_000L
    
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val cborMutex = Mutex()
    private var cborRebuildJob: Job? = null
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Schedule a debounced CBOR rebuild.
     * If immediate=true, rebuilds as soon as possible without debounce.
     */
    fun scheduleRebuild(context: Context, saf: SaFStorageHelper, immediate: Boolean = false) {
        writeScope.launch {
            cborMutex.withLock {
                cborRebuildJob?.cancel()
                cborRebuildJob = writeScope.launch {
                    try {
                        if (!immediate) {
                            delay(CBOR_REBUILD_DEBOUNCE_MS)
                        }
                        
                        performRebuild(context, saf)
                    } catch (ex: CancellationException) {
                        Log.i(TAG, "CBOR rebuild job cancelled/rescheduled")
                    } catch (ex: Exception) {
                        Log.w(TAG, "CBOR rebuild failed: ${ex.message}", ex)
                    } finally {
                        cborRebuildJob = null
                    }
                }
            }
        }
    }
    
    /**
     * Force immediate CBOR rebuild (synchronous).
     */
    suspend fun forceRebuild(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        cborMutex.withLock {
            try {
                cborRebuildJob?.cancel()
            } catch (_: Exception) {}
            
            try {
                performRebuild(context, saf)
                Log.i(TAG, "forceRebuild: CBOR rebuild finished (synchronous)")
            } catch (ex: Exception) {
                Log.w(TAG, "forceRebuild failed: ${ex.message}", ex)
            } finally {
                cborRebuildJob = null
            }
        }
    }
    
    /**
     * Perform the actual CBOR rebuild from master.json.
     */
    private suspend fun performRebuild(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        val vt5 = saf.getVt5DirIfExists() ?: run {
            Log.w(TAG, "performRebuild: VT5 not available")
            return@withContext
        }
        
        val assets = vt5.findFile(ASSETS)?.takeIf { it.isDirectory } ?: run {
            Log.w(TAG, "performRebuild: assets dir missing")
            return@withContext
        }
        
        val masterDoc = assets.findFile(MASTER_FILE)?.takeIf { it.isFile }
        if (masterDoc == null) {
            Log.w(TAG, "performRebuild: master.json missing")
            return@withContext
        }
        
        val masterJson = runCatching {
            context.contentResolver.openInputStream(masterDoc.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
        
        if (masterJson.isNullOrBlank()) {
            Log.w(TAG, "performRebuild: master.json empty")
            return@withContext
        }
        
        val master = try {
            jsonPretty.decodeFromString(AliasMaster.serializer(), masterJson)
        } catch (ex: Exception) {
            Log.w(TAG, "performRebuild: decode failed: ${ex.message}")
            return@withContext
        }
        
        val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } 
            ?: vt5.createDirectory(BINARIES) ?: run {
            Log.w(TAG, "performRebuild: cannot create binaries dir")
            return@withContext
        }
        
        rebuildCborCache(master, binaries, context, saf)
        Log.i(TAG, "performRebuild: CBOR rebuild finished")
    }
    
    /**
     * Rebuild CBOR cache from AliasMaster.
     */
    private suspend fun rebuildCborCache(
        master: AliasMaster,
        binariesDir: DocumentFile,
        context: Context,
        saf: SaFStorageHelper
    ) = withContext(Dispatchers.IO) {
        try {
            val index = master.toAliasIndex()
            
            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            val gzipped = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }
            
            // Attempt SAF write
            var wroteSaf = false
            try {
                binariesDir.findFile(CBOR_FILE)?.delete()
            } catch (_: Exception) {}
            
            val cborDoc = runCatching {
                binariesDir.createFile("application/octet-stream", CBOR_FILE)
            }.getOrNull()
            
            if (cborDoc != null) {
                wroteSaf = AliasSafWriter.safeWriteToDocument(context, cborDoc, gzipped)
                if (wroteSaf) {
                    Log.i(TAG, "Rebuilt CBOR cache and wrote to SAF binaries (${gzipped.size} bytes)")
                } else {
                    Log.w(TAG, "rebuildCborCache: SAF write to binaries failed; falling back to internal cache")
                }
            } else {
                Log.w(TAG, "rebuildCborCache: failed creating CBOR doc in binaries")
            }
            
            // Always update internal cache
            try {
                AliasIndexCache.write(context, index)
                Log.i(TAG, "Internal CBOR cache updated after rebuildCborCache")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to update internal cache after rebuildCborCache: ${ex.message}")
            }
            
            Log.i(TAG, "Rebuilt CBOR cache: ${index.json.size} records, ${gzipped.size} bytes compressed (safWrite=$wroteSaf)")
        } catch (ex: Exception) {
            Log.e(TAG, "rebuildCborCache failed: ${ex.message}", ex)
        }
    }
}
