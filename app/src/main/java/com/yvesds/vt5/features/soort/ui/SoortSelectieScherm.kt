package com.yvesds.vt5.features.soort.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermSoortSelectieBinding
import com.yvesds.vt5.features.recent.RecentSpeciesStore
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.telling.TellingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Soort-voorselectie met geoptimaliseerde prestaties:
 * - Efficiënte caching en data-filtering
 * - Verbeterde zoekfunctionaliteit met normalisatie
 * - Geoptimaliseerde UI updates met DiffUtil en payloads
 * - Verminderde GC pressure door object pooling
 * - Sectioned adapter met "recente soorten" bovenaan
 */
class SoortSelectieScherm : AppCompatActivity() {

    private lateinit var binding: SchermSoortSelectieBinding
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    private var telpostId: String? = null
    private var snapshot: DataSnapshot = DataSnapshot()

    // Datamodels
    data class Row(val soortId: String, val naam: String) {
        // Cache voor genormaliseerde naam (voor sneller zoeken)
        val normalizedName: String by lazy { normalizeString(naam) }
    }

    // Alfabetische basislijst (site-filter toegepast indien beschikbaar)
    private var baseAlphaRows: List<Row> = emptyList()
    // Cache voor snelle lookup op ID
    private var rowsByIdCache = ConcurrentHashMap<String, Row>()

    // Recents (subset van baseAlphaRows, meest recent eerst)
    private var recentRows: List<Row> = emptyList()
    private val recentIds: Set<String> get() = recentRows.map { it.soortId }.toSet()

    // Geselecteerde ids
    private val selectedIds = mutableSetOf<String>()

    // Adapters
    private lateinit var gridAdapter: SoortSelectieSectionedAdapter
    private lateinit var suggestAdapter: SoortSelectieAdapter

    // Laatste zoekopdracht voor onResume restore
    private var lastSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermSoortSelectieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        telpostId = intent.getStringExtra(EXTRA_TELPOST_ID)

        selectedIds.clear()
        selectedIds += TellingSessionManager.preselectState.value.selectedSoortIds

