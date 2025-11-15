# VT5 — GitHub Copilot Instructions

## Project Overview

VT5 is a blazingly fast, intuitive Android application for tracking bird migration observations via voice input. The app is designed for use in the field by bird watchers to log observations and automatically submit them to www.trektellen.nl (a Dutch bird migration tracking platform).

### Core Mission
- **Speed & Performance**: The app must be lightning-fast and responsive, even in field conditions
- **Voice-First Interface**: Primary input method is voice recognition (Dutch language)
- **Intuitive UX**: Minimize friction and cognitive load for field observations
- **Offline-First**: Core functionality must work without internet connection
- **Data Integrity**: Reliable synchronization with trektellen.nl backend

## Architecture & Technology Stack

### Platform & Language
- **Android**: minSdk 33, targetSdk 35
- **Kotlin**: Primary language (JVM target 17)
- **Build System**: Gradle with Kotlin DSL

### Key Libraries & Frameworks
- **UI**: Material Design 3, ViewBinding (no Compose)
- **Serialization**: kotlinx.serialization (JSON + CBOR)
- **Networking**: OkHttp, coroutines
- **Storage**: Android SAF (Scoped Storage), SharedPreferences, EncryptedSharedPreferences
- **Concurrency**: Kotlin Coroutines, WorkManager
- **Speech**: Android SpeechRecognizer API
- **Layout**: FlexboxLayout for adaptive tile grids

### Project Structure
```
com.yvesds.vt5/
├── VT5App.kt                   # Application singleton, DI root
├── core/                       # Core infrastructure
│   ├── app/                    # App lifecycle utilities
│   ├── opslag/                 # Storage abstraction (SAF)
│   ├── secure/                 # Credentials & encryption
│   └── ui/                     # Common UI helpers
├── features/                   # Feature modules
│   ├── alias/                  # Alias matching & management
│   ├── metadata/               # Session metadata
│   ├── opstart/                # Onboarding & setup
│   ├── serverdata/             # Server data sync
│   ├── soort/                  # Species selection
│   ├── speech/                 # Voice recognition
│   └── telling/                # Observation tracking
├── hoofd/                      # Main activity
├── net/                        # Network API clients
├── network/                    # Data uploaders
└── utils/                      # Utility functions
```

## Voice Recognition System

### Overview
The speech recognition system is the **heart of VT5**. It must:
- Recognize Dutch bird species names with high accuracy
- Handle noisy field conditions (wind, ambient sounds)
- Support phonetic variations and aliases
- Parse numbers and counts from natural speech
- Respond instantly (< 100ms after speech ends)

### Key Components

#### 1. SpeechRecognitionManager
- Manages Android SpeechRecognizer lifecycle
- Provides silence threshold configuration
- Serializes parsing on dedicated single-thread dispatcher
- Cancellable parse jobs to prevent stale results

#### 2. AliasMatcher & AliasPriorityMatcher
- **Phonetic Matching**: Cologne, Double Metaphone, Beider-Morse
- **Fuzzy Matching**: Levenshtein distance, token overlap
- **Hash-based**: MinHash-64 for fast similarity
- **Priority System**: Canonical names > tile names > common aliases
- Context-aware: considers recently used species

#### 3. Phonetic Models
- Cologne Phonetic (German-based, works well for Dutch)
- Double Metaphone (English-based)
- Beider-Morse (multi-lingual)
- Custom G2P (grapheme-to-phoneme) for Dutch

#### 4. Data Formats
- **Runtime**: CBOR (binary, gzipped) for speed
  - `Documents/VT5/binaries/aliases_optimized.cbor.gz`
  - Contains heavy fields: minhash64, simhash64, phonemes
- **Export/Debug**: JSON for human readability
  - `Documents/VT5/assets/alias_master.json` (canonical source)
  - `Documents/VT5/assets/alias_index.json` (export format)

### Speech Recognition Patterns
```kotlin
// Pattern: "species_name count"
"koolmees 5"          → Species: Koolmees, Count: 5
"wilde eend tien"     → Species: Wilde Eend, Count: 10

// Pattern: implicit count 1
"buizerd"             → Species: Buizerd, Count: 1

// Pattern: Dutch number words
"vink twee"           → Species: Vink, Count: 2
```

## Code Conventions & Patterns

### Naming Conventions
- **Dutch Business Terms**: Use Dutch names for domain concepts (soort, telling, teller)
- **English Technical Terms**: Use English for technical/framework concepts (Activity, Manager, Repository)
- **Classes**: PascalCase (`SoortSelectieScherm`, `AliasMatcher`)
- **Functions**: camelCase (`startListening()`, `parseSpeciesCount()`)
- **Constants**: UPPER_SNAKE_CASE (`MAX_RESULTS`, `TAG`)

