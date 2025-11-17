package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import com.yvesds.vt5.features.alias.AliasIndex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * AliasIndexCache: Manages the internal app-private CBOR cache.
 * 
 * Responsibilities:
 * - Load AliasIndex from internal cache (context.filesDir)
 * - Write AliasIndex to internal cache
 * - Delete internal cache when needed
 * 
 * This provides fast, reliable access to the alias index without depending on SAF.
 */
@OptIn(ExperimentalSerializationApi::class)
object AliasIndexCache {
    
    private const val TAG = "AliasIndexCache"
    private const val INTERNAL_CBOR = "aliases_optimized.cbor.gz"
    
    /**
     * Load AliasIndex from internal cache (app's filesDir).
     * Returns null if cache doesn't exist or load fails.
     */
    fun load(context: Context): AliasIndex? {
        return try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            if (!f.exists() || f.length() == 0L) return null
            
            f.inputStream().use { fis ->
                GZIPInputStream(fis).use { gis ->
                    val bytes = gis.readBytes()
                    Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "load failed: ${ex.message}")
            null
        }
    }
    
    /**
     * Write AliasIndex to internal cache (app's filesDir).
     * Uses atomic write (tmp file + rename) to prevent corruption.
     */
    fun write(context: Context, index: AliasIndex) {
        try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            val bytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            val tmp = File(context.filesDir, "$INTERNAL_CBOR.tmp")
            
            tmp.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.write(bytes)
                    gos.finish()
                }
            }
            
            tmp.renameTo(f)
            Log.i(TAG, "Wrote internal CBOR cache: ${f.absolutePath} (${f.length()} bytes)")
        } catch (ex: Exception) {
            Log.w(TAG, "write failed: ${ex.message}")
        }
    }
    
    /**
     * Delete the internal cache file.
     */
    fun delete(context: Context) {
        try {
            val f = File(context.filesDir, INTERNAL_CBOR)
            if (f.exists()) {
                f.delete()
                Log.i(TAG, "Deleted internal cache")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "delete failed: ${ex.message}")
        }
    }
    
    /**
     * Check if internal cache exists and is non-empty.
     */
    fun exists(context: Context): Boolean {
        val f = File(context.filesDir, INTERNAL_CBOR)
        return f.exists() && f.length() > 0L
    }
}