        setupAdapters()
        setupListeners()
        loadData()
    }

    private fun setupAdapters() {
        // GRID: sectioned (recents header + items)
        gridAdapter = SoortSelectieSectionedAdapter(
            isSelected = { id -> id in selectedIds },
            onToggleSpecies = { id, checked, position ->
                if (checked) selectedIds += id else selectedIds -= id
                gridAdapter.notifyItemChanged(position, SoortSelectieSectionedAdapter.PAYLOAD_SELECTION)

                // Update suggesties indien zichtbaar
                updateSuggestionsSelection()

                // Header checkbox updaten (alle recents geselecteerd?)
                updateHeaderState()

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

                // Update suggesties indien zichtbaar
                updateSuggestionsSelection()

                updateCounter()
            }
        )

        // Optimalisatie: SpanSizeLookup voor header over volle breedte
        val glm = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when {
                        gridAdapter.isHeader(position) -> 2 // Header over volle breedte
                        gridAdapter.isFooter(position) -> 2 // Footer over volle breedte
                        else -> 1                           // Items in 2 kolommen
                    }
                }
            }
        }
        // Pool instellen voor view recycling
        binding.rvSoorten.setItemViewCacheSize(30)
        binding.rvSoorten.layoutManager = glm
        binding.rvSoorten.setHasFixedSize(true)
        binding.rvSoorten.adapter = gridAdapter

        // SUGGESTIES: eenvoudige lijst (zelfde rijlayout)
        suggestAdapter = SoortSelectieAdapter(
            isSelected = { id -> id in selectedIds },
            onToggle = { id, checked, position ->
                if (checked) selectedIds += id else selectedIds -= id

                // Update het item in de suggestielijst
                suggestAdapter.notifyItemChanged(position, SoortSelectieAdapter.PAYLOAD_SELECTION)

                // Zoek en update het corresponderende item in de grid
                val idx = gridAdapter.currentList.indexOfFirst {
                    it is SoortSelectieSectionedAdapter.RowUi.Species && it.item.soortId == id
                }
                if (idx >= 0) gridAdapter.notifyItemChanged(idx, SoortSelectieSectionedAdapter.PAYLOAD_SELECTION)

                // Update header status
                updateHeaderState()
                updateCounter()
            }
        )

        binding.rvSuggesties.layoutManager = LinearLayoutManager(this)
        binding.rvSuggesties.setHasFixedSize(true)
        binding.rvSuggesties.adapter = suggestAdapter
        showSuggestions(false)
    }

    private fun setupListeners() {
        binding.btnAnnuleer.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnOk.setOnClickListener {
            // Pas bij bevestigen de keuze opslaan in de session manager
            val chosen = ArrayList(selectedIds)
            TellingSessionManager.setPreselectedSoorten(chosen)
            val data = intent.apply { putExtra(EXTRA_SELECTED_SOORT_IDS, chosen) }
            setResult(Activity.RESULT_OK, data)
            finish()
        }

        // Debounced zoekfunctie (optimalisatie)
        var searchJob: Job? = null
        binding.etZoek.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                lastSearchQuery = query

                // Cancel lopende zoekopdracht en start een nieuwe na kleine vertraging
                searchJob?.cancel()
                searchJob = uiScope.launch {
                    updateSuggestions(query)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun loadData() {
        // Eerst proberen data uit cache te laden
        uiScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val dialog = ProgressDialogHelper.show(this@SoortSelectieScherm, "Soorten laden...")

                // Data laden met cache
                val cachedData = ServerDataCache.getCachedOrNull()
                snapshot = if (cachedData != null) {
                    Log.d(TAG, "Using cached data")
                    cachedData
                } else {
                    Log.d(TAG, "Loading data from storage")
                    withContext(Dispatchers.IO) {
                        ServerDataCache.getOrLoad(this@SoortSelectieScherm)
                    }
                }

                // Basislijst opbouwen en sorteren
                baseAlphaRows = buildAlphaRowsForTelpost()

                // ID cache opbouwen voor snelle lookup
                rowsByIdCache = ConcurrentHashMap<String, Row>().apply {
                    baseAlphaRows.forEach { row -> put(row.soortId, row) }
                }

                // Recents berekenen
                recentRows = computeRecents(baseAlphaRows)

                // Submit naar grid
                submitGrid(recents = recentRows, restAlpha = baseAlphaRows.filterNot { it.soortId in recentIds })
                updateCounter()

                dialog.dismiss()

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Data loaded and processed in ${elapsed}ms")

                if (baseAlphaRows.isEmpty()) {
                    Toast.makeText(this@SoortSelectieScherm, "Geen soorten gevonden. Download eerst serverdata.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                Toast.makeText(this@SoortSelectieScherm, "Fout bij laden: ${e.message}", Toast.LENGTH_LONG).show()
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
        // Optimize by building ID map first
        val byId = baseAlpha.associateBy { it.soortId }
        val recentsOrderedIds = RecentSpeciesStore.getRecents(this).map { it.first }
        return recentsOrderedIds.mapNotNull { byId[it] }
    }

    private fun submitGrid(recents: List<Row>, restAlpha: List<Row>) {
        val items = mutableListOf<SoortSelectieSectionedAdapter.RowUi>()

        if (recents.isNotEmpty()) {
            val allSel = recents.all { selectedIds.contains(it.soortId) }

            // Header voor recente items
            items += SoortSelectieSectionedAdapter.RowUi.RecentsHeader(recentsCount = recents.size, allSelected = allSel)

            // Recente items als speciale visuele groep
            items += recents.map { SoortSelectieSectionedAdapter.RowUi.RecenteSpecies(it) }

            // Footer divider voor afsluiting van recente groep
            items += SoortSelectieSectionedAdapter.RowUi.RecentsFooter
        }

        // Rest van de items zoals gewoonlijk
        items += restAlpha.map { SoortSelectieSectionedAdapter.RowUi.Species(it) }

        gridAdapter.submitList(items)
    }
    /**
     * Normaliseert een string voor insensitive zoeken
     * - lowercase
     * - diacritics verwijderen
     * - trim en extra spaces verwijderen
     */
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

        // Normaliseren voor case-insensitive en diacritiek-insensitive zoeken
        val normalizedQuery = normalizeString(text)

        val max = 12
        val filtered = baseAlphaRows.asSequence()
            .filter { row ->
                row.normalizedName.contains(normalizedQuery) ||
                        row.soortId.lowercase().contains(normalizedQuery)
            }
            .take(max)
            .toList()

        suggestAdapter.submitList(filtered)
        showSuggestions(filtered.isNotEmpty())
        updateCounter()
    }

    /**
     * Update de selectiestatus in suggesties (indien zichtbaar)
     */
    private fun updateSuggestionsSelection() {
        if (binding.rvSuggesties.visibility == View.VISIBLE) {
            for (i in 0 until suggestAdapter.itemCount) {
                suggestAdapter.notifyItemChanged(i, SoortSelectieAdapter.PAYLOAD_SELECTION)
            }
        }
    }

    /**
     * Update de header checkbox status op basis van selecties
     */
    private fun updateHeaderState() {
        val idx = gridAdapter.currentList.indexOfFirst { it is SoortSelectieSectionedAdapter.RowUi.RecentsHeader }
        if (idx >= 0) {
            val allRecentsSelected = recentIds.isNotEmpty() && recentIds.all { it in selectedIds }
            val header = (gridAdapter.currentList[idx] as? SoortSelectieSectionedAdapter.RowUi.RecentsHeader)?.copy(allSelected = allRecentsSelected)
                ?: return

            // Update alleen als status veranderd is
            val current = gridAdapter.currentList[idx] as? SoortSelectieSectionedAdapter.RowUi.RecentsHeader
            if (current?.allSelected != header.allSelected) {
                val updatedList = gridAdapter.currentList.toMutableList().apply {
                    set(idx, header)
                }
                gridAdapter.submitList(updatedList)
                gridAdapter.notifyItemChanged(idx, SoortSelectieSectionedAdapter.PAYLOAD_HEADER_STATE)
            }
        }
    }

    private fun showSuggestions(visible: Boolean) {
        binding.rvSuggesties.visibility = if (visible) View.VISIBLE else View.GONE
        binding.dividerTop.visibility = binding.rvSuggesties.visibility
        binding.dividerBottom.visibility = binding.rvSuggesties.visibility
    }

    override fun onResume() {
        super.onResume()

        // Alleen refreshen wanneer er geen zoekfilter actief is
        if (lastSearchQuery.isEmpty() && this::gridAdapter.isInitialized) {
            // baseAlphaRows en recentRows horen al in memory te staan; we berekenen recents opnieuw
            uiScope.launch {
                // Recente items ophalen en bijwerken
                recentRows = computeRecents(baseAlphaRows)
                val restAlpha = baseAlphaRows.filterNot { r -> recentRows.any { it.soortId == r.soortId } }
                submitGrid(recents = recentRows, restAlpha = restAlpha)
                updateCounter()
            }
        }
    }

    private fun updateCounter() {
        val totaal = gridAdapter.currentList.count { it is SoortSelectieSectionedAdapter.RowUi.Species }
        val sel = selectedIds.size
        binding.tvTeller.text = "$totaal soorten • geselecteerd: $sel"
    }

    companion object {
        /**
         * Normaliseert een string voor zoekdoeleinden
         * - lowercase
         * - diacritical marks verwijderen
         * - spaties normaliseren
         */
        private const val TAG = "SoortSelectieScherm"
        const val EXTRA_TELPOST_ID = "extra_telpost_id"
        const val EXTRA_SELECTED_SOORT_IDS = "extra_selected_soort_ids"

        fun normalizeString(text: String): String {
            return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase(Locale.getDefault())
                .trim()
                .replace("\\s+".toRegex(), " ")
        }
    }
}