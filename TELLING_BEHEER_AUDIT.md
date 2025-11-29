# VT5 Telling Beheer - Audit en Code Voorstel

**Datum:** 29 november 2025  
**Branch:** copilot/audit-codebase-and-suggestions  
**Doel:** Toolset voor het bewerken van opgeslagen tellingen

---

## 1. Huidige Architectuur Analyse

### 1.1 Opslaglocaties

De app slaat tellingen op in de volgende locaties:

```
Documents/VT5/
├── counts/                  # Telling bestanden
│   ├── active_telling.json  # Actieve telling (wordt overschreven)
│   └── telling_<id>_<online>_<timestamp>.json  # Gearchiveerde tellingen
├── exports/                 # Record backups en audit bestanden
│   ├── <timestamp>_rec_<id>.txt        # Individuele record backup
│   ├── <timestamp>_count_<onlineid>.json  # Envelope export
│   └── <timestamp>_audit_<tellingid>.txt  # Upload audit
└── ...
```

### 1.2 Data Modellen

#### ServerTellingEnvelope (Metadata)
```kotlin
data class ServerTellingEnvelope(
    val externid: String,       // Extern ID
    val timezoneid: String,     // Tijdzone
    val bron: String,           // Bron applicatie
    val idLocal: String,        // Lokaal ID (_id)
    val tellingid: String,      // Telling ID
    val telpostid: String,      // Telpost ID
    val begintijd: String,      // Starttijd (epoch)
    val eindtijd: String,       // Eindtijd (epoch)
    val tellers: String,        // Namen tellers
    val weer: String,           // Weer opmerkingen
    val windrichting: String,   // Windrichting code
    val windkracht: String,     // Windkracht (Beaufort)
    val temperatuur: String,    // Temperatuur °C
    val bewolking: String,      // Bewolking (0-8)
    val bewolkinghoogte: String,// Bewolking hoogte
    val neerslag: String,       // Neerslag code
    val duurneerslag: String,   // Duur neerslag
    val zicht: String,          // Zicht (meters)
    val tellersactief: String,  // Actieve tellers
    val tellersaanwezig: String,// Aanwezige tellers
    val typetelling: String,    // Type telling code
    val metersnet: String,      // Meters net
    val geluid: String,         // Geluid
    val opmerkingen: String,    // Algemene opmerkingen
    val onlineid: String,       // Online ID (server)
    val hydro: String,          // HYDRO veld
    val hpa: String,            // Luchtdruk hPa
    val equipment: String,      // Apparatuur
    val uuid: String,           // UUID
    val uploadtijdstip: String, // Upload timestamp
    val nrec: String,           // Aantal records
    val nsoort: String,         // Aantal soorten
    val data: List<ServerTellingDataItem>  // De waarnemingen
)
```

#### ServerTellingDataItem (Data Record)
```kotlin
data class ServerTellingDataItem(
    val idLocal: String,        // Lokaal ID (_id)
    val tellingid: String,      // Telling ID
    val soortid: String,        // Soort ID
    val aantal: String,         // Aantal
    val richting: String,       // Richting
    val aantalterug: String,    // Aantal terug
    val richtingterug: String,  // Richting terug
    val sightingdirection: String, // Waarnemingsrichting
    val lokaal: String,         // Lokaal
    val aantal_plus: String,    // Aantal plus
    val aantalterug_plus: String, // Aantal terug plus
    val lokaal_plus: String,    // Lokaal plus
    val markeren: String,       // Markeren
    val markerenlokaal: String, // Markeren lokaal
    val geslacht: String,       // Geslacht
    val leeftijd: String,       // Leeftijd
    val kleed: String,          // Kleed
    val opmerkingen: String,    // Opmerkingen
    val trektype: String,       // Trektype
    val teltype: String,        // Teltype
    val location: String,       // Locatie
    val height: String,         // Hoogte
    val tijdstip: String,       // Tijdstip (epoch)
    val groupid: String,        // Group ID
    val uploadtijdstip: String, // Upload tijdstip
    val totaalaantal: String    // Totaal aantal
)
```

### 1.3 Bestaande Klassen

