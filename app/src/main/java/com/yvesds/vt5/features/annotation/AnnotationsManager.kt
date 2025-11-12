package com.yvesds.vt5.features.annotation

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.VT5App
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream

/**
 * AnnotationsManager
 *
 * Responsibilities:
 * - Copy a bundled asset (annotations.json) into Documents/VT5/assets/annotations.json via SAF
 *   (install / update step during your server-download / precompute flow).
 * - Load annotations.json from SAF (preferred) or fallback to packaged asset.
 * - Keep an in-memory cache (singleton) for ultra-fast access by the AnnotatieScherm.
 *
 * Usage (example):
 *   lifecycleScope.launch {
 *     // ensure the file is present in Documents/VT5/assets (will copy asset -> SAF if missing)
 *     AnnotationsManager.ensureAnnotationsInSaf(this@MyActivity, assetName = "annotations.json", outFileName = "annotations.json", overwrite = false)
 *     // load into memory cache (await)
 *     AnnotationsManager.loadCache(this@MyActivity, pathInAssets = "annotations.json")
 *   }
 *
 * Afterwards read quickly with:
 *   val all = AnnotationsManager.getCached()
 *   val leeftijd = AnnotationsManager.getEntriesFor("leeftijd")
 */

data class AnnotationOption(val tekst: String, val veld: String, val waarde: String?)

object AnnotationsManager {
    private const val TAG = "AnnotationsManager"
    private const val DEFAULT_ASSET_NAME = "annotations.json"
    private const val VT5_ASSETS_DIR = "assets"

    // in-memory cache, volatile for safe multi-thread reads
    @Volatile
    private var cachedMap: Map<String, List<AnnotationOption>>? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ensure the bundled asset (assetName) is written to Documents/VT5/assets/outFileName via SAF.
     * If overwrite=false and the file already exists, we skip writing.
     * Returns the DocumentFile written or existing, or null on failure.
     *
     * This should be called during your download/precompute flow (IO context).
     */
    suspend fun ensureAnnotationsInSaf(
        context: Context,
        assetName: String = DEFAULT_ASSET_NAME,
        outFileName: String = DEFAULT_ASSET_NAME,
        overwrite: Boolean = false
    ): DocumentFile? = withContext(Dispatchers.IO) {
        try {
            val saf = SaFStorageHelper(context)
            // Ensure VT5 tree exists
            var vt5Dir = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                try { saf.ensureFolders() } catch (ex: Exception) {
                    Log.w(TAG, "SaF ensureFolders failed: ${ex.message}")
                }
                vt5Dir = saf.getVt5DirIfExists()
            }
            if (vt5Dir == null) {
                Log.w(TAG, "No VT5 SAF directory available")
                return@withContext null
            }

            // Ensure 'assets' directory under Documents/VT5
            val assetsDir = saf.findOrCreateDirectory(vt5Dir, VT5_ASSETS_DIR) ?: vt5Dir

            // See if already present
            val existing = assetsDir.findFile(outFileName)
            if (existing != null && !overwrite) {
                Log.d(TAG, "Annotations file already present in SAF: ${existing.name}")
                return@withContext existing
            }

            // Open asset input stream
            val isStream: InputStream = try {
                // first try app assets
                context.assets.open(assetName)
            } catch (ex: Exception) {
                // fallback: try internal raw file from filesDir (allow dev convenience)
                val fallback = File(context.filesDir, assetName)
                if (fallback.exists()) fallback.inputStream() else throw ex
            }

            // If existing and overwrite, delete then recreate
            existing?.let { try { it.delete() } catch (_: Exception) {} }

            val created = assetsDir.createFile("application/json", outFileName) ?: run {
                Log.w(TAG, "Failed creating annotations file in SAF")
                return@withContext null
            }

            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                isStream.use { inp ->
                    inp.copyTo(out)
                    out.flush()
                }
            }

