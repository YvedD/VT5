package com.yvesds.vt5.features.recent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simpele 'recent gebruikt' opslag met SharedPreferences.
 * - Bewaart max [maxEntries] soortIDs met laatste gebruikstijd, meest recent eerst.
 * - recordUse() promoot of voegt toe; trimt lijst.
 *
 * Let op:
 * - Deze store is globaal (app-breed), niet telpost-specifiek.
 * - Filter recents zelf tegen de actuele lijst voor de gekozen telpost.
 */
object RecentSpeciesStore {
    private const val PREFS = "recent_species_prefs"
    private const val KEY = "recent_species_list"

    /** Registreer het gebruik van een soort-id. Standaard max 25 entries. */
    fun recordUse(context: Context, soortId: String, maxEntries: Int = 25) {
        val list = load(context).toMutableList()
        val now = System.currentTimeMillis()
        val without = list.filterNot { it.first == soortId }
        val updated = listOf(soortId to now) + without
        save(context, updated.take(maxEntries))
    }

    /** Retourneert lijst van (soortId, lastUsedMillis), meest recent eerst. */
    fun getRecents(context: Context): List<Pair<String, Long>> = load(context)

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
        }.getOrElse { emptyList() }
    }

    private fun save(context: Context, items: List<Pair<String, Long>>) {
        val arr = JSONArray()
        items.forEach { (id, ts) ->
            arr.put(JSONObject().put("id", id).put("ts", ts))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}