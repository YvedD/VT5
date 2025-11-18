package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasIndex
import com.yvesds.vt5.features.alias.AliasMaster
import com.yvesds.vt5.features.alias.toAliasIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AliasIndexLoader: Handles loading AliasIndex from various sources.
 * 
 * Responsibilities:
 * - Load from internal cache (fast path)
 * - Load from VT5Bin binary format (fastest from SAF)
 * - Load from SAF CBOR (copy to internal)
 * - Load from SAF JSON master
 * - Coordinate loading priority logic
 * 
 * Load priority:
 * 1) Internal cache (context.filesDir)
 * 2) SAF serverdata/alias_index.bin (VT5Bin format) - NEW in Phase 2
 * 3) SAF binaries/aliases_optimized.cbor.gz
 * 4) SAF assets/alias_master.json
 */
object AliasIndexLoader {
    
    private const val TAG = "AliasIndexLoader"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"
    private const val INTERNAL_CBOR = "aliases_optimized.cbor.gz"
    
    /**
     * Load AliasIndex with priority fallback logic.
     * Returns the loaded index or null if all sources fail.
     */
    suspend fun loadIndex(context: Context, saf: SaFStorageHelper): AliasIndex? = withContext(Dispatchers.IO) {
        // 1) Try internal cache (fastest)
        val fromInternal = AliasIndexCache.load(context)
        if (fromInternal != null) {
            Log.i(TAG, "Loaded AliasIndex from internal cache")
            return@withContext fromInternal
        }
        
        // 2) Try VT5Bin binary format (NEW - faster than CBOR, consistent with other serverdata)
        val fromBinary = AliasVT5BinLoader.loadFromBinary(context, saf)
        if (fromBinary != null) {
            Log.i(TAG, "Loaded AliasIndex from VT5Bin binary format")
            return@withContext fromBinary
        }
        
        // 3) Try SAF binaries CBOR (legacy path)
        val fromSafCbor = loadFromSafCbor(context, saf)
        if (fromSafCbor != null) {
            Log.i(TAG, "Loaded AliasIndex from SAF binaries CBOR (legacy)")
            return@withContext fromSafCbor
        }
        
        // 4) Try SAF assets JSON master (fallback)
        val fromSafJson = loadFromSafJson(context, saf)
        if (fromSafJson != null) {
            Log.i(TAG, "Built AliasIndex from SAF assets JSON")
            return@withContext fromSafJson
        }
        
        Log.w(TAG, "All index load sources failed")
        return@withContext null
    }
    
    /**
     * Load CBOR from SAF binaries and copy to internal cache.
     */
    private suspend fun loadFromSafCbor(context: Context, saf: SaFStorageHelper): AliasIndex? = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext null
            val binariesDir = vt5.findFile(BINARIES)?.takeIf { it.isDirectory } ?: return@withContext null
            val cborDoc = binariesDir.findFile(CBOR_FILE)?.takeIf { it.isFile } ?: return@withContext null
            
            // Copy to internal cache
            context.contentResolver.openInputStream(cborDoc.uri)?.use { ins ->
                val tmp = File(context.filesDir, "$INTERNAL_CBOR.tmp")
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
                tmp.renameTo(File(context.filesDir, INTERNAL_CBOR))
            }
            
            // Load from internal cache
            AliasIndexCache.load(context)
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromSafCbor failed: ${ex.message}", ex)
            null
        }
    }
    
    /**
     * Load master JSON from SAF assets and build index.
     */
    private suspend fun loadFromSafJson(context: Context, saf: SaFStorageHelper): AliasIndex? = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext null
            val master = AliasMasterIO.readMasterFromAssets(context, vt5) ?: return@withContext null
            
            val index = master.toAliasIndex()
            
            // Write to internal cache for next time
            AliasIndexCache.write(context, index)
            
            index
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromSafJson failed: ${ex.message}", ex)
            null
        }
    }
}
