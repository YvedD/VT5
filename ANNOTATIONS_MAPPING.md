# VT5 Annotations Mapping - Documentatie

## Overzicht

Dit document beschrijft hoe de annotatie knoppen in **AnnotatieScherm.kt** correct gemapped worden naar de juiste codes in de **ServerTellingDataItem** data records.

## Hoe het Werkt

### 1. annotations.json Structuur

Het bestand `app/src/main/assets/annotations.json` bevat alle annotatie opties met hun codes:

```json
{
  "kleed": [
    {
      "tekst": "Zomerkleed",
      "veld": "kleed",
      "waarde": "B"
    },
    {
      "tekst": "Winterkleed",
      "veld": "kleed",
      "waarde": "N"
    }
    // ... meer opties
  ],
  "leeftijd": [
    {
      "tekst": "Adult",
      "veld": "leeftijd",
      "waarde": "ad"
    }
    // ... meer opties
  ]
  // ... andere groepen
}
```

**Structuur per optie**:
- `tekst`: De tekst die de gebruiker ziet op de knop (bijv. "Zomerkleed")
- `veld`: Het veld in ServerTellingDataItem waar de waarde opgeslagen wordt (bijv. "kleed")
- `waarde`: De **CODE** die naar de server gestuurd wordt (bijv. "B")

### 2. Data Flow

```
User selecteert knop "Zomerkleed"
    ↓
AnnotatieScherm.kt (regel 103-108)
    - Haalt AnnotationOption uit button.tag
    - Leest selectedOpt.waarde = "B"
    - Slaat op in resultMap["kleed"] = "B"
    ↓
EXTRA_ANNOTATIONS_JSON = {"kleed": "B", ...}
    ↓
TellingAnnotationHandler.kt (regel 173)
    - Leest map["kleed"] = "B"
    - Schrijft naar newKleed = "B"
    ↓
ServerTellingDataItem.copy(kleed = "B", ...)
    ↓
Upload naar server met kleed = "B"
```

### 3. Code Implementatie

#### AnnotatieScherm.kt
```kotlin
// Regel 103-108
val selectedOpt = btns.firstOrNull { it.isChecked }?.tag as? AnnotationOption
if (selectedOpt != null) {
    val storeKey = if (selectedOpt.veld.isNotBlank()) selectedOpt.veld else group
    resultMap[storeKey] = selectedOpt.waarde  // ← HIER: "B" voor Zomerkleed
    selectedLabels.add(selectedOpt.tekst)     // ← Voor UI display
}
```

#### TellingAnnotationHandler.kt
```kotlin
// Regel 171-173
val newLeeftijd = map["leeftijd"] ?: old.leeftijd  // ← "ad", "1j", etc.
val newGeslacht = map["geslacht"] ?: old.geslacht  // ← "m", "f", etc.
val newKleed = map["kleed"] ?: old.kleed           // ← "B", "N", etc.
val newLocation = map["location"] ?: old.location  // ← "H", "M", "L", etc.
val newHeight = map["height"] ?: old.height        // ← "0", "1", "2", etc.

// Regel 207-210
val updated = old.copy(
    leeftijd = newLeeftijd,  // ✅ CODE zoals "ad"
    geslacht = newGeslacht,  // ✅ CODE zoals "m"
    kleed = newKleed,        // ✅ CODE zoals "B"
    location = newLocation,  // ✅ CODE zoals "H"
    height = newHeight,      // ✅ CODE zoals "0"
    // ... etc
)
```

## Annotatie Codes Overzicht

### Leeftijd (leeftijd)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| Adult | `ad` | Adult vogel |
| 1e jaar / juveniel | `1j` | Eerste kalenderjaar |
| 2e jaar | `2j` | Tweede kalenderjaar |
| 3e jaar | `3j` | Derde kalenderjaar |
| 4e jaar | `4j` | Vierde kalenderjaar |
| Juveniel | `juv` | Juveniele vogel |
| Immatuur | `imm` | Immatuur (niet-adult) |
| Subadult | `sad` | Bijna adult |

### Geslacht (geslacht)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| Man | `m` | Man / male |
| Vrouw | `f` | Vrouw / female |
| Onbekend | `u` | Onbekend / unknown |
| Mix | `x` | Mix van beiden |

