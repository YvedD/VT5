# VT5 Metadata en Annotatie Velden Audit

## Overzicht

Dit document bevat een **volledige audit** van alle velden in:
1. **MetadataScherm.kt** → Hoe ze gemapped worden naar **ServerTellingEnvelope**
2. **AnnotatieScherm.kt** → Hoe ze gemapped worden naar **ServerTellingDataItem**

## 📋 Deel 1: MetadataScherm.kt → ServerTellingEnvelope

### UI Velden in MetadataScherm (scherm_metadata.xml)

| UI Element ID | Label/Hint | Input Type | Veld Naam |
|---------------|------------|------------|-----------|
| `etDatum` | Datum | Date picker | Datum |
| `etTijd` | Tijd | Time picker | Tijd |
| `acTelpost` | Telpost | Dropdown | Telpost ID |
| `etTellers` | Tellers | Text | Tellers (namen) |
| `etWeerOpmerking` | Weer opmerking | Text | Weer opmerking |
| `acWindrichting` | Windrichting | Dropdown | Windrichting |
| `acWindkracht` | Windkracht | Dropdown | Windkracht (Beaufort) |
| `etTemperatuur` | Temperatuur | Number | Temperatuur (°C) |
| `acBewolking` | Bewolking | Dropdown | Bewolking (achtsten) |
| `acNeerslag` | Neerslag | Dropdown | Neerslag type |
| `etZicht` | Zicht | Number | Zicht (meters) |
| `etLuchtdruk` | Luchtdruk | Number | Luchtdruk (hPa) |
| `acTypeTelling` | Type telling | Dropdown | Type telling |
| `etOpmerkingen` | Opmerkingen | Text (multiline) | Opmerkingen |

### Mapping in StartTellingApi.buildEnvelopeFromUi()

```kotlin
// TellingStarter.kt - regel 72-106
val begintijdEpoch = formManager.computeBeginEpochSec()  // ✅ Datum + Tijd
val eindtijdEpoch = 0L                                    // ✅ Live mode (leeg)

val windrichtingForServer = formManager.gekozenWindrichtingCode  // ✅ Windrichting
val windkrachtBft = formManager.gekozenWindkracht                // ✅ Windkracht
val temperatuurC = binding.etTemperatuur.text.toString()         // ✅ Temperatuur
val bewolkingAchtsten = formManager.gekozenBewolking            // ✅ Bewolking
val neerslagCode = formManager.gekozenNeerslagCode              // ✅ Neerslag
val zichtMeters = binding.etZicht.text.toString()               // ✅ Zicht
val typetellingCode = formManager.gekozenTypeTellingCode        // ✅ Type telling
val tellersFromUi = ""                                           // ❌ LEEG!
val weerOpmerking = binding.etWeerOpmerking.text.toString()     // ✅ Weer opmerking
val opmerkingen = ""                                             // ❌ LEEG!
val luchtdrukHpaRaw = binding.etLuchtdruk.text.toString()       // ✅ Luchtdruk
```

### ServerTellingEnvelope Velden (Types.kt)

