# Android Studio: Main Branch Ophalen en Activeren

## Overzicht

Dit document bevat stapsgewijze instructies voor het ophalen en activeren van de geüpdatete **main** branch in Android Studio op je lokale laptop.

---

## Methode 1: Via Android Studio UI (Aanbevolen voor beginners)

### Stap 1: Open Android Studio
1. Start **Android Studio**
2. Open je VT5 project (als het nog niet geopend is)

### Stap 2: Update Project van Remote
1. Ga naar de menubar: **Git** → **Fetch**
   - Dit haalt alle updates op van GitHub zonder je lokale code te wijzigen
   - Wacht tot "Fetch successful" verschijnt

### Stap 3: Bekijk Beschikbare Branches
1. Klik rechtsonder in Android Studio op de **huidige branch naam** (bijv. "main" of een andere branch)
   - Dit opent het "Git Branches" popup venster
2. Je ziet een lijst met lokale en remote branches

### Stap 4: Checkout Main Branch
1. In het "Git Branches" popup venster:
   - Zoek naar **"main"** onder "Local Branches"
   - Als "main" daar staat:
     - Klik op **"main"**
     - Selecteer **"Checkout"**
   
   - Als "main" NIET onder "Local Branches" staat:
     - Zoek onder **"Remote Branches"** → **"origin/main"**
     - Klik op **"origin/main"**
     - Selecteer **"Checkout as 'main'"**

### Stap 5: Pull Laatste Wijzigingen
1. Ga naar de menubar: **Git** → **Pull**
2. Controleer dat de branch **"origin/main"** geselecteerd is
3. Klik op **"Pull"**
4. Wacht tot "Pull successful" verschijnt

### Stap 6: Gradle Sync
1. Android Studio zal automatisch een Gradle sync triggeren
2. Als dit niet gebeurt:
   - Klik op het **"Sync Project with Gradle Files"** icoon (olifant met sync pijl) in de toolbar
   - Of ga naar: **File** → **Sync Project with Gradle Files**

### Stap 7: Verificatie
1. Controleer rechtsonder dat de actieve branch **"main"** is
2. Ga naar **Git** → **Show Git Log** (Alt+9 of ⌘+9 op Mac)
3. Bekijk de recente commits:
   - Je zou moeten zien: **"Merge vorige 4 PR's naam main branch"** (commit 80e09a9)
   - Datum: **16 november 2025, 13:49:59**

---

## Methode 2: Via Terminal in Android Studio (Voor gevorderde gebruikers)

### Stap 1: Open Terminal
1. Klik op **"Terminal"** tab onderaan in Android Studio (Alt+F12 of ⌘+F12 op Mac)
2. Zorg dat je in de root directory van het VT5 project bent

### Stap 2: Fetch Updates
```bash
git fetch origin
```
- Dit haalt alle updates op van GitHub

### Stap 3: Bekijk Huidige Branch
```bash
git branch
```
- De branch met een * is de actieve branch
- Bekijk ook remote branches:
```bash
git branch -r
```

### Stap 4: Checkout Main Branch
```bash
git checkout main
```

- Als je een error krijgt dat "main" niet bestaat lokaal:
```bash
git checkout -b main origin/main
```

### Stap 5: Pull Laatste Wijzigingen
```bash
git pull origin main
```

### Stap 6: Verifieer de Merge Commit
```bash
git log --oneline -10
```
- Je zou moeten zien:
```
80e09a9 Merge vorige 4 PR's naam main branch
```

### Stap 7: Gradle Sync
```bash
./gradlew --stop
./gradlew sync
```
- Of gebruik de Gradle sync knop in Android Studio UI

---

## Veelvoorkomende Problemen en Oplossingen

### Probleem 1: "Your local changes would be overwritten"
**Oorzaak:** Je hebt lokale wijzigingen die conflicteren met de remote changes.

**Oplossing A - Stash Changes (aanbevolen als je je wijzigingen wilt bewaren):**
```bash
git stash
git checkout main
git pull origin main
git stash pop  # Pas je wijzigingen opnieuw toe
```

**Oplossing B - Discard Changes (gebruik alleen als je je lokale wijzigingen NIET wilt behouden):**
```bash
git checkout main
git reset --hard origin/main
```
⚠️ **WAARSCHUWING:** Dit gooit alle lokale wijzigingen weg!

### Probleem 2: "Conflict during merge"
**Oorzaak:** Je lokale wijzigingen conflicteren met remote changes.

**Oplossing:**
1. Android Studio toont de conflicting files
2. Voor elk bestand:
   - Klik op **"Resolve"**
   - Kies de juiste versie (left/right/merge)
   - Of edit handmatig
3. Na het oplossen van alle conflicts:
```bash
git add .
git commit -m "Resolved merge conflicts"
```

### Probleem 3: "Cannot checkout branch 'main'"
**Oorzaak:** De main branch bestaat niet lokaal.

**Oplossing:**
```bash
git fetch origin main:main
git checkout main
```

### Probleem 4: Gradle Sync Failures
**Oorzaak:** Dependencies niet gedownload of cache problemen.

**Oplossing:**
1. **File** → **Invalidate Caches**
2. Selecteer **"Invalidate and Restart"**
3. Na herstart:
```bash
./gradlew clean
./gradlew build
```

### Probleem 5: "Detached HEAD state"
**Oorzaak:** Je bent op een specifieke commit in plaats van een branch.

**Oplossing:**
```bash
git checkout main
```

---

## Verificatie Checklist

Na het ophalen van de main branch, controleer het volgende:

