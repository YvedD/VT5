package com.yvesds.vt5.core.opslag

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * SAF-helper voor VT5:
 * - Persistente toegang tot de door de gebruiker gekozen 'Documents' Tree-URI
 * - Aanmaken/Controleren van VT5-mappenstructuur
 *
 * Mappenstructuur:
 * Documents/VT5/
 *   - assets
 *   - serverdata
 *   - counts
 *   - exports
 *   - binaries
 */
class SaFStorageHelper(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRootUri(uri: Uri) {
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    fun getRootUri(): Uri? = prefs.getString(KEY_ROOT_URI, null)?.let { Uri.parse(it) }

    fun clearRootUri() {
        prefs.edit().remove(KEY_ROOT_URI).apply()
    }

    /**
     * Zorg dat we blijvende toegang hebben tot de gekozen Tree URI.
     */
    fun takePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Als gebruiker geen persist toestemming gaf; niets crashen.
        }
    }

    /**
     * Controleer of de VT5-root en ALLE submappen bestaan.
     */
    fun foldersExist(): Boolean {
        val rootTree = getRootUri() ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, rootTree) ?: return false
        val vt5 = rootDoc.findFile("VT5")?.takeIf { it.isDirectory } ?: return false
        val expected = setOf("assets", "serverdata", "counts", "exports", "binaries")
        val present = vt5.listFiles().filter { it.isDirectory }.mapNotNull { it.name }.toSet()
        return expected.all { it in present }
    }

    /**
     * Maakt VT5 + submappen aan indien ze niet bestaan (idempotent).
     * Retourneert true bij succes (d.w.z. na afloop bestaan alle mappen).
     */
    fun ensureFolders(): Boolean {
        val rootTree = getRootUri() ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, rootTree) ?: return false

        // 1) VT5 hoofdmap
        val vt5Folder = findOrCreateDirectory(rootDoc, "VT5") ?: return false

        // 2) Submappen
        val subfolders = listOf("assets", "serverdata", "counts", "exports", "binaries")
        for (name in subfolders) {
            if (findOrCreateDirectory(vt5Folder, name) == null) return false
        }
        return true
    }

    /**
     * Geeft de VT5-map terug als DocumentFile, als die bestaat.
     */
    fun getVt5DirIfExists(): DocumentFile? {
        val rootTree = getRootUri() ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, rootTree) ?: return null
        return rootDoc.findFile("VT5")?.takeIf { it.isDirectory }
    }

    /**
     * Vind bestaande directory (case-sensitief) of maak ze aan.
     */
    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile? {
        parent.listFiles().firstOrNull { it.isDirectory && it.name == name }?.let { return it }
        return parent.createDirectory(name)
    }

    companion object {
        private const val PREFS_NAME = "saf_storage_prefs"
        private const val KEY_ROOT_URI = "root_tree_uri"

        fun buildOpenDocumentTreeIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, null as Uri?)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }
}
