package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasData
import com.yvesds.vt5.features.alias.AliasMaster
import com.yvesds.vt5.features.alias.SpeciesEntry
import com.yvesds.vt5.features.serverdata.helpers.VT5Bin
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.features.serverdata.model.SiteSpeciesItem
import com.yvesds.vt5.features.serverdata.model.SpeciesItem
import com.yvesds.vt5.features.serverdata.model.WrappedJson
import com.yvesds.vt5.features.speech.ColognePhonetic
import com.yvesds.vt5.features.speech.DutchPhonemizer
import com.yvesds.vt5.utils.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * WorldSpeciesAdder: Adds species from the world list (species.json) to local lists.
 *
 * When a species from the world list is selected that is NOT yet in the local
 * site_species list, this helper:
 * 1. Adds the species to site_species.json and site_species.bin
 * 2. Adds the species to alias_master.json with computed phonemes/cologne
 * 3. Updates aliases_optimized.cbor.gz via existing helpers
 *
 * This ensures species remain available for future counting sessions.
 */
@OptIn(ExperimentalSerializationApi::class)
object WorldSpeciesAdder {

    private const val TAG = "WorldSpeciesAdder"
    
    // VT5BIN10 header constants
    private const val CRC_HEADER_BYTES = 0x24 // CRC32 is calculated over first 36 bytes

    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonLenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Add a species from the world list to local lists.
     *
     * @param context Android context
     * @param saf SAF storage helper
     * @param speciesId The species ID to add
     * @param speciesItem The species data from species.json (optional, will be looked up if null)
     * @param telpostId The telpost ID to associate the species with (optional)
     * @return true if species was added successfully, false otherwise
     */
    suspend fun addWorldSpeciesToLocalLists(
        context: Context,
        saf: SaFStorageHelper,
        speciesId: String,
        speciesItem: SpeciesItem?,
        telpostId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists()
            if (vt5 == null) {
                Log.w(TAG, "VT5 directory not found")
                return@withContext false
            }

            val serverdata = vt5.findFile("serverdata")?.takeIf { it.isDirectory }
            if (serverdata == null) {
                Log.w(TAG, "serverdata directory not found")
                return@withContext false
            }

            // Get species data if not provided
            val species = speciesItem ?: run {
                Log.w(TAG, "Species item not provided for $speciesId")
                return@withContext false
            }

            // 1. Check if species is already in site_species
            val existingIds = loadExistingSiteSpeciesIds(context, serverdata)
            if (existingIds.contains(speciesId)) {
                Log.i(TAG, "Species $speciesId already in site_species, skipping")
                return@withContext true
            }

            // 2. Add to site_species.json and site_species.bin
            val addedToSiteSpecies = addToSiteSpecies(
                context, serverdata, speciesId, telpostId ?: "0"
            )
            if (!addedToSiteSpecies) {
                Log.w(TAG, "Failed to add species $speciesId to site_species")
                // Continue anyway - alias_master update is more important
            } else {
                Log.i(TAG, "Added species $speciesId to site_species")
                // Invalidate cache so next load picks up the new species
                ServerDataCache.invalidate()
            }

            // 3. Add to alias_master.json
            val addedToAliasMaster = addToAliasMaster(
                context, vt5, saf, speciesId, species.soortnaam, species.soortkey
            )
            if (!addedToAliasMaster) {
                Log.w(TAG, "Failed to add species $speciesId to alias_master")
                return@withContext false
            }

            Log.i(TAG, "Successfully added world species $speciesId (${species.soortnaam}) to local lists")
            return@withContext true
        } catch (ex: Exception) {
            Log.e(TAG, "addWorldSpeciesToLocalLists failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /**
     * Load existing site_species IDs from JSON or binary file.
     */
    private fun loadExistingSiteSpeciesIds(
        context: Context,
        serverdata: DocumentFile
    ): Set<String> {
        val ids = mutableSetOf<String>()

        // Try JSON first
        val jsonFile = serverdata.findFile("site_species.json")?.takeIf { it.isFile }
        if (jsonFile != null) {
            try {
                val content = context.contentResolver.openInputStream(jsonFile.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (!content.isNullOrBlank()) {
                    // Try wrapped format first
                    val items = runCatching {
                        jsonLenient.decodeFromString(
                            WrappedJson.serializer(SiteSpeciesItem.serializer()),
                            content
                        ).json
                    }.getOrElse {
                        // Try direct list
                        runCatching {
                            jsonLenient.decodeFromString<List<SiteSpeciesItem>>(content)
                        }.getOrElse { emptyList() }
                    }
                    ids.addAll(items.map { it.soortid })
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to load site_species.json: ${ex.message}")
            }
        }

        return ids
    }

    /**
     * Add a species to site_species.json and site_species.bin.
     */
    private fun addToSiteSpecies(
        context: Context,
        serverdata: DocumentFile,
        speciesId: String,
        telpostId: String
    ): Boolean {
        try {
            // Load existing items
            val existingItems = mutableListOf<SiteSpeciesItem>()
            val jsonFile = serverdata.findFile("site_species.json")?.takeIf { it.isFile }

            if (jsonFile != null) {
                val content = context.contentResolver.openInputStream(jsonFile.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (!content.isNullOrBlank()) {
                    val items = runCatching {
                        jsonLenient.decodeFromString(
                            WrappedJson.serializer(SiteSpeciesItem.serializer()),
                            content
                        ).json
                    }.getOrElse {
                        runCatching {
                            jsonLenient.decodeFromString<List<SiteSpeciesItem>>(content)
                        }.getOrElse { emptyList() }
                    }
                    existingItems.addAll(items)
                }
            }

            // Check if already exists
            if (existingItems.any { it.soortid == speciesId }) {
                return true // Already exists
            }

            // Add new item
            val newItem = SiteSpeciesItem(telpostid = telpostId, soortid = speciesId)
            existingItems.add(newItem)

            // Create wrapped format
            val wrapped = WrappedJson(json = existingItems)

            // Write JSON
            val jsonContent = jsonPretty.encodeToString(
                WrappedJson.serializer(SiteSpeciesItem.serializer()),
                wrapped
            )

            val jsonDoc = serverdata.findFile("site_species.json")?.takeIf { it.isFile }
                ?: serverdata.createFile("application/json", "site_species.json")
            if (jsonDoc != null) {
                val wroteJson = context.contentResolver.openOutputStream(jsonDoc.uri)?.use { out ->
                    out.write(jsonContent.toByteArray(Charsets.UTF_8))
                    true
                } ?: false

                if (!wroteJson) {
                    Log.w(TAG, "Failed to write site_species.json")
                }
            }

            // Write BIN - get binaries dir from parent
            val vt5Dir = serverdata.parentFile
            val binariesDir = vt5Dir?.findFile("binaries")?.takeIf { it.isDirectory }
                ?: vt5Dir?.createDirectory("binaries")

            if (binariesDir != null) {
                writeSiteSpeciesBin(context, binariesDir, existingItems)
            }

            return true
        } catch (ex: Exception) {
            Log.e(TAG, "addToSiteSpecies failed: ${ex.message}", ex)
            return false
        }
    }

    /**
     * Write site_species.bin in VT5BIN10 format.
     */
    private fun writeSiteSpeciesBin(
        context: Context,
        binariesDir: DocumentFile,
        items: List<SiteSpeciesItem>
    ) {
        try {
            val wrapped = WrappedJson(json = items)
            val jsonBytes = jsonLenient.encodeToString(
                WrappedJson.serializer(SiteSpeciesItem.serializer()),
                wrapped
            ).toByteArray(Charsets.UTF_8)

            val gzBytes = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(jsonBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }

            val header = makeVt5BinHeader(
                datasetKind = VT5Bin.Kind.SITE_SPECIES,
                codec = VT5Bin.Codec.JSON,
                compression = VT5Bin.Compression.GZIP,
                payloadLen = gzBytes.size.toULong(),
                uncompressedLen = jsonBytes.size.toULong(),
                recordCount = items.size.toUInt()
            )

            // Delete existing and create new
            binariesDir.findFile("site_species.bin")?.delete()
            val binDoc = binariesDir.createFile("application/octet-stream", "site_species.bin")

            if (binDoc != null) {
                context.contentResolver.openOutputStream(binDoc.uri)?.use { out ->
                    out.write(header.array(), 0, header.limit())
                    out.write(gzBytes)
                    out.flush()
                }
                Log.i(TAG, "Wrote site_species.bin (${items.size} items, ${gzBytes.size} bytes)")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "writeSiteSpeciesBin failed: ${ex.message}", ex)
        }
    }

    /**
     * Add a species to alias_master.json with phonemes/cologne computed.
     */
    private suspend fun addToAliasMaster(
        context: Context,
        vt5RootDir: DocumentFile,
        saf: SaFStorageHelper,
        speciesId: String,
        canonical: String,
        tilename: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Read existing master
            val existingMaster = AliasMasterIO.readMasterFromAssets(context, vt5RootDir)
                ?: AliasMasterIO.readMasterFromBinaries(context, vt5RootDir)

            // Check if species already exists
            val speciesMap = existingMaster?.species?.associateBy { it.speciesId }?.toMutableMap()
                ?: mutableMapOf()

            if (speciesMap.containsKey(speciesId)) {
                Log.i(TAG, "Species $speciesId already in alias_master")
                return@withContext true
            }

            // Generate aliases with phonemes and cologne
            val canonicalAlias = generateAliasData(canonical, "seed_canonical")
            val tilenameAlias = if (!tilename.isNullOrBlank() && !tilename.equals(canonical, ignoreCase = true)) {
                generateAliasData(tilename, "seed_tilename")
            } else null

            val newEntry = SpeciesEntry(
                speciesId = speciesId,
                canonical = canonical,
                tilename = tilename?.takeIf { it.isNotBlank() },
                aliases = listOfNotNull(canonicalAlias, tilenameAlias)
            )

            speciesMap[speciesId] = newEntry

            // Create updated master
            val updatedMaster = AliasMaster(
                version = existingMaster?.version ?: "2.1",
                timestamp = Instant.now().toString(),
                species = speciesMap.values.sortedBy { it.speciesId }
            )

            // Write master and CBOR
            AliasMasterIO.writeMasterAndCbor(context, updatedMaster, vt5RootDir, saf)

            // Reload AliasMatcher
            try {
                com.yvesds.vt5.features.speech.AliasMatcher.reloadIndex(context, saf)
                Log.i(TAG, "AliasMatcher reloaded after adding species $speciesId")
            } catch (ex: Exception) {
                Log.w(TAG, "AliasMatcher reload failed: ${ex.message}")
            }

            Log.i(TAG, "Added species $speciesId ($canonical) to alias_master with ${newEntry.aliases.size} aliases")
            return@withContext true
        } catch (ex: Exception) {
            Log.e(TAG, "addToAliasMaster failed: ${ex.message}", ex)
            return@withContext false
        }
    }

    /**
     * Generate AliasData with phonetic encodings (cologne and phonemes).
     */
    private fun generateAliasData(text: String, source: String): AliasData {
        val cleaned = TextUtils.normalizeLowerNoDiacritics(text)
        val cologne = runCatching { ColognePhonetic.encode(cleaned) }.getOrNull() ?: ""
        val phonemes = runCatching { DutchPhonemizer.phonemize(cleaned) }.getOrNull() ?: ""

        return AliasData(
            text = text.trim().lowercase(Locale.ROOT),
            norm = cleaned,
            cologne = cologne,
            phonemes = phonemes,
            source = source,
            timestamp = null // Only user_field_training aliases get timestamps
        )
    }

    /**
     * Create VT5BIN10 header.
     */
    private fun makeVt5BinHeader(
        datasetKind: UShort,
        codec: UByte,
        compression: UByte,
        payloadLen: ULong,
        uncompressedLen: ULong,
        recordCount: UInt
    ): ByteBuffer {
        val headerSize = 40
        val buf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)

        // MAGIC "VT5BIN10"
        buf.put(byteArrayOf(0x56, 0x54, 0x35, 0x42, 0x49, 0x4E, 0x31, 0x30))
        // headerVersion
        buf.putShort(0x0001)
        // datasetKind
        buf.putShort(datasetKind.toShort())
        // codec, compression
        buf.put(codec.toByte())
        buf.put(compression.toByte())
        // reserved16
        buf.putShort(0)
        // payloadLen, uncompressedLen
        buf.putLong(payloadLen.toLong())
        buf.putLong(uncompressedLen.toLong())
        // recordCount
        buf.putInt(recordCount.toInt())

        // CRC32 over first 36 bytes [0x00..0x23]
        val tmp = buf.array().copyOfRange(0, CRC_HEADER_BYTES)
        val crc = CRC32().apply { update(tmp) }.value.toUInt()

        // headerCrc32
        buf.putInt(crc.toInt())

        buf.flip()
        return buf
    }

    /**
     * Extension function to find a file in a DocumentFile directory.
     * Note: This duplicates similar functionality elsewhere but is kept private
     * to avoid adding dependencies on other utility classes.
     */
    private fun DocumentFile.findFile(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name == name }
}
