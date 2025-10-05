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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yvesds.vt5.core.app.AppShutdown
import com.yvesds.vt5.core.opslag.SaFStorageHelper
import com.yvesds.vt5.core.secure.CredentialsStore
import com.yvesds.vt5.features.metadata.model.MetadataHeader
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import com.yvesds.vt5.ui.stijl.KnopStijl
import com.yvesds.vt5.ui.stijl.KnopStijlProvider
import com.yvesds.vt5.ui.theme.VT5DonkerThema
import kotlinx.coroutines.launch

/**
 * HoofdActiviteit
 *
 * - ALTIJD donker thema
 * - Globale knop-stijl via KnopStijlProvider
 * - (Her)Installatie toont het installatiepad; downloaden gebeurt daar
 * - Startscherm heeft "App netjes afsluiten"
 * - Navigeert nu naar MetadataScherm (pure Compose) en laadt vooraf serverdata snapshot
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

private enum class AppScreen { Start, Metadata }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Infra
    val saf = remember { SaFStorageHelper(context) }
    val creds = remember { CredentialsStore(context) }
    val repo = remember { ServerDataRepository(context) }
    val snapshot by repo.snapshot.collectAsState(initial = DataSnapshot())

    fun computeSetupOk(): Boolean {
        val hasRoot = saf.getRootUri() != null
        val foldersOk = saf.foldersExist()
        val credsOk = creds.hasCredentials()
        return hasRoot && foldersOk && credsOk
    }

    var showInstall by remember { mutableStateOf(false) }
    var setupOk by remember { mutableStateOf(computeSetupOk()) }
    var screen by remember { mutableStateOf(AppScreen.Start) }

    // Zodra setup ok is -> serverdata laden (off-main)
    LaunchedEffect(setupOk) {
        if (setupOk) {
            scope.launch {
                repo.loadAllFromSaf()
            }
        }
    }

    val title = when {
        showInstall || !setupOk -> "VT5 — Installatie"
        screen == AppScreen.Metadata -> "VT5 — Metadata"
        else -> "VT5 — Hoofdscherm"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) }
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
                            // Na installatie blijven we op Start; data wordt via LaunchedEffect geladen
                            screen = AppScreen.Start
                        }
                    )
                }
                else -> {
                    when (screen) {
                        AppScreen.Start -> StartScherm(
                            onInstallatie = { showInstall = true },
                            onGaVerder = {
                                // naar Metadata-scherm navigeren
                                screen = AppScreen.Metadata
                            }
                        )

                        AppScreen.Metadata -> MetadataScherm(
                            snapshot = snapshot,
                            onAnnuleer = { screen = AppScreen.Start },
                            onVerder = { header: MetadataHeader ->
                                // TODO: hier counts_save voorbereiden & uploaden
                                // Voor nu even feedback tonen:
                                Toast.makeText(
                                    context,
                                    "Metadata klaar voor upload: ${header.telpostNaam} op ${header.datum} ${header.tijd}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                // eventueel direct door naar volgende flow
                                screen = AppScreen.Start
                            }
                        )
                    }
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
            tekst = "Ga verder (Metadata)",
            modifier = Modifier.fillMaxWidth(),
            onClick = onGaVerder
        )

        // --- App netjes afsluiten ---
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
