package com.yvesds.vt5.core.opslag

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SaFStorageHelper
 *
 * - Keeps the original synchronous helpers for compatibility.
 * - Adds suspend wrappers that execute DocumentFile/contentResolver work on Dispatchers.IO.
 * - Callers in coroutines should prefer the suspend variants (foldersExistSuspend, ensureFoldersSuspend, getVt5DirIfExistsSuspend, findOrCreateDirectorySuspend)
 *   to avoid blocking the UI thread.
 *
 * Note: DocumentFile.listFiles() and contentResolver I/O can be slow on some devices. Always prefer the suspend wrappers in production code.
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
     * Ensure we have persistable permission for the selected tree.
     */
    fun takePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // swallow; caller should handle UX if permission wasn't granted
        }
    }

    /**
     * Synchronous variant: check if VT5 root and subfolders exist.
     * Prefer foldersExistSuspend() when called from a coroutine.
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
     * Suspend variant of foldersExist() (runs on Dispatchers.IO).
     */
    suspend fun foldersExistSuspend(): Boolean = withContext(Dispatchers.IO) {
        foldersExist()
    }

    /**
     * Synchronous ensure (idempotent) of VT5 tree and subfolders.
     * Prefer ensureFoldersSuspend() in coroutine contexts.
     */
    fun ensureFolders(): Boolean {
        val rootTree = getRootUri() ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, rootTree) ?: return false

        val vt5Folder = findOrCreateDirectory(rootDoc, "VT5") ?: return false

        val subfolders = listOf("assets", "serverdata", "counts", "exports", "binaries")
        for (name in subfolders) {
            if (findOrCreateDirectory(vt5Folder, name) == null) return false
        }
        return true
    }

    /**
     * Suspend variant of ensureFolders (runs on Dispatchers.IO).
     */
    suspend fun ensureFoldersSuspend(): Boolean = withContext(Dispatchers.IO) {
        ensureFolders()
    }

    /**
     * Synchronous get VT5 DocumentFile if it exists.
     * Prefer getVt5DirIfExistsSuspend() when calling from coroutine.
     */
    fun getVt5DirIfExists(): DocumentFile? {
        val rootTree = getRootUri() ?: return null
        return DocumentFile.fromTreeUri(context, rootTree)?.findFile("VT5")?.takeIf { it.isDirectory }
    }

    /**
     * Suspend variant (runs on Dispatchers.IO).
     */
    suspend fun getVt5DirIfExistsSuspend(): DocumentFile? = withContext(Dispatchers.IO) {
        getVt5DirIfExists()
    }

    /**
     * Find existing directory by exact name (case-sensitive) or create it.
     *
     * Note: listFiles() can be slow — prefer calling this via the suspend wrapper.
     */
    fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile? {
        parent.listFiles().firstOrNull { it.isDirectory && it.name == name }?.let { return it }
        return parent.createDirectory(name)
    }

    /**
     * Suspend-safe wrapper for findOrCreateDirectory.
     */
    suspend fun findOrCreateDirectorySuspend(parent: DocumentFile, name: String): DocumentFile? = withContext(Dispatchers.IO) {
        findOrCreateDirectory(parent, name)
    }
    
    // ========================================================================
    // COUNTS DIRECTORY HELPERS
    // ========================================================================
    
    /**
     * Get the counts directory (Documents/VT5/counts/) if it exists.
     * 
     * @return DocumentFile for counts directory, or null if not available
     */
    fun getCountsDir(): DocumentFile? {
        val vt5Dir = getVt5DirIfExists() ?: return null
        return vt5Dir.findFile(COUNTS_DIR)?.takeIf { it.isDirectory }
    }
    
    /**
     * Suspend variant of getCountsDir().
     */
    suspend fun getCountsDirSuspend(): DocumentFile? = withContext(Dispatchers.IO) {
        getCountsDir()
    }
    
    /**
     * List all files in the counts directory.
     * 
     * @return List of DocumentFile objects for files in counts directory
     */
    fun listCountsFiles(): List<DocumentFile> {
        val countsDir = getCountsDir() ?: return emptyList()
        return countsDir.listFiles().filter { it.isFile }
    }
    
    /**
     * Suspend variant of listCountsFiles().
     */
    suspend fun listCountsFilesSuspend(): List<DocumentFile> = withContext(Dispatchers.IO) {
        listCountsFiles()
    }
    
    /**
     * Delete a file from the counts directory.
     * 
     * @param filename Name of the file to delete (not a path)
     * @return true if file was deleted, false otherwise
     */
    fun deleteCountsFile(filename: String): Boolean {
        // Validate filename (no path traversal)
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false
        }
        
        val countsDir = getCountsDir() ?: return false
        val file = countsDir.findFile(filename) ?: return false
        return file.delete()
    }
    
    /**
     * Suspend variant of deleteCountsFile().
     */
    suspend fun deleteCountsFileSuspend(filename: String): Boolean = withContext(Dispatchers.IO) {
        deleteCountsFile(filename)
    }
    
    /**
     * Read contents of a file in the counts directory.
     * 
     * @param filename Name of the file to read
     * @return File contents as String, or null if file not found or read failed
     */
    fun readCountsFile(filename: String): String? {
        // Validate filename (no path traversal)
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return null
        }
        
        val countsDir = getCountsDir() ?: return null
        val file = countsDir.findFile(filename) ?: return null
        
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Suspend variant of readCountsFile().
     */
    suspend fun readCountsFileSuspend(filename: String): String? = withContext(Dispatchers.IO) {
        readCountsFile(filename)
    }
    
    /**
     * Write content to a file in the counts directory.
     * If file exists, it will be overwritten.
     * 
     * @param filename Name of the file to write
     * @param content Content to write
     * @return true if write was successful, false otherwise
     */
    fun writeCountsFile(filename: String, content: String): Boolean {
        // Validate filename (no path traversal)
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false
        }
        
        val vt5Dir = getVt5DirIfExists() ?: return false
        var countsDir = vt5Dir.findFile(COUNTS_DIR)
        
        // Create counts directory if it doesn't exist
        if (countsDir == null || !countsDir.isDirectory) {
            countsDir = vt5Dir.createDirectory(COUNTS_DIR) ?: return false
        }
        
        // Delete existing file if present
        countsDir.findFile(filename)?.delete()
        
        // Create new file
        val mimeType = if (filename.endsWith(".json")) "application/json" else "text/plain"
        val newFile = countsDir.createFile(mimeType, filename) ?: return false
        
        return try {
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            // Try to clean up failed file
            try { newFile.delete() } catch (_: Exception) {}
            false
        }
    }
    
    /**
     * Suspend variant of writeCountsFile().
     */
    suspend fun writeCountsFileSuspend(filename: String, content: String): Boolean = withContext(Dispatchers.IO) {
        writeCountsFile(filename, content)
    }

    companion object {
        private const val COUNTS_DIR = "counts"
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