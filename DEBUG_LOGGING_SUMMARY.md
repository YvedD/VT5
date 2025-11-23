# Debug Logging voor Kleed Annotatie Flow

## Overzicht
Deze PR voegt uitgebreide debug logging toe aan de hele kleed annotatie flow om te achterhalen waarom kleed codes (B/W/M/F/L/D/E/I) NIET verschijnen in het `ServerTellingDataItem.kleed` veld na het opslaan van een annotatie.

## Wijzigingen per Bestand

### 1. AnnotationsManager.kt
**Locatie**: `app/src/main/java/com/yvesds/vt5/features/annotation/AnnotationsManager.kt`

**Toegevoegde Logging** (regels 203-215):
- Logt wanneer kleed options uit annotations.json worden geladen
- Toont ALLE kleed options met:
  - `tekst`: de knoptekst (bijv. "zomerkleed")
  - `veld`: het doelveld (moet "kleed" zijn)
  - `waarde`: de code (bijv. "B")
- Waarschuwing als geen kleed options gevonden worden

**Voorbeeld Output**:
```
AnnotationsManager: === KLEED OPTIONS LOADED ===
AnnotationsManager: Total kleed options: 8
AnnotationsManager:   kleed[0]: tekst='zomerkleed', veld='kleed', waarde='B'
AnnotationsManager:   kleed[1]: tekst='winterkleed', veld='kleed', waarde='W'
...
AnnotationsManager: === END KLEED OPTIONS ===
```

### 2. AnnotatieScherm.kt
**Locatie**: `app/src/main/java/com/yvesds/vt5/features/telling/AnnotatieScherm.kt`

**Toegevoegde Logging**:

#### A. Button Click Tracking (regels 252-268)
Wanneer een annotatie button wordt aangeklikt:
- Logt de group naam (bijv. "kleed")
- Logt de waarde die opgeslagen zou moeten worden
- Waarschuwing als de tag niet correct is gezet

**Voorbeeld Output**:
```
AnnotatieScherm: Button kleed selected: B (tekst='zomerkleed', veld='kleed')
```

#### B. OK Button Handler (regels 102-171)
Wanneer OK wordt gedrukt:
- Logt ELKE geselecteerde optie per groep
- Toont complete resultMap met ALLE velden
- Specifieke highlight of kleed aanwezig is
- Toont de JSON payload die naar TellingAnnotationHandler wordt gestuurd

**Voorbeeld Output**:
```
AnnotatieScherm: === OK BUTTON PRESSED ===
AnnotatieScherm:   Group 'kleed': storeKey='kleed', waarde='B'
AnnotatieScherm: === COMPLETE RESULT MAP ===
AnnotatieScherm:   'kleed' = 'B'
AnnotatieScherm:   'leeftijd' = 'A'
AnnotatieScherm: *** resultMap contains kleed = 'B' ***
AnnotatieScherm: Payload JSON: {"kleed":"B","leeftijd":"A"}
AnnotatieScherm: === END RESULT MAP ===
```

### 3. TellingAnnotationHandler.kt
**Locatie**: `app/src/main/java/com/yvesds/vt5/features/telling/TellingAnnotationHandler.kt`

**Toegevoegde Logging**:

#### A. Annotation Extractie (regels 170-182)
Wanneer annotaties uit de map worden gehaald:
- Toont de complete ontvangen map
- Toont welk record wordt bijgewerkt (index + timestamp)
- Specifiek voor kleed: logt map["kleed"], old.kleed, en newKleed

**Voorbeeld Output**:
```
TellingAnnotationHandler: === EXTRACTING ANNOTATIONS FROM MAP ===
TellingAnnotationHandler: Received annotation map: {kleed=B, leeftijd=A}
TellingAnnotationHandler: Applying to record at index 0 (tijdstip=1763845194)
TellingAnnotationHandler: map["kleed"] = 'B'
TellingAnnotationHandler: old.kleed = ''
TellingAnnotationHandler: newKleed (will be applied) = 'B'
```

#### B. Record Update (regels 238-244)
Na de copy() operatie:
- Toont het bijgewerkte record met alle annotatie velden
- Specifiek: updated.kleed, updated.leeftijd, updated.geslacht, updated.teltype

**Voorbeeld Output**:
```
TellingAnnotationHandler: === UPDATED RECORD AFTER COPY ===
TellingAnnotationHandler: updated.kleed = 'B'
TellingAnnotationHandler: updated.leeftijd = 'A'
TellingAnnotationHandler: updated.geslacht = ''
TellingAnnotationHandler: updated.teltype = ''
TellingAnnotationHandler: === END UPDATED RECORD ===
TellingAnnotationHandler: Applied annotations to pendingRecords[0]: {kleed=B, leeftijd=A}
```

## Test Procedure

