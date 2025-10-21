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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yvesds.vt5.R
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
import androidx.documentfile.provider.DocumentFile as DFile
import java.io.File

/**
 * Complete InstallatieScherm.kt
 *
 * - SAF selection (persist)
 * - Credentials save/wipe
 * - Serverdata download
 * - Précompute aliassen (SAF-only) via WorkManager
 * - Preflight check shows required SAF inputs
 * - Debug helper to inspect Documents/VT5 contents
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

        btnAliasPrecompute.setOnClickListener { doAliasPrecompute() }

        btnKlaar.setOnClickListener {
            navigateToMetadata()
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
            }
        }
    }

    private fun navigateToMetadata() {
        val intent = Intent(this, MetadataScherm::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        uiScope.launch {
            try {
                if (!dataPreloaded) {
                    val dlg = progressDialog(getString(R.string.dlg_busy_titel), "Gegevens laden...")
                    dlg.show()
                    withContext(Dispatchers.IO) {
                        ServerDataCache.getOrLoad(applicationContext)
                    }
                    dlg.dismiss()
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@InstallatieScherm, "Fout bij laden data: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error navigating to MetadataScherm", e)
            }
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
            ServerDataCache.invalidate()
            dataPreloaded = false
            preloadDataIfExists()
            dlg.dismiss()
            showInfoDialog(getString(R.string.dlg_titel_result), msgs.joinToString("\n"))
        }
    }

    private fun doAliasPrecompute() {
        // Preflight check: ensure SAF root and required files exist
        val preflight = AliasIndexWriter.preflightCheckSafOnly(this, saf)
        val msg = preflight.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Preflight controle (SAF-only)")
            .setMessage(msg + "\n\nVoortzetten met pré-computen? (Outputs gaan naar Documents/VT5 en NIET intern)")
            .setPositiveButton("Ja") { _, _ ->
                val request = OneTimeWorkRequestBuilder<AliasPrecomputeWorker>()
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
                            val messages = out.getString("messages") ?: ""
                            val safJson = out.getString("safJsonGzPath") ?: ""
                            val safCbor = out.getString("safCborGzPath") ?: ""
                            val safExport = out.getString("safExportLogPath") ?: ""
                            val dialogMsg = StringBuilder()
                                .append(summary).append("\n\n")
                                .append("SAF outputs:\n")
                                .append(" - assets: ").append(safJson).append("\n")
                                .append(" - binaries: ").append(safCbor).append("\n")
                                .append(" - export-log: ").append(safExport).append("\n\n")
                                .append("Details:\n").append(messages).toString()

                            AlertDialog.Builder(this)
                                .setTitle("Resultaat pré-compute")
                                .setMessage(dialogMsg)
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

    private fun progressDialog(title: String, msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .create()
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