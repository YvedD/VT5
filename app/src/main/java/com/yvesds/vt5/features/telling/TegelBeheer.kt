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
 *
 * De tellers gebruiken semantische richtingen:
 *  - countMain: de hoofdrichting (record.aantal) - label is seizoensafhankelijk in UI
 *  - countReturn: de tegengestelde richting (record.aantalterug)
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
 * Houdt de aantallen gescheiden bij:
 * - countMain: aantal in de hoofdrichting (record.aantal)
 * - countReturn: aantal in de tegengestelde richting (record.aantalterug)
 * De UI toont dynamisch de juiste labels (ZW/NO) gebaseerd op het seizoen.
 */
data class SoortTile(
    val soortId: String,
    val naam: String,
    val countMain: Int = 0,
    val countReturn: Int = 0
) {
    // Backwards compatible total count property
    val count: Int get() = countMain + countReturn
}

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
            
            // New observations always go to countMain
            val new = SoortTile(soortId = soortId, naam = naam, countMain = initialCount, countReturn = 0)
            
            tiles.add(new)
            tiles.sortBy { it.naam.lowercase() }
            ui.submitTiles(tiles.map { it.copy() })
            return true
        }
    }

    fun voegSoortToe(soortId: String, naam: String, initialCount: Int = 0, mergeIfExists: Boolean = false) {
        synchronized(lock) {
            val idx = tiles.indexOfFirst { it.soortId == soortId }
            if (idx >= 0) {
                if (mergeIfExists) {
                    val current = tiles[idx]
                    val updated = current.copy(countMain = current.countMain + initialCount)
                    tiles[idx] = updated
                    ui.onTileCountUpdated(soortId, updated.count)
                    ui.submitTiles(tiles.map { it.copy() })
                }
                return
            }
            
            val new = SoortTile(soortId = soortId, naam = naam, countMain = initialCount, countReturn = 0)
            
            tiles.add(new)
            tiles.sortBy { it.naam.lowercase() }
            ui.submitTiles(tiles.map { it.copy() })
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
            val updated = cur.copy(countMain = cur.countMain + delta)
            
            tiles[idx] = updated
            ui.onTileCountUpdated(soortId, updated.count)
            ui.submitTiles(tiles.map { it.copy() })
            return true
        }
    }

    fun verhoogSoortAantalOfVoegToe(soortId: String, naamFallback: String, delta: Int): Int? {
        synchronized(lock) {
            val idx = tiles.indexOfFirst { it.soortId == soortId }
            if (idx == -1) {
                val initial = if (delta >= 0) delta else 0
                val new = SoortTile(soortId = soortId, naam = naamFallback, countMain = initial, countReturn = 0)
                
                tiles.add(new)
                tiles.sortBy { it.naam.lowercase() }
                ui.submitTiles(tiles.map { it.copy() })
                return initial
            } else {
                val cur = tiles[idx]
                val updated = cur.copy(countMain = cur.countMain + delta)
                
                tiles[idx] = updated
                ui.onTileCountUpdated(soortId, updated.count)
                ui.submitTiles(tiles.map { it.copy() })
                return updated.count
            }
        }
    }

    fun buildSelectedSpeciesMap(): Map<String, String> {
        synchronized(lock) {
            val map = tiles.associate { it.soortId to it.naam }
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
            val summary = tiles.joinToString(", ") { "${it.soortId}:${it.naam}:main=${it.countMain}+return=${it.countReturn}" }
        }
    }
    
    /**
     * Recalculate tile counts from pending records.
     * Direct mapping - no season logic needed:
     * - record.aantal → countMain
     * - record.aantalterug → countReturn
     */
    fun recalculateCountsFromRecords(records: List<com.yvesds.vt5.net.ServerTellingDataItem>) {
        synchronized(lock) {
            val countMap = mutableMapOf<String, Pair<Int, Int>>()
            
            for (record in records) {
                val soortId = record.soortid
                val aantal = record.aantal.toIntOrNull() ?: 0
                val aantalterug = record.aantalterug.toIntOrNull() ?: 0
                
                val current = countMap[soortId] ?: Pair(0, 0)
                countMap[soortId] = Pair(current.first + aantal, current.second + aantalterug)
            }
            
            var changed = false
            for (i in tiles.indices) {
                val tile = tiles[i]
                val counts = countMap[tile.soortId] ?: Pair(0, 0)
                if (tile.countMain != counts.first || tile.countReturn != counts.second) {
                    tiles[i] = tile.copy(countMain = counts.first, countReturn = counts.second)
                    changed = true
                }
            }
            
            if (changed) {
                ui.submitTiles(tiles.map { it.copy() })
            }
        }
    }
}