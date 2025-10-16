package com.yvesds.vt5.features.telling

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.yvesds.vt5.databinding.SchermTellingBinding
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.soort.ui.SoortSelectieScherm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TellingScherm : AppCompatActivity() {

    private lateinit var binding: SchermTellingBinding
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    private lateinit var tilesAdapter: SpeciesTileAdapter
    private lateinit var logAdapter: SpeechLogAdapter

    data class SoortRow(val soortId: String, val naam: String, val count: Int = 0)
    data class SpeechLogRow(val ts: Long, val tekst: String, val bron: String)

    private val addSoortenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val newIds = res.data?.getStringArrayListExtra(SoortSelectieScherm.EXTRA_SELECTED_SOORT_IDS).orEmpty()
            if (newIds.isNotEmpty()) {
                uiScope.launch {
                    val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
                    val speciesById = snapshot.speciesById

                    val existing = tilesAdapter.currentList
                    val existingIds = existing.asSequence().map { it.soortId }.toMutableSet()

                    val additions = newIds.asSequence()
                        .filterNot { it in existingIds }
                        .mapNotNull { sid ->
                            val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                            SoortRow(sid, naam, 0)
                        }
                        .toList()

                    if (additions.isNotEmpty()) {
                        val merged = (existing + additions).sortedBy { it.naam.lowercase() }
                        tilesAdapter.submitList(merged)
                        addLog("Soorten toegevoegd: ${additions.size}", "manueel")
                        Toast.makeText(this@TellingScherm, "Toegevoegd: ${additions.size}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermTellingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Log-venster
        binding.recyclerViewSpeechLog.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerViewSpeechLog.setHasFixedSize(true)
        logAdapter = SpeechLogAdapter()
        binding.recyclerViewSpeechLog.adapter = logAdapter

        // Tiles: flexbox layout (wrap)
        val flm = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.FLEX_START
        }
        binding.recyclerViewSpecies.layoutManager = flm
        binding.recyclerViewSpecies.setHasFixedSize(true)

        tilesAdapter = SpeciesTileAdapter(
            onTileClick = { pos -> showNumberInputDialog(pos) }
        )
        binding.recyclerViewSpecies.adapter = tilesAdapter

        binding.btnAddSoorten.setOnClickListener { openSoortSelectieForAdd() }
        binding.btnAfronden.setOnClickListener {
            Toast.makeText(this, "Afronden (batch-upload) volgt later.", Toast.LENGTH_LONG).show()
        }

        // Voorselectie inladen
        uiScope.launch {
            val snapshot = ServerDataCache.getOrLoad(this@TellingScherm)
            val pre = TellingSessionManager.preselectState.value
            val ids = pre.selectedSoortIds
            if (ids.isEmpty()) {
                Toast.makeText(this@TellingScherm, "Geen voorselectie. Keer terug en selecteer soorten.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val speciesById = snapshot.speciesById
            val initial = ids.mapNotNull { sid ->
                val naam = speciesById[sid]?.soortnaam ?: return@mapNotNull null
                SoortRow(sid, naam, 0)
            }.sortedBy { it.naam.lowercase() }

            tilesAdapter.submitList(initial)
            addLog("Telling gestart met ${initial.size} soorten.", "manueel")
        }
    }

    private fun showNumberInputDialog(position: Int) {
        val current = tilesAdapter.currentList
        if (position !in current.indices) return
        val row = current[position]

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row.count.toString())
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }

        AlertDialog.Builder(this)
            .setTitle(row.naam)
            .setMessage("Aantal invoeren:")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text?.toString()?.trim()
                val n = v?.toIntOrNull()
                if (n == null || n < 0) {
                    Toast.makeText(this, "Ongeldig aantal.", Toast.LENGTH_SHORT).show()
                } else {
                    val updated = current.map {
                        if (it.soortId == row.soortId) it.copy(count = n) else it
                    }
                    tilesAdapter.submitList(updated)
                    addLog("Set ${row.naam} = $n", "manueel")
                    // Markeer als recent gebruikt (max 25)
                    RecentSpeciesStore.recordUse(this, row.soortId, maxEntries = 25)
                }
            }
            .show()
    }

    private fun openSoortSelectieForAdd() {
        val telpostId = TellingSessionManager.preselectState.value.telpostId
        val intent = Intent(this, SoortSelectieScherm::class.java)
            .putExtra(SoortSelectieScherm.EXTRA_TELPOST_ID, telpostId)
        addSoortenLauncher.launch(intent)
    }

    private fun addLog(msg: String, bron: String) {
        val now = System.currentTimeMillis() / 1000L
        val newRow = SpeechLogRow(ts = now, tekst = msg, bron = bron)
        val newList = logAdapter.currentList + newRow
        logAdapter.submitList(newList) {
            binding.recyclerViewSpeechLog.scrollToPosition(logAdapter.itemCount - 1)
        }
    }
}