@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.alias

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.speech.AliasMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository voor het beheren van alias-data voor soorten
 *
 * Uitbreiding: addAliasInMemory() cleans input (removes "asr:" and trailing numbers) defensively.
 * Toegevoegd: reloadMatcherIfNeeded(...) om AliasMatcher.reloadIndex() asynchroon te triggeren.
 */
class AliasRepository(private val context: Context) {

    companion object {
        private const val TAG = "AliasRepository"
        private const val ALIAS_JSON_FILE = "aliases.json"
        private const val ALIAS_CSV_FILE = "aliasmapping.csv"

        // Broadcast actions for reload progress (UI can listen and show progressbar)
        const val ACTION_ALIAS_RELOAD_STARTED = "com.yvesds.vt5.ALIAS_RELOAD_STARTED"
        const val ACTION_ALIAS_RELOAD_COMPLETED = "com.yvesds.vt5.ALIAS_RELOAD_COMPLETED"
        const val EXTRA_RELOAD_SUCCESS = "com.yvesds.vt5.EXTRA_RELOAD_SUCCESS"

        // Singleton instance - we deliberately store applicationContext inside the repo.
        // Storing applicationContext in a static field is acceptable; suppress the lint warning.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: AliasRepository? = null

        fun getInstance(context: Context): AliasRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AliasRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Store the application context explicitly (avoid accidental Activity/context leaks)
    private val appContext: Context = context.applicationContext

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

        val normalizedText = text.trim().lowercase(Locale.getDefault())
        return aliasToSpeciesIdMap[normalizedText]
    }

    @Suppress("unused")
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
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false

            val serverDataDir = vt5Dir.findFile("serverdata") ?: return false
            val aliasFile = serverDataDir.findFile(ALIAS_JSON_FILE)

            if (aliasFile == null || !aliasFile.exists()) {
                Log.d(TAG, "Alias JSON file not found: $ALIAS_JSON_FILE")
                return false
            }

            val inputStream = appContext.contentResolver.openInputStream(aliasFile.uri) ?: return false
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            // Parse JSON met kotlinx.serialization
            val aliasData = Json.decodeFromString(AliasData.serializer(), jsonString)

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
            val safHelper = SaFStorageHelper(appContext)
            val vt5Dir = safHelper.getVt5DirIfExists() ?: return false

            val serverDataDir = vt5Dir.findFile("serverdata") ?: return false
            val aliasFile = serverDataDir.findFile(ALIAS_CSV_FILE)

            if (aliasFile == null || !aliasFile.exists()) {
                Log.d(TAG, "Alias CSV file not found: $ALIAS_CSV_FILE")
                return false
            }

            val inputStream = appContext.contentResolver.openInputStream(aliasFile.uri) ?: return false

            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    var count = 0

                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrBlank()) continue

                        val parts = line!!.split(";")
                        if (parts.size < 3) continue

                        val soortId = parts[0].trim()
                        val canonicalName = parts[1].trim().lowercase(Locale.getDefault())
                        val displayName = parts[2].trim()

                        val aliases = if (parts.size > 3) {
                            parts.subList(3, parts.size)
                                .filter { it.isNotBlank() }
                                .map { it.trim().lowercase(Locale.getDefault()) }
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
            // Voeg canonical name toe (already lowercased)
            entry.canonicalName.takeIf { it.isNotBlank() }?.let { aliasToSpeciesIdMap[it] = soortId }
            // Voeg displayName toe (lowercase)
            entry.displayName.takeIf { it.isNotBlank() }?.let { aliasToSpeciesIdMap[it.lowercase(Locale.getDefault())] = soortId }

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

            val safHelper = SaFStorageHelper(appContext)
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

            appContext.contentResolver.openOutputStream(outputFile.uri)?.use { stream ->
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

    /**
     * Voeg een alias toe aan de in-memory structures (hot-reload).
     * - Als soort bestaat: voeg alias toe aan bestaande AliasEntry
     * - Als soort niet bestaat: maak nieuwe AliasEntry met canonical = soortId (fallback)
     *
     * Returns true als nieuw alias toegevoegd werd, false als alias al bestond of ongeldig was.
     */
    fun addAliasInMemory(soortId: String, aliasRaw: String): Boolean {
        try {
            // defensive cleaning like AliasEditor
            var alias = aliasRaw.trim()
            alias = alias.replace(Regex("(?i)^\\s*asr:\\s*"), "")
            alias = alias.replace("/", " of ")
            alias = alias.replace(";", " ")
            alias = alias.replace(Regex("(?:\\s+\\d+)+\\s*$"), "")
            alias = alias.replace(Regex("\\s+"), " ").trim()
            alias = alias.lowercase(Locale.getDefault())
            if (alias.isBlank()) return false

            // atomic update using synchronization on aliasCache
            synchronized(aliasCache) {
                val existing = aliasCache[soortId]
                val currentAliases = existing?.aliases?.toMutableList() ?: mutableListOf()

                // Prevent duplicates
                if (currentAliases.any { it.equals(alias, ignoreCase = true) }) return false

                currentAliases.add(alias)
                val canonical = existing?.canonicalName ?: soortId
                val display = existing?.displayName ?: canonical

                // Replace with new AliasEntry (immutable data class)
                aliasCache[soortId] = AliasEntry(soortId, canonical, display, currentAliases.toList())

                // Update reverse mapping with normalized alias
                val norm = normalizeForKey(alias)
                if (norm.isNotBlank()) {
                    aliasToSpeciesIdMap[norm] = soortId
                }
            }

            Log.d(TAG, "addAliasInMemory: added alias '$alias' for species $soortId")
            return true
        } catch (ex: Exception) {
            Log.w(TAG, "addAliasInMemory failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Helper normalizer to keep consistent behaviour with precompute normalizer
     */
    private fun normalizeForKey(input: String): String {
        val lower = input.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    /**
     * Try to reload the heavy AliasMatcher index from SAF (if available).
     * This is a low-priority helper that is safe to call after user alias persistence.
     *
     * Returns true when reload completed successfully, false otherwise.
     */
    suspend fun reloadMatcherIfNeeded(context: Context, saf: SaFStorageHelper): Boolean = withContext(Dispatchers.IO) {
        try {
            AliasMatcher.reloadIndex(context, saf)
            Log.i(TAG, "AliasMatcher.reloadIndex completed")
            return@withContext true
        } catch (ex: Exception) {
            Log.w(TAG, "AliasMatcher.reloadIndex failed: ${ex.message}", ex)
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