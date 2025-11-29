package com.yvesds.vt5.features.telling

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.yvesds.vt5.net.ServerTellingDataItem
import com.yvesds.vt5.net.ServerTellingEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
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
        
        val etTellers = dialogView.findViewById<TextInputEditText>(R.id.etTellers)
        val etOpmerkingen = dialogView.findViewById<TextInputEditText>(R.id.etOpmerkingen)
        
        etTellers.setText(envelope.tellers)
        etOpmerkingen.setText(envelope.opmerkingen)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_metadata_bewerken))
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val updates = MetadataUpdates(
                    tellers = etTellers.text?.toString(),
                    opmerkingen = etOpmerkingen.text?.toString()
                )
                currentEnvelope = toolset.updateMetadata(envelope, updates)
                hasUnsavedChanges = true
                currentEnvelope?.let { updateDetailView(it) }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun showAddRecordDialog() {
        val envelope = currentEnvelope ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_record, null)
        
        val etSoortId = dialogView.findViewById<TextInputEditText>(R.id.etSoortId)
        val etAantal = dialogView.findViewById<TextInputEditText>(R.id.etAantal)
        val etOpmerkingen = dialogView.findViewById<TextInputEditText>(R.id.etRecordOpmerkingen)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_record_toevoegen))
            .setView(dialogView)
            .setPositiveButton("Toevoegen") { _, _ ->
                val soortId = etSoortId.text?.toString()?.trim() ?: ""
                val aantal = etAantal.text?.toString()?.toIntOrNull() ?: 1
                val opmerkingen = etOpmerkingen.text?.toString() ?: ""
                
                if (soortId.isBlank()) {
                    Toast.makeText(this, "Soort ID is verplicht", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val newRecord = ServerTellingDataItem(
                    soortid = soortId,
                    aantal = aantal.toString(),
                    totaalaantal = aantal.toString(),
                    opmerkingen = opmerkingen
                )
                
                currentEnvelope = toolset.addRecord(envelope, newRecord, generateId = true)
                hasUnsavedChanges = true
                currentEnvelope?.let { updateRecordsList(it) }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
    
    private fun showEditRecordDialog(index: Int, item: ServerTellingDataItem) {
        val envelope = currentEnvelope ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_record, null)
        
        val etSoortId = dialogView.findViewById<TextInputEditText>(R.id.etSoortId)
        val etAantal = dialogView.findViewById<TextInputEditText>(R.id.etAantal)
        val etOpmerkingen = dialogView.findViewById<TextInputEditText>(R.id.etRecordOpmerkingen)
        
        etSoortId.setText(item.soortid)
        etAantal.setText(item.aantal)
        etOpmerkingen.setText(item.opmerkingen)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.beheer_record_bewerken))
            .setView(dialogView)
            .setPositiveButton("Opslaan") { _, _ ->
                val soortId = etSoortId.text?.toString()?.trim() ?: ""
                val aantal = etAantal.text?.toString()?.toIntOrNull() ?: 1
                val opmerkingen = etOpmerkingen.text?.toString() ?: ""
                
                if (soortId.isBlank()) {
                    Toast.makeText(this, "Soort ID is verplicht", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val updatedRecord = item.copy(
                    soortid = soortId,
                    aantal = aantal.toString(),
                    totaalaantal = aantal.toString(),
                    opmerkingen = opmerkingen
                )
                
                currentEnvelope = toolset.updateRecord(envelope, index, updatedRecord)
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
                Toast.makeText(this@TellingBeheerScherm, "Fout: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
