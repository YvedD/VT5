@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.yvesds.vt5.features.opstart.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermInstallatieBinding
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.annotation.AnnotationsManager
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.hoofd.HoofdActiviteit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.documentfile.provider.DocumentFile as DFile

/**
 * InstallatieScherm.kt - updated to:
 * - auto-generate alias_master.json + aliases_optimized.cbor.gz on download only when sources changed
 * - compute & store source checksum metadata (alias_master.meta.json)
 * - disable précompute button when index already present
 * - provide Forceer reindex button (unlocks when pressed) without toast overview
 *
 * CSV support has been removed from runtime — all flows now use alias_master.json and aliases_optimized.cbor.gz.
 */
class InstallatieScherm : AppCompatActivity() {
    companion object {
        private const val TAG = "InstallatieScherm"
    }

    private lateinit var binding: SchermInstallatieBinding
    private lateinit var saf: SaFStorageHelper
    private lateinit var creds: CredentialsStore

    private var dataPreloaded = false
    
    // Track active progress dialogs to prevent leaks
    private var activeProgressDialog: android.app.Dialog? = null

    // JSON helper for metadata
    private val jsonPretty = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    // server files to include in checksum
    private val requiredServerFiles = listOf(
        "checkuser.json",
        "codes.json",
        "protocolinfo.json",
        "protocolspecies.json",
        "site_heights.json",
        "site_locations.json",
        "site_species.json",
        "sites.json",
        "species.json"
    )

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            binding.tvStatus.text = getString(R.string.status_saf_niet_ingesteld)
            return@registerForActivityResult
        }
        saf.takePersistablePermission(uri)
        saf.saveRootUri(uri)
        val ok = saf.foldersExist() || saf.ensureFolders()
        binding.tvStatus.text = if (ok) {
            preloadDataIfExists()
            getString(R.string.status_saf_ok)
        } else {
            getString(R.string.status_saf_missing)
        }
        // Update precompute button state after picking SAF
        updatePrecomputeButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermInstallatieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        saf = SaFStorageHelper(this)
        creds = CredentialsStore(this)

        initUi()
        wireClicks()

        if (saf.foldersExist()) {
            preloadDataIfExists()
        }

        // initial button state
        updatePrecomputeButtonState()
    }

    override fun onDestroy() {
        try {
            // Dismiss any active progress dialog to prevent window leaks
            activeProgressDialog?.dismiss()
            activeProgressDialog = null
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing progress dialog in onDestroy: ${e.message}")
        }
        super.onDestroy()
    }

    private fun initUi() {
        binding.etUitleg.setText(getString(R.string.install_uitleg))
        restoreCreds()
        refreshSafStatus()
        binding.etUitleg.measure(0, 0)
    }

    private fun wireClicks() = with(binding) {
        btnKiesDocuments.setOnClickListener { treePicker.launch(null) }

        btnCheckFolders.setOnClickListener {
            it.isEnabled = false
            try {
                val ok = saf.foldersExist() || saf.ensureFolders()
                tvStatus.text = if (ok) {
                    preloadDataIfExists()
                    getString(R.string.status_saf_ok)
                } else {
                    getString(R.string.status_saf_missing)
                }
                updatePrecomputeButtonState()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking folders: ${e.message}", e)
                showErrorDialog("Fout bij controleren folders", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        btnWis.setOnClickListener {
            it.isEnabled = false
            try {
                creds.clear()
                etLogin.setText("")
                etPass.setText("")
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_gewist), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing credentials: ${e.message}", e)
                showErrorDialog("Fout bij wissen credentials", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        btnBewaar.setOnClickListener {
            it.isEnabled = false
            try {
                val u = etLogin.text?.toString().orEmpty().trim()
                val p = etPass.text?.toString().orEmpty()
                creds.save(u, p)
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_opgeslagen), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving credentials: ${e.message}", e)
                showErrorDialog("Fout bij opslaan credentials", e.message ?: "Onbekende fout")
            } finally {
                it.isEnabled = true
            }
        }

        btnLoginTest.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            it.isEnabled = false
            try {
                doLoginTestAndPersist(u, p)
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating login test: ${e.message}", e)
                showErrorDialog("Fout bij login test", e.message ?: "Onbekende fout")
                it.isEnabled = true
            }
            // Note: button will be re-enabled in doLoginTestAndPersist after async completion
        }

        btnDownloadJsons.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            it.isEnabled = false
            try {
                doDownloadServerData(u, p)
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating download: ${e.message}", e)
                showErrorDialog("Fout bij starten download", e.message ?: "Onbekende fout")
                it.isEnabled = true
            }
            // Note: button will be re-enabled in doDownloadServerData after async completion
        }

        // Précompute button repurposed: Forceer reindex (unconditional rebuild) but disabled when index already present
        btnAliasPrecompute.setOnClickListener { forceRebuildAliasIndex() }

        // Changed: btnKlaar should return to OpstartScherm (startup flow) instead of MetadataScherm
        btnKlaar.setOnClickListener {
            navigateToOpstart()
        }
    }

    private fun preloadDataIfExists() {
        if (dataPreloaded) return
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Preloading data in background")
                withContext(Dispatchers.IO) {
                    ServerDataCache.preload(applicationContext)
                }
                dataPreloaded = true
                Log.d(TAG, "Data preloading complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error during data preloading: ${e.message}")
            } finally {
                updatePrecomputeButtonState()
            }
        }
    }

    // Navigate back to OpstartScherm so the startup flow continues there
    private fun navigateToOpstart() {
        try {
            val intent = Intent(this, HoofdActiviteit::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            finish()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to navigate to OpstartScherm: ${ex.message}", ex)
            // fallback: just finish
            finish()
        }
    }

    private fun restoreCreds() {
        binding.etLogin.setText(creds.getUsername().orEmpty())
        binding.etPass.setText(creds.getPassword().orEmpty())
    }

    private fun refreshSafStatus() {
        val uri = saf.getRootUri()
        val ok = uri != null && saf.foldersExist()
        binding.tvStatus.text = when {
            uri == null -> getString(R.string.status_saf_niet_ingesteld)
            ok -> getString(R.string.status_saf_ok)
            else -> getString(R.string.status_saf_missing)
        }
    }

    private fun doLoginTestAndPersist(username: String, password: String) {
        activeProgressDialog = ProgressDialogHelper.show(this, "Login testen...")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    TrektellenAuth.checkUser(
                        username = username,
                        password = password,
                        language = "dutch",
                        versie = "1845"
                    )
                }
                activeProgressDialog?.dismiss()
                activeProgressDialog = null
                
                res.onSuccess { pretty ->
                    showInfoDialog(getString(R.string.dlg_titel_result), pretty)
                    // persist checkuser.json on IO
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { saveCheckUserJson(pretty) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error saving checkuser.json: ${e.message}", e)
                            showErrorDialog("Waarschuwing", "Kon checkuser.json niet opslaan: ${e.message}")
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Login test failed: ${e.message}", e)
                    showErrorDialog("Login mislukt", e.message ?: e.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in doLoginTestAndPersist: ${e.message}", e)
                showErrorDialog("Fout bij login test", e.message ?: "Onbekende fout")
            } finally {
                activeProgressDialog?.dismiss()
                activeProgressDialog = null
                binding.btnLoginTest.isEnabled = true
            }
        }
    }

    /**
     * Trigger serverdata download and conditional reindexing.
     *
     * After successful download:
     *  - compute checksum of relevant server files
     *  - if checksum changed OR no index present -> force regeneration:
     *      delete existing master/cbor then call AliasManager.initialize(...) which will generate seed & write files
     *  - update meta file with new checksum
     */
    private fun doDownloadServerData(username: String, password: String) {
        val vt5Dir: DFile? = saf.getVt5DirIfExists()
        if (vt5Dir == null) {
            Toast.makeText(this, getString(R.string.msg_kies_documents_eerst), Toast.LENGTH_LONG).show()
            binding.btnDownloadJsons.isEnabled = true
            return
        }
        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("serverdata")
        val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("binaries")

        activeProgressDialog = ProgressDialogHelper.show(this, "JSONs downloaden...")
        lifecycleScope.launch {
            try {
                val msgs = withContext(Dispatchers.IO) {
                    ServerJsonDownloader.downloadAll(
                        context = this@InstallatieScherm,
                        serverdataDir = serverdata,
                        binariesDir = binaries,
                        username = username,
                        password = password,
                        language = "dutch",
                        versie = "1845"
                    )
                }

                // OPTIMIZATION 2: Parallel I/O operations for better performance
                // Execute annotations, cache invalidation, and checksum computation in parallel
                val (annotationsResult, _, checksumData) = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                        val annotationsJob = async {
                            try {
                                val created = writeAnnotationsJsonToSaf(vt5Dir)
                                if (created != null) {
                                    Log.i(TAG, "annotations.json ensured in SAF: ${created.name}")
                                    try {
                                        AnnotationsManager.loadCache(this@InstallatieScherm)
                                        Log.i(TAG, "Annotations cache loaded")
                                    } catch (ex: Exception) {
                                        Log.w(TAG, "Failed loading annotations cache: ${ex.message}", ex)
                                    }
                                } else {
                                    Log.w(TAG, "annotations.json not created (already present or error).")
                                }
                                true
                            } catch (ex: Exception) {
                                Log.w(TAG, "Failed ensuring annotations.json in SAF: ${ex.message}", ex)
                                false
                            }
                        }
                        
                        val cacheJob = async {
                            ServerDataCache.invalidate()
                        }
                        
                        // OPTIMIZATION 1: Cache checksum calculation to avoid duplicate computation
                        val checksumJob = async {
                            val newChecksum = computeServerFilesChecksum(vt5Dir)
                            val oldMeta = readAliasMeta(vt5Dir)
                            val oldChecksum = oldMeta?.sourceChecksum
                            val needsRegen = (oldChecksum == null) || (oldChecksum != newChecksum) || !isAliasIndexPresent()
                            Triple(newChecksum, needsRegen, oldMeta)
                        }
                        
                        Triple(annotationsJob.await(), cacheJob.await(), checksumJob.await())
                    }
                }
                
                dataPreloaded = false
                val (cachedChecksum, needRegen, _) = checksumData

                // Show annotation warnings to user if needed
                if (!annotationsResult) {
                    showErrorDialog("Waarschuwing", "Kon annotations.json niet aanmaken")
                }

                if (needRegen) {
                    // OPTIMIZATION 4: Update progress message instead of creating new dialog
                    ProgressDialogHelper.updateMessage(activeProgressDialog!!, "Alias index bijwerken...")
                    try {
                        withContext(Dispatchers.IO) {
                            // Ensure existing files removed so AliasManager.initialize will regenerate seed
                            removeExistingAliasFiles(vt5Dir)
                            AliasManager.initialize(this@InstallatieScherm, saf)
                            // OPTIMIZATION 1: Reuse cached checksum instead of recomputing
                            writeAliasMeta(vt5Dir, AliasMasterMeta(sourceChecksum = cachedChecksum, sourceFiles = requiredServerFiles, timestamp = isoNow()))
                        }
                        Log.i(TAG, "Alias index regenerated (checksum changed or missing)")
                    } catch (ex: Exception) {
                        Log.e(TAG, "Alias reindex failed: ${ex.message}", ex)
                        showErrorDialog("Fout bij alias reindex", ex.message ?: "Onbekende fout")
                    } finally {
                        updatePrecomputeButtonState()
                    }
                } else {
                    Log.i(TAG, "No alias regeneration needed (checksum unchanged)")
                    updatePrecomputeButtonState()
                }

                // OPTIMIZATION 3: Start preload in parallel while showing results
                val preloadJob = lifecycleScope.launch {
                    preloadDataIfExists()
                }
                
                activeProgressDialog?.dismiss()
                activeProgressDialog = null
                // show results dialog with downloaded file list (no toast)
                showInfoDialog(getString(R.string.dlg_titel_result), msgs.joinToString("\n"))
                
                // Ensure preload completes
                preloadJob.join()
            } catch (e: Exception) {
                Log.e(TAG, "Error during download: ${e.message}", e)
                showErrorDialog("Fout bij downloaden", e.message ?: "Onbekende fout")
            } finally {
                activeProgressDialog?.dismiss()
                activeProgressDialog = null
                binding.btnDownloadJsons.isEnabled = true
            }
        }
    }

    /**
     * Force rebuild invoked by the Pré-compute (now 'Forceer reindex') button.
     * This will delete existing master + cbor and then call AliasManager.initialize(...)
     * No toast overview is shown (per request) — UI state & logs updated.
     */
    private fun forceRebuildAliasIndex() {
        // disable button while working
        binding.btnAliasPrecompute.isEnabled = false
        binding.btnAliasPrecompute.alpha = 0.5f

        lifecycleScope.launch {
            activeProgressDialog = ProgressDialogHelper.show(this@InstallatieScherm, "Forceer heropbouw alias index...")
            try {
                withContext(Dispatchers.IO) {
                    val vt5 = saf.getVt5DirIfExists() ?: throw IllegalStateException("SAF root niet ingesteld")
                    removeExistingAliasFiles(vt5)
                    AliasManager.initialize(this@InstallatieScherm, saf)
                    // compute new checksum and write meta
                    val newChecksum = computeServerFilesChecksum(vt5)
                    writeAliasMeta(vt5, AliasMasterMeta(sourceChecksum = newChecksum, sourceFiles = requiredServerFiles, timestamp = isoNow()))
                }
                binding.tvStatus.text = getString(R.string.status_alias_rebuild_complete)
            } catch (ex: Exception) {
                Log.e(TAG, "forceRebuildAliasIndex failed: ${ex.message}", ex)
                binding.tvStatus.text = getString(R.string.status_alias_rebuild_error, ex.message ?: "Onbekend")
                showErrorDialog("Fout bij forceer rebuild", ex.message ?: "Onbekende fout")
            } finally {
                activeProgressDialog?.dismiss()
                activeProgressDialog = null
                updatePrecomputeButtonState()
            }
        }
    }

    private fun saveCheckUserJson(prettyText: String) {
        val vt5Dir = saf.getVt5DirIfExists() ?: return
        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("serverdata") ?: return
        serverdata.findFile("checkuser.json")?.delete()
        val f = serverdata.createFile("application/json", "checkuser.json") ?: return
        contentResolver.openOutputStream(f.uri, "w")?.use { out ->
            out.write(prettyText.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun showInfoDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.dlg_ok)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showErrorDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.dlg_ok)) { d, _ -> d.dismiss() }
            .show()
    }

    // small helper to get ISO timestamp
    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // -------------------------
    // Metadata for alias master
    // -------------------------
    @Serializable
    private data class AliasMasterMeta(
        val sourceChecksum: String,
        val sourceFiles: List<String>,
        val timestamp: String
    )

    // compute checksum (SHA-256 hex) over concatenated bytes of required server files (in listed order)
    private fun computeServerFilesChecksum(vt5Dir: DFile): String {
        return try {
            val serverDir = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: return ""
            val baos = java.io.ByteArrayOutputStream()
            for (name in requiredServerFiles) {
                val f = serverDir.findFile(name)
                if (f != null && f.isFile) {
                    contentResolver.openInputStream(f.uri)?.use { it.copyTo(baos) }
                }
            }
            val bytes = baos.toByteArray()
            sha256Hex(bytes)
        } catch (ex: Exception) {
            Log.w(TAG, "computeServerFilesChecksum failed: ${ex.message}")
            ""
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // read existing meta (alias_master.meta.json) if present
    private fun readAliasMeta(vt5Dir: DFile): AliasMasterMeta? {
        return try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return null
            val metaDoc = assets.findFile("alias_master.meta.json")?.takeIf { it.isFile } ?: return null
            val text = contentResolver.openInputStream(metaDoc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
            jsonPretty.decodeFromString(AliasMasterMeta.serializer(), text)
        } catch (ex: Exception) {
            Log.w(TAG, "readAliasMeta failed: ${ex.message}")
            null
        }
    }

    // write meta (overwrite)
    private fun writeAliasMeta(vt5Dir: DFile, meta: AliasMasterMeta) {
        try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("assets") ?: return
            assets.findFile("alias_master.meta.json")?.delete()
            val metaDoc = assets.createFile("application/json", "alias_master.meta.json") ?: return
            val txt = jsonPretty.encodeToString(AliasMasterMeta.serializer(), meta)
            contentResolver.openOutputStream(metaDoc.uri, "w")?.use { it.write(txt.toByteArray(Charsets.UTF_8)); it.flush() }
            Log.i(TAG, "Wrote alias_master.meta.json (checksum=${meta.sourceChecksum})")
        } catch (ex: Exception) {
            Log.w(TAG, "writeAliasMeta failed: ${ex.message}")
        }
    }

    // remove existing master and cbor to force AliasManager to regenerate
    private fun removeExistingAliasFiles(vt5Dir: DFile) {
        try {
            val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory }
            assets?.findFile("alias_master.json")?.delete()
            assets?.findFile("alias_master.meta.json")?.delete()
            val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory }
            binaries?.findFile("aliases_optimized.cbor.gz")?.delete()
        } catch (ex: Exception) {
            Log.w(TAG, "removeExistingAliasFiles failed: ${ex.message}")
        }
    }

    // Check if alias index exists (both pretty master in assets and cbor in binaries)
    private fun isAliasIndexPresent(): Boolean {
        val vt5Dir = saf.getVt5DirIfExists() ?: return false
        val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: return false
        val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: return false
        val master = assets.findFile("alias_master.json")?.takeIf { it.isFile }
        val cbor = binaries.findFile("aliases_optimized.cbor.gz")?.takeIf { it.isFile }
        return master != null && cbor != null
    }

    // Update précompute button state (disabled if index present)
    private fun updatePrecomputeButtonState() = with(binding) {
        val present = isAliasIndexPresent()
        btnAliasPrecompute.isEnabled = !present
        btnAliasPrecompute.alpha = if (present) 0.5f else 1.0f
    }

    /**
     * Write a hardcoded annotations.json into Documents/VT5/assets/annotations.json via SAF.
     * - If the file already exists, do nothing.
     * - Returns the created/existing DocumentFile or null on failure.
     */
    private fun writeAnnotationsJsonToSaf(vt5Dir: DFile?): DFile? {
        if (vt5Dir == null) return null
        try {
            // Ensure assets dir exists under Documents/VT5
            val assetsDir = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("assets")
            ?: run {
                Log.w(TAG, "Could not create/find assets directory in SAF")
                return null
            }

            // If already present, skip creation
            val existing = assetsDir.findFile("annotations.json")
            if (existing != null) {
                Log.d(TAG, "annotations.json already present in SAF, skipping creation")
                return existing
            }

            // Create file and write the hardcoded JSON content
            val created = assetsDir.createFile("application/json", "annotations.json") ?: run {
                Log.w(TAG, "Failed to create annotations.json in SAF")
                return null
            }

            // Hardcoded JSON content (exactly as provided by user)
            val jsonText = """
            {
              "leeftijd": [
                { "tekst": "adult",        "veld": "leeftijd", "waarde": "A" },
                { "tekst": "juveniel",     "veld": "leeftijd", "waarde": "J" },
                { "tekst": ">1kj",         "veld": "leeftijd", "waarde": "I" },
                { "tekst": "1kj",          "veld": "leeftijd", "waarde": "1" },
                { "tekst": "2kj",          "veld": "leeftijd", "waarde": "2" },
                { "tekst": "3kj",          "veld": "leeftijd", "waarde": "3" },
                { "tekst": "4kj",          "veld": "leeftijd", "waarde": "4" },
                { "tekst": "niet juv.",    "veld": "leeftijd", "waarde": "Non-Juv" }
              ],

              "geslacht": [
                { "tekst": "man",         "veld": "geslacht", "waarde": "M" },
                { "tekst": "vrouw",       "veld": "geslacht", "waarde": "F" },
                { "tekst": "vrouwkleed",  "veld": "geslacht", "waarde": "FC" }
              ],

              "kleed": [
                { "tekst": "zomerkleed",  "veld": "kleed", "waarde": "B" },
                { "tekst": "winterkleed", "veld": "kleed", "waarde": "W" },
                { "tekst": "man",         "veld": "kleed", "waarde": "M" },
                { "tekst": "vrouw",       "veld": "kleed", "waarde": "F" },
                { "tekst": "licht",       "veld": "kleed", "waarde": "L" },
                { "tekst": "donker",      "veld": "kleed", "waarde": "D" },
                { "tekst": "eclips",      "veld": "kleed", "waarde": "E" },
                { "tekst": "intermediar", "veld": "kleed", "waarde": "I" }
              ],

              "teltype": [
                { "tekst": "Handteller",   "veld": "teltype_C", "waarde": "C" }
              ],

               "height": [
                { "tekst": "<25m",    "veld": "location", "waarde": "<25m" },
                { "tekst": "<50m",    "veld": "location", "waarde": "<50m" },
                { "tekst": "50-100m", "veld": "location", "waarde": "50-100m" },
                { "tekst": "100-200m","veld": "location", "waarde": "100-200m" },
                { "tekst": ">200m",   "veld": "location", "waarde": ">200m" }
              ],

              "location": [
                { "tekst": "zee",       "veld": "height", "waarde": "zee" },
                { "tekst": "branding",  "veld": "height", "waarde": "branding" },
                { "tekst": "duinen",    "veld": "height", "waarde": "duinen" },
                { "tekst": "binnenkant","veld": "height", "waarde": "binnenkant" },
                { "tekst": "polders",   "veld": "height", "waarde": "polders" },
                { "tekst": "bos",       "veld": "height", "waarde": "bos" },
                { "tekst": "over water","veld": "height", "waarde": "over water" }
              ]
            }
            """.trimIndent()

            // write via contentResolver
            contentResolver.openOutputStream(created.uri, "w")?.use { out ->
                out.write(jsonText.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            Log.i(TAG, "Wrote annotations.json to SAF: ${created.uri}")
            return created
        } catch (ex: Exception) {
            Log.w(TAG, "writeAnnotationsJsonToSaf failed: ${ex.message}", ex)
            return null
        }
    }
}