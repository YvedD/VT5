@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yvesds.vt5.features.opstart.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.databinding.SchermInstallatieBinding
import com.yvesds.vt5.features.opstart.helpers.AliasIndexManager
import com.yvesds.vt5.features.opstart.helpers.InstallationDialogManager
import com.yvesds.vt5.features.opstart.helpers.InstallationSafManager
import com.yvesds.vt5.features.opstart.helpers.ServerAuthenticationManager
import com.yvesds.vt5.features.opstart.helpers.ServerDataDownloadManager
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.hoofd.HoofdActiviteit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * InstallatieScherm - Gerefactored om helper classes te gebruiken.
 * 
 * Helpers:
 * - InstallationSafManager: SAF operations
 * - ServerAuthenticationManager: Login test & checkuser
 * - ServerDataDownloadManager: Download orchestration
 * - AliasIndexManager: Alias index lifecycle
 * - InstallationDialogManager: All dialogs
 * 
 * Reduced from 702 lines to ~280 lines door extraction naar helpers.
 */
class InstallatieScherm : AppCompatActivity() {
    companion object {
        private const val TAG = "InstallatieScherm"
    }

    private lateinit var binding: SchermInstallatieBinding
    
    // Core infrastructure (existing)
    private lateinit var saf: SaFStorageHelper
    private lateinit var creds: CredentialsStore
    
    // NEW: Helper managers
    private lateinit var safManager: InstallationSafManager
    private lateinit var authManager: ServerAuthenticationManager
    private lateinit var downloadManager: ServerDataDownloadManager
    private lateinit var aliasManager: AliasIndexManager
    private lateinit var dialogManager: InstallationDialogManager
    
    private var dataPreloaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermInstallatieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize core infrastructure
        saf = SaFStorageHelper(this)
        creds = CredentialsStore(this)
        
        // Initialize helper managers
        initializeHelpers()
        
        // Setup UI
        initUi()
        wireClicks()

        // Preload data if SAF already configured
        if (safManager.isSafConfigured()) {
            preloadDataIfExists()
        }

