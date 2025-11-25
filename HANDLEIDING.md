# VT5 — Gebruikershandleiding

> **VT5** is een snelle, intuïtieve Android-app voor het vastleggen van vogeltrekwaarnemingen via spraakinvoer. De app is ontworpen voor gebruik in het veld door vogelwaarnemers en synchroniseert automatisch met [www.trektellen.nl](https://www.trektellen.nl).

---

## Inhoudsopgave

1. [Eerste Installatie](#1-eerste-installatie)
2. [Permissies Toekennen](#2-permissies-toekennen)
3. [SAF-map Kiezen](#3-saf-map-kiezen)
4. [Automatisch Aangemaakte Bestanden](#4-automatisch-aangemaakte-bestanden)
5. [Server Data Downloaden](#5-server-data-downloaden)
6. [Metadata Instellen](#6-metadata-instellen)
7. [Soorten Kiezen & Recente Soorten](#7-soorten-kiezen--recente-soorten)
8. [Waarneming Toevoegen via Tegels](#8-waarneming-toevoegen-via-tegels)
9. [Waarneming Toevoegen via Spraakinvoer](#9-waarneming-toevoegen-via-spraakinvoer)
10. [Waarneming Annoteren](#10-waarneming-annoteren)
11. [Alias Aanmaken (Spraakinvoer Opslaan)](#11-alias-aanmaken-spraakinvoer-opslaan)
12. [Soorten Toevoegen tijdens Telling](#12-soorten-toevoegen-tijdens-telling)
13. [Huidige Stand Scherm](#13-huidige-stand-scherm)
14. [Telling Afronden](#14-telling-afronden)
15. [Auto-Weather Systeem](#15-auto-weather-systeem)

---

## 1. Eerste Installatie

### Stap 1: App Starten
Na het installeren van de APK start u de VT5-app. U komt terecht op het **Hoofdscherm** met drie knoppen:

| Knop | Functie |
|------|---------|
| **(Her)Installatie** | Opent het installatieproces voor eerste configuratie of herconfiguratie |
| **Invoeren telpostgegevens** | Start een nieuwe telling (na installatie) |
| **Afsluiten** | Sluit de app veilig af |

### Stap 2: Installatiewizard Starten
Klik op **"(Her)Installatie"** om naar het `InstallatieScherm` te gaan.

---

## 2. Permissies Toekennen

VT5 vraagt om de volgende permissies:

| Permissie | Waarvoor nodig |
|-----------|----------------|
| **Microfoon** (`RECORD_AUDIO`) | Spraakherkenning voor het invoeren van waarnemingen |
| **Locatie** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Auto-weather functie: ophalen van actuele weergegevens |
| **Opslagtoegang** (via SAF) | Bestanden opslaan en laden in `Documents/VT5/` |
| **Alarm** (`SCHEDULE_EXACT_ALARM`) | Uurlijks alarm op de 59e minuut |
| **Trillen** (`VIBRATE`) | Feedback bij alarmmeldingen |

De app vraagt deze permissies automatisch aan wanneer ze nodig zijn.

---

## 3. SAF-map Kiezen

### Wat is SAF?
SAF (Storage Access Framework) is het moderne opslagsysteem van Android. U kiest zelf de map waar VT5 bestanden mag opslaan.

### Stappen:
1. In het `InstallatieScherm`, klik op **"Kies Documents map"**
2. Android toont een bestandskiezer
3. Navigeer naar uw **Documents**-map (of maak deze aan)
4. Klik op **"Gebruiken"** of **"Toestaan"**
5. De status verandert naar: *"SAF OK - Alle mappen aanwezig"*

### Mappen Controleren/Aanmaken
Klik op **"Controleer/Maak mappen"** om te verifiëren dat alle submappen bestaan:
- `Documents/VT5/assets/`
- `Documents/VT5/serverdata/`
- `Documents/VT5/counts/`
- `Documents/VT5/exports/`
- `Documents/VT5/binaries/`

---

## 4. Automatisch Aangemaakte Bestanden

Na het configureren van SAF maakt VT5 de volgende structuur aan:

```
Documents/VT5/
├── assets/                          # Master data & configuratie
│   ├── alias_master.json           # Alle aliassen (soortnaam-synoniemen)
│   ├── alias_master.meta.json      # Metadata over de alias index
│   ├── alias_index.json            # Exportformaat van aliases
│   ├── annotations.json            # Annotatie-opties (leeftijd, geslacht, kleed)
│
├── binaries/                        # Geoptimaliseerde runtime bestanden
│   ├── aliases_optimized.cbor.gz   # Binaire alias-index (snel laden)
│   └── species_master.cbor.gz      # Soortenlijst (binair)
│
├── serverdata/                      # Gedownloade server data
│   ├── species.json                # Alle vogelsoorten
│   ├── site_species.json           # Soorten per telpost
│   ├── sites.json                  # Telposten/locaties
│   ├── codes.json                  # Weer- en overige codes
│   └── checkuser.json              # Gebruikersinfo na login
│
├── counts/                          # Opgeslagen tellingen
│   └── <timestamp>_count_<id>.json # Per telling een JSON-bestand
│
└── exports/                         # Exports & logs
    └── alias_precompute_log_<ts>.txt
```

---

## 5. Server Data Downloaden

### Inloggegevens Instellen
1. Vul uw **trektellen.nl gebruikersnaam** in bij "Login"
2. Vul uw **wachtwoord** in
3. Klik op **"Bewaar"** om de gegevens veilig op te slaan

### Login Testen
Klik op **"Test login"** om te verifiëren dat uw credentials werken. Bij succes ziet u uw gebruikersinfo.

### Server Data Downloaden
1. Klik op **"Download JSONs van server"**
2. De app downloadt:
   - `species.json` — Alle vogelsoorten
   - `site_species.json` — Soorten per telpost
   - `sites.json` — Beschikbare telposten
   - `codes.json` — Weer- en overige codes

3. Automatisch wordt `annotations.json` aangemaakt in `assets/` met standaard annotatie-opties (leeftijd, geslacht, kleed) indien nog niet aanwezig

4. Na het downloaden wordt automatisch de **alias-index** bijgewerkt

### Alias Index Bijwerken
De alias-index wordt automatisch bijgewerkt na het downloaden van server data. U kunt handmatig een rebuild forceren via **"Forceer heropbouw alias index"**.

### Terug naar Hoofdscherm
Klik op **"Klaar"** om terug te keren naar het hoofdscherm.

---

## 6. Metadata Instellen

Het `MetadataScherm` is waar u de telling voorbereid voordat u begint met waarnemen.

### Scherm Openen
Klik op **"Invoeren telpostgegevens"** in het hoofdscherm.

### Velden Invullen

| Veld | Beschrijving |
|------|-------------|
| **Telpost** | Kies een telpost uit de dropdown (gedownload van server) |
| **Datum** | Automatisch ingevuld met vandaag; klik om aan te passen |
| **Starttijd** | Automatisch ingevuld met huidige tijd; klik om aan te passen |
| **Tellers** | Uw naam (automatisch ingevuld vanuit login) |
| **Windrichting** | 16-punts kompasroos (N, NNO, NO, ONO, etc.) |
| **Windkracht** | Beaufort schaal (0-12) |
| **Bewolking** | Achtsten (0/8 tot 8/8) |
| **Neerslag** | Geen, motregen, regen, etc. |
| **Temperatuur** | Graden Celsius |
| **Zicht** | Meters |
| **Luchtdruk** | Hectopascal (hPa) |
| **Weer opmerking** | Vrij tekstveld voor extra weerinfo |

### Verder naar Soortselectie
Na het invullen van de metadata, klik op **"Verder"** om naar het soortenselectiescherm te gaan.

---

## 7. Soorten Kiezen & Recente Soorten

Het `SoortSelectieScherm` toont alle beschikbare vogelsoorten voor uw telling.

### Schermindeling

```
┌─────────────────────────────────────┐
│ [Zoekbalk: Typ om te zoeken]        │
├─────────────────────────────────────┤
│ ═══ Recente Soorten (5) [✓] Alles ═ │ ← Header met "Selecteer alle recente"
│ ┌─────────┐ ┌─────────┐             │
│ │ Buizerd │ │ Koolmees│             │ ← Recent gebruikte soorten
│ └─────────┘ └─────────┘             │
│ ───────────────────────────────────── │ ← Scheidingslijn
│ ┌─────────┐ ┌─────────┐             │
│ │ Aalschol│ │ Appelvk │             │ ← Alfabetische lijst
│ └─────────┘ └─────────┘             │
│ ...                                  │
├─────────────────────────────────────┤
│ Totaal: 245 soorten | 12 geselecteerd│
│        [Annuleer]    [OK]            │
└─────────────────────────────────────┘
```

### Recente Soorten (Quick-Pick)
- **Bovenaan** ziet u de soorten die u recent heeft gebruikt
- Klik op **"Alles"** checkbox om alle recente soorten in één keer te selecteren
- Recente soorten worden automatisch bijgehouden (max. 30 items)

### Zoeken
- Typ in de zoekbalk om snel soorten te vinden
- Zoeken werkt op naam én ID
- De zoekfunctie is accent-insensitief ("e" vindt ook "é")

### Soorten Selecteren
- **Tik** op een soort om te selecteren/deselecteren
- Geselecteerde soorten krijgen een vinkje
- De teller onderaan toont hoeveel soorten zijn geselecteerd

### Bevestigen
Klik op **"OK"** om de selectie te bevestigen en naar het telscherm te gaan.

---

## 8. Waarneming Toevoegen via Tegels

In het `TellingScherm` ziet u uw geselecteerde soorten als tegels (tiles).

### Schermindeling

```
┌─────────────────────────────────────┐
│ ═══ Spraakherkenning Resultaten ═══ │
│ ┌─────────────────────────────────┐ │
│ │ [Partials - blauw kader]        │ │ ← Tussenstands spraakherkenning
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ [Finals - groen kader]          │ │ ← Definitieve resultaten
│ └─────────────────────────────────┘ │
├─────────────────────────────────────┤
│ [Totalen] [+ Soorten] [Afronden]    │ ← Actieknoppen
├─────────────────────────────────────┤
│ ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│ │ Buizerd │ │ Sperwer │ │ Vink    │ │ ← Soort-tegels
│ │    3    │ │    0    │ │   12    │ │    met aantallen
│ └─────────┘ └─────────┘ └─────────┘ │
│ ...                                  │
└─────────────────────────────────────┘
```

### Handmatig Tellen (Tik op Tegel)
1. **Tik** op een soort-tegel
2. Er verschijnt een dialoog: *"Voer aantal in voor [Soortnaam]"*
3. Typ het aantal (bijv. `5`)
4. Klik op **"OK"**
5. Het aantal wordt opgeteld bij de huidige telling

### Resultaat
- De tegel toont het nieuwe totaal
- In het **Finals-venster** verschijnt: `Buizerd -> +5`
- De telling wordt automatisch opgeslagen in een backup-bestand

---

## 9. Waarneming Toevoegen via Spraakinvoer

VT5 is geoptimaliseerd voor snelle spraakherkenning van Nederlandse vogelnamen.

### Spraakherkenning Activeren
- **Volumetoets** (omhoog of omlaag) indrukken en loslaten
- Of automatisch via voice-key handler

### Spraakprotocol
Spreek in het formaat: **"Soortnaam Aantal"**

| U zegt | Resultaat |
|--------|-----------|
| "Buizerd vijf" | Buizerd +5 |
| "Koolmees" | Koolmees +1 (impliciet 1) |
| "Wilde eend tien" | Wilde Eend +10 |
| "Vink twee" | Vink +2 |

### Nederlandse Getallen
VT5 herkent Nederlandse telwoorden:
- één, twee, drie, vier, vijf, zes, zeven, acht, negen, tien
- elf, twaalf, dertien, veertien, etc.
- twintig, dertig, veertig, vijftig, etc.

### Partials vs Finals
- **Partials** (blauw kader): Tussenresultaten terwijl u spreekt
- **Finals** (groen kader): Definitief herkende en geregistreerde waarnemingen

### Soort Niet in Tegels
Als u een soort noemt die niet in uw tegels zit:
1. VT5 toont een bevestigingsdialoog
2. *"Soort 'Wielewaal' herkend met aantal 2. Toevoegen?"*
3. Kies **"Ja"** om de soort toe te voegen, of **"Nee"** om te annuleren

### Suggestielijst
Bij onduidelijke herkenning toont VT5 een suggestielijst met kandidaten. Tik op de juiste soort om te selecteren.

---

## 10. Waarneming Annoteren

U kunt waarnemingen annoteren met extra details zoals leeftijd, geslacht, kleed, locatie en hoogte.

### Annotatiescherm Openen
1. **Tik** op een regel in het **Finals-venster** (groene kader)
2. Het `AnnotatieScherm` opent

### Beschikbare Annotaties

| Categorie | Opties |
|-----------|--------|
| **Leeftijd** | adult, 1e-kj, 2e-kj, 3e-kj, onbekend, etc. |
| **Geslacht** | man, vrouw, onbekend |
| **Kleed** | zomer, winter, overgangskleed, etc. |
| **Locatie** | over telpost, passend, rustend, etc. |
| **Hoogte** | < 10m, 10-50m, 50-100m, > 100m, etc. |
| **Richting** | ZW, NO (checkboxes) |
| **Markeren** | Speciale waarneming markeren |
| **Handteller** | Tally-telling modus |

### Aantallen Aanpassen
- **Hoofdrichting** (ZW of NO afhankelijk van seizoen)
- **Tegenrichting**
- **Lokaal** (lokale vogels, niet trekkend)

### Opmerkingen
Veld voor vrije tekst (bijv. bijzondere kenmerken)

### Opslaan
Klik op **"OK"** om de annotatie op te slaan. De annotatie wordt gekoppeld aan de specifieke waarneming.

---

## 11. Alias Aanmaken (Spraakinvoer Opslaan)

Als VT5 een gesproken tekst niet herkent, kunt u deze als **alias** opslaan voor toekomstig gebruik.

### Wanneer Aliassen Gebruiken?
- Regionale namen ("ekster" vs "Euraziatische Ekster")
- Afkortingen ("bui" voor "Buizerd")
- Fonetische varianten ("koolmees" vs "koolmeest")

### Alias Aanmaken
1. **Tik** op een niet-herkende tekst in het **Partials-venster** (blauw kader)
2. Het `AddAliasDialog` opent
3. Kies de **gesproken tekst** (indien meerdere opties)
4. Selecteer de **doelsoort** via autocomplete
5. Klik op **"Toevoegen"**

### Resultaat
- De alias wordt opgeslagen in `alias_master.json`
- De alias-index (`aliases_optimized.cbor.gz`) wordt bijgewerkt
- Volgende keer wordt deze spraakvariant automatisch herkend

### Voorbeeld
```
Gesproken: "bui"
Gekoppeld aan: "Buizerd"

Volgende keer: "bui vijf" → Buizerd +5
```

---

## 12. Soorten Toevoegen tijdens Telling

U kunt extra soorten toevoegen terwijl een telling actief is.

### Soorten Toevoegen
1. Klik op **"+ Soorten"** in het telscherm
2. Het `SoortSelectieScherm` opent
3. Selecteer extra soorten
4. Klik op **"OK"**
5. De nieuwe soorten verschijnen als tegels in het telscherm

### Automatisch Toevoegen via Spraak
Als u via spraak een soort noemt die niet in de tegels zit, biedt VT5 aan om deze toe te voegen (zie sectie 9).

---

## 13. Huidige Stand Scherm

Het `HuidigeStandScherm` toont een overzicht van alle getelde soorten met hun aantallen.

### Scherm Openen
Klik op **"Totalen"** (of "Huidige stand") in het telscherm.

### Overzicht

```
┌────────────────────────────────────────────┐
│          Huidige Stand                     │
├────────────┬────────┬───────┬──────────────┤
│ Soortnaam  │ Totaal │  ZW   │     NO       │
├────────────┼────────┼───────┼──────────────┤
│ Buizerd    │   15   │  12   │      3       │
│ Sperwer    │    8   │   8   │      0       │
│ Vink       │  250   │ 200   │     50       │
│ ...        │  ...   │ ...   │    ...       │
├────────────┴────────┴───────┴──────────────┤
│ Totaal: 523 | ZW: 420 | NO: 103            │
│                                            │
│              [OK - Terug]                  │
└────────────────────────────────────────────┘
```

### Kolommen
- **Soortnaam**: Naam van de soort
- **Totaal**: Som van hoofdrichting + tegenrichting
- **ZW/NO**: Aantallen per richting (labels afhankelijk van seizoen)

### Seizoensafhankelijke Labels
- **ZW-seizoen** (juli-december): Hoofdkolom = "ZW", Terugkolom = "NO"
- **NO-seizoen** (januari-juni): Hoofdkolom = "NO", Terugkolom = "ZW"

### Terug naar Telling
Klik op **"OK"** om terug te keren naar het telscherm.

---

## 14. Telling Afronden

Na het voltooien van uw telling uploadt u de gegevens naar trektellen.nl.

### Afronden
1. Klik op **"Afronden"** in het telscherm
2. VT5 vraagt om bevestiging: *"Weet je zeker dat je wilt afronden?"*
3. Klik op **"Ja"** om te bevestigen

### Upload Proces
1. VT5 bouwt een `counts_save` envelope met:
   - Alle metadata (telpost, datum, tijd, weer)
   - Alle waarnemingen met annotaties
2. De envelope wordt geüpload naar trektellen.nl
3. Bij succes:
   - Lokale backup-bestanden worden opgeruimd
   - De telling wordt opgeslagen in `counts/` als archief
   - U keert terug naar het MetadataScherm

### Foutafhandeling
- Bij netwerkfouten blijven de gegevens lokaal bewaard
- U kunt later opnieuw proberen via **"Afronden"**

### Archief
Afgeronde tellingen worden opgeslagen als:
```
Documents/VT5/counts/<timestamp>_count_<online_id>.json
```

---

## 15. Auto-Weather Systeem

VT5 kan automatisch actuele weergegevens ophalen via GPS en een weer-API.

### Functie Activeren
1. In het `MetadataScherm`, klik op **"Auto Weer"** (of wolk-icoon)
2. Bij eerste gebruik: sta locatiepermissie toe
3. VT5 haalt de huidige locatie op
4. Weergegevens worden automatisch ingevuld

### Automatisch Ingevulde Velden

| Veld | Bron |
|------|------|
| Windrichting | GPS + weer-API (16-punts kompas) |
| Windkracht | Windsnelheid omgezet naar Beaufort |
| Bewolking | Cloud cover % omgezet naar achtsten |
| Neerslag | Precipitation code |
| Temperatuur | Actuele temperatuur in °C |
| Zicht | Visibility in meters |
| Luchtdruk | Sea-level pressure in hPa |

### Na Auto-Weather
- De **"Auto Weer"** knop wordt blauw gekleurd
- De knop wordt uitgeschakeld (om dubbel ophalen te voorkomen)
- U kunt handmatig waarden aanpassen indien nodig

### Weer API
VT5 gebruikt de Open-Meteo API voor weergegevens (gratis, geen API-key nodig).

---

## Veelgestelde Vragen (FAQ)

### Q: De app start niet - wat nu?
**A:** Controleer of u alle permissies heeft toegekend. Ga naar Android Instellingen > Apps > VT5 > Permissies.

### Q: Spraakherkenning werkt niet
**A:** Controleer of microfoonpermissie is toegekend. Zorg voor een rustige omgeving voor betere herkenning.

### Q: Mijn soort wordt niet herkend
**A:** Maak een alias aan (zie sectie 11) of zoek de soort handmatig via de zoekfunctie.

### Q: Data is niet gesynced naar trektellen.nl
**A:** Controleer uw internetverbinding. Open de app en klik op "Afronden" om opnieuw te proberen.

### Q: Ik wil een telling annuleren
**A:** Sluit de app zonder op "Afronden" te klikken. De lokale data blijft bewaard. Gebruik de "Afsluiten" knop in het hoofdscherm voor een veilige afsluiting.

---

## Technische Informatie

- **Minimale Android versie**: Android 13 (API 33)
- **Taalondersteuning**: Nederlands (primair)
- **Offline functionaliteit**: Kernfuncties werken zonder internet
- **Data opslag**: Android SAF (Documents/VT5/)
- **Backend**: www.trektellen.nl

---

## Contact & Support

Voor vragen of problemen, neem contact op met de app-ontwikkelaar.

---

*Versie: 1.0 | Laatste update: 2025*
