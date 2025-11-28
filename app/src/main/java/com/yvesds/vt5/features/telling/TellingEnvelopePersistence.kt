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

/**
 * TellingEnvelopePersistence: Beheert het continu opslaan van de volledige telling envelope.
 * 
 * Bij elke nieuwe waarneming wordt de volledige envelope (metadata + alle records)
 * opgeslagen in een JSON bestand. Dit bestand wordt overschreven bij elke nieuwe waarneming,
 * zodat er altijd een actuele backup beschikbaar is.
 * 
 * Locaties:
 * - SAF: Documents/VT5/counts/active_telling.json
 * - Fallback: filesDir/VT5/counts/active_telling.json
 * 
 * Voordelen:
 * - Geen dataverlies bij app crash of onverwacht afsluiten
 * - Bestand kan later worden gebruikt om telling te hervatten
 * - Bestand komt overeen met wat naar server wordt gestuurd
 */
class TellingEnvelopePersistence(
    private val context: Context,
    private val safHelper: SaFStorageHelper
) {
    companion object {
        private const val TAG = "TellingEnvelopePersist"
        private const val COUNTS_DIR = "counts"
        private const val ACTIVE_FILENAME = "active_telling.json"
        private const val VT5_DIR = "VT5"
        private const val PREFS_NAME = "vt5_prefs"
        private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"
        
        private val PRETTY_JSON: Json by lazy { 
            Json { 
                prettyPrint = true 
                encodeDefaults = true 
            } 
        }
    }
    
    /**
     * Bouw een volledige envelope met de huidige metadata en alle records.
     * 
     * @param records Lijst van alle waarnemingen tot nu toe
     * @return De volledige envelope klaar voor opslag, of null als metadata ontbreekt
     */
    suspend fun buildCurrentEnvelope(records: List<ServerTellingDataItem>): ServerTellingEnvelope? {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedEnvelopeJson = prefs.getString(PREF_SAVED_ENVELOPE_JSON, null)
                
                if (savedEnvelopeJson.isNullOrBlank()) {
                    Log.w(TAG, "Geen opgeslagen envelope metadata gevonden")
                    return@withContext null
                }
                
                val envelopeList = try {
                    VT5App.json.decodeFromString(
                        ListSerializer(ServerTellingEnvelope.serializer()),
                        savedEnvelopeJson
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Kon envelope JSON niet decoderen: ${e.message}", e)
                    return@withContext null
                }
                
                if (envelopeList.isNullOrEmpty()) {
                    Log.w(TAG, "Envelope lijst is leeg")
                    return@withContext null
                }
                
                // Bouw de volledige envelope met alle records
                val baseEnv = envelopeList[0]
                val nrec = records.size
                val nsoort = records.map { it.soortid }.toSet().size
                
                baseEnv.copy(
                    nrec = nrec.toString(),
                    nsoort = nsoort.toString(),
                    data = records
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij bouwen envelope: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Sla de volledige envelope op als JSON bestand.
     * Dit overschrijft het bestaande bestand.
     * 
     * @param records Lijst van alle waarnemingen tot nu toe
     * @return Pad naar opgeslagen bestand, of null bij fout
     */
    suspend fun saveEnvelopeWithRecords(records: List<ServerTellingDataItem>): String? {
        return withContext(Dispatchers.IO) {
            try {
                val envelope = buildCurrentEnvelope(records)
                if (envelope == null) {
                    Log.w(TAG, "Kon geen envelope bouwen - skip opslaan")
                    return@withContext null
                }
                
                val envelopeList = listOf(envelope)
                val prettyJson = PRETTY_JSON.encodeToString(
                    ListSerializer(ServerTellingEnvelope.serializer()),
                    envelopeList
                )
                
                // Probeer eerst SAF
                var savedPath = saveToSaf(prettyJson)
                
                // Fallback naar internal storage
                if (savedPath == null) {
                    savedPath = saveToInternal(prettyJson)
                }
                
                if (savedPath != null) {
                    Log.i(TAG, "Envelope opgeslagen: $savedPath (${records.size} records)")
                } else {
                    Log.w(TAG, "Kon envelope niet opslaan")
                }
                
                savedPath
            } catch (e: Exception) {
                Log.e(TAG, "Fout bij opslaan envelope: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Sla JSON op via SAF naar Documents/VT5/counts/active_telling.json
     */
    private fun saveToSaf(jsonContent: String): String? {
        try {
            var vt5Dir: DocumentFile? = safHelper.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { safHelper.ensureFolders() } catch (_: Exception) {}
                vt5Dir = safHelper.getVt5DirIfExists()
            }
            
            if (vt5Dir == null) {
                Log.w(TAG, "VT5 map niet beschikbaar via SAF")
                return null
            }
            
            // Vind of maak counts directory
            var countsDir = vt5Dir.findFile(COUNTS_DIR)
            if (countsDir == null || !countsDir.isDirectory) {
                countsDir = vt5Dir.createDirectory(COUNTS_DIR)
            }
            if (countsDir == null) {
                Log.w(TAG, "Kon counts map niet maken in SAF")
                return null
            }
            
            // Verwijder bestaand bestand indien aanwezig
            val existingFile = countsDir.findFile(ACTIVE_FILENAME)
            if (existingFile != null) {
                existingFile.delete()
            }
            
            // Maak nieuw bestand
            val doc = countsDir.createFile("application/json", ACTIVE_FILENAME)
            if (doc == null) {
                Log.w(TAG, "Kon active_telling.json niet maken via SAF")
                return null
            }
            
            // Schrijf inhoud
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                out.write(jsonContent.toByteArray(Charsets.UTF_8))
            }
            
            return "Documents/VT5/$COUNTS_DIR/$ACTIVE_FILENAME"
        } catch (e: Exception) {
            Log.w(TAG, "SAF opslaan mislukt: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Fallback: sla op naar internal storage
     */
    private fun saveToInternal(jsonContent: String): String? {
        try {
            val root = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR")
            if (!root.exists()) {
                root.mkdirs()
            }
            
            val file = File(root, ACTIVE_FILENAME)
            file.writeText(jsonContent, Charsets.UTF_8)
            
            return "internal:${file.absolutePath}"
        } catch (e: Exception) {
            Log.w(TAG, "Internal opslaan mislukt: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Laad een opgeslagen envelope van schijf (indien beschikbaar).
     * 
     * @return De opgeslagen envelope, of null als niet gevonden
     */
    suspend fun loadSavedEnvelope(): ServerTellingEnvelope? {
        return withContext(Dispatchers.IO) {
            try {
                // Probeer eerst SAF
                var jsonContent = loadFromSaf()
                
                // Fallback naar internal
                if (jsonContent == null) {
                    jsonContent = loadFromInternal()
                }
                
                if (jsonContent == null) {
                    return@withContext null
                }
                
                val envelopeList = VT5App.json.decodeFromString(
                    ListSerializer(ServerTellingEnvelope.serializer()),
                    jsonContent
                )
                
                if (envelopeList.isNullOrEmpty()) {
                    return@withContext null
                }
                
                Log.i(TAG, "Envelope geladen: ${envelopeList[0].nrec} records")
                envelopeList[0]
            } catch (e: Exception) {
                Log.w(TAG, "Kon envelope niet laden: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Laad JSON van SAF
     */
    private fun loadFromSaf(): String? {
        try {
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return null
            val countsDir = vt5Dir.findFile(COUNTS_DIR) ?: return null
            val file = countsDir.findFile(ACTIVE_FILENAME) ?: return null
            
            return context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF laden mislukt: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Laad JSON van internal storage
     */
    private fun loadFromInternal(): String? {
        try {
            val file = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR/$ACTIVE_FILENAME")
            if (!file.exists()) {
                return null
            }
            return file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Internal laden mislukt: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Verwijder het active_telling.json bestand (na succesvolle upload).
     */
    suspend fun clearSavedEnvelope() {
        withContext(Dispatchers.IO) {
            try {
                // Verwijder SAF bestand
                val vt5Dir = safHelper.getVt5DirIfExists()
                if (vt5Dir != null) {
                    val countsDir = vt5Dir.findFile(COUNTS_DIR)
                    val file = countsDir?.findFile(ACTIVE_FILENAME)
                    if (file != null) {
                        file.delete()
                        Log.i(TAG, "SAF active_telling.json verwijderd")
                    }
                }
                
                // Verwijder internal bestand
                val internalFile = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR/$ACTIVE_FILENAME")
                if (internalFile.exists()) {
                    internalFile.delete()
                    Log.i(TAG, "Internal active_telling.json verwijderd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kon active_telling.json niet verwijderen: ${e.message}", e)
            }
        }
    }
    
    /**
     * Controleer of er een opgeslagen telling beschikbaar is.
     */
    suspend fun hasSavedEnvelope(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check SAF
                val vt5Dir = safHelper.getVt5DirIfExists()
                if (vt5Dir != null) {
                    val countsDir = vt5Dir.findFile(COUNTS_DIR)
                    val file = countsDir?.findFile(ACTIVE_FILENAME)
                    if (file != null && file.exists()) {
                        return@withContext true
                    }
                }
                
                // Check internal
                val internalFile = File(context.filesDir, "$VT5_DIR/$COUNTS_DIR/$ACTIVE_FILENAME")
                return@withContext internalFile.exists()
            } catch (e: Exception) {
                Log.w(TAG, "Kon niet controleren op opgeslagen envelope: ${e.message}", e)
                false
            }
        }
    }
}
