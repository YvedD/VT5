package com.yvesds.vt5.features.telling

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.ui.CompassView
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.features.annotation.AnnotationsManager
import com.yvesds.vt5.features.annotation.AnnotationOption
import com.yvesds.vt5.utils.SeizoenUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AnnotatieScherm
 *
 * - Laadt annotations.json in geheugen via AnnotationsManager.loadCache(...)
 * - Vult vooraf getekende ToggleButtons in activity_annotatie.xml met de "tekst" uit annotations.json,
 *   plaatst het corresponderende AnnotationOption object in btn.tag, en handhaaft single-select per groep.
 * - Als er minder opties zijn dan knoppen, worden overtollige buttons verborgen.
 * - OK retourneert een JSON-map { storeKey -> waarde } via EXTRA_ANNOTATIONS_JSON.
 * - Voor compatibiliteit met oudere callers (bv. TellingScherm) vult het resultaat ook:
 *     EXTRA_TEXT -> een korte samenvattende tekst (labels, komma-gescheiden)
 *     EXTRA_TS   -> timestamp in seconden (Long)
 */
class AnnotatieScherm : AppCompatActivity() {

    companion object {
        const val EXTRA_ANNOTATIONS_JSON = "extra_annotations_json"

        // Legacy keys expected by older code paths (keeps TellingScherm compile+runtime compatible)
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TS = "extra_ts"
        
        // Keys for prefilling count fields from existing record values
        // These are the raw record values, which need season-based mapping to UI fields
        const val EXTRA_RECORD_AANTAL = "extra_record_aantal"           // record.aantal (main direction)
        const val EXTRA_RECORD_AANTALTERUG = "extra_record_aantalterug" // record.aantalterug (opposite direction)
        const val EXTRA_LOKAAL = "extra_lokaal"
    }

    private val json = Json { prettyPrint = false }

    // Map groupName -> list of ToggleButtons
    private val groupButtons = mutableMapOf<String, MutableList<AppCompatToggleButton>>()
    
    // Reference to remarks EditText for location/height auto-tagging
    private lateinit var etOpmerkingen: EditText
    
    // Selected sighting direction (code from windoms, e.g., "N", "NNE", etc.)
    private var selectedSightingDirection: String? = null
    
    // Track active compass dialog and view for proper sensor cleanup
    private var activeCompassDialog: Dialog? = null
    private var activeCompassView: CompassView? = null

    // Pre-drawn button IDs per column (layout contains these)
    private val leeftijdBtnIds = listOf(
        R.id.btn_leeftijd_1, R.id.btn_leeftijd_2, R.id.btn_leeftijd_3, R.id.btn_leeftijd_4,
        R.id.btn_leeftijd_5, R.id.btn_leeftijd_6, R.id.btn_leeftijd_7, R.id.btn_leeftijd_8
    )
    private val geslachtBtnIds = listOf(
        R.id.btn_geslacht_1, R.id.btn_geslacht_2, R.id.btn_geslacht_3, R.id.btn_geslacht_4
    )
    private val kleedBtnIds = listOf(
        R.id.btn_kleed_1, R.id.btn_kleed_2, R.id.btn_kleed_3, R.id.btn_kleed_4,
        R.id.btn_kleed_5, R.id.btn_kleed_6, R.id.btn_kleed_7, R.id.btn_kleed_8
    )
    private val locationBtnIds = listOf(
        R.id.btn_location_1, R.id.btn_location_2, R.id.btn_location_3,
        R.id.btn_location_4, R.id.btn_location_5, R.id.btn_location_6,
        R.id.btn_location_7, R.id.btn_location_8
    )
    private val heightBtnIds = listOf(
        R.id.btn_height_1, R.id.btn_height_2, R.id.btn_height_3, R.id.btn_height_4,
        R.id.btn_height_5, R.id.btn_height_6, R.id.btn_height_7, R.id.btn_height_8
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_annotatie)

        // Initialize remarks EditText for location/height auto-tagging
        etOpmerkingen = findViewById(R.id.et_opmerkingen)

        // DEBUG: Log incoming Intent extras
        val rowPosition = intent.getIntExtra("extra_row_pos", -1)

