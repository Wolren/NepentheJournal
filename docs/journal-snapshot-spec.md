# JournalSnapshot format specification

**Schema version:** 5  
**File:** `schemas/journal-snapshot-v5.json` (JSON Schema draft 2020-12)  
**Seed data:** `scripts/seed.json` (version 3, being migrated to 5)  
**License:** GPLv3 (the schema spec itself is CC0)

---

## 1. Overview

JournalSnapshot is the interchange format for Nepenthe Journal's data. It is a single JSON file that contains the full state of a journal: substances, sessions, doses, notes, interactions, effects, timeline events, and custom units, plus app settings.

The format is designed to be:

- **Self-contained** — one file holds every entity. No external references.
- **Append-only friendly** — new entities are additive. Existing entities are replaced by ID. Deletes happen separately (via tombstone or file rewrite).
- **Sync-compatible** — every entity carries a `deviceOrigin` and timestamps for conflict resolution.
- **Typeable** — the JSON Schema generates Kotlin data classes, TypeScript interfaces, and Rust structs automatically.

---

## 2. Version history

| Version | Changes |
|---------|---------|
| 1       | Initial format. Substances, sessions, doses, interactions, effects. |
| 2       | Added timeline events, notes, custom units. |
| 3       | CID-based substance IDs, `oldId` migration field, multi-source pharmacology blocks (ChEMBL, IUPHAR, PDSP, BindingDB). |
| 4       | Obsidian export settings (`obsidianVaultPath`, `obsidianAutoExport`, `obsidianSubfolder`, `obsidianFileOrganization`). |
| 5       | Added `showSessionsTrendChart`, `useShulginRating`, `useSubstanceColors` to snapshot settings. Schema published as standalone JSON Schema. |

**Migration policy:**
- The app loads any version >= 1 with `ignoreUnknownKeys = true`.
- On write, the app always writes `CURRENT_VERSION` (currently 5).
- Unknown fields are preserved during load-write cycles when using JournalJson.
- There is no automated data migration between versions — each version adds fields that are optional (`null` or `default` values are acceptable).

---

## 3. Top-level structure

```json
{
  "version": 5,
  "savedAt": 1783942757808,
  "sessions": [ ... ],
  "substances": [ ... ],
  "doses": [ ... ],
  "notes": [ ... ],
  "timelineEvents": [ ... ],
  "interactions": [ ... ],
  "effects": [ ... ],
  "customUnits": [ ... ],
  "useShulginRating": false,
  "useSubstanceColors": true,
  "obsidianVaultPath": "",
  "obsidianAutoExport": false,
  "obsidianSubfolder": "Nepenthe",
  "obsidianFileOrganization": "flat",
  "showSessionsTrendChart": false
}
```

### Required fields

| Field | Type | Description |
|-------|------|-------------|
| `version` | `int` | Schema version. Must be >= 1. Consumers should warn on mismatch with their expected version. |
| `savedAt` | `int` | Unix millisecond timestamp of serialization. UTC. |

All array fields default to `[]` if omitted. Settings fields default to their documented defaults. The file must contain at least one of `substances` or `sessions` to be meaningful.

---

## 4. Entity model: VaultDocument

Every entity in the snapshot extends the `VaultDocument` base interface:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | Stable unique identifier. Substance IDs use `cid:{PubChemCID}` (e.g. `cid:5761`). User-created entities use device-keyed UUIDs. |
| `docType` | `string` | Discriminator. One of: `substance`, `session`, `dose`, `note`, `interaction`, `effect`, `timelineEvent`, `customUnit`. |
| `createdAt` | `int` | Unix millisecond creation timestamp. UTC. |
| `updatedAt` | `int` | Unix millisecond last-modified timestamp. UTC. |
| `deviceOrigin` | `string` | Device that created this document. `system` for bundled seed data. Device fingerprint for user content. Used in sync conflict resolution. |

---

## 5. Entity: Substance

A substance in the library. Pure compounds are keyed by PubChem CID. Plants and mixtures without a single PubChem CID use `pwiki:{name}` fallback.

