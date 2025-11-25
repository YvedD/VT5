# VT5 Codebase Audit Rapport

**Datum:** 25 november 2025
**Branch:** main
**Doel:** Identificeren van ongebruikte functies, variabelen, en bestanden

---

## Samenvatting

| Categorie | Aantal |
|-----------|--------|
| Potentieel ongebruikte private functies | 86 |
| Potentieel ongebruikte private variabelen | 29 |
| Potentieel ongebruikte Kotlin bestanden | 2 |
| Potentieel ongebruikte XML layouts | 1 |
| Potentieel ongebruikte drawables | 2 |

---

## 1. Potentieel Ongebruikte Private Functies (86)

> **Let op:** Deze functies worden 2x of minder aangetroffen in de codebase (1x definitie + mogelijk 1x call).
> Functies die intern worden aangeroepen door andere private functies kunnen vals positief zijn.

### VT5App.kt
- L98: `preloadDataAsync()` - Wordt wel degelijk aangeroepen in onCreate()

### core/app/HourlyAlarmManager.kt
- L113: `cancelAlarm()` - Mogelijk nodig voor alarm lifecycle
- L130: `isTellingActive()` - Intern gebruikt

### features/alias/AliasIndexWriter.kt
- L421: `writeStringToSaFOverwrite()` - Hulpfunctie

### features/alias/AliasManager.kt
- L553: `scheduleBatchWrite()` - **ENIGE REF = DEFINITIE - NIET GEBRUIKT**

### features/alias/helpers/AliasSeedGenerator.kt
- L135: `parseSiteSpeciesIds()` - Intern helper
- L184: `loadSpeciesMap()` - Intern helper
- L234: `buildSpeciesList()` - Intern helper

### features/metadata/helpers/MetadataFormManager.kt
- L113: `setupTellersAutoSave()` - Callback setup
- L241: `openDatePicker()` - UI handler
- L265: `openTimeSpinnerDialog()` - UI handler

### features/metadata/helpers/WeatherDataFetcher.kt
- L89: `applyWeatherToForm()` - Callback gebruikt
- L144: `markWeatherAutoApplied()` - Intern helper

### features/metadata/ui/MetadataScherm.kt
- L117: `loadEssentialData()` - Activity lifecycle
- L228: `ensureLocationPermissionThenFetch()` - Click handler
- L270: `onVerderClicked()` - Click handler
- L307: `startTellingAndOpenSoortSelectie()` - Navigation

### features/opstart/helpers/AliasIndexManager.kt
- L241: `sha256Hex()` - Crypto helper

### features/opstart/helpers/ServerDataDownloadManager.kt
- L145: `ensureAnnotationsFile()` - Download helper

### features/opstart/ui/InstallatieScherm.kt
- L102: `initUi()` - Activity init
- L112: `wireClicks()` - Click handlers setup
- L263: `handleDownloadServerData()` - Click handler
- L413: `navigateToOpstart()` - Navigation
- L428: `restoreCreds()` - Startup helper
- L436: `refreshSafStatus()` - UI update

### features/opstart/usecases/ServerJsonDownloader.kt
- L130: `httpGetJsonBasicAuth()` - Network helper
- L167: `parseJsonOrThrow()` - Parse helper
- L185: `writeBin()` - File helper
- L198: `datasetKindFor()` - Mapping helper
- L214: `makeVt5BinHeader()` - Header builder

### features/opstart/usecases/TrektellenAuth.kt
- L70: `prettyJsonOrRaw()` - Debug helper

### features/soort/ui/SoortSelectieScherm.kt
- L104: `setupAdapters()` - Activity init
- L182: `setupListeners()` - Listeners setup
- L215: `loadData()` - Data loading
- L413: `updateSuggestions()` - Search handler

### features/speech/AliasPriorityMatcher.kt
- L130: `tryExactMatch()` - Match helper

### features/speech/DutchPhonemizer.kt
- L124: `phonemizeUncached()` - Phonemization

### features/speech/SpeechRecognitionManager.kt
- L266: `createRecognitionListener()` - Speech setup
- L459: `quickPartialParse()` - Parsing helper
- L590: `calculateSimilarity()` - Matching helper
- L655: `splitEmbeddedNumbers()` - Number parsing
- L671: `findNextNumber()` - Number parsing
- L688: `isDigitOne()` - Number helper
- L726: `singularizeNl()` - Dutch linguistics

### features/speech/VolumeKeyHandler.kt
- L128: `handleKeyCodes()` - Key handling

### features/speech/helpers/FastPathMatcher.kt
- L97: `disambiguateSpecies()` - Match disambiguation
- L118: `validateMatch()` - Match validation

### features/speech/helpers/HeavyPathMatcher.kt
- L140: `extractMatcherScore()` - Score extraction

