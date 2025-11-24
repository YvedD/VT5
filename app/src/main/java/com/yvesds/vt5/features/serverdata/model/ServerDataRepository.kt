@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.serverdata.model

import android.content.Context
import android.util.Log
import com.yvesds.vt5.features.serverdata.helpers.ServerDataDecoder
import com.yvesds.vt5.features.serverdata.helpers.ServerDataFileReader
import com.yvesds.vt5.features.serverdata.helpers.ServerDataTransformer
import com.yvesds.vt5.features.serverdata.helpers.VT5Bin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Optimized ServerDataRepository with helper delegation:
 * - ServerDataFileReader: File discovery and stream management
 * - ServerDataDecoder: Binary/JSON parsing
 * - ServerDataTransformer: Data mapping and normalization
 * 
 * Features:
 * - Parallel loading for faster startup
 * - Selective data loading (codes-only, minimal, full)
 * - Efficient caching and memory usage
 * - Off-main execution with Dispatchers.IO and Default
 */
class ServerDataRepository(
    private val context: Context,
    private val json: Json = ServerDataDecoder.defaultJson,
    private val cbor: Cbor = ServerDataDecoder.defaultCbor
) {
    companion object {
        private const val TAG = "ServerDataRepo"
    }

    private val snapshotState = MutableStateFlow(DataSnapshot())
    val snapshot: StateFlow<DataSnapshot> = snapshotState

    private val fileReader = ServerDataFileReader(context)
    private val decoder = ServerDataDecoder(context, json, cbor)

    /**
     * Checks if required data files exist without loading them.
     */
    suspend fun hasRequiredFiles(): Boolean = fileReader.hasRequiredFiles()

    /**
     * Clear any cached file information.
     */
    fun clearFileCache() {
        fileReader.clearCache()
    }

    /**
     * Load only minimal data required for startup (sites and codes).
     * Optimized with parallel loading and processing.
     */
    suspend fun loadMinimalData(): DataSnapshot = withContext(Dispatchers.IO) {
        val serverdata = fileReader.getServerdataDir() ?: return@withContext DataSnapshot()

        coroutineScope {
            // Load both files in parallel
            val sitesJob = async { readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES) }
            val codesJob = async {
                runCatching {
                    readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)
                }.getOrElse { emptyList() }
            }

            val sites = sitesJob.await()
            val codes = codesJob.await()

            // Process in parallel on Default dispatcher
            val (sitesById, codesByCategory) = ServerDataTransformer.processMinimalData(sites, codes)

            DataSnapshot(
                sitesById = sitesById,
                codesByCategory = codesByCategory
            )
        }
    }

    /**
     * Load ONLY codes - ultra-fast startup for code-dependent screens.
     */
    suspend fun loadCodesOnly(): Map<String, List<CodeItemSlim>> = withContext(Dispatchers.IO) {
        val serverdata = fileReader.getServerdataDir() ?: return@withContext emptyMap()

        val codes = runCatching {
            readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES)
        }.getOrElse { emptyList() }

        ServerDataTransformer.transformCodes(codes)
    }

    /**
     * Full data load with parallel processing for better performance.
     */
    suspend fun loadAllFromSaf(): DataSnapshot = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val serverdata = fileReader.getServerdataDir() ?: return@withContext DataSnapshot()

        if (!hasRequiredFiles()) {
            Log.w(TAG, "Missing required data files")
            return@withContext DataSnapshot()
        }

        // Parallel loading of all data files
        return@withContext coroutineScope {
            val userObjDef = async { readOne<CheckUserItem>(serverdata, "checkuser", VT5Bin.Kind.CHECK_USER) }
            val speciesListDef = async { readList<SpeciesItem>(serverdata, "species", VT5Bin.Kind.SPECIES) }
            val protocolInfoDef = async { readList<ProtocolInfoItem>(serverdata, "protocolinfo", VT5Bin.Kind.PROTOCOL_INFO) }
            val protocolSpeciesDef = async { readList<ProtocolSpeciesItem>(serverdata, "protocolspecies", VT5Bin.Kind.PROTOCOL_SPECIES) }
            val sitesDef = async { readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES) }
            val siteLocationsDef = async { readList<SiteValueItem>(serverdata, "site_locations", VT5Bin.Kind.SITE_LOCATIONS) }
            val siteHeightsDef = async { readList<SiteValueItem>(serverdata, "site_heights", VT5Bin.Kind.SITE_HEIGHTS) }
            val siteSpeciesDef = async { readList<SiteSpeciesItem>(serverdata, "site_species", VT5Bin.Kind.SITE_SPECIES) }
            val codesDef = async { runCatching { readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES) }.getOrElse { emptyList() } }

            // Await all results
            val userObj = userObjDef.await()
            val speciesList = speciesListDef.await()
            val protocolInfo = protocolInfoDef.await()
            val protocolSpecies = protocolSpeciesDef.await()
            val sites = sitesDef.await()
            val siteLocations = siteLocationsDef.await()
            val siteHeights = siteHeightsDef.await()
            val siteSpecies = siteSpeciesDef.await()
            val codes = codesDef.await()

            // Transform data in parallel using helper
            val (speciesById, speciesByCanonical) = ServerDataTransformer.transformSpecies(speciesList)
            val sitesById = ServerDataTransformer.transformSites(sites)
            val (siteLocationsBySite, siteHeightsBySite) = ServerDataTransformer.transformSiteValues(siteLocations, siteHeights)
            val siteSpeciesBySite = ServerDataTransformer.transformSiteSpecies(siteSpecies)
            val protocolSpeciesByProtocol = ServerDataTransformer.transformProtocolSpecies(protocolSpecies)
            val codesByCategory = ServerDataTransformer.transformCodes(codes)

            val snap = DataSnapshot(
                currentUser = userObj,
                speciesById = speciesById,
                speciesByCanonical = speciesByCanonical,
                sitesById = sitesById,
                assignedSites = emptyList(),
                siteLocationsBySite = siteLocationsBySite,
                siteHeightsBySite = siteHeightsBySite,
                siteSpeciesBySite = siteSpeciesBySite,
                protocolsInfo = protocolInfo,
                protocolSpeciesByProtocol = protocolSpeciesByProtocol,
                codesByCategory = codesByCategory
            )

            val elapsed = System.currentTimeMillis() - startTime

            snapshotState.value = snap
            snap
        }
    }

    /**
     * Load only site data.
     */
    @Suppress("unused")
    suspend fun loadSitesOnly(): Map<String, SiteItem> = withContext(Dispatchers.IO) {
        val serverdata = fileReader.getServerdataDir() ?: return@withContext emptyMap()
        val sites = readList<SiteItem>(serverdata, "sites", VT5Bin.Kind.SITES)
        ServerDataTransformer.transformSites(sites)
    }

    /**
     * Load only codes for a specific field with caching.
     */
    @Suppress("unused")
    suspend fun loadCodesFor(field: String): List<CodeItemSlim> = withContext(Dispatchers.IO) {
        val serverdata = fileReader.getServerdataDir() ?: return@withContext emptyList()

        // Reuse cached codes if available
        val cachedCodes = snapshotState.value.codesByCategory[field]
        if (!cachedCodes.isNullOrEmpty()) {
            return@withContext cachedCodes.sortedBy { it.text.lowercase(Locale.getDefault()) }
        }

        val codes = runCatching { readList<CodeItem>(serverdata, "codes", VT5Bin.Kind.CODES) }.getOrElse { emptyList() }
        val transformed = ServerDataTransformer.transformCodes(codes)
        
        transformed[field]?.sortedBy { it.text.lowercase(Locale.getDefault()) } ?: emptyList()
    }

    /* ============ Private helper methods for reading data ============ */

    private inline fun <reified T> readList(
        dir: androidx.documentfile.provider.DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): List<T> {
        val (file, isBinary) = fileReader.findFile(dir, baseName) ?: return emptyList()

        return if (isBinary) {
            decoder.decodeListFromBinary<T>(file, expectedKind) ?: emptyList()
        } else {
            decoder.decodeListFromJson<T>(file) ?: emptyList()
        }
    }

    private inline fun <reified T> readOne(
        dir: androidx.documentfile.provider.DocumentFile,
        baseName: String,
        expectedKind: UShort
    ): T? {
        val (file, isBinary) = fileReader.findFile(dir, baseName) ?: return null

        return if (isBinary) {
            decoder.decodeOneFromBinary<T>(file, expectedKind)
        } else {
            decoder.decodeOneFromJson<T>(file)
        }
    }
}
