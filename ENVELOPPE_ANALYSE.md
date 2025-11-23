# VT5 Enveloppe Creatie - Volledige Analyse

## Samenvatting

De 'enveloppe' voor het doorsturen van waarnemingen naar de trektellen.nl server wordt in **drie belangrijke fases** gemaakt:

1. **Metadata Header Creatie** - In `MetadataScherm.kt` via `TellingStarter` helper
2. **Data Record Verzameling** - In `TellingScherm.kt` via `TellingSpeciesManager`
3. **Finale Enveloppe Assembly** - In `TellingAfrondHandler.kt` voor upload

---

## 📋 Fase 1: Metadata Header Creatie

### Locatie
- **File**: `app/src/main/java/com/yvesds/vt5/features/metadata/ui/MetadataScherm.kt`
- **Helper Class**: `app/src/main/java/com/yvesds/vt5/features/metadata/helpers/TellingStarter.kt`
- **API Builder**: `app/src/main/java/com/yvesds/vt5/net/StartTellingApi.kt`

### Proces

#### Stap 1: Gebruiker vult metadata formulier in
```kotlin
// MetadataScherm.kt - regel 273-291
private fun onVerderClicked() {
    val telpostId = formManager.gekozenTelpostId
    // Validatie...
    startTellingAndOpenSoortSelectie(telpostId, username, password)
}
```

#### Stap 2: TellingStarter bouwt de envelope
```kotlin
// TellingStarter.kt - regel 56-166
suspend fun startTelling(
    telpostId: String,
    username: String,
    password: String,
    snapshot: DataSnapshot
): StartResult {
    // 1. Genereer telling ID
    val tellingIdLong = VT5App.nextTellingId().toLong()
    
    // 2. Verzamel form data
    val begintijdEpoch = formManager.computeBeginEpochSec()
    val windrichtingForServer = formManager.gekozenWindrichtingCode
    val temperatuurC = binding.etTemperatuur.text.toString()
    // ... etc
    
    // 3. Bouw envelope via StartTellingApi
    val envelope = StartTellingApi.buildEnvelopeFromUi(
        tellingId = tellingIdLong,
        telpostId = telpostId,
        begintijdEpochSec = begintijdEpoch,
        eindtijdEpochSec = 0L,  // Live mode: leeg
        // ... alle weather/metadata velden
        liveMode = true
    )
    
    // 4. Post naar server
    val (ok, resp) = TrektellenApi.postCountsSave(
        baseUrl, language, versie, username, password, envelope
    )
    
    // 5. Parse onlineId en sla op
    val onlineId = parseOnlineIdFromResponse(resp)
    prefs.edit {
        putString(PREF_ONLINE_ID, onlineId)
        putString(PREF_TELLING_ID, tellingIdLong.toString())
        putString(PREF_SAVED_ENVELOPE_JSON, envelopeJson)
    }
}
```

#### Stap 3: StartTellingApi maakt de ServerTellingEnvelope
```kotlin
// StartTellingApi.kt - regel 16-84
fun buildEnvelopeFromUi(...): List<ServerTellingEnvelope> {
    val env = ServerTellingEnvelope(
        externid = "Android App 1.8.45",
        timezoneid = "Europe/Brussels",
        bron = "4",
        idLocal = "",
        tellingid = tellingId.toString(),
        telpostid = telpostId,
        begintijd = begintijdEpochSec.toString(),
        eindtijd = if (liveMode) "" else eindtijdEpochSec.toString(),
        tellers = telers ?: "",
        weer = weerOpmerking ?: "",
        windrichting = windrichtingLabel ?: "",
        windkracht = windkracht,
        temperatuur = temperatuur,
        bewolking = bewolking,
        // ... alle metadata velden
        nrec = "0",      // Nog geen records
        nsoort = "0",    // Nog geen soorten
        data = emptyList()  // Nog geen data
    )
    return listOf(env)
}
```

### Resultaat Fase 1
- Envelope met **metadata header** gemaakt
- **Opgeslagen in SharedPreferences** als JSON (`PREF_SAVED_ENVELOPE_JSON`)
- **Online ID** ontvangen van server en opgeslagen
- **Telling ID** gegenereerd en opgeslagen
- Data lijst is nog **leeg** (`data = emptyList()`)

---

## 📊 Fase 2: Data Record Verzameling

