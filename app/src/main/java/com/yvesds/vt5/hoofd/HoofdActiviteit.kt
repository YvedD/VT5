@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yvesds.vt5.hoofd

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.yvesds.vt5.features.metadata.ui.MetadataScherm
import com.yvesds.vt5.features.opstart.ui.InstallatieScherm
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.features.serverdata.model.ServerDataRepository
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import com.yvesds.vt5.ui.stijl.KnopStijl
import com.yvesds.vt5.ui.stijl.KnopStijlProvider
import com.yvesds.vt5.ui.theme.VT5DonkerThema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * Belangrijk:
 * - We doen GEEN zwaardere SAF/IO op de main thread.
 * - We pre-warmen JIT + pickers direct bij start.
 * - Naar Metadata navigeren we pas als snapshot klaar is (of tonen we korte loader).
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
    val scope = rememberCoroutineScope()
    val saf = remember { SaFStorageHelper(context) }
    val creds = remember { CredentialsStore(context) }
    val repo = remember { ServerDataRepository(context) }

    // Setup-check (op IO, niet op main)
    var setupOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        setupOk = withContext(Dispatchers.IO) {
            val hasRoot = saf.getRootUri() != null
            val foldersOk = saf.foldersExist()
            val credsOk = creds.hasCredentials()
            hasRoot && foldersOk && credsOk
        }
    }

    // Snapshot + loading state
    var snapshot by remember { mutableStateOf<DataSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }

    // --- WARMP-UP: start meteen in background ---
    LaunchedEffect(Unit) {
        // 1) JIT/serializers warmen én eventueel data alvast laden (IO)
        loading = true
        val warmSnap = withContext(Dispatchers.IO) {
            // zelfs als SAF nog niet ingesteld is: loadAllFromSaf() geeft dan snel een lege snapshot terug
            runCatching { repo.loadAllFromSaf() }.getOrElse { DataSnapshot() }
        }
        snapshot = warmSnap
        loading = false

        // 2) Picker classes “touchen” op main zodat ze straks instant openen
        val nowD = LocalDate.now()
        val nowT = LocalTime.now().withSecond(0).withNano(0)
        DatePickerDialog(context, null, nowD.year, nowD.monthValue - 1, nowD.dayOfMonth)
        TimePickerDialog(context, null, nowT.hour, nowT.minute, true)
    }

    var showInstall by remember { mutableStateOf(false) }
    var showMetadata by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (!showMetadata) {
                TopAppBar(
                    title = { Text(if (showInstall || !setupOk) "VT5 — Installatie" else "VT5 — Hoofdscherm") }
                )
            }
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
                            // her-check setup op IO
                            scope.launch {
                                setupOk = withContext(Dispatchers.IO) {
                                    val hasRoot = saf.getRootUri() != null
                                    val foldersOk = saf.foldersExist()
                                    val credsOk = creds.hasCredentials()
                                    hasRoot && foldersOk && credsOk
                                }
                                // direct na installatie nog eens preloaden
                                loading = true
                                snapshot = withContext(Dispatchers.IO) { repo.loadAllFromSaf() }
                                loading = false
                                showInstall = false
                            }
                        }
                    )
                }
                showMetadata -> {
                    val snap = snapshot
                    if (snap == null || loading) {
                        LoadingFullScreen("Serverdata laden…")
                    } else {
                        // TopAppBar is weg voor meer ruimte
                        MetadataScherm(
                            snapshot = snap,
                            onAnnuleer = { showMetadata = false },
                            onVerder = {
                                Toast.makeText(context, "Header verzameld: ${it.telpostNaam}", Toast.LENGTH_SHORT).show()
                                showMetadata = false
                            }
                        )
                    }
                }
                else -> {
                    StartScherm(
                        onInstallatie = { showInstall = true },
                        onGaVerder = {
                            // Als warmup nog bezig is, korte loader tonen i.p.v. navigeren en stallen
                            if (snapshot == null || loading) {
                                Toast.makeText(context, "Data wordt geladen…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    loading = true
                                    snapshot = withContext(Dispatchers.IO) { repo.loadAllFromSaf() }
                                    loading = false
                                    showMetadata = true
                                }
                            } else {
                                showMetadata = true
                            }
                        },
                        onNetjesAfsluiten = {
                            AppShutdown.shutdownApp(context)
                            (context as? Activity)?.finishAndRemoveTask()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingFullScreen(msg: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(msg, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StartScherm(
    onInstallatie: () -> Unit,
    onGaVerder: () -> Unit,
    onNetjesAfsluiten: () -> Unit
) {
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
                    onNetjesAfsluiten()
                }) { Text("Afsluiten") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Annuleren") }
            }
        )
    }
}
