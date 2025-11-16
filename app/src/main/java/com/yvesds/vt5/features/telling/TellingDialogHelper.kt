package com.yvesds.vt5.features.telling

import android.app.Activity
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.speech.Candidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TellingDialogHelper: Manages dialog interactions for TellingScherm.
 * 
 * Responsibilities:
 * - Number input dialogs for tiles
 * - Suggestion bottom sheets
 * - Confirmation dialogs
 * - Dialog text styling for sunlight readability
 */
class TellingDialogHelper(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val safHelper: SaFStorageHelper
) {
    companion object {
        private const val TAG = "TellingDialogHelper"
    }

    /**
     * Show number input dialog for a species tile.
     */
    fun showNumberInputDialog(
        position: Int,
        currentTiles: List<TellingScherm.SoortRow>,
        onCountUpdated: (String, Int) -> Unit
    ) {
        if (position < 0 || position >= currentTiles.size) return
        val row = currentTiles[position]

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "Aantal (bijv. 5)"
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_enter_count, row.naam))
            .setMessage("Huidige stand: ${row.count}")
            .setView(input)
            .setPositiveButton("Toevoegen") { _, _ ->
                val text = input.text.toString().trim()
                val delta = text.toIntOrNull() ?: 0
                if (delta > 0) {
                    onCountUpdated(row.soortId, delta)
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()

        styleAlertDialogTextToWhite(dialog)
    }

    /**
     * Show suggestion bottom sheet with multiple candidates.
     */
    fun showSuggestionBottomSheet(
        candidates: List<Candidate>,
        count: Int,
        onCandidateSelected: (String, String, Int) -> Unit
    ) {
        if (candidates.isEmpty()) return

        val items = candidates.map { "${it.displayName} (${it.speciesId})" }.toTypedArray()
        
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_select_species))
            .setItems(items) { _, which ->
                val selected = candidates.getOrNull(which) ?: return@setItems
                onCandidateSelected(selected.speciesId, selected.displayName, count)
            }
            .setNegativeButton("Annuleren", null)
            .show()

        styleAlertDialogTextToWhite(dialog)
    }

    /**
     * Show confirmation dialog before adding species with popup.
     */
    fun showAddSpeciesConfirmation(
        speciesId: String,
        displayName: String,
        count: Int,
        onConfirmed: (String, String, Int) -> Unit
    ) {
        val msg = "Soort \"$displayName\" herkend met aantal $count.\n\nToevoegen?"
        
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_add_species))
            .setMessage(msg)
            .setPositiveButton("Ja") { _, _ ->
                onConfirmed(speciesId, displayName, count)
            }
            .setNegativeButton("Nee", null)
            .show()

        styleAlertDialogTextToWhite(dialog)
    }

    /**
     * Show Add Alias dialog for partial/raw entries.
     */
    fun showAddAliasDialog(
        nameText: String,
        count: Int,
        availableSpecies: List<String>,
        onAliasAdded: (String, String, Int) -> Unit,
        fragmentManager: androidx.fragment.app.FragmentManager
    ) {
        val dialog = AddAliasDialog.newInstance(listOf(nameText), availableSpecies)
        
        dialog.listener = object : AddAliasDialog.AddAliasListener {
            override fun onAliasAssigned(speciesId: String, aliasText: String) {
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        val snapshot = ServerDataCache.getOrLoad(activity)
                        val canonical = snapshot.speciesById[speciesId]?.soortnaam ?: aliasText
                        val tilename = snapshot.speciesById[speciesId]?.soortkey

                        val added = AliasManager.addAlias(
                            context = activity,
                            saf = safHelper,
                            speciesId = speciesId,
                            aliasText = aliasText.trim(),
                            canonical = canonical,
                            tilename = tilename
                        )

                        if (added) {
                            // Notify caller
                            onAliasAdded(speciesId, canonical, count)
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to add alias: ${ex.message}", ex)
                    }
                }
            }
        }
        
        dialog.show(fragmentManager, "addAlias")
    }

    /**
     * Style AlertDialog text to white for sunlight readability.
     * Forces title and message text to white color.
     */
    fun styleAlertDialogTextToWhite(dialog: AlertDialog) {
        try {
            // Title
            val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
            if (titleId > 0) {
                val titleView = dialog.findViewById<TextView>(titleId)
                titleView?.setTextColor(Color.WHITE)
            }

            // Message
            val messageId = android.R.id.message
            val messageView = dialog.findViewById<TextView>(messageId)
            messageView?.setTextColor(Color.WHITE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to style dialog text: ${e.message}")
        }
    }
}
