package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.ServerTellingEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TellingBeheerToolset: Centrale toolset voor het beheren van opgeslagen tellingen.
 * 
 * Deze klasse biedt functionaliteit voor:
 * - Bestandsbeheer: lijst, laden, opslaan, verwijderen van telling bestanden
 * - Record operaties: aanpassen, toevoegen, verwijderen van data records
 * - Metadata operaties: wijzigen van envelope metadata
 * 
 * Locaties:
 * - SAF: Documents/VT5/counts/
 * - Fallback: filesDir/VT5/counts/
 * 
 * Bestandsformaat: JSON array met één ServerTellingEnvelope
 * 
 * @param context Android context voor file operaties
 * @param safHelper Helper voor Storage Access Framework operaties
 */
class TellingBeheerToolset(
    private val context: Context,
    private val safHelper: SaFStorageHelper = SaFStorageHelper(context)
) {
    companion object {
        private const val TAG = "TellingBeheerToolset"
        private const val COUNTS_DIR = "counts"
        private const val VT5_DIR = "VT5"
        private const val ACTIVE_FILENAME = "active_telling.json"
        private const val MAX_FILENAME_ID_LENGTH = 50
        
        private val PRETTY_JSON: Json by lazy { 
            Json { 
                prettyPrint = true 
                encodeDefaults = true 
            } 
        }
    }
    
    // ========================================================================
    // BESTANDSBEHEER
    // ========================================================================
    
    /**
     * Lijst alle opgeslagen telling bestanden in de counts map.
     * 
     * @return Lijst van TellingFileInfo objecten, gesorteerd op laatste wijzigingsdatum (nieuwste eerst)
     */
    suspend fun listSavedTellingen(): List<TellingFileInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<TellingFileInfo>()
        
        // Probeer SAF eerste
        try {
            val countsDir = getCountsDirSaf()
            if (countsDir != null) {
                countsDir.listFiles()
                    .filter { it.isFile && it.name?.endsWith(".json") == true }
                    .forEach { docFile ->
                        val info = parseFileInfo(docFile)
                        if (info != null) {
                            result.add(info)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF listSavedTellingen failed: ${e.message}", e)
        }
        
        // Fallback: internal storage
        try {
            val internalDir = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR")
            if (internalDir.exists() && internalDir.isDirectory) {
                internalDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".json") }
                    ?.forEach { file ->
                        val info = parseFileInfoInternal(file)
                        if (info != null && result.none { it.filename == info.filename }) {
                            result.add(info)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Internal listSavedTellingen failed: ${e.message}", e)
        }
        
        // Sorteer op laatste wijzigingsdatum (nieuwste eerst)
        result.sortedByDescending { it.lastModified }
    }
    
    /**
     * Laad een telling uit een bestand.
     * 
     * @param filename Naam van het bestand (bijv. "telling_1_12345_20251129.json")
     * @return ServerTellingEnvelope of null als laden mislukt
     */
    suspend fun loadTelling(filename: String): ServerTellingEnvelope? = withContext(Dispatchers.IO) {
        // Valideer bestandsnaam (geen path traversal)
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            Log.w(TAG, "Invalid filename rejected: $filename")
            return@withContext null
        }
        
        var jsonContent: String? = null
        
        // Probeer SAF eerste
        try {
            val countsDir = getCountsDirSaf()
            val file = countsDir?.findFile(filename)
            if (file != null && file.exists()) {
                jsonContent = context.contentResolver.openInputStream(file.uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF loadTelling failed: ${e.message}", e)
        }
        
        // Fallback: internal storage
        if (jsonContent == null) {
            try {
                val file = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR/$filename")
                if (file.exists()) {
                    jsonContent = file.readText(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Internal loadTelling failed: ${e.message}", e)
            }
        }
        
        // Parse JSON
        if (jsonContent.isNullOrBlank()) {
            Log.w(TAG, "No content found for: $filename")
            return@withContext null
        }
        
        try {
            val envelopeList = VT5App.json.decodeFromString(
                ListSerializer(ServerTellingEnvelope.serializer()),
                jsonContent
            )
            if (envelopeList.isNotEmpty()) {
                Log.i(TAG, "Loaded telling: $filename with ${envelopeList[0].data.size} records")
                return@withContext envelopeList[0]
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse loadTelling failed: ${e.message}", e)
        }
        
        null
    }
    
    /**
     * Sla een telling op naar een bestand.
     * 
     * @param envelope De envelope om op te slaan
     * @param filename Naam van het bestand (optioneel, genereert automatische naam indien null)
     * @return true als opslaan succesvol, anders false
     */
    suspend fun saveTelling(envelope: ServerTellingEnvelope, filename: String? = null): Boolean = withContext(Dispatchers.IO) {
        val targetFilename = filename ?: generateFilename(envelope)
        
        // Valideer bestandsnaam (geen path traversal)
        if (targetFilename.contains("..") || targetFilename.contains("/") || targetFilename.contains("\\")) {
            Log.w(TAG, "Invalid filename rejected: $targetFilename")
            return@withContext false
        }
        
        // Bereken nrec en nsoort
        val updatedEnvelope = envelope.copy(
            nrec = envelope.data.size.toString(),
            nsoort = envelope.data.map { it.soortid }.toSet().size.toString()
        )
        
        val envelopeList = listOf(updatedEnvelope)
        val prettyJson = try {
            PRETTY_JSON.encodeToString(
                ListSerializer(ServerTellingEnvelope.serializer()),
                envelopeList
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON encode failed: ${e.message}", e)
            return@withContext false
        }
        
        // Probeer SAF eerste
        var savedToSaf = false
        try {
            val countsDir = ensureCountsDirSaf()
            if (countsDir != null) {
                // Verwijder bestaand bestand indien aanwezig
                val existingFile = countsDir.findFile(targetFilename)
                existingFile?.delete()
                
                // Maak nieuw bestand
                val doc = countsDir.createFile("application/json", targetFilename)
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                        out.write(prettyJson.toByteArray(Charsets.UTF_8))
                    }
                    savedToSaf = true
                    Log.i(TAG, "Saved telling to SAF: $targetFilename")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF saveTelling failed: ${e.message}", e)
        }
        
        // Fallback: internal storage
        if (!savedToSaf) {
            try {
                val dir = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, targetFilename)
                file.writeText(prettyJson, Charsets.UTF_8)
                Log.i(TAG, "Saved telling to internal: $targetFilename")
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Internal saveTelling failed: ${e.message}", e)
                return@withContext false
            }
        }
        
        savedToSaf
    }
    
    /**
     * Verwijder een telling bestand.
     * 
     * @param filename Naam van het bestand om te verwijderen
     * @return true als verwijderen succesvol, anders false
     */
    suspend fun deleteTelling(filename: String): Boolean = withContext(Dispatchers.IO) {
        // Valideer bestandsnaam (geen path traversal)
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            Log.w(TAG, "Invalid filename rejected: $filename")
            return@withContext false
        }
        
        var deletedFromSaf = false
        var deletedFromInternal = false
        
        // Probeer SAF
        try {
            val countsDir = getCountsDirSaf()
            val file = countsDir?.findFile(filename)
            if (file != null && file.exists()) {
                deletedFromSaf = file.delete()
                if (deletedFromSaf) {
                    Log.i(TAG, "Deleted from SAF: $filename")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF deleteTelling failed: ${e.message}", e)
        }
        
        // Probeer internal storage
        try {
            val file = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR/$filename")
            if (file.exists()) {
                deletedFromInternal = file.delete()
                if (deletedFromInternal) {
                    Log.i(TAG, "Deleted from internal: $filename")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Internal deleteTelling failed: ${e.message}", e)
        }
        
        deletedFromSaf || deletedFromInternal
    }
    
    // ========================================================================
    // RECORD OPERATIES
    // ========================================================================
    
    /**
     * Update een bestaand record in de envelope.
     * 
     * @param envelope De huidige envelope
     * @param index Index van het record om bij te werken (0-based)
     * @param updated Het bijgewerkte record
     * @return Nieuwe envelope met het bijgewerkte record
     * @throws IndexOutOfBoundsException als index ongeldig is
     */
    fun updateRecord(
        envelope: ServerTellingEnvelope,
        index: Int,
        updated: ServerTellingDataItem
    ): ServerTellingEnvelope {
        require(index >= 0 && index < envelope.data.size) {
            "Index $index is out of bounds (0..${envelope.data.size - 1})"
        }
        
        val newData = envelope.data.toMutableList()
        newData[index] = updated
        
        return envelope.copy(
            data = newData,
            nrec = newData.size.toString(),
            nsoort = newData.map { it.soortid }.toSet().size.toString()
        )
    }
    
    /**
     * Voeg een nieuw record toe aan de envelope.
     * 
     * @param envelope De huidige envelope
     * @param record Het nieuwe record om toe te voegen
     * @param generateId Of een nieuw idLocal moet worden gegenereerd (default: true)
     * @return Nieuwe envelope met het toegevoegde record
     */
    fun addRecord(
        envelope: ServerTellingEnvelope,
        record: ServerTellingDataItem,
        generateId: Boolean = true
    ): ServerTellingEnvelope {
        val newRecord = if (generateId) {
            // Generate new ID: find max numeric ID and increment
            // Non-numeric IDs are treated as 0 to ensure we always get a valid next ID
            val existingNumericIds = envelope.data.mapNotNull { it.idLocal.toIntOrNull() }
            val maxId = existingNumericIds.maxOrNull() ?: 0
            val newId = (maxId + 1).toString()
            record.copy(
                idLocal = newId,
                tellingid = envelope.tellingid,
                groupid = newId,
                tijdstip = if (record.tijdstip.isBlank()) {
                    (System.currentTimeMillis() / 1000L).toString()
                } else {
                    record.tijdstip
                }
            )
        } else {
            record
        }
        
        val newData = envelope.data + newRecord
        
        return envelope.copy(
            data = newData,
            nrec = newData.size.toString(),
            nsoort = newData.map { it.soortid }.toSet().size.toString()
        )
    }
    
    /**
     * Verwijder een record uit de envelope.
     * 
     * @param envelope De huidige envelope
     * @param index Index van het record om te verwijderen (0-based)
     * @return Nieuwe envelope zonder het verwijderde record
     * @throws IndexOutOfBoundsException als index ongeldig is
     */
    fun deleteRecord(envelope: ServerTellingEnvelope, index: Int): ServerTellingEnvelope {
        require(index >= 0 && index < envelope.data.size) {
            "Index $index is out of bounds (0..${envelope.data.size - 1})"
        }
        
        val newData = envelope.data.toMutableList()
        newData.removeAt(index)
        
        return envelope.copy(
            data = newData,
            nrec = newData.size.toString(),
            nsoort = newData.map { it.soortid }.toSet().size.toString()
        )
    }
    
    /**
     * Verwijder meerdere records uit de envelope (in volgorde van hoog naar laag om index issues te voorkomen).
     * 
     * @param envelope De huidige envelope
     * @param indices Indices van records om te verwijderen
     * @return Nieuwe envelope zonder de verwijderde records
     */
    fun deleteRecords(envelope: ServerTellingEnvelope, indices: List<Int>): ServerTellingEnvelope {
        // Sorteer van hoog naar laag om index-verschuiving te voorkomen
        val sortedIndices = indices.distinct().sortedDescending()
        
        var result = envelope
        for (index in sortedIndices) {
            if (index >= 0 && index < result.data.size) {
                result = deleteRecord(result, index)
            }
        }
        return result
    }
    
    // ========================================================================
    // METADATA OPERATIES
    // ========================================================================
    
    /**
     * Update metadata velden in de envelope.
     * 
     * @param envelope De huidige envelope
     * @param updates De metadata updates (null waarden worden niet gewijzigd)
     * @return Nieuwe envelope met bijgewerkte metadata
     */
    fun updateMetadata(
        envelope: ServerTellingEnvelope,
        updates: MetadataUpdates
    ): ServerTellingEnvelope {
        return envelope.copy(
            tellers = updates.tellers ?: envelope.tellers,
            opmerkingen = updates.opmerkingen ?: envelope.opmerkingen,
            windrichting = updates.windrichting ?: envelope.windrichting,
            windkracht = updates.windkracht ?: envelope.windkracht,
            temperatuur = updates.temperatuur ?: envelope.temperatuur,
            bewolking = updates.bewolking ?: envelope.bewolking,
            bewolkinghoogte = updates.bewolkinghoogte ?: envelope.bewolkinghoogte,
            neerslag = updates.neerslag ?: envelope.neerslag,
            duurneerslag = updates.duurneerslag ?: envelope.duurneerslag,
            zicht = updates.zicht ?: envelope.zicht,
            hpa = updates.hpa ?: envelope.hpa,
            typetelling = updates.typetelling ?: envelope.typetelling,
            weer = updates.weer ?: envelope.weer,
            tellersactief = updates.tellersactief ?: envelope.tellersactief,
            tellersaanwezig = updates.tellersaanwezig ?: envelope.tellersaanwezig,
            metersnet = updates.metersnet ?: envelope.metersnet,
            geluid = updates.geluid ?: envelope.geluid,
            equipment = updates.equipment ?: envelope.equipment,
            begintijd = updates.begintijd ?: envelope.begintijd,
            eindtijd = updates.eindtijd ?: envelope.eindtijd,
            telpostid = updates.telpostid ?: envelope.telpostid
        )
    }
    
    /**
     * Update de opmerkingen van een specifiek record.
     * 
     * @param envelope De huidige envelope
     * @param index Index van het record
     * @param opmerkingen De nieuwe opmerkingen
     * @return Nieuwe envelope met bijgewerkte record opmerkingen
     */
    fun updateRecordOpmerkingen(
        envelope: ServerTellingEnvelope,
        index: Int,
        opmerkingen: String
    ): ServerTellingEnvelope {
        require(index >= 0 && index < envelope.data.size) {
            "Index $index is out of bounds (0..${envelope.data.size - 1})"
        }
        
        val updatedRecord = envelope.data[index].copy(opmerkingen = opmerkingen)
        return updateRecord(envelope, index, updatedRecord)
    }
    
    /**
     * Update het aantal van een specifiek record.
     * 
     * @param envelope De huidige envelope
     * @param index Index van het record
     * @param aantal Het nieuwe aantal
     * @return Nieuwe envelope met bijgewerkt aantal
     */
    fun updateRecordAantal(
        envelope: ServerTellingEnvelope,
        index: Int,
        aantal: Int
    ): ServerTellingEnvelope {
        require(index >= 0 && index < envelope.data.size) {
            "Index $index is out of bounds (0..${envelope.data.size - 1})"
        }
        require(aantal >= 0) { "Aantal moet 0 of groter zijn" }
        
        val updatedRecord = envelope.data[index].copy(
            aantal = aantal.toString(),
            totaalaantal = aantal.toString()
        )
        return updateRecord(envelope, index, updatedRecord)
    }
    
    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================
    
    /**
     * Get the counts directory from SAF.
     * Uses SaFStorageHelper.getCountsDir() for consistency.
     */
    private fun getCountsDirSaf(): DocumentFile? {
        return try {
            safHelper.getCountsDir()
        } catch (e: Exception) {
            Log.w(TAG, "getCountsDirSaf failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * Ensure counts directory exists and return it.
     * Creates the directory if it doesn't exist.
     */
    private fun ensureCountsDirSaf(): DocumentFile? {
        return try {
            // First try to get existing counts dir
            safHelper.getCountsDir()?.let { return it }
            
            // If not found, ensure all VT5 folders exist (including counts)
            if (!safHelper.ensureFolders()) {
                Log.w(TAG, "ensureFolders failed")
                return null
            }
            
            // Now try to get counts dir again
            safHelper.getCountsDir()
        } catch (e: Exception) {
            Log.w(TAG, "ensureCountsDirSaf failed: ${e.message}", e)
            null
        }
    }
    
    private fun parseFileInfo(docFile: DocumentFile): TellingFileInfo? {
        return try {
            val filename = docFile.name ?: return null
            val isActive = filename == ACTIVE_FILENAME
            
            // Probeer basis info uit bestandsnaam te halen
            // Format: telling_<tellingId>_<onlineId>_<timestamp>.json
            val parts = filename.removeSuffix(".json").split("_")
            val tellingId = if (parts.size >= 2 && parts[0] == "telling") parts[1] else null
            val onlineId = if (parts.size >= 3 && parts[0] == "telling") parts[2] else null
            val timestamp = if (parts.size >= 4 && parts[0] == "telling") {
                parts.drop(3).joinToString("_")
            } else null
            
            // Probeer envelope te laden voor nrec/nsoort (light-weight: alleen eerste envelope metadata)
            var nrec = 0
            var nsoort = 0
            var telpost: String? = null
            try {
                val content = context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
                if (!content.isNullOrBlank()) {
                    val envelopeList = VT5App.json.decodeFromString(
                        ListSerializer(ServerTellingEnvelope.serializer()),
                        content
                    )
                    if (envelopeList.isNotEmpty()) {
                        nrec = envelopeList[0].nrec.toIntOrNull() ?: envelopeList[0].data.size
                        nsoort = envelopeList[0].nsoort.toIntOrNull() ?: envelopeList[0].data.map { it.soortid }.toSet().size
                        telpost = envelopeList[0].telpostid.takeIf { it.isNotBlank() }
                    }
                }
            } catch (_: Exception) {
                // Ignore parse errors for file list
            }
            
            TellingFileInfo(
                filename = filename,
                tellingId = tellingId,
                onlineId = onlineId,
                telpost = telpost,
                timestamp = timestamp,
                nrec = nrec,
                nsoort = nsoort,
                fileSize = docFile.length(),
                lastModified = docFile.lastModified(),
                isActive = isActive
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseFileInfo failed: ${e.message}", e)
            null
        }
    }
    
    private fun parseFileInfoInternal(file: File): TellingFileInfo? {
        return try {
            val filename = file.name
            val isActive = filename == ACTIVE_FILENAME
            
            // Probeer basis info uit bestandsnaam te halen
            val parts = filename.removeSuffix(".json").split("_")
            val tellingId = if (parts.size >= 2 && parts[0] == "telling") parts[1] else null
            val onlineId = if (parts.size >= 3 && parts[0] == "telling") parts[2] else null
            val timestamp = if (parts.size >= 4 && parts[0] == "telling") {
                parts.drop(3).joinToString("_")
            } else null
            
            // Probeer envelope te laden voor nrec/nsoort
            var nrec = 0
            var nsoort = 0
            var telpost: String? = null
            try {
                val content = file.readText(Charsets.UTF_8)
                val envelopeList = VT5App.json.decodeFromString(
                    ListSerializer(ServerTellingEnvelope.serializer()),
                    content
                )
                if (envelopeList.isNotEmpty()) {
                    nrec = envelopeList[0].nrec.toIntOrNull() ?: envelopeList[0].data.size
                    nsoort = envelopeList[0].nsoort.toIntOrNull() ?: envelopeList[0].data.map { it.soortid }.toSet().size
                    telpost = envelopeList[0].telpostid.takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
                // Ignore parse errors for file list
            }
            
            TellingFileInfo(
                filename = filename,
                tellingId = tellingId,
                onlineId = onlineId,
                telpost = telpost,
                timestamp = timestamp,
                nrec = nrec,
                nsoort = nsoort,
                fileSize = file.length(),
                lastModified = file.lastModified(),
                isActive = isActive
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseFileInfoInternal failed: ${e.message}", e)
            null
        }
    }
    
    private fun generateFilename(envelope: ServerTellingEnvelope): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tellingId = sanitizeFilename(envelope.tellingid.takeIf { it.isNotBlank() } ?: "unknown")
        val onlineId = sanitizeFilename(envelope.onlineid.takeIf { it.isNotBlank() } ?: "local")
        return "telling_${tellingId}_${onlineId}_$timestamp.json"
    }
    
    /**
     * Sanitize een string voor gebruik in een bestandsnaam.
     * Vervangt ongeldige tekens en beperkt lengte tot MAX_FILENAME_ID_LENGTH.
     */
    private fun sanitizeFilename(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(MAX_FILENAME_ID_LENGTH)
    }
}