### Locatie
- **File**: `app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt`
- **Helper Class**: `app/src/main/java/com/yvesds/vt5/features/telling/TellingSpeciesManager.kt`

### Proces

#### Stap 1: Gebruiker telt vogels via spraak of UI
```kotlin
// TellingScherm.kt - regel 610-617
private fun recordSpeciesCount(speciesId: String, displayName: String, count: Int) {
    addFinalLog("$displayName -> +$count")
    lifecycleScope.launch {
        speciesManager.updateSoortCountInternal(speciesId, count)
        speciesManager.collectFinalAsRecord(speciesId, count)  // ← Hier!
    }
    RecentSpeciesStore.recordUse(this, speciesId, maxEntries = 30)
}
```

#### Stap 2: TellingSpeciesManager maakt ServerTellingDataItem
```kotlin
// TellingSpeciesManager.kt - regel 177-235
suspend fun collectFinalAsRecord(soortId: String, amount: Int) {
    withContext(Dispatchers.IO) {
        val tellingId = prefs.getString(PREF_TELLING_ID, null)
        
        // Genereer unieke record ID
        val idLocal = DataUploader.getAndIncrementRecordId(activity, tellingId)
        val nowEpoch = (System.currentTimeMillis() / 1000L).toString()
        
        // Maak data item
        val item = ServerTellingDataItem(
            idLocal = idLocal,
            tellingid = tellingId,
            soortid = soortId,
            aantal = amount.toString(),
            richting = "",
            aantalterug = "0",
            // ... alle velden
            tijdstip = nowEpoch,
            groupid = idLocal,
            uploadtijdstip = "",
            totaalaantal = amount.toString()
        )
        
        // Voeg toe aan buffer
        onRecordCollected?.invoke(item)  // ← Callback naar TellingScherm
        
        // Schrijf backup
        backupManager.writeRecordBackupSaf(tellingId, item)
        
        Toast.makeText(activity, "Waarneming opgeslagen (buffer)", LENGTH_SHORT).show()
    }
}
```

#### Stap 3: TellingScherm verzamelt records in buffer
```kotlin
// TellingScherm.kt - regel 143, 344-346
private val pendingRecords = mutableListOf<ServerTellingDataItem>()

// Callback van speciesManager
speciesManager.onRecordCollected = { item ->
    synchronized(pendingRecords) { pendingRecords.add(item) }
    if (::viewModel.isInitialized) viewModel.addPendingRecord(item)
}
```

### Resultaat Fase 2
- Voor **elke waarneming** wordt een `ServerTellingDataItem` gemaakt
- Alle items worden **verzameld in `pendingRecords` lijst**
- Elke item heeft **unieke `_id`** (incrementeel per telling)
- **Backup geschreven** naar SAF storage voor veiligheid
- **Metadata header blijft bewaard** in SharedPreferences

---

## 🚀 Fase 3: Finale Enveloppe Assembly & Upload

### Locatie
- **File**: `app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt` (regel 758-798)
- **Handler Class**: `app/src/main/java/com/yvesds/vt5/features/telling/TellingAfrondHandler.kt`
- **API Class**: `app/src/main/java/com/yvesds/vt5/net/TrektellenApi.kt`

### Proces

#### Stap 1: Gebruiker klikt op "Afronden"
```kotlin
// TellingScherm.kt - regel 466-484
private fun handleAfrondenWithConfirmation() {
    AlertDialog.Builder(this)
        .setTitle("Weet je zeker dat je wilt afronden?")
        .setPositiveButton("Ja") { _, _ ->
            lifecycleScope.launch {
                handleAfronden()  // ← Hier begint het proces
            }
        }
}

// TellingScherm.kt - regel 758-798
private suspend fun handleAfronden() {
    val result = afrondHandler.handleAfronden(
        pendingRecords = synchronized(pendingRecords) { ArrayList(pendingRecords) },
        pendingBackupDocs = pendingBackupDocs,
        pendingBackupInternalPaths = pendingBackupInternalPaths
    )
    // Handle success/failure...
}
```

