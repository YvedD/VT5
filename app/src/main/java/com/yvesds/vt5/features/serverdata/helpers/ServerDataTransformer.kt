package com.yvesds.vt5.features.serverdata.helpers

import com.yvesds.vt5.features.serverdata.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Helper for transforming raw ServerData into optimized maps and structures.
 * 
 * Responsibilities:
 * - Data mapping (associateBy, groupBy)
 * - Canonical normalization
 * - CodeItem → CodeItemSlim conversion
 * - Parallel processing for performance
 */
object ServerDataTransformer {
    
    /**
     * Transform species list into lookup maps with parallel processing.
     */
    suspend fun transformSpecies(
        speciesList: List<SpeciesItem>
    ): Pair<Map<String, SpeciesItem>, Map<String, String>> = withContext(Dispatchers.Default) {
        coroutineScope {
            val byIdJob = async {
                speciesList.associateBy { it.soortid }
            }
            
            val byCanonicalJob = async {
                val canonicalBuilder = HashMap<String, String>(speciesList.size)
                speciesList.forEach { sp ->
                    canonicalBuilder[normalizeCanonical(sp.soortnaam)] = sp.soortid
                }
                canonicalBuilder
            }
            
            Pair(byIdJob.await(), byCanonicalJob.await())
        }
    }
    
    /**
     * Transform sites list into lookup map.
     */
    suspend fun transformSites(
        sites: List<SiteItem>
    ): Map<String, SiteItem> = withContext(Dispatchers.Default) {
        sites.associateBy { it.telpostid }
    }
    
    /**
     * Transform site locations/heights into grouped maps.
     */
    suspend fun transformSiteValues(
        siteLocations: List<SiteValueItem>,
        siteHeights: List<SiteValueItem>
    ): Pair<Map<String, List<SiteValueItem>>, Map<String, List<SiteValueItem>>> = 
        withContext(Dispatchers.Default) {
            coroutineScope {
                val locationsJob = async {
                    siteLocations.groupBy { it.telpostid }
                }
                val heightsJob = async {
                    siteHeights.groupBy { it.telpostid }
                }
                Pair(locationsJob.await(), heightsJob.await())
            }
        }
    
    /**
     * Transform site species into grouped map.
     */
    suspend fun transformSiteSpecies(
        siteSpecies: List<SiteSpeciesItem>
    ): Map<String, List<SiteSpeciesItem>> = withContext(Dispatchers.Default) {
        siteSpecies.groupBy { it.telpostid }
    }
    
    /**
     * Transform protocol species into grouped map.
     */
    suspend fun transformProtocolSpecies(
        protocolSpecies: List<ProtocolSpeciesItem>
    ): Map<String, List<ProtocolSpeciesItem>> = withContext(Dispatchers.Default) {
        protocolSpecies.groupBy { it.protocolid }
    }
    
    /**
     * Transform codes into slim format and group by category.
     * Uses parallel processing for conversion and grouping.
     */
    suspend fun transformCodes(
        codes: List<CodeItem>
    ): Map<String, List<CodeItemSlim>> = withContext(Dispatchers.Default) {
        codes
            .mapNotNull { CodeItemSlim.fromCodeItem(it) }
            .groupBy { it.category }
    }
    
    /**
     * Process sites and codes in parallel for minimal data loading.
     */
    suspend fun processMinimalData(
        sites: List<SiteItem>,
        codes: List<CodeItem>
    ): Pair<Map<String, SiteItem>, Map<String, List<CodeItemSlim>>> = 
        withContext(Dispatchers.Default) {
            coroutineScope {
                val sitesJob = async {
                    sites.associateBy { it.telpostid }
                }
                val codesJob = async {
                    codes
                        .mapNotNull { CodeItemSlim.fromCodeItem(it) }
                        .groupBy { it.category }
                }
                
                Pair(sitesJob.await(), codesJob.await())
            }
        }
    
    /**
     * Normalize canonical species name for lookup.
     * Converts to lowercase and maps accented characters to ASCII equivalents.
     */
    fun normalizeCanonical(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            val mapped = when (ch) {
                'à','á','â','ã','ä','å' -> 'a'
                'ç' -> 'c'
                'è','é','ê','ë' -> 'e'
                'ì','í','î','ï' -> 'i'
                'ñ' -> 'n'
                'ò','ó','ô','õ','ö' -> 'o'
                'ù','ú','û','ü' -> 'u'
                'ý','ÿ' -> 'y'
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString().trim()
    }
}
