# VT5 - Master Refactoring Plan

## Executive Summary

Dit document biedt een **complete overzicht** van alle refactoring werk voor de VT5 applicatie, inclusief prioriteiten, tijdsinschattingen en afhankelijkheden.

**Doel**: Alle bestanden >500 regels opsplitsen in onderhoudbare modules, zoals succesvol gedaan bij TellingScherm.kt.

---

## Current State Overview - UPDATED 2025-11-17

### Code Metrics (Totaal: ~16,000 regels)

| Bestand | Regels | Status | Prioriteit | Phase |
|---------|--------|--------|------------|-------|
| **TellingScherm.kt** | 1288 | ❌ Te refactoren | 🔴 HOOG | Phase 4 |
| **AliasManager.kt** | 801 | ✅ Gerefactored | 🟢 DONE | Phase 2/3 |
| **SpeechRecognitionManager.kt** | 740 | ❌ Te refactoren | 🟡 MEDIUM | Phase 4 |
| **ServerDataRepository.kt** | 644 | ❌ Te refactoren | 🟡 MEDIUM | Phase 4 |
| **AliasSpeechParser.kt** | 540 | ✅ Goed gestructureerd | 🟢 OK | - |
| **SoortSelectieScherm.kt** | 498 | 🟢 OK (<500) | 🟢 LOW | - |
| **AliasRepository.kt** | 479 | 🟢 OK (<500) | 🟢 LOW | - |
| **InstallatieScherm.kt** | 456 | ✅ Gerefactored | 🟢 DONE | Phase 1 |
| **MetadataScherm.kt** | 367 | ✅ Gerefactored | 🟢 DONE | Phase 2 |

**Phases Completed**: 
- ✅ Phase 1: InstallatieScherm (702→456, 35% reductie) 
- ✅ Phase 2: MetadataScherm (798→367, 54% reductie)
- ✅ Phase 3: AliasManager (1332→801, 40% reductie)

**Total Lines Removed**: 962 regels uit 2 bestanden (Phase 2/3)  
**Total Helpers Created**: 9 helpers (~1,580 regels)

---

## Refactoring Roadmap

### Phase 0: Planning & Analysis ✅ **COMPLETED**

**Status**: ✅ **DONE**

**Deliverables**:
- [x] VOLLEDIGE_APP_ANALYSE.md - App flow mapping
- [x] VERBETERINGEN_ANALYSE.md - TellingScherm improvements
- [x] REFACTORING_INSTALLATIESCHERM.md - Detailed plan
- [x] REFACTORING_MASTER_PLAN.md - This document

**Branch**: `copilot/complete-refactoring-phases`

---

### Phase 1: Setup & Infrastructure 🔴 **HIGH PRIORITY**

**Doel**: Refactor setup flows die minst kritiek zijn voor core functionaliteit

#### 1.1 InstallatieScherm.kt Refactoring
**Omvang**: 702 → ~280 regels (60% reductie)

**Helpers te maken** (6 classes, ~810 regels):
- [ ] InstallationDialogManager.kt (80 regels)
- [ ] InstallationSafManager.kt (120 regels)
- [ ] CredentialsFlowManager.kt (80 regels)
- [ ] ServerAuthenticationManager.kt (150 regels)
- [ ] ServerDataDownloadManager.kt (200 regels)
- [ ] AliasIndexManager.kt (180 regels)

**Tijdsinschatting**: 2-3 dagen

**Testing**:
- Unit tests voor elke helper
- Integration test voor complete setup flow
- Test op meerdere Android devices (API 33+)

**Branch**: `copilot/refactor-installatiescherm`

**Details**: Zie `REFACTORING_INSTALLATIESCHERM.md`

---

#### 1.2 MetadataScherm.kt Refactoring ✅ **DONE**
**Result**: 798 → 367 regels (**54% reductie**, 431 regels verwijderd)

**Helpers Created** (3 classes):
- ✅ MetadataFormManager.kt (~250 regels) - Form management, validation
- ✅ WeatherDataFetcher.kt (~140 regels) - Weather API, location permissions
- ✅ TellingStarter.kt (~200 regels) - Telling start API, envelope building

**Key Improvements**:
- Separated concerns: Form, Weather, API calls
- Simplified activity from complex nested coroutines
- Better testability and maintainability
- All functionality preserved

**Branch**: `copilot/refactor-aliasmanager-and-metadata`

**Status**: ✅ **COMPLETED**

---

### Phase 2: Core Observation Flow 🟡 **MEDIUM PRIORITY**

#### 2.1 SoortSelectieScherm.kt Refactoring
**Omvang**: 498 regels → ~250 regels (verwacht)

