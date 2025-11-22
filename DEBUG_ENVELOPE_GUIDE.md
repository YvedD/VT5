# VT5 - Envelope Debugging Guide

## Overzicht

Dit document beschrijft hoe je de volledige envelope (header + data records) kunt bekijken die naar de trektellen.nl server verzonden wordt, voor debugging doeleinden.

## 🔍 Waar Vind Je De Data?

### 1. Logcat (Real-time Logging)

De app logt nu de complete envelope voordat deze verzonden wordt naar de server.

**Hoe te gebruiken**:
```bash
# Via Android Studio Logcat, filter op tag "TellingAfrondHandler"
# Of via adb:
adb logcat -s TellingAfrondHandler:D
```

**Log Output Format**:
```
D/TellingAfrondHandler: === ENVELOPE TO SERVER ===
D/TellingAfrondHandler: OnlineId: 12345
D/TellingAfrondHandler: TellingId: abc123
D/TellingAfrondHandler: Number of records: 3
D/TellingAfrondHandler: Record 0: soortid=1234, aantal=5, markeren=1, markerenlokaal=, leeftijd=A, geslacht=M, kleed=B
D/TellingAfrondHandler: Record 1: soortid=5678, aantal=3, markeren=, markerenlokaal=, leeftijd=, geslacht=, kleed=
D/TellingAfrondHandler: Complete envelope JSON:
[
  {
    "externid": "Android App 1.8.45",
    "timezoneid": "Europe/Brussels",
    ...
    "data": [
      {
        "_id": "1",
        "soortid": "1234",
        "aantal": "5",
        "markeren": "1",
        ...
      }
    ]
  }
]
D/TellingAfrondHandler: === END ENVELOPE ===
```

### 2. JSON Backup Files (Post-Upload)

Na elke afgeronde telling worden er automatisch 2 bestanden opgeslagen:

#### A. Envelope JSON (Pretty-Printed)
**Locatie**: `Documents/VT5/exports/`  
**Bestandsnaam**: `YYYYMMDD_HHMMSS_count_<onlineId>.json`  
**Voorbeeld**: `20251122_143025_count_12345.json`

**Inhoud**: Complete envelope met alle data records in leesbaar JSON formaat.

#### B. Audit File (Response + Envelope)
**Locatie**: `Documents/VT5/exports/`  
**Bestandsnaam**: `YYYYMMDD_HHMMSS_audit_<tellingId>.txt`  
**Voorbeeld**: `20251122_143025_audit_abc123.txt`

**Inhoud**:
```
=== ENVELOPE SENT ===
[
  {
    "externid": "Android App 1.8.45",
    ...
    "data": [...]
  }
]

=== SERVER RESPONSE ===
OK: {"onlineid":"12345","message":"success"}
```

### 3. Individual Record Backups

Elke waarneming (met of zonder annotatie) wordt ook individueel gebackup:

**Locatie**: `Documents/VT5/exports/`  
**Bestandsnaam**: `YYYYMMDD_HHMMSS_SSS_rec_<recordId>.txt`  
**Voorbeeld**: `20251122_143025_123_rec_1.txt`

**Inhoud**:
```
=== RECORD BACKUP ===
TellingID: abc123
RecordID: 1
Soortid: 1234
Aantal: 5
Richting: w
Markeren: 1
Markerenlokaal: 
Leeftijd: A
Geslacht: M
Kleed: B
Location: <50m
Height: zee
Opmerkingen: Test opmerking
Tijdstip: 1700662825
UploadTijdstip: 2025-11-22 14:30:25
TotaalAantal: 5
```

## 🐛 Debugging "Markeren" Probleem

### Symptoom
Waarneming met "markeren" checkbox aangevinkt verschijnt **niet** in vet/bold op trektellen.nl.

### Check Procedure

#### Stap 1: Verifieer Annotatie Opslag
1. Maak een waarneming
2. Tap op final log entry
3. Vink "markeren" checkbox aan
4. Druk OK
5. Check logcat of individueel record backup bestand
6. **Verwacht**: `markeren: 1` of `"markeren": "1"`

#### Stap 2: Verifieer Envelope
1. Rond telling af
2. Open envelope JSON bestand: `Documents/VT5/exports/YYYYMMDD_HHMMSS_count_<onlineId>.json`
3. Zoek het data record met jouw soort
4. **Verwacht**:
```json
{
  "soortid": "1234",
  "aantal": "5",
  "markeren": "1",     // ← MOET "1" zijn
  "markerenlokaal": "",
  ...
}
```

#### Stap 3: Verifieer Server Response
1. Open audit bestand: `Documents/VT5/exports/YYYYMMDD_HHMMSS_audit_<tellingId>.txt`
2. Check of server "OK" response geeft
3. Check of er error messages zijn

