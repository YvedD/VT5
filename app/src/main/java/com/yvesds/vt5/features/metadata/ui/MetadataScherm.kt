@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yvesds.vt5.features.metadata.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import java.util.Locale

@Composable
fun MetadataScherm(
    snapshot: DataSnapshot,
    modifier: Modifier = Modifier,
    onAnnuleer: () -> Unit,
    onVerder: (MetadataHeader) -> Unit,
) {
    // Defaults: vandaag / huidig uur
    var datum by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_DATE)) }
    var tijd by remember {
        mutableStateOf(LocalTime.now().withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm")))
    }

    // Telposten
    val siteList: List<Pair<String, String>> = remember(snapshot.sitesById) {
        snapshot.sitesById.values
            .sortedBy { it.telpostnaam.lowercase(Locale.ROOT) }
            .map { it.telpostid to it.telpostnaam }
    }
    var telpostNaam by remember { mutableStateOf(siteList.firstOrNull()?.second ?: "") }
    var telpostId by remember { mutableStateOf(siteList.firstOrNull()?.first ?: "") }

    // Overige velden
    var tellers by remember { mutableStateOf("") }
    var weerOpmerking by remember { mutableStateOf("") }

    val windrichtingOpt: List<String> = remember(snapshot.codesByCategory) { snapshot.optionsFor("windrichting") }
    val windkrachtOpt: List<String>   = remember(snapshot.codesByCategory) { snapshot.optionsFor("windkracht") }
    val bewolkingOpt: List<String>    = remember(snapshot.codesByCategory) { snapshot.optionsFor("bewolking") }
    val neerslagOpt: List<String>     = remember(snapshot.codesByCategory) { snapshot.optionsFor("neerslag") }
    val typeTellingOpt: List<String>  = remember(snapshot.codesByCategory) { snapshot.optionsFor("typetelling") }

    var windrichting by remember { mutableStateOf(windrichtingOpt.firstOrNull() ?: "") }
    var windkracht by remember { mutableStateOf(windkrachtOpt.firstOrNull() ?: "") }
    var bewolking by remember { mutableStateOf(bewolkingOpt.firstOrNull() ?: "") }
    var neerslag by remember { mutableStateOf(neerslagOpt.firstOrNull() ?: "") }
    var typeTelling by remember { mutableStateOf(typeTellingOpt.firstOrNull() ?: "") }

    var temperatuur by remember { mutableStateOf("") }
    var zicht by remember { mutableStateOf("") }
    var luchtdruk by remember { mutableStateOf("") }
    var opmerkingen by remember { mutableStateOf("") }

    val context = LocalContext.current

    fun openDatePicker() {
        val init = runCatching { LocalDate.parse(datum) }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            context,
            { _, y, m, d -> datum = "%04d-%02d-%02d".format(y, m + 1, d) },
            init.year, init.monthValue - 1, init.dayOfMonth
        ).show()
    }

    fun openTimePicker() {
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val init = runCatching { LocalTime.parse(tijd, fmt) }
            .getOrDefault(LocalTime.now().withSecond(0).withNano(0))
        TimePickerDialog(
            context,
            { _, h, min -> tijd = "%02d:%02d".format(h, min) },
            init.hour, init.minute, true
        ).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadonlyPickField(
                        value = datum,
                        onClick = { openDatePicker() },
                        label = "Datum",
                        modifier = Modifier.weight(1f)
                    )
                    ReadonlyPickField(
                        value = tijd,
                        onClick = { openTimePicker() },
                        label = "Tijd",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

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

                LabeledTextField(
                    value = tellers,
                    onValueChange = { tellers = it },
                    label = "Teller(s)",
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

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

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                ExposedDropdownSimple(
                    label = "Type telling",
                    selectedText = typeTelling,
                    options = typeTellingOpt,
                    onOptionClick = { idx -> typeTelling = typeTellingOpt.getOrNull(idx) ?: typeTelling }
                )

                Spacer(Modifier.height(8.dp))

                LabeledTextField(
                    value = opmerkingen,
                    onValueChange = { opmerkingen = it },
                    label = "Opmerkingen",
                    singleLine = false,
                    minLines = 3
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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

/* ============== UI helpers ============== */

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
        @Suppress("DEPRECATION")
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
                    },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }
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
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    )
}

/**
 * Read-only veld met **overlay-click** zodat taps ALTIJD een dialog openen,
 * los van TextField-focus of input-modus.
 */
@Composable
private fun ReadonlyPickField(
    value: String,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { /* read-only */ },
            label = { Text(label) },
            readOnly = true,
            modifier = Modifier
                .fillMaxSize()
        )
        // overlay die de volledige box klikbaar maakt
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onClick() }
        )
    }
}

/* ============== String helpers ============== */

private fun String.filterNumUnsigned(maxLen: Int): String {
    val f = this.filter { it.isDigit() }
    return if (f.length > maxLen) f.take(maxLen) else f
}

private fun String.filterNumSignedDecimal(maxLen: Int): String {
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

/* ============== Snapshot → opties ============== */

private fun DataSnapshot.optionsFor(categoryKey: String): List<String> {
    val raw = codesByCategory[categoryKey] ?: return emptyList()
    return raw.mapNotNull { item ->
        item.value?.takeIf { it.isNotBlank() }
            ?: item.key?.takeIf { it.isNotBlank() }
            ?: item.id?.takeIf { it.isNotBlank() }
            ?: item.tekst?.takeIf { it.isNotBlank() }
    }
}