#### Stap 2: TellingAfrondHandler bouwt finale envelope
```kotlin
// TellingAfrondHandler.kt - regel 67-126
suspend fun handleAfronden(
    pendingRecords: List<ServerTellingDataItem>,
    pendingBackupDocs: List<DocumentFile>,
    pendingBackupInternalPaths: List<String>
): AfrondResult = withContext(Dispatchers.IO) {
    
    // 1. Laad opgeslagen envelope header
    val savedEnvelopeJson = prefs.getString(PREF_SAVED_ENVELOPE_JSON, null)
    val envelopeList = VT5App.json.decodeFromString(
        ListSerializer(ServerTellingEnvelope.serializer()), 
        savedEnvelopeJson
    )
    
    // 2. Inject saved onlineId
    val savedOnlineId = prefs.getString(PREF_ONLINE_ID, "")
    val envelopeWithOnline = dataProcessor.applySavedOnlineIdToEnvelope(
        envelopeList, 
        savedOnlineId
    )
    
    // 3. Update tijden en statistics
    val nowEpoch = (System.currentTimeMillis() / 1000L)
    val nowEpochStr = nowEpoch.toString()
    val nowFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    
    val baseEnv = envelopeWithOnline[0]
    val envWithTimes = baseEnv.copy(
        eindtijd = nowEpochStr,      // ← Eindtijd nu ingevuld!
        uploadtijdstip = nowFormatted
    )
    
    // 4. Voeg alle verzamelde records toe
    val recordsSnapshot = ArrayList(pendingRecords)
    val nrec = recordsSnapshot.size
    val nsoort = recordsSnapshot.map { it.soortid }.toSet().size
    
    val finalEnv = envWithTimes.copy(
        nrec = nrec.toString(),       // ← Aantal records
        nsoort = nsoort.toString(),   // ← Aantal unieke soorten
        data = recordsSnapshot        // ← ALLE WAARNEMINGEN HIER!
    )
    val envelopeToSend = listOf(finalEnv)
    
    // 5. Sla pretty JSON op voor backup
    val prettyJson = PRETTY_JSON.encodeToString(
        ListSerializer(ServerTellingEnvelope.serializer()), 
        envelopeToSend
    )
    val savedPrettyPath = backupManager.writePrettyEnvelopeToSaf(onlineId, prettyJson)
    
    // 6. Upload naar server
    val (ok, resp) = TrektellenApi.postCountsSave(
        baseUrl, language, versie, user, pass, envelopeToSend
    )
    
    // 7. Cleanup bij succes
    if (ok) {
        pendingRecords.clear()
        pendingBackupDocs.forEach { it.delete() }
        prefs.edit {
            remove(PREF_ONLINE_ID)
            remove(PREF_TELLING_ID)
            remove(PREF_SAVED_ENVELOPE_JSON)
        }
    }
}
```

### Resultaat Fase 3
- **Volledige envelope** met metadata header + alle data records
- **Statistieken bijgewerkt**: `nrec` (aantal records), `nsoort` (aantal soorten)
- **Eindtijd ingevuld** (was leeg in live mode)
- **Upload naar server** via `TrektellenApi.postCountsSave`
- **Pretty JSON backup** geschreven naar SAF
- **Cleanup** van buffers en preferences bij succes

---

## 📦 Data Model: ServerTellingEnvelope

### Definitie
**File**: `app/src/main/java/com/yvesds/vt5/net/Types.kt` (regel 9-43)

```kotlin
@Serializable
data class ServerTellingEnvelope(
    // Metadata velden
    @SerialName("externid") val externid: String,           // "Android App 1.8.45"
    @SerialName("timezoneid") val timezoneid: String,       // "Europe/Brussels"
    @SerialName("bron") val bron: String,                   // "4"
    @SerialName("_id") val idLocal: String,
    @SerialName("tellingid") val tellingid: String,         // Telling ID
    @SerialName("telpostid") val telpostid: String,         // Telpost locatie ID
    @SerialName("onlineid") val onlineid: String,           // Server-gegenereerde ID
    
    // Tijd velden
    @SerialName("begintijd") val begintijd: String,         // Epoch seconds
    @SerialName("eindtijd") val eindtijd: String,           // Epoch seconds (leeg in live mode)
    @SerialName("uploadtijdstip") val uploadtijdstip: String, // "yyyy-MM-dd HH:mm:ss"
    
    // Weer metadata
    @SerialName("tellers") val tellers: String,
    @SerialName("weer") val weer: String,                   // Opmerking
    @SerialName("windrichting") val windrichting: String,
    @SerialName("windkracht") val windkracht: String,       // Beaufort 0-12
    @SerialName("temperatuur") val temperatuur: String,     // Celsius
    @SerialName("bewolking") val bewolking: String,         // Achtsten 0-8
    @SerialName("bewolkinghoogte") val bewolkinghoogte: String,
    @SerialName("neerslag") val neerslag: String,           // Code (regen, etc)
    @SerialName("duurneerslag") val duurneerslag: String,
    @SerialName("zicht") val zicht: String,                 // Meters
    @SerialName("hpa") val hpa: String,                     // Luchtdruk
    
    // Telling metadata
    @SerialName("tellersactief") val tellersactief: String,
    @SerialName("tellersaanwezig") val tellersaanwezig: String,
    @SerialName("typetelling") val typetelling: String,     // "all" etc
    @SerialName("metersnet") val metersnet: String,
    @SerialName("geluid") val geluid: String,
    @SerialName("opmerkingen") val opmerkingen: String,
    @SerialName("equipment") val equipment: String,
    @SerialName("HYDRO") val hydro: String,
    @SerialName("uuid") val uuid: String,                   // Unique UUID
    
    // Statistics (ingevuld bij afronden)
    @SerialName("nrec") val nrec: String,                   // Aantal records
    @SerialName("nsoort") val nsoort: String,               // Aantal unieke soorten
    
    // DATA ARRAY - ALLE WAARNEMINGEN!
    @SerialName("data") val data: List<ServerTellingDataItem>  // ← HIER!
)
```