        // Update button states
        updatePrecomputeButtonState()
    }

    override fun onDestroy() {
        // No need for manual dialog cleanup - InstallationDialogManager handles it
        super.onDestroy()
    }

    /**
     * Initialize all helper managers.
     */
    private fun initializeHelpers() {
        safManager = InstallationSafManager(this, saf)
        authManager = ServerAuthenticationManager(this)
        downloadManager = ServerDataDownloadManager(this)
        aliasManager = AliasIndexManager(this, saf)
        dialogManager = InstallationDialogManager(this)
    }

    /**
     * Initialize UI elements.
     */
    private fun initUi() {
        binding.etUitleg.setText(getString(R.string.install_uitleg))
        restoreCreds()
        refreshSafStatus()
        binding.etUitleg.measure(0, 0)
    }

    /**
     * Wire up all button click listeners.
     */
    private fun wireClicks() = with(binding) {
        // SAF picker button
        btnKiesDocuments.setOnClickListener { 
            val picker = safManager.setupDocumentPicker { success ->
                tvStatus.text = if (success) {
                    preloadDataIfExists()
                    getString(R.string.status_saf_ok)
                } else {
                    getString(R.string.status_saf_niet_ingesteld)
                }
                updatePrecomputeButtonState()
            }
            picker.launch(null)
        }

        // Check/create folders button
        btnCheckFolders.setOnClickListener {
            it.isEnabled = false
            try {
                val ok = safManager.ensureFoldersExist()
                tvStatus.text = if (ok) {
                    preloadDataIfExists()
                    getString(R.string.status_saf_ok)
                } else {
                    getString(R.string.status_saf_missing)
                }
                updatePrecomputeButtonState()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking folders: ${e.message}", e)
                dialogManager.showError("Fout bij controleren folders", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        // Credentials management buttons
        btnWis.setOnClickListener {
            it.isEnabled = false
            try {
                creds.clear()
                etLogin.setText("")
                etPass.setText("")
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_gewist), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing credentials: ${e.message}", e)
                dialogManager.showError("Fout bij wissen credentials", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        btnBewaar.setOnClickListener {
            it.isEnabled = false
            try {
                val username = etLogin.text?.toString().orEmpty().trim()
                val password = etPass.text?.toString().orEmpty()
                creds.save(username, password)
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_opgeslagen), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving credentials: ${e.message}", e)
                dialogManager.showError("Fout bij opslaan credentials", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        // Login test button
        btnLoginTest.setOnClickListener {
            val (username, password) = getCredentialsOrWarn() ?: return@setOnClickListener
            it.isEnabled = false
            handleLoginTest(username, password)
        }

        // Download server data button
        btnDownloadJsons.setOnClickListener {
            val (username, password) = getCredentialsOrWarn() ?: return@setOnClickListener
            it.isEnabled = false
            handleDownloadServerData(username, password)
        }

        // Force alias reindex button
        btnAliasPrecompute.setOnClickListener {
            handleForceRebuildAliasIndex()
        }

        // Done button - return to main
        btnKlaar.setOnClickListener {
            navigateToOpstart()
        }
    }

    /**
     * Get credentials from input fields or show warning if missing.
     */
    private fun getCredentialsOrWarn(): Pair<String, String>? {
        val username = binding.etLogin.text?.toString().orEmpty().trim()
        val password = binding.etPass.text?.toString().orEmpty()
        
        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
            return null
        }
        return username to password
    }

    /**
     * Handle login test using ServerAuthenticationManager.
     */
    private fun handleLoginTest(username: String, password: String) {
        lifecycleScope.launch {
            val progressDialog = dialogManager.showProgress("Login testen...")
            
            try {
                val result = authManager.testLogin(username, password)
                progressDialog.dismiss()
                
                when (result) {
                    is ServerAuthenticationManager.AuthResult.Success -> {
                        dialogManager.showInfo(getString(R.string.dlg_titel_result), result.response)
                        
                        // Save fullname to SharedPreferences for quick access
                        authManager.saveFullnameToPreferences(result.response)
                        
                        // Save checkuser.json
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val vt5Dir = safManager.getVt5Directory()
                                val assetsDir = safManager.getSubdirectory("assets", createIfMissing = true)
                                authManager.saveCheckUserResponse(assetsDir, result.response)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error saving checkuser.json: ${e.message}", e)
                            }
                        }
                    }
                    is ServerAuthenticationManager.AuthResult.Failure -> {
                        dialogManager.showError("Login mislukt", result.error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in handleLoginTest: ${e.message}", e)
                dialogManager.showError("Fout bij login test", e.message ?: "Onbekende fout")
                progressDialog.dismiss()
            } finally {
                binding.btnLoginTest.isEnabled = true
            }
        }
    }

    /**
     * Handle server data download using ServerDataDownloadManager.
     */
    private fun handleDownloadServerData(username: String, password: String) {
        val vt5Dir = safManager.getVt5Directory()
        if (vt5Dir == null) {
            Toast.makeText(this, getString(R.string.msg_kies_documents_eerst), Toast.LENGTH_LONG).show()
            binding.btnDownloadJsons.isEnabled = true
            return
        }
        
        val serverdataDir = safManager.getSubdirectory("serverdata", createIfMissing = true)
        val binariesDir = safManager.getSubdirectory("binaries", createIfMissing = true)

        lifecycleScope.launch {
            val progressDialog = dialogManager.showProgress("JSONs downloaden...")
            
            try {
                // Download server data
                val downloadResult = downloadManager.downloadAllServerData(
                    serverdataDir = serverdataDir,
                    binariesDir = binariesDir,
                    username = username,
                    password = password,
                    onProgress = { message ->
                        dialogManager.updateProgress(progressDialog, message)
                    }
                )
                
                when (downloadResult) {
                    is ServerDataDownloadManager.DownloadResult.Success -> {
                        // Check if alias regeneration needed
                        if (aliasManager.needsRegeneration(vt5Dir)) {
                            dialogManager.updateProgress(progressDialog, "Alias index bijwerken...")
                            
                            val timestamp = authManager.generateIsoTimestamp()
                            val regenResult = aliasManager.regenerateIndexIfNeeded(
                                vt5Dir = vt5Dir,
                                timestamp = timestamp,
                                onProgress = { message ->
                                    dialogManager.updateProgress(progressDialog, message)
                                }
                            )
                            
                            when (regenResult) {
                                is AliasIndexManager.RegenerationResult.Success -> {
                                }
                                is AliasIndexManager.RegenerationResult.AlreadyUpToDate -> {
                                }
                                is AliasIndexManager.RegenerationResult.Failure -> {
                                    Log.w(TAG, "Alias regeneration failed: ${regenResult.error}")
                                }
                            }
                        }
                        
                        // Invalidate data preload flag
                        dataPreloaded = false
                        
                        // Show success
                        progressDialog.dismiss()
                        dialogManager.showInfo(
                            getString(R.string.dlg_titel_result),
                            downloadResult.messages.joinToString("\n")
                        )
                        
                        // Preload data in background
                        preloadDataIfExists()
                    }
                    is ServerDataDownloadManager.DownloadResult.Failure -> {
                        progressDialog.dismiss()
                        dialogManager.showError("Fout bij downloaden", downloadResult.error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during download: ${e.message}", e)
                dialogManager.showError("Fout bij downloaden", e.message ?: "Onbekende fout")
                progressDialog.dismiss()
            } finally {
                binding.btnDownloadJsons.isEnabled = true
                updatePrecomputeButtonState()
            }
        }
    }

    /**
     * Handle force rebuild of alias index using AliasIndexManager.
     */
    private fun handleForceRebuildAliasIndex() {
        val vt5Dir = safManager.getVt5Directory()
        if (vt5Dir == null) {
            Toast.makeText(this, getString(R.string.msg_kies_documents_eerst), Toast.LENGTH_LONG).show()
            return
        }
        
        binding.btnAliasPrecompute.isEnabled = false
        binding.btnAliasPrecompute.alpha = 0.5f

        lifecycleScope.launch {
            val progressDialog = dialogManager.showProgress("Forceer heropbouw alias index...")
            
            try {
                val timestamp = authManager.generateIsoTimestamp()
                val result = aliasManager.forceRebuildIndex(vt5Dir, timestamp)
                
                progressDialog.dismiss()
                
                when (result) {
                    is AliasIndexManager.RegenerationResult.Success -> {
                        binding.tvStatus.text = "Alias index succesvol opnieuw opgebouwd"
                        dialogManager.showInfo("Succes", "Alias index is succesvol opnieuw opgebouwd")
                    }
                    is AliasIndexManager.RegenerationResult.Failure -> {
                        binding.tvStatus.text = "Fout bij forceer rebuild: ${result.error}"
                        dialogManager.showError("Fout bij forceer rebuild", result.error)
                    }
                    else -> {
                        // AlreadyUpToDate should not happen in force rebuild
                        binding.tvStatus.text = "Alias index rebuild voltooid"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in handleForceRebuildAliasIndex: ${e.message}", e)
                dialogManager.showError("Fout bij forceer rebuild", e.message ?: "Onbekende fout")
                progressDialog.dismiss()
            } finally {
                updatePrecomputeButtonState()
            }
        }
    }

    /**
     * Preload data in background if SAF is configured.
     */
    private fun preloadDataIfExists() {
        if (dataPreloaded) return
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ServerDataCache.preload(applicationContext)
                }
                dataPreloaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error during data preloading: ${e.message}")
            } finally {
                updatePrecomputeButtonState()
            }
        }
    }

    /**
     * Navigate back to HoofdActiviteit.
     */
    private fun navigateToOpstart() {
        try {
            val intent = Intent(this, HoofdActiviteit::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to navigate to OpstartScherm: ${ex.message}", ex)
            finish()
        }
    }

    /**
     * Restore credentials from secure storage.
     */
    private fun restoreCreds() {
        binding.etLogin.setText(creds.getUsername().orEmpty())
        binding.etPass.setText(creds.getPassword().orEmpty())
    }

    /**
     * Refresh SAF status text.
     */
    private fun refreshSafStatus() {
        val uri = saf.getRootUri()
        val ok = uri != null && saf.foldersExist()
        binding.tvStatus.text = when {
            uri == null -> getString(R.string.status_saf_niet_ingesteld)
            ok -> getString(R.string.status_saf_ok)
            else -> getString(R.string.status_saf_missing)
        }
    }

    /**
     * Update precompute button state (disabled if index already present).
     */
    private fun updatePrecomputeButtonState() = with(binding) {
        val vt5Dir = safManager.getVt5Directory()
        val present = vt5Dir != null && aliasManager.isIndexPresent(vt5Dir)
        btnAliasPrecompute.isEnabled = !present
        btnAliasPrecompute.alpha = if (present) 0.5f else 1.0f
    }
}
