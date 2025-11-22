# VT5 - Suggesties voor Toekomstige Verbeteringen

## Overzicht

Dit document bevat suggesties voor verdere verbeteringen aan de VT5 app, gebaseerd op de analyse uitgevoerd tijdens de metadata/annotatie velden fix.

---

## 🎯 Hoge Prioriteit Suggesties

### 1. Optionele Metadata Velden Toevoegen

**Context**: Verschillende metadata velden hebben geen UI elementen maar zijn wel beschikbaar in de server API.

**Suggestie**: Overweeg om de volgende velden toe te voegen aan MetadataScherm:

| Veld | Beschrijving | UI Type | Impact |
|------|-------------|---------|--------|
| `bewolkinghoogte` | Hoogte van bewolking (m) | Number input | Nuttig voor vlieganalyse |
| `duurneerslag` | Duur van neerslag (min) | Number input | Betere weer context |
| `tellersactief` | Aantal actieve tellers | Number input | Statistiek voor analyse |
| `tellersaanwezig` | Totaal aantal tellers | Number input | Statistiek voor analyse |

**Implementatie**:
```xml
<!-- scherm_metadata.xml -->
<com.google.android.material.textfield.TextInputLayout
    android:id="@+id/tilBewolkingHoogte"
    android:hint="Bewolkinghoogte (m)"
    ...>
    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/etBewolkingHoogte"
        android:inputType="number" />
</com.google.android.material.textfield.TextInputLayout>
```

**Effort**: 2-4 uur per veld (UI + mapping)

---

### 2. UI Validation Toevoegen

**Context**: Sommige velden kunnen invalid input krijgen (bijv. negatieve getallen, tekst in number fields).

**Suggestie**: Voeg input validation toe:

```kotlin
// MetadataFormManager.kt
fun validateTemperature(): Boolean {
    val temp = binding.etTemperatuur.text.toString().toIntOrNull()
    return when {
        temp == null -> {
            showError(binding.tilTemperatuur, "Voer een geldig getal in")
            false
        }
        temp < -50 || temp > 60 -> {
            showError(binding.tilTemperatuur, "Temperatuur moet tussen -50 en 60°C zijn")
            false
        }
        else -> {
            clearError(binding.tilTemperatuur)
            true
        }
    }
}

fun validateAllFields(): Boolean {
    return validateTemperature() &&
           validateZicht() &&
           validateLuchtdruk() &&
           telpostSelected()
}
```

**Voordelen**:
- Voorkomt invalid data uploads
- Betere gebruikerservaring
- Minder server errors

**Effort**: 1-2 dagen

---

### 3. Auto-Save Functionaliteit

**Context**: Als de app crasht of wordt afgesloten, gaat alle metadata input verloren.

**Suggestie**: Implementeer auto-save naar SharedPreferences:

```kotlin
// MetadataFormManager.kt
private var autoSaveJob: Job? = null

fun enableAutoSave(lifecycleScope: CoroutineScope) {
    // Setup text watchers for all fields
    binding.etTemperatuur.addTextChangedListener { 
        scheduleAutoSave(lifecycleScope)
    }
    // ... etc for all fields
}

private fun scheduleAutoSave(scope: CoroutineScope) {
    autoSaveJob?.cancel()
    autoSaveJob = scope.launch {
        delay(2000) // Debounce 2 seconds
        saveAllFields()
    }
}

private fun saveAllFields() {
    val prefs = context.getSharedPreferences("metadata_draft", Context.MODE_PRIVATE)
    prefs.edit {
        putString("temperature", binding.etTemperatuur.text.toString())
        putString("wind_direction", gekozenWindrichtingCode)
        // ... etc
    }
}

fun restoreFromDraft() {
    val prefs = context.getSharedPreferences("metadata_draft", Context.MODE_PRIVATE)
    binding.etTemperatuur.setText(prefs.getString("temperature", ""))
    // ... etc
}
```

**Voordelen**:
- Geen data verlies bij crash
- Betere user experience
- Snellere herstart

**Effort**: 1 dag

---

## 📊 Medium Prioriteit Suggesties

### 4. Metadata Templates

**Context**: Gebruikers moeten veel velden elke keer opnieuw invullen.

**Suggestie**: Voeg "Templates" functionaliteit toe:

```kotlin
// MetadataTemplate.kt
@Serializable
data class MetadataTemplate(
    val name: String,
    val windrichting: String?,
    val windkracht: String?,
    val typetelling: String?,
    // ... andere vaak-herhaalde velden
)

// MetadataFormManager.kt
fun saveAsTemplate(name: String) {
    val template = MetadataTemplate(
        name = name,
        windrichting = gekozenWindrichtingCode,
        windkracht = gekozenWindkracht,
        typetelling = gekozenTypeTellingCode
    )
    // Save to SharedPreferences or file
}

fun loadTemplate(template: MetadataTemplate) {
    template.windrichting?.let { 
        gekozenWindrichtingCode = it
        // Update UI
    }
    // ... etc
}
```

