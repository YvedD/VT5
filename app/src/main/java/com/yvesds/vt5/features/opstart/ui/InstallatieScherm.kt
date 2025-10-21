package com.yvesds.vt5.features.opstart.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.databinding.SchermInstallatieBinding
import com.yvesds.vt5.features.alias.AliasIndexWriter
import com.yvesds.vt5.features.alias.AliasPrecomputeWorker
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/**
 * XML Installatie-scherm (AppCompat + ViewBinding).
 * - SAF-map kiezen en submappen aanmaken
 * - Credentials bewaren/wissen
 * - Login testen (Basic Auth) -> popup + checkuser.json wegschrijven
 * - Serverdata downloaden (.json + .bin)
 * - Aliassen pr\u00e9-computen (assets -> binaries) via WorkManager
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

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            binding.tvStatus.text = getString(com.yvesds.vt5.R.string.status_saf_niet_ingesteld)
            return@registerForActivityResult
        }
        saf.takePersistablePermission(uri)
        saf.saveRootUri(uri)
        val ok = saf.foldersExist() || saf.ensureFolders()
        binding.tvStatus.text = if (ok) {
            // Als mappen OK zijn, preload data om latere overgangen sneller te maken
            preloadDataIfExists()
            getString(com.yvesds.vt5.R.string.status_saf_ok)
        } else {
            getString(com.yvesds.vt5.R.string.status_saf_missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SchermInstallatieBinding.inflate(layoutInflater)
        setContentView(binding.root)

        saf = SaFStorageHelper(this)
        creds = CredentialsStore(this)

        initUi()
        wireClicks()

        // Check of data al kan worden voorgeladen
        if (saf.foldersExist()) {
            preloadDataIfExists()
        }
    }

    /**
     * Preload data in de achtergrond om schermovergang naar MetadataScherm te versnellen
     */
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
            }
        }
    }

    private fun initUi() {
        binding.etUitleg.setText(getString(com.yvesds.vt5.R.string.install_uitleg))
        restoreCreds()
        refreshSafStatus()

        // Voorkom dat de UI "springt" door de views al direct hun juiste grootte te geven
        binding.etUitleg.measure(0, 0)
    }

    private fun wireClicks() = with(binding) {
        btnKiesDocuments.setOnClickListener { treePicker.launch(null) }

        btnCheckFolders.setOnClickListener {
            val ok = saf.foldersExist() || saf.ensureFolders()
            tvStatus.text = if (ok) {
                preloadDataIfExists()
                getString(com.yvesds.vt5.R.string.status_saf_ok)
            } else {
                getString(com.yvesds.vt5.R.string.status_saf_missing)
            }
        }

        btnWis.setOnClickListener {
            creds.clear()
            etLogin.setText("")
            etPass.setText("")
            Toast.makeText(this@InstallatieScherm, getString(com.yvesds.vt5.R.string.msg_credentials_gewist), Toast.LENGTH_SHORT).show()
        }

        btnBewaar.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            creds.save(u, p)
            Toast.makeText(this@InstallatieScherm, getString(com.yvesds.vt5.R.string.msg_credentials_opgeslagen), Toast.LENGTH_SHORT).show()
        }

        btnLoginTest.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(com.yvesds.vt5.R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            doLoginTestAndPersist(u, p)
        }

        btnDownloadJsons.setOnClickListener {
            val u = etLogin.text?.toString().orEmpty().trim()
            val p = etPass.text?.toString().orEmpty()
            if (u.isBlank() || p.isBlank()) {
                Toast.makeText(this@InstallatieScherm, getString(com.yvesds.vt5.R.string.msg_vul_login_eerst), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            doDownloadServerData(u, p)
        }

        btnAliasPrecompute.setOnClickListener { doAliasPrecompute() }

        btnKlaar.setOnClickListener {
            // Navigeer direct naar MetadataScherm in plaats van terug te gaan
            navigateToMetadata()
        }
    }

    /**
     * Navigeer naar MetadataScherm met voorgeladen data
     * Dit verbetert de app flow door een directe overgang mogelijk te maken
     */
    private fun navigateToMetadata() {
        val intent = Intent(this, MetadataScherm::class.java)

        // Accelerate the transition
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        uiScope.launch {
            try {
                // Ensure data is loaded before navigation
                if (!dataPreloaded) {
                    // Show progress while loading
                    val dlg = progressDialog(
                        title = getString(com.yvesds.vt5.R.string.dlg_busy_titel),
                        msg = "Gegevens laden..."
                    )
                    dlg.show()

                    withContext(Dispatchers.IO) {
                        ServerDataCache.getOrLoad(applicationContext)
                    }

                    dlg.dismiss()
                }

                startActivity(intent)
                finish() // Remove this activity from the back stack
            } catch (e: Exception) {
                Toast.makeText(this@InstallatieScherm, "Fout bij laden data: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error navigating to MetadataScherm", e)
            }
        }
    }

    /* -------------------- Actions -------------------- */

    private fun restoreCreds() {
        binding.etLogin.setText(creds.getUsername().orEmpty())
        binding.etPass.setText(creds.getPassword().orEmpty())
    }

    private fun refreshSafStatus() {
        val uri = saf.getRootUri()
        val ok = uri != null && saf.foldersExist()
        binding.tvStatus.text = when {
            uri == null -> getString(com.yvesds.vt5.R.string.status_saf_niet_ingesteld)
            ok -> getString(com.yvesds.vt5.R.string.status_saf_ok)
            else -> getString(com.yvesds.vt5.R.string.status_saf_missing)
        }
    }

    private fun doLoginTestAndPersist(username: String, password: String) {
        // Gebruik de ProgressDialogHelper in plaats van een aangepaste dialog
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
                showInfoDialog(getString(com.yvesds.vt5.R.string.dlg_titel_result), pretty)
                saveCheckUserJson(pretty)
            }.onFailure { e ->
                showInfoDialog("checkuser — fout", e.message ?: e.toString())
            }
        }
    }
    private fun doDownloadServerData(username: String, password: String) {
        val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
        if (vt5Dir == null) {
            Toast.makeText(this, getString(com.yvesds.vt5.R.string.msg_kies_documents_eerst), Toast.LENGTH_LONG).show()
            return
        }
        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("serverdata")
        val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("binaries")

        // Gebruik de ProgressDialogHelper
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

            // Belangrijk: in-memory cache ongeldig maken zodat nieuwe data direct gebruikt wordt
            ServerDataCache.invalidate()
            dataPreloaded = false

            // Direct na een download alvast in de achtergrond de data voorladen
            preloadDataIfExists()

            dlg.dismiss()
            showInfoDialog(getString(com.yvesds.vt5.R.string.dlg_titel_result), msgs.joinToString("\n"))
        }
    }

    private fun doAliasPrecompute() {
        // First run a quick preflight check and show results to the user. If OK, start worker.
        val preflight = AliasIndexWriter.preflightCheck(this, saf)
        val msg = preflight.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Preflight controle")
            .setMessage(msg + "\n\nVoortzetten met pré-computen?")
            .setPositiveButton("Ja") { _, _ ->
                // enqueue WorkManager job
                val request = OneTimeWorkRequestBuilder<com.yvesds.vt5.features.alias.AliasPrecomputeWorker>()
                    .addTag("alias-precompute")
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("alias_precompute", ExistingWorkPolicy.REPLACE, request)

                val dlg = ProgressDialogHelper.show(this, "Pré-computen van aliassen...")
                WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(request.id).observe(this, Observer { info: WorkInfo? ->
                    if (info == null) return@Observer
                    val progress = info.progress
                    val percent = progress.getInt("progress", -1)
                    val message = progress.getString("message") ?: "Bezig..."
                    ProgressDialogHelper.updateMessage(dlg, message)
                    if (info.state.isFinished) {
                        dlg.dismiss()
                        if (info.state == WorkInfo.State.SUCCEEDED) {
                            val out = info.outputData
                            val summary = out.getString("summary") ?: "Index succesvol bijgewerkt"
                            val messagesJson = out.getString("messages") ?: "[]"
                            AlertDialog.Builder(this)
                                .setTitle("Resultaat")
                                .setMessage(summary + "\n\nDetails:\n" + messagesJson)
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            val err = info.outputData.getString("error") ?: "Onbekende fout"
                            AlertDialog.Builder(this).setTitle("Fout").setMessage(err).setPositiveButton("OK", null).show()
                        }
                    }
                })
            }
            .setNegativeButton("Nee", null)
            .show()
    }

    // Temporary debug helper to inspect internal filesDir/binaries and SAF VT5 assets/binaries
    private fun verifyOutputsAndShowDialog() {
        uiScope.launch {
            val sb = StringBuilder()
            val binDir = File(filesDir, "binaries")
            if (binDir.exists() && binDir.isDirectory) {
                sb.append("Internal binaries (${binDir.absolutePath}):\n")
                binDir.listFiles()?.forEach { f ->
                    sb.append(" - ${f.name}  (${f.length()} bytes)\n")
                } ?: sb.append(" - (empty)\n")
            } else {
                sb.append("Internal binaries: NOT FOUND\n")
            }

            val vt5Dir = saf.getVt5DirIfExists()
            if (vt5Dir == null) {
                sb.append("\nSAF VT5 root: NOT SET\n")
            } else {
                sb.append("\nSAF VT5 root found. Listing:\n")
                fun listDir(doc: DocumentFile?, prefix: String) {
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
            }

            runOnUiThread {
                AlertDialog.Builder(this@InstallatieScherm)
                    .setTitle("Debug: outputs & SAF")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }    /* -------------------- Helpers -------------------- */

    private fun saveCheckUserJson(prettyText: String) {
        val vt5Dir = saf.getVt5DirIfExists() ?: return

        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
            ?: vt5Dir.createDirectory("serverdata")
            ?: return  // kon map niet maken

        // Overschrijf bestaand bestand netjes
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
            .setPositiveButton(getString(com.yvesds.vt5.R.string.dlg_ok)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun progressDialog(title: String, msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .create()
    }
}