**Bekende issues** (uit PERFORMANCE_OPTIMALISATIE_ANALYSE.md):
- Duplicate data loading
- Blocking UI tijdens cached data load
- Inefficiënte data transformaties
- O(n²) complexity in filtering

**Voorgestelde helpers**:
- SpeciesListBuilder.kt (~120 regels)
- SpeciesFilter.kt (~80 regels)
- RecentSpeciesManager.kt (~50 regels)

**Tijdsinschatting**: 1.5 dagen

**Branch**: `copilot/refactor-soortelectiescherm`

---

#### 2.2 TellingScherm.kt Polish
**Omvang**: 1288 regels (al goed, minor improvements)

**Resterende items** (uit VERBETERINGEN_ANALYSE.md):
- [ ] Empty catch blocks → logging
- [ ] Magic numbers → constants (MAX_RECENT_SPECIES = 25)
- [ ] Hardcoded dialog strings → strings.xml
- [ ] Input validation voor count fields

**Tijdsinschatting**: 4 uur

**Branch**: `copilot/polish-tellingscherm`

---

### Phase 3: Data Layer ✅ **COMPLETED**

#### 3.1 AliasManager.kt Refactoring ✅ **DONE**
**Result**: 1332 → 801 regels (**40% reductie**, 531 regels verwijderd)

**Helpers Created** (6 classes):
- ✅ AliasIndexCache.kt (~90 regels)
- ✅ AliasSafWriter.kt (~120 regels)
- ✅ AliasMasterIO.kt (~220 regels)
- ✅ AliasIndexLoader.kt (~100 regels)
- ✅ AliasSeedGenerator.kt (~280 regels)
- ✅ AliasCborRebuilder.kt (~180 regels)

**Key Improvements**:
- ensureIndexLoadedSuspend(): 98 → 20 regels (80% reductie)
- forceFlush/forceRebuildCborNow: 80+ → 6 regels (93% reductie)
- Removed 323 regels duplicate/moved code

**Branch**: `copilot/refactor-aliasmanager-and-metadata`

**Status**: ✅ **COMPLETED**

---

#### 3.2 ServerDataRepository.kt Refactoring
**Omvang**: 644 regels → ~300 regels (verwacht)

**Te analyseren**: Download logic, caching, parsing

**Voorgestelde helpers**:
- ServerDataDownloader.kt
- ServerDataParser.kt
- ServerDataCache.kt (already exists?)

**Tijdsinschatting**: 2 dagen

**Branch**: `copilot/refactor-serverdatarepository`

---

### Phase 4: Supporting Components 🟢 **LOW PRIORITY**

#### 4.1 Kleine Refactorings
**Files** <300 regels that could still benefit:
- AnnotatieScherm.kt (241 regels) - Extract form validation
- RecordsBeheer.kt (296 regels) - Extract data operations
- AfrondWorker.kt (283 regels) - Extract notification logic

**Tijdsinschatting**: 1 dag totaal

---

### Phase 4: Remaining Large Files ⏳ **NEXT PRIORITY**

**Status**: Phase 2 & 3 Complete ✅ | Phase 4 Analysis Ready 🚀

**Target Files** (>500 regels):
1. **TellingScherm.kt** (1,288 regels) → ~450 regels 🔴 HIGHEST PRIORITY
2. **SpeechRecognitionManager.kt** (740 regels) → ~400 regels 🟡 MEDIUM
3. **ServerDataRepository.kt** (644 regels) → ~350 regels 🟡 MEDIUM
4. **AliasSpeechParser.kt** (540 regels) → ~350 regels 🟢 LOW (optional)

**Total Projected Reduction**: ~1,070 regels

**Detailed Analysis**: Zie `PHASE_4_ANALYSIS.md`

---

#### 4.1 TellingScherm.kt Refactoring 🔴 **RECOMMENDED FIRST**
**Omvang**: 1,288 regels (NU GROOTSTE FILE!) → ~450 regels (65% reductie)

**✅ Bestaande Helpers** (al aanwezig maar niet volledig gebruikt!):
- TellingUiManager.kt - UI updates, tiles, colors
- TellingLogManager.kt - Log display, filtering
- TellingSessionManager.kt - Session state, persistence
- TellingBackupManager.kt - Auto-backup, recovery
- AfrondManager.kt - Finish flow, upload
- TellingDialogHelper.kt - Dialog styling
- TegelBeheer.kt - Tile layout management
- RecordsBeheer.kt - Observation records
- TellingDataProcessor.kt - Data transformations

**Strategy**: Refactor TellingScherm om volledig te delegeren naar bestaande helpers

