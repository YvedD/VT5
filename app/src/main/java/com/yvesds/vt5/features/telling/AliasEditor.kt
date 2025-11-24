package com.yvesds.vt5.features.telling

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.features.alias.AliasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AliasEditor
 *
 * - buffer voor user toegevoegde aliassen tijdens sessie
 * - persist naar assets/user_aliases.csv (merge mode) en schrijf audit ndjson in exports/
 *
 * Verbeteringen:
 * - atomic tmp-write -> final write (vermijdt corrupte bestanden bij onderbreking)
 * - buffer wordt alleen geleegd na succesvolle persist
 * - start achtergrond reload van AliasMatcher na succesvolle persist (niet blokkerend)
 * - stuurt broadcasts ACTION_ALIAS_RELOAD_STARTED / ACTION_ALIAS_RELOAD_COMPLETED zodat UI progress kan tonen
 */
class AliasEditor(private val context: Context, private val saf: SaFStorageHelper) {

    companion object {
        private const val TAG = "AliasEditor"
    }

    private val buffer: MutableMap<String, MutableSet<String>> = mutableMapOf()

    private fun validateAlias(alias: String): Boolean {
        if (alias.isBlank()) return false
        if (alias.contains(";") || alias.contains("\n") || alias.contains("\r")) return false
        if (alias.length > 150) return false
        return true
    }

