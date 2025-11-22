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
      "tekst": "zomerkleed",
      "veld": "kleed",
      "waarde": "B"
    },
    {
      "tekst": "winterkleed",
      "veld": "kleed",
      "waarde": "W"
    }
    // ... meer opties
  ],
  "leeftijd": [
    {
      "tekst": "adult",
      "veld": "leeftijd",
      "waarde": "A"
    }
    // ... meer opties
  ]
  // ... andere groepen
}
```

**Structuur per optie**:
- `tekst`: De tekst die de gebruiker ziet op de knop (bijv. "zomerkleed")
- `veld`: Het veld in ServerTellingDataItem waar de waarde opgeslagen wordt (bijv. "kleed")
- `waarde`: De **CODE** die naar de server gestuurd wordt (bijv. "B")

**Let op**: In de echte annotations.json staan de height en location velden gekruist gemapped:
- `"height"` items mappen naar `"veld": "location"`
- `"location"` items mappen naar `"veld": "height"`

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
| adult | `A` | Adult vogel |
| juveniel | `J` | Juveniel |
| >1kj | `I` | Meer dan 1 kalenderjaar |
| 1kj | `1` | Eerste kalenderjaar |
| 2kj | `2` | Tweede kalenderjaar |
| 3kj | `3` | Derde kalenderjaar |
| 4kj | `4` | Vierde kalenderjaar |
| niet juv. | `Non-Juv` | Niet juveniel |

### Geslacht (geslacht)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| man | `M` | Man / male |
| vrouw | `F` | Vrouw / female |
| vrouwkleed | `FC` | Vrouwkleed |

### Kleed (kleed)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| zomerkleed | `B` | Breeding / zomerkleed |
| winterkleed | `W` | Winterkleed |
| man | `M` | Man kleed |
| vrouw | `F` | Vrouw kleed |
| licht | `L` | Licht kleed |
| donker | `D` | Donker kleed |
| eclips | `E` | Eclipse kleed |
| intermediar | `I` | Intermediair kleed |

### Teltype
| Tekst | Code | Betekenis |
|-------|------|-----------|
| Handteller | `C` | Handteller gebruikt |

### Hoogte (height → veld: location)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| <25m | `<25m` | Minder dan 25 meter |
| <50m | `<50m` | Minder dan 50 meter |
| 50-100m | `50-100m` | 50-100 meter |
| 100-200m | `100-200m` | 100-200 meter |
| >200m | `>200m` | Meer dan 200 meter |

### Locatie (location → veld: height)
| Tekst | Code | Betekenis |
|-------|------|-----------|
| zee | `zee` | Boven zee |
| branding | `branding` | In/bij branding |
| duinen | `duinen` | Boven duinen |
| binnenkant | `binnenkant` | Binnenkant |
| polders | `polders` | Boven polders |
| bos | `bos` | Boven bos |
| over water | `over water` | Over water |

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
   - Leeftijd: "adult" → code `A`
   - Geslacht: "man" → code `M`
   - Kleed: "zomerkleed" → code `B`
   - Height: "<50m" → code `<50m` (gaat naar location veld!)
   - Location: "zee" → code `zee` (gaat naar height veld!)
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
      "leeftijd": "A",        // ✅ CODE niet "adult"
      "geslacht": "M",        // ✅ CODE niet "man"
      "kleed": "B",           // ✅ CODE niet "zomerkleed"
      "location": "<50m",     // ✅ CODE (van height knop!)
      "height": "zee",        // ✅ CODE (van location knop!)
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
| "zomerkleed" | `"kleed": "B"` | `"kleed": "B"` ✅ |
| "adult" | `"leeftijd": "A"` | `"leeftijd": "A"` ✅ |
| "man" | `"geslacht": "M"` | `"geslacht": "M"` ✅ |
| "<50m" (height knop) | `"location": "<50m"` | `"location": "<50m"` ✅ |
| "zee" (location knop) | `"height": "zee"` | `"height": "zee"` ✅ |

De **codes** (niet de teksten) worden correct gebruikt in de data records!

---

**Datum**: 2025-11-22  
**Status**: ✅ Geïmplementeerd en getest  
**Changes**: Alleen annotations.json toegevoegd, code was al correct
