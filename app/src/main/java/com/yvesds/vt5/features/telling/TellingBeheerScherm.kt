package com.yvesds.vt5.features.telling

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.annotation.AnnotationsManager
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.serverdata.model.SiteItem
import com.yvesds.vt5.features.serverdata.model.SpeciesItem
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.ServerTellingEnvelope
import com.yvesds.vt5.net.TrektellenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TellingBeheerScherm: Activity voor het beheren van opgeslagen tellingen.
 * 
 * Biedt een UI voor:
 * - Lijst van opgeslagen tellingen bekijken
 * - Telling selecteren en details bekijken
 * - Records bewerken, toevoegen, verwijderen
 * - Metadata bewerken
 * - Telling verwijderen
 * - Opslaan en uploaden naar server
 */
class TellingBeheerScherm : AppCompatActivity() {
    
    companion object {
        private const val TAG = "TellingBeheerScherm"
    }
    
    private lateinit var toolset: TellingBeheerToolset
    private lateinit var safHelper: SaFStorageHelper
    
    // Views
    private lateinit var tvTitel: TextView
    private lateinit var btnTerug: MaterialButton
    private lateinit var layoutList: LinearLayout
    private lateinit var layoutDetail: View
    private lateinit var tvLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rvTellingen: RecyclerView
    
    // Detail views
    private lateinit var tvDetailFilename: TextView
    private lateinit var tvDetailInfo: TextView
    private lateinit var tvDetailTelpost: TextView
    private lateinit var btnMetadataBewerken: MaterialButton
    private lateinit var btnRecordToevoegen: MaterialButton
    private lateinit var btnOpslaan: MaterialButton
    private lateinit var btnTellingVerwijderen: MaterialButton
    private lateinit var rvRecords: RecyclerView
    
    // State
    private var tellingenList: List<TellingFileInfo> = emptyList()
    private var currentFilename: String? = null
    private var currentEnvelope: ServerTellingEnvelope? = null
    private var hasUnsavedChanges = false
    