### features/speech/helpers/PendingMatchBuffer.kt
- L102: `ensureWorkerRunning()` - Worker management
- L119: `runWorkerLoop()` - Worker loop
- L133: `processPendingItem()` - Item processing
- L152: `handleTimeout()` - Timeout handler
- L169: `handleSuccess()` - Success handler

### features/speech/helpers/SpeechMatchLogger.kt
- L62: `buildLogEntry()` - Log builder
- L84: `buildCandidateLog()` - Log builder
- L109: `buildMultiMatchLog()` - Log builder
- L123: `writeToSAFAsync()` - SAF writer
- L163: `appendToLogFile()` - File append
- L186: `fallbackRewriteLogFile()` - Fallback writer

### features/telling/AliasEditor.kt
- L40: `validateAlias()` - Validation
- L56: `cleanAliasForStorage()` - Sanitization

### features/telling/AnnotatieScherm.kt
- L213: `populateAllColumnsFromCache()` - UI population
- L336: `addTagToRemarks()` - Tag handler
- L387: `prefillCountFields()` - Field prefill
- L414: `updateCountFieldLabels()` - Label update

### features/telling/TellingAlarmHandler.kt
- L81: `checkAndTriggerAlarm()` - Alarm check
- L112: `calculateDelayUntilNextCheck()` - Delay calculation

### features/telling/TellingAnnotationHandler.kt
- L93: `handleAnnotationResult()` - Result handler
- L300: `getCurrentTimestamp()` - Timestamp helper

### features/telling/TellingMatchResultHandler.kt
- L57: `handleAutoAcceptMatch()` - Match handler
- L68: `handleAutoAcceptAddPopup()` - Popup handler
- L80: `handleMultiMatch()` - Multi-match handler
- L88: `extractCountFromHypothesis()` - Count extraction

### features/telling/TellingScherm.kt
- L315: `setupHelperCallbacks()` - Callback setup
- L414: `setupUiWithManager()` - UI setup
- L444: `handlePartialTap()` - Tap handler
- L491: `handleFinalTap()` - Tap handler
- L500: `handleAfrondenWithConfirmation()` - Confirm handler
- L639: `initializeSpeechRecognition()` - Speech init
- L653: `handleSpeechHypotheses()` - Speech handler
- L736: `updateLogsUi()` - UI update
- L901: `openSoortSelectieForAdd()` - Navigation

### hoofd/HoofdActiviteit.kt
- L85: `shutdownAndExit()` - Exit handler
- L104: `finishAndRemoveTaskCompat()` - Exit helper
- L112: `setupAlarmSection()` - Alarm UI setup

### net/StartTellingApi.kt
- L86: `nowAsSqlLike()` - Date formatting

---

## 2. Potentieel Ongebruikte Private Variabelen (29)

### core/secure/CredentialsStore.kt
- L13: `masterKey` - **VALS POSITIEF**: Wordt gebruikt in lazy getter

### features/alias/AliasManager.kt
- L68: `indexLoadMutex` - Synchronization mutex
- L73: `masterWriteMutex` - Synchronization mutex

### features/metadata/ui/MetadataScherm.kt
- L55: `dataLoaded` - State tracking
- L69: `requestLocationPerms` - Permission launcher
- L291: `soortSelectieLauncher` - Activity result launcher

### features/opstart/usecases/ServerJsonDownloader.kt
- L41: `jsonLenient` - JSON parser config

### features/opstart/usecases/TrektellenAuth.kt
- L24: `jsonParser` - JSON parser

### features/speech/AliasMatcher.kt
- L47: `loadMutex` - Synchronization mutex
- L53: `WHITESPACE` - Regex pattern

### features/speech/AliasSpeechParser.kt
- L46: `fastPathMatcher` - Matcher instance

### features/speech/DutchPhonemizer.kt
- L37: `multiCharRaw` - Character mapping
- L66: `singleChar` - Character mapping

### features/speech/NumberPatterns.kt
- L52: `numberCologneCodes` - Phonetic codes
- L60: `numberPhonemePatterns` - Phoneme patterns

### features/speech/PhoneticModels.kt
- L35: `firstCodeIndex` - Index tracking

### features/speech/SpeechParsingBuffer.kt
- L12: `maxSize` - Buffer config
- L13: `expiryTimeMs` - Expiry config

### features/telling/AnnotatieScherm.kt
- L64: `leeftijdBtnIds` - Button ID array
- L68: `geslachtBtnIds` - Button ID array
- L71: `kleedBtnIds` - Button ID array
- L75: `locationBtnIds` - Button ID array
- L79: `heightBtnIds` - Button ID array

### features/telling/SpeciesTileAdapter.kt
- L17: `onTileClick` - Click callback

