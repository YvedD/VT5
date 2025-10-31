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
import com.yvesds.vt5.R
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.databinding.SchermInstallatieBinding
import com.yvesds.vt5.features.alias.AliasManager
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.hoofd.HoofdActiviteit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)
    private var dataPreloaded = false

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

    private fun initUi() {
        binding.etUitleg.setText(getString(R.string.install_uitleg))
        restoreCreds()
        refreshSafStatus()
        binding.etUitleg.measure(0, 0)
    }

    private fun wireClicks() = with(binding) {
        btnKiesDocuments.setOnClickListener { treePicker.launch(null) }

        btnCheckFolders.setOnClickListener {
            val ok = saf.foldersExist() || saf.ensureFolders()
            tvStatus.text = if (ok) {
                preloadDataIfExists()
                getString(R.string.status_saf_ok)
            } else {
                getString(R.string.status_saf_missing)
            }
            updatePrecomputeButtonState()
        }

        btnWis.setOnClickListener {
            creds.clear()
            etLogin.setText("")
            etPass.setText("")
            Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_gewist), Toast.LENGTH_SHORT).show()
        }

        btnBewaar.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            creds.save(u, p)
            Toast.makeText(this@InstallatieScherm, getString(R.string.msg_credentials_opgeslagen), Toast.LENGTH_SHORT).show()
        }

        btnLoginTest.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            doLoginTestAndPersist(u, p)
        }

        btnDownloadJsons.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            doDownloadServerData(u, p)
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
        uiScope.launch {
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
        val dlg = ProgressDialogHelper.show(this, "Login testen...")
        uiScope.launch {
            val res = withContext(Dispatchers.IO) {
                TrektellenAuth.checkUser(
                    username = username,
                    password = password,
                    language = "dutch",
                    versie = "1845"
                )
            }
            dlg.dismiss()
            res.onSuccess { pretty ->
                showInfoDialog(getString(R.string.dlg_titel_result), pretty)
                saveCheckUserJson(pretty)
            }.onFailure { e ->
                showInfoDialog("checkuser — fout", e.message ?: e.toString())
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
            return
        }
        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("serverdata")
        val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("binaries")

        val dlg = ProgressDialogHelper.show(this, "JSONs downloaden...")
        uiScope.launch {
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

            // Invalidate cache
            ServerDataCache.invalidate()
            dataPreloaded = false

            // Compute checksum of server files
            val newChecksum = computeServerFilesChecksum(vt5Dir)
            val oldMeta = readAliasMeta(vt5Dir)
            val oldChecksum = oldMeta?.sourceChecksum

            val needRegen = (oldChecksum == null) || (oldChecksum != newChecksum) || !isAliasIndexPresent()

            if (needRegen) {
                uiScope.launch {
                    try {
                        val reindexDialog = ProgressDialogHelper.show(this@InstallatieScherm, "Alias index bijwerken...")
                        withContext(Dispatchers.IO) {
                            // Ensure existing files removed so AliasManager.initialize will regenerate seed
                            removeExistingAliasFiles(vt5Dir)
                            AliasManager.initialize(this@InstallatieScherm, saf)
                        }
                        reindexDialog.dismiss()
                        // write metadata
                        writeAliasMeta(vt5Dir, AliasMasterMeta(sourceChecksum = newChecksum, sourceFiles = requiredServerFiles, timestamp = isoNow()))
                        Log.i(TAG, "Alias index regenerated (checksum changed or missing)")
                    } catch (ex: Exception) {
                        Log.w(TAG, "Alias reindex failed: ${ex.message}", ex)
                    } finally {
                        updatePrecomputeButtonState()
                    }
                }
            } else {
                Log.i(TAG, "No alias regeneration needed (checksum unchanged)")
                updatePrecomputeButtonState()
            }

            preloadDataIfExists()
            dlg.dismiss()
            // show results dialog with downloaded file list (no toast)
            showInfoDialog(getString(R.string.dlg_titel_result), msgs.joinToString("\n"))
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

        uiScope.launch {
            val dlg = ProgressDialogHelper.show(this@InstallatieScherm, "Forceer heropbouw alias index...")
            try {
                withContext(Dispatchers.IO) {
                    val vt5 = saf.getVt5DirIfExists() ?: throw IllegalStateException("SAF root niet ingesteld")
                    removeExistingAliasFiles(vt5)
                    AliasManager.initialize(this@InstallatieScherm, saf)
                    // compute new checksum and write meta
                    val newChecksum = computeServerFilesChecksum(vt5)
                    writeAliasMeta(vt5, AliasMasterMeta(sourceChecksum = newChecksum, sourceFiles = requiredServerFiles, timestamp = isoNow()))
                }
                binding.tvStatus.text = "Alias index rebuild voltooid"
            } catch (ex: Exception) {
                Log.e(TAG, "forceRebuildAliasIndex failed: ${ex.message}", ex)
                binding.tvStatus.text = "Fout bij rebuild: ${ex.message}"
            } finally {
                dlg.dismiss()
                updatePrecomputeButtonState()
            }
        }
    }

    private data class MergeResult(
        val mergedFilePath: String?,
        val existingSpecies: Int,
        val addedFromSite: Int,
        val aliasesAdded: Int,
        val conflicts: List<String>,
        val userAliasesPath: String? = null,
        val message: String? = null
    )

    // ... existing mergeAliasWithSite() removed/unused in this variant (legacy CSV path deprecated) ...
    // by design we no longer create CSVs in runtime

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

    private fun progressDialog(title: String, msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .create()
    }

    // Reuse the normalization logic used elsewhere (replace "/" -> " of " and remove diacritics)
    private fun normalizeLowerNoDiacritics(input: String): String {
        val replacedSlash = input.replace("/", " of ")
        val lower = replacedSlash.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    // Debug helper to inspect Documents/VT5 via SAF
    private fun verifyOutputsAndShowDialog() {
        uiScope.launch {
            val sb = StringBuilder()
            val vt5Dir = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                sb.append("SAF VT5 root: NOT SET\n")
            } else {
                sb.append("Documents/VT5 contents:\n")
                fun listDir(doc: DFile?, prefix: String) {
                    if (doc == null) { sb.append(" - $prefix: (not present)\n"); return }
                    try {
                        val files = doc.listFiles()
                        if (files.isEmpty()) sb.append(" - $prefix: (empty)\n")
                        else {
                            sb.append(" - $prefix:\n")
                            files.forEach { d -> sb.append("    * ${d.name}  (isDir=${d.isDirectory})\n") }
                        }
                    } catch (ex: Exception) {
                        sb.append(" - $prefix: error reading: ${ex.message}\n")
                    }
                }
                listDir(vt5Dir.findFile("assets"), "assets")
                listDir(vt5Dir.findFile("binaries"), "binaries")
                listDir(vt5Dir.findFile("serverdata"), "serverdata")
                listDir(vt5Dir.findFile("exports"), "exports")
                listDir(vt5Dir.findFile("counts"), "counts")
            }

            runOnUiThread {
                AlertDialog.Builder(this@InstallatieScherm)
                    .setTitle("Debug: Documents/VT5")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}