### Identity fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `string` | yes | `cid:{PubChemCID}` for pure compounds, `pwiki:{name}` for plants/mixtures. |
| `oldId` | `string` | no | Previous ID before CID migration. Preserved for session reference compatibility. |
| `cid` | `int` | no | PubChem Compound ID. Null for plants/mixtures. |
| `pwikiId` | `string` | no | Original PsychonautWiki page title (with underscores). |

### Metadata fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | `string` | — | Display name. Usually the common English name. |
| `aliases` | `string[]` | `[]` | Alternative names, street names, abbreviations from PsychonautWiki, TripSit, PubChem, Wikidata. |
| `summary` | `string` | `null` | Paragraph-length substance description from PsychonautWiki SMW. |
| `substanceClass` | `string[]` | `[]` | Chemical families (e.g. `Lysergamides`) and effect categories (e.g. `Psychedelic`). Not normalized across sources — expect duplicates. |
| `routesOfAdministration` | `string[]` | `[]` | Valid administration routes. Values: `Oral`, `Sublingual`, `Insufflated`, `Inhalation`, `Smoked`, `Intravenous`, `Intramuscular`, `Subcutaneous`, `Rectal`, `Transdermal`, `Buccal`, `Topical`, `Ophthalmic`. |
| `erowidUrl` | `string` | `""` | Erowid experience vault URL. |

### Pharmacology data fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `dosageBands` | `map<string,string>` | `{}` | Typical dose ranges per route. Keys are route names, values are string-formatted dose bands. |
| `durationProfile` | `map<string,string>` | `{}` | Duration info per route. Keys are route names, values contain onset/comeup/peak/offset/total durations as text. |
| `addictionPotential` | `string` | `null` | Addiction potential classification. |
| `toxicity` | `string[]` | `[]` | Toxicity warnings. |
| `crossTolerances` | `string[]` | `[]` | Substance names this has cross-tolerance with. |
| `effects` | `string[]` | `[]` | Effect labels from SMW. Unstructured — use DoseWiki data for structured effects. |
| `interactionClasses` | `string[]` | `[]` | Interaction pharmacology classes. Valid values: `maoi`, `ssri`, `serotonin_releaser`, `serotonergic`, `stimulant`, `depressant`, `benzodiazepine`, `opioid`, `psychedelic`, `dissociative`, `lithium`, `cannabinoid`, `deliriant`. |

### Chemical properties (from PubChem)

| Field | Type | Description |
|-------|------|-------------|
| `cid` | `int` | PubChem CID. |
| `molecularFormula` | `string` | Hill system formula (e.g. `C20H25N3O`). |
| `molecularWeight` | `string` | Weight in daltons, stored as string to preserve precision. |
| `smiles` | `string` | Canonical SMILES. |
| `inchiKey` | `string` | Standard InChIKey hash. |
| `iupacName` | `string` | Preferred IUPAC name. |
| `xlogP` | `float` | Predicted LogP (octanol/water partition). |
| `hBondDonorCount` | `int` | H-bond donor count. |
| `hBondAcceptorCount` | `int` | H-bond acceptor count. |

### Cross-database identifiers

| Field | Type | Source | Example |
|-------|------|--------|---------|
| `chemblId` | `string` | Wikidata | `CHEMBL263881` |
| `drugbankId` | `string` | Wikidata | `DB04829` |
| `iupharId` | `string` | Wikidata | `17` |
| `chemspiderId` | `string` | Wikidata | `5554` |
| `unii` | `string` | Wikidata | `8NA5SWF92O` |
| `chebiId` | `string` | Wikidata | `CHEBI:6605` |
| `atcCode` | `string` | Wikidata | `N05AD01` |

### Bioactivity blocks

| Field | Type | Description |
|-------|------|-------------|
| `chemblData` | `ChemblData` | ChEMBL molecule properties + bioactivity measurements (IC50/Ki/EC50/Kd) at molecular targets. |
| `iupharData` | `IupharData` | IUPHAR/BPS GtoPdb ligand-target interactions with affinity data (pKi/pIC50/pEC50). |
| `pdspData` | `PdspData` | PDSP Ki database binding affinities (Ki in nM) at CNS targets. |
| `bindingdbData` | `BindingdbData` | BindingDB experimental binding affinities (Ki/Kd/IC50/EC50) at protein targets. |