| Klasse | Verantwoordelijkheid |
|--------|---------------------|
| `TellingEnvelopePersistence` | Continue opslag van actieve telling naar `active_telling.json` |
| `RecordsBeheer` | In-memory beheer van pending records + backup schrijven |
| `TellingBackupManager` | Schrijven van export/audit bestanden naar SAF/internal |
| `SaFStorageHelper` | Abstractie voor Storage Access Framework operaties |

### 1.4 Bestandsformaat

Tellingen worden opgeslagen als JSON array met één envelope:

```json
[
  {
    "externid": "...",
    "tellingid": "1",
    "telpostid": "123",
    "begintijd": "1732876800",
    "eindtijd": "1732890000",
    "tellers": "Jan Jansen",
    "nrec": "5",
    "nsoort": "3",
    "data": [
      {
        "_id": "1",
        "tellingid": "1",
        "soortid": "1001",
        "aantal": "5",
        "tijdstip": "1732877400",
        ...
      },
      ...
    ]
  }
]
```

---

## 2. Vereiste Functionaliteit

Op basis van de probleemstelling zijn de volgende functies nodig:

### 2.1 Data Record Operaties
- **Aanpassen**: Wijzigen van bestaande records (aantal, soort, annotaties)
- **Toevoegen**: Nieuwe records toevoegen aan een telling
- **Verwijderen**: Records verwijderen uit een telling

### 2.2 Metadata Operaties
- **Aanpassen metadata**: Wijzigen van envelope velden (tellers, opmerkingen, weer, etc.)

### 2.3 Bestandsbeheer
- **Lijst tellingen**: Overzicht van alle opgeslagen tellingen in counts map
- **Verwijderen telling**: Volledig verwijderen van een telling bestand
- **Laden telling**: Telling laden voor bewerking
- **Opslaan telling**: Telling opslaan na bewerking

---

## 3. Geïmplementeerde Oplossing

### 3.1 Nieuwe Klassen

#### TellingBeheerToolset.kt
Centrale toolset klasse met alle bewerkingsoperaties:

```kotlin
class TellingBeheerToolset(context: Context, safHelper: SaFStorageHelper) {
    // === Bestandsbeheer ===
    suspend fun listSavedTellingen(): List<TellingFileInfo>
    suspend fun loadTelling(filename: String): ServerTellingEnvelope?
    suspend fun saveTelling(envelope: ServerTellingEnvelope, filename: String?): Boolean
    suspend fun deleteTelling(filename: String): Boolean
    
    // === Record Operaties ===
    fun updateRecord(envelope: ServerTellingEnvelope, index: Int, updated: ServerTellingDataItem): ServerTellingEnvelope
    fun addRecord(envelope: ServerTellingEnvelope, record: ServerTellingDataItem, generateId: Boolean): ServerTellingEnvelope
    fun deleteRecord(envelope: ServerTellingEnvelope, index: Int): ServerTellingEnvelope
    fun deleteRecords(envelope: ServerTellingEnvelope, indices: List<Int>): ServerTellingEnvelope
    
    // === Metadata Operaties ===
    fun updateMetadata(envelope: ServerTellingEnvelope, updates: MetadataUpdates): ServerTellingEnvelope
    fun updateRecordOpmerkingen(envelope: ServerTellingEnvelope, index: Int, opmerkingen: String): ServerTellingEnvelope
    fun updateRecordAantal(envelope: ServerTellingEnvelope, index: Int, aantal: Int): ServerTellingEnvelope
}
```

#### TellingFileInfo.kt
Data class voor bestandsinformatie:

```kotlin
data class TellingFileInfo(
    val filename: String,
    val tellingId: String?,
    val onlineId: String?,
    val telpost: String?,
    val timestamp: String?,
    val nrec: Int,
    val nsoort: Int,
    val fileSize: Long,
    val lastModified: Long,
    val isActive: Boolean
)
```

#### MetadataUpdates.kt
Data class voor metadata wijzigingen:

```kotlin
data class MetadataUpdates(
    val tellers: String? = null,
    val opmerkingen: String? = null,
    val windrichting: String? = null,
    val windkracht: String? = null,
    val temperatuur: String? = null,
    val bewolking: String? = null,
    // ... alle metadata velden
)
```

