# Research prompt 01: OSM multi-region storage, publication, deletion, and reindexing

You are a principal researcher in SQLite/Room persistence, crash consistency, Android offline data
pipelines, spatial indexes, and OpenStreetMap graph storage. You have internet access and no local
checkout. Inspect Tracker through the immutable GitHub links below and use external primary sources.

Your assignment is to produce a decision-ready architecture report for Tracker's next OSM schema.
This is not a request for a generic Room tutorial. Determine the smallest design that makes multiple
overlapping user-imported regions safe under incremental writes, cancellation, process death,
re-import, deletion, schema migration, and cell-index rebuild.

> **2026-07-22 integration-record follow-up:** The historical V10 integration
> report's broad `BUILDING`/`READY` publication claim was corrected in a
> [post-integration erratum](../DEV_V10_INTEGRATION_REPORT.md). A `READY` region
> can keep the global consumer gate open while a second region is `BUILDING`, and
> global-ID replacement can transfer ownership of shared roads. Neither risk is
> fixed by that historical integration. This pack is the designated design
> follow-up and is a release gate if V10 ships multiple retained regions.

## Immutable repository target and required entry points

Review exactly commit `fb2684d53ea2900780feb3438e736fbcd1b26c3f`:

- [Commit](https://github.com/adsamcik/Tracker-Android/commit/fb2684d53ea2900780feb3438e736fbcd1b26c3f)
- [Tree](https://github.com/adsamcik/Tracker-Android/tree/fb2684d53ea2900780feb3438e736fbcd1b26c3f)

Start with these files and follow every relevant import, DAO caller, migration, UI action, worker path,
and test:

- [`AppDatabase`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt)
- [Migrations](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabaseMigrations.kt)
- [Exported Room schema 40](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/40.json)
- [`OsmImportEntity`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/OsmImportEntity.kt), [`OsmWayEntity`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/OsmWayEntity.kt), and [`OsmWayCellEntity`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/OsmWayCellEntity.kt)
- [`OsmImportDao`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/OsmImportDao.kt), [`OsmWayDao`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/OsmWayDao.kt), and [`OsmWayCellDao`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/OsmWayCellDao.kt)
- [`OsmImportWorker`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/imp/OsmImportWorker.kt) and [`OsmImportController`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/imp/OsmImportController.kt)
- [`OsmWayCellReindexer`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/reindex/OsmWayCellReindexer.kt) and [`OsmModuleInitializer`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/main/java/com/adsamcik/tracker/osm/reindex/OsmModuleInitializer.kt)
- [`OsmImportSettingsViewModel`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/app/src/main/java/com/adsamcik/tracker/app/settings/osm/OsmImportSettingsViewModel.kt)
- [`DefaultRoadMatcher`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/data/src/main/java/com/adsamcik/tracker/stats/data/roadmatch/DefaultRoadMatcher.kt) and [`DefaultSpeedLimitSource`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/stats/data/src/main/java/com/adsamcik/tracker/stats/data/speed/DefaultSpeedLimitSource.kt)
- [`OsmImportWorkerTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/imp/OsmImportWorkerTest.kt), [`OsmWayCellReindexerTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/reindex/OsmWayCellReindexerTest.kt), and [`OsmModuleInitializerTest`](https://github.com/adsamcik/Tracker-Android/blob/fb2684d53ea2900780feb3438e736fbcd1b26c3f/domain/osm/src/test/java/com/adsamcik/tracker/osm/reindex/OsmModuleInitializerTest.kt)

Do not use a moving branch. Code findings must link to tight line ranges at this commit, give the
reachable execution path, assign `P0`–`P3`, recommend the smallest safe correction, and name a test.

## Mandatory research method

Browse the web. Cite every consequential external claim next to the sentence it supports.

Source priority:

1. SQLite official documentation and, where necessary, SQLite source/tests.
2. Official Android Developers and AndroidX Room documentation/source for Room 2.8.x.
3. The OSM PBF specification or official OpenStreetMap documentation for identity semantics.
4. Peer-reviewed database literature for generation/pointer or shadow-index designs.
5. High-quality engineering reports only when primary sources do not answer an operational question.

For SQLite, verify rather than assume the exact semantics of `INSERT OR REPLACE`, foreign-key
cascades, transactions, WAL reader snapshots, DDL migration transactions, deferred foreign keys,
and conflict handling. State the SQLite-version dependency of any claim. For Room, distinguish what
Room guarantees from what underlying SQLite guarantees.

Label every conclusion as one of:

- **External fact** — directly supported by a cited primary source.
- **Repository fact** — verified from commit-pinned code with a direct line link.
- **Dossier inference** — not yet verified, or a logical consequence spanning linked code.
- **Recommendation** — a design choice with stated trade-offs.
- **Needs local/device verification** — not answerable from GitHub because it requires generated code,
  migration execution, parser fixtures, or measurements.

If evidence conflicts, show the conflict and explain which source is more authoritative. Do not invent
performance numbers or claim to have run migrations.

## Verified Tracker dossier

The dossier was verified locally at the target commit, but it is orientation rather than a substitute
for review. Confirm each relevant claim from the linked code and report any discrepancy.

Tracker is a single-user, local-first Android app. Relevant platform facts:

- Kotlin/JVM 17, Android API 26 minimum, API 37 target/compile.
- Room 2.8.4; current database schema 40 is unreleased.
- OSM data comes from local user-selected `.osm.pbf` files through Android's Storage Access Framework.
- Imports are offline WorkManager jobs. Unique work permits at most one active OSM import, but users
  can retain multiple completed regions and delete regions individually.

Current logical tables:

1. `osm_import`
   - auto-generated import ID;
   - display name, source URI breadcrumb, import time, counts, and bounding box;
   - `status` with `BUILDING`, `READY`, and `FAILED` concepts;
   - `cell_index_built` completion marker.
2. `osm_way`
   - **global OSM way ID is the primary key**;
   - one `import_id` foreign key to `osm_import`, `ON DELETE CASCADE`;
   - road class, resolved speed, explicit-speed flag, one-way flag;
   - packed E7 polyline and E7 bounding box;
   - batch insertion uses Room `OnConflictStrategy.REPLACE`.
3. `osm_way_cell`
   - primary key `(cell_key, way_id)`;
   - foreign key to the global `osm_way.id`, `ON DELETE CASCADE`;
   - coarse spatial candidates for speed lookup and HMM matching.

Current import protocol:

1. Insert `osm_import(status=BUILDING)`.
2. Parse the PBF and persist `osm_way` plus `osm_way_cell` in bounded batches. The entire import is
   intentionally not one SQLite transaction.
3. Update the header to `READY` after parsing and writes complete.
4. On cancellation or failure, delete the BUILDING import; cascades remove its children.
5. Startup removes abandoned BUILDING imports left by prior process death.

Current read protocol and confirmed weakness:

- Production road consumers are enabled when the count of READY imports is greater than zero.
- Candidate DAO queries read `osm_way_cell` and `osm_way` without joining or filtering on import status.
- Therefore, if region A is already READY while region B is BUILDING, ready-count gating remains open
  and readers can observe B's partially written graph.
- If A and B overlap, B's global-ID `REPLACE` can transfer a shared way's `import_id` from A to B.
  Cancelling, failing, or deleting B can then remove that way even though A still needs it.
- The UI supports multiple retained regions and per-region deletion, so overlap is reachable.

Current reindex protocol:

- A background reindexer pages all way bounding boxes, inserts cell rows additively, and marks all
  imports `cell_index_built=1` only after the pass finishes.
- Readers are not globally gated while a cell-index rebuild is incomplete.
- A normal successful import already generates current cell rows, but current publication does not set
  its `cell_index_built` marker to 1, causing a later unnecessary rebuild.
- A prior migration can clear the index before the background rebuild; readers may then observe empty
  or partial candidates while READY imports still exist.

The imported graph is reproducible from the user's PBF, but Tracker intentionally does not retain
long-term URI permission. Losing graph rows is recoverable only by asking the user to select files
again, so correctness still matters.

## Decisions you must resolve

### A. Prove the current failure semantics

Using official SQLite evidence, analyze the exact consequences of Room's replace conflict strategy
on a primary-key collision when the replaced row is referenced by `osm_way_cell` and owns an
`import_id` foreign key. Cover delete/insert behavior, cascading effects, trigger implications, rowid
behavior, and transaction visibility. Explain which consequences are guaranteed and which depend on
Room's generated SQL or SQLite configuration and thus require local verification.

Trace these scenarios conceptually:

1. A READY; non-overlapping B writes half its batches; a reader queries B's area.
2. A READY; overlapping B replaces a shared way; B is cancelled.
3. A READY; overlapping B completes; user deletes A.
4. A and B READY and overlapping; user deletes B.
5. Process death between the last data batch and publication.
6. Process death during startup cleanup.
7. Migration clears the cell index; a reader holds or starts a WAL snapshot during rebuild.

### B. Compare ownership and staging models

Evaluate at least these candidates:

1. **Per-import way instances:** surrogate way-row ID or composite `(import_id, osm_way_id)` identity;
   overlapping geometry may be duplicated.
2. **Canonical way plus ownership join:** one canonical way/version with
   `osm_import_way(import_id, way_id)` many-to-many membership and garbage collection of unowned ways.
3. **Immutable import generations:** all rows belong to an unpublished generation; an atomic pointer or
   generation-status transition selects visible generations.
4. **Single active region as a constrained fallback:** deliberately remove multi-region semantics until
   a richer model exists.
5. Any materially better alternative supported by evidence.

Do not compare them abstractly. Score each against:

- partial-import invisibility while older READY regions remain usable;
- safe cancellation/failure cleanup;
- deletion of either overlapping region;
- two files containing different versions of the same OSM way;
- duplicate candidate suppression in HMM matching;
- import and query complexity;
- migration complexity from schema 40;
- disk amplification for heavily overlapping regions;
- index rebuild and future grid-version changes;
- crash recovery and idempotent retry;
- ability to explain and test invariants;
- compatibility with Room and SQLite on Android API 26+.

Recommend one model for V10 correctness. If you prefer a normalized shared table, specify how a
failed new import is prevented from mutating the canonical data visible to older imports. If you
prefer per-import duplication, specify how queries deduplicate identical OSM roads and how storage
cost should be measured before later normalization.

### C. Define publication and query invariants

Propose a concrete state machine and SQL-level visibility contract. At minimum answer:

- Which rows may be read in BUILDING, READY, FAILED, deleting, and rebuilding states?
- Must every spatial query join through import/generation status, or can a globally active generation
  pointer make status filtering unnecessary?
- Which transition must be one SQLite transaction?
- How are cancellations made idempotent?
- Can stale worker cleanup race a legitimate replacement worker, and what identity prevents it?
- How should cache invalidation observe publication and deletion?
- Should a successful current-format import set `cell_index_built=1` in the same publication statement?
- What database constraints make illegal ownership/publication states impossible rather than merely
  unlikely?

Give schema sketches and representative SQL/pseudocode, not production Kotlin.

### D. Choose a reindex strategy

Compare:

1. Gating all OSM consumers whenever any visible index generation is incomplete, with graceful
   fallback until completion.
2. Building a shadow cell-index generation and atomically switching an active pointer.
3. A single large transaction.
4. Per-import independent index generations.

Determine when simple gating is sufficient and when shadow publication is justified. Include WAL
reader behavior, disk-space amplification, cancellation, process death, old-generation cleanup, and
the user experience during migration. Do not recommend a maintenance lease unless you identify a
specific race it solves better than ownership/state constraints.

### E. Prepare the migration and verification plan

Provide a migration outline from current schema 40 without assuming destructive migration is allowed.
Address:

- copying global-ID ways into the chosen ownership model;
- preserving all currently reachable graph data despite already-transferred ownership;
- rebuilding foreign keys and indexes safely in SQLite;
- handling duplicate/ambiguous ownership that cannot be reconstructed perfectly;
- Room exported-schema and migration-test requirements;
- free-space preflight and failure behavior;
- application downgrade expectations, if any;
- rollback/recovery strategy if migration cannot finish.

Specify adversarial tests, including the seven scenarios in section A, foreign-key checks, repeated
migration/open cycles, cancellation at every batch boundary, and overlapping OSM snapshots.

## Required output

Return one self-contained Markdown report in this exact order:

1. **Executive decision** — recommended V10 model and whether multi-region import is a release gate.
2. **Source-backed semantics** — SQLite/Room facts relevant to the failure.
3. **Current-scenario analysis** — the seven traces, with expected outcomes and confidence.
4. **Alternative decision matrix** — scored candidates and explicit rejection reasons.
5. **Recommended logical schema** — tables, keys, constraints, indexes, and ownership semantics.
6. **Publication/deletion state machine** — legal transitions and reader visibility.
7. **Reindex decision** — gating versus shadow generation, with a clear threshold for complexity.
8. **Migration outline** — ordered, crash-aware, and testable.
9. **Acceptance-test matrix** — scenario, setup, injected failure, invariant, expected result.
10. **Performance/storage measurement plan** — what must be measured on real Android devices.
11. **Open questions for local verification** — only questions genuinely unavailable from this dossier.
12. **Evidence ledger** — source, authority tier, version/date, supported claims, limitations.

End with a short list titled **Do not implement yet** for elegant but unjustified extensions.