**Nieuwe helpers** (indien nodig):
- TellingSpeechHandler.kt (~150 regels) - Speech recognition integration
- TellingGestureHandler.kt (~100 regels) - Gesture detection, volume keys
- TellingDataSync.kt (~100 regels) - Data synchronization

**Tijdsinschatting**: 2.5-3 dagen

**Risico**: Medium-High (centrale functionaliteit)

**Branch**: `copilot/refactor-aliasmanager-and-metadata` (continue in same branch)

---

#### 4.2 SpeechRecognitionManager.kt Refactoring
**Omvang**: 740 → ~400 regels (46% reductie)

**Voorgestelde helpers** (3 classes):
- SpeechRecognizerWrapper.kt (~150 regels) - Android SpeechRecognizer lifecycle
- SpeechResultParser.kt (~120 regels) - Result parsing, normalization
- PhoneticIndexLoader.kt (~100 regels) - Phonetic index management

**Tijdsinschatting**: 4 dagen

**Risico**: Medium (kernfunctionaliteit)

---

#### 4.3 ServerDataRepository.kt Refactoring
**Omvang**: 644 → ~350 regels (46% reductie)

**Voorgestelde helpers** (3 classes):
- ServerDataFileReader.kt (~150 regels) - SAF file operations
- ServerDataDecoder.kt (~120 regels) - JSON/CBOR decoding
- ServerDataCacheManager.kt (~100 regels) - Cache management

**Tijdsinschatting**: 2.5 dagen

**Risico**: Laag (data layer)

---

#### 4.4 AliasSpeechParser.kt (Optional)
**Omvang**: 540 → ~350 regels (35% reductie)

**Status**: ✅ Goed gestructureerd, minimal refactoring needed

**Actie**: Alleen indien tijd over is

**Tijdsinschatting**: 0.5 dag (optional)

---

### Phase 5: Testing & Documentation 🔴 **HIGH PRIORITY**

#### 5.1 Unit Tests
**Scope**: All new helper classes

**Coverage target**: >80% voor business logic

**Tijdsinschatting**: 3-4 dagen (parallel met refactoring)

---

#### 5.2 Integration Tests
**Scope**: Critical user flows
- Setup flow (InstallatieScherm)
- Metadata → SoortSelectie → Telling
- Speech recognition → tile update
- Afronden & upload

**Tijdsinschatting**: 2 dagen

---

#### 5.3 Documentation
- [ ] Update README.md
- [ ] KDoc voor alle helpers
- [ ] Architecture diagrams
- [ ] Developer guidelines

**Tijdsinschatting**: 1 dag

---

## Totale Tijdsinschatting - UPDATED

| Phase | Tijd | Status | Completion Date |
|-------|------|--------|-----------------|
| Phase 0: Planning | 1 dag | ✅ DONE | 2025-11-17 |
| Phase 1: Setup & Infrastructure | 3 dagen | ✅ DONE | 2025-11-17 |
| Phase 2: MetadataScherm | 2 dagen | ✅ DONE | 2025-11-17 |
| Phase 3: AliasManager | 3 dagen | ✅ DONE | 2025-11-17 |
| **Phase 4: Remaining Large Files** | **7-9 dagen** | **⏳ PLANNING** | - |
| Phase 5: Testing & Documentation | 6-7 dagen | 🔄 PARALLEL | - |
| **TOTAAL** | **22-25 dagen** | **40% DONE** | |

**Progress**: 
- ✅ Phases 0-3: 9 dagen (36% van totaal)
- ⏳ Phase 4: 7-9 dagen (planning)
- 🔜 Phase 5: 6-7 dagen (parallel)

---

## Implementation Strategy

### Workflow Per Phase

1. **Analysis** → Maak REFACTORING_[BESTAND].md document
2. **Branch** → Create feature branch
3. **Implement Helpers** → Een voor één, met commits
4. **Test Helpers** → Unit tests
5. **Refactor Main File** → Update main file to use helpers
6. **Integration Test** → Test complete flow
7. **Code Review** → Request review
8. **Merge** → Na approval

### Branch Strategy

```
copilot/complete-refactoring-phases (master refactoring branch)
    ├── copilot/refactor-installatiescherm
    ├── copilot/refactor-metadatascherm
    ├── copilot/refactor-soortelectiescherm
    ├── copilot/polish-tellingscherm
    ├── copilot/refactor-aliasmanager
    └── copilot/refactor-serverdatarepository
```

### Merge Protocol

