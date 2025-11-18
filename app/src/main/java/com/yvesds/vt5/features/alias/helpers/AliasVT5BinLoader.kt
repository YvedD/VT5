@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasIndex
import com.yvesds.vt5.features.serverdata.helpers.ServerDataDecoder
import com.yvesds.vt5.features.serverdata.helpers.VT5Bin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AliasVT5BinLoader: Load alias data from VT5Bin binary format (.bin files).
 * 
 * Responsibilities:
 * - Load alias data from VT5BIN10 binary format
 * - Provide faster loading than JSON/CBOR for alias data
 * - Support for serverdata directory binary files
 * 
 * Binary format advantages:
 * - Faster parsing than JSON
 * - Compressed (GZIP)
 * - Type-safe with header validation
 * - Consistent with other serverdata formats
 * 
 * Load priority (updated):
 * 1) Internal cache (context.filesDir) - fastest
 * 2) SAF serverdata/alias_index.bin (VT5Bin format) - NEW
 * 3) SAF binaries/aliases_optimized.cbor.gz - legacy
 * 4) SAF assets/alias_master.json - fallback
 */
object AliasVT5BinLoader {
    
    private const val TAG = "AliasVT5BinLoader"
    private const val SERVERDATA = "serverdata"
    private const val ALIAS_BIN_FILE = "alias_index.bin"
    
    /**
     * Load AliasIndex from VT5Bin binary format in serverdata directory.
     * This is faster than CBOR/JSON and consistent with other serverdata.
     * 
     * Returns the loaded index or null if file doesn't exist or load fails.
     */
    suspend fun loadFromBinary(
        context: Context, 
        saf: SaFStorageHelper
    ): AliasIndex? = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext null
            val serverdataDir = vt5.findFile(SERVERDATA)?.takeIf { it.isDirectory } 
                ?: return@withContext null
            
            val binFile = serverdataDir.findFile(ALIAS_BIN_FILE)?.takeIf { it.isFile }
                ?: return@withContext null
            
            // Use ServerDataDecoder to load binary format
            val decoder = ServerDataDecoder(context)
            val index = decoder.decodeOneFromBinary<AliasIndex>(
                binFile, 
                VT5Bin.Kind.ALIAS_INDEX
            )
            
            if (index != null) {
                Log.i(TAG, "Loaded AliasIndex from VT5Bin binary: ${index.json.size} records")
                
                // Cache to internal storage for next time
                AliasIndexCache.write(context, index)
            }
            
            index
        } catch (ex: Exception) {
            Log.w(TAG, "loadFromBinary failed: ${ex.message}", ex)
            null
        }
    }
    
    /**
     * Check if binary format alias file exists.
     */
    suspend fun hasBinaryFile(saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext false
            val serverdataDir = vt5.findFile(SERVERDATA)?.takeIf { it.isDirectory }
                ?: return@withContext false
            
            serverdataDir.findFile(ALIAS_BIN_FILE)?.isFile == true
        } catch (ex: Exception) {
            false
        }
    }
}
