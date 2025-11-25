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
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermSoortSelectieBinding
import com.yvesds.vt5.features.alias.AliasManager
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
 * 
 * UPDATE: Gebruikt nu ALLE unieke soorten uit alias_master.json (via AliasManager)
 * in plaats van site-specifieke filtering. Dit zorgt voor een complete soortenlijst
 * die alle soorten uit site_species.json bevat, ongeacht telpost assignment.
 */
class SoortSelectieScherm : AppCompatActivity() {

    private lateinit var binding: SchermSoortSelectieBinding
    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    private var telpostId: String? = null
    private var snapshot: DataSnapshot = DataSnapshot()
    
    // SAF helper for alias manager access
    private lateinit var saf: SaFStorageHelper

    // Datamodels
    data class Row(val soortId: String, val naam: String) {
        // Performance: Lazy cache voor genormaliseerde naam - alleen berekend bij eerste gebruik
        // Vermijdt onnodige string normalisatie voor items die nooit gezocht worden
        val normalizedName: String by lazy { normalizeString(naam) }
    }

    // Alfabetische basislijst (site-filter toegepast indien beschikbaar)
    private var baseAlphaRows: List<Row> = emptyList()
    // Wereldwijde soortenlijst (alle soorten uit species.json, voor uitgebreide zoekfunctie)
    private var worldSpeciesRows: List<Row> = emptyList()
    // Set van site species IDs voor snelle lookup
    private var siteSpeciesIds: Set<String> = emptySet()
    // Cache voor snelle lookup op ID (inclusief wereldwijde soorten)
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

        // Initialize SAF helper for alias manager access
        saf = SaFStorageHelper(this)

        telpostId = intent.getStringExtra(EXTRA_TELPOST_ID)

        // Begin met een lege selectie in plaats van alle preselecties toe te voegen
        selectedIds.clear()
        // Verwijder of commentarieer deze regel uit:
        // selectedIds += TellingSessionManager.preselectState.value.selectedSoortIds

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
        uiScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                // Fast-path: check cache first without showing dialog
                val cachedData = ServerDataCache.getCachedOrNull()
                if (cachedData != null) {
                    // Cache hit - process immediately without dialog
                    snapshot = cachedData
                    
                    // Process data synchronously (it's fast with cached data)
                    baseAlphaRows = buildAlphaRowsForTelpost()
                    
                    // Build site species ID set for quick lookup
                    siteSpeciesIds = baseAlphaRows.map { it.soortId }.toSet()
                    
                    // Build world species list (alle species.json soorten die NIET in site_species zitten)
                    worldSpeciesRows = buildWorldSpeciesRows()
                    
                    // Build ID cache for O(1) lookups (inclusief wereldwijde soorten)
                    rowsByIdCache.clear()
                    baseAlphaRows.forEach { row -> rowsByIdCache[row.soortId] = row }
                    worldSpeciesRows.forEach { row -> rowsByIdCache[row.soortId] = row }
                    
                    recentRows = computeRecents(baseAlphaRows)
                    submitGrid(recents = recentRows, restAlpha = baseAlphaRows.filterNot { it.soortId in recentIds })
                    updateCounter()
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    return@launch
                }

                // Slow-path: need to load from storage
                val dialog = ProgressDialogHelper.show(this@SoortSelectieScherm, "Soorten laden...")