### 3.2 SaFStorageHelper Extensies

Nieuwe methoden voor counts-directory operaties:

```kotlin
// Nieuwe functies in SaFStorageHelper
fun getCountsDir(): DocumentFile?
suspend fun getCountsDirSuspend(): DocumentFile?
fun listCountsFiles(): List<DocumentFile>
suspend fun listCountsFilesSuspend(): List<DocumentFile>
fun deleteCountsFile(filename: String): Boolean
suspend fun deleteCountsFileSuspend(filename: String): Boolean
fun readCountsFile(filename: String): String?
suspend fun readCountsFileSuspend(filename: String): String?
fun writeCountsFile(filename: String, content: String): Boolean
suspend fun writeCountsFileSuspend(filename: String, content: String): Boolean
```

---

## 4. Gebruik Voorbeelden

### 4.1 Lijst alle opgeslagen tellingen

```kotlin
val toolset = TellingBeheerToolset(context, safHelper)
val tellingen = toolset.listSavedTellingen()

tellingen.forEach { info ->
    println("${info.filename}: ${info.nrec} records, ${info.nsoort} soorten")
}
```

### 4.2 Telling laden en bewerken

```kotlin
// Laad telling
val envelope = toolset.loadTelling("telling_1_12345_20251129.json")
if (envelope != null) {
    // Update aantal van record 0
    val updated = toolset.updateRecordAantal(envelope, 0, 10)
    
    // Sla op
    toolset.saveTelling(updated, "telling_1_12345_20251129.json")
}
```

### 4.3 Record toevoegen

```kotlin
val newRecord = ServerTellingDataItem(
    soortid = "1001",
    aantal = "5"
    // ... overige velden
)

val updatedEnvelope = toolset.addRecord(envelope, newRecord, generateId = true)
toolset.saveTelling(updatedEnvelope)
```

### 4.4 Metadata bijwerken

```kotlin
val updates = MetadataUpdates(
    tellers = "Nieuwe Teller",
    opmerkingen = "Nieuwe opmerking"
)

val updatedEnvelope = toolset.updateMetadata(envelope, updates)
toolset.saveTelling(updatedEnvelope)
```

### 4.5 Telling verwijderen

```kotlin
val success = toolset.deleteTelling("telling_1_12345_20251129.json")
```

---

## 5. Beveiliging

### 5.1 Path Traversal Bescherming

Alle bestandsnamen worden gevalideerd om path traversal aanvallen te voorkomen:
- Geen `..` toegestaan
- Geen `/` of `\` toegestaan
- Bestandsnamen worden gesanitized voor opslag

### 5.2 Bestandsnaam Sanitizatie

```kotlin
private fun sanitizeFilename(input: String): String {
    return input.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
}
```

---

## 6. Voordelen van Deze Implementatie

1. **Centralisatie**: Alle bewerkingslogica op één plek (TellingBeheerToolset)
2. **Immutability**: Record/metadata operaties retourneren nieuwe envelope instances
3. **Testbaarheid**: Pure functies voor record/metadata operaties
4. **Flexibiliteit**: Werkt met zowel actieve als gearchiveerde tellingen
5. **Consistentie**: Gebruikt bestaande data modellen (ServerTellingEnvelope, ServerTellingDataItem)
6. **SAF Compatibiliteit**: Werkt correct met Android Storage Access Framework
7. **Fallback**: Automatische fallback naar internal storage wanneer SAF niet beschikbaar is
8. **Coroutine-Safe**: Alle I/O operaties zijn suspend functions op Dispatchers.IO

---

## 7. Bestandslocaties

| Bestand | Locatie |
|---------|---------|
| `TellingBeheerToolset.kt` | `app/src/main/java/com/yvesds/vt5/features/telling/` |
| `TellingFileInfo.kt` | `app/src/main/java/com/yvesds/vt5/features/telling/` |
| `MetadataUpdates.kt` | `app/src/main/java/com/yvesds/vt5/features/telling/` |
| `SaFStorageHelper.kt` | `app/src/main/java/com/yvesds/vt5/core/opslag/` (uitgebreid) |

---

*Document gegenereerd tijdens code audit sessie.*
