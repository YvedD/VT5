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
import androidx.lifecycle.lifecycleScope
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
import com.yvesds.vt5.features.alias.PrecomputeAliasIndex
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.features.serverdata.model.ServerDataCache
import com.yvesds.vt5.core.ui.ProgressDialogHelper
import com.yvesds.vt5.hoofd.HoofdActiviteit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile as DFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * InstallatieScherm.kt - merge aliasmapping.csv + site_species.json -> alias_merged.csv
 *
 * Gedrag:
 * - leest Documents/VT5/assets/aliasmapping.csv (indien aanwezig)
 * - leest Documents/VT5/serverdata/site_species.json (indien aanwezig)
 * - leest Documents/VT5/serverdata/species.json (indien aanwezig) om soortid -> naam/key te mappen
 * - voegt site-only soorten toe en merge't aliassen zonder bestaande aliassen te verwijderen
 * - kolommen:
 *     0 = speciesId (unchanged)
 *     1 = canonical (preserve case from species.json or CSV)
 *     2 = tilename (preserve case)
 *     3+ = aliases (lowercased, "/" -> " of ")
 * - indien aliasmapping.csv ontbreekt: er wordt alias_merged.csv aangemaakt met per soort
 *   canonical toegevoegd als alias (lowercased) zodat parser altijd iets heeft
 * - er wordt altijd een user_aliases.csv aangemaakt/gevuld:
 *     - als aliasmapping.csv aanwezig: user_aliases.csv bevat per soort de aliassen uit aliasmapping.csv (lowercased)
 *     - als aliasmapping.csv ontbreekt: user_aliases.csv bevat per soort 1 alias = canonical (lowercased)
 * - conflicten (genormaliseerde alias gekoppeld aan meerdere soortids) worden gedetecteerd en gerapporteerd
 * - resultaten worden weggeschreven naar Documents/VT5/assets/alias_merged.csv en assets/user_aliases.csv
 *
 * UPDATED: Trigger alias reindex na download (HIGH PRIORITY PATCH)
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

        // btnAliasPrecompute now runs merge -> user confirm -> precompute
        btnAliasPrecompute.setOnClickListener { doAliasPrecompute() }

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
     * UPDATED: Trigger alias reindex na download (HIGH PRIORITY PATCH)
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

            // NEW: Trigger alias reindex na download
            uiScope.launch {
                try {
                    val reindexDialog = ProgressDialogHelper.show(this@InstallatieScherm, "Alias index bijwerken...")
                    val result = withContext(Dispatchers.IO) {
                        AliasIndexWriter.ensureComputedSafOnly(this@InstallatieScherm, saf, q = 3, minhashK = 8)
                    }
                    reindexDialog.dismiss()
                    Log.d(TAG, "Alias index updated: ${result.aliasCount} aliases")
                } catch (ex: Exception) {
                    Log.w(TAG, "Alias reindex failed: ${ex.message}", ex)
                }
            }
            preloadDataIfExists()
            dlg.dismiss()
            showInfoDialog(getString(R.string.dlg_titel_result), msgs.joinToString("\n"))
        }
    }

    /**
     * Main entry for pré-computing aliases.
     * This triggers merge first (if possible), writes alias_merged.csv and user_aliases.csv,
     * shows summary and then optionally runs precompute.
     */
    private fun doAliasPrecompute() {
        val preflight = AliasIndexWriter.preflightCheckSafOnly(this, saf)
        val msg = preflight.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Preflight controle (SAF-only)")
            .setMessage(msg + "\n\nVoortzetten met samenvoegen en pré-computen? (Outputs gaan naar Documents/VT5 en NIET intern)")
            .setPositiveButton("Ja") { _, _ ->
                uiScope.launch {
                    val dlgMerge = ProgressDialogHelper.show(this@InstallatieScherm, "Mergen aliasmapping met site_species...")
                    val mergeResult = withContext(Dispatchers.IO) { mergeAliasWithSite() }
                    dlgMerge.dismiss()

                    if (mergeResult.mergedFilePath == null) {
                        AlertDialog.Builder(this@InstallatieScherm)
                            .setTitle("Merge resultaat")
                            .setMessage(mergeResult.message ?: "Merge niet uitgevoerd.")
                            .setPositiveButton("OK") { _, _ ->
                                promptRunPrecompute()
                            }
                            .show()
                    } else {
                        val summary = StringBuilder()
                        summary.append("Merged file: ${mergeResult.mergedFilePath}\n")
                        summary.append(" - species_in_csv: ${mergeResult.existingSpecies}\n")
                        summary.append(" - species_added_from_site: ${mergeResult.addedFromSite}\n")
                        summary.append(" - total_aliases: ${mergeResult.aliasesAdded}\n")
                        mergeResult.userAliasesPath?.let { summary.append(" - user aliases: $it\n") }
                        if (mergeResult.conflicts.isNotEmpty()) {
                            summary.append("\nConflicten gedetecteerd (alias -> meerdere species):\n")
                            mergeResult.conflicts.take(20).forEach { c -> summary.append(" - ${c}\n") }
                            if (mergeResult.conflicts.size > 20) summary.append(" ... en ${mergeResult.conflicts.size - 20} meer\n")
                            summary.append("\nControleer conflicten handmatig in het CSV bestand.\n")
                        }

                        AlertDialog.Builder(this@InstallatieScherm)
                            .setTitle("Merge voltooid")
                            .setMessage(summary.toString())
                            .setPositiveButton("Start pré-compute") { _, _ ->
                                startPrecomputeWorker()
                            }
                            .setNeutralButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Nee", null)
            .show()
    }

    private fun promptRunPrecompute() {
        AlertDialog.Builder(this)
            .setTitle("Pré-compute")
            .setMessage("Wil je doorgaan met pré-computen met de huidige bestanden?")
            .setPositiveButton("Ja") { _, _ -> startPrecomputeWorker() }
            .setNegativeButton("Nee", null)
            .show()
    }

    private fun startPrecomputeWorker() {
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

    private data class MergeResult(
        val mergedFilePath: String?,
        val existingSpecies: Int,
        val addedFromSite: Int,
        val aliasesAdded: Int,
        val conflicts: List<String>,
        val userAliasesPath: String? = null,
        val message: String? = null
    )

    /**
     * Merge routine:
     * - reads aliasmapping.csv from assets (if present)
     * - reads site_species.json from serverdata (if present)
     * - reads species.json from serverdata (if present) to map soortid -> naam/key
     * - creates alias_merged.csv in assets (overwrites existing alias_merged.csv)
     * - creates/overwrites user_aliases.csv in assets:
     *     - if aliasmapping.csv exists: include aliases from aliasmapping.csv (lowercased) per species
     *     - else: include per species the canonical lowercased as initial alias
     */
    private suspend fun mergeAliasWithSite(): MergeResult {
        return withContext(Dispatchers.IO) {
            try {
                val vt5Dir = saf.getVt5DirIfExists() ?: return@withContext MergeResult(null, 0, 0, 0, emptyList(), null, "SAF root niet ingesteld")
                val assets = vt5Dir.findFile("assets")?.takeIf { it.isDirectory } ?: vt5Dir.createDirectory("assets") ?: return@withContext MergeResult(null, 0, 0, 0, emptyList(), null, "Kan assets map niet aanmaken")
                val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }

                // Read aliasmapping.csv if present
                val aliasCsvDoc = assets.findFile("aliasmapping.csv")?.takeIf { it.isFile }
                val csvText = aliasCsvDoc?.let {
                    contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes().toString(Charsets.UTF_8) }
                } ?: ""

                // Read site_species.json if present
                val siteSpeciesDoc = serverdata?.findFile("site_species.json")?.takeIf { it.isFile }
                val siteSpeciesBytes = siteSpeciesDoc?.let {
                    contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() }
                }

                // Read species.json (the canonical species mapping) if present
                val speciesDoc = serverdata?.findFile("species.json")?.takeIf { it.isFile }
                val speciesBytes = speciesDoc?.let {
                    contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() }
                }

                if (csvText.isBlank() && siteSpeciesBytes == null) {
                    return@withContext MergeResult(null, 0, 0, 0, emptyList(), null, "Geen invoerbestanden gevonden (aliasmapping.csv of site_species.json ontbreken).")
                }

                // Parse existing CSV rows using PrecomputeAliasIndex.parseCsv
                val csvRows = if (csvText.isBlank()) emptyList() else PrecomputeAliasIndex.parseCsv(csvText)

                // Build accumulator: speciesId -> RowAcc
                data class RowAcc(var canonical: String, var tilename: String?, val aliases: MutableSet<String>)

                val acc = mutableMapOf<String, RowAcc>()

                // Also collect userAliases source (from aliasmapping.csv if any)
                val userAcc = mutableMapOf<String, MutableSet<String>>() // speciesId -> aliases (lowercased)

                csvRows.forEach { r ->
                    val existing = acc.getOrPut(r.speciesId) {
                        RowAcc(
                            canonical = r.canonical ?: "",
                            tilename = r.tileName,
                            aliases = mutableSetOf()
                        )
                    }
                    // Add aliases to acc (lowercased normalized for alias columns)
                    r.aliases.forEach { aliasRaw ->
                        val a = formatAliasForCsv(aliasRaw)
                        if (a.isNotBlank()) existing.aliases.add(a)
                        if (a.isNotBlank()) userAcc.getOrPut(r.speciesId) { mutableSetOf() }.add(a)
                    }
                    // if no aliases present in CSV, don't add an empty alias here yet
                    if (existing.canonical.isBlank() && r.canonical.isNotBlank()) existing.canonical = r.canonical
                    if (existing.tilename.isNullOrBlank() && !r.tileName.isNullOrBlank()) existing.tilename = r.tileName
                }

                // Build species map from species.json: soortid -> (soortnaam, soortkey)
                val speciesMap = mutableMapOf<String, Pair<String, String?>>()
                if (speciesBytes != null) {
                    val s = speciesBytes.toString(Charsets.UTF_8)
                    val parsed = Json.parseToJsonElement(s).jsonObject
                    val arr = parsed["json"]?.jsonArray
                    arr?.forEach { el ->
                        val obj = el.jsonObject
                        val sid = obj["soortid"]?.toString()?.trim('"')?.lowercase()
                        val naam = obj["soortnaam"]?.toString()?.trim('"')
                        val key = obj["soortkey"]?.toString()?.trim('"')
                        if (!sid.isNullOrBlank()) {
                            speciesMap[sid] = Pair(naam ?: sid, key?.takeIf { it.isNotBlank() })
                        }
                    }
                }

                // Collect all unique soortids from site_species.json (site lists)
                val siteSpeciesIds = mutableSetOf<String>()
                if (siteSpeciesBytes != null) {
                    val s = siteSpeciesBytes.toString(Charsets.UTF_8)
                    val parsed = Json.parseToJsonElement(s).jsonObject
                    val arr = parsed["json"]?.jsonArray
                    arr?.forEach { el ->
                        val obj = el.jsonObject
                        val sid = obj["soortid"]?.toString()?.trim('"')?.lowercase()
                        if (!sid.isNullOrBlank()) siteSpeciesIds.add(sid)
                    }
                }

                // Ensure every soortid from siteSpeciesIds is present in acc, using speciesMap to fill canonical/tilename
                var addedFromSite = 0
                for (sid in siteSpeciesIds) {
                    if (!acc.containsKey(sid)) {
                        val (naamRaw, keyRaw) = speciesMap[sid] ?: Pair(sid, null)
                        val canonicalCsv = naamRaw ?: sid
                        val tilenameCsv = keyRaw
                        val row = RowAcc(canonicalCsv, tilenameCsv, mutableSetOf())
                        // add canonical as alias (lowercased) so column 3 never empty
                        val aliasFromCanonical = formatAliasForCsv(naamRaw ?: sid)
                        if (aliasFromCanonical.isNotBlank()) {
                            row.aliases.add(aliasFromCanonical)
                            // also add to userAcc if csv was missing (we will create user_aliases.csv)
                            if (csvText.isBlank()) userAcc.getOrPut(sid) { mutableSetOf() }.add(aliasFromCanonical)
                        }
                        acc[sid] = row
                        addedFromSite++
                    } else {
                        val row = acc[sid]!!
                        val (naamRaw, keyRaw) = speciesMap[sid] ?: Pair(row.canonical, row.tilename)
                        if (row.canonical.isBlank() && !naamRaw.isNullOrBlank()) row.canonical = naamRaw
                        if (row.tilename.isNullOrBlank() && !keyRaw.isNullOrBlank()) row.tilename = keyRaw
                        // ensure canonical present as alias if no aliases yet
                        val aliasFromCanonical = formatAliasForCsv(naamRaw ?: row.canonical)
                        if (row.aliases.isEmpty() && aliasFromCanonical.isNotBlank()) {
                            row.aliases.add(aliasFromCanonical)
                            if (csvText.isBlank()) userAcc.getOrPut(sid) { mutableSetOf() }.add(aliasFromCanonical)
                        }
                    }
                }

                // If aliasmapping.csv existed but some species had no aliases (empty col3), ensure canonical is present as alias
                if (csvText.isNotBlank()) {
                    acc.forEach { (sid, row) ->
                        if (row.aliases.isEmpty()) {
                            val canonical = row.canonical.ifBlank { speciesMap[sid]?.first ?: sid }
                            val aliasFromCanonical = formatAliasForCsv(canonical)
                            if (aliasFromCanonical.isNotBlank()) {
                                row.aliases.add(aliasFromCanonical)
                                userAcc.getOrPut(sid) { mutableSetOf() }.add(aliasFromCanonical)
                            }
                        }
                    }
                }

                // Conflict detection: normalized alias -> set of speciesIds
                fun normalizeForKey(s: String): String = normalizeLowerNoDiacritics(s.replace("/", " of "))

                val aliasToSpecies = mutableMapOf<String, MutableSet<String>>()
                acc.forEach { (sid, row) ->
                    row.aliases.forEach { alias ->
                        val norm = normalizeForKey(alias)
                        if (norm.isBlank()) return@forEach
                        aliasToSpecies.getOrPut(norm) { mutableSetOf() }.add(sid)
                    }
                }

                val conflicts = aliasToSpecies.filter { it.value.size > 1 }.map { (alias, sids) -> "$alias -> ${sids.joinToString(",")}" }

                // Build merged CSV content (alias_merged.csv)
                val sbMerged = StringBuilder()
                var existingSpecies = 0
                var aliasesAdded = 0

                // Create comparator that sorts numerically when possible, otherwise lexicographically
                val sidComparator = Comparator<String> { a, b ->
                    val ai = a.toIntOrNull()
                    val bi = b.toIntOrNull()
                    when {
                        ai != null && bi != null -> ai.compareTo(bi)
                        ai != null && bi == null -> -1
                        ai == null && bi != null -> 1
                        else -> a.compareTo(b)
                    }
                }

                // sort keys deterministically using the comparator
                acc.keys.sortedWith(sidComparator).forEach { sid ->
                    val row = acc[sid]!!
                    existingSpecies++
                    val aliasesList = row.aliases.toList()
                    aliasesAdded += aliasesList.size
                    sbMerged.append(sid).append(';')
                    // column 1: canonical (preserve case)
                    sbMerged.append(row.canonical.replace(';', ' ')).append(';')
                    // column 2: tilename (preserve case)
                    sbMerged.append(row.tilename?.replace(';', ' ') ?: "").append(';')
                    // columns 3+: aliases (already normalized to lowercase and "/"->" of ")
                    sbMerged.append(aliasesList.joinToString(";") { it.replace(';', ' ') })
                    sbMerged.append('\n')
                }

                // Write merged file alias_merged.csv (overwrite if exists)
                val mergedName = "alias_merged.csv"
                assets.findFile(mergedName)?.delete()
                val mergedDoc = assets.createFile("text/csv", mergedName) ?: return@withContext MergeResult(null, existingSpecies, addedFromSite, aliasesAdded, conflicts, null, "Kan merged file niet aanmaken in assets")
                contentResolver.openOutputStream(mergedDoc.uri, "w")?.use { os ->
                    os.write(sbMerged.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                // Build and write user_aliases.csv:
                val sbUser = StringBuilder()
                var userAliasesCount = 0
                userAcc.keys.sortedWith(sidComparator).forEach { sid ->
                    val aliases = userAcc[sid]?.toList() ?: emptyList()
                    val canonical = acc[sid]?.canonical ?: speciesMap[sid]?.first ?: sid
                    val tilename = acc[sid]?.tilename ?: speciesMap[sid]?.second
                    sbUser.append(sid).append(';')
                    sbUser.append(canonical.replace(';', ' ')).append(';')
                    sbUser.append(tilename?.replace(';', ' ') ?: "").append(';')
                    sbUser.append(aliases.joinToString(";") { it.replace(';', ' ') })
                    sbUser.append('\n')
                    userAliasesCount += aliases.size
                }

                val userName = "user_aliases.csv"
                assets.findFile(userName)?.delete()
                val userDoc = assets.createFile("text/csv", userName) ?: return@withContext MergeResult(mergedDoc.uri.toString(), existingSpecies, addedFromSite, aliasesAdded, conflicts, null, "Kan user_aliases file niet aanmaken in assets")
                contentResolver.openOutputStream(userDoc.uri, "w")?.use { os ->
                    os.write(sbUser.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val mergedPath = "Documents/VT5/assets/$mergedName"
                val userPath = "Documents/VT5/assets/$userName"
                return@withContext MergeResult(mergedPath, existingSpecies, addedFromSite, aliasesAdded, conflicts, userPath, null)
            } catch (ex: Exception) {
                Log.e(TAG, "Error during mergeAliasWithSite: ${ex.message}", ex)
                return@withContext MergeResult(null, 0, 0, 0, emptyList(), null, "Fout tijdens merge: ${ex.message}")
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

    private fun progressDialog(title: String, msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .create()
    }

    // Reuse the normalization logic used in PrecomputeAliasIndex with "/" -> " of " replacement
    private fun normalizeLowerNoDiacritics(input: String): String {
        val replacedSlash = input.replace("/", " of ")
        val lower = replacedSlash.lowercase(Locale.getDefault())
        val decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        val noDiacritics = decomposed.replace("\\p{Mn}+".toRegex(), "")
        val cleaned = noDiacritics.replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ").trim()
        return cleaned.replace("\\s+".toRegex(), " ")
    }

    // aliases: lowercase, replace "/" with " of ", collapse spaces, remove semicolons
    private fun formatAliasForCsv(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.replace("/", " of ")
        s = s.replace(";", " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.lowercase(Locale.getDefault())
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