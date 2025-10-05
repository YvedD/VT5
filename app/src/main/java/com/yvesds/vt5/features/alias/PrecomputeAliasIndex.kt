/*
 * VT5 - Précompute pipeline voor aliassen
 *
 * Verwerkt aliasmapping.csv → AliasIndex:
 *  - normalisatie (lowercase, diacritics weg, tokens schoon)
 *  - q-grams
 *  - Cologne / DoubleMetaphone / Beider-Morse
 *  - lichte NL phonemizer (ruwe IPA benadering)
 *  - MinHash64 (K signatures) en SimHash64
 *
 * Vereist: org.apache.commons:commons-codec:1.17.1
 */

package com.yvesds.vt5.features.alias

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.ExperimentalSerializationApi
import java.text.Normalizer
import org.apache.commons.codec.language.ColognePhonetic
import org.apache.commons.codec.language.DoubleMetaphone
import org.apache.commons.codec.language.bm.PhoneticEngine
import org.apache.commons.codec.language.bm.NameType
import org.apache.commons.codec.language.bm.RuleType

object PrecomputeAliasIndex {

    // Eén gedeelde Json-instantie (pretty) en compact voor performance.
    private val jsonPretty: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val jsonCompact: Json = Json { encodeDefaults = true }

    // ---------- Publieke API ----------

    fun buildFromCsv(
        csvRawText: String,
        q: Int = 3,
        minhashK: Int = 64
    ): AliasIndex {
        val rows = parseCsv(csvRawText)
        val out = mutableListOf<AliasRecord>()

        for (row in rows) {
            var aliasCounter = 0

            fun add(aliasRaw: String) {
                aliasCounter += 1
                out += makeRecord(
                    aliasid = aliasCounter.toString(),
                    speciesId = row.speciesId,
                    canonicalRaw = row.canonical,
                    tilename = row.tileName,
                    aliasRaw = aliasRaw,
                    q = q,
                    minhashK = minhashK
                )
            }

            // 1) canonical
            add(row.canonical)

            // 2) tilename (indien aanwezig en verschillend)
            row.tileName?.let { t ->
                if (t.isNotBlank() && !t.equals(row.canonical, ignoreCase = true)) add(t)
            }

            // 3) extra aliassen
            row.aliases.forEach { a -> if (a.isNotBlank()) add(a) }
        }

        return AliasIndex(json = out)
    }

    fun toPrettyJson(index: AliasIndex): String =
        jsonPretty.encodeToString(index)

    fun toCompactJson(index: AliasIndex): String =
        jsonCompact.encodeToString(index)

    @OptIn(ExperimentalSerializationApi::class)
    fun toCborBytes(index: AliasIndex): ByteArray =
        Cbor.encodeToByteArray(AliasIndex.serializer(), index)

    // ---------- CSV parsing ----------

    data class CsvRow(
        val speciesId: String,
        val canonical: String,
        val tileName: String?,
        val aliases: List<String>
    )

