@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository voor het beheren van alias-data voor soorten
 */
class AliasRepository(private val context: Context) {

    companion object {
        private const val TAG = "AliasRepository"
        private const val ALIAS_JSON_FILE = "aliases.json"
        private const val ALIAS_CSV_FILE = "aliasmapping.csv"

        // Singleton instantie
        @Volatile private var INSTANCE: AliasRepository? = null

        fun getInstance(context: Context): AliasRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AliasRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // In-memory cache voor aliassen
    private val aliasCache = ConcurrentHashMap<String, AliasEntry>()

    // Omgekeerde mapping van alias -> soortId voor snelle lookups
    private val aliasToSpeciesIdMap = ConcurrentHashMap<String, String>()

    // Flag die aangeeft of de data is geladen
    private var isDataLoaded = false

    /**
     * Laad alle alias data (asynchroon)
     */
    suspend fun loadAliasData(): Boolean = withContext(Dispatchers.IO) {
        if (isDataLoaded) return@withContext true

        try {
            // Probeer eerst JSON te laden
            val jsonSuccess = loadFromJson()

            // Als JSON niet beschikbaar is, probeer CSV
            if (!jsonSuccess) {
                val csvSuccess = loadFromCsv()
                if (!csvSuccess) {
                    Log.w(TAG, "Failed to load alias data from both JSON and CSV")
                    return@withContext false
                }
            }

            // Bouw omgekeerde mapping op
            buildReverseMapping()

            isDataLoaded = true
            Log.d(TAG, "Loaded ${aliasCache.size} species with aliases")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading alias data", e)
            return@withContext false
        }
    }

    /**
     * Zoek een soort-ID op basis van een alias
     * @param text De tekst om te zoeken (case-insensitive)
     * @return Het soortId als gevonden, anders null
     */
    fun findSpeciesIdByAlias(text: String): String? {
        if (!isDataLoaded) return null

        val normalizedText = text.trim().lowercase()
        return aliasToSpeciesIdMap[normalizedText]
    }

    /**
     * Geef alle aliassen voor een specifieke soortId
     * @param soortId De soortId om te zoeken
     * @return Een AliasEntry als gevonden, anders null
     */
    fun getAliasesForSpecies(soortId: String): AliasEntry? {
        if (!isDataLoaded) return null
        return aliasCache[soortId]
    }

    /**
     * Geef alle aliassen
     */
    fun getAllAliases(): Map<String, AliasEntry> {
        return aliasCache.toMap()
    }

    /**
     * Laad aliassen vanuit JSON bestand
     */
    private fun loadFromJson(): Boolean {
        try {
            val safHelper = SaFStorageHelper(context)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false

            val serverDataDir = vt5Dir.findFile("serverdata") ?: return false
            val aliasFile = serverDataDir.findFile(ALIAS_JSON_FILE)

            if (aliasFile == null || !aliasFile.exists()) {
                Log.d(TAG, "Alias JSON file not found: $ALIAS_JSON_FILE")
                return false
            }

            val inputStream = context.contentResolver.openInputStream(aliasFile.uri) ?: return false
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            // Parse JSON met kotlinx.serialization
            val aliasData = Json.decodeFromString<AliasData>(jsonString)

            // Vul cache
            aliasData.aliases.forEach { entry ->
                aliasCache[entry.soortId] = entry
            }

            Log.d(TAG, "Successfully loaded aliases from JSON: ${aliasCache.size} entries")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading aliases from JSON", e)
            return false
        }
    }

    /**
     * Laad aliassen vanuit CSV bestand
     */
    private fun loadFromCsv(): Boolean {
        try {
            val safHelper = SaFStorageHelper(context)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false

            val serverDataDir = vt5Dir.findFile("serverdata") ?: return false
            val aliasFile = serverDataDir.findFile(ALIAS_CSV_FILE)

            if (aliasFile == null || !aliasFile.exists()) {
                Log.d(TAG, "Alias CSV file not found: $ALIAS_CSV_FILE")
                return false
            }

            val inputStream = context.contentResolver.openInputStream(aliasFile.uri) ?: return false

            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    var count = 0

                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue

                        val parts = line!!.split(";")
                        if (parts.size < 3) continue

                        val soortId = parts[0].trim()
                        val canonicalName = parts[1].trim().lowercase()
                        val displayName = parts[2].trim()

                        val aliases = if (parts.size > 3) {
                            parts.subList(3, parts.size)
                                .filter { it.isNotBlank() }
                                .map { it.trim().lowercase() }
                        } else {
                            emptyList()
                        }

                        aliasCache[soortId] = AliasEntry(soortId, canonicalName, displayName, aliases)
                        count++
                    }

                    Log.d(TAG, "Successfully loaded aliases from CSV: $count entries")
                    return count > 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading aliases from CSV", e)
            return false
        }
    }

    /**
     * Bouw omgekeerde mapping op van alias -> soortId voor snelle lookups
     */
    private fun buildReverseMapping() {
        aliasToSpeciesIdMap.clear()

        for ((soortId, entry) in aliasCache) {
            // Voeg canonical name toe
            aliasToSpeciesIdMap[entry.canonicalName] = soortId

            // Voeg displayName toe (lowercase)
            aliasToSpeciesIdMap[entry.displayName.lowercase()] = soortId

            // Voeg alle aliassen toe
            for (alias in entry.aliases) {
                aliasToSpeciesIdMap[alias] = soortId
            }
        }

        Log.d(TAG, "Built reverse mapping with ${aliasToSpeciesIdMap.size} entries")
    }

    /**
     * Maak een JSON bestand van het CSV bestand (eenmalig conversie)
     */
    suspend fun convertCsvToJson(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Eerst CSV laden als dat nog niet gedaan is
            if (aliasCache.isEmpty() && !loadFromCsv()) {
                return@withContext false
            }

            val safHelper = SaFStorageHelper(context)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return@withContext false

            val serverDataDir = vt5Dir.findFile("serverdata") ?: return@withContext false

            // Maak JSON structuur
            val aliasData = AliasData(aliasCache.values.toList())

            // Serialiseer naar JSON
            val json = Json {
                prettyPrint = true
                encodeDefaults = true
            }
            val jsonString = json.encodeToString(AliasData.serializer(), aliasData)

            // Schrijf naar bestand
            val outputFile = serverDataDir.createFile("application/json", ALIAS_JSON_FILE)
                ?: return@withContext false

            context.contentResolver.openOutputStream(outputFile.uri)?.use { stream ->
                stream.write(jsonString.toByteArray())
                stream.flush()
            } ?: return@withContext false

            Log.d(TAG, "Successfully converted CSV to JSON with ${aliasCache.size} entries")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error converting CSV to JSON", e)
            return@withContext false
        }
    }
}

/**
 * Data klasse voor de volledige alias dataset
 */
@Serializable
data class AliasData(
    @SerialName("aliases")
    val aliases: List<AliasEntry>
)

/**
 * Data klasse voor één alias entry
 */
@Serializable
data class AliasEntry(
    @SerialName("soortId")
    val soortId: String,

    @SerialName("canonicalName")
    val canonicalName: String,

    @SerialName("displayName")
    val displayName: String,

    @SerialName("aliases")
    val aliases: List<String>
)