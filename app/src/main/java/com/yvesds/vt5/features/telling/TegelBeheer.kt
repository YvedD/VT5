package com.yvesds.vt5.features.telling

import android.util.Log
import java.util.Calendar

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
 * Seizoen-gebaseerde tellers:
 *  - Jan-Jun (maand 1-6): Vogels gaan naar NO (noordoost migratie)
 *  - Jul-Dec (maand 7-12): Vogels gaan naar ZW (zuidwest migratie)
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
 * Houdt nu ZW (aantal) en NO (aantalterug) gescheiden bij.
 */
data class SoortTile(
    val soortId: String,
    val naam: String,
    val countZW: Int = 0,
    val countNO: Int = 0
) {
    // Backwards compatible total count property
    val count: Int get() = countZW + countNO
}

/**
 * TegelBeheer: beheer van de lijst met SoortTile objecten en eenvoudige mutatie-API.
 */
class TegelBeheer(private val ui: TegelUi) {

    private val tiles = mutableListOf<SoortTile>()
    private val lock = Any()
    
    /**
     * Bepaal of we in ZW seizoen zitten (Jul-Dec) of NO seizoen (Jan-Jun).
     * Returns true voor ZW seizoen, false voor NO seizoen.
     */
    private fun isZwSeizoen(): Boolean {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1 // 1-12
        return month in 7..12  // Jul-Dec = ZW seizoen
    }

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
            
            // Seizoen-gebaseerd: plaats count in juiste teller
            val isZw = isZwSeizoen()
            val new = if (isZw) {
                SoortTile(soortId = soortId, naam = naam, countZW = initialCount, countNO = 0)
            } else {
                SoortTile(soortId = soortId, naam = naam, countZW = 0, countNO = initialCount)
            }
            
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
                    // Seizoen-gebaseerd verhogen
                    val isZw = isZwSeizoen()
                    val updated = if (isZw) {
                        current.copy(countZW = current.countZW + initialCount)
                    } else {
                        current.copy(countNO = current.countNO + initialCount)
                    }
                    tiles[idx] = updated
                    ui.onTileCountUpdated(soortId, updated.count)
                    ui.submitTiles(tiles.map { it.copy() })
                }
                return
            }
            
            // Nieuwe tile: seizoen-gebaseerd
            val isZw = isZwSeizoen()
            val new = if (isZw) {
                SoortTile(soortId = soortId, naam = naam, countZW = initialCount, countNO = 0)
            } else {
                SoortTile(soortId = soortId, naam = naam, countZW = 0, countNO = initialCount)
            }
            
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
            // Seizoen-gebaseerd: verhoog juiste teller
            val isZw = isZwSeizoen()
            val updated = if (isZw) {
                cur.copy(countZW = cur.countZW + delta)
            } else {
                cur.copy(countNO = cur.countNO + delta)
            }
            
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
                
                // Seizoen-gebaseerd: nieuwe tile
                val isZw = isZwSeizoen()
                val new = if (isZw) {
                    SoortTile(soortId = soortId, naam = naamFallback, countZW = initial, countNO = 0)
                } else {
                    SoortTile(soortId = soortId, naam = naamFallback, countZW = 0, countNO = initial)
                }
                
                tiles.add(new)
                tiles.sortBy { it.naam.lowercase() }
                ui.submitTiles(tiles.map { it.copy() })
                return initial
            } else {
                val cur = tiles[idx]
                // Seizoen-gebaseerd: verhoog bestaande tile
                val isZw = isZwSeizoen()
                val updated = if (isZw) {
                    cur.copy(countZW = cur.countZW + delta)
                } else {
                    cur.copy(countNO = cur.countNO + delta)
                }
                
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
            val summary = tiles.joinToString(", ") { "${it.soortId}:${it.naam}:ZW=${it.countZW}+NO=${it.countNO}" }
        }
    }
    
    /**
     * Recalculate tile counts from pending records.
     * 
     * The mapping from record fields to tile counts depends on the season:
     * - In ZW seizoen (Jul-Dec): aantal → countZW, aantalterug → countNO
     * - In NO seizoen (Jan-Jun): aantal → countNO, aantalterug → countZW
     * 
     * This ensures that the tile counts always show the physical direction counts
     * (how many birds went ZW vs NO), regardless of the seasonal main direction.
     */
    fun recalculateCountsFromRecords(records: List<com.yvesds.vt5.net.ServerTellingDataItem>) {
        synchronized(lock) {
            val isZw = isZwSeizoen()
            
            // Create map of soortId -> (totalZW, totalNO)
            val countMap = mutableMapOf<String, Pair<Int, Int>>()
            
            for (record in records) {
                val soortId = record.soortid
                val aantal = record.aantal.toIntOrNull() ?: 0
                val aantalterug = record.aantalterug.toIntOrNull() ?: 0
                
                val current = countMap[soortId] ?: Pair(0, 0)
                
                // Season-dependent mapping
                val (addZw, addNo) = if (isZw) {
                    // ZW seizoen: aantal = ZW, aantalterug = NO
                    Pair(aantal, aantalterug)
                } else {
                    // NO seizoen: aantal = NO, aantalterug = ZW
                    Pair(aantalterug, aantal)
                }
                
                countMap[soortId] = Pair(current.first + addZw, current.second + addNo)
            }
            
            // Update tiles with new counts
            var changed = false
            for (i in tiles.indices) {
                val tile = tiles[i]
                val counts = countMap[tile.soortId] ?: Pair(0, 0)
                if (tile.countZW != counts.first || tile.countNO != counts.second) {
                    tiles[i] = tile.copy(countZW = counts.first, countNO = counts.second)
                    changed = true
                }
            }
            
            if (changed) {
                ui.submitTiles(tiles.map { it.copy() })
            }
        }
    }
}