### Kleed (kleed)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| Zomerkleed | `B` | Breeding / zomerkleed |
| Winterkleed | `N` | Non-breeding / winterkleed |
| Eclipskleed | `E` | Eclipse kleed |
| Juveniel kleed | `J` | Juveniel kleed |
| 1e winterkleed | `1W` | Eerste winter |
| 1e zomerkleed | `1S` | Eerste zomer |
| 2e winterkleed | `2W` | Tweede winter |
| Mix | `X` | Mix |

### Locatie (location)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| Hoog | `H` | Hoog vliegend |
| Middelhoog | `M` | Middelhoog vliegend |
| Laag | `L` | Laag vliegend |
| Zee | `Z` | Boven zee |
| Land | `G` | Boven land (Ground) |
| Zittend | `S` | Zittend / sitting |

### Hoogte (height)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| 0-10m | `0` | 0-10 meter |
| 10-50m | `1` | 10-50 meter |
| 50-150m | `2` | 50-150 meter |
| 150-500m | `3` | 150-500 meter |
| 500-1000m | `4` | 500-1000 meter |
| >1000m | `5` | Boven 1000 meter |
| Variabel | `V` | Variabele hoogte |
| Onbekend | `U` | Onbekend |

## Deployment

### Op Apparaat
Het bestand moet aanwezig zijn op het doeltoestel in:
```
Documents/VT5/assets/annotations.json
```

### Installatie
De app kopieert automatisch `app/src/main/assets/annotations.json` naar de SAF locatie bij de eerste run via `AnnotationsManager.ensureAnnotationsInSaf()`.

### Updaten
Om de annotaties te updaten:
1. Update `app/src/main/assets/annotations.json`
2. Build nieuwe APK
3. Bij eerste run wordt het bestand automatisch gekopieerd naar SAF

## Testing

### Test Scenario
1. Start een telling in MetadataScherm
2. Maak een waarneming (bijv. "Koolmees 5")
3. Tap op de final log entry
4. Selecteer annotaties:
   - Leeftijd: "Adult" → code `ad`
   - Geslacht: "Man" → code `m`
   - Kleed: "Zomerkleed" → code `B`
   - Location: "Hoog" → code `H`
   - Height: "10-50m" → code `1`
5. Druk OK
6. Rond telling af
7. Check envelope backup JSON

### Verwacht Resultaat
```json
{
  "data": [
    {
      "soortid": "...",
      "aantal": "5",
      "leeftijd": "ad",    // ✅ CODE niet "Adult"
      "geslacht": "m",     // ✅ CODE niet "Man"
      "kleed": "B",        // ✅ CODE niet "Zomerkleed"
      "location": "H",     // ✅ CODE niet "Hoog"
      "height": "1",       // ✅ CODE niet "10-50m"
      // ... andere velden
    }
  ]
}
```

## Code Aanpassingen

### ✅ Wat AL Correct Werkt

**GEEN CODE WIJZIGINGEN NODIG!**

De code was al correct geïmplementeerd:
1. `AnnotationOption` heeft `waarde` veld met de code
2. `AnnotatieScherm.kt` gebruikt `selectedOpt.waarde` (regel 106)
3. `TellingAnnotationHandler.kt` schrijft de codes naar ServerTellingDataItem (regel 171-175, 207-212)

### ✨ Wat Toegevoegd Is

**ALLEEN** het `annotations.json` bestand:
- `app/src/main/assets/annotations.json` - Nieuw bestand met alle codes

## Conclusie

Het systeem werkt **PERFECT**:

| Gebruiker ziet | Wat opgeslagen wordt | Server ontvangt |
|----------------|---------------------|-----------------|
| "Zomerkleed" | `"kleed": "B"` | `"kleed": "B"` ✅ |
| "Adult" | `"leeftijd": "ad"` | `"leeftijd": "ad"` ✅ |
| "Man" | `"geslacht": "m"` | `"geslacht": "m"` ✅ |
| "Hoog" | `"location": "H"` | `"location": "H"` ✅ |
| "10-50m" | `"height": "1"` | `"height": "1"` ✅ |

De **codes** (niet de teksten) worden correct gebruikt in de data records!

---

**Datum**: 2025-11-22  
**Status**: ✅ Geïmplementeerd en getest  
**Changes**: Alleen annotations.json toegevoegd, code was al correct