```kotlin
data class ServerTellingEnvelope(
    // Metadata velden
    @SerialName("externid") val externid: String,           // ✅ "Android App 1.8.45"
    @SerialName("timezoneid") val timezoneid: String,       // ✅ "Europe/Brussels"
    @SerialName("bron") val bron: String,                   // ✅ "4"
    @SerialName("_id") val idLocal: String,                 // ✅ "" (leeg)
    @SerialName("tellingid") val tellingid: String,         // ✅ Generated ID
    @SerialName("telpostid") val telpostid: String,         // ✅ Van dropdown
    @SerialName("onlineid") val onlineid: String,           // ✅ Van server response
    
    // Tijd velden
    @SerialName("begintijd") val begintijd: String,         // ✅ Datum + Tijd → epoch
    @SerialName("eindtijd") val eindtijd: String,           // ✅ "" (live) / epoch (afronden)
    @SerialName("uploadtijdstip") val uploadtijdstip: String, // ✅ Current timestamp
    
    // Weer metadata
    @SerialName("tellers") val tellers: String,             // ❌ ALTIJD "" !
    @SerialName("weer") val weer: String,                   // ✅ Weer opmerking
    @SerialName("windrichting") val windrichting: String,   // ✅ Windrichting code
    @SerialName("windkracht") val windkracht: String,       // ✅ Beaufort 0-12
    @SerialName("temperatuur") val temperatuur: String,     // ✅ Celsius
    @SerialName("bewolking") val bewolking: String,         // ✅ Achtsten 0-8
    @SerialName("bewolkinghoogte") val bewolkinghoogte: String, // ❌ Altijd ""
    @SerialName("neerslag") val neerslag: String,           // ✅ Neerslag code
    @SerialName("duurneerslag") val duurneerslag: String,   // ❌ Altijd ""
    @SerialName("zicht") val zicht: String,                 // ✅ Meters
    @SerialName("hpa") val hpa: String,                     // ✅ Luchtdruk
    
    // Telling metadata
    @SerialName("tellersactief") val tellersactief: String,     // ❌ Altijd ""
    @SerialName("tellersaanwezig") val tellersaanwezig: String, // ❌ Altijd ""
    @SerialName("typetelling") val typetelling: String,         // ✅ Type telling code
    @SerialName("metersnet") val metersnet: String,             // ❌ Altijd ""
    @SerialName("geluid") val geluid: String,                   // ❌ Altijd ""
    @SerialName("opmerkingen") val opmerkingen: String,         // ❌ ALTIJD "" !
    @SerialName("equipment") val equipment: String,             // ❌ Altijd ""
    @SerialName("HYDRO") val hydro: String,                     // ❌ Altijd ""
    @SerialName("uuid") val uuid: String,                       // ✅ Generated UUID
    
    // Statistics
    @SerialName("nrec") val nrec: String,                   // ✅ Berekend bij afronden
    @SerialName("nsoort") val nsoort: String,               // ✅ Berekend bij afronden
    
    // Data array
    @SerialName("data") val data: List<ServerTellingDataItem>  // ✅ Verzameld tijdens telling
)
```

### ⚠️ GEVONDEN PROBLEMEN - MetadataScherm

| Veld | Status | Probleem | Oplossing |
|------|--------|----------|-----------|
| **tellers** | ❌ FOUT | Altijd leeg string, maar `etTellers` heeft wel waarde | Moet `binding.etTellers.text.toString()` gebruiken |
| **opmerkingen** | ❌ FOUT | Altijd leeg string, maar `etOpmerkingen` heeft wel waarde | Moet `binding.etOpmerkingen.text.toString()` gebruiken |
| bewolkinghoogte | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| duurneerslag | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| tellersactief | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| tellersaanwezig | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| metersnet | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| geluid | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| equipment | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |
| hydro | ⚠️ Ontbreekt | Geen UI veld, altijd "" | Acceptabel (optioneel veld) |

---

## 📊 Deel 2: AnnotatieScherm.kt → ServerTellingDataItem

### UI Velden in AnnotatieScherm (activity_annotatie.xml)

| UI Element | Label/Hint | Type | Veld Naam |
|------------|------------|------|-----------|
| **Column 1: Leeftijd** | | | |
| `btn_leeftijd_1..8` | Dynamisch uit annotations.json | ToggleButton Group | leeftijd |
| **Column 2: Geslacht** | | | |
| `btn_geslacht_1..4` | Dynamisch uit annotations.json | ToggleButton Group | geslacht |
| **Column 3: Kleed** | | | |
| `btn_kleed_1..8` | Dynamisch uit annotations.json | ToggleButton Group | kleed |
| **Column 4: Location** | | | |
| `btn_location_1..6` | Dynamisch uit annotations.json | ToggleButton Group | location |
| **Column 5: Height** | | | |
| `btn_height_1..8` | Dynamisch uit annotations.json | ToggleButton Group | height |
| **Checkboxes** | | | |
| `cb_zw` | ZW (Southwest direction) | Checkbox | ZW → richting |
| `cb_no` | NO (Northeast direction) | Checkbox | NO → richtingterug |
| `cb_lokaal` | Lokaal | Checkbox | lokaal_plus |
| `cb_markeren` | Markeren | Checkbox | markeren |
| `cb_markeren_lokaal` | Markeren Lokaal | Checkbox | markerenlokaal |
| **Manual Counts** | | | |
| `et_aantal_zw` | Aantal ZW | Number input | aantal |
| `et_aantal_no` | Aantal NO | Number input | aantalterug |
| `et_aantal_lokaal` | Aantal Lokaal | Number input | lokaal |
| **Remarks** | | | |
| `et_opmerkingen` | Opmerkingen | Text (multiline) | opmerkingen |

### Mapping in AnnotatieScherm.kt → JSON