### Kotlin Style
- Prefer `val` over `var` (immutability by default)
- Use data classes for DTOs and models
- Leverage Kotlin stdlib functions (let, apply, run, etc.)
- Avoid `!!` null assertions (use safe calls or elvis operator)
- Use sealed classes for state/result types

### Coroutines Patterns
```kotlin
// UI operations: launch on Main
lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) {
        // IO work here
    }
    updateUI(result) // back on Main
}

// Heavy CPU work: use Dispatchers.Default
withContext(Dispatchers.Default) {
    // CPU-intensive parsing/matching
}

// File I/O: use Dispatchers.IO
withContext(Dispatchers.IO) {
    // Read/write files
}
```

### Dispatcher Strategy
- **Main**: UI updates, Android framework calls
- **IO**: Network requests, file I/O, SAF operations
- **Default**: CPU-intensive work (parsing, matching, hashing)
- **Custom Single-Thread**: Serial parsing to prevent race conditions

### Error Handling
- Use `try-catch` for expected exceptions
- Log errors with appropriate tag and level
- Provide user-friendly error messages (Dutch)
- Never crash on recoverable errors
- Graceful degradation when features unavailable

### Performance Guidelines

#### Critical Performance Paths
1. **Speech Recognition → Parse → Result**: Must complete in < 100ms
2. **App Launch → Ready**: Target < 1.5s cold start
3. **Species Selection Screen**: Smooth 60fps scrolling
4. **Tile Rendering**: Lazy loading, view recycling

#### Optimization Techniques
- **Preloading**: Background data load in VT5App.onCreate()
- **Lazy Initialization**: Use `lazy` delegate for expensive singletons
- **Index Caching**: Keep parsed CBOR indexes in memory
- **View Recycling**: RecyclerView for all lists
- **Debouncing**: User input (search) debounced to 150-300ms
- **Async by Default**: Never block main thread

#### Memory Management
- Clear caches when memory pressure detected
- Use WeakReferences for listeners/callbacks
- Avoid loading entire datasets into memory
- Streaming for large file operations

### Threading & Concurrency

#### Thread Safety
- SharedPreferences access via `@Synchronized` methods
- Use `AtomicInteger` for session IDs
- Immutable data classes preferred
- Avoid shared mutable state

#### Cancellation
- Cancel coroutine jobs on lifecycle events
- Use `SupervisorJob` to prevent child failures from cascading
- Provide explicit cancel methods for long-running operations

## Data Handling

### Storage Locations (SAF-based)
All app data stored under `Documents/VT5/`:
```
Documents/VT5/
├── assets/              # Human-readable master data
│   ├── alias_master.json
│   ├── alias_index.json
│   └── alias_master.meta.json
├── binaries/            # Runtime optimized formats
│   ├── aliases_optimized.cbor.gz
│   └── species_master.cbor.gz
├── serverdata/          # Server sync artifacts
│   ├── species.json
│   ├── locations.json
│   └── manifest.schema.json
└── exports/             # User exports & logs
    └── alias_precompute_log_<timestamp>.txt
```

### Serialization Strategy
- **JSON**: Human-readable, for master data and debugging
  - Use `Json { ignoreUnknownKeys = true }`
  - Pretty-print for master files
- **CBOR**: Binary, for runtime performance
  - Gzipped for storage efficiency
  - Contains heavy fields (hashes, phonemes)
- **Never use CSV**: Removed from runtime, legacy only

### Data Synchronization
1. **Download** from trektellen.nl (on setup or manual refresh)
2. **Precompute** alias indexes offline
3. **Cache** in binary format for fast loading
4. **Upload** observations to server (background WorkManager)

## Security Considerations

### Credentials Storage
- Use `EncryptedSharedPreferences` for API tokens
- Never log credentials or sensitive data
- Clear credentials on logout
- Validate certificates for HTTPS connections

### Data Privacy
- No analytics/tracking without user consent
- Location data only when explicitly enabled
- Observations stored locally until upload confirmed
- User can delete all local data