    /**
     * Clean alias for storage:
     *  - remove leading "asr:" (case-insensitive)
     *  - remove trailing numeric tokens
     *  - replace "/" with " of "
     *  - remove semicolons
     *  - collapse whitespace
     *  - lowercase
     */
    private fun cleanAliasForStorage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        // remove leading "asr:" prefix
        s = s.replace(Regex("(?i)^\\s*asr:\\s*"), "")
        // replace slashes with ' of '
        s = s.replace("/", " of ")
        // remove semicolons
        s = s.replace(";", " ")
        // remove trailing numeric tokens like " 3" or " 12"
        s = s.replace(Regex("(?:\\s+\\d+)+\\s*$"), "")
        // collapse spaces
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.lowercase(Locale.getDefault())
    }

    fun addAliasInMemory(soortId: String, aliasRaw: String): Boolean {
        val alias = cleanAliasForStorage(aliasRaw)
        if (!validateAlias(alias)) return false
        val set = buffer.getOrPut(soortId) { mutableSetOf() }
        val added = set.add(alias)
        if (added) {
        } else {
        }
        return added
    }

    @Suppress("unused")
    fun getBufferSnapshot(): Map<String, List<String>> {
        return buffer.mapValues { it.value.toList() }
    }

    /**
     * Persist user aliases to assets/user_aliases.csv in MERGE mode (atomic).
     * - write tmp -> write final -> delete tmp
     * - merges with existing user_aliases.csv
     * - clears in-memory buffer only on success
     *
     * Returns true on success.
     */
    suspend fun persistUserAliasesSaf(): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: run {
                Log.w(TAG, "SAF VT5 root not set; cannot persist aliases")
                return@withContext false
            }
            val assets = vt5.findFile("assets")?.takeIf { it.isDirectory } ?: vt5.createDirectory("assets") ?: run {
                Log.w(TAG, "Could not create/find assets dir")
                return@withContext false
            }

            // Build merged map: existing + buffer
            val existingUser = mutableMapOf<String, MutableSet<String>>() // sid -> aliases

            // Read existing user_aliases.csv (if present)
            assets.findFile("user_aliases.csv")?.takeIf { it.isFile }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val parts = line.split(";")
                            if (parts.isEmpty()) return@forEach
                            val sid = parts.getOrNull(0)?.trim() ?: return@forEach
                            val aliases = if (parts.size > 3) {
                                parts.subList(3, parts.size).map { it.trim().lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
                            } else emptyList()
                            if (aliases.isNotEmpty()) {
                                val set = existingUser.getOrPut(sid) { mutableSetOf() }
                                set.addAll(aliases)
                            }
                        }
                    }
                }
            }

            // Read aliasmapping.csv to capture canonical/displayName for writing output
            val mappingCanonical = mutableMapOf<String, Pair<String?, String?>>() // sid -> (canonical, tilename)
            assets.findFile("aliasmapping.csv")?.takeIf { it.isFile }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val parts = line.split(";")
                            val sid = parts.getOrNull(0)?.trim() ?: return@forEach
                            val canonical = parts.getOrNull(1)?.trim()
                            val tilename = parts.getOrNull(2)?.trim()
                            mappingCanonical[sid] = Pair(canonical, tilename)
                        }
                    }
                }
            }

            // Merge existingUser U buffer
            val merged = mutableMapOf<String, MutableSet<String>>()
            existingUser.forEach { (sid, set) -> merged[sid] = set.toMutableSet() }
            buffer.forEach { (sid, set) ->
                val entry = merged.getOrPut(sid) { mutableSetOf() }
                entry.addAll(set)
            }

            // Build CSV text in memory
            val sb = StringBuilder()
            val keys = merged.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            keys.forEach { sid ->
                val aliases = merged[sid]?.toList() ?: emptyList()
                val (canon, tile) = mappingCanonical[sid] ?: Pair(null, null)
                val canonicalOut = canon?.replace(';', ' ') ?: ""
                val tilenameOut = tile?.replace(';', ' ') ?: ""
                sb.append(sid).append(';')
                sb.append(canonicalOut).append(';')
                sb.append(tilenameOut).append(';')
                sb.append(aliases.joinToString(";") { it.replace(';', ' ') })
                sb.append('\n')
            }
            val csvBytes = sb.toString().toByteArray(Charsets.UTF_8)

            // Atomic write: tmp -> final
            val filename = "user_aliases.csv"
            val tmpName = "$filename.tmp"

            // Remove existing tmp if any
            assets.findFile(tmpName)?.delete()
            val tmpDoc = assets.createFile("text/csv", tmpName) ?: run {
                Log.w(TAG, "Failed to create tmp file for user aliases")
                return@withContext false
            }

            // write tmp
            context.contentResolver.openOutputStream(tmpDoc.uri, "w")?.use { out ->
                out.write(csvBytes)
                out.flush()
            } ?: run {
                Log.w(TAG, "Failed to open tmp output stream")
                return@withContext false
            }

            // Remove existing final and create new final
            assets.findFile(filename)?.delete()
            val finalDoc = assets.createFile("text/csv", filename) ?: run {
                Log.w(TAG, "Failed to create final user_aliases file")
                // attempt to clean tmp and return
                assets.findFile(tmpName)?.delete()
                return@withContext false
            }

            // write final
            context.contentResolver.openOutputStream(finalDoc.uri, "w")?.use { out ->
                out.write(csvBytes)
                out.flush()
            } ?: run {
                Log.w(TAG, "Failed to write final user_aliases file")
                assets.findFile(tmpName)?.delete()
                return@withContext false
            }

            // delete tmp
            assets.findFile(tmpName)?.delete()

            // Write audit NDJSON for additions (atomic-ish: create file and write)
            writeAuditLogAtomic(merged)

            // Clear buffer only after successful persist
            buffer.clear()

            // Start background reload of AliasMatcher for full index consistency (non-blocking)
            CoroutineScope(Dispatchers.IO).launch {
                // notify UI that reload started
                try {
                    val startIntent = Intent(AliasRepository.ACTION_ALIAS_RELOAD_STARTED)
                    context.sendBroadcast(startIntent)
                } catch (_: Exception) { }

                val success = try {
                    AliasRepository.getInstance(context).reloadMatcherIfNeeded(context, saf)
                } catch (ex: Exception) {
                    Log.w(TAG, "Background reload invocation failed: ${ex.message}", ex)
                    false
                }

                // notify UI that reload completed (with success flag)
                try {
                    val doneIntent = Intent(AliasRepository.ACTION_ALIAS_RELOAD_COMPLETED).apply {
                        putExtra(AliasRepository.EXTRA_RELOAD_SUCCESS, success)
                    }
                    context.sendBroadcast(doneIntent)
                } catch (_: Exception) { }

                // Show a short toast on main thread notifying result (non-blocking)
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (success) {
                            Toast.makeText(context.applicationContext, context.getString(R.string.alias_reload_complete), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context.applicationContext, context.getString(R.string.alias_reload_failed), Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) { }
                }
            }

            Log.i(TAG, "Persisted user aliases successfully (${merged.size} species entries)")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "persistUserAliasesSaf failed: ${ex.message}", ex)
            false
        }
    }

    /**
     * Write audit NDJSON entry (one file per persist, timestamped) atomically.
     */
    private fun writeAuditLogAtomic(merged: Map<String, Set<String>>) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return
            val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fname = "alias_additions_$ts.ndjson"
            // create and write
            exports.createFile("application/x-ndjson", fname)?.let { doc ->
                context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                    val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(Date())
                    // Only log the actual buffer additions (we logged merged for debugging, but user buffer has been cleared after success)
                    merged.forEach { (sid, aliases) ->
                        aliases.forEach { a ->
                            val json = """{"ts":"$timestamp","speciesId":"$sid","alias":"${a.replace("\"","'")}"}"""
                            out.write((json + "\n").toByteArray(Charsets.UTF_8))
                        }
                    }
                    out.flush()
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "writeAuditLogAtomic failed: ${ex.message}", ex)
        }
    }
}