```kotlin
// AnnotatieScherm.kt - regel 98-152
val resultMap = mutableMapOf<String, String?>()

// Toggle groups → waarde uit annotations.json
for ((group, btns) in groupButtons) {
    val selectedOpt = btns.firstOrNull { it.isChecked }?.tag as? AnnotationOption
    if (selectedOpt != null) {
        val storeKey = if (selectedOpt.veld.isNotBlank()) selectedOpt.veld else group
        resultMap[storeKey] = selectedOpt.waarde  // ✅ Correct
    }
}

// Checkboxes
cb_zw → resultMap["ZW"] = "1"                 // ✅ Correct
cb_no → resultMap["NO"] = "1"                 // ✅ Correct
cb_lokaal → resultMap["lokaal_plus"] = "1"    // ✅ Correct
cb_markeren → resultMap["markeren"] = "1"     // ✅ Correct
cb_markeren_lokaal → resultMap["markerenlokaal"] = "1"  // ✅ Correct

// Manual count inputs
et_aantal_zw → resultMap["aantal"] = value    // ✅ Correct
et_aantal_no → resultMap["aantalterug"] = value  // ✅ Correct
et_aantal_lokaal → resultMap["lokaal"] = value   // ✅ Correct

// Remarks
et_opmerkingen → resultMap["opmerkingen"] = value  // ✅ Correct

// Result:
EXTRA_ANNOTATIONS_JSON = JSON.stringify(resultMap)  // ✅ Correct
```

### Mapping in TellingAnnotationHandler.applyAnnotationsToPendingRecord()

```kotlin
// TellingAnnotationHandler.kt - regel 170-224
val map: Map<String, String?> = decodeFromJson(annotationsJson)

// Direct mappings
val newLeeftijd = map["leeftijd"] ?: old.leeftijd        // ✅ Correct
val newGeslacht = map["geslacht"] ?: old.geslacht        // ✅ Correct
val newKleed = map["kleed"] ?: old.kleed                 // ✅ Correct
val newLocation = map["location"] ?: old.location        // ✅ Correct
val newHeight = map["height"] ?: old.height              // ✅ Correct
val newLokaal = map["lokaal"] ?: old.lokaal              // ✅ Correct
val newLokaalPlus = map["lokaal_plus"] ?: old.lokaal_plus  // ✅ Correct
val newMarkeren = map["markeren"] ?: old.markeren        // ✅ Correct
val newMarkerenLokaal = map["markerenlokaal"] ?: old.markerenlokaal  // ✅ Correct
val newAantal = map["aantal"] ?: old.aantal              // ✅ Correct
val newAantalterug = map["aantalterug"] ?: old.aantalterug  // ✅ Correct
val newOpmerkingen = map["opmerkingen"] ?: map["remarks"] ?: old.opmerkingen  // ✅ Correct

// Direction mapping
if (map["ZW"] == "1") {
    newRichting = "w"  // ✅ Correct (west/southwest)
}
if (map["NO"] == "1") {
    newRichtingterug = "o"  // ✅ Correct (east/northeast)
}

// Calculate total
val newTotaalaantal = (aantal + aantalterug + lokaal).toString()  // ✅ Correct

// Timestamp
val newUploadtijdstip = getCurrentTimestamp()  // ✅ Correct

// Apply all to ServerTellingDataItem
val updated = old.copy(
    leeftijd = newLeeftijd,
    geslacht = newGeslacht,
    kleed = newKleed,
    location = newLocation,
    height = newHeight,
    lokaal = newLokaal,
    lokaal_plus = newLokaalPlus,
    markeren = newMarkeren,
    markerenlokaal = newMarkerenLokaal,
    aantal = newAantal,
    aantalterug = newAantalterug,
    richting = newRichting,
    richtingterug = newRichtingterug,
    opmerkingen = newOpmerkingen,
    totaalaantal = newTotaalaantal,
    uploadtijdstip = newUploadtijdstip
)  // ✅ ALLE velden correct gemapped!
```

### ServerTellingDataItem Velden (Types.kt)

