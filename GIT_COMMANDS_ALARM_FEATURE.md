# Git Commands voor Uurlijks Alarm Feature

## Branch Downloaden

Om de nieuwe alarm feature branch naar je lokale laptop te downloaden:

```bash
# Stap 1: Haal de laatste updates op van GitHub
git fetch origin

# Stap 2: Checkout de nieuwe branch
git checkout copilot/add-alarm-sound-feature

# Stap 3: Zorg dat je de laatste versie hebt
git pull origin copilot/add-alarm-sound-feature
```

## Alternatief: Vanaf Scratch

Als je de repository nog niet lokaal hebt:

```bash
# Clone de repository
git clone https://github.com/YvedD/VT5.git
cd VT5

# Checkout de alarm feature branch
git checkout copilot/add-alarm-sound-feature
```

## Branch Status Checken

```bash
# Bekijk welke branch je momenteel gebruikt
git branch

# Zie welke bestanden zijn gewijzigd
git status

# Bekijk de commit geschiedenis van deze branch
git log --oneline

# Zie het verschil met de main branch
git diff main..copilot/add-alarm-sound-feature
```

## Merge naar Main (Later)

Wanneer je klaar bent om de feature naar main te mergen:

```bash
# Stap 1: Ga naar main branch
git checkout main

# Stap 2: Zorg dat main up-to-date is
git pull origin main

# Stap 3: Merge de feature branch
git merge copilot/add-alarm-sound-feature

# Stap 4: Als er conflicten zijn, los ze op, dan:
git add .
git commit -m "Merge alarm feature into main"

# Stap 5: Push naar GitHub
git push origin main
```

## Snel Overzicht van Wijzigingen

```bash
# Bekijk welke bestanden zijn toegevoegd/gewijzigd
git diff --name-status main..copilot/add-alarm-sound-feature

# Output zal zijn:
# A    HOURLY_ALARM_USAGE.md
# A    GIT_COMMANDS_ALARM_FEATURE.md
# M    app/src/main/AndroidManifest.xml
# M    app/src/main/java/com/yvesds/vt5/VT5App.kt
# M    app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt
# M    app/src/main/java/com/yvesds/vt5/hoofd/HoofdActiviteit.kt
# M    app/src/main/res/layout/scherm_hoofd.xml
# A    app/src/main/java/com/yvesds/vt5/core/app/HourlyAlarmManager.kt
# A    app/src/main/java/com/yvesds/vt5/core/app/AlarmTestHelper.kt
# A    app/src/main/res/raw/README_ALARM_SOUND.txt
```

## Belangrijke Bestanden in deze Branch

### Nieuwe bestanden:
- `app/src/main/java/com/yvesds/vt5/core/app/HourlyAlarmManager.kt` - Hoofd alarm systeem
- `app/src/main/java/com/yvesds/vt5/core/app/AlarmTestHelper.kt` - Test utilities
- `app/src/main/res/raw/README_ALARM_SOUND.txt` - Instructies voor alarm geluid
- `HOURLY_ALARM_USAGE.md` - Complete gebruiksinstructies
- `GIT_COMMANDS_ALARM_FEATURE.md` - Dit bestand

### Gewijzigde bestanden:
- `app/src/main/AndroidManifest.xml` - Permissies en receivers toegevoegd
- `app/src/main/java/com/yvesds/vt5/VT5App.kt` - Alarm initialisatie
- `app/src/main/java/com/yvesds/vt5/features/telling/TellingScherm.kt` - onNewIntent handler
- `app/src/main/java/com/yvesds/vt5/hoofd/HoofdActiviteit.kt` - Debug UI
- `app/src/main/res/layout/scherm_hoofd.xml` - Debug UI layout

## Build en Test Commands

```bash
# Build de app (debug versie met test UI)
./gradlew assembleDebug

# Installeer op aangesloten device/emulator
./gradlew installDebug

# Bekijk logs tijdens testen
adb logcat | grep -E "HourlyAlarmManager|AlarmTestHelper"
```

## Branch Informatie

- **Branch naam**: `copilot/add-alarm-sound-feature`
- **Base branch**: `main` (of laatste actieve branch)
- **Commits**: 2
  1. "Add hourly alarm system with HuidigeStandScherm integration"
  2. "Add debug UI for alarm testing and comprehensive documentation"

## Hulp Commands

```bash
# Als je per ongeluk wijzigingen hebt gemaakt en terug wilt
git checkout .
git clean -fd

# Als je wilt zien wat er in een specifieke commit is veranderd
git show <commit-hash>

# Als je de branch wilt verwijderen (na merge naar main)
git branch -d copilot/add-alarm-sound-feature

# Remote branch ook verwijderen (na merge)
git push origin --delete copilot/add-alarm-sound-feature
```

## Contact & Vragen

Voor vragen over de implementatie, zie:
- `HOURLY_ALARM_USAGE.md` voor technische details
- GitHub PR voor code review
- Commit messages voor specifieke wijzigingen

## Handige Git Aliases (Optioneel)

Voeg deze toe aan je `~/.gitconfig` voor snellere commands:

```ini
[alias]
    co = checkout
    br = branch
    ci = commit
    st = status
    unstage = reset HEAD --
    last = log -1 HEAD
    visual = log --oneline --graph --decorate --all
```

Dan kun je gebruiken:
```bash
git co copilot/add-alarm-sound-feature  # i.p.v. git checkout
git st                                   # i.p.v. git status
git visual                               # Mooie visuele log
```