### Mogelijke Oorzaken

#### A. Veld Wordt Niet Gemapped
**Symptoom**: `"markeren": ""` (leeg) in plaats van `"markeren": "1"`

**Oorzaak**: Annotatie data wordt niet correct toegepast.

**Oplossing**: Verificeer dat `TellingAnnotationHandler.kt` de checkbox correct leest:
```kotlin
// Regel 123-126
findViewById<CheckBox>(R.id.cb_markeren)?.takeIf { it.isChecked }?.let {
    resultMap["markeren"] = "1"  // ← Check of deze regel uitgevoerd wordt
    selectedLabels.add("Markeren")
}
```

#### B. Server Accepteert Veld Niet
**Symptoom**: Envelope bevat `"markeren": "1"` maar trektellen.nl toont het niet in bold.

**Mogelijke oorzaken**:
1. **Server verwacht andere waarde**: Misschien moet het `"true"`, `"yes"`, `"Y"` zijn in plaats van `"1"`
2. **Server veld mapping**: Misschien gebruikt de server een ander veld naam
3. **Permission/Protocol probleem**: Misschien is markeren alleen beschikbaar voor bepaalde users of telpost types

**Test**: Maak handmatig een telling op trektellen.nl website en vink "markeren" aan, inspecteer de data die verzonden wordt via browser DevTools.

#### C. Checkbox Niet Gechecked
**Symptoom**: Gebruiker denkt checkbox aan te vinken maar is niet gechecked.

**Oplossing**: Verbeter UI feedback, bijvoorbeeld:
- Maak checked state duidelijker visueel
- Toon bevestiging "Annotaties toegepast: Markeren"

## 📋 Debug Checklist

Gebruik deze checklist om het probleem systematisch te debuggen:

- [ ] **Logcat bekeken**: Zie je `markeren=1` in de log output?
- [ ] **Record backup bekeken**: Staat `Markeren: 1` in het individuele record bestand?
- [ ] **Envelope JSON bekeken**: Staat `"markeren": "1"` in de data array?
- [ ] **Audit bestand bekeken**: Zegt server "OK" of is er een error?
- [ ] **Website gecontroleerd**: Verschijnt de waarneming überhaupt op trektellen.nl?
- [ ] **Andere annotaties getest**: Werken leeftijd/geslacht/kleed wel correct?
- [ ] **Website handmatig getest**: Werkt markeren als je direct op website invoert?

## 🔧 Code Locaties

### Annotatie Opslag (AnnotatieScherm.kt)
```kotlin
// Regel 123-126: Markeren checkbox
findViewById<CheckBox>(R.id.cb_markeren)?.takeIf { it.isChecked }?.let {
    resultMap["markeren"] = "1"
    selectedLabels.add("Markeren")
}
```

### Annotatie Toepassing (TellingAnnotationHandler.kt)
```kotlin
// Regel 178: Markeren uit map halen
val newMarkeren = map["markeren"] ?: old.markeren

// Regel 215: Markeren schrijven naar record
markeren = newMarkeren
```

### Envelope Logging (TellingAfrondHandler.kt)
```kotlin
// Regel 170-185: Complete envelope logging
Log.d(TAG, "=== ENVELOPE TO SERVER ===")
// ... logging code ...
Log.d(TAG, "Complete envelope JSON:\n$prettyJson")
Log.d(TAG, "=== END ENVELOPE ===")
```

### Server Upload (TrektellenApi.kt)
```kotlin
// postCountsSave() method
// Verzend envelope naar https://trektellen.nl/counts_save
```

## 💡 Tips

1. **Gebruik Android Studio Logcat Filter**:
   - Filter: `tag:TellingAfrondHandler`
   - Sla relevante logs op naar bestand voor analyse

2. **Bewaar Envelope Files**:
   - Copy JSON en audit bestanden naar computer
   - Vergelijk met werkende waarnemingen

3. **Test Incrementeel**:
   - Test eerst zonder annotaties
   - Test met alleen markeren
   - Test met combinatie van annotaties

4. **Vraag Server Team**:
   - Toon envelope JSON aan trektellen.nl support
   - Vraag of `markeren` field correct verwerkt wordt
   - Check of er specifieke requirements zijn

## 📞 Support

Als markeren correct in envelope staat (`"markeren": "1"`) maar niet op website verschijnt:
1. Bewaar de envelope JSON en audit file
2. Neem contact op met trektellen.nl support
3. Stuur de bestanden mee als bewijs

Het is mogelijk dat de server het veld anders interpreteert of dat er een bug in de website is.

---

**Datum**: 2025-11-22  
**Status**: Debugging tools toegevoegd  
**Versie**: 1.0