### features/telling/SpeechLogAdapter.kt
- L51: `fmt` - Date formatter

### features/telling/TellingAfrondHandler.kt
- L44: `PRETTY_JSON` - JSON config

### features/telling/TellingLogManager.kt
- L20: `RE_TRIM_RAW_NUMBER` - Regex pattern

### features/telling/TellingScherm.kt
- L144: `PARTIAL_UI_DEBOUNCE_MS` - Debounce constant

### utils/weather/TextUtils.kt
- L20: `TRAILING_NUMBER` - Regex pattern

---

## 3. Potentieel Ongebruikte Kotlin Bestanden (2)

### `features/alias/AliasPrecomputeWorker.kt`
- **Bevat:** `AliasPrecomputeWorker` class
- **Status:** WorkManager worker die niet wordt gequeued
- **Analyse:** Deze worker class is gedefinieerd maar wordt nergens gequeued via `WorkManager.enqueue()`. Mogelijk legacy code of gepland voor toekomstig gebruik.
- **Aanbeveling:** ⚠️ Controleer of deze worker nog nodig is

### `features/metadata/model/MetadataModels.kt`
- **Bevat:** `MetadataHeader` data class
- **Status:** Model class die niet wordt gebruikt
- **Analyse:** Deze data class is gedefinieerd maar wordt nergens geïnstantieerd of als type gebruikt.
- **Aanbeveling:** ⚠️ Kan verwijderd worden als niet meer nodig

---

## 4. Potentieel Ongebruikte XML Layouts (1)

### `item_speech_log_secondary.xml`
- **Status:** Niet gebruikt in ViewBinding of R.layout references
- **Analyse:** Dit layout bestand wordt niet geïnflate in de huidige codebase
- **Aanbeveling:** ⚠️ Controleer of dit voor toekomstige features gepland was

---

## 5. Potentieel Ongebruikte Drawables (2)

### `vt5_btn_shape_normal.xml`
- **Status:** Niet gerefereerd in andere XML of Kotlin bestanden
- **Analyse:** Was mogelijk onderdeel van een oudere button selector
- **Aanbeveling:** ✅ Kan veilig verwijderd worden

### `vt5_btn_shape_pressed.xml`
- **Status:** Niet gerefereerd in andere XML of Kotlin bestanden
- **Analyse:** Was mogelijk onderdeel van een oudere button selector
- **Aanbeveling:** ✅ Kan veilig verwijderd worden

---

## Belangrijke Opmerkingen

### Vals Positieven
Veel van de "ongebruikte" functies zijn **vals positieven** omdat:

1. **Private functies worden intern aangeroepen** - Een functie kan private zijn en vanuit een andere private functie worden aangeroepen
2. **Lifecycle callbacks** - Functies als `onCreate`, `onResume` worden door het Android framework aangeroepen
3. **Activity result launchers** - Variabelen die callbacks registreren
4. **Mutexes en synchronization primitives** - Worden impliciet gebruikt door coroutines

### Echte Kandidaten voor Verwijdering

Na grondige analyse zijn de volgende items **echt ongebruikt**:

| Type | Bestand | Item | Veilig te verwijderen? |
|------|---------|------|------------------------|
| Kotlin | `AliasManager.kt` | `scheduleBatchWrite()` | ✅ Ja |
| Kotlin | `AliasPrecomputeWorker.kt` | Hele bestand | ⚠️ Controleren |
| Kotlin | `MetadataModels.kt` | `MetadataHeader` | ⚠️ Controleren |
| XML | `item_speech_log_secondary.xml` | Hele bestand | ⚠️ Controleren |
| Drawable | `vt5_btn_shape_normal.xml` | Hele bestand | ✅ Ja |
| Drawable | `vt5_btn_shape_pressed.xml` | Hele bestand | ✅ Ja |

---

## Aanbevelingen

1. **Gebruik Android Studio's "Unused declaration" inspections** voor meer nauwkeurige detectie
2. **Voer een build uit** met strikte warnings om compiletime ongebruikte code te detecteren
3. **Controleer git history** van verdachte bestanden om te zien wanneer ze voor het laatst zijn aangepast
4. **Test na verwijdering** om te controleren of core functionaliteit intact blijft

---

---

## 6. Potentieel Ongebruikte String Resources

### strings.xml (21 mogelijk ongebruikt van 119 totaal)

