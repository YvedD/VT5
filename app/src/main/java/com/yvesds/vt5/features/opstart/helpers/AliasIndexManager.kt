package com.yvesds.vt5.features.opstart.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Helper class voor alias index lifecycle management.
 * 
 * Verantwoordelijkheden:
 * - Checksum computation van server files
 * - Metadata (alias_master.meta.json) read/write
 * - Conditional regeneration logic
 * - Force rebuild functionality
 * 
 * Gebruik:
 * ```kotlin
 * val aliasManager = AliasIndexManager(context, safHelper)
 * if (aliasManager.needsRegeneration(vt5Dir)) {
 *     val result = aliasManager.regenerateIndex(vt5Dir)
 * }
 * ```
 */
class AliasIndexManager(
    private val context: Context,
    private val safHelper: SaFStorageHelper
) {
    private val jsonPretty = Json { 
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    
    companion object {
        private const val TAG = "AliasIndexManager"
        
        // Server files included in checksum computation
        private val REQUIRED_SERVER_FILES = listOf(
            "checkuser.json",
            "codes.json",
            "protocolinfo.json",
            "protocolspecies.json",
            "site_heights.json",
            "site_locations.json",
            "site_species.json",
            "sites.json",
            "species.json"
        )
    }
    
    /**
     * Metadata structure voor alias_master.meta.json.
     */
    @Serializable
    data class AliasMasterMeta(
        val sourceChecksum: String,
        val sourceFiles: List<String>,
        val timestamp: String
    )
    
    /**
     * Sealed class voor regeneration resultaten.
     */
    sealed class RegenerationResult {
        object Success : RegenerationResult()
        object AlreadyUpToDate : RegenerationResult()
        data class Failure(val error: String) : RegenerationResult()
    }
    
    /**
     * Controleer of alias index regeneratie nodig is.
     * 
     * @param vt5Dir De VT5 root directory
     * @return true als regeneratie nodig is
     */
    suspend fun needsRegeneration(vt5Dir: DocumentFile?): Boolean = withContext(Dispatchers.IO) {
        if (vt5Dir == null) return@withContext false
        
        try {
            val newChecksum = computeServerFilesChecksum(vt5Dir)
            val oldMeta = readMetadata(vt5Dir)
            val oldChecksum = oldMeta?.sourceChecksum
            
            val indexPresent = isIndexPresent(vt5Dir)
            
            // Regenerate if:
            // - No old checksum exists
            // - Checksum changed
            // - Index files missing
            val needsRegen = (oldChecksum == null) || (oldChecksum != newChecksum) || !indexPresent
            
            
            needsRegen
        } catch (e: Exception) {
            Log.w(TAG, "Error checking regeneration need: ${e.message}", e)
            false
        }
    }
    
    /**
     * Regenereer alias index indien nodig.
     * 
     * @param vt5Dir De VT5 root directory
     * @param timestamp ISO timestamp voor metadata
     * @param onProgress Progress callback
     * @return RegenerationResult
     */
    suspend fun regenerateIndexIfNeeded(
        vt5Dir: DocumentFile?,
        timestamp: String,
        onProgress: (String) -> Unit = {}
    ): RegenerationResult = withContext(Dispatchers.IO) {
        if (vt5Dir == null) {
            return@withContext RegenerationResult.Failure("VT5 directory niet gevonden")
        }
        
        try {
            if (!needsRegeneration(vt5Dir)) {
                return@withContext RegenerationResult.AlreadyUpToDate
            }
            
            onProgress("Alias index regenereren...")
            
            // Remove existing files to force regeneration
            removeExistingIndexFiles(vt5Dir)
            
            // Initialize AliasManager (will regenerate seed)
            AliasManager.initialize(context, safHelper)
            
            // Compute and save new checksum
            val newChecksum = computeServerFilesChecksum(vt5Dir)
            val meta = AliasMasterMeta(
                sourceChecksum = newChecksum,
                sourceFiles = REQUIRED_SERVER_FILES,
                timestamp = timestamp
            )
            writeMetadata(vt5Dir, meta)
            
            RegenerationResult.Success
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during alias regeneration: ${e.message}", e)
            RegenerationResult.Failure(e.message ?: "Onbekende fout bij alias regeneratie")
        }
    }
    
    /**
     * Force rebuild van alias index (onvoorwaardelijk).
     * 
     * @param vt5Dir De VT5 root directory
     * @param timestamp ISO timestamp voor metadata
     * @return RegenerationResult
     */
    suspend fun forceRebuildIndex(
        vt5Dir: DocumentFile?,
        timestamp: String
    ): RegenerationResult = withContext(Dispatchers.IO) {
        if (vt5Dir == null) {
            return@withContext RegenerationResult.Failure("VT5 directory niet gevonden")
        }
        
        try {
            
            // Remove existing files
            removeExistingIndexFiles(vt5Dir)
            
            // Regenerate
            AliasManager.initialize(context, safHelper)
            
            // Save metadata
            val newChecksum = computeServerFilesChecksum(vt5Dir)
            val meta = AliasMasterMeta(
                sourceChecksum = newChecksum,
                sourceFiles = REQUIRED_SERVER_FILES,
                timestamp = timestamp
            )
            writeMetadata(vt5Dir, meta)
            
            RegenerationResult.Success
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during force rebuild: ${e.message}", e)
            RegenerationResult.Failure(e.message ?: "Onbekende fout bij force rebuild")
        }
    }
    
    /**
     * Check if alias index is present (both master.json and cbor.gz).
     * 
     * @param vt5Dir De VT5 root directory
     * @return true als index compleet aanwezig is
     */
    fun isIndexPresent(vt5Dir: DocumentFile?): Boolean {
        if (vt5Dir == null) return false
        
        val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return false
        val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: return false
        
        val master = assets.findFile("alias_master.json")?.takeIf { it.isFile }
        val cbor = binaries.findFile("aliases_optimized.cbor.gz")?.takeIf { it.isFile }
        
        return master != null && cbor != null
    }
    
    /**
     * Compute SHA-256 checksum over concatenated server files.
     */
    private fun computeServerFilesChecksum(vt5Dir: DocumentFile): String {
        return try {
            val serverDir = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: return ""
            val baos = java.io.ByteArrayOutputStream()
            
            for (name in REQUIRED_SERVER_FILES) {
                val file = serverDir.findFile("$name.json") ?: serverDir.findFile(name)
                if (file != null && file.isFile) {
                    context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        inputStream.copyTo(baos)
                    }
                }
            }
            
            val bytes = baos.toByteArray()
            sha256Hex(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "computeServerFilesChecksum failed: ${e.message}")
            ""
        }
    }
    
    /**
     * Convert bytes to SHA-256 hex string.
     */
    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Read alias metadata from alias_master.meta.json.
     */
    private fun readMetadata(vt5Dir: DocumentFile): AliasMasterMeta? {
        return try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return null
            val metaDoc = assets.findFile("alias_master.meta.json")?.takeIf { it.isFile } ?: return null
            
            val text = context.contentResolver.openInputStream(metaDoc.uri)?.use { inputStream ->
                inputStream.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            
            jsonPretty.decodeFromString<AliasMasterMeta>(text)
        } catch (e: Exception) {
            Log.w(TAG, "readMetadata failed: ${e.message}")
            null
        }
    }
    
    /**
     * Write alias metadata to alias_master.meta.json.
     */
    private fun writeMetadata(vt5Dir: DocumentFile, meta: AliasMasterMeta) {
        try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory }
                ?: vt5Dir.createDirectory("assets")
                ?: return
            
            assets.findFile("alias_master.meta.json")?.delete()
            val metaDoc = assets.createFile("application/json", "alias_master.meta.json") ?: return
            
            val jsonText = jsonPretty.encodeToString(meta)
            
            context.contentResolver.openOutputStream(metaDoc.uri, "w")?.use { outputStream ->
                outputStream.write(jsonText.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "writeMetadata failed: ${e.message}")
        }
    }
    
    /**
     * Remove existing alias index files to force regeneration.
     */
    private fun removeExistingIndexFiles(vt5Dir: DocumentFile) {
        try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory }
            assets?.findFile("alias_master.json")?.delete()
            assets?.findFile("alias_master.meta.json")?.delete()
            
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory }
            binaries?.findFile("aliases_optimized.cbor.gz")?.delete()
            
        } catch (e: Exception) {
            Log.w(TAG, "removeExistingIndexFiles failed: ${e.message}")
        }
    }
}
