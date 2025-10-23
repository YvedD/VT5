# VT5 CBOR- en JSON-schema documentatie (Nederlands)

Dit document beschrijft het binaire CBOR‑schema en de bijbehorende JSON‑artefacten die door de VT5 alias précompute‑pipeline worden aangemaakt. Het is bedoeld als één geïntegreerde, ontwikkelaarvriendelijke referentie zodat toekomstige onderhouders snel begrijpen welke velden, types en keuzes er gemaakt werden tijdens het précomputen.

Bewaar dit bestand bij voorkeur naast de gegenereerde bestanden (bijv. `VT5/serverdata/README_cbor_schema.md`) zodat toekomstige refactors er direct naar kunnen verwijzen.

---

## Overzicht — bestanden en locaties (SAF: Documents/VT5)
Alle lees- en schrijfacties gebeuren via SAF (Documents/VT5). De précompute produceert de volgende outputbestanden (exacte paden):

- `Documents/VT5/assets/alias_index.json`
  - Menselijk leesbare JSON‑index (lichte weergave). `simhash64` is bewust weggelaten uit deze JSON.
- `Documents/VT5/assets/alias_index.json.gz`
  - Optionele GZIP‑versie van de JSON.
- `Documents/VT5/serverdata/aliases_flat.schema.json`
  - JSON samenvatting / schema van de aliassen (leesbaar).
- `Documents/VT5/serverdata/species_master.schema.json`
  - JSON samenvatting voor species_master.
- `Documents/VT5/serverdata/phonetic_map.schema.json`
  - Leesbare map phonetic_code -> aliasId lijst.
- `Documents/VT5/serverdata/manifest.schema.json`
  - Manifest met bronbestanden, paden, SHA‑256 checksums en groottes (nummers als strings waar server dat vereist).
- `Documents/VT5/binaries/aliases_flat.cbor.gz`
  - HOOFDBESTAND in CBOR, gzipped. Bevat de VOLLEDIGE data voor runtime (inclusief zware velden: minhash64, simhash64, phonemes, beidermorse, double_metaphone, cologne).
- `Documents/VT5/binaries/species_master.cbor.gz`
  - CBOR.gz met species_master (compleet).
- `Documents/VT5/exports/alias_precompute_log_<timestamp>.txt`
  - Menselijk leesbare log van de run.

Belangrijk projectbeleid:
- JSON‑artefacten zijn bedoeld voor mensen / web‑tools en om handmatig te inspecteren of bewerken. Houd ze compacte en leesbaar.
- CBOR‑artefacten zijn runtime‑geoptimaliseerd en bevatten de zware gegevens die nodig zijn voor fuzzy matching en snelle laadprestaties.

---

## CBOR‑encodering keuzes en conventies

- CBOR‑standaard: RFC 8949. We gebruiken `kotlinx.serialization.cbor` om data classes te (de)serialiseren. Na serialisatie wordt het resultaat gzipped (`.cbor.gz`).
- Determinisme: Standaard garandeert `kotlinx.serialization.cbor` geen byte‑identieke output tussen runs (key‑volgorde in maps). Als je bit‑voor‑bit identieke CBOR wilt (bv. voor ondertekening), moet je een deterministische encoder of een key‑sortering toepassen vóór serialisatie.
- Numerieke types:
  - MinHash‑waarden: 64‑bit integers (`Long`) gebruikt. Bij vergelijkingen die unsigned semantics vereisen, gebruik consistente unsigned routines.
  - SimHash: opgeslagen als 64‑bit integer in CBOR (efficiënt voor bitwise operaties). In JSON tonen we doorgaans een hex‑string of we laten het weg (in onze opdracht is simhash in JSON weggelaten).
- Strings: UTF‑8 strings (CBOR major type 3).
- Arrays en maps: standaard CBOR arrays/maps gebruikt voor lijsten en objecten (tokens, ngrams, dmetaphone arrays, etc).

---

