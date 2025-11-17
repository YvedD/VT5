@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.helpers

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper for reading ServerData files from SAF with caching and optimizations.
 * 
 * Responsibilities:
 * - File discovery in serverdata directory
 * - File type preference caching (.bin vs .json)
 * - Existence checking without loading
 * - Stream management
 */
class ServerDataFileReader(private val context: Context) {
    
    companion object {
        // Cache for file existence to avoid repeated directory scanning
        private val fileExistenceCache = ConcurrentHashMap<String, Boolean>()
        
        // File type quick lookup cache (true = .bin, false = .json)
        private val fileTypeCache = ConcurrentHashMap<String, Boolean>()
    }
    
    /**
     * Checks if required data files exist without loading them.
     * Used to quickly determine if app is ready to run.
     */
    suspend fun hasRequiredFiles(): Boolean = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext false
        val serverdata = vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
            ?: return@withContext false

        // Cache result to avoid repeated scans
        val cacheKey = "${vt5Root.uri}_hasRequiredFiles"
        fileExistenceCache[cacheKey]?.let {
            return@withContext it
        }

        // Check for essential files (sites, codes, species)
        val hasSites = serverdata.findChildByName("sites.bin") != null || 
                      serverdata.findChildByName("sites.json") != null
        val hasCodes = serverdata.findChildByName("codes.bin") != null || 
                      serverdata.findChildByName("codes.json") != null
        val hasSpecies = serverdata.findChildByName("species.bin") != null || 
                        serverdata.findChildByName("species.json") != null

        val result = hasSites && hasCodes && hasSpecies
        fileExistenceCache[cacheKey] = result

        return@withContext result
    }
    
    /**
     * Get the serverdata directory, or null if it doesn't exist.
     */
    suspend fun getServerdataDir(): DocumentFile? = withContext(Dispatchers.IO) {
        val saf = SaFStorageHelper(context)
        val vt5Root = saf.getVt5DirIfExists() ?: return@withContext null
        vt5Root.findChildByName("serverdata")?.takeIf { it.isDirectory }
    }
    
    /**
     * Find a file by basename, preferring .bin over .json.
     * Uses file type cache to optimize subsequent reads.
     * 
     * @param dir The directory to search in
     * @param baseName The base filename without extension (e.g. "sites")
     * @return Pair of (file, isBinary) or null if not found
     */
    fun findFile(dir: DocumentFile, baseName: String): Pair<DocumentFile, Boolean>? {
        val cacheKey = "${dir.uri}_$baseName"
        
        // Check cache first
        fileTypeCache[cacheKey]?.let { isBin ->
            val fileName = if (isBin) "$baseName.bin" else "$baseName.json"
            dir.findChildByName(fileName)?.let { file ->
                return Pair(file, isBin)
            }
        }
        
        // Try .bin first (faster binary format)
        dir.findChildByName("$baseName.bin")?.let { file ->
            fileTypeCache[cacheKey] = true
            return Pair(file, true)
        }
        
        // Fall back to .json
        dir.findChildByName("$baseName.json")?.let { file ->
            fileTypeCache[cacheKey] = false
            return Pair(file, false)
        }
        
        return null
    }
    
    /**
     * Open an input stream for a file with buffering.
     */
    fun openBufferedStream(file: DocumentFile): BufferedInputStream? {
        return context.contentResolver.openInputStream(file.uri)?.let { 
            BufferedInputStream(it) 
        }
    }
    
    /**
     * Open a simple input stream for a file.
     */
    fun openStream(file: DocumentFile): InputStream? {
        return context.contentResolver.openInputStream(file.uri)
    }
    
    /**
     * Clear all file caches.
     */
    fun clearCache() {
        fileExistenceCache.clear()
        fileTypeCache.clear()
    }
}

/**
 * Extension function to find a child by name.
 */
private fun DocumentFile.findChildByName(name: String): DocumentFile? =
    listFiles().firstOrNull { it.name == name }
