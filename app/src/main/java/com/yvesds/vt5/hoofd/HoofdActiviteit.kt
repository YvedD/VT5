package com.yvesds.vt5.hoofd

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.features.opstart.usecases.ServerJsonDownloader
import com.yvesds.vt5.ui.componenten.AppOutlinedKnop
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import com.yvesds.vt5.ui.stijl.KnopStijl
import com.yvesds.vt5.ui.stijl.KnopStijlProvider
import com.yvesds.vt5.ui.theme.VT5DonkerThema

/**
 * HoofdActiviteit
 *
 * - ALTIJD donker thema
 * - Globale knop-stijl via KnopStijlProvider
 * - Toont automatisch het InstallatieScherm als de eerste/herinstallatie nog nodig is:
 *      * SAF root URI gezet? (Documents)
 *      * VT5-submappen aanwezig?
 *      * Credentials ingevuld?
 * - Startscherm bevat knoppen voor (Her)Installatie en "Serverdata (.json) downloaden"
 *
 * Let op (voor later bij netwerkaanroepen):
 * - De server van trektellen gebruikt "HTTP Basic Auth" (user:pass → Base64) in de Authorization header:
 *      Authorization: Basic base64(username:password)
 *   Dat implementeren we in de downloader zodra je de exacte endpoints geeft.
 */
class HoofdActiviteit : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VT5DonkerThema {
                KnopStijlProvider(stijl = KnopStijl.LichtblauwOmlijnd) {
                    AppRoot()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val saf = remember { SaFStorageHelper(context) }
    val creds = remember { CredentialsStore(context) }

    // recomputeable status
    fun computeSetupOk(): Boolean {
        val hasRoot = saf.getRootUri() != null
        val foldersOk = saf.foldersExist()
        val credsOk = creds.hasCredentials()
        return hasRoot && foldersOk && credsOk
    }

    var showInstall by remember { mutableStateOf(false) }
    var setupOk by remember { mutableStateOf(computeSetupOk()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showInstall || !setupOk) "VT5 — Installatie" else "VT5 — Hoofdscherm") }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.surface
        ) {
            when {
                showInstall || !setupOk -> {
                    InstallatieScherm(
                        onKlaar = {
                            // Na installatie: status herberekenen en terug naar start
                            setupOk = computeSetupOk()
                            showInstall = false
                        }
                    )
                }
                else -> {
                    StartScherm(
                        onInstallatie = { showInstall = true },
                        onDownloadServerdata = {
                            val vt5Dir: DocumentFile? = saf.getVt5DirIfExists()
                            if (vt5Dir == null) {
                                Toast
                                    .makeText(context, "VT5 map ontbreekt. Voer (her)installatie uit.", Toast.LENGTH_LONG)
                                    .show()
                            } else {
                                val serverdata = vt5Dir.findFile("serverdata")?.takeIf { it.isDirectory }
                                    ?: vt5Dir.createDirectory("serverdata")
                                val ok = ServerJsonDownloader.downloadAll(context, serverdata)
                                val msg = if (ok) "Serverdata gedownload ✔" else "Download mislukt of nog niet geconfigureerd."
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        onGaVerder = {
                            // TODO: navigeer naar je echte app-flow (bijv. StartScherm, TellingScherm, ...)
                            Toast.makeText(context, "Verder naar app-flow…", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StartScherm(
    onInstallatie: () -> Unit,
    onDownloadServerdata: () -> Unit,
    onGaVerder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text("Welkom bij VT5", style = MaterialTheme.typography.titleLarge)

        AppPrimaireKnop(
            tekst = "(Her)Installatie starten",
            modifier = Modifier.fillMaxWidth(),
            onClick = onInstallatie
        )

        AppOutlinedKnop(
            tekst = "Serverdata (.json) downloaden",
            modifier = Modifier.fillMaxWidth(),
            onClick = onDownloadServerdata
        )

        AppPrimaireKnop(
            tekst = "Ga verder",
            modifier = Modifier.fillMaxWidth(),
            onClick = onGaVerder
        )
    }
}
