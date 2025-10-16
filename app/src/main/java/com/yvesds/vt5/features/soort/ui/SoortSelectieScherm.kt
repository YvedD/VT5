package com.yvesds.vt5.features.soort.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.yvesds.vt5.databinding.SchermSoortSelectieBinding
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.telling.TellingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Soort-voorselectie met:
 * - Suggestiezone bij zoeken (boven teller)
 * - Hoofdgrid met 'recent gebruikt' (max 25) bovenaan, daaronder alfabetisch de rest
 * - Visuele scheiding + checkbox "Alle recente"
 * - Caching van serverdata via ServerDataCache
 */
class SoortSelectieScherm : AppCompatActivity() {

    private lateinit var binding: SchermSoortSelectieBinding
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    private var telpostId: String? = null
    private var snapshot: DataSnapshot = DataSnapshot()

    data class Row(val soortId: String, val naam: String)

    // Alfabetische basislijst (site-filter toegepast indien beschikbaar)
    private var baseAlphaRows: List<Row> = emptyList()

    // Recents (subset van baseAlphaRows, meest recent eerst)
    private var recentRows: List<Row> = emptyList()
    private val recentIds: Set<String> get() = recentRows.map { it.soortId }.toSet()

    // Geselecteerde ids
    private val selectedIds = mutableSetOf<String>()

    // Adapters
    private lateinit var gridAdapter: SoortSelectieSectionedAdapter
    private lateinit var suggestAdapter: SoortSelectieAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermSoortSelectieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        telpostId = intent.getStringExtra(EXTRA_TELPOST_ID)

        selectedIds.clear()
        selectedIds += TellingSessionManager.preselectState.value.selectedSoortIds