### Provenance fields

| Field | Type | Description |
|-------|------|-------------|
| `sources` | `string[]` | Which data sources contributed: `psychonautwiki`, `pubchem`, `tripsit`, `wikidata`, `chembl`, `iuphar`, `pdsp`, `bindingdb`. |
| `cachedAt` | `int` | When this record was last refreshed from source APIs. |
| `sourceVersion` | `string` | ETL pipeline run ID, e.g. `pwiki-smw-v2`. |
| `userAnnotations` | `map<string,string>` | User key-value annotations. Preserved across sync. |

---

## 6. Entity: Session

A journal session representing a single experience. Groups doses, timeline events, notes, and check-ins.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Device-keyed UUID. |
| `title` | `string` | yes | — | Session title (auto-generated from substance + date, user-editable). |
| `startTime` | `int` | yes | — | Unix ms timestamp of first dose. |
| `endTime` | `int` | no | `null` | Unix ms timestamp of session end. Null while live. |
| `tags` | `string[]` | no | `[]` | User tags for filtering. |
| `set` | `string` | no | `null` | Mindset before session. |
| `setting` | `string` | no | `null` | Physical/social environment. |
| `intention` | `string` | no | `null` | Session intention. |
| `outcome` | `string` | no | `null` | Session outcome reflection. |
| `notes` | `string` | no | `null` | Free-text notes. |
| `rating` | `int` | no | `null` | 1-10 numeric rating (when Shulgin is off). |
| `shulginRating` | `string` | no | `null` | Shulgin scale: `+/-`, `+`, `++`, `+++`, `++++`. |
| `checkins` | `CheckIn[]` | no | `[]` | In-session check-in snapshots. |
| `isArchived` | `bool` | no | `false` | Hidden from main list. |
| `isFavorite` | `bool` | no | `false` | Marked as favorite. |
| `consumerName` | `string` | no | `null` | Person who took the substances (for multi-user journals). |

### CheckIn

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `timestamp` | `int` | yes | Unix ms timestamp of check-in. |
| `overallIntensity` | `float` | yes | 0.0-1.0 intensity score. |
| `effectScores` | `map<string,float>` | no | Per-effect intensity scores. |
| `mood` | `string` | no | Mood label. |
| `notes` | `string` | no | Check-in notes. |

---

## 7. Entity: Dose

An individual ingestion. Belongs to one session, references one substance.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Device-keyed UUID. |
| `sessionId` | `string` | yes | — | Parent session ID. |
| `substanceId` | `string` | yes | — | Substance ID (e.g. `cid:5761`). |
| `routeOfAdministration` | `string` | yes | — | Route (see substance routes list). |
| `amount` | `float` | yes | — | Dose amount. |
| `unit` | `string` | yes | — | Unit: `mg`, `µg`, `ug`, `ml`, or custom. |
| `timestamp` | `int` | yes | — | Unix ms ingestion time. |
| `redosing` | `bool` | no | `false` | True = this is a redose. |
| `notes` | `string` | no | `null` | Dose-specific notes. |
| `isDoseEstimate` | `bool` | no | `false` | Amount is estimated. |
| `estimatedDoseStandardDeviation` | `float` | no | `null` | ± amount uncertainty. |
| `customUnitId` | `string` | no | `null` | Links to a CustomUnit definition. |
| `stomachFullness` | `string` | no | `null` | Enum: `Empty`, `Light snack`, `Moderate meal`, `Full meal`. |

---

## 8. Entity: Interaction

A pairwise substance interaction risk assessment.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Usually `{substanceAId}-{substanceBId}`. |
| `substanceAId` | `string` | yes | — | First substance (sorted alphabetically). |
| `substanceBId` | `string` | yes | — | Second substance. |
| `riskLevel` | `string` | yes | — | `DANGEROUS`, `UNSAFE`, `UNCERTAIN`, `LOW`, `UNKNOWN`. |
| `description` | `string` | no | `null` | Human-readable risk description. |
| `sources` | `string[]` | no | `[]` | Source citations. |

