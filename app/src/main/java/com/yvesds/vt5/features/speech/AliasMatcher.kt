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
 * AliasMatcher (updated)
 *
 * - Loads aliases_flat.cbor.gz into an in-memory index.
 * - Uses only three matching layers: norm (text), cologne, phonemes.
 * - Provides: ensureLoaded(), reloadIndex(), findExact(), findFuzzyCandidates(), addAliasHotpatch().
 *
 * Legacy fields (minhash/simhash/dmetapho/beidermorse/ngrams) removed.
 */

internal object AliasMatcher {
    private const val TAG = "AliasMatcher"
    private const val ALIASES_CBOR_GZ = "aliases_flat.cbor.gz"
    private const val BINARIES = "binaries"
    private val json = Json { prettyPrint = false }

    @Volatile private var loadedIndex: RepoAliasIndex? = null

    // Map normalized key -> records
    @Volatile private var aliasMap: Map<String, List<AliasRecord>>? = null

    // phonetic cache: normalized -> cologne code (string)
    @Volatile private var phoneticCache: Map<String, String>? = null

    // buckets by first char to shorten search space
    @Volatile private var firstCharBuckets: Map<Char, List<String>>? = null

    // lightweight bloom-like set (hashes) for quick rejection
    @Volatile private var bloomFilter: Set<Long>? = null

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun ensureLoaded(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        if (loadedIndex != null && aliasMap != null) return@withContext
        synchronized(this@AliasMatcher) {
            if (loadedIndex != null && aliasMap != null) return@synchronized
            try {
                val vt5 = saf.getVt5DirIfExists() ?: run {
                    Log.w(TAG, "SAF VT5 root not set; cannot load aliases")
                    return@synchronized
                }
                val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                val cborDoc = binaries?.findFile(ALIASES_CBOR_GZ)
                if (cborDoc == null) {
                    Log.w(TAG, "aliases cbor not found")
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

                val map = mutableMapOf<String, MutableList<AliasRecord>>()
                val phonCache = mutableMapOf<String, String>()
                val buckets = mutableMapOf<Char, MutableList<String>>()
                val bloomSet = mutableSetOf<Long>()

                for (r in idx.json) {
                    // index alias surface
                    val keys = mutableSetOf<String>()
                    r.alias.trim().takeIf { it.isNotEmpty() }?.let { keys += it.lowercase() }
                    r.canonical.trim().takeIf { it.isNotEmpty() }?.let { keys += it.lowercase() }
                    r.norm.trim().takeIf { it.isNotEmpty() }?.let { keys += r.norm }

                    for (k in keys) {
                        map.getOrPut(k) { mutableListOf() }.add(r)
                        if (k.isNotEmpty()) {
                            val first = k[0]
                            buckets.getOrPut(first) { mutableListOf() }.add(k)
                            val col = runCatching { ColognePhonetic.encode(k) }.getOrDefault("")
                            phonCache[k] = col
                            bloomSet.add(k.hashCode().toLong())
                        }
                    }
                }

                aliasMap = map.mapValues { it.value.toList() }
                phoneticCache = phonCache
                firstCharBuckets = buckets.mapValues { it.value.toList() }
                bloomFilter = bloomSet
                Log.i(TAG, "Loaded alias index: ${idx.json.size} records, keys=${aliasMap!!.size}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error loading alias index: ${ex.message}", ex)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun reloadIndex(context: Context, saf: SaFStorageHelper) = withContext(Dispatchers.IO) {
        synchronized(this@AliasMatcher) {
            try {
                val vt5 = saf.getVt5DirIfExists() ?: run {
                    Log.w(TAG, "SAF VT5 root not set; cannot reload aliases")
                    loadedIndex = null; aliasMap = null; phoneticCache = null; firstCharBuckets = null; bloomFilter = null
                    return@synchronized
                }
                val binaries = vt5.findFile(BINARIES)?.takeIf { it.isDirectory }
                val cborDoc = binaries?.findFile(ALIASES_CBOR_GZ)
                if (cborDoc == null) {
                    Log.w(TAG, "aliases cbor not found during reload")
                    loadedIndex = null; aliasMap = null; phoneticCache = null; firstCharBuckets = null; bloomFilter = null
                    return@synchronized
                }

                val bytes = context.contentResolver.openInputStream(cborDoc.uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Failed to read aliases cbor bytes during reload")
                    loadedIndex = null; aliasMap = null; phoneticCache = null; firstCharBuckets = null; bloomFilter = null
                    return@synchronized
                }

                val ungz = gunzip(bytes)
                val idx = Cbor.decodeFromByteArray(RepoAliasIndex.serializer(), ungz)
                loadedIndex = idx

                val map = mutableMapOf<String, MutableList<AliasRecord>>()
                val phonCache = mutableMapOf<String, String>()
                val buckets = mutableMapOf<Char, MutableList<String>>()
                val bloomSet = mutableSetOf<Long>()

                for (r in idx.json) {
                    val keys = mutableSetOf<String>()
                    r.alias.trim().takeIf { it.isNotBlank() }?.let { keys += it.lowercase() }
                    r.canonical.trim().takeIf { it.isNotBlank() }?.let { keys += it.lowercase() }
                    r.norm.trim().takeIf { it.isNotBlank() }?.let { keys += r.norm }

                    for (k in keys) {
                        map.getOrPut(k) { mutableListOf() }.add(r)
                        if (k.isNotEmpty()) {
                            val first = k[0]
                            buckets.getOrPut(first) { mutableListOf() }.add(k)
                            val col = runCatching { ColognePhonetic.encode(k) }.getOrDefault("")
                            phonCache[k] = col
                            bloomSet.add(k.hashCode().toLong())
                        }
                    }
                }

                aliasMap = map.mapValues { it.value.toList() }
                phoneticCache = phonCache
                firstCharBuckets = buckets.mapValues { it.value.toList() }
                bloomFilter = bloomSet
                Log.i(TAG, "Reloaded alias index: ${idx.json.size} records, keys=${aliasMap!!.size}")
            } catch (ex: Exception) {
                Log.w(TAG, "Error reloading alias index: ${ex.message}", ex)
            }
        }
    }

    suspend fun findExact(aliasPhrase: String, context: Context, saf: SaFStorageHelper): List<AliasRecord> = withContext(Dispatchers.Default) {
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        map[aliasPhrase.trim().lowercase()] ?: emptyList()
    }

    /**
     * Fuzzy candidate search using combined scoring:
     * - text similarity (normalized Levenshtein ratio)
     * - cologne phonetic similarity
     * - phoneme similarity (if phonemes available)
     */
    suspend fun findFuzzyCandidates(
        phrase: String,
        context: Context,
        saf: SaFStorageHelper,
        topN: Int = 6,
        threshold: Double = 0.40
    ): List<Pair<AliasRecord, Double>> = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        ensureLoaded(context, saf)
        val map = aliasMap ?: return@withContext emptyList()
        val buckets = firstCharBuckets ?: return@withContext emptyList()
        val bloom = bloomFilter ?: return@withContext emptyList()

        val q = phrase.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()

        val tokens = q.split("\\s+".toRegex()).filter { it.isNotBlank() && it.toIntOrNull() == null }

        if (tokens.isNotEmpty()) {
            val anyTokenMatches = tokens.any { token -> token.hashCode().toLong() in bloom }
            if (!anyTokenMatches) {
                Log.d(TAG, "Bloom filter rejected: $q")
                return@withContext emptyList()
            }
        }

        val firstChar = q[0]
        val bucket = buckets[firstChar] ?: emptyList()
        val len = q.length
        val shortlist = bucket.asSequence()
            .filter { key ->
                val l = key.length
                val diff = kotlin.math.abs(l - len)
                diff <= max(2, len / 3)
            }

        val scored = mutableListOf<Pair<AliasRecord, Double>>()
        for (k in shortlist) {
            val lev = normalizedLevenshteinRatio(q, k)
            val colSim = runCatching { ColognePhonetic.similarity(q, k) }.getOrDefault(0.0)
            // Evaluate candidates (for each record)
            val recs = map[k] ?: continue
            for (r in recs) {
                val phonSim = if (!r.phonemes.isNullOrBlank()) {
                    val qPh = runCatching { DutchPhonemizer.phonemize(q) }.getOrDefault("")
                    runCatching { DutchPhonemizer.phonemeSimilarity(qPh, r.phonemes) }.getOrDefault(0.0)
                } else 0.0

                // Weights: text 45%, cologne 35%, phonemes 20%
                val score = (0.45 * lev + 0.35 * colSim + 0.20 * phonSim).coerceIn(0.0, 1.0)
                if (score >= threshold) scored += Pair(r, score)
            }
        }

        scored.sortByDescending { it.second }
        val result = scored.take(topN)
        val t1 = System.nanoTime()
        Log.d(TAG, "findFuzzyCandidates: phrase='$q' shortlist=${shortlist.count()} scored=${scored.size} topN=$topN timeMs=${(t1 - t0) / 1_000_000}")
        return@withContext result
    }

    /**
     * Hot-patch: add a minimal AliasRecord in-memory so new alias is immediately visible.
     * The created record uses only the agreed fields.
     */
    fun addAliasHotpatch(speciesId: String, aliasRaw: String, canonical: String? = null, tilename: String? = null) {
        try {
            val norm = normalizeLowerNoDiacritics(aliasRaw)
            if (norm.isBlank()) return

            val aliasLower = aliasRaw.trim().lowercase()
            val col = runCatching { ColognePhonetic.encode(norm) }.getOrDefault("")
            val phon = runCatching { DutchPhonemizer.phonemize(norm) }.getOrDefault("")

            val record = AliasRecord(
                aliasid = "hotpatch_${System.nanoTime()}",
                speciesid = speciesId,
                canonical = canonical ?: aliasLower,
                tilename = tilename,
                alias = aliasLower,
                norm = norm,
                cologne = if (col.isNotBlank()) col else null,
                phonemes = if (phon.isNotBlank()) phon else null,
                weight = 1.0,
                source = "user_field_training"
            )

            synchronized(this) {
                val currentMap = aliasMap?.mapValues { it.value.toMutableList() }?.toMutableMap() ?: mutableMapOf()
                val currentPhon = phoneticCache?.toMutableMap() ?: mutableMapOf()
                val currentBuckets = firstCharBuckets?.mapValues { it.value.toMutableList() }?.toMutableMap() ?: mutableMapOf()
                val currentBloom = bloomFilter?.toMutableSet() ?: mutableSetOf()

                val keys = listOf(aliasLower, (record.canonical ?: "").trim().lowercase(), norm)
                for (k in keys) {
                    if (k.isBlank()) continue
                    val list = currentMap.getOrPut(k) { mutableListOf() }
                    list.add(record)
                    currentPhon[k] = col
                    val first = k[0]
                    currentBuckets.getOrPut(first) { mutableListOf() }.add(k)
                    currentBloom.add(k.hashCode().toLong())
                }

                aliasMap = currentMap.mapValues { it.value.toList() }
                phoneticCache = currentPhon
                firstCharBuckets = currentBuckets.mapValues { it.value.toList() }
                bloomFilter = currentBloom
            }

            Log.d(TAG, "Hot-patched alias into aliasMap: '$aliasRaw' -> $speciesId")
        } catch (ex: Exception) {
            Log.w(TAG, "addAliasHotpatch failed: ${ex.message}", ex)
        }
    }

    // Helpers
    private fun gunzip(input: ByteArray): ByteArray {
        GZIPInputStream(input.inputStream()).use { gis ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var n: Int
            while (gis.read(buf).also { n = it } >= 0) baos.write(buf, 0, n)
            return baos.toByteArray()
        }
    }

    private fun normalizedLevenshteinRatio(s1: String, s2: String): Double {
        val d = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (d.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val la = a.length; val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la
        val prev = IntArray(lb + 1) { it }
        val cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            val ai = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ai == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
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