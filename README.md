# VT5 — CBOR- en JSON‑schema documentatie (Nederlands)

Dit document beschrijft de CBOR‑ en JSON‑artefacten die de VT5 alias précompute‑pipeline produceert en gebruikt. Het is bedoeld als ontwikkelaarsreferentie zodat toekomstige onderhouders snel begrijpen welke bestanden, velden en keuzes er gemaakt werden tijdens het précomputen.

Bewaar dit bestand bij voorkeur naast de gegenereerde bestanden (bijv. `Documents/VT5/serverdata/README_cbor_schema.md`) zodat refactors er direct naar kunnen verwijzen.

---

## Kort overzicht — bestanden en locaties (SAF: Documents/VT5)

Alle lees- en schrijfacties gebeuren via SAF (Documents/VT5). De précompute / export produceert en gebruikt de volgende outputbestanden (exacte paden):

- `Documents/VT5/assets/alias_master.json`
  - Menselijk leesbare, canonieke masterlijst per soort (species → aliases). Dit is de canonical source voor het systeem (readable, bewerkbaar).
- `Documents/VT5/assets/alias_index.json`
  - Compacte, export‑georiënteerde JSON (lijst van alias‑records, zonder zware binaire velden). Handig voor web‑tools en debugging.
- `Documents/VT5/assets/alias_index.json.gz`
  - Optionele GZIP‑versie van de bovenstaande JSON.
- `Documents/VT5/serverdata/aliases_schema.json`
  - Leesbaar schema/summary van de alias‑index (menselijk / tools).
- `Documents/VT5/serverdata/species_master.schema.json`
  - Leesbaar schema/summary voor het species‑overzicht.
- `Documents/VT5/serverdata/phonetic_map.schema.json`
  - Leesbare mapping phonetic_code → aliasId lijst (voor inspectie).
- `Documents/VT5/serverdata/manifest.schema.json`
  - Manifest met bronbestanden, paden, SHA‑256 checksums en groottes. Gebruik dit om te bepalen of regeneratie nodig is.
- `Documents/VT5/assets/alias_master.meta.json`
  - Metadata over de gegenereerde master (bv. `sourceChecksum`, `sourceFiles`, `timestamp`).
- `Documents/VT5/binaries/aliases_optimized.cbor.gz`
  - HOOFDBESTAND voor runtime in CBOR (gzipped). Bevat de volledige, runtime‑geoptimaliseerde data inclusief zware binaire/numerieke velden die niet in JSON opgenomen zijn.
- `Documents/VT5/binaries/species_master.cbor.gz`
  - CBOR.gz met species_master (compleet).
- `Documents/VT5/exports/alias_precompute_log_<timestamp>.txt`
  - Menselijk leesbare log van een précompute‑run.

Belangrijk projectbeleid:
- JSON‑artefacten zijn bedoeld voor mensen en tooling; houd ze compact en leesbaar.
- CBOR‑artefacten zijn runtime‑geoptimaliseerd en bevatten zware velden (minhash, simhash, phonemes, etc.) die JSON onpraktisch groot of onhandig zouden maken.
- CSV‑ondersteuning is uit de runtime verwijderd. Eventuele legacy CSV‑migraties moeten offline uitgevoerd worden en resulteren in een geldige `alias_master.json` en (optioneel) in `aliases_optimized.cbor.gz`.

---

## CBOR‑encodering keuzes en conventies

- CBOR‑standaard: RFC 8949. We gebruiken `kotlinx.serialization.cbor` in Kotlin om data classes te (de)serialiseren. Na serialisatie wordt het resultaat gzipped (`.cbor.gz`).
- Determinisme: `kotlinx.serialization.cbor` garandeert niet per se byte‑identieke output tussen runs (maps kunnen in volgorde verschillen). Voor bit‑voor‑bit identieke output (bv. ondertekening) moet je keys sorteren of een deterministische encoder gebruiken.
- Numerieke types:
  - MinHash‑waarden: 64‑bit integers (`Long`).
  - SimHash: 64‑bit integer (`Long`) — nuttig voor bit‑operaties; we bewaren dit in CBOR (niet in de JSON export).
- Strings: UTF‑8 strings (CBOR major type 3).
- Arrays en maps: standaard CBOR arrays/maps voor lijsten en objecten.

---

## Data‑model (CBOR) — AliasRecord (in `aliases_optimized.cbor.gz`)

Elke alias entry (AliasRecord) is een object in de root‑array van `aliases_optimized.cbor.gz`. Voor runtime bevatten records meestal de volgende velden (veld‑namen kunnen in code small‑caps/underscored zijn; hieronder leesbaar weergegeven):

- aliasId — string
  - Unieke identifier voor het alias‑record.
- speciesId — string
- canonical — string
- tilename — string | null
- alias — string
- norm — string (genormaliseerde representatie)
- tokens — array[string]
- cologne — string | null
- double_metaphone — array[string] | null
- beidermorse — array[string] | null
- phonemes — string | null (G2P / fonetische representatie)
- ngrams — object { "q": int, "grams": array[string] } | null
- minhash64 — array[int64] (K MinHash signatures) — alleen in CBOR
- simhash64 — int64 — alleen in CBOR (wordt niet in de JSON export opgenomen)
- weight — float / double
- flags — map[string -> bool] (bijv. isCanonical, isTilename)
- meta — map[string -> string] (bv. source, line, of "generated_on_device")

Opmerking:
- Omdat `minhash64` en `simhash64` potentieel veel ruimte innemen, bewaren we deze zware velden uitsluitend in CBOR. Dat houdt JSON‑exports beheersbaar en geschikt voor debugging.

Illustratief JSON‑voorbeeld (ter verduidelijking — in werkelijkheid staat dit binair in CBOR):
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
  "minhash64":[1234001,-2345002,3456003],
  "simhash64":1234567890123456789,
  "weight":0.9,
  "flags":{"isCanonical":true,"isTilename":true},
  "meta":{"source":"generated_on_device","note":"seed-from-species.json"}
}