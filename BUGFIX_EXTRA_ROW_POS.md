# BUGFIX: extra_row_pos Not Preserved - Annotaties Werden Nooit Toegepast

## Probleem Analyse

### Symptomen
- Button clicks werden correct gelogd: `Button kleed selected: B`, `Button leeftijd selected: A`, `Button geslacht selected: M`
- Maar in de finale datarecord: `kleed=`, `leeftijd=`, `geslacht=` (allemaal LEEG!)

### Root Cause
**AnnotatieScherm.kt gaf `extra_row_pos` NIET terug in de result Intent!**

```kotlin
// VOOR (FOUT):
val out = Intent().apply {
    putExtra(EXTRA_ANNOTATIONS_JSON, payload)
    putExtra(EXTRA_TEXT, summaryText)
    putExtra(EXTRA_TS, tsSeconds)
    // ❌ extra_row_pos ONTBREEKT!
}
```

### Gevolgen
1. TellingAnnotationHandler ontving `rowPos = -1` (default waarde)
2. Kon geen matching record vinden in pendingRecords
3. `idx = -1` → early return
4. **Annotaties werden NOOIT toegepast aan het record!**

```kotlin
// TellingAnnotationHandler.kt regel 158-161
if (idx == -1) {
    Log.w(TAG, "no matching pending record found (rowPos=$rowPos, rowTs=$rowTs)")
    return  // ← Hier stopte het proces!
}
```

## De Fix

### Commit: c38219f

**AnnotatieScherm.kt wijzigingen:**

1. **Extract rowPosition in onCreate()**:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_annotatie)

    // Extract row position from incoming intent
    val rowPosition = intent.getIntExtra("extra_row_pos", -1)
    Log.d("AnnotatieScherm", "Received extra_row_pos: $rowPosition")
    // ...
}
```

2. **Preserve rowPosition in result Intent**:
```kotlin
val out = Intent().apply {
    putExtra(EXTRA_ANNOTATIONS_JSON, payload)
    putExtra(EXTRA_TEXT, summaryText)
    putExtra(EXTRA_TS, tsSeconds)
    putExtra("extra_row_pos", rowPosition)  // ✅ KRITIEKE FIX!
}
Log.d("AnnotatieScherm", "Returning extra_row_pos: $rowPosition")
```

**TellingAnnotationHandler.kt wijzigingen:**

Enhanced debug logging om het probleem duidelijker te maken:

```kotlin
if (rowPos >= 0) {
    val finalsList = onGetFinalsList?.invoke() ?: emptyList()
    Log.d(TAG, "rowPos=$rowPos, finalsList.size=${finalsList.size}")
    val finalRowTs = finalsList.getOrNull(rowPos)?.ts
    Log.d(TAG, "finalRowTs at position $rowPos: $finalRowTs")
    // ...
} else {
    Log.w(TAG, "!!! CRITICAL: rowPos is -1, cannot match record by position !!!")
}

if (idx == -1) {
    Log.e(TAG, "!!! CRITICAL ERROR: no matching pending record found !!!")
    Log.e(TAG, "rowPos=$rowPos, rowTs=$rowTs")
    Log.e(TAG, "This means annotations will NOT be applied to any record!")
    return
}
```

## Verificatie

### Verwachte Log Output (Met Fix)

```
AnnotatieScherm: === ANNOTATIESCHERM OPENED ===
AnnotatieScherm: Received extra_row_pos: 0
AnnotatieScherm: Button leeftijd selected: A (tekst='adult', veld='leeftijd')
AnnotatieScherm: Button geslacht selected: M (tekst='man', veld='geslacht')
AnnotatieScherm: Button kleed selected: B (tekst='zomerkleed', veld='kleed')
AnnotatieScherm: === OK BUTTON PRESSED ===
AnnotatieScherm:   Group 'leeftijd': storeKey='leeftijd', waarde='A'
AnnotatieScherm:   Group 'geslacht': storeKey='geslacht', waarde='M'
AnnotatieScherm:   Group 'kleed': storeKey='kleed', waarde='B'
AnnotatieScherm: *** resultMap contains kleed = 'B' ***
AnnotatieScherm: Returning extra_row_pos: 0