    /** Verwacht ;-delimiter:
     * kolom0=speciesId, kolom1=canonical, kolom2=tilename, kolom3..=aliassen
     * Lege velden worden genegeerd.
     */
    fun parseCsv(raw: String): List<CsvRow> {
        val out = mutableListOf<CsvRow>()
        raw.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val cols = line.split(';')
                if (cols.isEmpty()) return@forEach

                val id = cols.getOrNull(0)?.trim().orEmpty()
                if (id.isBlank()) return@forEach

                val canonical = cols.getOrNull(1)?.trim().orEmpty()
                val tile = cols.getOrNull(2)?.trim().orEmpty().ifBlank { null }
                val aliases = if (cols.size > 3)
                    cols.drop(3).map { it.trim() }.filter { it.isNotBlank() }.distinct()
                else emptyList()

                out += CsvRow(
                    speciesId = id.lowercase(),
                    canonical = canonical.lowercase(),
                    tileName = tile,               // UI mag hoofdletters tonen, raw bewaren
                    aliases = aliases.map { it.lowercase() }
                )
            }
        return out
    }

    // ---------- Record builder ----------

    private fun makeRecord(
        aliasid: String,
        speciesId: String,
        canonicalRaw: String,
        tilename: String?,
        aliasRaw: String,
        q: Int,
        minhashK: Int
    ): AliasRecord {
        val canonical = canonicalRaw.lowercase()
        val aliasLower = aliasRaw.lowercase()
        val norm = normalizeLowerNoDiacritics(aliasLower)

        val grams = qgrams(norm, q)
        val minhash = minhash64(grams, minhashK)
        val simhash = "0x" + java.lang.Long.toUnsignedString(simhash64(grams), 16).padStart(16, '0')

        // Fonetik / Phonemics
        val col = cologne(norm)
        val dm = dmCodes(norm)
        val bm = beiderMorse(norm)
        val ipaRaw = DutchLitePhonemizer.phonemize(norm)
        val ipa = if (ipaRaw.isBlank()) null else ipaRaw

        return AliasRecord(
            aliasid = aliasid,
            speciesid = speciesId,
            canonical = canonical,
            tilename = tilename,
            alias = aliasLower,

            norm = norm,
            cologne = col,
            dmetapho = dm,
            beidermorse = bm,
            phonemes = ipa,

            ngrams = mapOf("q" to q.toString()),
            minhash64 = minhash,
            simhash64 = simhash,

            weight = 1.0
        )
    }

    // ---------- Normalisatie ----------

    private fun normalizeLowerNoDiacritics(input: String): String {
        val lower = input.lowercase()
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        // alleen letters/cijfers + spaties; whitespace comprimeren
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    // ---------- Q-grams ----------

    private fun qgrams(s: String, q: Int): List<String> {
        val padded = " $s "
        if (padded.length < q) return listOf(padded)
        return (0..padded.length - q).map { i -> padded.substring(i, i + q) }
    }

    // ---------- Phonetics (Apache Commons Codec) ----------

    private val cologneImpl = ColognePhonetic()
    private val dmetaImpl = DoubleMetaphone().apply { setMaxCodeLen(16) }
    private val bmEngine = PhoneticEngine(NameType.GENERIC, RuleType.APPROX, true)

    private fun cologne(s: String): String? {
        val v = runCatching { cologneImpl.encode(s) }.getOrNull()
        return if (v.isNullOrBlank()) null else v
    }

    private fun dmCodes(s: String): List<String>? {
        val p = runCatching { dmetaImpl.doubleMetaphone(s, false) }.getOrNull()
        val alt = runCatching { dmetaImpl.doubleMetaphone(s, true) }.getOrNull()
        val list = mutableListOf<String>()
        if (!p.isNullOrBlank()) list += p
        if (!alt.isNullOrBlank()) list += alt
        return list.distinct().takeIf { it.isNotEmpty() }
    }

    private fun beiderMorse(s: String): List<String>? {
        val code = runCatching { bmEngine.encode(s) }.getOrNull()
        if (code.isNullOrBlank()) return null
        val parts = code.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return parts.takeIf { it.isNotEmpty() }
    }

    // ---------- Lichte NL phonemizer ----------

    private object DutchLitePhonemizer {
        private val multi = listOf(
            "oe" to "u",
            "ie" to "i",
            "ee" to "eː",
            "aa" to "aː",
            "oo" to "oː",
            "eu" to "øː",
            "ui" to "œy",
            "ou" to "ʌu",
            "au" to "ʌu",
            "ng" to "ŋ",
            "sj" to "ʃ",
            "tj" to "c",
            "ch" to "x",
            "sch" to "sx",
            "ij" to "ɛi",
            "ei" to "ɛi"
        )

        private val mono = mapOf(
            'a' to "a",
            'b' to "b",
            'c' to "k",
            'd' to "d",
            'e' to "ə",
            'f' to "f",
            'g' to "x",
            'h' to "ɦ",
            'i' to "ɪ",
            'j' to "j",
            'k' to "k",
            'l' to "l",
            'm' to "m",
            'n' to "n",
            'o' to "ɔ",
            'p' to "p",
            'q' to "k",
            'r' to "r",
            's' to "s",
            't' to "t",
            'u' to "ʏ",
            'v' to "v",
            'w' to "ʋ",
            'x' to "ks",
            'y' to "i",
            'z' to "z"
        )

        fun phonemize(s: String): String {
            if (s.isBlank()) return ""
            var rest = s
            val out = mutableListOf<String>()

            while (rest.isNotEmpty()) {
                var matched = false
                for ((pat, ipa) in multi) {
                    if (rest.startsWith(pat)) {
                        out += ipa
                        rest = rest.removePrefix(pat)
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    val ch = rest.first()
                    val ipa = mono[ch] ?: ""
                    out += if (ipa.isNotEmpty()) ipa else ch.toString()
                    rest = rest.drop(1)
                }
            }
            return out.joinToString(" ")
        }
    }

    // ---------- Murmur3 x64 128 → 64 ----------

    private fun murmur3_128_x64_64(input: String, seed: Int = 0): Long {
        val data = input.encodeToByteArray()
        val nblocks = data.size / 16
        var h1 = seed.toLong() and 0xffffffffL
        var h2 = seed.toLong() and 0xffffffffL

        val c1 = -0x783c846eeebdac2bL
        val c2 = 0x4cf5ad432745937fL

        var i = 0
        repeat(nblocks) {
            val k1 = getLE(data, i)
            val k2 = getLE(data, i + 8)
            i += 16

            var kk1 = k1 * c1
            kk1 = java.lang.Long.rotateLeft(kk1, 31)
            kk1 *= c2
            h1 = h1 xor kk1

            h1 = java.lang.Long.rotateLeft(h1, 27)
            h1 += h2
            h1 = h1 * 5 + 0x52dce729

            var kk2 = k2 * c2
            kk2 = java.lang.Long.rotateLeft(kk2, 33)
            kk2 *= c1
            h2 = h2 xor kk2

            h2 = java.lang.Long.rotateLeft(h2, 31)
            h2 += h1
            h2 = h2 * 5 + 0x38495ab5
        }

        var k1t = 0L
        var k2t = 0L
        when (data.size and 15) {
            15 -> k2t = k2t xor (data[i + 14].toLong() and 0xffL shl 48)
            14 -> k2t = k2t xor (data[i + 13].toLong() and 0xffL shl 40)
            13 -> k2t = k2t xor (data[i + 12].toLong() and 0xffL shl 32)
            12 -> k2t = k2t xor (data[i + 11].toLong() and 0xffL shl 24)
            11 -> k2t = k2t xor (data[i + 10].toLong() and 0xffL shl 16)
            10 -> k2t = k2t xor (data[i + 9].toLong() and 0xffL shl 8)
            9  -> k2t = k2t xor (data[i + 8].toLong() and 0xffL)
        }
        if ((data.size and 15) >= 9) {
            k2t *= c2; k2t = java.lang.Long.rotateLeft(k2t, 33); k2t *= c1; h2 = h2 xor k2t
        }
        when (data.size and 15) {
            8 -> k1t = k1t xor (data[i + 7].toLong() and 0xffL shl 56)
            7 -> k1t = k1t xor (data[i + 6].toLong() and 0xffL shl 48)
            6 -> k1t = k1t xor (data[i + 5].toLong() and 0xffL shl 40)
            5 -> k1t = k1t xor (data[i + 4].toLong() and 0xffL shl 32)
            4 -> k1t = k1t xor (data[i + 3].toLong() and 0xffL shl 24)
            3 -> k1t = k1t xor (data[i + 2].toLong() and 0xffL shl 16)
            2 -> k1t = k1t xor (data[i + 1].toLong() and 0xffL shl 8)
            1 -> k1t = k1t xor (data[i].toLong() and 0xffL)
        }
        if ((data.size and 15) >= 1) {
            k1t *= c1; k1t = java.lang.Long.rotateLeft(k1t, 31); k1t *= c2; h1 = h1 xor k1t
        }

        h1 = h1 xor data.size.toLong()
        h2 = h2 xor data.size.toLong()
        h1 += h2
        h2 += h1
        h1 = fmix64(h1)
        h2 = fmix64(h2)
        h1 += h2
        h2 += h1

        return h1 xor h2
    }

    private fun getLE(data: ByteArray, index: Int): Long {
        return (data[index].toLong() and 0xffL) or
                ((data[index + 1].toLong() and 0xffL) shl 8) or
                ((data[index + 2].toLong() and 0xffL) shl 16) or
                ((data[index + 3].toLong() and 0xffL) shl 24) or
                ((data[index + 4].toLong() and 0xffL) shl 32) or
                ((data[index + 5].toLong() and 0xffL) shl 40) or
                ((data[index + 6].toLong() and 0xffL) shl 48) or
                ((data[index + 7].toLong() and 0xffL) shl 56)
    }

    private fun fmix64(k: Long): Long {
        var kk = k
        kk = kk xor (kk ushr 33)
        kk *= -0xae502812aa7333L
        kk = kk xor (kk ushr 33)
        kk *= -0x3b314601e57a13adL
        kk = kk xor (kk ushr 33)
        return kk
    }

    // ---------- MinHash ----------

    private val seeds: LongArray by lazy {
        LongArray(64) { i -> 0x9E3779B97F4A7C15uL.toLong() * (i + 1L) }
    }

    private fun minhash64(tokens: List<String>, k: Int): List<Long> {
        val K = k.coerceIn(1, seeds.size)
        if (tokens.isEmpty()) return List(K) { Long.MAX_VALUE }
        val mins = LongArray(K) { Long.MAX_VALUE }
        for (t in tokens) {
            for (i in 0 until K) {
                val h = murmur3_128_x64_64(t, seed = seeds[i].toInt())
                if (java.lang.Long.compareUnsigned(h, mins[i]) < 0) mins[i] = h
            }
        }
        return mins.toList()
    }

    // ---------- SimHash ----------

    private fun simhash64(tokens: List<String>): Long {
        if (tokens.isEmpty()) return 0L
        val vec = IntArray(64)
        for (t in tokens) {
            val h = murmur3_128_x64_64(t)
            for (bit in 0 until 64) {
                val set = ((h ushr bit) and 1L) == 1L
                vec[bit] += if (set) 1 else -1
            }
        }
        var res = 0L
        for (bit in 0 until 64) {
            if (vec[bit] > 0) res = res or (1L shl bit)
        }
        return res
    }
}
