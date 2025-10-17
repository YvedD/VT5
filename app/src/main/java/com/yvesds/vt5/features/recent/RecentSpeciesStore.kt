package com.yvesds.vt5.features.recent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Verbeterde 'recent gebruikt' opslag met in-memory caching.
 * - Bewaart max [maxEntries] soortIDs met laatste gebruikstijd, meest recent eerst.
 * - recordUse() promoot of voegt toe; trimt lijst.
 * - Optimalisaties: in-memory caching voor snellere toegang zonder IO
 *
 * Let op:
 * - Deze store is globaal (app-breed), niet telpost-specifiek.
 * - Filter recents zelf tegen de actuele lijst voor de gekozen telpost.
 */
object RecentSpeciesStore {
    private const val TAG = "RecentSpeciesStore"
    private const val PREFS = "recent_species_prefs"
    private const val KEY = "recent_species_list"

    // In-memory cache van recente soorten
    private var cachedRecents: List<Pair<String, Long>>? = null
    // Memory-efficient lookup voor membership checks
    private var cachedRecentIdsSet = ConcurrentHashMap<String, Boolean>()

    /**
     * Registreer het gebruik van een soort-id. Standaard max 25 entries.
     * Promoot bestaande soort naar boven of voegt nieuwe toe.
     */
    fun recordUse(context: Context, soortId: String, maxEntries: Int = 25) {
        val list = load(context).toMutableList()
        val now = System.currentTimeMillis()
        val without = list.filterNot { it.first == soortId }
        val updated = listOf(soortId to now) + without
        save(context, updated.take(maxEntries))

        // Update cache
        cachedRecents = updated.take(maxEntries)
        cachedRecentIdsSet.clear()
        cachedRecents?.forEach { (id, _) -> cachedRecentIdsSet[id] = true }
    }

    /**
     * Retourneert lijst van (soortId, lastUsedMillis), meest recent eerst.
     * Gebruikt in-memory cache voor betere performance.
     */
    fun getRecents(context: Context): List<Pair<String, Long>> {
        cachedRecents?.let { return it }

        val loaded = load(context)
        cachedRecents = loaded
        cachedRecentIdsSet.clear()
        loaded.forEach { (id, _) -> cachedRecentIdsSet[id] = true }

        return loaded
    }

    /**
     * Snel controleren of een soortId recent is gebruikt
     */
    fun isRecent(context: Context, soortId: String): Boolean {
        if (cachedRecentIdsSet.containsKey(soortId)) {
            return cachedRecentIdsSet[soortId] == true
        }

        // Cache vullen als die nog niet bestaat
        if (cachedRecents == null) {
            getRecents(context)
        }

        return cachedRecentIdsSet[soortId] == true
    }

    private fun load(context: Context): List<Pair<String, Long>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                val ts = o.optLong("ts", 0L)
                if (id.isNotBlank() && ts > 0L) id to ts else null
            }
        }.getOrElse { e ->
            Log.e(TAG, "Error loading recents: ${e.message}", e)
            emptyList()
        }
    }

    private fun save(context: Context, items: List<Pair<String, Long>>) {
        try {
            val arr = JSONArray()
            items.forEach { (id, ts) ->
                arr.put(JSONObject().put("id", id).put("ts", ts))
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, arr.toString())
                .apply() // apply in plaats van commit voor background IO
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recents: ${e.message}", e)
        }
    }

    /**
     * Handmatig de cache leegmaken (gebruikt in testsituaties)
     */
    fun invalidateCache() {
        cachedRecents = null
        cachedRecentIdsSet.clear()
    }
}