                try {
                    snapshot = withContext(Dispatchers.IO) {
                        ServerDataCache.getOrLoad(this@SoortSelectieScherm)
                    }

                    // Basislijst opbouwen en sorteren
                    baseAlphaRows = buildAlphaRowsForTelpost()
                    
                    // Build site species ID set for quick lookup
                    siteSpeciesIds = baseAlphaRows.map { it.soortId }.toSet()
                    
                    // Build world species list (alle species.json soorten die NIET in site_species zitten)
                    worldSpeciesRows = buildWorldSpeciesRows()

                    // ID cache opbouwen voor snelle lookup (inclusief wereldwijde soorten)
                    rowsByIdCache.clear()
                    baseAlphaRows.forEach { row -> rowsByIdCache[row.soortId] = row }
                    worldSpeciesRows.forEach { row -> rowsByIdCache[row.soortId] = row }

                    // Recents berekenen
                    recentRows = computeRecents(baseAlphaRows)

                    // Submit naar grid
                    submitGrid(recents = recentRows, restAlpha = baseAlphaRows.filterNot { it.soortId in recentIds })
                    updateCounter()

                    val elapsed = System.currentTimeMillis() - startTime

                    if (baseAlphaRows.isEmpty()) {
                        Toast.makeText(this@SoortSelectieScherm, getString(R.string.soort_no_species_download), Toast.LENGTH_LONG).show()
                    }
                } finally {
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data", e)
                Toast.makeText(this@SoortSelectieScherm, getString(R.string.soort_error_loading, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Build alphabetically sorted species list.
     * 
     * NEW: Uses ALL unique species from alias_master.json (via AliasManager)
     * instead of filtering by telpost. This provides the complete "truth list"
     * of all species from site_species.json regardless of site assignment.
     * 
     * Performance: Runs synchronously but data is already loaded in memory
     * by AliasManager (loaded during MetadataScherm background preload).
     */
    private suspend fun buildAlphaRowsForTelpost(): List<Row> = withContext(Dispatchers.IO) {
        // Try to get all species from alias index first (preferred source)
        val aliasSpecies = try {
            AliasManager.getAllSpeciesFromIndex(this@SoortSelectieScherm, saf)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to get species from alias index: ${ex.message}")
            emptyMap()
        }
        
        // If alias index has species, use that as the source of truth
        val base = if (aliasSpecies.isNotEmpty()) {
            ArrayList<Row>(aliasSpecies.size).apply {
                aliasSpecies.forEach { (sid, naam) ->
                    add(Row(sid, naam))
                }
            }
        } else {
            // Fallback to snapshot.speciesById (all species, no filtering)
            ArrayList<Row>(snapshot.speciesById.size).apply {
                snapshot.speciesById.values.forEach { 
                    add(Row(it.soortid, it.soortnaam)) 
                }
            }
        }

        // Sort by pre-computed lowercase to avoid repeated lowercase() calls
        return@withContext base.sortedBy { it.naam.lowercase() }
    }

    /**
     * Build world species list from species.json (snapshot.speciesById).
     * 
     * Contains ONLY species that are NOT in the site species list (siteSpeciesIds).
     * This allows the search function to extend beyond site-specific species
     * when no match is found in the site species list.
     * 
     * Performance: O(n) filter but cached in memory for fast search.
     */
    private fun buildWorldSpeciesRows(): List<Row> {
        // Filter species.json to exclude those already in site_species
        val worldSpecies = ArrayList<Row>()
        snapshot.speciesById.values.forEach { species ->
            if (species.soortid !in siteSpeciesIds) {
                worldSpecies.add(Row(species.soortid, species.soortnaam))
            }
        }
        
        // Sort alphabetically
        return worldSpecies.sortedBy { it.naam.lowercase() }
    }

    private fun computeRecents(baseAlpha: List<Row>): List<Row> {
        // Use cached lookup map instead of rebuilding
        val recentsOrderedIds = RecentSpeciesStore.getRecents(this).map { it.first }
        
        // Pre-allocate result list with expected size
        val result = ArrayList<Row>(recentsOrderedIds.size.coerceAtMost(baseAlpha.size))
        
        // Use the cached rowsByIdCache for O(1) lookups if available, otherwise build temporary map
        if (rowsByIdCache.isNotEmpty()) {
            recentsOrderedIds.forEach { id ->
                rowsByIdCache[id]?.let { result.add(it) }
            }
        } else {
            // Fallback: build temporary map (should rarely happen)
            val byId = baseAlpha.associateBy { it.soortId }
            recentsOrderedIds.forEach { id ->
                byId[id]?.let { result.add(it) }
            }
        }
        
        return result
    }

    private fun submitGrid(recents: List<Row>, restAlpha: List<Row>) {
        // Pre-allocate list with known capacity to avoid resizing
        val estimatedSize = if (recents.isNotEmpty()) {
            recents.size + restAlpha.size + 2 // +2 for header and footer
        } else {
            restAlpha.size
        }
        val items = ArrayList<SoortSelectieSectionedAdapter.RowUi>(estimatedSize)

        if (recents.isNotEmpty()) {
            // Check if all recents are selected (optimized with any { } short-circuit)
            val allSel = recents.isNotEmpty() && !recents.any { !selectedIds.contains(it.soortId) }

            // Header voor recente items
            items.add(SoortSelectieSectionedAdapter.RowUi.RecentsHeader(recentsCount = recents.size, allSelected = allSel))

            // Recente items als speciale visuele groep (direct add to avoid intermediate list)
            recents.forEach { items.add(SoortSelectieSectionedAdapter.RowUi.RecenteSpecies(it)) }

            // Footer divider voor afsluiting van recente groep
            items.add(SoortSelectieSectionedAdapter.RowUi.RecentsFooter)
        }

        // Rest van de items zoals gewoonlijk (direct add to avoid intermediate list)
        restAlpha.forEach { items.add(SoortSelectieSectionedAdapter.RowUi.Species(it)) }

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
            // Optimize: avoid creating intermediate filtered list
            val restAlpha = if (recentIds.isEmpty()) {
                baseAlphaRows
            } else {
                baseAlphaRows.filterNot { it.soortId in recentIds }
            }
            submitGrid(recentRows, restAlpha)
            updateCounter()
            return
        }

        // Normaliseren voor case-insensitive en diacritiek-insensitive zoeken
        val normalizedQuery = normalizeString(text)

        val max = 12
        // Pre-allocate result list to avoid resizing
        val filtered = ArrayList<Row>(max)
        
        // Eerst zoeken in site_species (baseAlphaRows)
        for (row in baseAlphaRows) {
            if (row.normalizedName.contains(normalizedQuery) || 
                row.soortId.lowercase().contains(normalizedQuery)) {
                filtered.add(row)
                if (filtered.size >= max) break
            }
        }
        
        // Als er niet genoeg resultaten zijn, ook zoeken in de wereldlijst (species.json)
        if (filtered.size < max) {
            val remaining = max - filtered.size
            var added = 0
            for (row in worldSpeciesRows) {
                if (row.normalizedName.contains(normalizedQuery) || 
                    row.soortId.lowercase().contains(normalizedQuery)) {
                    filtered.add(row)
                    added++
                    if (added >= remaining) break
                }
            }
        }

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
                
                // Performance: avoid filtering when no recents, use Set lookup for O(1) instead of O(n)
                val restAlpha = if (recentIds.isEmpty()) {
                    baseAlphaRows
                } else {
                    baseAlphaRows.filterNot { it.soortId in recentIds }
                }
                
                submitGrid(recents = recentRows, restAlpha = restAlpha)
                updateCounter()
            }
        }
    }

    private fun updateCounter() {
        val totaal = gridAdapter.currentList.count { it is SoortSelectieSectionedAdapter.RowUi.Species }
        val sel = selectedIds.size
        binding.tvTeller.text = getString(R.string.soort_selection_info, totaal, sel)
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