**UI**:
- "Templates" knop in MetadataScherm
- Dialog met lijst van saved templates
- "Save current as template" optie
- "Load template" dropdown

**Voordelen**:
- Snellere data entry
- Consistentere data
- Minder typewerk

**Effort**: 2-3 dagen

---

### 5. Weer Data Caching

**Context**: Weer data wordt opnieuw opgehaald bij elke sessie.

**Suggestie**: Cache weer data met expiry:

```kotlin
// WeatherCache.kt
data class CachedWeather(
    val data: WeatherData,
    val timestamp: Long,
    val location: Pair<Double, Double>
)

object WeatherCache {
    private const val CACHE_VALIDITY_MS = 30 * 60 * 1000L // 30 minutes
    
    fun get(lat: Double, lon: Double): WeatherData? {
        val cached = // load from SharedPreferences
        return if (cached != null && 
                   System.currentTimeMillis() - cached.timestamp < CACHE_VALIDITY_MS &&
                   locationMatch(lat, lon, cached.location)) {
            cached.data
        } else {
            null
        }
    }
    
    fun put(lat: Double, lon: Double, data: WeatherData) {
        // Save to SharedPreferences with timestamp
    }
}
```

**Voordelen**:
- Sneller prefill
- Minder API calls
- Works offline (kort)

**Effort**: 0.5-1 dag

---

### 6. Annotatie Presets

**Context**: Sommige annotaties worden vaak samen gebruikt.

**Suggestie**: Voeg snelkeuze presets toe:

**UI**:
```xml
<!-- activity_annotatie.xml -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:paddingTop="8dp">
    
    <Button
        android:id="@+id/btn_preset_adult_male"
        android:text="Adult ♂"
        android:onClick="onPresetClick" />
    
    <Button
        android:id="@+id/btn_preset_adult_female"
        android:text="Adult ♀"
        android:onClick="onPresetClick" />
    
    <Button
        android:id="@+id/btn_preset_juvenile"
        android:text="Juvenile"
        android:onClick="onPresetClick" />
</LinearLayout>
```

**Logic**:
```kotlin
// AnnotatieScherm.kt
private fun applyPreset(presetId: Int) {
    when (presetId) {
        R.id.btn_preset_adult_male -> {
            selectToggle("leeftijd", "ad")
            selectToggle("geslacht", "m")
        }
        R.id.btn_preset_adult_female -> {
            selectToggle("leeftijd", "ad")
            selectToggle("geslacht", "f")
        }
        R.id.btn_preset_juvenile -> {
            selectToggle("leeftijd", "juv")
        }
    }
}
```

**Voordelen**:
- Snellere annotatie
- Minder foutgevoelig
- Betere workflow

**Effort**: 1 dag

---

## 🔍 Lage Prioriteit Suggesties

### 7. Batch Annotatie

**Context**: Gebruiker moet meerdere waarnemingen individueel annoteren.

**Suggestie**: Voeg "Apply to multiple" functionaliteit toe:

- Multi-select in finals log
- "Annotate selected" knop
- Bulk apply dezelfde annotaties

**Effort**: 2-3 dagen

---

### 8. Export Functionaliteit

**Context**: Envelope backup files zijn JSON maar niet gebruiksvriendelijk.

**Suggestie**: Voeg export opties toe:

- CSV export voor Excel
- GPX export met GPS data
- PDF report met samenvatting

**Effort**: 3-5 dagen

---

### 9. Dark Mode

**Context**: App is lastig leesbaar in fel zonlicht.

**Suggestie**: 
- Implementeer Material Design 3 dark theme
- Auto-switch based on daytime
- Manual toggle in settings

**Effort**: 2-3 dagen

---

### 10. Offline Mode Indicator

**Context**: Gebruiker weet niet altijd of ze offline zijn.

**Suggestie**:
- Status bar indicator (online/offline)
- Badge op Afronden knop ("Will upload when online")
- Pending uploads counter

**Effort**: 1 dag

---

## 🏗️ Architectuur Suggesties

### 11. Repository Pattern

**Context**: Data access logica verspreid over verschillende classes.

**Suggestie**: Implementeer Repository pattern:

```kotlin
// MetadataRepository.kt
class MetadataRepository(
    private val localDataSource: MetadataLocalDataSource,
    private val remoteDataSource: MetadataRemoteDataSource
) {
    suspend fun saveMetadata(metadata: Metadata): Result<OnlineId> {
        // Save locally first
        localDataSource.save(metadata)
        
        // Then sync to server
        return try {
            val onlineId = remoteDataSource.upload(metadata)
            localDataSource.updateOnlineId(metadata.id, onlineId)
            Result.success(onlineId)
        } catch (e: Exception) {
            // Queue for retry
            localDataSource.markForRetry(metadata.id)
            Result.failure(e)
        }
    }
}
```

**Voordelen**:
- Betere separation of concerns
- Makkelijker te testen
- Eenvoudiger retry logic

**Effort**: 1-2 weken (major refactor)

---

### 12. Use Cases / Interactors

**Context**: Business logic mixed met UI logic.

**Suggestie**: Extract business logic naar use cases:

```kotlin
// StartTellingUseCase.kt
class StartTellingUseCase(
    private val metadataRepository: MetadataRepository,
    private val sessionManager: TellingSessionManager
) {
    suspend operator fun invoke(params: StartTellingParams): Result<TellingSession> {
        // Validate inputs
        if (!validateParams(params)) {
            return Result.failure(InvalidInputException())
        }
        
        // Build metadata
        val metadata = buildMetadata(params)
        
        // Save to server
        val onlineId = metadataRepository.saveMetadata(metadata).getOrElse {
            return Result.failure(it)
        }
        
        // Create session
        val session = TellingSession(
            id = generateId(),
            onlineId = onlineId,
            metadata = metadata
        )
        sessionManager.start(session)
        
        return Result.success(session)
    }
}
```

**Voordelen**:
- Testbare business logic
- Herbruikbaar
- Clean Architecture

**Effort**: 2-3 weken (major refactor)

---

### 13. ViewModel voor MetadataScherm

**Context**: Activity doet te veel (UI + business logic).

**Suggestie**: Migrate naar MVVM:

```kotlin
// MetadataViewModel.kt
class MetadataViewModel(
    private val startTellingUseCase: StartTellingUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<MetadataUiState>(MetadataUiState.Initial)
    val uiState: StateFlow<MetadataUiState> = _uiState.asStateFlow()
    
    fun startTelling(params: StartTellingParams) {
        viewModelScope.launch {
            _uiState.value = MetadataUiState.Loading
            
            startTellingUseCase(params)
                .onSuccess { session ->
                    _uiState.value = MetadataUiState.Success(session)
                }
                .onFailure { error ->
                    _uiState.value = MetadataUiState.Error(error.message)
                }
        }
    }
}

sealed class MetadataUiState {
    object Initial : MetadataUiState()
    object Loading : MetadataUiState()
    data class Success(val session: TellingSession) : MetadataUiState()
    data class Error(val message: String?) : MetadataUiState()
}
```

**Voordelen**:
- Configuration change survival
- Testable UI logic
- Modern Android architecture

**Effort**: 1-2 weken per screen

---

## 📱 UI/UX Suggesties

### 14. Material Design 3 Upgrade

**Context**: App gebruikt Material Design 2.

**Suggestie**: Upgrade naar Material 3:
- Modern look & feel
- Better accessibility
- Dynamic colors (Android 12+)

**Effort**: 1-2 weken

---

### 15. Haptic Feedback

**Context**: Geen tactiele feedback bij belangrijke acties.

**Suggestie**: Voeg haptic feedback toe:
```kotlin
// HapticFeedbackHelper.kt
fun performHapticFeedback(view: View, type: Int = HapticFeedbackConstants.CONFIRM) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(type)
    }
}

// Gebruik:
binding.btnVerder.setOnClickListener { 
    performHapticFeedback(it, HapticFeedbackConstants.CONFIRM)
    // ... rest of logic
}
```

**Effort**: 0.5 dag

---

### 16. Animaties

**Context**: Scherm transitions zijn abrupt.

**Suggestie**: Voeg subtiele animaties toe:
- Fade in/out voor dialogs
- Slide transitions tussen screens
- Loading skeletons

**Effort**: 1-2 dagen

---

## 🧪 Testing Suggesties

### 17. Unit Tests

**Context**: Geen unit tests voor business logic.

**Suggestie**: Start met critical path testing:

```kotlin
// TellingStarterTest.kt
class TellingStarterTest {
    
    @Test
    fun `startTelling with valid inputs should return success`() = runTest {
        // Given
        val formManager = mockk<MetadataFormManager> {
            every { getTellers() } returns "John Doe"
            every { getOpmerkingen() } returns "Test remarks"
            // ... etc
        }
        
        val starter = TellingStarter(context, binding, formManager, prefs)
        
        // When
        val result = starter.startTelling(
            telpostId = "12345",
            username = "user",
            password = "pass",
            snapshot = mockSnapshot
        )
        
        // Then
        assertTrue(result.success)
        assertNotNull(result.onlineId)
    }
}
```