        // GRID: sectioned (recents header + items)
        gridAdapter = SoortSelectieSectionedAdapter(
            isSelected = { id -> id in selectedIds },
            onToggleSpecies = { id, checked, position ->
                if (checked) selectedIds += id else selectedIds -= id
                gridAdapter.notifyItemChanged(position, SoortSelectieSectionedAdapter.PAYLOAD_SELECTION)
                // header checkbox updaten (alle recents geselecteerd?)
                gridAdapter.notifyHeaderStateChanged()
                updateCounter()
            },
            onToggleAllRecents = { checked ->
                if (checked) {
                    selectedIds.addAll(recentIds)
                } else {
                    selectedIds.removeAll(recentIds)
                }
                // Alleen recents-items refreshen + headerstate bijwerken
                gridAdapter.notifyRecentsSelectionChanged(recentIds)
                gridAdapter.notifyHeaderStateChanged()
                updateCounter()
            }
        )
        val glm = GridLayoutManager(this, 2, LinearLayoutManager.VERTICAL, false).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (gridAdapter.isHeader(position)) 2 else 1
                }
            }
        }
        binding.rvSoorten.layoutManager = glm
        binding.rvSoorten.setHasFixedSize(true)
        binding.rvSoorten.adapter = gridAdapter

        // SUGGESTIES: eenvoudige lijst (zelfde rijlayout)
        suggestAdapter = SoortSelectieAdapter(
            isSelected = { id -> id in selectedIds },
            onToggle = { id, checked, position ->
                if (checked) selectedIds += id else selectedIds -= id
                suggestAdapter.notifyItemChanged(position, SoortSelectieAdapter.PAYLOAD_SELECTION)
                // sync grid (alleen de betreffende soort)
                val idx = gridAdapter.currentList.indexOfFirst {
                    it is SoortSelectieSectionedAdapter.RowUi.Species && it.item.soortId == id
                }
                if (idx >= 0) gridAdapter.notifyItemChanged(idx, SoortSelectieSectionedAdapter.PAYLOAD_SELECTION)
                // header check bijwerken
                gridAdapter.notifyHeaderStateChanged()
                updateCounter()
            }
        )
        binding.rvSuggesties.layoutManager = LinearLayoutManager(this)
        binding.rvSuggesties.setHasFixedSize(true)
        binding.rvSuggesties.adapter = suggestAdapter
        showSuggestions(false)

        binding.btnAnnuleer.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        binding.btnOk.setOnClickListener {
            val chosen = ArrayList(selectedIds)
            TellingSessionManager.setPreselectedSoorten(chosen)
            val data = intent.apply { putExtra(EXTRA_SELECTED_SOORT_IDS, chosen) }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        binding.etZoek.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateSuggestions(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Data laden via cache (geen lag bij terugkeren)
        uiScope.launch {
            snapshot = ServerDataCache.getOrLoad(this@SoortSelectieScherm)
            baseAlphaRows = buildAlphaRowsForTelpost()
            recentRows = computeRecents(baseAlphaRows)
            submitGrid(recents = recentRows, restAlpha = baseAlphaRows.filterNot { it.soortId in recentIds })
            updateCounter()

            if (baseAlphaRows.isEmpty()) {
                Toast.makeText(this@SoortSelectieScherm, "Geen soorten gevonden. Download eerst serverdata.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildAlphaRowsForTelpost(): List<Row> {
        val speciesById = snapshot.speciesById
        val siteMap = snapshot.siteSpeciesBySite
        val allowed = telpostId?.let { id -> siteMap[id]?.map { it.soortid }?.toSet().orEmpty() } ?: emptySet()
        val base = if (allowed.isNotEmpty()) {
            allowed.mapNotNull { sid -> speciesById[sid]?.let { Row(sid, it.soortnaam) } }
        } else {
            speciesById.values.map { Row(it.soortid, it.soortnaam) }
        }
        return base.sortedBy { it.naam.lowercase() }
    }

    private fun computeRecents(baseAlpha: List<Row>): List<Row> {
        val byId = baseAlpha.associateBy { it.soortId }
        val recentsOrderedIds = RecentSpeciesStore.getRecents(this).map { it.first }
        return recentsOrderedIds.mapNotNull { byId[it] }
    }

    private fun submitGrid(recents: List<Row>, restAlpha: List<Row>) {
        val items = mutableListOf<SoortSelectieSectionedAdapter.RowUi>()
        if (recents.isNotEmpty()) {
            val allSel = recents.all { selectedIds.contains(it.soortId) }
            items += SoortSelectieSectionedAdapter.RowUi.RecentsHeader(recentsCount = recents.size, allSelected = allSel)
            items += recents.map { SoortSelectieSectionedAdapter.RowUi.Species(it) }
        }
        items += restAlpha.map { SoortSelectieSectionedAdapter.RowUi.Species(it) }
        gridAdapter.submitList(items)
    }

    private fun updateSuggestions(q: String) {
        val text = q.trim()
        if (text.isEmpty()) {
            suggestAdapter.submitList(emptyList())
            showSuggestions(false)
            // rebuild grid (recents + rest) zodat header-state klopt
            submitGrid(recentRows, baseAlphaRows.filterNot { it.soortId in recentIds })
            updateCounter()
            return
        }
        val needle = text.lowercase()
        val max = 12
        val filtered = baseAlphaRows.asSequence()
            .filter { r ->
                val n = r.naam.lowercase()
                n.contains(needle) || r.soortId.lowercase().contains(needle)
            }
            .take(max)
            .toList()

        suggestAdapter.submitList(filtered)
        showSuggestions(filtered.isNotEmpty())
        updateCounter()
    }

    private fun showSuggestions(visible: Boolean) {
        binding.rvSuggesties.visibility = if (visible) View.VISIBLE else View.GONE
        binding.dividerTop.visibility = binding.rvSuggesties.visibility
        binding.dividerBottom.visibility = binding.rvSuggesties.visibility
    }
    override fun onResume() {
        super.onResume()
        // Alleen refreshen wanneer er geen zoekfilter actief is
        val q = binding.etZoek.text?.toString().orEmpty().trim()
        if (q.isEmpty() && this::gridAdapter.isInitialized) {
            // baseAlphaRows en recentRows horen al in memory te staan; we berekenen recents opnieuw
            recentRows = computeRecents(baseAlphaRows)
            val restAlpha = baseAlphaRows.filterNot { r -> recentRows.any { it.soortId == r.soortId } }
            submitGrid(recents = recentRows, restAlpha = restAlpha)
            updateCounter()
        }
    }
    private fun updateCounter() {
        val totaal = gridAdapter.currentList.count { it is SoortSelectieSectionedAdapter.RowUi.Species }
        val sel = selectedIds.size
        binding.tvTeller.text = "$totaal soorten • geselecteerd: $sel"
    }

    companion object {
        const val EXTRA_TELPOST_ID = "extra_telpost_id"
        const val EXTRA_SELECTED_SOORT_IDS = "extra_selected_soort_ids"
    }
}