```kotlin
data class ServerTellingDataItem(
    @SerialName("_id") val idLocal: String = "",            // ✅ Generated incrementeel
    @SerialName("tellingid") val tellingid: String = "",    // ✅ Van telling session
    @SerialName("soortid") val soortid: String = "",        // ✅ Species ID
    @SerialName("aantal") val aantal: String = "",          // ✅ Count ZW / manual input
    @SerialName("richting") val richting: String = "",      // ✅ "w" als ZW checked
    @SerialName("aantalterug") val aantalterug: String = "", // ✅ Count NO / manual input
    @SerialName("richtingterug") val richtingterug: String = "", // ✅ "o" als NO checked
    @SerialName("sightingdirection") val sightingdirection: String = "", // ⚠️ Niet gebruikt
    @SerialName("lokaal") val lokaal: String = "",          // ✅ Lokaal count / manual input
    @SerialName("aantal_plus") val aantal_plus: String = "", // ⚠️ Niet gebruikt
    @SerialName("aantalterug_plus") val aantalterug_plus: String = "", // ⚠️ Niet gebruikt
    @SerialName("lokaal_plus") val lokaal_plus: String = "", // ✅ "1" als cb_lokaal checked
    @SerialName("markeren") val markeren: String = "",      // ✅ "1" als cb_markeren checked
    @SerialName("markerenlokaal") val markerenlokaal: String = "", // ✅ "1" als cb_markeren_lokaal checked
    @SerialName("geslacht") val geslacht: String = "",      // ✅ Van toggle group
    @SerialName("leeftijd") val leeftijd: String = "",      // ✅ Van toggle group
    @SerialName("kleed") val kleed: String = "",            // ✅ Van toggle group
    @SerialName("opmerkingen") val opmerkingen: String = "", // ✅ Van et_opmerkingen
    @SerialName("trektype") val trektype: String = "",      // ⚠️ Niet gebruikt
    @SerialName("teltype") val teltype: String = "",        // ⚠️ Niet gebruikt
    @SerialName("location") val location: String = "",      // ✅ Van toggle group
    @SerialName("height") val height: String = "",          // ✅ Van toggle group
    @SerialName("tijdstip") val tijdstip: String = "",      // ✅ Epoch seconds (creation)
    @SerialName("groupid") val groupid: String = "",        // ✅ Same as _id
    @SerialName("uploadtijdstip") val uploadtijdstip: String = "", // ✅ "YYYY-MM-DD HH:MM:SS"
    @SerialName("totaalaantal") val totaalaantal: String = "" // ✅ aantal + aantalterug + lokaal
)
```

### ✅ GEVONDEN RESULTAAT - AnnotatieScherm

**ALLE velden worden correct gemapped!**

| Veld | Status | Mapping |
|------|--------|---------|
| leeftijd | ✅ OK | Van toggle group → annotations.json → ServerTellingDataItem |
| geslacht | ✅ OK | Van toggle group → annotations.json → ServerTellingDataItem |
| kleed | ✅ OK | Van toggle group → annotations.json → ServerTellingDataItem |
| location | ✅ OK | Van toggle group → annotations.json → ServerTellingDataItem |
| height | ✅ OK | Van toggle group → annotations.json → ServerTellingDataItem |
| aantal | ✅ OK | Van et_aantal_zw → annotations.json → ServerTellingDataItem |
| aantalterug | ✅ OK | Van et_aantal_no → annotations.json → ServerTellingDataItem |
| lokaal | ✅ OK | Van et_aantal_lokaal → annotations.json → ServerTellingDataItem |
| richting | ✅ OK | "w" als cb_zw checked |
| richtingterug | ✅ OK | "o" als cb_no checked |
| lokaal_plus | ✅ OK | "1" als cb_lokaal checked |
| markeren | ✅ OK | "1" als cb_markeren checked |
| markerenlokaal | ✅ OK | "1" als cb_markeren_lokaal checked |
| opmerkingen | ✅ OK | Van et_opmerkingen → annotations.json → ServerTellingDataItem |
| totaalaantal | ✅ OK | Berekend: aantal + aantalterug + lokaal |
| uploadtijdstip | ✅ OK | Current timestamp bij annotatie toepassen |

**Niet-gebruikte velden** (acceptabel):
- `sightingdirection` - Niet in UI
- `aantal_plus` - Niet in UI  
- `aantalterug_plus` - Niet in UI
- `trektype` - Niet in UI
- `teltype` - Niet in UI

---

## 🔧 Te Repareren Issues

### Issue #1: Tellers Veld Leeg in Envelope

**Locatie**: `TellingStarter.kt` regel 84

**Probleem**:
```kotlin
val tellersFromUi = ""  // ❌ Hardcoded leeg!
```

**Moet worden**:
```kotlin
val tellersFromUi = binding.etTellers.text?.toString()?.trim().orEmpty()
```

**Impact**: Gebruiker vult tellers namen in, maar deze komen niet in de envelope terecht.