TellingAnnotationHandler: === EXTRACTING ANNOTATIONS FROM MAP ===
TellingAnnotationHandler: Received annotation map: {leeftijd=A, geslacht=M, kleed=B}
TellingAnnotationHandler: rowPos=0, finalsList.size=1
TellingAnnotationHandler: finalRowTs at position 0: 1763890953
TellingAnnotationHandler: Found record at index 0 matching timestamp
TellingAnnotationHandler: map["kleed"] = 'B'
TellingAnnotationHandler: old.kleed = ''
TellingAnnotationHandler: newKleed (will be applied) = 'B'
TellingAnnotationHandler: === UPDATED RECORD AFTER COPY ===
TellingAnnotationHandler: updated.kleed = 'B'
TellingAnnotationHandler: updated.leeftijd = 'A'
TellingAnnotationHandler: updated.geslacht = 'M'
```

### Verwachte Datarecord Output (Met Fix)

```
Full Item: ServerTellingDataItem(
    idLocal=1, 
    tellingid=91, 
    soortid=307, 
    aantal=2, 
    richting=, 
    aantalterug=0, 
    richtingterug=, 
    sightingdirection=ZW, 
    lokaal=0, 
    aantal_plus=0, 
    aantalterug_plus=0, 
    lokaal_plus=0, 
    markeren=0, 
    markerenlokaal=0, 
    geslacht=M,          // ✅ NU INGEVULD!
    leeftijd=A,          // ✅ NU INGEVULD!
    kleed=B,             // ✅ NU INGEVULD!
    opmerkingen=, 
    trektype=, 
    teltype=, 
    location=, 
    height=, 
    tijdstip=1763890953, 
    groupid=1, 
    uploadtijdstip=2025-11-23 10:42:33, 
    totaalaantal=2
)
```

## Waarom Dit Niet Eerder Opviel

Het probleem was subtiel omdat:
1. ✅ Button clicks werkten perfect
2. ✅ resultMap werd correct opgebouwd
3. ✅ JSON serialisatie werkte
4. ✅ Intent werd correct verstuurd naar handler

**MAAR**: Het cruciale `extra_row_pos` veld ontbrak, waardoor de handler het juiste record niet kon vinden en de early return triggerde **voor** de debug logs in `applyAnnotationsToPendingRecord()`.

De nieuwe debug logs maken dit nu kristalhelder:
- `rowPos=-1` → CRITICAL WARNING
- `idx=-1` → CRITICAL ERROR met duidelijke uitleg

## Test Procedure

1. Pull de branch:
```bash
git fetch origin copilot/add-debug-logging-annotations-flow
git checkout copilot/add-debug-logging-annotations-flow
git pull origin copilot/add-debug-logging-annotations-flow
```

2. Build en installeer:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Test:
- Maak waarneming via spraak
- Tik Final log entry
- Selecteer leeftijd, geslacht, kleed
- Druk OK
- Check logcat en audit file

4. Verificatie:
- Logcat moet `rowPos=0` tonen (niet -1)
- Audit file moet kleed, leeftijd, geslacht bevatten
- Datarecord moet alle drie velden gevuld hebben

## Conclusie

De bug was een klassiek geval van "missing parameter in return Intent". De debug logging heeft het probleem perfect zichtbaar gemaakt, en de fix is simpel maar kritiek: bewaar en geef de `extra_row_pos` door.

**Zonder deze fix**: Annotaties werden 100% van de tijd genegeerd
**Met deze fix**: Annotaties worden correct toegepast aan het juiste record

Dit verklaart waarom ALLE annotatie velden (kleed, leeftijd, geslacht) leeg bleven - het was niet een probleem met één veld, maar met het hele annotatie toepassings mechanisme.