### ServerTellingDataItem (individuele waarneming)
**File**: `app/src/main/java/com/yvesds/vt5/net/Types.kt` (regel 46-73)

```kotlin
@Serializable
data class ServerTellingDataItem(
    @SerialName("_id") val idLocal: String = "",            // Unieke record ID
    @SerialName("tellingid") val tellingid: String = "",    // Telling ID
    @SerialName("soortid") val soortid: String = "",        // Species ID
    @SerialName("aantal") val aantal: String = "",          // Aantal vogels
    @SerialName("richting") val richting: String = "",
    @SerialName("aantalterug") val aantalterug: String = "",
    @SerialName("richtingterug") val richtingterug: String = "",
    @SerialName("sightingdirection") val sightingdirection: String = "",
    @SerialName("lokaal") val lokaal: String = "",
    @SerialName("aantal_plus") val aantal_plus: String = "",
    @SerialName("aantalterug_plus") val aantalterug_plus: String = "",
    @SerialName("lokaal_plus") val lokaal_plus: String = "",
    @SerialName("markeren") val markeren: String = "",
    @SerialName("markerenlokaal") val markerenlokaal: String = "",
    @SerialName("geslacht") val geslacht: String = "",
    @SerialName("leeftijd") val leeftijd: String = "",
    @SerialName("kleed") val kleed: String = "",
    @SerialName("opmerkingen") val opmerkingen: String = "",
    @SerialName("trektype") val trektype: String = "",
    @SerialName("teltype") val teltype: String = "",
    @SerialName("location") val location: String = "",
    @SerialName("height") val height: String = "",
    @SerialName("tijdstip") val tijdstip: String = "",      // Epoch seconds
    @SerialName("groupid") val groupid: String = "",
    @SerialName("uploadtijdstip") val uploadtijdstip: String = "",
    @SerialName("totaalaantal") val totaalaantal: String = ""
)
```

---

## 🔄 Volledige Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ FASE 1: METADATA HEADER CREATIE                                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
    MetadataScherm.kt (onVerderClicked)
                              ↓
    TellingStarter.startTelling()
         ├─→ Verzamel form data
         ├─→ StartTellingApi.buildEnvelopeFromUi()
         │      └─→ ServerTellingEnvelope(
         │              tellingid = "123456",
         │              telpostid = "NL001",
         │              begintijd = "1700000000",
         │              eindtijd = "",              ← LEEG (live mode)
         │              windrichting = "N",
         │              temperatuur = "15",
         │              ... alle metadata ...
         │              nrec = "0",                 ← Nog geen data
         │              nsoort = "0",
         │              data = []                   ← LEEG ARRAY
         │          )
         ├─→ TrektellenApi.postCountsSave(envelope)
         ├─→ Parse onlineId uit response
         └─→ Opslaan in SharedPreferences:
                 PREF_ONLINE_ID = "789123"
                 PREF_TELLING_ID = "123456"
                 PREF_SAVED_ENVELOPE_JSON = "{...envelope JSON...}"