**Coverage Target**: 
- Business logic: 80%+
- UI logic: 40%+

**Effort**: 1-2 weken initial setup, ongoing

---

### 18. Integration Tests

**Context**: Geen automated tests voor data flow.

**Suggestie**: Test complete flows:

```kotlin
// TellingFlowTest.kt
@Test
fun `complete telling flow should sync correctly`() = runTest {
    // Start telling
    val session = startTelling(testMetadata)
    
    // Add observations
    addObservation(session, speciesId = "1001", count = 5)
    addObservation(session, speciesId = "1002", count = 3)
    
    // Annotate one
    annotateObservation(index = 0, annotations = testAnnotations)
    
    // Finish
    val envelope = finishTelling(session)
    
    // Verify
    assertEquals(2, envelope.nrec.toInt())
    assertEquals("5", envelope.data[0].aantal)
    assertEquals("test_value", envelope.data[0].leeftijd)
}
```

**Effort**: 2-3 weken

---

## 🔒 Security Suggesties

### 19. Credentials Rotation

**Context**: Passwords stored indefinitely.

**Suggestie**: 
- Implement token refresh
- Session timeout
- Re-authentication after N days

**Effort**: 1 week

---

### 20. Data Encryption at Rest

**Context**: Backups niet encrypted.

**Suggestie**: Encrypt SAF backup files:

```kotlin
// EncryptedBackupManager.kt
class EncryptedBackupManager(
    private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    fun writeEncryptedBackup(data: String, fileName: String) {
        val encryptedFile = EncryptedFile.Builder(
            context,
            File(context.filesDir, fileName),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        
        encryptedFile.openFileOutput().use { 
            it.write(data.toByteArray())
        }
    }
}
```

**Effort**: 2-3 dagen

---

## 📊 Analytics Suggesties

### 21. Crash Reporting

**Context**: Geen insight in crashes in production.

**Suggestie**: Integrate Firebase Crashlytics:
- Automatic crash reporting
- Custom logs
- Non-fatal error tracking

**Effort**: 1 dag

---

### 22. Performance Monitoring

**Context**: Geen metingen van app performance.

**Suggestie**: 
- Track startup time
- Monitor memory usage
- Measure frame drops

**Effort**: 2-3 dagen

---

## 🎓 Code Quality Suggesties

### 23. Detekt Integration

**Context**: Geen static code analysis.

**Suggestie**: Add Detekt:

```kotlin
// build.gradle.kts
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.4"
}

detekt {
    config = files("config/detekt/detekt.yml")
    buildUponDefaultConfig = true
}
```

**Effort**: 0.5 dag setup, ongoing fixes

---

### 24. KtLint Integration

**Context**: Code style niet geautomatiseerd.

**Suggestie**: Add KtLint:

```kotlin
// build.gradle.kts
plugins {
    id("org.jlleitschuh.gradle.ktlint") version "12.0.3"
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
}
```

**Effort**: 0.5 dag setup, ongoing

---

### 25. Documentation

**Context**: Beperkte inline documentation.

**Suggestie**: 
- KDoc voor alle public APIs
- Architecture Decision Records (ADRs)
- Setup instructions
- Contribution guidelines

**Effort**: Ongoing

---

## 🚀 Prioriteit Matrix

| Suggestie | Impact | Effort | Priority | Timeframe |
|-----------|--------|--------|----------|-----------|
| UI Validation | High | Low | **P0** | Week 1 |
| Auto-Save | High | Low | **P0** | Week 1 |
| Weer Caching | Medium | Low | **P1** | Week 2 |
| Annotatie Presets | Medium | Low | **P1** | Week 2 |
| Metadata Templates | Medium | Medium | **P2** | Week 3-4 |
| Optionele Velden | Low | Medium | **P2** | Week 3-4 |
| Crash Reporting | High | Low | **P1** | Week 2 |
| Unit Tests | High | High | **P2** | Ongoing |
| Repository Pattern | High | Very High | **P3** | Month 2-3 |
| MVVM Migration | High | Very High | **P3** | Month 2-3 |

---

## 📝 Implementation Notes

### Voor Elke Suggestie:
1. Create GitHub issue
2. Design document (if major change)
3. PR met incremental changes
4. Code review
5. Testing
6. Documentation update
7. Deploy

### Best Practices:
- Een feature per branch
- Small, focused PRs
- Write tests first (TDD)
- Update docs samen met code
- Get feedback early

---

**Document Versie**: 1.0  
**Datum**: 2025-11-22  
**Status**: Voor overweging

Deze suggesties zijn niet verplicht maar kunnen de app verder verbeteren op vlak van gebruiksvriendelijkheid, onderhoudbaarheid, en professionaliteit.
