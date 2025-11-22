package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.features.annotation.AnnotationsManager
import com.yvesds.vt5.features.annotation.AnnotationOption
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
 * - Als er meer opties aanwezig zijn dan vooraf getekende knoppen, worden extra buttons dynamisch toegevoegd.
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
    }

    private val json = Json { prettyPrint = false }

    // Map groupName -> list of ToggleButtons (including dynamically created ones)
    private val groupButtons = mutableMapOf<String, MutableList<AppCompatToggleButton>>()

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
        R.id.btn_location_4, R.id.btn_location_5, R.id.btn_location_6
    )
    private val heightBtnIds = listOf(
        R.id.btn_height_1, R.id.btn_height_2, R.id.btn_height_3, R.id.btn_height_4,
        R.id.btn_height_5, R.id.btn_height_6, R.id.btn_height_7, R.id.btn_height_8
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_annotatie)

        // Show progress while loading cache and populating UI
        lifecycleScope.launch {
            val progress = ProgressDialogHelper.show(this@AnnotatieScherm, getString(R.string.msg_loading_annotations))
            try {
                withContext(Dispatchers.IO) {
                    // load annotations into memory (SAF -> assets fallback)
                    AnnotationsManager.loadCache(this@AnnotatieScherm)
                }
                // populate the pre-drawn buttons and possibly append dynamic ones
                populateAllColumnsFromCache()
            } finally {
                progress.dismiss()
            }
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
                }
            }
            // Checkboxes
            findViewById<CheckBox>(R.id.cb_zw)?.takeIf { it.isChecked }?.let {
                resultMap["ZW"] = "1"
                selectedLabels.add("ZW")
            }
            findViewById<CheckBox>(R.id.cb_no)?.takeIf { it.isChecked }?.let {
                resultMap["NO"] = "1"
                selectedLabels.add("NO")
            }
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

            // Manual count inputs
            findViewById<EditText>(R.id.et_aantal_zw)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["aantal"] = it
                selectedLabels.add("ZW: $it")
            }
            findViewById<EditText>(R.id.et_aantal_no)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                resultMap["aantalterug"] = it
                selectedLabels.add("NO: $it")
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

            val payload = json.encodeToString(resultMap)

            // Build legacy summary text and timestamp for backward compatibility
            val summaryText = if (selectedLabels.isEmpty()) "" else selectedLabels.joinToString(", ")
            val tsSeconds = System.currentTimeMillis() / 1000L

            val out = Intent().apply {
                putExtra(EXTRA_ANNOTATIONS_JSON, payload)
                putExtra(EXTRA_TEXT, summaryText)
                putExtra(EXTRA_TS, tsSeconds)
            }
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
     * - If options.size > preIds.size: fill preIds, then append dynamic buttons to container.
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

        // If more options than preIds, append extra buttons dynamically
        if (options.size > preIds.size) {
            for (i in preIds.size until options.size) {
                val opt = options[i]
                val themeWrapper = android.view.ContextThemeWrapper(this, R.style.Widget_VT5_Button_Outlined)
                val dynBtn = androidx.appcompat.widget.AppCompatToggleButton(themeWrapper).apply {
                    text = opt.tekst
                    textOn = opt.tekst
                    textOff = opt.tekst
                    isAllCaps = false
                    tag = opt
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(6, 6, 6, 6)
                    layoutParams = lp
                    setPadding(18, 12, 18, 12)
                    setOnClickListener { v -> onGroupButtonClicked(group, v as AppCompatToggleButton) }
                }
                container.addView(dynBtn)
                btnList.add(dynBtn)            }
        }

        groupButtons[group] = btnList
    }

    /**
     * Called when any toggle in a group is clicked.
     * Enforces single-select within the group and updates colouring.
     */
    private fun onGroupButtonClicked(group: String, clicked: AppCompatToggleButton) {
        val list = groupButtons[group] ?: return
        if (clicked.isChecked) {
            for (btn in list) {
                if (btn === clicked) {
                    setToggleColor(btn)
                } else {
                    if (btn.isChecked) btn.isChecked = false
                    setToggleColor(btn)
                }
            }
        } else {
            // toggled off
            setToggleColor(clicked)
        }
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
}