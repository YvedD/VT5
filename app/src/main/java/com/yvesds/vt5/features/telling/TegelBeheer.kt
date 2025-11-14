package com.yvesds.vt5.features.telling

import android.util.Log

/**
 * TegelBeheer.kt
 *
 * Doel:
 *  - Centraliseert alle logica rond 'tegels' (soorten/tiles): toevoegen, zoeken en tellen.
 *  - Houdt een eenvoudige in‑memory lijst bij en levert callbacks naar UI voor presentatiewijzigingen.
 *
 * Gebruik (kort):
 *  - Maak in TellingScherm een instance: val tegelBeheer = TegelBeheer(ui = object : TegelUi { ... })
 *  - Bij opstart: tegelBeheer.setTiles(initialListFromServer)
 *  - Bij ASR of handmatige wijziging: tegelBeheer.verhoogSoortAantal(soortId, delta)
 *  - UI implementatie ontvangt submitTiles(list) en rolt de adapter updates uit op Main thread.
 */

private const val TAG = "TegelBeheer"

/**
 * Interface die de Activity (UI) implementeert om tegellijst updates te ontvangen.
 */
interface TegelUi {
    fun submitTiles(list: List<SoortTile>)
    fun onTileCountUpdated(soortId: String, newCount: Int) {}
}

/**
 * Lokaal model voor één tegel (soort).
 */
data class SoortTile(
    val soortId: String,
    val naam: String,
    val count: Int = 0
)

/**
 * TegelBeheer: beheer van de lijst met SoortTile objecten en eenvoudige mutatie-API.
 */
class TegelBeheer(private val ui: TegelUi) {

    private val tiles = mutableListOf<SoortTile>()
    private val lock = Any()

    fun setTiles(list: List<SoortTile>) {
        synchronized(lock) {
            tiles.clear()
            tiles.addAll(list)
            ui.submitTiles(tiles.map { it.copy() })
            Log.d(TAG, "setTiles: ${tiles.size} tegels gezet")
        }
    }

    fun getTiles(): List<SoortTile> {
        synchronized(lock) {
            return tiles.map { it.copy() }
        }
    }

    fun voegSoortToeIndienNodig(soortId: String, naam: String, initialCount: Int = 0): Boolean {
        synchronized(lock) {
            val exists = tiles.any { it.soortId == soortId }
            if (exists) return false
            val new = SoortTile(soortId = soortId, naam = naam, count = initialCount)
            tiles.add(new)
            tiles.sortBy { it.naam.lowercase() }
            ui.submitTiles(tiles.map { it.copy() })
            Log.d(TAG, "voegSoortToeIndienNodig: toegevoegd $soortId / $naam ($initialCount)")
            return true
        }
    }

    fun voegSoortToe(soortId: String, naam: String, initialCount: Int = 0, mergeIfExists: Boolean = false) {
        synchronized(lock) {
            val idx = tiles.indexOfFirst { it.soortId == soortId }
            if (idx >= 0) {
                if (mergeIfExists) {
                    val current = tiles[idx]
                    val updated = current.copy(count = current.count + initialCount)
                    tiles[idx] = updated
                    ui.onTileCountUpdated(soortId, updated.count)
                    ui.submitTiles(tiles.map { it.copy() })
                }
                return
            }
            tiles.add(SoortTile(soortId = soortId, naam = naam, count = initialCount))
            tiles.sortBy { it.naam.lowercase() }
            ui.submitTiles(tiles.map { it.copy() })
            Log.d(TAG, "voegSoortToe: $soortId / $naam ($initialCount)")
        }
    }

    fun verhoogSoortAantal(soortId: String, delta: Int): Boolean {
        if (delta == 0) return true
        synchronized(lock) {
            val idx = tiles.indexOfFirst { it.soortId == soortId }
            if (idx == -1) {
                Log.w(TAG, "verhoogSoortAantal: soortId $soortId niet gevonden")
                return false
            }
            val cur = tiles[idx]
            val updated = cur.copy(count = cur.count + delta)
            tiles[idx] = updated
            ui.onTileCountUpdated(soortId, updated.count)
            ui.submitTiles(tiles.map { it.copy() })
            Log.d(TAG, "verhoogSoortAantal: $soortId -> ${updated.count} (delta $delta)")
            return true
        }
    }

    fun verhoogSoortAantalOfVoegToe(soortId: String, naamFallback: String, delta: Int): Int? {
        synchronized(lock) {
            val idx = tiles.indexOfFirst { it.soortId == soortId }
            if (idx == -1) {
                val initial = if (delta >= 0) delta else 0
                tiles.add(SoortTile(soortId = soortId, naam = naamFallback, count = initial))
                tiles.sortBy { it.naam.lowercase() }
                ui.submitTiles(tiles.map { it.copy() })
                Log.d(TAG, "verhoogSoortAantalOfVoegToe: soort niet gevonden. Toegevoegd $soortId met $initial")
                return initial
            } else {
                val cur = tiles[idx]
                val updated = cur.copy(count = cur.count + delta)
                tiles[idx] = updated
                ui.onTileCountUpdated(soortId, updated.count)
                ui.submitTiles(tiles.map { it.copy() })
                Log.d(TAG, "verhoogSoortAantalOfVoegToe: $soortId -> ${updated.count}")
                return updated.count
            }
        }
    }

    fun buildSelectedSpeciesMap(): Map<String, String> {
        synchronized(lock) {
            val map = tiles.associate { it.soortId to it.naam }
            Log.d(TAG, "buildSelectedSpeciesMap: ${map.size} entries")
            return map
        }
    }

    fun findIndexBySoortId(soortId: String): Int {
        synchronized(lock) {
            return tiles.indexOfFirst { it.soortId == soortId }
        }
    }

    fun findNaamBySoortId(soortId: String): String? {
        synchronized(lock) {
            return tiles.firstOrNull { it.soortId == soortId }?.naam
        }
    }

    fun logTilesState(prefix: String = "tiles") {
        synchronized(lock) {
            val summary = tiles.joinToString(", ") { "${it.soortId}:${it.naam}:${it.count}" }
            Log.d(TAG, "$prefix (${tiles.size}): $summary")
        }
    }
}