| String Name | Status |
|-------------|--------|
| `annotatie_none` | ⚠️ Mogelijk ongebruikt |
| `annotatie_opmerkingen_hint` | ⚠️ Mogelijk ongebruikt |
| `annotatie_opmerkingen_label` | ⚠️ Mogelijk ongebruikt |
| `annotation_local` | ⚠️ Mogelijk ongebruikt |
| `app_name` | **VALS POSITIEF** - Gebruikt in AndroidManifest.xml |
| `btn_install_start` | ⚠️ Mogelijk ongebruikt |
| `dialog_choose_species` | ⚠️ Mogelijk ongebruikt |
| `dialog_finish_success` | ⚠️ Mogelijk ongebruikt |
| `dlg_busy_titel` | ⚠️ Mogelijk ongebruikt |
| `dlg_cancel` | ⚠️ Mogelijk ongebruikt |
| `dlg_disable_next` | ⚠️ Mogelijk ongebruikt |
| `metadata_cloudcover_format` | ⚠️ Mogelijk ongebruikt |
| `metadata_error_no_location` | ⚠️ Mogelijk ongebruikt |
| `metadata_error_unclear_msg` | ⚠️ Mogelijk ongebruikt |
| `metadata_error_unclear_title` | ⚠️ Mogelijk ongebruikt |
| `metadata_loading` | ⚠️ Mogelijk ongebruikt |
| `msg_annotations_not_available` | ⚠️ Mogelijk ongebruikt |
| `status_alias_rebuild_complete` | ⚠️ Mogelijk ongebruikt |
| `status_alias_rebuild_error` | ⚠️ Mogelijk ongebruikt |
| `telling_alias_saved_observation` | ⚠️ Mogelijk ongebruikt |
| `tile_count` | ⚠️ Mogelijk ongebruikt |

### strings_metadata.xml (1 mogelijk ongebruikt van 19 totaal)

| String Name | Status |
|-------------|--------|
| `meta_weer_titel` | ⚠️ Mogelijk ongebruikt |

---

## Gedetailleerde Analyse van Echte Kandidaten

### 1. `AliasManager.kt` → `scheduleBatchWrite()`
```kotlin
private fun scheduleBatchWrite(context: Context, saf: SaFStorageHelper) {
    // Functie is gedefinieerd maar wordt nergens aangeroepen
}
```
**Conclusie:** ✅ Veilig te verwijderen - functie wordt niet aangeroepen.

### 2. `AliasPrecomputeWorker.kt`
Dit is een WorkManager worker class die:
- Wel gedefinieerd is
- Nergens wordt gequeued via `WorkManager.enqueue()`
- Mogelijk was bedoeld voor background alias precomputing

**Conclusie:** ⚠️ Controleren met ontwikkelaar - was dit gepland voor toekomstige functionaliteit?

### 3. `MetadataModels.kt` → `MetadataHeader`
Data class die metadata velden bevat maar nergens wordt gebruikt:
- Alle velden zijn Strings
- Bevat weer-gerelateerde data
- Lijkt bedoeld voor JSON export

**Conclusie:** ⚠️ Controleren - dit lijkt een niet-afgeronde feature te zijn.

### 4. `item_speech_log_secondary.xml`
Een layout voor een secundaire speech log item:
- Geen ViewBinding referenties gevonden
- Mogelijk voor toekomstige multi-log view

**Conclusie:** ⚠️ Controleren met ontwikkelaar.

### 5. Button Shape Drawables
`vt5_btn_shape_normal.xml` en `vt5_btn_shape_pressed.xml`:
- Werden vervangen door nieuwe selector states
- Huidige selector gebruikt: `vt5_btn_checked`, `vt5_btn_pressed`, `vt5_btn_unchecked`

**Conclusie:** ✅ Veilig te verwijderen - legacy drawables.

---

## Volledige Statistieken

| Categorie | Totaal | Potentieel Ongebruikt | Percentage |
|-----------|--------|----------------------|------------|
| Kotlin bestanden | 99 | 2 | 2.0% |
| Private functies | ~537 | 86 | ~16% |
| Private variabelen | ~200 | 29 | ~15% |
| XML layouts | 16 | 1 | 6.3% |
| Drawables | 12 | 2 | 16.7% |
| String resources | 138 | 22 | 15.9% |

---

## Actieplan

### Fase 1: Veilige Verwijderingen (Low Risk)
1. Verwijder `vt5_btn_shape_normal.xml`
2. Verwijder `vt5_btn_shape_pressed.xml`
3. Verwijder `scheduleBatchWrite()` uit `AliasManager.kt`

### Fase 2: Nader Onderzoek Vereist
1. Controleer of `AliasPrecomputeWorker.kt` nog nodig is
2. Controleer of `MetadataModels.kt` nog nodig is
3. Controleer of `item_speech_log_secondary.xml` nog nodig is
4. Beoordeel ongebruikte strings

### Fase 3: Validatie
1. Build project na verwijderingen
2. Run alle tests
3. Test core functionaliteit handmatig

---

*Dit rapport is gegenereerd via statische analyse en kan vals positieven bevatten.*
