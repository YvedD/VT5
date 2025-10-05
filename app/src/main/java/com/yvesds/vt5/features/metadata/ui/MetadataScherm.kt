package com.yvesds.vt5.features.metadata.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yvesds.vt5.features.metadata.model.MetadataHeader
import com.yvesds.vt5.features.serverdata.model.DataSnapshot
import com.yvesds.vt5.ui.componenten.AppOutlinedKnop
import com.yvesds.vt5.ui.componenten.AppPrimaireKnop
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * Compose implementatie van je fragment_metadata_scherm.xml
 * - Scrollbare content in een Card
 * - Compacte datum/tijd-rij met native pickers
 * - Dropdowns voor telpost, windrichting, windkracht, bewolking, neerslag, type telling
 * - Numerieke velden voor temp/zicht/luchtdruk
 * - Bottombar met "Annuleer" & "Verder" knoppen (jouw knoppencomponenten)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScherm(
    snapshot: DataSnapshot,
    modifier: Modifier = Modifier,
    onAnnuleer: () -> Unit,
    onVerder: (MetadataHeader) -> Unit,
) {
    // ---- State ----
    val today = remember { LocalDate.now() }
    val now = remember { LocalTime.now().withSecond(0).withNano(0) }

    var datum by remember { mutableStateOf(today.format(DateTimeFormatter.ISO_DATE)) }             // YYYY-MM-DD
    var tijd by remember { mutableStateOf(now.format(DateTimeFormatter.ofPattern("HH:mm"))) }     // HH:mm

    // Telpost
    val siteList = remember(snapshot.sitesById) {
        snapshot.sitesById.values
            .sortedBy { it.telpostnaam.lowercase(Locale.ROOT) }
            .map { it.telpostid to it.telpostnaam }
    }
    var telpostNaam by remember { mutableStateOf(siteList.firstOrNull()?.second ?: "") }
    var telpostId by remember { mutableStateOf(siteList.firstOrNull()?.first ?: "") }

    // Tellers
    var tellers by remember { mutableStateOf("") }

    // Weer/opmerking
    var weerOpmerking by remember { mutableStateOf("") }

    // Codes (generieke keys; als categorie ontbreekt => lege set)
    val windrichtingOpt = remember(snapshot.codesByCategory) { snapshot.optionsFor("windrichting") }
    val windkrachtOpt   = remember(snapshot.codesByCategory) { snapshot.optionsFor("windkracht") }
    val bewolkingOpt    = remember(snapshot.codesByCategory) { snapshot.optionsFor("bewolking") }
    val neerslagOpt     = remember(snapshot.codesByCategory) { snapshot.optionsFor("neerslag") }
    val typeTellingOpt  = remember(snapshot.codesByCategory) { snapshot.optionsFor("typetelling") }

    var windrichting by remember { mutableStateOf(windrichtingOpt.firstOrNull() ?: "") }
    var windkracht   by remember { mutableStateOf(windkrachtOpt.firstOrNull() ?: "") }
    var bewolking    by remember { mutableStateOf(bewolkingOpt.firstOrNull() ?: "") }
    var neerslag     by remember { mutableStateOf(neerslagOpt.firstOrNull() ?: "") }
    var typeTelling  by remember { mutableStateOf(typeTellingOpt.firstOrNull() ?: "") }

    var temperatuur  by remember { mutableStateOf("") } // °C
    var zicht        by remember { mutableStateOf("") } // meters
    var luchtdruk    by remember { mutableStateOf("") } // hPa

    var opmerkingen  by remember { mutableStateOf("") }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val datePicker = remember {
        val c = Calendar.getInstance()
        DatePickerDialog(ctx, { _, y, m, d ->
            val mm = (m + 1).toString().padStart(2, '0')
            val dd = d.toString().padStart(2, '0')
            datum = "$y-$mm-$dd"
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }
    val timePicker = remember {
        val c = Calendar.getInstance()
        TimePickerDialog(ctx, { _, h, min ->
            val hh = h.toString().padStart(2, '0')
            val mm = min.toString().padStart(2, '0')
            tijd = "$hh:$mm"
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true)
    }

    // ---- UI ----
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 30.dp)
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Titel
                CenteredTitle(text = "Basisgegevens")

                Spacer(Modifier.height(8.dp))

                // Datum & Tijd
                Row(Modifier.fillMaxWidth()) {
                    ReadonlyPickField(
                        value = datum,
                        onClick = { datePicker.show() },
                        label = "Datum",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    ReadonlyPickField(
                        value = tijd,
                        onClick = { timePicker.show() },
                        label = "Tijd",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Telpost dropdown
                ExposedDropdownSimple(
                    label = "Telpost",
                    selectedText = telpostNaam,
                    options = siteList.map { it.second },
                    onOptionClick = { idx ->
                        val (id, naam) = siteList[idx]
                        telpostId = id
                        telpostNaam = naam
                    }
                )

                Spacer(Modifier.height(8.dp))

                // Tellers
                LabeledTextField(
                    value = tellers,
                    onValueChange = { tellers = it },
                    label = "Teller(s)",
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                // Weer header + opmerking
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Weer :",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    LabeledTextField(
                        value = weerOpmerking,
                        onValueChange = { weerOpmerking = it },
                        label = "Opmerking weer",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Weer 2 kolommen
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        ExposedDropdownSimple(
                            label = "Windrichting",
                            selectedText = windrichting,
                            options = windrichtingOpt,
                            onOptionClick = { idx -> windrichting = windrichtingOpt.getOrNull(idx) ?: windrichting }
                        )
                        Spacer(Modifier.height(8.dp))
                        LabeledNumberField(
                            value = temperatuur,
                            onValueChange = { temperatuur = it.filterNumSignedDecimal(maxLen = 6) },
                            label = "Temperatuur (°C)"
                        )
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownSimple(
                            label = "Bewolking (achtsten)",
                            selectedText = bewolking,
                            options = bewolkingOpt,
                            onOptionClick = { idx -> bewolking = bewolkingOpt.getOrNull(idx) ?: bewolking }
                        )
                        Spacer(Modifier.height(8.dp))
                        LabeledNumberField(
                            value = zicht,
                            onValueChange = { zicht = it.filterNumUnsigned(maxLen = 6) },
                            label = "Zicht (m)"
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        ExposedDropdownSimple(
                            label = "Windkracht (Bft)",
                            selectedText = windkracht,
                            options = windkrachtOpt,
                            onOptionClick = { idx -> windkracht = windkrachtOpt.getOrNull(idx) ?: windkracht }
                        )
                        Spacer(Modifier.height(8.dp))
                        ExposedDropdownSimple(
                            label = "Neerslag",
                            selectedText = neerslag,
                            options = neerslagOpt,
                            onOptionClick = { idx -> neerslag = neerslagOpt.getOrNull(idx) ?: neerslag }
                        )
                        Spacer(Modifier.height(8.dp))
                        LabeledNumberField(
                            value = luchtdruk,
                            onValueChange = { luchtdruk = it.filterNumSignedDecimal(maxLen = 6) },
                            label = "Luchtdruk (hPa)"
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Type telling
                ExposedDropdownSimple(
                    label = "Type telling",
                    selectedText = typeTelling,
                    options = typeTellingOpt,
                    onOptionClick = { idx -> typeTelling = typeTellingOpt.getOrNull(idx) ?: typeTelling }
                )

                Spacer(Modifier.height(8.dp))

                // Opmerkingen
                LabeledTextField(
                    value = opmerkingen,
                    onValueChange = { opmerkingen = it },
                    label = "Opmerkingen",
                    singleLine = false,
                    minLines = 3
                )
            }
        }

        // Bottombar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppOutlinedKnop(
                tekst = "Annuleer",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp),
                onClick = onAnnuleer
            )
            Spacer(Modifier.width(8.dp))
            AppPrimaireKnop(
                tekst = "Verder",
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp),
                onClick = {
                    val header = MetadataHeader(
                        datum = datum,
                        tijd = tijd,
                        telpostId = telpostId,
                        telpostNaam = telpostNaam,
                        tellers = tellers,
                        weerOpmerking = weerOpmerking,
                        windrichting = windrichting,
                        windkracht = windkracht,
                        temperatuur = temperatuur,
                        bewolking = bewolking,
                        zicht = zicht,
                        neerslag = neerslag,
                        luchtdruk = luchtdruk,
                        typeTelling = typeTelling,
                        opmerkingen = opmerkingen
                    )
                    onVerder(header)
                }
            )
        }
    }
}

/* =================== Kleine UI helpers =================== */

@Composable
private fun CenteredTitle(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReadonlyPickField(
    value: String,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.clickable { onClick() }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(bottom = 4.dp),
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = { /* none */ },
        )
    }
    LaunchedEffect(value) { /* no-op */ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownSimple(
    label: String,
    selectedText: String,
    options: List<String>,
    onOptionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onOptionClick(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    )
}

@Composable
private fun LabeledNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    )
}

/* =================== String helpers =================== */

private fun String.filterNumUnsigned(maxLen: Int): String {
    val f = this.filter { it.isDigit() }
    return if (f.length > maxLen) f.take(maxLen) else f
}

private fun String.filterNumSignedDecimal(maxLen: Int): String {
    // laat één '-' toe aan het begin, en één '.'
    val b = StringBuilder()
    var hasDot = false
    this.forEachIndexed { idx, c ->
        when {
            c.isDigit() -> b.append(c)
            c == '-' && idx == 0 -> b.append(c)
            c == '.' && !hasDot -> { b.append(c); hasDot = true }
        }
    }
    val out = b.toString()
    return if (out.length > maxLen) out.take(maxLen) else out
}

/* =================== Snapshot → opties =================== */

private fun DataSnapshot.optionsFor(categoryKey: String): List<String> {
    val raw = codesByCategory[categoryKey] ?: return emptyList()
    // Kies de eerste niet-lege representatie per item:
    return raw.mapNotNull { item ->
        item.value?.takeIf { it.isNotBlank() }
            ?: item.key?.takeIf { it.isNotBlank() }
            ?: item.id?.takeIf { it.isNotBlank() }
            ?: item.tekst?.takeIf { it.isNotBlank() }
    }
}
