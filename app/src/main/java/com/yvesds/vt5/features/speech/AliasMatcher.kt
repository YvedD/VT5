package com.yvesds.vt5.features.speech

import android.content.Context
import android.util.Log
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasIndex as RepoAliasIndex
import com.yvesds.vt5.features.alias.AliasRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * AliasMatcher
 *
 * Responsibilities:
 *  - load aliases_flat.cbor.gz (SAF Documents/VT5/binaries/aliases_flat.cbor.gz) and keep in-memory index
 *  - provide fast exact lookup by alias text
 *  - provide a simple fuzzy fallback using normalized Levenshtein ratio
 *
 * Notes:
 *  - This first implementation focuses on correctness and field performance (load into memory once).
 *  - Later optimization may add on-disk indexing or LRU per-record caching.
 */

internal object AliasMatcher {
    private const val TAG = "AliasMatcher"
    private const val ALIASES_CBOR_GZ = "aliases_flat.cbor.gz"
    private const val BINARIES = "binaries"
    private val json = Json { prettyPrint = false }

    // In-memory loaded index (will be loaded once per process)
    // Use @Volatile so multiple coroutines see updates
    @Volatile
    private var loadedIndex: RepoAliasIndex? = null

    // Map alias(lowercase normalized) -> list of AliasRecord
    @Volatile
    private var aliasMap: Map<String, List<AliasRecord>>? = null

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun ensureLoaded(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (loadedIndex != null && aliasMap != null) return@withContext
        synchronized(this@AliasMatcher) {
            if (loadedIndex != null && aliasMap != null) return@synchronized
            try {
                val vt5 = saf.getVt5DirIfExists()
                if (vt5 == null) {
                    Log.w(TAG, "SAF VT5 root not set; cannot load aliases")
                    return@synchronized
                }
                val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                val cborDoc = binaries?.findFile(ALIASES_CBOR_GZ)
                if (cborDoc == null) {
                    Log.w(TAG, "aliases cbor not found in Documents/VT5/$BINARIES")
                    return@synchronized
                }

                val bytes = context.contentResolver.openInputStream(cborDoc.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Failed to read aliases cbor bytes")
                    return@synchronized
                }

                val ungz = gunzip(bytes)
                val idx = Cbor.decodeFromByteArray(RepoAliasIndex.serializer(), ungz)
                loadedIndex = idx

                // build aliasMap
                val map = mutableMapOf<String, MutableList<AliasRecord>>()
                for (r in idx.json) {
                    // alias (explicit)
                    val key = r.alias.trim().lowercase()
                    map.getOrPut(key) { mutableListOf() }.add(r)
                    // canonical (non-null per AliasRecord definition)
                    val c = r.canonical
                    val k2 = c.trim().lowercase()
                    map.getOrPut(k2) { mutableListOf() }.add(r)
                    // norm (non-null)
                    val k3 = r.norm.trim().lowercase()
                    if (k3.isNotBlank()) map.getOrPut(k3) { mutableListOf() }.add(r)
                }
                aliasMap = map.mapValues { it.value.toList() }
                Log.i(TAG, "Loaded alias index: ${idx.json.size} records, aliasMap keys=${aliasMap!!.size}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error loading alias index: ${ex.message}", ex)
            }
        }
    }

    suspend fun findExact(aliasPhrase: String, context: Context, saf: SaFStorageHelper): List<AliasRecord> = withContext(Dispatchers.Default) {
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        map[aliasPhrase.trim().lowercase()] ?: emptyList()
    }

    /**
     * Simple fuzzy candidate search using normalized Levenshtein ratio across aliasMap keys.
     * Returns topN candidates with score in descending order.
     */
    suspend fun findFuzzyCandidates(
        phrase: String,
        context: Context,
        saf: SaFStorageHelper,
        topN: Int = 5,
        threshold: Double = 0.55
    ): List<Pair<AliasRecord, Double>> = withContext(Dispatchers.Default) {
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        val p = phrase.trim().lowercase()
        if (p.isEmpty()) return@withContext emptyList()

        // shortlist keys by length heuristic for performance
        val len = p.length
        val shortlist = map.keys.asSequence()
            .filter { key ->
                val l = key.length
                val diff = kotlin.math.abs(l - len)
                diff <= max(2, len / 3) // allow relative delta
            }

        val scored = mutableListOf<Pair<AliasRecord, Double>>()
        for (k in shortlist) {
            val ratio = normalizedLevenshteinRatio(p, k)
            if (ratio >= threshold) {
                val records = map[k] ?: continue
                for (r in records) {
                    scored += Pair(r, ratio)
                }
            }
        }
        scored.sortByDescending { it.second }
        return@withContext scored.take(topN)
    }

    // ---------- Helpers ----------

    private fun gunzip(input: ByteArray): ByteArray {
        GZIPInputStream(input.inputStream()).use { gis ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var n: Int
            while (gis.read(buf).also { n = it } >= 0) baos.write(buf, 0, n)
            return baos.toByteArray()
        }
    }

    /**
     * Normalized Levenshtein similarity ratio in [0..1]
     */
    private fun normalizedLevenshteinRatio(s1: String, s2: String): Double {
        val d = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (d.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            val ai = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ai == b[j - 1]) 0 else 1
                cur[j] = kotlin.math.min(kotlin.math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, lb + 1)
        }
        return prev[lb]
    }
}