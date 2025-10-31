@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yvesds.vt5.features.alias

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * PrecomputeAliasIndex.kt (CSV-free)
 *
 * - All automatic CSV handling removed.
 * - Provides suspend/off-main helpers to convert AliasMaster -> AliasIndex and to produce CBOR bytes.
 * - If explicit migration from CSV is required, use an external migration tool or implement it
 *   outside the normal startup paths; CSV code is intentionally removed from runtime hot paths.
 *
 * Public API:
 * - suspend fun buildFromAliasMaster(master: AliasMaster): AliasIndex
 * - suspend fun buildFromMasterJsonString(jsonText: String): AliasIndex
 * - suspend fun encodeAliasIndexToCborBytes(master: AliasMaster): ByteArray
 */
object PrecomputeAliasIndex {

    private val jsonPretty = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Build AliasIndex from AliasMaster (off-main).
     */
    suspend fun buildFromAliasMaster(master: AliasMaster): AliasIndex = withContext(Dispatchers.Default) {
        master.toAliasIndex()
    }

    /**
     * Decode alias_master JSON string and build AliasIndex (off-main).
     */
    suspend fun buildFromMasterJsonString(jsonText: String): AliasIndex = withContext(Dispatchers.Default) {
        val master = jsonPretty.decodeFromString(AliasMaster.serializer(), jsonText)
        master.toAliasIndex()
    }

    /**
     * Encode AliasIndex (derived from AliasMaster) to CBOR bytes (off-main).
     * Caller may gzip the resulting bytes if desired.
     */
    suspend fun encodeAliasIndexToCborBytes(master: AliasMaster): ByteArray = withContext(Dispatchers.Default) {
        val idx = master.toAliasIndex()
        Cbor.encodeToByteArray(AliasIndex.serializer(), idx)
    }

    /**
     * Deprecated/removed: CSV conversion is intentionally removed from automatic flows.
     * If you need to migrate CSV → AliasMaster, do it explicitly in a migration tool or offline script.
     * Keeping this placeholder to make usage intentions clear at compile-time.
     */
    @Deprecated("CSV import removed from runtime. Use an explicit migration tool outside app startup.", level = DeprecationLevel.ERROR)
    fun buildFromCsv(csvRawText: String, q: Int = 3): AliasIndex {
        error("CSV-based index build removed. Use an explicit migration tool to convert CSV to alias_master.json.")
    }
}