┌─────────────────────────────────────────────────────────────────┐
│ FASE 2: DATA RECORD VERZAMELING (tijdens telling)               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
    TellingScherm.kt (gebruiker telt vogels)
                              ↓
    Voor elke waarneming:
         Speech recognition of UI input
                              ↓
         recordSpeciesCount(speciesId, displayName, count)
                              ↓
         TellingSpeciesManager.collectFinalAsRecord(soortId, amount)
              ├─→ Genereer unieke _id
              ├─→ Maak ServerTellingDataItem(
              │       idLocal = "1",
              │       tellingid = "123456",
              │       soortid = "species_001",
              │       aantal = "5",
              │       tijdstip = "1700001234",
              │       ... alle velden ...
              │   )
              ├─→ pendingRecords.add(item)        ← BUFFER
              └─→ Backup naar SAF storage

    Herhaal voor elke waarneming...
    
    ┌────────────────────────────────┐
    │ pendingRecords buffer:          │
    │  [0] ServerTellingDataItem      │
    │      soortid="species_001"      │
    │      aantal="5"                 │
    │  [1] ServerTellingDataItem      │
    │      soortid="species_002"      │
    │      aantal="12"                │
    │  [2] ServerTellingDataItem      │
    │      soortid="species_001"      │
    │      aantal="3"                 │
    │  ... meer records ...           │
    └────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ FASE 3: FINALE ENVELOPPE ASSEMBLY & UPLOAD                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
    TellingScherm.handleAfronden()
                              ↓
    TellingAfrondHandler.handleAfronden(pendingRecords)
         ├─→ Laad opgeslagen envelope uit SharedPreferences
         ├─→ Inject onlineId = "789123"
         ├─→ Update tijden:
         │      eindtijd = "1700010000"           ← NU INGEVULD!
         │      uploadtijdstip = "2024-01-15 14:30:00"
         ├─→ Bereken statistics:
         │      nrec = pendingRecords.size = "3"
         │      nsoort = unieke soorten = "2"
         ├─→ Voeg alle data toe:
         │      data = pendingRecords             ← ALLE WAARNEMINGEN!
         │
         └─→ Finale envelope:
                 ServerTellingEnvelope(
                     tellingid = "123456",
                     onlineid = "789123",
                     telpostid = "NL001",
                     begintijd = "1700000000",
                     eindtijd = "1700010000",     ← NU GEVULD
                     windrichting = "N",
                     temperatuur = "15",
                     ... alle metadata ...
                     nrec = "3",                  ← GEVULD
                     nsoort = "2",                ← GEVULD
                     data = [                     ← GEVULD MET DATA!
                         ServerTellingDataItem(
                             _id="1", soortid="species_001", aantal="5"
                         ),
                         ServerTellingDataItem(
                             _id="2", soortid="species_002", aantal="12"
                         ),
                         ServerTellingDataItem(
                             _id="3", soortid="species_001", aantal="3"
                         )
                     ]
                 )
                              ↓
         TrektellenApi.postCountsSave(finalEnvelope)
                              ↓
         Server verwerkt telling
                              ↓
         Success: Cleanup buffers & preferences
```

---

## 🔑 Belangrijke Keys & Constants

### SharedPreferences Keys
```kotlin
// app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt
private const val PREFS_NAME = "vt5_prefs"
private const val PREF_ONLINE_ID = "pref_online_id"              // Server-gegenereerd ID
private const val PREF_TELLING_ID = "pref_telling_id"            // Lokaal telling ID
private const val PREF_SAVED_ENVELOPE_JSON = "pref_saved_envelope_json"  // Envelope header
```

### API Endpoints
```kotlin
// TrektellenApi.postCountsSave()
// POST https://trektellen.nl/api/counts_save
// Body: JSON array met 1 envelope (ServerTellingEnvelope)
```

---

## 📁 File Overzicht

### Core Enveloppe Files
1. **Types.kt** - Data models (ServerTellingEnvelope, ServerTellingDataItem)
2. **StartTellingApi.kt** - Bouwt initiële envelope met metadata
3. **TellingStarter.kt** - Start telling, maakt header, krijgt onlineId
4. **TellingSpeciesManager.kt** - Maakt individuele data items
5. **TellingAfrondHandler.kt** - Assembleert finale envelope, upload
6. **TrektellenApi.kt** - HTTP communicatie met server

### Helper Files
7. **MetadataScherm.kt** - UI voor metadata invoer
8. **MetadataFormManager.kt** - Form data management
9. **TellingScherm.kt** - Hoofd telling scherm
10. **TellingBackupManager.kt** - Backup functionaliteit
11. **DataUploader.kt** - Individual upload (niet voor finale envelope)

---

## 🎯 Antwoorden op Je Vragen

### "Waar wordt de enveloppe gemaakt?"

**Antwoord**: De enveloppe wordt in **3 stappen** gemaakt:

1. **Metadata header** → `StartTellingApi.buildEnvelopeFromUi()` (regel 16-84)
   - Aangeroepen vanuit `TellingStarter.startTelling()` (regel 90-107)
   - Triggered door `MetadataScherm.onVerderClicked()` (regel 273-291)

2. **Data items** → `TellingSpeciesManager.collectFinalAsRecord()` (regel 177-235)
   - Aangeroepen vanuit `TellingScherm.recordSpeciesCount()` (regel 610-617)
   - Voor elke waarneming tijdens de telling

3. **Finale assembly** → `TellingAfrondHandler.handleAfronden()` (regel 67-248)
   - Aangeroepen vanuit `TellingScherm.handleAfronden()` (regel 758-798)
   - Bij klikken op "Afronden" knop

### "MetadataScherm.kt maakt metadata header"

**Correct!** Exact via deze flow:
```
MetadataScherm.kt (UI)
    ↓
