package com.yvesds.vt5.features.opstart.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.databinding.SchermInstallatieBinding
import com.yvesds.vt5.features.alias.AliasIndexWriter
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * XML Installatie-scherm (AppCompat + ViewBinding).
 * - SAF-map kiezen en submappen aanmaken
 * - Credentials bewaren/wissen
 * - Login testen (Basic Auth) -> popup + checkuser.json wegschrijven
 * - Serverdata downloaden (.json + .bin)
 * - Aliassen pré-computen (assets -> binaries)
 * Alles off-main.
 */
class InstallatieScherm : AppCompatActivity() {

    private lateinit var binding: SchermInstallatieBinding
    private lateinit var saf: SaFStorageHelper
    private lateinit var creds: CredentialsStore

    private val uiScope = CoroutineScope(Job() + Dispatchers.Main)

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            binding.tvStatus.text = getString(com.yvesds.vt5.R.string.status_saf_niet_ingesteld)
            return@registerForActivityResult
        }
        saf.takePersistablePermission(uri)
        saf.saveRootUri(uri)
        val ok = saf.foldersExist() || saf.ensureFolders()
        binding.tvStatus.text = if (ok) {
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
    }

    private fun initUi() {
        binding.etUitleg.setText(getString(com.yvesds.vt5.R.string.install_uitleg))
        restoreCreds()
        refreshSafStatus()
    }

    private fun wireClicks() = with(binding) {
        btnKiesDocuments.setOnClickListener { treePicker.launch(null) }

        btnCheckFolders.setOnClickListener {
            val ok = saf.foldersExist() || saf.ensureFolders()
            tvStatus.text = if (ok) {
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
            setResult(Activity.RESULT_OK, Intent())
            finish()
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
        val dlg = progressDialog(
            title = getString(com.yvesds.vt5.R.string.dlg_busy_titel),
            msg = "Login testen…"
        )
        dlg.show()
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

        val dlg = progressDialog(
            title = getString(com.yvesds.vt5.R.string.dlg_busy_titel),
            msg = "JSONs downloaden…"
        )
        dlg.show()
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
            dlg.dismiss()
            showInfoDialog(getString(com.yvesds.vt5.R.string.dlg_titel_result), msgs.joinToString("\n"))
        }
    }

    private fun doAliasPrecompute() {
        val dlg = progressDialog(
            title = getString(com.yvesds.vt5.R.string.dlg_busy_titel),
            msg = "Pré-computen van aliassen…"
        )
        dlg.show()
        uiScope.launch {
            val msg = withContext(Dispatchers.Default) {
                runCatching {
                    val (jsonGz, cborGz) = AliasIndexWriter.ensureComputed(
                        context = this@InstallatieScherm,
                        saf = saf,
                        q = 3,
                        minhashK = 64
                    )
                    val index = AliasIndexWriter.loadIndexFromBinaries(this@InstallatieScherm)
                    val cnt = index?.json?.size ?: 0
                    "Aliassen pré-computed en geladen.\n" +
                            "- Records: $cnt\n" +
                            "- JSON.gz: $jsonGz\n" +
                            "- CBOR.gz: $cborGz"
                }.getOrElse { e ->
                    "Pré-compute aliassen — fout:\n${e.message ?: e.toString()}"
                }
            }
            dlg.dismiss()
            showInfoDialog(getString(com.yvesds.vt5.R.string.dlg_titel_result), msg)
        }
    }

    /* -------------------- Helpers -------------------- */

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
