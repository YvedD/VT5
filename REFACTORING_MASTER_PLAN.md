# VT5 - Master Refactoring Plan

## Executive Summary

Dit document biedt een **complete overzicht** van alle refactoring werk voor de VT5 applicatie, inclusief prioriteiten, tijdsinschattingen en afhankelijkheden.

**Doel**: Alle bestanden >500 regels opsplitsen in onderhoudbare modules, zoals succesvol gedaan bij TellingScherm.kt.

---

## Current State Overview

### Code Metrics (Totaal: ~16,000 regels)

| Bestand | Regels | Methods | Status | Prioriteit |
|---------|--------|---------|--------|------------|
| **AliasManager.kt** | 1332 | ? | ❌ Te analyseren | 🔴 KRITIEK |
| **TellingScherm.kt** | 1288 | 42 | ✅ Gerefactored | 🟢 DONE |
| **MetadataScherm.kt** | 798 | 21 | ❌ Te analyseren | 🔴 HOOG |
| **SpeechRecognitionManager.kt** | 740 | ? | ✅ Goed gestructureerd | 🟢 OK |
| **InstallatieScherm.kt** | 702 | 23 | ✅ Analyse compleet | 🔴 HOOG |
| **ServerDataRepository.kt** | 644 | ? | ❌ Te analyseren | 🟡 MEDIUM |
| **AliasSpeechParser.kt** | 540 | ? | ✅ Goed gestructureerd | 🟢 OK |
| **SoortSelectieScherm.kt** | 498 | ? | ❌ Te analyseren | 🟡 MEDIUM |
| **AliasRepository.kt** | 479 | ? | ❌ Te analyseren | 🟡 MEDIUM |

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

#### 1.2 MetadataScherm.kt Refactoring
**Omvang**: 798 regels → ~300 regels (verwacht)

**Te analyseren verantwoordelijkheden**:
- Form management (dropdowns, date/time pickers)
- Weather data fetching (WeatherManager integration)
- Server API call (start telling)
- Data validation
- Dialog management

**Voorgestelde helpers** (indicatief):
- MetadataFormManager.kt (~150 regels)
- WeatherDataFetcher.kt (~100 regels)
- TellingStarter.kt (~120 regels)
- MetadataValidator.kt (~80 regels)

**Tijdsinschatting**: 2 dagen

**Branch**: `copilot/refactor-metadatascherm`

**Status**: ⏳ **Planning phase - detailed analysis needed**

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

### Phase 3: Data Layer 🟡 **MEDIUM PRIORITY**

#### 3.1 AliasManager.kt Refactoring ⚠️
**Omvang**: 1332 regels (GROOTSTE BESTAND!) → ~400 regels (verwacht)

**Te analyseren**: Gedetailleerde analyse nodig

**Mogelijke helpers**:
- AliasIndexBuilder.kt
- AliasIndexLoader.kt
- AliasIndexCache.kt
- AliasPrecompute.kt

**Tijdsinschatting**: 3-4 dagen (complex systeem)

**Branch**: `copilot/refactor-aliasmanager`

**Status**: ⏳ **Needs detailed analysis - CRITICAL SIZE**

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

## Totale Tijdsinschatting

| Phase | Tijd | Status |
|-------|------|--------|
| Phase 0: Planning | 1 dag | ✅ DONE |
| Phase 1: Setup & Infrastructure | 4-5 dagen | ⏳ NEXT |
| Phase 2: Core Observation Flow | 2 dagen | 🔜 SOON |
| Phase 3: Data Layer | 5-6 dagen | 🔜 LATER |
| Phase 4: Supporting Components | 1 dag | 🔜 LATER |
| Phase 5: Testing & Documentation | 6-7 dagen | 🔄 PARALLEL |
| **TOTAAL** | **19-22 dagen** | |

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

## Current Status

**Phase**: 0 (Planning) ✅ **COMPLETED**

**Next Step**: User approval voor Phase 1.1 (InstallatieScherm refactoring)

**Branch**: `copilot/complete-refactoring-phases`

**Documents Ready**:
- ✅ VOLLEDIGE_APP_ANALYSE.md
- ✅ VERBETERINGEN_ANALYSE.md  
- ✅ REFACTORING_INSTALLATIESCHERM.md
- ✅ REFACTORING_MASTER_PLAN.md (this document)

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