            Log.i(TAG, "Annotations asset written to SAF: ${created.uri}")
            return@withContext created
        } catch (ex: Exception) {
            Log.w(TAG, "ensureAnnotationsInSaf failed: ${ex.message}", ex)
            return@withContext null
        }
    }

    /**
     * Load the annotations JSON into the in-memory cache.
     *
     * Lookup order:
     * 1) Documents/VT5/assets/annotations.json (SAF) if present
     * 2) app assets (assets/annotations.json)
     * 3) fallback path (optional local path in filesDir)
     *
     * Returns the loaded map (or empty map on failure). This function also populates cachedMap.
     */
    suspend fun loadCache(context: Context, assetName: String = DEFAULT_ASSET_NAME, safFileName: String = DEFAULT_ASSET_NAME, fallbackLocalPath: String? = null): Map<String, List<AnnotationOption>> = withContext(Dispatchers.IO) {
        try {
            // 1) try SAF Documents/VT5/assets/<safFileName>
            val saf = SaFStorageHelper(context)
            val vt5Dir = saf.getVt5DirIfExists()
            var rawJson: String? = null
            if (vt5Dir != null) {
                val assetsDir = saf.findOrCreateDirectory(vt5Dir, VT5_ASSETS_DIR) ?: vt5Dir
                val file = assetsDir.findFile(safFileName)
                if (file != null) {
                    try {
                        context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8).use { r ->
                            rawJson = r?.readText()
                        }
                        Log.d(TAG, "Loaded annotations from SAF: ${file.name}")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed reading annotations from SAF: ${ex.message}", ex)
                    }
                }
            }

            // 2) fallback to packaged asset
            if (rawJson.isNullOrBlank()) {
                try {
                    rawJson = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Log.d(TAG, "Loaded annotations from app assets: $assetName")
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed loading annotations from assets: ${ex.message}")
                }
            }

            // 3) optional fallback path (written to filesDir)
            if (rawJson.isNullOrBlank() && !fallbackLocalPath.isNullOrBlank()) {
                try {
                    val f = File(fallbackLocalPath)
                    if (f.exists()) rawJson = f.readText(Charsets.UTF_8)
                    Log.d(TAG, "Loaded annotations from fallback local path: $fallbackLocalPath")
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed loading annotations from fallback path: ${ex.message}")
                }
            }

            if (rawJson.isNullOrBlank()) {
                Log.w(TAG, "No annotations JSON available (SAF, assets or fallback).")
                cachedMap = emptyMap()
                return@withContext cachedMap!!
            }

            // Parse JSON into map
            val root = json.parseToJsonElement(rawJson).jsonObject
            val out = mutableMapOf<String, List<AnnotationOption>>()
            for ((k, v) in root) {
                try {
                    val arr = v.jsonArray
                    val list = arr.mapNotNull { je ->
                        val jo = je.jsonObject
                        val tekst = jo["tekst"]?.toString()?.trim('"') ?: ""
                        val veld = jo["veld"]?.toString()?.trim('"') ?: k
                        val waarde = jo["waarde"]?.toString()?.trim('"')
                        AnnotationOption(tekst = tekst, veld = veld, waarde = waarde)
                    }
                    out[k] = list
                } catch (ex: Exception) {
                    Log.w(TAG, "Skipping field '$k' during parse: ${ex.message}")
                }
            }

            // Cache it
            cachedMap = out.toMap()
            Log.i(TAG, "Annotations loaded and cached: ${cachedMap?.keys?.size ?: 0} groups")
            return@withContext cachedMap!!
        } catch (ex: Exception) {
            Log.w(TAG, "loadCache failed: ${ex.message}", ex)
            cachedMap = emptyMap()
            return@withContext cachedMap!!
        }
    }

    /** Return the cached map (may be null if not loaded). */
    fun getCached(): Map<String, List<AnnotationOption>> {
        return cachedMap ?: emptyMap()
    }

    /** Convenience: get entries for a specific key (e.g. "leeftijd"). */
    fun getEntriesFor(field: String): List<AnnotationOption> {
        return cachedMap?.get(field).orEmpty()
    }

    /**
     * Clear the in-memory cache (useful for testing or forcing reload).
     */
    fun clearCache() {
        cachedMap = null
    }
}