package com.yvesds.vt5.features.opstart.helpers

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper

/**
 * Helper class voor SAF (Storage Access Framework) operaties tijdens installatie.
 * 
 * Verantwoordelijkheden:
 * - Document picker setup en handling
 * - Folder existence checks
 * - VT5 directory en subdirectory management
 * 
 * Gebruik:
 * ```kotlin
 * val safManager = InstallationSafManager(activity, safHelper)
 * val picker = safManager.setupDocumentPicker { success ->
 *     if (success) {
 *         // SAF setup succesvol
 *     }
 * }
 * ```
 */
class InstallationSafManager(
    private val activity: AppCompatActivity,
    private val safHelper: SaFStorageHelper
) {
    /**
     * Setup de document tree picker voor SAF toegang.
     * 
     * @param onResult Callback die aangeroepen wordt met true als setup succesvol was
     * @return ActivityResultLauncher voor de tree picker
     */
    fun setupDocumentPicker(
        onResult: (Boolean) -> Unit
    ): ActivityResultLauncher<Uri?> {
        return activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                onResult(false)
                return@registerForActivityResult
            }
            
            safHelper.takePersistablePermission(uri)
            safHelper.saveRootUri(uri)
            
            val success = safHelper.foldersExist() || safHelper.ensureFolders()
            onResult(success)
        }
    }
    
    /**
     * Controleer of alle VT5 folders aanwezig zijn, en maak ze aan indien nodig.
     * 
     * @return true als alle folders aanwezig zijn of succesvol aangemaakt
     */
    fun ensureFoldersExist(): Boolean {
        return safHelper.foldersExist() || safHelper.ensureFolders()
    }
    
    /**
     * Haal de VT5 root directory op als deze bestaat.
     * 
     * @return DocumentFile van de VT5 directory, of null als niet gevonden
     */
    fun getVt5Directory(): DocumentFile? {
        return safHelper.getVt5DirIfExists()
    }
    
    /**
     * Haal een subdirectory op binnen de VT5 root, en maak deze aan indien nodig.
     * 
     * @param name Naam van de subdirectory
     * @param createIfMissing true om de directory aan te maken als deze niet bestaat
     * @return DocumentFile van de subdirectory, of null als niet gevonden/aangemaakt
     */
    fun getSubdirectory(name: String, createIfMissing: Boolean = true): DocumentFile? {
        val vt5Dir = getVt5Directory() ?: return null
        
        val existing = vt5Dir.findFile(name)
        if (existing != null && existing.isDirectory) {
            return existing
        }
        
        return if (createIfMissing) {
            vt5Dir.createDirectory(name)
        } else {
            null
        }
    }
    
    /**
     * Controleer of SAF correct is ingesteld (root URI bestaat en folders zijn aanwezig).
     * 
     * @return true als SAF volledig geconfigureerd is
     */
    fun isSafConfigured(): Boolean {
        return safHelper.getVt5DirIfExists() != null && safHelper.foldersExist()
    }
}