        // Show progress while loading cache and populating UI
        lifecycleScope.launch {
            val progress = ProgressDialogHelper.show(this@AnnotatieScherm, getString(R.string.msg_loading_annotations))
            try {
                withContext(Dispatchers.IO) {
                    // load annotations into memory (SAF -> assets fallback)
                    AnnotationsManager.loadCache(this@AnnotatieScherm)
                }
                // populate the pre-drawn buttons
                populateAllColumnsFromCache()
                
                // Update count field labels based on current season
                updateCountFieldLabels()
                
                // Prefill count fields with existing record values if provided
                prefillCountFields()
            } finally {
                progress.dismiss()
            }
        }

        // Wire compass button
        findViewById<Button>(R.id.btn_compass).setOnClickListener {
            showCompassDialog()
        }

        // Wire action buttons
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.btn_ok).setOnClickListener {
            val resultMap = mutableMapOf<String, String?>()
            val selectedLabels = mutableListOf<String>()

            
            // For each group collect the selected option and label for summary
            for ((group, btns) in groupButtons) {
                val selectedOpt = btns.firstOrNull { it.isChecked }?.tag as? AnnotationOption
                if (selectedOpt != null) {
                    val storeKey = if (selectedOpt.veld.isNotBlank()) selectedOpt.veld else group
                    resultMap[storeKey] = selectedOpt.waarde
                    selectedLabels.add(selectedOpt.tekst)
                    
                    // DEBUG: Log each selected option
                }
            }
            // Checkboxes
            findViewById<CheckBox>(R.id.cb_markeren)?.takeIf { it.isChecked }?.let {
                resultMap["markeren"] = "1"
                selectedLabels.add("Markeren")
            }
            findViewById<CheckBox>(R.id.cb_markeren_lokaal)?.takeIf { it.isChecked }?.let {
                resultMap["markerenlokaal"] = "1"
                selectedLabels.add("Markeren Lokaal")
            }
            findViewById<CheckBox>(R.id.cb_tally)?.takeIf { it.isChecked }?.let {
                resultMap["teltype_C"] = "C"
                selectedLabels.add("Handteller")
            }

            // Manual count inputs - direct mapping (labels are adjusted based on season)
            // et_aantal always maps to record.aantal (main direction)
            // et_aantalterug always maps to record.aantalterug (opposite direction)
            val isZwSeizoen = isZwSeizoen()
            val mainLabel = if (isZwSeizoen) "ZW" else "NO"
            val returnLabel = if (isZwSeizoen) "NO" else "ZW"
            
            findViewById<EditText>(R.id.et_aantal)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["aantal"] = it
                selectedLabels.add("$mainLabel: $it")
            }
            findViewById<EditText>(R.id.et_aantalterug)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["aantalterug"] = it
                selectedLabels.add("$returnLabel: $it")
            }
            findViewById<EditText>(R.id.et_aantal_lokaal)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["lokaal"] = it
                selectedLabels.add("Lokaal: $it")
            }
            
            // Remarks/Comments
            findViewById<EditText>(R.id.et_opmerkingen)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["opmerkingen"] = it
                selectedLabels.add("Opm: $it")
            }
            
            // Sighting direction from compass
            selectedSightingDirection?.let { direction ->
                resultMap["sightingdirection"] = direction
                // Find the Dutch label for display
                val dirIndex = CompassView.DIRECTION_CODES.indexOf(direction)
                val label = if (dirIndex >= 0) CompassView.DIRECTION_LABELS[dirIndex] else direction
                selectedLabels.add("Richting: $label")
            }

            val payload = json.encodeToString(resultMap)

            // DEBUG: Log complete resultMap
            resultMap.forEach { (key, value) ->
            }
            if (resultMap.containsKey("kleed")) {
            } else {
                Log.w("AnnotatieScherm", "*** WARNING: resultMap does NOT contain 'kleed' key! ***")
            }

            // Build legacy summary text and timestamp for backward compatibility
            val summaryText = if (selectedLabels.isEmpty()) "" else selectedLabels.joinToString(", ")
            val tsSeconds = System.currentTimeMillis() / 1000L

            val out = Intent().apply {
                putExtra(EXTRA_ANNOTATIONS_JSON, payload)
                putExtra(EXTRA_TEXT, summaryText)
                putExtra(EXTRA_TS, tsSeconds)
                // CRITICAL FIX: Preserve row position so handler can match the correct record
                putExtra("extra_row_pos", rowPosition)
            }
            
            // DEBUG: Log outgoing Intent extras
            
            setResult(Activity.RESULT_OK, out)
            finish()
        }
    }

    private fun populateAllColumnsFromCache() {
        val cache = AnnotationsManager.getCached()

        // mapping group -> preIds + container id
        applyOptionsToPreDrawn("leeftijd", cache["leeftijd"].orEmpty(), leeftijdBtnIds, R.id.col_leeftijd)
        applyOptionsToPreDrawn("geslacht", cache["geslacht"].orEmpty(), geslachtBtnIds, R.id.col_geslacht)
        applyOptionsToPreDrawn("kleed", cache["kleed"].orEmpty(), kleedBtnIds, R.id.col_kleed)
        applyOptionsToPreDrawn("location", cache["location"].orEmpty(), locationBtnIds, R.id.col_location)
        applyOptionsToPreDrawn("height", cache["height"].orEmpty(), heightBtnIds, R.id.col_height)
    }

    /**
     * Fill pre-drawn buttons with the provided options.
     * - If options.size <= preIds.size: fill first N buttons, hide rest.
     * - All buttons are pre-drawn in the layout; no dynamic button creation.
     */
    private fun applyOptionsToPreDrawn(group: String, options: List<AnnotationOption>, preIds: List<Int>, containerId: Int) {
        val container = findViewById<LinearLayout>(containerId) ?: return
        val btnList = mutableListOf<AppCompatToggleButton>()

        // Fill pre-drawn buttons
        for ((idx, resId) in preIds.withIndex()) {
            val btn = findViewById<AppCompatToggleButton?>(resId)
            if (btn == null) continue
            if (idx < options.size) {
                val opt = options[idx]
                btn.text = opt.tekst
                btn.textOn = opt.tekst
                btn.textOff = opt.tekst
                btn.tag = opt
                btn.visibility = View.VISIBLE
                btn.isChecked = false
                btn.setOnClickListener { v -> onGroupButtonClicked(group, v as AppCompatToggleButton) }
            } else {
                // hide unused pre-drawn button
                btn.visibility = View.GONE
                btn.tag = null
                btn.setOnClickListener(null)
            }
            setToggleColor(btn)
            btnList.add(btn)
        }

        groupButtons[group] = btnList
    }

    /**
     * Called when any toggle in a group is clicked.
     * Enforces single-select within the group and updates colouring.
     * For location/height groups, also manages tags in remarks field.
     */
    private fun onGroupButtonClicked(group: String, clicked: AppCompatToggleButton) {
        val list = groupButtons[group] ?: return
        if (clicked.isChecked) {
            // DEBUG: Log button click with value from tag
            val selectedOpt = clicked.tag as? AnnotationOption
            if (selectedOpt != null) {
                
                // For location and height groups, add tag to remarks
                if (group == "location" || group == "height") {
                    addTagToRemarks(selectedOpt.tekst)
                }
            } else {
                Log.w("AnnotatieScherm", "Button $group clicked but tag is null or not AnnotationOption!")
            }
            
            // Single-select: uncheck other buttons in the group
            for (btn in list) {
                if (btn === clicked) {
                    setToggleColor(btn)
                } else {
                    if (btn.isChecked) {
                        // Remove tag from remarks if this was a location/height button
                        if (group == "location" || group == "height") {
                            val oldOpt = btn.tag as? AnnotationOption
                            if (oldOpt != null) {
                                removeTagFromRemarks(oldOpt.tekst)
                            }
                        }
                        btn.isChecked = false
                    }
                    setToggleColor(btn)
                }
            }
        } else {
            // toggled off
            
            // Remove tag from remarks if this is a location/height button
            if (group == "location" || group == "height") {
                val opt = clicked.tag as? AnnotationOption
                if (opt != null) {
                    removeTagFromRemarks(opt.tekst)
                }
            }
            
            setToggleColor(clicked)
        }
    }

    /**
     * Add a tag to the remarks field in format "[text]"
     */
    private fun addTagToRemarks(tag: String) {
        val current = etOpmerkingen.text.toString()
        val formattedTag = "[$tag]"
        
        // Check if tag already exists
        if (current.contains(formattedTag)) {
            return
        }
        
        // Add tag without separator (tags are adjacent)
        val newText = if (current.isBlank()) {
            formattedTag
        } else {
            "$current$formattedTag"
        }
        
        etOpmerkingen.setText(newText)
    }
    
    /**
     * Remove a tag from the remarks field
     */
    private fun removeTagFromRemarks(tag: String) {
        val current = etOpmerkingen.text.toString()
        val formattedTag = "[$tag]"
        
        // Remove the tag
        val newText = current.replace(formattedTag, "")
        
        etOpmerkingen.setText(newText)
    }

    private fun setToggleColor(btn: AppCompatToggleButton?) {
        if (btn == null) return
        // Ensure the button uses the selector background (preserve the blue border)
        // The style already sets this, but we refresh it to ensure it's not lost
        btn.setBackgroundResource(R.drawable.vt5_btn_selector)
        // Set text color to white for readability
        btn.setTextColor(Color.WHITE)
        // Refresh drawable state to apply the selector based on isChecked
        btn.refreshDrawableState()
    }
    
    /**
     * Prefill count fields with existing record values if provided via Intent extras.
     * This allows the user to see the current counts from the speech input and modify them.
     * 
     * The mapping from record fields to UI fields depends on the season:
     * - In ZW seizoen (Jul-Dec): record.aantal → et_aantal_zw, record.aantalterug → et_aantal_no
     * - In NO seizoen (Jan-Jun): record.aantal → et_aantal_no, record.aantalterug → et_aantal_zw
     */
    private fun prefillCountFields() {
        // Get existing count values from intent extras (these are raw record values)
        val recordAantal = intent.getStringExtra(EXTRA_RECORD_AANTAL)
        val recordAantalterug = intent.getStringExtra(EXTRA_RECORD_AANTALTERUG)
        val lokaal = intent.getStringExtra(EXTRA_LOKAAL)
        
        // Direct mapping - labels are already adjusted based on season
        // et_aantal always corresponds to record.aantal
        // et_aantalterug always corresponds to record.aantalterug
        recordAantal?.takeIf { it.isNotBlank() && it != "0" }?.let { value ->
            findViewById<EditText>(R.id.et_aantal)?.setText(value)
        }
        recordAantalterug?.takeIf { it.isNotBlank() && it != "0" }?.let { value ->
            findViewById<EditText>(R.id.et_aantalterug)?.setText(value)
        }
        
        // Prefill lokaal count field (this is direction-independent)
        lokaal?.takeIf { it.isNotBlank() && it != "0" }?.let { value ->
            findViewById<EditText>(R.id.et_aantal_lokaal)?.setText(value)
        }
    }
    
    /**
     * Update the count field labels based on the current season.
     * In ZW seizoen (Jul-Dec): "Aantal ZW :" and "Aantal NO :"
     * In NO seizoen (Jan-Jun): "Aantal NO :" and "Aantal ZW :"
     */
    private fun updateCountFieldLabels() {
        val isZwSeizoen = isZwSeizoen()
        
        val labelAantal = findViewById<TextView>(R.id.tv_label_aantal)
        val labelAantalterug = findViewById<TextView>(R.id.tv_label_aantalterug)
        
        if (isZwSeizoen) {
            // ZW seizoen: hoofdrichting is ZW, terug is NO
            labelAantal?.text = getString(R.string.annotation_count_zw)
            labelAantalterug?.text = getString(R.string.annotation_count_no)
        } else {
            // NO seizoen: hoofdrichting is NO, terug is ZW
            labelAantal?.text = getString(R.string.annotation_count_no)
            labelAantalterug?.text = getString(R.string.annotation_count_zw)
        }
    }
    
    /**
     * Helper to get the current season status.
     * Delegates to SeizoenUtils for consistent behavior across the app.
     */
    private fun isZwSeizoen(): Boolean = SeizoenUtils.isZwSeizoen()
    
    /**
     * Shows the compass dialog for selecting sighting direction.
     * The compass uses device sensors to show a real moving needle.
     * User can tap on any of the 16 wind directions to select it.
     */
    private fun showCompassDialog() {
        // Store original direction to restore on cancel
        val originalDirection = selectedSightingDirection
        
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_compass)
        dialog.setCancelable(true)
        
        val compassView = dialog.findViewById<CompassView>(R.id.compass_view)
        val tvSelectedDirection = dialog.findViewById<TextView>(R.id.tv_selected_direction)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_compass_cancel)
        val btnClear = dialog.findViewById<Button>(R.id.btn_compass_clear)
        val btnOk = dialog.findViewById<Button>(R.id.btn_compass_ok)
        
        // Track active dialog and compass view for cleanup on activity destroy
        activeCompassDialog = dialog
        activeCompassView = compassView
        
        // Set initial selection if already selected
        compassView.setSelectedDirection(selectedSightingDirection)
        updateCompassSelectionText(tvSelectedDirection, selectedSightingDirection)
        
        // Handle direction selection - immediately store the selected direction
        compassView.onDirectionSelectedListener = { _, label, code ->
            // Store the selected direction immediately when tapped
            selectedSightingDirection = code
            updateCompassSelectionText(tvSelectedDirection, code)
            // Update the main view display as well
            updateSightingDirectionDisplay()
        }
        
        // Handle deselection - when user taps the same direction again to undo
        compassView.onDirectionDeselectedListener = {
            selectedSightingDirection = null
            updateCompassSelectionText(tvSelectedDirection, null)
            updateSightingDirectionDisplay()
        }
        
        // Start sensors when dialog is shown
        compassView.startSensors()
        
        btnCancel.setOnClickListener {
            // Cancel reverts to the original selection (before opening dialog)
            selectedSightingDirection = originalDirection
            updateSightingDirectionDisplay()
            cleanupCompassDialog()
            dialog.dismiss()
        }
        
        btnClear.setOnClickListener {
            compassView.setSelectedDirection(null)
            selectedSightingDirection = null
            updateCompassSelectionText(tvSelectedDirection, null)
            updateSightingDirectionDisplay()
        }
        
        btnOk.setOnClickListener {
            // Selection is already stored, just close the dialog
            cleanupCompassDialog()
            dialog.dismiss()
        }
        
        // Stop sensors when dialog is dismissed (by back button, etc.)
        dialog.setOnDismissListener {
            cleanupCompassDialog()
        }
        
        dialog.show()
        
        // Set dialog width to match parent with some margin
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    /**
     * Cleans up the compass dialog resources, stopping sensors.
     */
    private fun cleanupCompassDialog() {
        activeCompassView?.stopSensors()
        activeCompassView = null
        activeCompassDialog = null
    }
    
    override fun onDestroy() {
        // Ensure compass sensors are stopped if activity is destroyed while dialog is showing
        cleanupCompassDialog()
        super.onDestroy()
    }
    
    /**
     * Updates the text showing the selected direction in the compass dialog.
     */
    private fun updateCompassSelectionText(textView: TextView, directionCode: String?) {
        if (directionCode == null) {
            textView.text = getString(R.string.compass_no_selection)
        } else {
            val dirIndex = CompassView.DIRECTION_CODES.indexOf(directionCode)
            val label = if (dirIndex >= 0) CompassView.DIRECTION_LABELS[dirIndex] else directionCode
            textView.text = getString(R.string.compass_selected, label)
        }
    }
    
    /**
     * Updates the sighting direction display in the main annotation screen.
     */
    private fun updateSightingDirectionDisplay() {
        val tvSelectedDirection = findViewById<TextView>(R.id.tv_selected_sighting_direction)
        
        if (selectedSightingDirection != null) {
            val dirIndex = CompassView.DIRECTION_CODES.indexOf(selectedSightingDirection)
            val label = if (dirIndex >= 0) CompassView.DIRECTION_LABELS[dirIndex] else selectedSightingDirection
            tvSelectedDirection?.text = label
        } else {
            tvSelectedDirection?.text = ""
        }
    }
}