    // Adapters
    private lateinit var tellingenAdapter: TellingenAdapter
    private lateinit var recordsAdapter: RecordsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.scherm_telling_beheer)
        
        safHelper = SaFStorageHelper(this)
        toolset = TellingBeheerToolset(this, safHelper)
        
        initViews()
        setupListeners()
        loadTellingenList()
    }
    
    private fun initViews() {
        tvTitel = findViewById(R.id.tvTitel)
        btnTerug = findViewById(R.id.btnTerug)
        layoutList = findViewById(R.id.layoutList)
        layoutDetail = findViewById(R.id.layoutDetail)
        tvLoading = findViewById(R.id.tvLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvTellingen = findViewById(R.id.rvTellingen)
        
        tvDetailFilename = findViewById(R.id.tvDetailFilename)
        tvDetailInfo = findViewById(R.id.tvDetailInfo)
        tvDetailTelpost = findViewById(R.id.tvDetailTelpost)
        btnMetadataBewerken = findViewById(R.id.btnMetadataBewerken)
        btnRecordToevoegen = findViewById(R.id.btnRecordToevoegen)
        btnOpslaan = findViewById(R.id.btnOpslaan)
        btnTellingVerwijderen = findViewById(R.id.btnTellingVerwijderen)
        rvRecords = findViewById(R.id.rvRecords)
        
        // Setup RecyclerViews
        tellingenAdapter = TellingenAdapter { info -> onTellingSelected(info) }
        rvTellingen.layoutManager = LinearLayoutManager(this)
        rvTellingen.adapter = tellingenAdapter
        
        recordsAdapter = RecordsAdapter(
            onEdit = { index, item -> showEditRecordDialog(index, item) },
            onDelete = { index, item -> showDeleteRecordDialog(index, item) }
        )
        rvRecords.layoutManager = LinearLayoutManager(this)
        rvRecords.adapter = recordsAdapter
    }
    
    private fun setupListeners() {
        btnTerug.setOnClickListener {
            if (layoutDetail.visibility == View.VISIBLE) {
                if (hasUnsavedChanges) {
                    showUnsavedChangesDialog()
                } else {
                    showListView()
                }
            } else {
                finish()
            }
        }
        
        btnMetadataBewerken.setOnClickListener {
            currentEnvelope?.let { showEditMetadataDialog(it) }
        }
        
        btnRecordToevoegen.setOnClickListener {
            showAddRecordDialog()
        }
        
        btnOpslaan.setOnClickListener {
            saveCurrentTelling()
        }
        
        btnTellingVerwijderen.setOnClickListener {
            currentFilename?.let { showDeleteTellingDialog(it) }
        }
    }
    
    private fun loadTellingenList() {
        tvLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        rvTellingen.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                tellingenList = withContext(Dispatchers.IO) {
                    toolset.listSavedTellingen()
                }
                
                tvLoading.visibility = View.GONE
                
                if (tellingenList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rvTellingen.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rvTellingen.visibility = View.VISIBLE
                    tellingenAdapter.submitList(tellingenList)
                }
            } catch (e: Exception) {
                tvLoading.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Fout bij laden: ${e.message}"
            }
        }
    }
    
    private fun onTellingSelected(info: TellingFileInfo) {
        lifecycleScope.launch {
            try {
                val envelope = withContext(Dispatchers.IO) {
                    toolset.loadTelling(info.filename)
                }
                
                if (envelope != null) {
                    currentFilename = info.filename
                    currentEnvelope = envelope
                    hasUnsavedChanges = false
                    showDetailView(info, envelope)
                } else {
                    Toast.makeText(this@TellingBeheerScherm, "Kon telling niet laden", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@TellingBeheerScherm, "Fout: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showListView() {
        tvTitel.text = getString(R.string.beheer_titel)
        layoutList.visibility = View.VISIBLE
        layoutDetail.visibility = View.GONE
        currentFilename = null
        currentEnvelope = null
        hasUnsavedChanges = false
        loadTellingenList()
    }
    
    private fun showDetailView(info: TellingFileInfo, envelope: ServerTellingEnvelope) {
        tvTitel.text = getString(R.string.beheer_terug)
        layoutList.visibility = View.GONE
        layoutDetail.visibility = View.VISIBLE
        
        tvDetailFilename.text = info.filename
        tvDetailInfo.text = getString(R.string.beheer_telling_info, envelope.data.size, 
            envelope.data.map { it.soortid }.toSet().size)
        tvDetailTelpost.text = "Telpost: ${envelope.telpostid} • Tellers: ${envelope.tellers}"
        
        updateRecordsList(envelope)
    }
    
    private fun updateRecordsList(envelope: ServerTellingEnvelope) {
        recordsAdapter.submitList(envelope.data.mapIndexed { index, item -> index to item })
    }
    
    private fun showEditMetadataDialog(envelope: ServerTellingEnvelope) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_metadata, null)
        
        // Find all views
        val acTelpost = dialogView.findViewById<AutoCompleteTextView>(R.id.acTelpost)
        val etDatum = dialogView.findViewById<TextInputEditText>(R.id.etDatum)
        val etBegintijd = dialogView.findViewById<TextInputEditText>(R.id.etBegintijd)
        val etEindtijd = dialogView.findViewById<TextInputEditText>(R.id.etEindtijd)
        val etTellers = dialogView.findViewById<TextInputEditText>(R.id.etTellers)
        val etWeer = dialogView.findViewById<TextInputEditText>(R.id.etWeer)
        val etOpmerkingen = dialogView.findViewById<TextInputEditText>(R.id.etOpmerkingen)
        
        // State for tracking selected values
        var selectedTelpostId = envelope.telpostid
        var selectedBegintijdEpoch = envelope.begintijd.toLongOrNull() ?: 0L
        var selectedEindtijdEpoch = envelope.eindtijd.toLongOrNull() ?: 0L
        
        // Load telpost data and populate dropdown
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    ServerDataCache.getOrLoad(this@TellingBeheerScherm)
                }
                
                val sites = snapshot.sitesById.values
                    .sortedBy { it.telpostnaam.lowercase(Locale.getDefault()) }
                
                val labels = sites.map { it.telpostnaam }
                val ids = sites.map { it.telpostid }
                
                val adapter = ArrayAdapter(this@TellingBeheerScherm, android.R.layout.simple_list_item_1, labels)
                acTelpost.setAdapter(adapter)
                
                // Pre-select current telpost
                val currentIdx = ids.indexOf(envelope.telpostid)
                if (currentIdx >= 0) {
                    acTelpost.setText(labels[currentIdx], false)
                }
                
                acTelpost.setOnItemClickListener { _, _, pos, _ ->
                    selectedTelpostId = ids[pos]
                }
            } catch (e: Exception) {
                // Fallback: just show telpostid as text
                acTelpost.setText(envelope.telpostid)
            }
        }
        
        // Date/time formatters
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        
        // Convert epoch to date string for display
        fun epochToDateStr(epoch: Long): String {
            return if (epoch > 0) dateFormat.format(Date(epoch * 1000)) else ""
        }
        
        fun epochToTimeStr(epoch: Long): String {
            return if (epoch > 0) timeFormat.format(Date(epoch * 1000)) else ""
        }
        
        // Prefill current values
        etTellers.setText(envelope.tellers)
        etWeer.setText(envelope.weer)
        etOpmerkingen.setText(envelope.opmerkingen)
        
        // Prefill date (from begintijd)
        if (selectedBegintijdEpoch > 0) {
            etDatum.setText(epochToDateStr(selectedBegintijdEpoch))
        }
        
        // Prefill times
        etBegintijd.setText(epochToTimeStr(selectedBegintijdEpoch))
        etEindtijd.setText(epochToTimeStr(selectedEindtijdEpoch))
        
        // Setup date picker
        etDatum.setOnClickListener {
            val cal = Calendar.getInstance()
            if (selectedBegintijdEpoch > 0) {
                cal.timeInMillis = selectedBegintijdEpoch * 1000
            }
            
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val mm = (month + 1).toString().padStart(2, '0')
                    val dd = dayOfMonth.toString().padStart(2, '0')
                    etDatum.setText("$year-$mm-$dd")
                    
                    // Update epoch values with new date
                    val datePart = "$year-$mm-$dd"
                    val beginTimePart = etBegintijd.text?.toString() ?: "00:00"
                    val endTimePart = etEindtijd.text?.toString() ?: "00:00"
                    
                    try {
                        selectedBegintijdEpoch = dateTimeFormat.parse("$datePart $beginTimePart")?.time?.div(1000) ?: selectedBegintijdEpoch
                        selectedEindtijdEpoch = dateTimeFormat.parse("$datePart $endTimePart")?.time?.div(1000) ?: selectedEindtijdEpoch
                    } catch (_: Exception) {}
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        
        // Setup begintijd time picker
        etBegintijd.setOnClickListener {
            showTimePickerDialog(etBegintijd, selectedBegintijdEpoch) { newEpoch ->
                selectedBegintijdEpoch = newEpoch
            }
        }
        
        // Setup eindtijd time picker
        etEindtijd.setOnClickListener {
            showTimePickerDialog(etEindtijd, selectedEindtijdEpoch) { newEpoch ->
                selectedEindtijdEpoch = newEpoch
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_metadata_bewerken))
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                // Build epoch strings from date + time
                val datePart = etDatum.text?.toString() ?: ""
                val beginTimePart = etBegintijd.text?.toString() ?: "00:00"
                val endTimePart = etEindtijd.text?.toString() ?: "00:00"
                
                var finalBegintijd = selectedBegintijdEpoch.toString()
                var finalEindtijd = selectedEindtijdEpoch.toString()
                
                if (datePart.isNotBlank()) {
                    try {
                        dateTimeFormat.parse("$datePart $beginTimePart")?.let {
                            finalBegintijd = (it.time / 1000).toString()
                        }
                        dateTimeFormat.parse("$datePart $endTimePart")?.let {
                            finalEindtijd = (it.time / 1000).toString()
                        }
                    } catch (_: Exception) {}
                }
                
                val updates = MetadataUpdates(
                    telpostid = selectedTelpostId,
                    begintijd = finalBegintijd,
                    eindtijd = finalEindtijd,
                    tellers = etTellers.text?.toString(),
                    weer = etWeer.text?.toString(),
                    opmerkingen = etOpmerkingen.text?.toString()
                )
                currentEnvelope = toolset.updateMetadata(envelope, updates)
                hasUnsavedChanges = true
                currentEnvelope?.let { updateDetailView(it) }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    /**
     * Show a time picker dialog with hour and minute spinners.
     */
    private fun showTimePickerDialog(
        targetEditText: TextInputEditText,
        currentEpoch: Long,
        onTimeSelected: (Long) -> Unit
    ) {
        val cal = Calendar.getInstance()
        if (currentEpoch > 0) {
            cal.timeInMillis = currentEpoch * 1000
        }
        
        val startHour = cal.get(Calendar.HOUR_OF_DAY)
        val startMinute = cal.get(Calendar.MINUTE)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 32, 48, 16)
        }
        
        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = startHour
            wrapSelectorWheel = true
        }
        
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = startMinute
            wrapSelectorWheel = true
            setFormatter { v -> v.toString().padStart(2, '0') }
        }
        
        // Auto-increment hour when going 59->0 or 0->59
        var lastMinute = startMinute
        minutePicker.setOnValueChangedListener { _, _, newVal ->
            if (newVal == 0 && lastMinute == 59) {
                hourPicker.value = (hourPicker.value + 1) % 24
            } else if (newVal == 59 && lastMinute == 0) {
                hourPicker.value = if (hourPicker.value == 0) 23 else hourPicker.value - 1
            }
            lastMinute = newVal
        }
        
        row.addView(hourPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(minutePicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_tijd_kiezen))
            .setView(row)
            .setPositiveButton("OK") { _, _ ->
                val hh = hourPicker.value.toString().padStart(2, '0')
                val mm = minutePicker.value.toString().padStart(2, '0')
                targetEditText.setText("$hh:$mm")
                
                // Calculate new epoch
                val newCal = Calendar.getInstance()
                if (currentEpoch > 0) {
                    newCal.timeInMillis = currentEpoch * 1000
                }
                newCal.set(Calendar.HOUR_OF_DAY, hourPicker.value)
                newCal.set(Calendar.MINUTE, minutePicker.value)
                newCal.set(Calendar.SECOND, 0)
                
                onTimeSelected(newCal.timeInMillis / 1000)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun showAddRecordDialog() {
        showFullRecordDialog(null, null)
    }
    
    private fun showEditRecordDialog(index: Int, item: ServerTellingDataItem) {
        showFullRecordDialog(index, item)
    }
    
    /**
     * Show a comprehensive dialog for editing or adding a record.
     * Includes species selection with searchable dropdown and all annotation fields.
     * 
     * @param index Record index (null for adding new record)
     * @param existingItem Existing record to edit (null for adding new record)
     */
    private fun showFullRecordDialog(index: Int?, existingItem: ServerTellingDataItem?) {
        val envelope = currentEnvelope ?: return
        val isEditing = index != null && existingItem != null
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_record_full, null)
        
        // Find all views
        val acSoort = dialogView.findViewById<AutoCompleteTextView>(R.id.acSoort)
        val etAantal = dialogView.findViewById<TextInputEditText>(R.id.etAantal)
        val etAantalterug = dialogView.findViewById<TextInputEditText>(R.id.etAantalterug)
        val etLokaal = dialogView.findViewById<TextInputEditText>(R.id.etLokaal)
        val acRichting = dialogView.findViewById<AutoCompleteTextView>(R.id.acRichting)
        val acLeeftijd = dialogView.findViewById<AutoCompleteTextView>(R.id.acLeeftijd)
        val acGeslacht = dialogView.findViewById<AutoCompleteTextView>(R.id.acGeslacht)
        val acKleed = dialogView.findViewById<AutoCompleteTextView>(R.id.acKleed)
        val cbMarkeren = dialogView.findViewById<android.widget.CheckBox>(R.id.cbMarkeren)
        val cbMarkerenLokaal = dialogView.findViewById<android.widget.CheckBox>(R.id.cbMarkerenLokaal)
        val etOpmerkingen = dialogView.findViewById<TextInputEditText>(R.id.etRecordOpmerkingen)
        
        // State variables
        var selectedSoortId = existingItem?.soortid ?: ""
        var selectedRichting = existingItem?.richting ?: ""
        var selectedLeeftijd = existingItem?.leeftijd ?: ""
        var selectedGeslacht = existingItem?.geslacht ?: ""
        var selectedKleed = existingItem?.kleed ?: ""
        
        // Prefill existing values
        if (isEditing) {
            acSoort.setText(existingItem!!.soortid)
            etAantal.setText(existingItem.aantal)
            etAantalterug.setText(existingItem.aantalterug)
            etLokaal.setText(existingItem.lokaal)
            acRichting.setText(existingItem.richting)
            acLeeftijd.setText(existingItem.leeftijd)
            acGeslacht.setText(existingItem.geslacht)
            acKleed.setText(existingItem.kleed)
            cbMarkeren.isChecked = existingItem.markeren == "1"
            cbMarkerenLokaal.isChecked = existingItem.markerenlokaal == "1"
            etOpmerkingen.setText(existingItem.opmerkingen)
        }
        
        // Load species and annotation data
        lifecycleScope.launch {
            try {
                // Load species data
                val snapshot = withContext(Dispatchers.IO) {
                    ServerDataCache.getOrLoad(this@TellingBeheerScherm)
                }
                
                // Populate species dropdown
                val species = snapshot.speciesById.values.toList()
                    .sortedBy { it.soortnaam.lowercase(Locale.getDefault()) }
                
                val speciesLabels = species.map { "${it.soortnaam} (${it.soortid})" }
                val speciesIds = species.map { it.soortid }
                
                val speciesAdapter = ArrayAdapter(
                    this@TellingBeheerScherm,
                    android.R.layout.simple_dropdown_item_1line,
                    speciesLabels
                )
                acSoort.setAdapter(speciesAdapter)
                acSoort.threshold = 1 // Start filtering after 1 character
                
                // Set current species display name if editing
                if (isEditing && selectedSoortId.isNotBlank()) {
                    val currentIdx = speciesIds.indexOf(selectedSoortId)
                    if (currentIdx >= 0) {
                        acSoort.setText(speciesLabels[currentIdx], false)
                    }
                }
                
                acSoort.setOnItemClickListener { _, _, pos, _ ->
                    // Filter to find actual match
                    val selectedLabel = acSoort.adapter.getItem(pos) as String
                    val idx = speciesLabels.indexOf(selectedLabel)
                    if (idx >= 0) {
                        selectedSoortId = speciesIds[idx]
                    }
                }
                
                // Also handle manual text entry (allow typing soortid directly)
                acSoort.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val text = acSoort.text.toString().trim()
                        // Check if it's a known soortid
                        if (speciesIds.contains(text)) {
                            selectedSoortId = text
                        } else {
                            // Check if it matches a label
                            val idx = speciesLabels.indexOfFirst { it.equals(text, ignoreCase = true) }
                            if (idx >= 0) {
                                selectedSoortId = speciesIds[idx]
                            } else {
                                // Just use the entered text as soortid
                                selectedSoortId = text
                            }
                        }
                    }
                }
                
                // Load annotations
                withContext(Dispatchers.IO) {
                    AnnotationsManager.loadCache(this@TellingBeheerScherm)
                }
                val annotations = AnnotationsManager.getCached()
                
                // Populate richting dropdown
                val richtingOptions = listOf("", "ZW", "NO")
                val richtingAdapter = ArrayAdapter(this@TellingBeheerScherm, android.R.layout.simple_list_item_1, richtingOptions)
                acRichting.setAdapter(richtingAdapter)
                acRichting.setOnItemClickListener { _, _, pos, _ ->
                    selectedRichting = richtingOptions[pos]
                }
                
                // Populate leeftijd dropdown - use waarde (server code) for values
                val leeftijdOptions = listOf("") + (annotations["leeftijd"]?.map { it.waarde ?: "" } ?: emptyList())
                val leeftijdLabels = listOf("(geen)") + (annotations["leeftijd"]?.map { it.tekst } ?: emptyList())
                val leeftijdAdapter = ArrayAdapter(this@TellingBeheerScherm, android.R.layout.simple_list_item_1, leeftijdLabels)
                acLeeftijd.setAdapter(leeftijdAdapter)
                if (selectedLeeftijd.isNotBlank()) {
                    val idx = leeftijdOptions.indexOf(selectedLeeftijd)
                    if (idx >= 0) acLeeftijd.setText(leeftijdLabels[idx], false)
                }
                acLeeftijd.setOnItemClickListener { _, _, pos, _ ->
                    selectedLeeftijd = leeftijdOptions[pos]
                }
                
                // Populate geslacht dropdown - use waarde (server code) for values
                val geslachtOptions = listOf("") + (annotations["geslacht"]?.map { it.waarde ?: "" } ?: emptyList())
                val geslachtLabels = listOf("(geen)") + (annotations["geslacht"]?.map { it.tekst } ?: emptyList())
                val geslachtAdapter = ArrayAdapter(this@TellingBeheerScherm, android.R.layout.simple_list_item_1, geslachtLabels)
                acGeslacht.setAdapter(geslachtAdapter)
                if (selectedGeslacht.isNotBlank()) {
                    val idx = geslachtOptions.indexOf(selectedGeslacht)
                    if (idx >= 0) acGeslacht.setText(geslachtLabels[idx], false)
                }
                acGeslacht.setOnItemClickListener { _, _, pos, _ ->
                    selectedGeslacht = geslachtOptions[pos]
                }
                
                // Populate kleed dropdown - use waarde (server code) for values
                val kleedOptions = listOf("") + (annotations["kleed"]?.map { it.waarde ?: "" } ?: emptyList())
                val kleedLabels = listOf("(geen)") + (annotations["kleed"]?.map { it.tekst } ?: emptyList())
                val kleedAdapter = ArrayAdapter(this@TellingBeheerScherm, android.R.layout.simple_list_item_1, kleedLabels)
                acKleed.setAdapter(kleedAdapter)
                if (selectedKleed.isNotBlank()) {
                    val idx = kleedOptions.indexOf(selectedKleed)
                    if (idx >= 0) acKleed.setText(kleedLabels[idx], false)
                }
                acKleed.setOnItemClickListener { _, _, pos, _ ->
                    selectedKleed = kleedOptions[pos]
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load species/annotation data: ${e.message}", e)
                Toast.makeText(this@TellingBeheerScherm, "Kon soorten niet laden", Toast.LENGTH_SHORT).show()
            }
        }
        
        val title = if (isEditing) {
            getString(R.string.beheer_record_volledig_bewerken)
        } else {
            getString(R.string.beheer_record_toevoegen)
        }
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(if (isEditing) "Opslaan" else "Toevoegen") { _, _ ->
                // Get final soortid from the text field if not set
                if (selectedSoortId.isBlank()) {
                    val text = acSoort.text.toString().trim()
                    // Try to extract soortid from "naam (id)" format
                    val match = Regex("\\(([^)]+)\\)$").find(text)
                    selectedSoortId = match?.groupValues?.get(1) ?: text
                }
                
                if (selectedSoortId.isBlank()) {
                    Toast.makeText(this, "Soort is verplicht", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // All numeric fields must be "0" when empty/blank, not ""
                // Server expects string values, empty strings must be "" for non-numeric fields
                val aantalRaw = etAantal.text?.toString()?.trim() ?: ""
                val aantalterugRaw = etAantalterug.text?.toString()?.trim() ?: ""
                val lokaalRaw = etLokaal.text?.toString()?.trim() ?: ""
                val opmerkingen = etOpmerkingen.text?.toString() ?: ""
                
                // Numeric fields: use "0" when blank
                val aantal = aantalRaw.ifBlank { "0" }
                val aantalterug = aantalterugRaw.ifBlank { "0" }
                val lokaal = lokaalRaw.ifBlank { "0" }
                
                // Checkboxes: "0" or "1" (never "")
                val markeren = if (cbMarkeren.isChecked) "1" else "0"
                val markerenlokaal = if (cbMarkerenLokaal.isChecked) "1" else "0"
                
                // Calculate totaalaantal
                val aantalInt = aantal.toIntOrNull() ?: 0
                val aantalterugInt = aantalterug.toIntOrNull() ?: 0
                val lokaalInt = lokaal.toIntOrNull() ?: 0
                val totaal = aantalInt + aantalterugInt + lokaalInt
                
                if (isEditing) {
                    val updatedRecord = existingItem!!.copy(
                        soortid = selectedSoortId,
                        aantal = aantal,
                        aantalterug = aantalterug,
                        lokaal = lokaal,
                        totaalaantal = totaal.toString(),
                        richting = selectedRichting,
                        leeftijd = selectedLeeftijd,
                        geslacht = selectedGeslacht,
                        kleed = selectedKleed,
                        markeren = markeren,
                        markerenlokaal = markerenlokaal,
                        opmerkingen = opmerkingen
                    )
                    currentEnvelope = toolset.updateRecord(envelope, index!!, updatedRecord)
                } else {
                    // Create new record with all fields properly set
                    // Numeric fields must be "0" when blank, not ""
                    val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
                    val currentTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    
                    val newRecord = ServerTellingDataItem(
                        // IDs will be set by addRecord
                        idLocal = "",
                        tellingid = "",
                        soortid = selectedSoortId,
                        aantal = aantal,
                        richting = selectedRichting,
                        aantalterug = aantalterug,
                        richtingterug = "",
                        sightingdirection = "",
                        lokaal = lokaal,
                        aantal_plus = "0",
                        aantalterug_plus = "0",
                        lokaal_plus = "0",
                        markeren = markeren,
                        markerenlokaal = markerenlokaal,
                        geslacht = selectedGeslacht,
                        leeftijd = selectedLeeftijd,
                        kleed = selectedKleed,
                        opmerkingen = opmerkingen,
                        trektype = "",
                        teltype = "",
                        location = "",
                        height = "",
                        tijdstip = nowEpoch,
                        groupid = "",
                        uploadtijdstip = currentTimestamp,
                        totaalaantal = totaal.toString()
                    )
                    currentEnvelope = toolset.addRecord(envelope, newRecord, generateId = true)
                }
                
                hasUnsavedChanges = true
                currentEnvelope?.let { updateRecordsList(it) }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun showDeleteRecordDialog(index: Int, item: ServerTellingDataItem) {
        val envelope = currentEnvelope ?: return
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_record_verwijderen))
            .setMessage("Weet je zeker dat je record ${index + 1} (${item.soortid}) wilt verwijderen?")
            .setPositiveButton("Verwijderen") { _, _ ->
                currentEnvelope = toolset.deleteRecord(envelope, index)
                hasUnsavedChanges = true
                currentEnvelope?.let { updateRecordsList(it) }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun showDeleteTellingDialog(filename: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_verwijder_bevestig_titel))
            .setMessage(getString(R.string.beheer_verwijder_bevestig_msg, filename))
            .setPositiveButton("Verwijderen") { _, _ ->
                deleteTelling(filename)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun deleteTelling(filename: String) {
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    toolset.deleteTelling(filename)
                }
                
                if (success) {
                    Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_verwijderd), Toast.LENGTH_SHORT).show()
                    showListView()
                } else {
                    Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_verwijder_fout), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@TellingBeheerScherm, "Fout: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun saveCurrentTelling() {
        val filename = currentFilename ?: return
        val envelope = currentEnvelope ?: return
        
        // Show confirmation dialog for upload
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_upload_bevestig_titel))
            .setMessage(getString(R.string.beheer_upload_bevestig_msg))
            .setPositiveButton(getString(R.string.beheer_upload_en_opslaan)) { _, _ ->
                performSaveAndUpload(filename, envelope)
            }
            .setNegativeButton(getString(R.string.beheer_alleen_opslaan)) { _, _ ->
                performSaveOnly(filename, envelope)
            }
            .setNeutralButton("Annuleren", null)
            .show()
    }
    
    /**
     * Save locally only without uploading to server.
     */
    private fun performSaveOnly(filename: String, envelope: ServerTellingEnvelope) {
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    toolset.saveTelling(envelope, filename)
                }
                
                if (success) {
                    hasUnsavedChanges = false
                    Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_opgeslagen), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_opslaan_fout), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Save only failed: ${e.message}", e)
                Toast.makeText(this@TellingBeheerScherm, "Fout: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Save locally and upload to server (like Afronden in TellingScherm).
     */
    private fun performSaveAndUpload(filename: String, envelope: ServerTellingEnvelope) {
        lifecycleScope.launch {
            try {
                // First save locally
                val saveSuccess = withContext(Dispatchers.IO) {
                    toolset.saveTelling(envelope, filename)
                }
                
                if (!saveSuccess) {
                    Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_opslaan_fout), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Get credentials
                val creds = CredentialsStore(this@TellingBeheerScherm)
                val user = creds.getUsername().orEmpty()
                val pass = creds.getPassword().orEmpty()
                
                if (user.isBlank() || pass.isBlank()) {
                    AlertDialog.Builder(this@TellingBeheerScherm)
                        .setTitle(getString(R.string.beheer_upload_fout_titel))
                        .setMessage(getString(R.string.beheer_geen_credentials))
                        .setPositiveButton("OK", null)
                        .show()
                    return@launch
                }
                
                // Show uploading toast
                Toast.makeText(this@TellingBeheerScherm, getString(R.string.beheer_uploading), Toast.LENGTH_SHORT).show()
                
                // Prepare final envelope with current timestamp
                val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val finalEnvelope = envelope.copy(
                    uploadtijdstip = nowFormatted,
                    nrec = envelope.data.size.toString(),
                    nsoort = envelope.data.map { it.soortid }.toSet().size.toString()
                )
                
                // Upload to server
                val baseUrl = "https://trektellen.nl"
                val language = "dutch"
                val versie = "1845"
                
                val (ok, resp) = withContext(Dispatchers.IO) {
                    try {
                        TrektellenApi.postCountsSave(baseUrl, language, versie, user, pass, listOf(finalEnvelope))
                    } catch (ex: Exception) {
                        Log.w(TAG, "postCountsSave exception: ${ex.message}", ex)
                        false to (ex.message ?: "exception")
                    }
                }
                
                if (ok) {
                    hasUnsavedChanges = false
                    
                    // Parse returned onlineId if available
                    val returnedOnlineId = parseOnlineIdFromResponse(resp)
                    if (!returnedOnlineId.isNullOrBlank() && returnedOnlineId != envelope.onlineid) {
                        // Update envelope with new onlineId and save again
                        val updatedEnvelope = finalEnvelope.copy(onlineid = returnedOnlineId)
                        currentEnvelope = updatedEnvelope
                        withContext(Dispatchers.IO) {
                            toolset.saveTelling(updatedEnvelope, filename)
                        }
                        updateDetailView(updatedEnvelope)
                    }
                    
                    AlertDialog.Builder(this@TellingBeheerScherm)
                        .setTitle(getString(R.string.beheer_upload_succes_titel))
                        .setMessage(getString(R.string.beheer_upload_succes_msg))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@TellingBeheerScherm)
                        .setTitle(getString(R.string.beheer_upload_fout_titel))
                        .setMessage(getString(R.string.beheer_upload_fout_msg, resp))
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Save and upload failed: ${e.message}", e)
                AlertDialog.Builder(this@TellingBeheerScherm)
                    .setTitle(getString(R.string.beheer_upload_fout_titel))
                    .setMessage("Fout: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    /**
     * Parse onlineId from server response.
     * Response format is typically: "onlineid|timestamp" or just contains onlineId.
     */
    private fun parseOnlineIdFromResponse(response: String): String? {
        return try {
            val trimmed = response.trim()
            if (trimmed.contains("|")) {
                trimmed.split("|").firstOrNull()?.trim()
            } else {
                // Try to extract a number from the response
                val match = Regex("\\d+").find(trimmed)
                match?.value
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse onlineId: ${e.message}")
            null
        }
    }
    
    private fun showUnsavedChangesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Onopgeslagen wijzigingen")
            .setMessage("Je hebt wijzigingen die nog niet zijn opgeslagen. Wil je deze opslaan?")
            .setPositiveButton("Opslaan") { _, _ ->
                saveCurrentTelling()
                showListView()
            }
            .setNegativeButton("Negeren") { _, _ ->
                showListView()
            }
            .setNeutralButton("Annuleren", null)
            .show()
    }
    
    private fun updateDetailView(envelope: ServerTellingEnvelope) {
        tvDetailInfo.text = getString(R.string.beheer_telling_info, envelope.data.size, 
            envelope.data.map { it.soortid }.toSet().size)
        tvDetailTelpost.text = "Telpost: ${envelope.telpostid} • Tellers: ${envelope.tellers}"
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (layoutDetail.visibility == View.VISIBLE) {
            if (hasUnsavedChanges) {
                showUnsavedChangesDialog()
            } else {
                showListView()
            }
        } else {
            super.onBackPressed()
        }
    }
    
    // ========================================================================
    // ADAPTERS
    // ========================================================================
    
    private inner class TellingenAdapter(
        private val onClick: (TellingFileInfo) -> Unit
    ) : RecyclerView.Adapter<TellingenAdapter.ViewHolder>() {
        
        private var items: List<TellingFileInfo> = emptyList()
        
        fun submitList(newItems: List<TellingFileInfo>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_telling_beheer, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }
        
        override fun getItemCount() = items.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFilename: TextView = itemView.findViewById(R.id.tvFilename)
            private val tvInfo: TextView = itemView.findViewById(R.id.tvInfo)
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
            private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
            
            fun bind(info: TellingFileInfo) {
                tvFilename.text = info.filename
                tvInfo.text = getString(R.string.beheer_telling_info, info.nrec, info.nsoort)
                
                val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                tvTimestamp.text = dateFormat.format(Date(info.lastModified))
                
                if (info.isActive) {
                    tvBadge.visibility = View.VISIBLE
                    tvBadge.text = getString(R.string.beheer_actieve_telling)
                } else {
                    tvBadge.visibility = View.GONE
                }
                
                itemView.setOnClickListener { onClick(info) }
            }
        }
    }
    
    private inner class RecordsAdapter(
        private val onEdit: (Int, ServerTellingDataItem) -> Unit,
        private val onDelete: (Int, ServerTellingDataItem) -> Unit
    ) : RecyclerView.Adapter<RecordsAdapter.ViewHolder>() {
        
        private var items: List<Pair<Int, ServerTellingDataItem>> = emptyList()
        
        fun submitList(newItems: List<Pair<Int, ServerTellingDataItem>>) {
            items = newItems
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_telling_record, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (index, item) = items[position]
            holder.bind(index, item)
        }
        
        override fun getItemCount() = items.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
            private val tvSoortId: TextView = itemView.findViewById(R.id.tvSoortId)
            private val tvAantal: TextView = itemView.findViewById(R.id.tvAantal)
            private val tvOpmerkingen: TextView = itemView.findViewById(R.id.tvOpmerkingen)
            private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
            
            fun bind(index: Int, item: ServerTellingDataItem) {
                tvIndex.text = "#${index + 1}"
                tvSoortId.text = "Soort: ${item.soortid}"
                tvAantal.text = "Aantal: ${item.aantal}"
                
                if (item.opmerkingen.isNotBlank()) {
                    tvOpmerkingen.visibility = View.VISIBLE
                    tvOpmerkingen.text = item.opmerkingen
                } else {
                    tvOpmerkingen.visibility = View.GONE
                }
                
                btnEdit.setOnClickListener { onEdit(index, item) }
                btnDelete.setOnClickListener { onDelete(index, item) }
            }
        }
    }
}