### Risk levels

| Level | Meaning |
|-------|---------|
| `DANGEROUS` | Life-threatening interaction. Known risk of serious harm. |
| `UNSAFE` | Significant risk. Not immediately life-threatening but hazardous. |
| `UNCERTAIN` | Risk is unknown or debated. Best to avoid. |
| `LOW` | Generally safe combination. No significant risk. |
| `UNKNOWN` | No data available. |

---

## 9. Entity: Effect

A named psychoactive effect.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Effect identifier. |
| `name` | `string` | yes | — | Effect name (e.g. `Anxiety`). |
| `url` | `string` | no | `null` | Reference URL (PsychonautWiki page). |
| `description` | `string` | no | `null` | Description text. |
| `category` | `string` | no | `null` | Category: `cognitive`, `physical`, `visual`, `auditory`, etc. |
| `substanceIds` | `string[]` | no | `[]` | Substances known to produce this effect. |

---

## 10. Entity: Note

A user note. Attachable to sessions or doses, or standalone.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Device-keyed UUID. |
| `sessionId` | `string` | no | `null` | Parent session ID. |
| `doseId` | `string` | no | `null` | Parent dose ID. |
| `title` | `string` | no | `null` | Title (markdown). |
| `body` | `string` | yes | — | Body text (markdown). |
| `tags` | `string[]` | no | `[]` | Tags. |
| `isPinned` | `bool` | no | `false` | Pinned status. |
| `conflictSiblings` | `ConflictSibling[]` | no | `[]` | Sync conflict versions. |

---

## 11. Entity: TimelineEvent

A timestamped event inside a session.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Device-keyed UUID. |
| `sessionId` | `string` | yes | — | Parent session ID. |
| `timestamp` | `int` | yes | — | Unix ms event time. |
| `eventType` | `string` | yes | — | One of: `ONSET`, `COMEUP`, `PEAK`, `PLATEAU`, `OFFSET`, `AFTERGLOW`, `END`, `OBSERVATION`, `SAFETY_CHECK`, `SIDE_EFFECT`, `EMERGENCY`, `NOTE`. |
| `label` | `string` | yes | — | Short event label. |
| `body` | `string` | no | `null` | Detailed description. |
| `relatedEffectIds` | `string[]` | no | `[]` | Related effect IDs. |
| `intensity` | `float` | no | `null` | 0.0-1.0 intensity. |

---

## 12. Entity: CustomUnit

A user-defined dosing unit.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | `string` | yes | — | Device-keyed UUID. |
| `substanceId` | `string` | yes | — | Substance this unit applies to. |
| `name` | `string` | yes | — | Unit name (e.g. `drops`, `puffs`). |
| `description` | `string` | no | `null` | Optional description. |
| `estimatedMgPerUnit` | `float` | no | `null` | Estimated mg equivalent for tolerance. |
| `isEstimate` | `bool` | no | `false` | When true, show `~` prefix on doses. |

---

## 13. Pharmacology data format details

### ChEMBL bioactivity

```json
{
  "chemblData": {
    "prefName": "LSD",
    "mw": 323.43,
    "alogp": 2.2,
    "psa": 31.8,
    "ro5Violations": 0,
    "maxPhase": 2,
    "bioactivities": [
      {
        "type": "Ki",
        "value": 2.5,
        "units": "nM",
        "relation": "=",
        "targetId": "CHEMBL_TARGET:1234",
        "assay": "Binding assay at human 5-HT2A receptor"
      }
    ]
  }
}
```

`type` values: `IC50`, `Ki`, `EC50`, `Kd`, `pIC50`, `pKi`  
`relation` values: `=`, `<`, `>`, `~`

### IUPHAR ligand-target interactions

```json
{
  "iupharData": {
    "ligandId": 17,
    "interactions": [
      {
        "targetId": 4,
        "targetName": "5-HT2A receptor",
        "targetSpecies": "Human",
        "type": "agonist",
        "action": "Activation",
        "affinity": "pKi 8.7",
        "affinityParameter": "pKi",
        "endogenous": false,
        "primaryTarget": true
      }
    ]
  }
}
```

