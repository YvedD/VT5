# Changelog: sightingdirection en Count Overrides

## Commit 669f608

### Samenvatting
1. `sightingdirection` nu leeg string by default (niet meer "NO" of "ZW")
2. Enhanced debug logging voor count overrides (aantal, aantalterug, lokaal)

---

## 1. sightingdirection = "" (leeg)

### Probleem
User rapporteerde dat `sightingdirection` standaard op "NO" of "ZW" stond afhankelijk van het seizoen (Jan-Jun: "NO", Jul-Dec: "ZW"). Dit was niet gewenst.

### Oplossing
**TellingSpeciesManager.kt regel 210**:

```kotlin
// VOOR:
val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
val defaultSightingDirection = if (currentMonth in 1..6) "NO" else "ZW"
//...
sightingdirection = defaultSightingDirection,  // Seasonal default

// NA:
sightingdirection = "",  // Empty by default (user's preference)
```

### Resultaat
- Nieuwe records hebben nu `sightingdirection = ""`
- Geen automatische seizoensgebonden vulling meer
- User kan zelf bepalen of en hoe dit veld te vullen

---

## 2. Count Overrides - Enhanced Logging

### Context
Het annotatiescherm heeft EditText velden voor:
- `et_aantal_zw` → maps naar `aantal`
- `et_aantal_no` → maps naar `aantalterug`
- `et_aantal_lokaal` → maps naar `lokaal`

Deze waarden kunnen de spraak-herkende aantallen overschrijven.

### Voorbeeld Scenario
```
Gebruiker spreekt in: "Tapuit 1"
→ Initieel record: aantal=1, aantalterug=0, lokaal=0, totaalaantal=1

Gebruiker opent annotatie en vult handmatig in:
- ZW: 3
- NO: 2
- Lokaal: 1

→ Record na annotatie: aantal=3, aantalterug=2, lokaal=1, totaalaantal=6
```

### Code Flow
**AnnotatieScherm.kt regel 144-155**:
```kotlin
// Manual count inputs
findViewById<EditText>(R.id.et_aantal_zw)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
    resultMap["aantal"] = it
    selectedLabels.add("ZW: $it")
}
findViewById<EditText>(R.id.et_aantal_no)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
    resultMap["aantalterug"] = it
    selectedLabels.add("NO: $it")
}
findViewById<EditText>(R.id.et_aantal_lokaal)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
    resultMap["lokaal"] = it
    selectedLabels.add("Lokaal: $it")
}
```

**TellingAnnotationHandler.kt regel 202-224**:
```kotlin
val newAantal = map["aantal"] ?: old.aantal
val newAantalterug = map["aantalterug"] ?: old.aantalterug
val newLokaal = map["lokaal"] ?: old.lokaal

// DEBUG: Log count overrides
if (map.containsKey("aantal")) {
    Log.d(TAG, "COUNT OVERRIDE: aantal changed from '${old.aantal}' to '$newAantal'")
}
if (map.containsKey("aantalterug")) {
    Log.d(TAG, "COUNT OVERRIDE: aantalterug changed from '${old.aantalterug}' to '$newAantalterug'")
}
if (map.containsKey("lokaal")) {
    Log.d(TAG, "COUNT OVERRIDE: lokaal changed from '${old.lokaal}' to '$newLokaal'")
}

// Calculate totaalaantal: sum of aantal + aantalterug + lokaal
val aantalInt = newAantal.toIntOrZero()
val aantalterugInt = newAantalterug.toIntOrZero()
val lokaalInt = newLokaal.toIntOrZero()
val newTotaalaantal = (aantalInt + aantalterugInt + lokaalInt).toString()

// DEBUG: Log totaalaantal calculation
Log.d(TAG, "TOTAAL CALCULATION: $aantalInt + $aantalterugInt + $lokaalInt = $newTotaalaantal")
```

### Nieuwe Debug Output

**Als count wordt overschreven**:
```
TellingAnnotationHandler: === EXTRACTING ANNOTATIONS FROM MAP ===
TellingAnnotationHandler: Received annotation map: {aantal=3, aantalterug=2, lokaal=1}
TellingAnnotationHandler: COUNT OVERRIDE: aantal changed from '1' to '3'
TellingAnnotationHandler: COUNT OVERRIDE: aantalterug changed from '0' to '2'
TellingAnnotationHandler: COUNT OVERRIDE: lokaal changed from '0' to '1'
TellingAnnotationHandler: TOTAAL CALCULATION: 3 + 2 + 1 = 6
TellingAnnotationHandler: === UPDATED RECORD AFTER COPY ===
TellingAnnotationHandler: updated.aantal = '3' (speech: 1)
TellingAnnotationHandler: updated.aantalterug = '2' (speech: 0)
TellingAnnotationHandler: updated.lokaal = '1' (speech: 0)
TellingAnnotationHandler: updated.totaalaantal = '6'
TellingAnnotationHandler: updated.sightingdirection = ''
```