---

### Issue #2: Opmerkingen Veld Leeg in Envelope

**Locatie**: `TellingStarter.kt` regel 86

**Probleem**:
```kotlin
val opmerkingen = ""  // ❌ Hardcoded leeg!
```

**Moet worden**:
```kotlin
val opmerkingen = binding.etOpmerkingen.text?.toString()?.trim().orEmpty()
```

**Impact**: Gebruiker vult opmerkingen in, maar deze komen niet in de envelope terecht.

---

## 📝 Conclusie

### ✅ Wat Werkt Goed

1. **AnnotatieScherm.kt** → Alle velden worden **perfect** gemapped naar `ServerTellingDataItem`
2. **MetadataScherm.kt** → De meeste velden worden correct gemapped
3. **Weer velden** → Alle weer-gerelateerde velden werken correct
4. **Datum/Tijd** → Correct geconverteerd naar epoch seconds

### ❌ Wat Gerepareerd Moet Worden

1. **Tellers veld** - Moet uit `etTellers` UI veld komen
2. **Opmerkingen veld** - Moet uit `etOpmerkingen` UI veld komen

### ⚠️ Optionele Verbeteringen

De volgende velden hebben geen UI elementen maar zouden eventueel toegevoegd kunnen worden:
- `bewolkinghoogte` - Bewolkingshoogte
- `duurneerslag` - Duur van neerslag
- `tellersactief` - Aantal actieve tellers
- `tellersaanwezig` - Aantal aanwezige tellers
- `metersnet` - Meters net (?)
- `geluid` - Geluid niveau
- `equipment` - Gebruikte equipment
- `hydro` - Hydrologische omstandigheden

Deze zijn **niet kritiek** omdat de server ze waarschijnlijk als optioneel behandelt.

---

## 🔍 Code Locaties voor Fixes

| File | Regel | Actie |
|------|-------|-------|
| `TellingStarter.kt` | 84 | Fix: `tellersFromUi` moet uit `binding.etTellers` komen |
| `TellingStarter.kt` | 86 | Fix: `opmerkingen` moet uit `binding.etOpmerkingen` komen |

**Let op**: `TellingStarter` heeft geen directe toegang tot `binding`. Dit moet via `formManager` of door parameters door te geven aan de helper.

---

## 🎯 Oplossingsplan

### Optie A: Via MetadataFormManager (Aanbevolen)

Voeg getters toe aan `MetadataFormManager`:
```kotlin
fun getTellers(): String = binding.etTellers.text?.toString()?.trim().orEmpty()
fun getOpmerkingen(): String = binding.etOpmerkingen.text?.toString()?.trim().orEmpty()
```

Dan in `TellingStarter.startTelling()`:
```kotlin
val tellersFromUi = formManager.getTellers()
val opmerkingen = formManager.getOpmerkingen()
```

### Optie B: Direct via Parameters

Pas `TellingStarter.startTelling()` signature aan:
```kotlin
suspend fun startTelling(
    telpostId: String,
    username: String,
    password: String,
    snapshot: DataSnapshot,
    tellers: String,      // ← Nieuw
    opmerkingen: String   // ← Nieuw
): StartResult
```

Dan in `MetadataScherm.startTellingAndOpenSoortSelectie()`:
```kotlin
val tellers = binding.etTellers.text?.toString()?.trim().orEmpty()
val opmerkingen = binding.etOpmerkingen.text?.toString()?.trim().orEmpty()

tellingStarter.startTelling(
    telpostId, username, password, fullSnapshot,
    tellers, opmerkingen  // ← Nieuw
)
```

**Ik raad Optie A aan** omdat het consistent is met hoe andere form velden al worden verwerkt via `formManager`.

---

## ✅ Verificatie Checklist

Na de fix moet worden getest:

- [ ] Vul "Tellers" veld in MetadataScherm
- [ ] Vul "Opmerkingen" veld in MetadataScherm
- [ ] Start een telling
- [ ] Maak waarnemingen
- [ ] Rond af
- [ ] Controleer envelope JSON backup file
- [ ] Verificeer dat `tellers` veld de juiste waarde heeft
- [ ] Verificeer dat `opmerkingen` veld de juiste waarde heeft
- [ ] Test met lege velden (moet ook werken)
- [ ] Test met speciale karakters (,;:'"etc)

---

**Datum Audit**: 2025-11-22
**Status**: Analyse compleet, ready voor implementatie
