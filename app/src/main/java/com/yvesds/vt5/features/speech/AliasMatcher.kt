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
 *  - provide a fuzzy fallback using combined phonetic + normalized Levenshtein ratio
 *
 * Extended:
 *  - reloadIndex(context, saf) to force reloading the CBOR from SAF
 *  - addAliasHotpatch(speciesId, aliasText, canonical, tilename) to add a minimal AliasRecord in-memory
 *  - OPTIMIZED: phoneticCache for fast phonetic encoding, firstCharBuckets for instant shortlist.
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

    // OPTIMIZED: Cache phonetic codes (normalized key -> cologne code) to avoid re-encoding
    @Volatile
    private var phoneticCache: Map<String, String>? = null

    // OPTIMIZED: Buckets by first char for instant shortlist (char -> list of keys)
    @Volatile
    private var firstCharBuckets: Map<Char, List<String>>? = null

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

                // build aliasMap and phoneticCache and firstCharBuckets
                val map = mutableMapOf<String, MutableList<AliasRecord>>()
                val phonCache = mutableMapOf<String, String>()
                val buckets = mutableMapOf<Char, MutableList<String>>()
                for (r in idx.json) {
                    // alias (explicit)
                    val key = r.alias.trim().lowercase()
                    map.getOrPut(key) { mutableListOf() }.add(r)
                    if (key.isNotEmpty()) {
                        val first = key[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(key)
                        // Cache phonetic for alias
                        val phonAlias = runCatching { ColognePhonetic.encode(normalizeLowerNoDiacritics(r.alias)) }.getOrDefault("")
                        phonCache[key] = phonAlias
                    }
                    // canonical (non-null per AliasRecord definition)
                    val c = r.canonical
                    val k2 = c.trim().lowercase()
                    map.getOrPut(k2) { mutableListOf() }.add(r)
                    if (k2.isNotEmpty()) {
                        val first = k2[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(k2)
                        val phonCanon = runCatching { ColognePhonetic.encode(normalizeLowerNoDiacritics(c)) }.getOrDefault("")
                        phonCache[k2] = phonCanon
                    }
                    // norm (non-null)
                    val k3 = r.norm.trim().lowercase()
                    if (k3.isNotBlank()) {
                        map.getOrPut(k3) { mutableListOf() }.add(r)
                        val first = k3[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(k3)
                        val phonNorm = runCatching { ColognePhonetic.encode(k3) }.getOrDefault("")
                        phonCache[k3] = phonNorm
                    }
                }
                aliasMap = map.mapValues { it.value.toList() }
                phoneticCache = phonCache
                firstCharBuckets = buckets.mapValues { it.value.toList() }
                Log.i(TAG, "Loaded alias index: ${idx.json.size} records, aliasMap keys=${aliasMap!!.size}, phoneticCache=${phoneticCache!!.size}, buckets=${firstCharBuckets!!.size}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error loading alias index: ${ex.message}", ex)
            }
        }
    }

    /**
     * Force reload the index from SAF (useful after recompute or if you want to drop current index).
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun reloadIndex(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        synchronized(this@AliasMatcher) {
            try {
                val vt5 = saf.getVt5DirIfExists() ?: run {
                    Log.w(TAG, "SAF VT5 root not set; cannot reload aliases")
                    return@synchronized
                }
                val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                val cborDoc = binaries?.findFile(ALIASES_CBOR_GZ)
                if (cborDoc == null) {
                    Log.w(TAG, "aliases cbor not found in Documents/VT5/$BINARIES during reload")
                    // clear loaded index to avoid stale state
                    loadedIndex = null
                    aliasMap = null
                    phoneticCache = null
                    firstCharBuckets = null
                    return@synchronized
                }

                val bytes = context.contentResolver.openInputStream(cborDoc.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Failed to read aliases cbor bytes during reload")
                    loadedIndex = null
                    aliasMap = null
                    phoneticCache = null
                    firstCharBuckets = null
                    return@synchronized
                }

                val ungz = gunzip(bytes)
                val idx = Cbor.decodeFromByteArray(RepoAliasIndex.serializer(), ungz)
                loadedIndex = idx

                // build aliasMap and caches
                val map = mutableMapOf<String, MutableList<AliasRecord>>()
                val phonCache = mutableMapOf<String, String>()
                val buckets = mutableMapOf<Char, MutableList<String>>()
                for (r in idx.json) {
                    val key = r.alias.trim().lowercase()
                    map.getOrPut(key) { mutableListOf() }.add(r)
                    if (key.isNotEmpty()) {
                        val first = key[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(key)
                        val phonAlias = runCatching { ColognePhonetic.encode(normalizeLowerNoDiacritics(r.alias)) }.getOrDefault("")
                        phonCache[key] = phonAlias
                    }
                    val c = r.canonical
                    val k2 = c.trim().lowercase()
                    map.getOrPut(k2) { mutableListOf() }.add(r)
                    if (k2.isNotEmpty()) {
                        val first = k2[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(k2)
                        val phonCanon = runCatching { ColognePhonetic.encode(normalizeLowerNoDiacritics(c)) }.getOrDefault("")
                        phonCache[k2] = phonCanon
                    }
                    val k3 = r.norm.trim().lowercase()
                    if (k3.isNotBlank()) {
                        map.getOrPut(k3) { mutableListOf() }.add(r)
                        val first = k3[0]
                        buckets.getOrPut(first) { mutableListOf() }.add(k3)
                        val phonNorm = runCatching { ColognePhonetic.encode(k3) }.getOrDefault("")
                        phonCache[k3] = phonNorm
                    }
                }
                aliasMap = map.mapValues { it.value.toList() }
                phoneticCache = phonCache
                firstCharBuckets = buckets.mapValues { it.value.toList() }
                Log.i(TAG, "Reloaded alias index: ${idx.json.size} records, aliasMap keys=${aliasMap!!.size}, phoneticCache=${phoneticCache!!.size}, buckets=${firstCharBuckets!!.size}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error reloading alias index: ${ex.message}", ex)
                // keep previous loadedIndex if reload fails
            }
        }
    }

    suspend fun findExact(aliasPhrase: String, context: Context, saf: SaFStorageHelper): List<AliasRecord> = withContext(Dispatchers.Default) {
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        map[aliasPhrase.trim().lowercase()] ?: emptyList()
    }

    /**
     * Fuzzy candidate search using combined phonetic + normalized Levenshtein ratio across aliasMap keys.
     * Returns topN candidates with score in descending order.
     *
     * OPTIMIZED: Use firstCharBuckets for instant shortlist.
     */
    suspend fun findFuzzyCandidates(
        phrase: String,
        context: Context,
        saf: SaFStorageHelper,
        topN: Int = 5,
        threshold: Double = 0.50
    ): List<Pair<AliasRecord, Double>> = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        val buckets = firstCharBuckets ?: return@withContext emptyList()
        val p = phrase.trim().lowercase()
        if (p.isEmpty()) return@withContext emptyList()

        // OPTIMIZED: Shortlist by first char + length (drastically reduces candidates)
        val firstChar = if (p.isNotEmpty()) p[0] else return@withContext emptyList()
        val bucket = buckets[firstChar] ?: emptyList()
        val len = p.length
        val shortlist = bucket.asSequence()
            .filter { key ->
                val l = key.length
                val diff = kotlin.math.abs(l - len)
                diff <= max(2, len / 3) // allow relative delta
            }

        val scored = mutableListOf<Pair<AliasRecord, Double>>()

        for (k in shortlist) {
            // normalized levenshtein ratio
            val levRatio = normalizedLevenshteinRatio(p, k)

            // phonetic similarity using ColognePhonetic on surface forms (this handles Dutch tweaks)
            val phonSim = runCatching { ColognePhonetic.similarity(p, k) }.getOrDefault(0.0)

            // Combine scores (weights chosen experimentally)
            val finalScore = (0.65 * phonSim + 0.35 * levRatio).coerceIn(0.0, 1.0)

            if (finalScore >= threshold) {
                val records = map[k] ?: continue
                for (r in records) {
                    scored += Pair(r, finalScore)
                }
            }
        }

        scored.sortByDescending { it.second }
        val result = scored.take(topN)
        val t1 = System.nanoTime()
        Log.d(TAG, "findFuzzyCandidates: phrase='$p' shortlist=${shortlist.count()} scored=${scored.size} topN=$topN timeMs=${(t1 - t0) / 1_000_000}")
        return@withContext result
    }

    // Hot-patch: add a minimal AliasRecord in-memory so new alias is immediately visible
    fun addAliasHotpatch(speciesId: String, aliasRaw: String, canonical: String? = null, tilename: String? = null) {
        try {
            val norm = normalizeLowerNoDiacritics(aliasRaw)
            if (norm.isBlank()) return

            val aliasLower = aliasRaw.trim().lowercase()
            val col = runCatching { ColognePhonetic.encode(norm) }.getOrDefault("")
            val record = AliasRecord(
                aliasid = "hotpatch_${System.nanoTime()}",
                speciesid = speciesId,
                canonical = (canonical ?: aliasLower),
                tilename = tilename,
                alias = aliasLower,
                norm = norm,
                cologne = col,
                dmetapho = null,
                beidermorse = null,
                phonemes = null,
                ngrams = mapOf("q" to "3"),
                minhash64 = emptyList(),
                simhash64 = "0x0",
                weight = 1.0
            )

            synchronized(this) {
                val currentMap = aliasMap?.mapValues { it.value.toMutableList() }?.toMutableMap() ?: mutableMapOf()
                val currentPhon = phoneticCache?.toMutableMap() ?: mutableMapOf()
                val currentBuckets = firstCharBuckets?.mapValues { it.value.toMutableList() }?.toMutableMap() ?: mutableMapOf()
                // keys to update: aliasLower, canonical, norm
                val keys = listOf(aliasLower, (record.canonical ?: "").trim().lowercase(), norm)
                for (k in keys) {
                    if (k.isBlank()) continue
                    val list = currentMap.getOrPut(k) { mutableListOf() }
                    list.add(record)
                    currentPhon[k] = col
                    if (k.isNotEmpty()) {
                        val first = k[0]
                        currentBuckets.getOrPut(first) { mutableListOf() }.add(k)
                    }
                }
                aliasMap = currentMap.mapValues { it.value.toList() }.toMap()
                phoneticCache = currentPhon
                firstCharBuckets = currentBuckets.mapValues { it.value.toList() }.toMap()
            }

            Log.d(TAG, "Hot-patched alias into aliasMap: '$aliasRaw' -> $speciesId")
        } catch (ex: Exception) {
            Log.w(TAG, "addAliasHotpatch failed: ${ex.message}", ex)
        }
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

    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase()
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }
}