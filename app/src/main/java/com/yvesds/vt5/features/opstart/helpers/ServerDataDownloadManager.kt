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
            
            // Create default annotations.json with full options matching annotations sample
            val defaultAnnotations = AnnotationsConfig(
                leeftijd = listOf(
                    AnnotationOption("adult", "leeftijd", "A"),
                    AnnotationOption("juveniel", "leeftijd", "J"),
                    AnnotationOption(">1kj", "leeftijd", "I"),
                    AnnotationOption("1kj", "leeftijd", "1"),
                    AnnotationOption("2kj", "leeftijd", "2"),
                    AnnotationOption("3kj", "leeftijd", "3"),
                    AnnotationOption("4kj", "leeftijd", "4"),
                    AnnotationOption("niet juv.", "leeftijd", "Non-Juv")
                ),
                geslacht = listOf(
                    AnnotationOption("man", "geslacht", "M"),
                    AnnotationOption("vrouw", "geslacht", "F"),
                    AnnotationOption("vrouwkleed", "geslacht", "FC")
                ),
                kleed = listOf(
                    AnnotationOption("zomerkleed", "kleed", "B"),
                    AnnotationOption("winterkleed", "kleed", "W"),
                    AnnotationOption("man", "kleed", "M"),
                    AnnotationOption("vrouw", "kleed", "F"),
                    AnnotationOption("licht", "kleed", "L"),
                    AnnotationOption("donker", "kleed", "D"),
                    AnnotationOption("eclips", "kleed", "E"),
                    AnnotationOption("intermediar", "kleed", "I")
                ),
                teltype = listOf(
                    AnnotationOption("Handteller", "teltype_C", "C")
                ),
                height = listOf(
                    AnnotationOption("<25m", "height", "<25m"),
                    AnnotationOption("<50m", "height", "<50m"),
                    AnnotationOption("50-100m", "height", "50-100m"),
                    AnnotationOption("100-200m", "height", "100-200m"),
                    AnnotationOption(">200m", "height", ">200m")
                ),
                location = listOf(
                    AnnotationOption("zee", "location", "zee"),
                    AnnotationOption("branding", "location", "branding"),
                    AnnotationOption("duinen", "location", "duinen"),
                    AnnotationOption("binnenkant", "location", "binnenkant"),
                    AnnotationOption("polders", "location", "polders"),
                    AnnotationOption("bos", "location", "bos"),
                    AnnotationOption("over water", "location", "over water")
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
     * Matches the format used by AnnotationsManager: { tekst, veld, waarde }
     */
    @Serializable
    private data class AnnotationsConfig(
        val leeftijd: List<AnnotationOption> = emptyList(),
        val geslacht: List<AnnotationOption> = emptyList(),
        val kleed: List<AnnotationOption> = emptyList(),
        val teltype: List<AnnotationOption> = emptyList(),
        val height: List<AnnotationOption> = emptyList(),
        val location: List<AnnotationOption> = emptyList()
    )
    
    @Serializable
    private data class AnnotationOption(
        val tekst: String,
        val veld: String,
        val waarde: String? = null
    )
}