Affinity types are reported as negative-log values (pKi, pIC50, pEC50) by convention. Higher pKi = stronger affinity.

### PDSP Ki records

```json
{
  "pdspData": {
    "records": [
      {
        "targetName": "5-HT2A",
        "gene": "HTR2A",
        "species": "h",
        "kiNanoMolar": 2.5,
        "reference": "PMID: 12345678",
        "source": "Roth Lab"
      }
    ]
  }
}
```

Ki values are in nanomolar (nM). **Lower Ki = stronger binding.**  
Species codes: `h` (human), `r` (rat), `m` (mouse), `gp` (guinea pig).

### BindingDB records

```json
{
  "bindingdbData": {
    "records": [
      {
        "targetName": "Serotonin 2a (5-HT2a) receptor",
        "uniprotId": "P28223",
        "geneSymbol": "HTR2A",
        "species": "Human",
        "affinityType": "Ki",
        "affinityNM": 2.5,
        "pmid": "12345678"
      }
    ]
  }
}
```

Affinity types: `Ki`, `IC50`, `Kd`, `EC50`. Lower affinityNM = stronger binding.

---

## 14. Data sources

Each substance's `sources` array documents which data sources contributed. The sources are processed in-order by the ETL pipeline (matrix_build.py):

| # | Source | URL | Data contributed |
|---|--------|-----|------------------|
| 1 | PsychonautWiki (SMW) | https://psychonautwiki.org | Doses, durations, effects, ROAs, classes, interactions, summaries |
| 2 | PubChem | https://pubchem.ncbi.nlm.nih.gov | CID, molecular formula, weight, SMILES, InChIKey, LogP |
| 3 | TripSit | https://tripsit.me | Aliases, categories, Erowid links, interaction chart (combos.json) |
| 4 | Wikidata | https://wikidata.org | Cross-references (ChEMBL, DrugBank, CAS, UNII, ChEBI, ATC) |
| 5 | ChEMBL | https://www.ebi.ac.uk/chembl | Bioactivity data (IC50, Ki, EC50, Kd) at molecular targets |
| 6 | IUPHAR/BPS GtoPdb | https://guidetopharmacology.org | Ligand-target interactions with affinity data (pKi, pIC50) |
| 7 | PDSP Ki Database | https://pdsp.unc.edu | Ki binding values (nM) at CNS targets |
| 8 | BindingDB | https://bindingdb.org | Measured binding affinities (Ki, Kd, IC50, EC50) at protein targets |

---

## 15. Versioning and compatibility

### Reading across versions

The Kotlin deserializer uses `ignoreUnknownKeys = true`, so a future-version file with extra fields loads without errors (extra fields are silently dropped). A past-version file is missing some current fields — those fields take their Kotlin default values (usually `null` or `false`).

### Writing

The app always writes `CURRENT_VERSION`. If a file is loaded that has a `version` higher than `CURRENT_VERSION`, the app logs a warning but proceeds. On save, it downgrades to `CURRENT_VERSION` by stripping unknown fields — this is a known limitation.

### Recommended consumer strategy

1. Read `version`.
2. If `version` > your expected max, warn and attempt best-effort parse.
3. If `version` < your expected min, apply defaults for missing fields or reject.
4. Validate against `schemas/journal-snapshot-v{N}.json`.

---

## 16. Seed data

The seed file shipped with the app (`scripts/seed.json`) is a JournalSnapshot version 3 containing ~325 substances. It has no sessions, doses, notes, or timeline events. The seed file is used to initialize the app's substance library on first launch.

Seed data sources and build pipeline are documented in the ETL pipeline section above. The seed is regenerated periodically by running:
```
python scripts/matrix_build.py --input seed.json --output seed.json --verbose
```

The `scripts/` directory also contains cache files (`chembl_cache.json`, `iuphar_cache.json`, `pdsp_cache.json`, `bindingdb_cache.json`, `pubchem_cid_cache.json`, `wikidata_cache.json`) that speed up incremental rebuilds. Delete them to force a full refresh.
