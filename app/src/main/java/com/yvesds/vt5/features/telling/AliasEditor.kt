package com.yvesds.vt5.features.telling

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AliasEditor
 *
 * - buffer voor user toegevoegde aliassen tijdens sessie
 * - persist naar assets/user_aliases.csv (merge mode) en schrijf audit ndjson in exports/
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
            Log.d(TAG, "Buffered alias [$soortId] -> '$alias'")
        } else {
            Log.d(TAG, "Alias duplicate or unchanged: [$soortId] -> '$alias'")
        }
        return added
    }

    fun getBufferSnapshot(): Map<String, List<String>> {
        return buffer.mapValues { it.value.toList() }
    }

    /**
     * Persist user aliases to assets/user_aliases.csv in MERGE mode:
     * - read existing user_aliases.csv (if present) and aliasmapping.csv (if present)
     * - merge aliases (keep previous aliases)
     * - write merged file (overwrite user_aliases.csv)
     */
    suspend fun persistUserAliasesSaf(): Boolean = withContext(Dispatchers.IO) {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return@withContext false
            val assets = vt5.findFile("assets")?.takeIf { it.isDirectory } ?: vt5.createDirectory("assets") ?: return@withContext false

            // Read existing user_aliases.csv (if present)
            val existingUser = mutableMapOf<String, MutableSet<String>>() // sid -> aliases
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

            // Merge: existingUser U buffer
            val merged = mutableMapOf<String, MutableSet<String>>()
            // start with existingUser
            existingUser.forEach { (sid, set) -> merged[sid] = set.toMutableSet() }
            // add buffer
            buffer.forEach { (sid, set) ->
                val entry = merged.getOrPut(sid) { mutableSetOf() }
                entry.addAll(set)
            }

            // Also ensure that for any species appearing in aliasCache (if aliasmapping absent),
            // we at least have canonical in alias column — but we avoid adding duplicate logic here.
            // Build CSV text
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

            // Write user_aliases.csv (overwrite with merged content)
            assets.findFile("user_aliases.csv")?.delete()
            val doc = assets.createFile("text/csv", "user_aliases.csv") ?: return@withContext false
            context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }

            // Also write an audit NDJSON entry for additions with timestamp (only the buffer additions)
            writeAuditLog()

            true
        } catch (ex: Exception) {
            Log.e(TAG, "persistUserAliasesSaf failed: ${ex.message}", ex)
            false
        }
    }

    private fun writeAuditLog() {
        try {
            val vt5 = saf.getVt5DirIfExists() ?: return
            val exports = vt5.findFile("exports")?.takeIf { it.isDirectory } ?: vt5.createDirectory("exports") ?: return
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fname = "alias_additions_$ts.ndjson"
            val doc = exports.createFile("application/x-ndjson", fname) ?: return
            context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
                buffer.forEach { (sid, aliases) ->
                    aliases.forEach { a ->
                        val json = """{"ts":"$timestamp","speciesId":"$sid","alias":"${a.replace("\"","'")}"}"""
                        out.write((json + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
                out.flush()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "writeAuditLog failed: ${ex.message}", ex)
        }
    }
}