TellingStarter.kt (Logic)
    ↓
StartTellingApi.kt (Builder)
    ↓
ServerTellingEnvelope (Model)
```

### "TellingScherm.kt maakt enveloppe voor data record"

**Bijna correct!** De precieze flow is:
```
TellingScherm.kt (Orchestration)
    ↓
TellingSpeciesManager.kt (maakt individuele ServerTellingDataItem)
    ↓
TellingAfrondHandler.kt (assembleert finale envelope met alle items)
```

---

## 💡 Belangrijke Inzichten

1. **Twee-fase proces**: 
   - Header eerst (bij start) 
   - Data later (bij afronden)

2. **Live mode**: 
   - Eindtijd is **leeg** bij start
   - Wordt **ingevuld** bij afronden

3. **Buffer systeem**: 
   - Alle waarnemingen in `pendingRecords` lijst
   - Backup naar SAF na elke waarneming
   - Finale upload bij afronden

4. **Separation of concerns**:
   - `StartTellingApi` → Envelope builder
   - `TellingStarter` → Start logica
   - `TellingSpeciesManager` → Data items
   - `TellingAfrondHandler` → Assembly & upload

5. **Persistence**:
   - Envelope header in SharedPreferences
   - Data items in memory buffer + SAF backup
   - Pretty JSON backup bij upload

---

## 🔍 Code Locaties Samenvatting

| Component | File | Regels | Functie |
|-----------|------|--------|---------|
| Envelope Model | `net/Types.kt` | 9-73 | Data class definitie |
| Envelope Builder | `net/StartTellingApi.kt` | 16-84 | buildEnvelopeFromUi() |
| Start Logic | `features/metadata/helpers/TellingStarter.kt` | 56-166 | startTelling() |
| Data Item Creator | `features/telling/TellingSpeciesManager.kt` | 177-235 | collectFinalAsRecord() |
| Final Assembly | `features/telling/TellingAfrondHandler.kt` | 67-248 | handleAfronden() |
| Orchestration | `features/telling/TellingScherm.kt` | 758-798 | handleAfronden() |
| API Upload | `net/TrektellenApi.kt` | - | postCountsSave() |

---

## 📝 Conclusie

De 'enveloppe' in VT5 is een **composiet datastructuur** (`ServerTellingEnvelope`) die bestaat uit:

1. **Metadata header** (weer, locatie, tijden) - gemaakt in `MetadataScherm.kt`
2. **Data array** (alle waarnemingen) - verzameld in `TellingScherm.kt`
3. **Statistics** (aantal records, soorten) - berekend bij afronden

De **volledige enveloppe** wordt pas **geassembleerd** bij het **afronden** door `TellingAfrondHandler.handleAfronden()`, die:
- De opgeslagen header laadt
- Alle verzamelde data items toevoegt
- Statistics berekent
- Upload naar server doet

Dit is een **efficiënt ontwerp** omdat:
- Metadata maar 1x verzonden wordt (bij start)
- Waarnemingen lokaal gebufferd worden
- Finale upload alles in 1 keer verstuurt
- Backup op meerdere punten gebeurt
