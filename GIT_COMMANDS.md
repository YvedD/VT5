# Git Commando's voor VT5 Refactoring

## Overzicht
Deze refactoring is uitgevoerd op de branch: **copilot/remove-unused-code-and-strings**

Er zijn 2 commits gemaakt:
1. "Extract hardcoded strings to strings.xml resources"
2. "Remove unused code and configure AGP 8.10.1 with documentation"

## Git Commando's voor Android Studio Terminal

### 1. Controleer Huidige Status
```bash
# Bekijk op welke branch je zit
git branch

# Bekijk status van repository
git status

# Bekijk recent commit history
git log --oneline -5
```

### 2. Bekijk de Wijzigingen

#### Bekijk alle wijzigingen
```bash
# Bekijk verschillen van uncommitted changes
git diff

# Bekijk verschillen van laatste commit
git diff HEAD~1

# Bekijk verschillen tussen twee commits
git diff HEAD~2 HEAD
```

#### Bekijk specifieke bestanden
```bash
# Strings XML
git diff HEAD~1 app/src/main/res/values/strings.xml

# Kotlin files
git diff HEAD~1 app/src/main/java/com/yvesds/vt5/hoofd/HoofdActiviteit.kt
git diff HEAD~1 app/src/main/java/com/yvesds/vt5/features/speech/SpeechRecognitionManager.kt
git diff HEAD~1 app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt

# Layout files
git diff HEAD~1 app/src/main/res/layout/activity_annotatie.xml
git diff HEAD~1 app/src/main/res/layout/scherm_telling.xml
```

#### Bekijk lijst van gewijzigde bestanden
```bash
# Alleen bestandsnamen
git diff --name-only HEAD~2

# Met status (Modified, Added, Deleted)
git diff --name-status HEAD~2

# Statistieken per bestand
git diff --stat HEAD~2
```

### 3. Werk met de Refactoring Branch

#### Switch naar de refactoring branch (als je op andere branch zit)
```bash
git checkout copilot/remove-unused-code-and-strings
```

#### Pull laatste wijzigingen van remote
```bash
git pull origin copilot/remove-unused-code-and-strings
```

#### Bekijk commit details
```bash
# Laatste commit
git show HEAD

# Specifieke commit (vervang met commit hash)
git show 19619a9

# Eerste commit van deze refactoring
git show e550326
```

### 4. Merge naar Main Branch

**Let op**: Voer deze stappen alleen uit na code review en approval!

#### Optie A: Merge via Command Line
```bash
# Switch naar main
git checkout main

# Pull laatste wijzigingen
git pull origin main

# Merge de refactoring branch
git merge copilot/remove-unused-code-and-strings

# Push naar remote
git push origin main
```

#### Optie B: Merge via Pull Request (Aanbevolen)
1. Ga naar GitHub repository: https://github.com/YvedD/VT5
2. Klik op "Pull requests"
3. Klik op "New pull request"
4. Base: main, Compare: copilot/remove-unused-code-and-strings
5. Klik "Create pull request"
6. Voeg beschrijving toe (gebruik REFACTORING_COMPLETE.md als referentie)
7. Request review (optioneel)
8. Merge na approval

### 5. Lokaal Testen voor Merge

#### Maak een test branch om merge te simuleren
```bash
# Maak test branch vanaf main
git checkout main
git checkout -b test-merge

# Merge refactoring branch
git merge copilot/remove-unused-code-and-strings

# Test de build (na AGP versie aanpassing!)
./gradlew clean assembleDebug

# Als alles werkt, verwijder test branch
git checkout main
git branch -D test-merge
```

### 6. AGP Versie Aanpassen (BELANGRIJK voor Build)

Voordat je build, wijzig `gradle/libs.versions.toml`:

```bash
# Open bestand in editor
# Wijzig agp = "8.10.1" naar agp = "8.7.0" (of andere beschikbare versie)

# Of via command line (Linux/Mac):
sed -i 's/agp = "8.10.1"/agp = "8.7.0"/' gradle/libs.versions.toml

# Of via command line (Windows Git Bash):
sed -i 's/agp = "8.10.1"/agp = "8.7.0"/' gradle/libs.versions.toml
```

### 7. Build en Test

