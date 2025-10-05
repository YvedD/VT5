package com.yvesds.vt5.features.opstart.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.alias.AliasIndexWriter
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.features.opstart.usecases.TrektellenAuth
import com.yvesds.vt5.ui.componenten.AppOutlinedKnop
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VT5 – InstallatieScherm
 *
 * Eén scherm dat:
 *  - SAF map laat kiezen en submappen controleert/aanmaakt
 *  - Trektellen credentials opslaat en login-check uitvoert
 *  - Serverdata (.json) downloadt en binaries bijwerkt
 *  - Aliassen pré-computet uit assets/aliasmapping.csv → filesDir/binaries
 *
 * NB: we hebben eSpeak-NG volledig verwijderd (geen dependency/knop/progress meer).
 */
@Composable
fun InstallatieScherm(
    onKlaar: () -> Unit
) {
    val context = LocalContext.current
    val saf = remember { SaFStorageHelper(context) }
    val creds = remember { CredentialsStore(context) }
    val scope = rememberCoroutineScope()

    var gekozenUri by remember { mutableStateOf<Uri?>(saf.getRootUri()) }
    var foldersOk by remember { mutableStateOf(saf.foldersExist()) }
    var status by remember { mutableStateOf(makeStatusText(gekozenUri, foldersOk)) }

    var username by remember { mutableStateOf(creds.getUsername().orEmpty()) }
    var password by remember { mutableStateOf(creds.getPassword().orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }

    // Pop-up queue (batched resultaten)
    val uiPrefs = remember { UiPrefs(context) }
    var showDialog by remember { mutableStateOf(false) }
    val dialogQueue = remember { mutableStateListOf<String>() }
    var dialogText by remember { mutableStateOf("") }

    // Loading states
    var loadingAuth by remember { mutableStateOf(false) }
    var loadingDownloads by remember { mutableStateOf(false) }
    var loadingAliases by remember { mutableStateOf(false) }
    var aliasProgressMsg by remember { mutableStateOf("Pré-computen van aliassen…") }

    // SAF folder chooser
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            saf.takePersistablePermission(uri)
            saf.saveRootUri(uri)
            gekozenUri = uri
            foldersOk = saf.foldersExist() || saf.ensureFolders()
            status = makeStatusText(gekozenUri, foldersOk)
        } else {
            status = "Geen map gekozen. Kies 'Documents' en bevestig 'Deze map gebruiken'."
        }
    }

    val uitlegTekst =
        "Deze toepassing moet kunnen bestanden inlezen, wegschrijven en bewerken.\n" +
                "Bevestig hierna het gebruik van de map 'Documents'. Kies de optie [Deze map gebruiken] om verder te gaan. " +
                "Alle mappen en bestanden worden voor u aangemaakt indien deze nog niet bestaan. " +
                "Bij een herinstallatie worden er geen bestanden overschreven!"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VT5 (Her)Installatie", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = uitlegTekst,
                onValueChange = { /* read-only */ },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                label = { Text("Belangrijke uitleg") },
                supportingText = { Text("Bevestig het gebruik van de map 'Documents' in de volgende stap.") }
            )

            AppPrimaireKnop(
                tekst = "Map 'Documents' kiezen",
                modifier = Modifier.fillMaxWidth(),
                onClick = { treeLauncher.launch(null) }
            )

            AppOutlinedKnop(
                tekst = "Submappen controleren/aanmaken",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (gekozenUri == null) {
                        status = "SAF niet ingesteld. Kies eerst 'Documents' en bevestig 'Deze map gebruiken'."
                    } else {
                        foldersOk = saf.foldersExist() || saf.ensureFolders()
                        status = makeStatusText(gekozenUri, foldersOk)
                    }
                }
            )

            Text(text = status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f))

            Text("Trektellen-gegevens", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Loginnaam") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Paswoord") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        if (showPassword) {
                            Icon(imageVector = Icons.Filled.VisibilityOff, contentDescription = "Verberg wachtwoord")
                        } else {
                            Icon(imageVector = Icons.Filled.Visibility, contentDescription = "Toon wachtwoord")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                AppOutlinedKnop(
                    tekst = "Wis",
                    onClick = {
                        CredentialsStore(context).clear()
                        username = ""
                        password = ""
                        Toast.makeText(context, "Credentials gewist", Toast.LENGTH_SHORT).show()
                    }
                )
                AppPrimaireKnop(
                    tekst = "Bewaar",
                    onClick = {
                        CredentialsStore(context).save(username.trim(), password)
                        Toast.makeText(context, "Credentials opgeslagen", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Login testen (Basic Auth)
            AppOutlinedKnop(
                tekst = if (loadingAuth) "Bezig…" else "Login testen (Basic Auth)",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (loadingAuth) return@AppOutlinedKnop
                    if (username.isBlank() || password.isBlank()) {
                        Toast.makeText(context, "Vul eerst login en paswoord in.", Toast.LENGTH_LONG).show()
                        return@AppOutlinedKnop
                    }
                    loadingAuth = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            TrektellenAuth.checkUser(
                                username = username.trim(),
                                password = password,
                                language = "dutch",
                                versie = "1845"
                            )
                        }
                        loadingAuth = false
                        res.onSuccess { txt ->
                            if (uiPrefs.getShowAuthPopup()) {
                                dialogQueue.add("checkuser\n\n$txt")
                                if (!showDialog) {
                                    dialogText = dialogQueue.removeAt(0)
                                    showDialog = true
                                }
                            } else {
                                Toast.makeText(context, "Login OK (popup uitgeschakeld)", Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure { e ->
                            dialogQueue.add("checkuser — fout\n\n${e.message ?: e.toString()}")
                            if (!showDialog) {
                                dialogText = dialogQueue.removeAt(0)
                                showDialog = true
                            }
                        }
                    }
                }
            )

            // Alle server-JSONs downloaden (per bestand pop-up)
            AppPrimaireKnop(
                tekst = if (loadingDownloads) "Downloaden…" else "Serverdata (.json) downloaden",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (loadingDownloads) return@AppPrimaireKnop
                    if (username.isBlank() || password.isBlank()) {
                        Toast.makeText(context, "Vul eerst login en paswoord in.", Toast.LENGTH_LONG).show()
                        return@AppPrimaireKnop
                    }
                    val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
                    if (vt5Dir == null) {
                        Toast.makeText(context, "VT5 map ontbreekt. Kies eerst 'Documents' en maak submappen.", Toast.LENGTH_LONG).show()
                        return@AppPrimaireKnop
                    }
                    val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
                        ?: vt5Dir.createDirectory("serverdata")
                    val binaries = vt5Dir.findFile("binaries")?.takeIf { it.isDirectory }
                        ?: vt5Dir.createDirectory("binaries")

                    loadingDownloads = true
                    scope.launch {
                        val msgs = ServerJsonDownloader.downloadAll(
                            context = context,
                            serverdataDir = serverdata,
                            binariesDir = binaries,
                            username = username.trim(),
                            password = password,
                            language = "dutch",
                            versie = "1845"
                        )
                        loadingDownloads = false
                        dialogQueue.addAll(msgs)
                        if (dialogQueue.isNotEmpty() && !showDialog) {
                            dialogText = dialogQueue.removeAt(0)
                            showDialog = true
                        }
                    }
                }
            )

            // Pré-compute aliassen (assets → binaries) met voortgang-popup
            AppOutlinedKnop(
                tekst = if (loadingAliases) "Aliassen pré-computen…" else "Pré-compute aliassen (assets → binaries)",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (loadingAliases) return@AppOutlinedKnop
                    loadingAliases = true
                    aliasProgressMsg = "Zoeken naar assets/aliasmapping.csv en pré-computen…"
                    scope.launch {
                        val resultMsg = withContext(Dispatchers.Default) {
                            runCatching {
                                val (jsonGz, cborGz) = AliasIndexWriter.ensureComputed(
                                    context = context,
                                    saf = saf,   // <-- zodat hij eerst naar Documents/VT5/assets/... kijkt
                                    q = 3,
                                    minhashK = 64
                                )
                                aliasProgressMsg = "Pré-compute gelukt. Index laden…"
                                val index = AliasIndexWriter.loadIndexFromBinaries(context)
                                val cnt = index?.json?.size ?: 0
                                "Aliassen pré-computed en geladen.\n" +
                                        "- Records: $cnt\n" +
                                        "- JSON.gz: $jsonGz\n" +
                                        "- CBOR.gz: $cborGz"
                            }.getOrElse { e ->
                                "Pré-compute aliassen — fout:\n${e.message ?: e.toString()}"
                            }
                        }
                        loadingAliases = false
                        dialogQueue.add(resultMsg)
                        if (!showDialog) {
                            dialogText = dialogQueue.removeAt(0)
                            showDialog = true
                        }
                    }
                }
            )

            AppPrimaireKnop(
                tekst = "Klaar",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onKlaar() }
            )
        }
    }

    // Pop-up queue (batched resultaten)
    if (showDialog) {
        var disableNext by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { /* via OK */ },
            title = { Text("Resultaat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        dialogText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 0.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = disableNext, onCheckedChange = { disableNext = it })
                        Text("Pop-ups uitzetten (alleen voor login-check)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (disableNext) uiPrefs.setShowAuthPopup(false)
                    if (dialogQueue.isNotEmpty()) {
                        dialogText = dialogQueue.removeAt(0)
                    } else {
                        showDialog = false
                    }
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Blokkerende voortgang-popup tijdens aliasberekening
    if (loadingAliases) {
        AlertDialog(
            onDismissRequest = { /* niet cancelbaar tijdens compute */ },
            title = { Text("Bezig…") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(aliasProgressMsg)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { }
        )
    }
}

/* ---------- helpers ---------- */

private fun makeStatusText(uri: Uri?, foldersOk: Boolean): String =
    when {
        uri == null -> "SAF niet ingesteld. Kies 'Documents' en bevestig 'Deze map gebruiken'."
        foldersOk -> "SAF ingesteld ✔ — alle VT5-submappen aanwezig."
        else -> "SAF ingesteld, maar submappen ontbreken nog. Druk op 'Submappen controleren/aanmaken'."
    }

private class UiPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("vt5_ui_prefs", Context.MODE_PRIVATE)
    fun getShowAuthPopup(): Boolean = p.getBoolean(KEY, true)
    fun setShowAuthPopup(v: Boolean) { p.edit().putBoolean(KEY, v).apply() }
    companion object { private const val KEY = "show_auth_popup" }
}