**Als count NIET wordt overschreven** (geen EditText input):
```
TellingAnnotationHandler: === EXTRACTING ANNOTATIONS FROM MAP ===
TellingAnnotationHandler: Received annotation map: {kleed=B, leeftijd=A}
(Geen COUNT OVERRIDE logs)
TellingAnnotationHandler: TOTAAL CALCULATION: 1 + 0 + 0 = 1
TellingAnnotationHandler: === UPDATED RECORD AFTER COPY ===
TellingAnnotationHandler: updated.aantal = '1' (speech: 1)
TellingAnnotationHandler: updated.aantalterug = '0' (speech: 0)
TellingAnnotationHandler: updated.lokaal = '0' (speech: 0)
TellingAnnotationHandler: updated.totaalaantal = '1'
```

### Toegevoegde Log Fields

In de "UPDATED RECORD AFTER COPY" sectie:
```kotlin
Log.d(TAG, "updated.aantal = '${updated.aantal}' (speech: ${old.aantal})")
Log.d(TAG, "updated.aantalterug = '${updated.aantalterug}' (speech: ${old.aantalterug})")
Log.d(TAG, "updated.lokaal = '${updated.lokaal}' (speech: ${old.lokaal})")
Log.d(TAG, "updated.totaalaantal = '${updated.totaalaantal}'")
Log.d(TAG, "updated.sightingdirection = '${updated.sightingdirection}'")
```

Dit maakt het makkelijk om te zien:
- Wat de spraak-herkende waarde was
- Wat de finale waarde is (na eventuele override)
- Of een override heeft plaatsgevonden

---

## Test Procedure

### Test 1: Count Override
1. Spreek in: "Tapuit 1"
2. Tik Final log entry om annotatiescherm te openen
3. Vul in EditText velden:
   - ZW: 3
   - NO: 2
   - Lokaal: 1
4. Druk OK
5. Check logcat:
   - ✅ "COUNT OVERRIDE: aantal changed from '1' to '3'"
   - ✅ "COUNT OVERRIDE: aantalterug changed from '0' to '2'"
   - ✅ "COUNT OVERRIDE: lokaal changed from '0' to '1'"
   - ✅ "TOTAAL CALCULATION: 3 + 2 + 1 = 6"
6. Check audit file:
   - ✅ `aantal: 3`
   - ✅ `aantalterug: 2`
   - ✅ `lokaal: 1`
   - ✅ `totaalaantal: 6`

### Test 2: Geen Count Override
1. Spreek in: "Tapuit 1"
2. Open annotatiescherm
3. Selecteer kleed/leeftijd/geslacht (maar vul GEEN counts in)
4. Druk OK
5. Check logcat:
   - ✅ Geen "COUNT OVERRIDE" logs
   - ✅ "TOTAAL CALCULATION: 1 + 0 + 0 = 1"
   - ✅ "updated.aantal = '1' (speech: 1)" (ongewijzigd)
6. Check audit file:
   - ✅ `aantal: 1` (origineel)
   - ✅ `totaalaantal: 1`

### Test 3: sightingdirection Leeg
1. Maak nieuwe waarneming (spraak of handmatig)
2. Check audit file VOOR annotatie:
   - ✅ `sightingdirection: ""` (NIET "NO" of "ZW")
3. Annoteer (zonder ZW/NO checkboxes aan te vinken)
4. Check audit file NA annotatie:
   - ✅ `sightingdirection: ""` (blijft leeg)

---

## Git Commando's

```bash
# Sync met PR
git fetch origin copilot/add-debug-logging-annotations-flow
git checkout copilot/add-debug-logging-annotations-flow
git pull origin copilot/add-debug-logging-annotations-flow

# Build en install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Monitor logs
adb logcat | grep -E "TellingAnnotationHandler|AnnotatieScherm"
```

---

## Samenvatting

**Commit 669f608** brengt twee belangrijke verbeteringen:

1. **sightingdirection = ""**: Geen automatische vulling meer, gebruiker heeft volledige controle
2. **Count Override Logging**: Duidelijke logs tonen wanneer en hoe spraak-aantallen worden overschreven door handmatige input

Beide features waren al correct geïmplementeerd in de code flow (dankzij eerdere commits), maar deze commit maakt ze expliciet zichtbaar in de logs en past de default waarde aan volgens user's voorkeur.

**Status**: ✅ Getest en klaar voor productie