### ✅ Branch Verificatie
- [ ] Actieve branch is "main" (zie rechtsonder in Android Studio)
- [ ] Laatste commit is "Merge vorige 4 PR's naam main branch" (80e09a9)
- [ ] Commit datum is 16 november 2025, 13:49:59

### ✅ Code Verificatie
- [ ] Nieuwe bestanden aanwezig:
  - [ ] `.github/copilot-instructions.md`
  - [ ] `CODES_OPTIMIZATION.md`
  - [ ] `CONSOLIDATED_PR_SUMMARY.md`
  - [ ] `PERFORMANCE_OPTIMALISATIE_ANALYSE.md`
  - [ ] `REFACTORING_ANALYSE.md`
  - [ ] `REFACTORING_SUMMARY.md`
  - [ ] `SPECIES_LIST_ARCHITECTURE.md`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingLogManager.kt`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingDialogHelper.kt`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingBackupManager.kt`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingDataProcessor.kt`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingUiManager.kt`
  - [ ] `app/src/main/java/com/yvesds/vt5/features/telling/TellingAfrondHandler.kt`

### ✅ Build Verificatie
- [ ] Gradle sync succesvol
- [ ] Geen compile errors
- [ ] AGP versie is 8.5.2 (check `gradle/libs.versions.toml`)

---

## Testing Workflow

Na het succesvol ophalen van de main branch:

### 1. Clean Build
```bash
./gradlew clean
./gradlew assembleDebug
```

### 2. Test App Startup
1. Run de app op een emulator of fysiek device
2. Verwacht gedrag:
   - ⚡ **INSTANT startup** (~50ms i.p.v. 5-8 sec)
   - Geen lange laadtijden
   - Geen UI freezing

### 3. Test Metadata Flow
1. Klik op **"Invullen telpostgegevens"** knop
2. Verwacht gedrag:
   - ⚡ **MetadataScherm opent INSTANT**
   - Geen "Metadata laden..." toast
   - Gladde transitions
   - Dropdowns werken direct (wind, neerslag, type telling)

### 4. Test Species Selection
1. Vul metadata in en ga door naar TellingScherm
2. Klik op **"Soort toevoegen"** of gebruik voice input
3. Verwacht gedrag:
   - ⚡ **SoortSelectieScherm opent instant**
   - ~766 species beschikbaar (volledige lijst)
   - 30 recent species bovenaan (was 25)
   - Geen loading dialogs

### 5. Test Voice Recognition
1. In TellingScherm, gebruik microfoon knop
2. Zeg een vogelnaam in het Nederlands
3. Verwacht gedrag:
   - Snelle herkenning
   - Accurate matching
   - Smooth confirmation dialogs

---

## Performance Verwachtingen

Na het ophalen van de main branch, verwacht de volgende performance verbeteringen:

| Functie | Voor | Na | Verbetering |
|---------|------|-----|-------------|
| App Startup | 5-8 sec | ~50ms | **99% sneller** ⚡⚡⚡ |
| MetadataScherm Open | Slow | Instant | **~95% sneller** ⚡ |
| Cache Hit (SoortSelectie) | 1-2 sec | ~50ms | **95% sneller** ⚡ |
| Background Load Delay | 500ms | 50ms | **90% sneller** ⚡ |
| InstallatieScherm | Baseline | 30-40% sneller | **40% sneller** |
| Geheugen (codes) | 25KB | 4KB | **84% reductie** |
| Codes Parsing | 15-20ms | 3-4ms | **80% sneller** |

---

## Hulp Nodig?

### Android Studio Resources
- **Official Docs**: https://developer.android.com/studio
- **Git Integration**: https://www.jetbrains.com/help/idea/version-control-integration.html

### VT5 Specifieke Documentatie
- Lees `.github/copilot-instructions.md` voor volledige architectuur
- Lees `PERFORMANCE_OPTIMALISATIE_ANALYSE.md` voor performance details
- Lees `SPECIES_LIST_ARCHITECTURE.md` voor species list architectuur

### Contact
Als je problemen ondervindt die niet in dit document worden behandeld:
1. Check de Git log voor recente changes: `git log --oneline -20`
2. Check de build output in Android Studio
3. Check de Logcat voor runtime errors

---

## Aanbevolen Volgende Stappen

Na het succesvol ophalen en testen van de main branch:

1. **Lees de Nieuwe Documentatie**
   - `.github/copilot-instructions.md` - Volledige architectuur guide
   - `PERFORMANCE_OPTIMALISATIE_ANALYSE.md` - Performance details

2. **Exploreer de Nieuwe Helper Classes**
   - `TellingLogManager.kt` - Logging functionaliteit
   - `TellingDialogHelper.kt` - Dialog management
   - `TellingBackupManager.kt` - Backup operaties
   - `TellingDataProcessor.kt` - Data processing
   - `TellingUiManager.kt` - UI updates
   - `TellingAfrondHandler.kt` - Afronden flow

3. **Test de Performance Verbeteringen**
   - Meet de startup tijd
   - Test de nieuwe lazy loading
   - Verifieer de cache optimalisaties

4. **Backup Je Oude Code (Optioneel)**
   - Als je lokale wijzigingen had die je wilt bewaren:
   ```bash
   git stash save "Mijn oude wijzigingen backup"
   ```

---

## Conclusie

Na het volgen van deze instructies heb je:
- ✅ De laatste main branch lokaal
- ✅ Alle gemergde PR wijzigingen
- ✅ Performance verbeteringen geactiveerd
- ✅ Een werkende, geteste build

**Veel succes met testen!** 🚀
