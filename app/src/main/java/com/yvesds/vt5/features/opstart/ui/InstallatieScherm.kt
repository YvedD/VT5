package com.yvesds.vt5.features.opstart.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.ui.componenten.AppOutlinedKnop
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop

/**
 * Eerste/herinstallatie scherm.
 *
 * - Opt-out mappen-aanmaak: we checken eerst; enkel aanmaken als ze nog ontbreken.
 * - Credentials zijn altijd aanpasbaar/overschrijfbaar.
 * - Extra actie: server-JSON downloaden (stub tot we endpoints invullen).
 * - Paswoordveld heeft “oogje” om zichtbaar/onzichtbaar te schakelen.
 */
@Composable
fun InstallatieScherm(
    onKlaar: () -> Unit
) {
    val context = LocalContext.current
    val saf = remember { SaFStorageHelper(context) }
    val creds = remember { CredentialsStore(context) }

    var gekozenUri by remember { mutableStateOf<Uri?>(saf.getRootUri()) }
    var foldersOk by remember { mutableStateOf(saf.foldersExist()) }
    var status by remember { mutableStateOf(makeStatusText(gekozenUri, foldersOk)) }

    var username by remember { mutableStateOf(creds.getUsername().orEmpty()) }
    var password by remember { mutableStateOf(creds.getPassword().orEmpty()) }
    var credsOk by remember { mutableStateOf(creds.hasCredentials()) }

    // toggle voor wachtwoord-oogje
    var showPassword by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            saf.takePersistablePermission(uri)
            saf.saveRootUri(uri)
            gekozenUri = uri
            // Opt-out: alleen aanmaken als het nog NIET bestaat
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

            // Read-only multi-line uitleg
            OutlinedTextField(
                value = uitlegTekst,
                onValueChange = { /* read-only */ },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                label = { Text("Belangrijke uitleg") },
                supportingText = { Text("Bevestig het gebruik van de map 'Documents' in de volgende stap.") }
            )

            // 1) SAF kiezen
            AppPrimaireKnop(
                tekst = "Map 'Documents' kiezen",
                modifier = Modifier.fillMaxWidth(),
                onClick = { treeLauncher.launch(null) }
            )

            // 2) Submappen controleren/aanmaken (opt-out)
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

            // Status
            Text(
                text = status,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
            )

            Spacer(Modifier.height(8.dp))

            // 3) Credentials (altijd aanpasbaar)
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
                            Icon(
                                imageVector = Icons.Filled.VisibilityOff,
                                contentDescription = "Verberg wachtwoord"
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = "Toon wachtwoord"
                            )
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
                        creds.clear()
                        username = ""
                        password = ""
                        credsOk = false
                        Toast.makeText(context, "Credentials gewist", Toast.LENGTH_SHORT).show()
                    }
                )
                AppPrimaireKnop(
                    tekst = "Bewaar",
                    onClick = {
                        creds.save(username.trim(), password)
                        credsOk = creds.hasCredentials()
                        Toast.makeText(context, "Credentials opgeslagen", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 4) Serverdata downloaden (.json) — stub
            AppPrimaireKnop(
                tekst = "Serverdata (.json) downloaden",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
                    if (vt5Dir == null) {
                        Toast.makeText(context, "VT5 map ontbreekt. Kies eerst 'Documents' en maak submappen.", Toast.LENGTH_LONG).show()
                    } else {
                        val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
                            ?: vt5Dir.createDirectory("serverdata")
                        val ok = ServerJsonDownloader.downloadAll(context, serverdata)
                        val msg = if (ok) "Serverdata gedownload ✔" else "Download mislukt of nog niet geconfigureerd."
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            // Klaar
            AppPrimaireKnop(
                tekst = "Klaar",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onKlaar()
                }
            )
        }
    }
}

private fun makeStatusText(uri: Uri?, foldersOk: Boolean): String {
    return when {
        uri == null -> "SAF niet ingesteld. Kies 'Documents' en bevestig 'Deze map gebruiken'."
        foldersOk -> "SAF ingesteld ✔ — alle VT5-submappen aanwezig."
        else -> "SAF ingesteld, maar submappen ontbreken nog. Druk op 'Submappen controleren/aanmaken'."
    }
}