```bash
# Refresh dependencies
./gradlew --refresh-dependencies

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Run tests (if re-enabled)
./gradlew test

# Install op device/emulator
./gradlew installDebug
```

### 8. Rollback Opties (Als er problemen zijn)

#### Optie A: Revert laatste commit (behoud history)
```bash
git revert HEAD
git push origin copilot/remove-unused-code-and-strings
```

#### Optie B: Reset naar vorige commit (verwijder history - PAS OP!)
```bash
# Soft reset (behoud wijzigingen in working directory)
git reset --soft HEAD~1

# Hard reset (verwijder alle wijzigingen - GEVAARLIJK!)
git reset --hard HEAD~1

# Force push (alleen als branch nog niet gemerged is!)
git push -f origin copilot/remove-unused-code-and-strings
```

#### Optie C: Checkout specifiek bestand van vorige versie
```bash
# Reset één bestand naar vorige versie
git checkout HEAD~1 -- app/src/main/res/values/strings.xml
git commit -m "Revert strings.xml changes"
git push origin copilot/remove-unused-code-and-strings
```

### 9. Verwijder Branch (Na Merge naar Main)

#### Verwijder lokale branch
```bash
git branch -d copilot/remove-unused-code-and-strings
```

#### Verwijder remote branch
```bash
git push origin --delete copilot/remove-unused-code-and-strings
```

### 10. Extra Handige Commando's

#### Bekijk alle branches
```bash
# Lokale branches
git branch

# Remote branches
git branch -r

# Alle branches
git branch -a
```

#### Bekijk commit geschiedenis
```bash
# Compacte view
git log --oneline --graph --all

# Laatste 10 commits
git log -10 --pretty=format:"%h - %an, %ar : %s"

# Commits van specifiek bestand
git log --follow app/src/main/res/values/strings.xml
```

#### Search in commit history
```bash
# Zoek commits met specifiek woord in message
git log --grep="hardcoded"

# Zoek commits die specifiek bestand wijzigden
git log -- app/src/main/java/com/yvesds/vt5/hoofd/HoofdActiviteit.kt
```

#### Stash changes (tijdelijk opslaan)
```bash
# Sla uncommitted changes op
git stash save "Work in progress"

# Lijst van stashes
git stash list

# Apply laatste stash
git stash apply

# Apply en verwijder laatste stash
git stash pop
```

## Workflow Samenvatting

### Voor Code Review:
1. `git checkout copilot/remove-unused-code-and-strings`
2. `git pull origin copilot/remove-unused-code-and-strings`
3. Bekijk wijzigingen: `git diff main`
4. Test lokaal: Wijzig AGP versie → `./gradlew assembleDebug`
5. Maak PR op GitHub of merge direct

### Na Approval:
1. `git checkout main`
2. `git pull origin main`
3. `git merge copilot/remove-unused-code-and-strings`
4. Test: `./gradlew clean assembleDebug`
5. `git push origin main`
6. Cleanup: `git branch -d copilot/remove-unused-code-and-strings`

## Belangrijke Aantekeningen

1. **AGP 8.10.1 Issue**: De build zal falen zonder AGP versie aanpassing. Zie REFACTORING_COMPLETE.md voor details.

2. **Branch Name**: De volledige refactoring zit op branch `copilot/remove-unused-code-and-strings`

3. **Files Changed**: 20+ bestanden gewijzigd, zie REFACTORING_COMPLETE.md voor complete lijst

4. **Testing**: Test alle schermen na merge, vooral:
   - HoofdActiviteit (toast messages)
   - SoortSelectieScherm (error messages)
   - TellingScherm (dialogs en toasts)
   - AnnotatieScherm (labels)
   - HuidigeStandScherm (totals)

5. **Backup**: Maak backup voor merge naar main:
   ```bash
   git tag pre-refactoring-backup main
   git push origin pre-refactoring-backup
   ```

## Hulp en Support

Voor vragen over deze refactoring:
- Zie REFACTORING_COMPLETE.md voor volledige details
- Check commit messages voor context: `git log --oneline`
- Bekijk specifieke wijzigingen: `git show <commit-hash>`

---

**Branch**: copilot/remove-unused-code-and-strings  
**Commits**: 2 (e550326, 19619a9)  
**Files Changed**: 20+  
**Lines Changed**: ~300 insertions, ~150 deletions
