package com.yvesds.vt5.features.alias.helpers

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasIndex
import com.yvesds.vt5.features.alias.AliasMaster
import com.yvesds.vt5.features.alias.toAliasIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * AliasMasterIO: Handles reading and writing of alias master files.
 * 
 * Responsibilities:
 * - Read alias_master.json from SAF (assets or binaries)
 * - Write alias_master.json to SAF assets
 * - Write CBOR cache to SAF binaries
 * - Coordinate internal cache updates
 * 
 * This centralizes all master file I/O operations.
 */
@OptIn(ExperimentalSerializationApi::class)
object AliasMasterIO {
    
    private const val TAG = "AliasMasterIO"
    private const val MASTER_FILE = "alias_master.json"
    private const val CBOR_FILE = "aliases_optimized.cbor.gz"
    private const val BINARIES = "binaries"
    private const val ASSETS = "assets"
    
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Read alias_master.json from SAF assets directory.
     * Returns null if not found or parse fails.
     */
    suspend fun readMasterFromAssets(
        context: Context,
        vt5RootDir: DocumentFile
    ): AliasMaster? = withContext(Dispatchers.IO) {
        try {
            val assetsDir = vt5RootDir.findFile(ASSETS)?.takeIf { it.isDirectory }
            val masterDoc = assetsDir?.findFile(MASTER_FILE)?.takeIf { it.isFile }
            
            masterDoc?.let { doc ->
                val json = context.contentResolver.openInputStream(doc.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                
                if (!json.isNullOrBlank()) {
                    try {
                        jsonPretty.decodeFromString(AliasMaster.serializer(), json)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to decode alias_master.json: ${ex.message}")
                        null
                    }
                } else null
            }
        } catch (ex: Exception) {
            Log.w(TAG, "readMasterFromAssets failed: ${ex.message}")
            null
        }
    }
    
    /**
     * Read alias_master.json from SAF binaries directory (legacy location).
     * Returns null if not found or parse fails.
     */
    suspend fun readMasterFromBinaries(
        context: Context,
        vt5RootDir: DocumentFile
    ): AliasMaster? = withContext(Dispatchers.IO) {
        try {
            val binariesDir = vt5RootDir.findFile(BINARIES)?.takeIf { it.isDirectory }
            val masterDoc = binariesDir?.findFile(MASTER_FILE)?.takeIf { it.isFile }
            
            masterDoc?.let { doc ->
                val json = context.contentResolver.openInputStream(doc.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                
                if (!json.isNullOrBlank()) {
                    try {
                        jsonPretty.decodeFromString(AliasMaster.serializer(), json)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to decode alias_master.json from binaries: ${ex.message}")
                        null
                    }
                } else null
            }
        } catch (ex: Exception) {
            Log.w(TAG, "readMasterFromBinaries failed: ${ex.message}")
            null
        }
    }
    
    /**
     * Write alias_master.json and CBOR cache to SAF.
     * Also updates internal cache and writes exports copy.
     */
    suspend fun writeMasterAndCbor(
        context: Context,
        master: AliasMaster,
        vt5RootDir: DocumentFile,
        saf: SaFStorageHelper? = null
    ) = withContext(Dispatchers.IO) {
        try {
            // --- ASSETS: pretty JSON ---
            val assetsDir = vt5RootDir.findFile(ASSETS)?.takeIf { it.isDirectory } 
                ?: vt5RootDir.createDirectory(ASSETS)
            
            if (assetsDir == null) {
                Log.e(TAG, "writeMasterAndCbor: cannot access/create assets dir")
            } else {
                val existingMaster = assetsDir.findFile(MASTER_FILE)?.takeIf { it.isFile }
                var masterDoc = existingMaster
                
                if (masterDoc == null) {
                    masterDoc = runCatching { 
                        assetsDir.createFile("application/json", MASTER_FILE) 
                    }.getOrNull()
                }
                
                val prettyJson = jsonPretty.encodeToString(AliasMaster.serializer(), master)
                
                if (masterDoc != null) {
                    val wroteMaster = AliasSafWriter.safeWriteTextToDocument(context, masterDoc, prettyJson)
                    if (!wroteMaster) {
                        Log.w(TAG, "writeMasterAndCbor: writing master.json to assets failed; will fallback to internal cache and exports copy")
                    } else {
                        Log.i(TAG, "writeMasterAndCbor: wrote $MASTER_FILE to ${masterDoc.uri} (${prettyJson.length} bytes)")
                    }
                } else {
                    Log.e(TAG, "writeMasterAndCbor: failed creating $MASTER_FILE in assets")
                }
                
                // Also write a user-visible copy to exports folder (best-effort)
                runCatching {
                    AliasSafWriter.writeCopyToExports(
                        context, vt5RootDir, MASTER_FILE, 
                        prettyJson.toByteArray(Charsets.UTF_8), "application/json"
                    )
                }
            }
            
            // --- BINARIES: gzipped CBOR ---
            val binariesDir = vt5RootDir.findFile(BINARIES)?.takeIf { it.isDirectory } 
                ?: vt5RootDir.createDirectory(BINARIES)
            
            if (binariesDir == null) {
                Log.e(TAG, "writeMasterAndCbor: cannot access/create binaries dir")
                return@withContext
            }
            
            val index = master.toAliasIndex()
            val cborBytes = Cbor.encodeToByteArray(AliasIndex.serializer(), index)
            
            val gzipped: ByteArray = ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzip ->
                    gzip.write(cborBytes)
                    gzip.finish()
                }
                baos.toByteArray()
            }
            
            // Remove existing if present
            binariesDir.findFile(CBOR_FILE)?.delete()
            val cborDoc = runCatching { 
                binariesDir.createFile("application/octet-stream", CBOR_FILE) 
            }.getOrNull()
            
            if (cborDoc != null) {
                val wroteCborSaf = AliasSafWriter.safeWriteToDocument(context, cborDoc, gzipped)
                if (wroteCborSaf) {
                    Log.i(TAG, "writeMasterAndCbor: wrote $CBOR_FILE to ${cborDoc.uri} (${gzipped.size} bytes)")
                } else {
                    Log.w(TAG, "writeMasterAndCbor: failed writing $CBOR_FILE to binaries; will fallback to internal cache")
                }
            } else {
                Log.e(TAG, "writeMasterAndCbor: failed creating $CBOR_FILE in binaries")
            }
            
            // Also write user-visible copy to exports
            runCatching {
                AliasSafWriter.writeCopyToExports(
                    context, vt5RootDir, CBOR_FILE, gzipped, "application/octet-stream"
                )
            }
            
            // Always update internal cache so runtime uses latest index
            try {
                AliasIndexCache.write(context, index)
                Log.i(TAG, "Internal CBOR cache updated after writeMasterAndCbor")
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to update internal cache after writeMasterAndCbor: ${ex.message}")
            }
            
        } catch (ex: Exception) {
            Log.e(TAG, "writeMasterAndCbor failed: ${ex.message}", ex)
        }
    }
}
