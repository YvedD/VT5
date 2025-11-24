package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.features.network.DataUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * TellingSpeciesManager: Manages species operations for TellingScherm.
 * 
 * Responsibilities:
 * - Add species to tiles (manually or via speech)
 * - Update species counts
 * - Launch species selection screen
 * - Collect observations as records
 * - Manage available species list
 */
class TellingSpeciesManager(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val safHelper: SaFStorageHelper,
    private val backupManager: TellingBackupManager,
    private val tegelBeheer: TegelBeheer,
    private val prefsName: String
) {
    companion object {
        private const val TAG = "TellingSpeciesManager"
        private const val PREF_TELLING_ID = "pref_telling_id"
    }

    // Available species (flat list for dialogs)
    private var availableSpeciesFlat: List<String> = emptyList()

    // Activity result launcher
    private lateinit var addSoortenLauncher: ActivityResultLauncher<Intent>

    // Callbacks
    var onSpeciesAdded: ((Int) -> Unit)? = null
    var onLogMessage: ((String, String) -> Unit)? = null
    var onTilesUpdated: (() -> Unit)? = null
    var onRecordCollected: ((ServerTellingDataItem) -> Unit)? = null

    /**
     * Register activity result launchers.
     */
    fun registerLaunchers() {
        addSoortenLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleSpeciesSelectionResult(result.resultCode, result.data)
        }
    }

    /**
     * Handle species selection result.
     */
    private fun handleSpeciesSelectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val newIds = data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            if (newIds.isNotEmpty()) {
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        val snapshot = ServerDataCache.getOrLoad(activity)
                        val speciesById = snapshot.speciesById

                        val existing = tegelBeheer.getTiles()
                        val existingIds = existing.map { it.soortId }.toSet()

                        val additions = newIds
                            .filterNot { it in existingIds }
                            .mapNotNull { sid ->
                                val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                                SoortTile(sid, naam, 0)
                            }

                        if (additions.isNotEmpty()) {
                            // Add new tiles
                            additions.forEach { tile ->
                                tegelBeheer.voegSoortToe(tile.soortId, tile.naam, 0, mergeIfExists = false)
                            }

                            onLogMessage?.invoke("Soorten toegevoegd: ${additions.size}", "manueel")
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.telling_added_count, additions.size),
                                Toast.LENGTH_SHORT
                            ).show()

                            onTilesUpdated?.invoke()
                            onSpeciesAdded?.invoke(additions.size)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "handleSpeciesSelectionResult failed: ${ex.message}", ex)
                    }
                }
            }
        }
    }

    /**
     * Launch species selection screen.
     */
    fun launchSpeciesSelection() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(activity, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }

    /**
     * Add species to tiles if needed.
     */
    suspend fun addSpeciesToTilesIfNeeded(speciesId: String, canonical: String, extractedCount: Int) {
        withContext(Dispatchers.Main) {
            val added = tegelBeheer.voegSoortToeIndienNodig(speciesId, canonical, extractedCount)
            if (!added) {
                // Species already exists, just increase count
                tegelBeheer.verhoogSoortAantal(speciesId, extractedCount)
            }
            onTilesUpdated?.invoke()
            RecentSpeciesStore.recordUse(activity, speciesId, maxEntries = 30)
            onLogMessage?.invoke(
                "Soort ${if (added) "toegevoegd" else "bijgewerkt"}: $canonical ($extractedCount)",
                "systeem"
            )
        }
    }

    /**
     * Add species to tiles with specified count.
     */
    suspend fun addSpeciesToTiles(soortId: String, naam: String, initialCount: Int) {
        try {
            val snapshot = withContext(Dispatchers.IO) { ServerDataCache.getOrLoad(activity) }
            val canonical = snapshot.speciesById[soortId]?.soortnaam ?: naam

            tegelBeheer.voegSoortToe(soortId, canonical, initialCount, mergeIfExists = true)
            onTilesUpdated?.invoke()
            RecentSpeciesStore.recordUse(activity, soortId, maxEntries = 30)
            onLogMessage?.invoke("Soort toegevoegd: $canonical ($initialCount)", "systeem")
        } catch (ex: Exception) {
            Log.w(TAG, "addSpeciesToTiles failed: ${ex.message}", ex)
            onLogMessage?.invoke("Fout bij toevoegen soort $naam", "systeem")
        }
    }

    /**
     * Update species count internally (no log message).
     */
    suspend fun updateSoortCountInternal(soortId: String, count: Int) {
        tegelBeheer.verhoogSoortAantal(soortId, count)
        RecentSpeciesStore.recordUse(activity, soortId, maxEntries = 30)
        onTilesUpdated?.invoke()
    }

    /**
     * Collect observation as record and create backup.
     */
    suspend fun collectFinalAsRecord(soortId: String, amount: Int) {
        withContext(Dispatchers.IO) {
            try {
                val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val tellingId = prefs.getString(PREF_TELLING_ID, null)
                if (tellingId.isNullOrBlank()) {
                    Log.w(TAG, "No PREF_TELLING_ID available - cannot collect final as record")
                    return@withContext
                }

                val idLocal = DataUploader.getAndIncrementRecordId(activity, tellingId)
                val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
                
                // Generate uploadtijdstip in "YYYY-MM-DD HH:MM:SS" format
                val currentTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                
                // Create complete record with sensible defaults
                // Annotation system will override these values if user annotates the observation
                val item = ServerTellingDataItem(
                    idLocal = idLocal,
                    tellingid = tellingId,
                    soortid = soortId,
                    aantal = amount.toString(),
                    richting = "",                     // Empty - only filled via annotation checkboxes
                    aantalterug = "0",
                    richtingterug = "",                // Empty until direction annotated
                    sightingdirection = "",            // Empty by default (user's preference)
                    lokaal = "0",
                    aantal_plus = "0",
                    aantalterug_plus = "0",
                    lokaal_plus = "0",
                    markeren = "0",                    // Not marked by default
                    markerenlokaal = "0",              // Not marked local by default
                    geslacht = "",                     // Empty until user annotates
                    leeftijd = "",                     // Empty until user annotates
                    kleed = "",                        // Empty until user annotates
                    opmerkingen = "",                  // Empty until user adds remarks
                    trektype = "",                     // Empty (not used in UI)
                    teltype = "",                      // Empty until user annotates
                    location = "",                     // Empty until user annotates (height buttons)
                    height = "",                       // Empty until user annotates (location buttons)
                    tijdstip = nowEpoch,
                    groupid = idLocal,
                    uploadtijdstip = currentTimestamp, // Set timestamp immediately!
                    totaalaantal = amount.toString()
                )

                // Notify callback
                withContext(Dispatchers.Main) {
                    onRecordCollected?.invoke(item)
                }

                // Write backup
                try {
                    backupManager.writeRecordBackupSaf(tellingId, item)
                } catch (ex: Exception) {
                    Log.w(TAG, "Record backup failed: ${ex.message}", ex)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Waarneming opgeslagen (buffer)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "collectFinalAsRecord failed: ${e.message}", e)
            }
        }
    }

    /**
     * Ensure available species flat list is loaded.
     */
    fun ensureAvailableSpeciesFlat(onReady: (List<String>) -> Unit) {
        if (availableSpeciesFlat.isNotEmpty()) {
            onReady(availableSpeciesFlat)
            return
        }
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = ServerDataCache.getOrLoad(activity)
                val flat = snapshot.speciesById.map { (id, s) -> "$id||${s.soortnaam}" }.toList()
                withContext(Dispatchers.Main) {
                    availableSpeciesFlat = flat
                    onReady(flat)
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    Log.w(TAG, "ensureAvailableSpeciesFlat failed: ${ex.message}", ex)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.soort_list_not_available),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Refresh aliases runtime asynchronously after user adds alias.
     */
    fun refreshAliasesRuntimeAsync(
        speechHandler: TellingSpeechHandler,
        onContextRebuilt: ((com.yvesds.vt5.features.speech.MatchContext) -> Unit)?
    ) {
        lifecycleOwner.lifecycleScope.launch {
            try {
                // Reload AliasMatcher index
                withContext(Dispatchers.IO) {
                    try {
                        com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(activity, safHelper)
                    } catch (ex: Exception) {
                        Log.w(TAG, "AliasMatcher.reloadIndex failed (post addAlias): ${ex.message}", ex)
                    }
                }

                // Reload speech recognition aliases
                speechHandler.loadAliases()

                // Rebuild match context
                withContext(Dispatchers.Default) {
                    try {
                        val tiles = tegelBeheer.getTiles().map { it.soortId }.toSet()
                        val snapshot = ServerDataCache.getOrLoad(activity)
                        val telpostId = TellingSessionManager.preselectState.value.telpostId
                        val siteAllowed = telpostId?.let { id ->
                            snapshot.siteSpeciesBySite[id]?.map { it.soortid }?.toSet() ?: emptySet()
                        } ?: snapshot.speciesById.keys
                        val recents = RecentSpeciesStore.getRecents(activity).map { it.first }.toSet()
                        val speciesById = snapshot.speciesById.mapValues { (_, sp) ->
                            sp.soortnaam to sp.soortkey
                        }

                        val mc = com.yvesds.vt5.features.speech.MatchContext(
                            tilesSpeciesIds = tiles,
                            siteAllowedIds = siteAllowed,
                            recentIds = recents,
                            speciesById = speciesById
                        )
                        onContextRebuilt?.invoke(mc)
                    } catch (ex: Exception) {
                        Log.w(TAG, "buildMatchContext failed (post addAlias): ${ex.message}", ex)
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "refreshAliasesRuntimeAsync overall failed: ${ex.message}", ex)
            }
        }
    }
}