### Stap 1: Voorbereiding
1. Build de app met deze branch:
   ```bash
   git fetch origin copilot/add-debug-logging-annotations-flow
   git checkout copilot/add-debug-logging-annotations-flow
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Start logcat filtering:
   ```bash
   adb logcat | grep -E "AnnotatieScherm|TellingAnnotationHandler|AnnotationsManager"
   ```

### Stap 2: Test Uitvoeren
1. Open de app
2. **Verwachte log**: Kleed options laden
3. Start een nieuwe telling (MetadataScherm → TellingScherm)
4. Maak een waarneming via spraak (bijv. "Jan-van-Gent")
5. Tik op de "Final" log regel om AnnotatieScherm te openen
6. **Verwachte log**: AnnotatieScherm geopend
7. Klik op "zomerkleed" button (eerste kleed button)
8. **Verwachte log**: "Button kleed selected: B"
9. Druk OK
10. **Verwachte logs**: 
    - resultMap contains kleed=B
    - Extracting annotations from map
    - newKleed = 'B'
    - updated.kleed = 'B'

### Stap 3: Verificatie
1. Check logcat output - alle verwachte logs aanwezig?
2. Open audit file: `Documents/VT5/exports/YYYYMMDD_HHMMSS_audit_XX.txt`
3. Zoek naar `"kleed": "B"` in het datarecord

## Diagnostiek: Wat te Doen Als...

### Scenario 1: Geen "Button kleed selected" log
**Probleem**: Button click wordt niet geregistreerd
**Mogelijk oorzaken**:
- Button listener niet correct gezet
- AnnotationOption niet in button.tag
- onGroupButtonClicked wordt niet aangeroepen

### Scenario 2: "Button kleed selected" WEL, maar kleed NIET in resultMap
**Probleem**: Waarde gaat verloren tussen button click en OK
**Mogelijk oorzaken**:
- Button wordt ge-uncheck voor OK
- groupButtons map wordt niet correct bijgehouden
- selectedOpt.waarde is null of leeg

### Scenario 3: Kleed WEL in resultMap, maar NIET in map["kleed"]
**Probleem**: JSON serialisatie/deserialisatie issue
**Mogelijk oorzaken**:
- Verkeerde storeKey gebruikt
- Intent data passing probleem
- JSON parsing fout in TellingAnnotationHandler

### Scenario 4: map["kleed"] WEL aanwezig, maar updated.kleed is leeg
**Probleem**: copy() operatie werkt niet correct
**Mogelijk oorzaken**:
- newKleed wordt niet correct gezet (Elvis operator probleem)
- copy() parameters in verkeerde volgorde
- Field naam mismatch in ServerTellingDataItem

## Verwachte Complete Log Sequence

```
D/AnnotationsManager: === KLEED OPTIONS LOADED ===
D/AnnotationsManager: Total kleed options: 8
D/AnnotationsManager:   kleed[0]: tekst='zomerkleed', veld='kleed', waarde='B'
D/AnnotationsManager:   kleed[1]: tekst='winterkleed', veld='kleed', waarde='W'
D/AnnotationsManager:   kleed[2]: tekst='man', veld='kleed', waarde='M'
D/AnnotationsManager:   kleed[3]: tekst='vrouw', veld='kleed', waarde='F'
D/AnnotationsManager:   kleed[4]: tekst='licht', veld='kleed', waarde='L'
D/AnnotationsManager:   kleed[5]: tekst='donker', veld='kleed', waarde='D'
D/AnnotationsManager:   kleed[6]: tekst='eclips', veld='kleed', waarde='E'
D/AnnotationsManager:   kleed[7]: tekst='intermediar', veld='kleed', waarde='I'
D/AnnotationsManager: === END KLEED OPTIONS ===
D/AnnotationsManager: Annotations loaded and cached: 8 groups

[... gebruiker klikt op zomerkleed button ...]

D/AnnotatieScherm: Button kleed selected: B (tekst='zomerkleed', veld='kleed')

[... gebruiker klikt OK ...]

D/AnnotatieScherm: === OK BUTTON PRESSED ===
D/AnnotatieScherm:   Group 'kleed': storeKey='kleed', waarde='B'
D/AnnotatieScherm: === COMPLETE RESULT MAP ===
D/AnnotatieScherm:   'kleed' = 'B'
D/AnnotatieScherm: *** resultMap contains kleed = 'B' ***
D/AnnotatieScherm: Payload JSON: {"kleed":"B"}
D/AnnotatieScherm: === END RESULT MAP ===

D/TellingAnnotationHandler: === EXTRACTING ANNOTATIONS FROM MAP ===
D/TellingAnnotationHandler: Received annotation map: {kleed=B}
D/TellingAnnotationHandler: Applying to record at index 0 (tijdstip=1763845194)
D/TellingAnnotationHandler: map["kleed"] = 'B'
D/TellingAnnotationHandler: old.kleed = ''
D/TellingAnnotationHandler: newKleed (will be applied) = 'B'
D/TellingAnnotationHandler: === UPDATED RECORD AFTER COPY ===
D/TellingAnnotationHandler: updated.kleed = 'B'
D/TellingAnnotationHandler: updated.leeftijd = ''
D/TellingAnnotationHandler: updated.geslacht = ''
D/TellingAnnotationHandler: updated.teltype = ''
D/TellingAnnotationHandler: === END UPDATED RECORD ===
D/TellingAnnotationHandler: Applied annotations to pendingRecords[0]: {kleed=B}
```

## Volgende Stappen

Na deze debug logging hebben we:
1. **Exact zicht** waar de kleed waarde verloren gaat
2. **Concrete data** om de bug te fixen
3. **Verificatie** dat de fix werkt via dezelfde logs

Zodra de logs een duidelijk probleem aanwijzen, kan de volgende PR de daadwerkelijke fix implementeren.

## Git Commando's

```bash
# Fetch en checkout branch
git fetch origin copilot/add-debug-logging-annotations-flow
git checkout copilot/add-debug-logging-annotations-flow

# Pull latest changes
git pull origin copilot/add-debug-logging-annotations-flow

# Als je lokaal wilt testen
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