### Code Security
- Input validation for all user input
- Sanitize data before network transmission
- Avoid SQL injection (we don't use SQL, but principle applies)
- No hardcoded secrets in source code

## Testing Strategy

### Current State
- Tests are **disabled** in build.gradle.kts (line 79-81)
- Focus is on field testing and real-world validation

### Testing Approach (when re-enabled)
- **Unit Tests**: Core algorithms (phonetic matching, parsing)
- **Integration Tests**: API clients, data sync
- **UI Tests**: Critical user flows (voice → observation)
- **Manual Testing**: Field testing in real conditions

### Test Principles
- Fast: Unit tests < 100ms each
- Isolated: No network or file I/O in unit tests
- Readable: Clear test names (given_when_then)
- Maintainable: Don't test implementation details

## Distribution & Release

### Build Variants
- **Debug**: Development build, logging enabled
- **Release**: Production build, ProGuard disabled (for now)

### Release Checklist
1. Update versionCode and versionName
2. Test on multiple devices (min API 33+)
3. Verify offline functionality
4. Check voice recognition accuracy
5. Test trektellen.nl API integration
6. Verify storage permissions (SAF)
7. Generate signed APK/AAB

### Code Quality
- No compiler warnings in production code
- Consistent formatting (use IDE defaults)
- Remove debug logging before release
- Minimize APK size

## Common Patterns & Idioms

### Activity Lifecycle
```kotlin
class ExampleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExampleBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExampleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup UI
    }
    
    override fun onDestroy() {
        // Cleanup resources
        super.onDestroy()
    }
}
```

### SAF File Operations
```kotlin
val saf = SaFStorageHelper(context)
val file = saf.getFileInVT5("binaries/aliases_optimized.cbor.gz")
file?.let { docFile ->
    context.contentResolver.openInputStream(docFile.uri)?.use { stream ->
        // Read from stream
    }
}
```

### API Calls with OkHttp
```kotlin
suspend fun fetchData(): Result<Data> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url(API_URL)
            .build()
        
        val response = VT5App.http.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string() ?: ""
            val data = VT5App.json.decodeFromString<Data>(json)
            Result.success(data)
        } else {
            Result.failure(Exception("HTTP ${response.code}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

## Dutch Language Guidelines

### UI Text
- Use formal Dutch ("U" form) for app text
- Be concise and action-oriented
- Error messages should be helpful, not technical
- Use Dutch bird names (not Latin)

### Comments & Documentation
- Code comments in Dutch for domain logic
- Technical comments can be English
- README and user docs in Dutch
- API/library docs in English

## AI Assistant Guidelines

### When Suggesting Code Changes
1. **Preserve Performance**: Never compromise speed for convenience
2. **Minimal Changes**: Smallest possible modification to achieve goal
3. **Context-Aware**: Consider voice recognition flow
4. **Test in Mind**: Changes should be verifiable
5. **Dutch Domain**: Keep Dutch names for business concepts

### Common Pitfalls to Avoid
- ❌ Blocking main thread with heavy operations
- ❌ Using `!!` without null checks
- ❌ Forgetting to cancel coroutines
- ❌ Hardcoding strings (use resources)
- ❌ Synchronous network calls
- ❌ Ignoring Android lifecycle
- ❌ Over-engineering simple solutions

### When to Ask for Clarification
- Performance implications unclear
- Potential breaking changes to voice recognition
- Data format migrations required
- Security concerns arise
- Architecture deviation needed

## Key Files Reference

### Entry Points
- `VT5App.kt` — Application class, singletons, preloading
- `hoofd/HoofdActiviteit.kt` — Main launcher activity
- `features/opstart/ui/InstallatieScherm.kt` — Setup wizard

### Core Logic
- `features/speech/SpeechRecognitionManager.kt` — Voice recognition
- `features/speech/AliasMatcher.kt` — Species name matching
- `features/alias/AliasManager.kt` — Alias data management

### Data Layer
- `features/serverdata/model/ServerDataCache.kt` — Data preloading
- `core/opslag/SaFStorageHelper.kt` — File I/O abstraction
- `core/secure/CredentialsStore.kt` — Secure credential storage

### Network
- `net/TrektellenApi.kt` — Main API client
- `network/DataUploader.kt` — Background upload worker

## Development Workflow

### Local Development
1. Clone repository
2. Open in Android Studio
3. Sync Gradle
4. Run on device/emulator (API 33+)
5. Test voice recognition (requires mic)

### Making Changes
1. Create feature branch
2. Make minimal, focused changes
3. Test thoroughly (especially voice recognition)
4. Update documentation if needed
5. Submit PR with clear description

### Debugging Tips
- Enable `enablePartialsLogging` for speech recognition debug
- Check logs with tag filters (`SpeechRecognitionMgr`, `AliasMatcher`)
- Use `MatchLogWriter` for detailed matching analysis
- Verify SAF paths: `Documents/VT5/*`

---

**Remember**: VT5 is optimized for **speed**, **intuitiveness**, and **field reliability**. Every code change should enhance the user experience for bird watchers in real-world conditions.
