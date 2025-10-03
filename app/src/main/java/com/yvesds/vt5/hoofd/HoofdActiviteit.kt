package com.yvesds.vt5.hoofd

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yvesds.vt5.core.app.AppShutdown
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import com.yvesds.vt5.ui.stijl.KnopStijl
import com.yvesds.vt5.ui.stijl.KnopStijlProvider
import com.yvesds.vt5.ui.theme.VT5DonkerThema

/**
 * HoofdActiviteit
 *
 * - ALTIJD donker thema
 * - Globale knop-stijl via KnopStijlProvider
 * - (Her)Installatie toont het installatiepad; downloaden gebeurt daar
 * - Startscherm heeft nu ook "App netjes afsluiten"
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
                            setupOk = computeSetupOk()
                            showInstall = false
                        }
                    )
                }
                else -> {
                    StartScherm(
                        onInstallatie = { showInstall = true },
                        onGaVerder = {
                            // TODO: navigeer naar je echte app-flow
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
    onGaVerder: () -> Unit
) {
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

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

        AppPrimaireKnop(
            tekst = "Ga verder",
            modifier = Modifier.fillMaxWidth(),
            onClick = onGaVerder
        )

        // --- Nieuw: App netjes afsluiten ---
        AppPrimaireKnop(
            tekst = "App netjes afsluiten",
            modifier = Modifier.fillMaxWidth(),
            onClick = { showExitDialog = true }
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("App afsluiten") },
            text = { Text("Wil je VT5 nu netjes afsluiten? Lopende netwerktaken worden gestopt en de app verdwijnt uit 'Recente apps'.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    // 1) interne afsluit-haken (netwerk, executors)
                    AppShutdown.shutdownApp(context)
                    // 2) activity sluiten en uit recents verwijderen
                    (context as? Activity)?.finishAndRemoveTask()
                }) { Text("Afsluiten") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Annuleren") }
            }
        )
    }
}