## Data‑model (CBOR) — AliasRecord (in `aliases_flat.cbor`)
Elke alias entry (AliasRecord) is een object in de root array van `aliases_flat.cbor.gz`. Velden die in CBOR aanwezig zijn:

- aliasId — string
  - Unieke identifier voor het alias‑record (strings voor servercompatibiliteit).
- speciesId — string
- canonical — string
- tilename — string | null
- alias — string
- norm — string (genormaliseerde representatie)
- tokens — array[string]
- cologne — string | null
- double_metaphone — array[string] | null
- beidermorse — array[string] | null
- phonemes — string | null (G2P, IPA‑achtig)
- ngrams — object { "q": int, "grams": array[string] }
- minhash64 — array[int64] (K MinHash signatures; zwaar)
- simhash64 — int64 (64‑bit integer; BIJZONDER: dit veld is in CBOR aanwezig maar NIET in de JSON export)
- weight — float / double
- flags — map[string -> bool] (bijv. isCanonical, isTilename)
- meta — map[string -> string] (bv. source en line)

Opmerking:
- Omdat `minhash64` groot kan worden (K × aantal aliassen), bewaren we die alleen in CBOR. Dat voorkomt enorme JSON‑bestanden en houdt `alias_index.json` beheersbaar.

Voorbeeld (illustratief — in werkelijkheid CBOR):
```json
{
  "aliasId":"5",
  "speciesId":"453",
  "canonical":"alk of zeekoet",
  "tilename":"Alk/Zeekoet",
  "alias":"alk of zeekoet",
  "norm":"alk of zeekoet",
  "tokens":["alk","of","zeekoet"],
  "cologne":"ALKFZKT",
  "double_metaphone":["ALKFZKT","ALKFZK2"],
  "beidermorse":["alk-of-zeekoet"],
  "phonemes":"a l k o f z eː k u t",
  "ngrams":{"q":3,"grams":["alk","lk ","k o"," of","of ","f z"," ze","zee","eek","eko","koe","oet"]},
  "minhash64":[1234001,-2345002,3456003,...],
  "simhash64":1234567890123456789,
  "weight":0.9,
  "flags": {"isCanonical":true,"isTilename":true,"isCompound":true,"isUserAdded":false},
  "meta":{"source":"aliasmapping.csv","line":"3"}
}
```

---

