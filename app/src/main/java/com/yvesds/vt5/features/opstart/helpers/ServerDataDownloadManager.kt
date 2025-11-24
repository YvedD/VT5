package com.yvesds.vt5.features.opstart.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.features.annotation.AnnotationsManager
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Helper class voor server data download orchestration.
 * 
 * Verantwoordelijkheden:
 * - Download van server JSON files
 * - Parallel I/O operations (annotations, cache invalidation)
 * - Progress callbacks voor UI updates
 * 
 * Gebruik:
 * ```kotlin
 * val downloadManager = ServerDataDownloadManager(context)
 * val result = downloadManager.downloadAllServerData(username, password) { progress ->
 *     updateProgressDialog(progress)
 * }
 * ```
 */
class ServerDataDownloadManager(
    private val context: Context
) {
    private val jsonPretty = Json { 
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    companion object {
        private const val TAG = "ServerDataDownloadMgr"
    }
    
    /**
     * Sealed class voor download resultaten.
     */
    sealed class DownloadResult {
        /**
         * Download succesvol.
         * @param messages Lijst van status berichten
         */
        data class Success(val messages: List<String>) : DownloadResult()
        
        /**
         * Download mislukt.
         * @param error Foutmelding
         */
        data class Failure(val error: String) : DownloadResult()
    }
    
    /**
     * Download alle server data en voer parallel operations uit.
     * 
     * @param serverdataDir DocumentFile van serverdata directory
     * @param binariesDir DocumentFile van binaries directory
     * @param username Gebruikersnaam
     * @param password Wachtwoord
     * @param language Taal (default: "dutch")
     * @param versie Versie (default: "1845")
     * @param onProgress Callback voor progress updates
     * @return DownloadResult met succes of foutmelding
     */
    suspend fun downloadAllServerData(
        serverdataDir: DocumentFile?,
        binariesDir: DocumentFile?,
        username: String,
        password: String,
        language: String = "dutch",
        versie: String = "1845",
        onProgress: (String) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            if (serverdataDir == null) {
                return@withContext DownloadResult.Failure("Serverdata directory niet gevonden")
            }
            
            onProgress("JSONs downloaden...")
            
            // Download alle JSON files
            val messages = ServerJsonDownloader.downloadAll(
                context = context,
                serverdataDir = serverdataDir,
                binariesDir = binariesDir,
                username = username,
                password = password,
                language = language,
                versie = versie
            )
            
            
            // Parallel operations voor betere performance
            val (annotationsSuccess, _) = coroutineScope {
                val annotationsJob = async {
                    try {
                        ensureAnnotationsFile(serverdataDir.parentFile)
                    } catch (e: Exception) {
                        Log.w(TAG, "Annotations setup failed: ${e.message}", e)
                        false
                    }
                }
                
                val cacheJob = async {
                    try {
                        ServerDataCache.invalidate()
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "Cache invalidation failed: ${e.message}", e)
                        false
                    }
                }
                
                annotationsJob.await() to cacheJob.await()
            }
            
            if (!annotationsSuccess) {
                Log.w(TAG, "Annotations file could not be ensured")
            }
            
            DownloadResult.Success(messages)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during download: ${e.message}", e)
            DownloadResult.Failure(e.message ?: "Onbekende fout bij downloaden")
        }
    }
    
    /**
     * Ensure annotations.json exists in VT5/assets directory.
     * 
     * @param vt5Dir De VT5 root directory
     * @return true als bestand bestaat of succesvol aangemaakt
     */
    private suspend fun ensureAnnotationsFile(vt5Dir: DocumentFile?): Boolean = withContext(Dispatchers.IO) {
        if (vt5Dir == null) return@withContext false
        
        try {
            val assetsDir = vt5Dir.findFile("assets")?.takeIf { it.isDirectory }
                ?: vt5Dir.createDirectory("assets")
                ?: return@withContext false
            
            val existingFile = assetsDir.findFile("annotations.json")
            if (existingFile != null) {
                try {
                    AnnotationsManager.loadCache(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load annotations cache: ${e.message}", e)
                }
                return@withContext true
            }
            
            // Create default annotations.json
            val defaultAnnotations = AnnotationsConfig(
                geslacht = listOf(
                    AnnotationOption("M", "Man"),
                    AnnotationOption("V", "Vrouw"),
                    AnnotationOption("O", "Onbekend")
                ),
                leeftijd = listOf(
                    AnnotationOption("ad", "Adult"),
                    AnnotationOption("imm", "Immatuur"),
                    AnnotationOption("juv", "Juveniel")
                ),
                kleed = listOf(
                    AnnotationOption("zomer", "Zomerkleed"),
                    AnnotationOption("winter", "Winterkleed"),
                    AnnotationOption("overgangonbekend")
                )
            )
            
            val jsonContent = jsonPretty.encodeToString(AnnotationsConfig.serializer(), defaultAnnotations)
            
            val newFile = assetsDir.createFile("application/json", "annotations.json")
                ?: return@withContext false
            
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                outputStream.write(jsonContent.toByteArray())
            }
            
            // Load into cache
            try {
                AnnotationsManager.loadCache(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load annotations cache after creation: ${e.message}", e)
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring annotations file: ${e.message}", e)
            false
        }
    }
    
    /**
     * Data classes voor annotations.json structure.
     */
    @Serializable
    private data class AnnotationsConfig(
        val geslacht: List<AnnotationOption> = emptyList(),
        val leeftijd: List<AnnotationOption> = emptyList(),
        val kleed: List<AnnotationOption> = emptyList()
    )
    
    @Serializable
    private data class AnnotationOption(
        val code: String,
        val label: String = code
    )
}