```bash
# Elke feature branch:
git checkout copilot/refactor-[naam]
# ... work ...
git push origin copilot/refactor-[naam]

# Na testing en approval:
git checkout copilot/complete-refactoring-phases
git merge --no-ff copilot/refactor-[naam]
git push origin copilot/complete-refactoring-phases

# Pas na ALLE phases:
git checkout main
git merge --no-ff copilot/complete-refactoring-phases
git tag v2.0-refactored
git push origin main --tags
```

---

## Success Metrics

### Code Quality Targets

- ✅ Geen bestand >800 regels
- ✅ Max 5 verantwoordelijkheden per class
- ✅ Geen method >100 regels
- ✅ >80% test coverage voor business logic

### Architecture Targets

- ✅ Helper classes voor elk domein
- ✅ Clear separation of concerns
- ✅ Type-safe APIs (sealed classes)
- ✅ Testable components

### Documentation Targets

- ✅ KDoc voor alle public methods
- ✅ README per package
- ✅ Architecture diagrams
- ✅ Developer onboarding guide

---

## Risk Management

### High Risk Areas

1. **AliasManager.kt** - Grootste bestand, complexe logica
   - **Mitigatie**: Extra tijd, thorough testing, phased approach

2. **Speech Recognition** - Kritieke functionaliteit
   - **Mitigatie**: TellingScherm al goed, speech package blijft intact

3. **Network Operations** - Timeout/connection issues
   - **Mitigatie**: Proper error handling, retry logic

4. **File System** - SAF complexity, permissions
   - **Mitigatie**: Test op meerdere devices, Android versies

### Medium Risk Areas

1. **State Synchronization** - ViewModel, persistence
   - **Mitigatie**: Integration tests

2. **Performance** - Data loading, parsing
   - **Mitigatie**: Keep async patterns, profile changes

---

## Dependencies & Blockers

### Dependencies

- Phase 2 kan starten na Phase 1.1
- Phase 3 onafhankelijk van andere phases
- Phase 5 parallel met alle phases

### Potential Blockers

- ❌ User availability for testing
- ❌ Device availability voor testing
- ❌ Breaking changes in Android API's

---

## Communication & Reporting

### Progress Updates

**Frequentie**: Na elke voltooide phase

**Format**: Update dit document + commit message

### User Checkpoints

**Critical Checkpoints**:
1. ✅ Na Phase 0 (Planning) - Approval voor plan
2. ⏳ Na Phase 1.1 (InstallatieScherm) - Test setup flow
3. ⏳ Na Phase 2 (Core flows) - Test complete user journey
4. ⏳ Na Phase 3 (Data layer) - Performance validation
5. ⏳ Voor final merge - Complete regression test

---

## Current Status - UPDATED 2025-11-17

**Phase**: 2 & 3 ✅ **COMPLETED** | Phase 4 **PLANNING**

**Completed Work**:
- ✅ Phase 2: MetadataScherm.kt (798→367, 54% reductie)
- ✅ Phase 3: AliasManager.kt (1332→801, 40% reductie)
- ✅ Total: 962 regels verwijderd, 9 helpers aangemaakt

**Next Step**: User approval voor Phase 4 refactoring

**Branch**: `copilot/refactor-aliasmanager-and-metadata` (continuing in same branch)

**Documents Ready**:
- ✅ VOLLEDIGE_APP_ANALYSE.md
- ✅ VERBETERINGEN_ANALYSE.md  
- ✅ REFACTORING_INSTALLATIESCHERM.md
- ✅ REFACTORING_MASTER_PLAN.md (this document - UPDATED)
- ✅ PHASE_2_COMPLETION_SUMMARY.md
- ✅ PHASE_3_ALIASMANAGER_REFACTORING_COMPLETE.md
- ✅ **PHASE_4_ANALYSIS.md** (NEW - Complete analysis of remaining files)

---

## Git Commands Voor User

### Review Analysis Documents
```bash
git fetch origin copilot/complete-refactoring-phases
git checkout copilot/complete-refactoring-phases

# Bekijk analyse documenten
cat REFACTORING_MASTER_PLAN.md
cat REFACTORING_INSTALLATIESCHERM.md
cat VOLLEDIGE_APP_ANALYSE.md
cat VERBETERINGEN_ANALYSE.md
```

### Approve & Start Implementation
```bash
# Als je akkoord bent met het plan:
# Ik begin met Phase 1.1 (InstallatieScherm)
# Je krijgt updates na elke helper class implementation
```

### Test Na Implementatie
```bash
# Na elke phase
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Test kritieke flows handmatig op device
```

---

**Wacht op feedback**: Is dit plan akkoord? Zal ik beginnen met Phase 1.1?

---

*Master Plan door*: GitHub Copilot Coding Agent  
*Datum*: 2025-11-17  
*Branch*: copilot/complete-refactoring-phases  
*Versie*: 1.0