## Data‑model (CBOR/JSON) — species_master
Een array van species records:
- speciesId — string
- soortnaam — string
- tilename — string
- sortering — string | null
- aliases — array[string] (lijst met aliasId's)
- notes — string | null
- meta — map[string -> string]

Gebruik dit bestand voor UI‑weergave en om snel species ↔ aliassen relaties op te halen.

---

## phonetic_map (`serverdata/phonetic_map.schema.json`)
- Vorm: map van key -> array(aliasId)
  - Key = "<algorithm>:<code>", bijv. `cologne:ALKFZKT`, `dmetaphone:ALK`
  - Value = array met aliasId strings, bijv. `["5","23"]`
- Doel: snelle candidate‑reductie op basis van fonetische codes.
- Dit bestand verschijnt als JSON in `VT5/serverdata` voor eenvoudige inspectie. Indien gewenst kan een binaire CBOR‑variant later toegevoegd worden voor snellere runtime loads.

---

## manifest.schema.json
Velden (serververwachting: veel velden als strings, vooral groottes):

- version — string (converterversie)
- generated_at — ISO8601 timestamp
- sources — object met bronbestanden → { "sha256": "<hex>", "size":"<bytes-as-string>" }
- indexes — object met indexbestanden → { "path":"Documents/VT5/..", "sha256":"<hex>", "size":"<bytes-as-string>", "aliases_count":"<string>" }
- counts — { "aliases": <int>, "species": <int> } (aantallen)
- notes — string

Voorbeeldstructuur:
```json
{
  "version":"v1",
  "generated_at":"2025-10-21T19:57:04Z",
  "sources": { "aliasmapping.csv": { "sha256":"...", "size":"12345" } },
  "indexes": { "aliases_flat": { "path":"Documents/VT5/binaries/aliases_flat.cbor.gz", "sha256":"...", "size":"123456", "aliases_count":"729" } },
  "counts": { "aliases":729, "species": 182 },
  "notes":"Generated on-device"
}
```

---

## Waarom JSON ↔ CBOR splitsen?
- JSON = leesbaar, handmatig navigeerbaar, bruikbaar voor webtools en snelle inspectie.
- CBOR = compact, sneller te deserialiseren en ruimt efficiente opslag van binaire/numerieke arrays (minhash64, simhash64).
- Door simhash64 uit JSON te laten en in CBOR op te nemen, houden we JSON bestandsformaten praktisch, terwijl runtime‑algoritmen volledig gebruik kunnen maken van de zware velden in CBOR.

---

## Aanbevolen runtime‑patronen (Kotlin)
- Gebruik `@Serializable` data classes die exact overeenkomen met de CBOR‑structuur.
- Schrijf CBOR: `val bytes = Cbor.encodeToByteArray(AliasIndex.serializer(), aliasIndex)` → gzip → SAF write.
- Lees CBOR: SAF read → gunzip → `Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)`
- Gebruik CBOR voor volledige matching (minhash/simhash), en JSON alleen voor user‑facing lists of debug.

---

## Inspectie van CBOR buiten de app
- Python:
  - `pip install cbor2`
  - Voorbeeld:
    ```py
    import gzip, cbor2
    with gzip.open("aliases_flat.cbor.gz","rb") as f:
        obj = cbor2.load(f)
    print(obj)
    ```
- Node.js: gebruik `cbor` package na gunzip.
- Converteer naar JSON voor debugging; bewaar CBOR voor runtime.

---

## Precompute regels & preflight
- Start précompute als:
  - `Documents/VT5/assets/aliasmapping.csv` is aangepast.
  - `Documents/VT5/serverdata/species.json` of `site_species.json` is aangepast.
- Preflight controleert:
  - aanwezigheid van vereiste SAF‑bestanden,
  - optioneel: SHA256 van inputbestanden versus manifest om te bepalen of regeneratie nodig is.
- Schrijfoperaties zijn per‑bestand atomisch: schrijf naar `.tmp` en hernoem naar eindnaam.

---

## Versiebeheer & migratie
- Houd `manifest.schema.json` up‑to‑date (versieveld) zodat oudere loaders schema‑verschillen herkennen.
- Bij schema‑wijzigingen:
  - incrementeer `version`,
  - bewaar oude CBOR bestanden of lever migratiescripts mee.

---

## Veiligheid & cleanup
- Deze pipeline schrijft UITSLUITEND naar SAF‑paden onder `Documents/VT5`. Er worden geen app‑private indexbestanden meer aangemaakt.
- Als er nog interne bestanden (oude runs) aanwezig zijn, kun je die met Device File Explorer verwijderen of een tijdelijke debug‑knop gebruiken (alleen in debug builds).

---

## Appendix: korte Kotlin snippets

CBOR → gzip → SAF schrijven:
```kotlin
val bytes = Cbor.encodeToByteArray(AliasIndex.serializer(), aliasIndex)
val gz = gzip(bytes)
writeBytesToSaFOverwrite(context, saf, "binaries", "aliases_flat.cbor.gz", gz, "application/gzip")
```

Lees CBOR vanaf SAF:
```kotlin
val gzBytes = readBytesFromSaF(uri)
val bytes = gunzip(gzBytes)
val aliasIndex = Cbor.decodeFromByteArray(AliasIndex.serializer(), bytes)
```

---

Plaatsing en commit:
- Kopieer deze markdown naar `Documents/VT5/serverdata/README_cbor_schema.md` of commit het in je repository bij `app/serverdata/README_cbor_schema.md` voor blijvende documentatie.

---

Als je wilt, maak ik nu direct een committable Markdown‑bestand (tekstblok) dat je eenvoudig in je repo kunt plakken — of ik open een PR met de wijzigingen (als je toegang wilt). Welke optie heeft je voorkeur?