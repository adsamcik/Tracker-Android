# V27 Storage Release — Lean Implementation Plan

> **Status:** V27 storage implementation and release verification are complete; repository lint retains unrelated existing debt, and physical-provider/OEM smoke testing is optional follow-up<br>
> **Scope:** Public database v26 to v27, legacy database preservation/import, schema cleanup, and unfinished release-critical logic<br>
> **Last reviewed:** 2026-08-12<br>
> **Project fit:** Personal project. Prefer small, explicit, frozen compatibility code over general frameworks.

## Outcome

V27 uses a new long-lived database file, `main_database_v27`. The released database currently stored as `main_database` remains the user's local legacy vault and is never opened as the active v27 database.

The app imports relevant v26 data while Room creates a fresh v27 database. Room's `onCreate` transaction makes the target atomic: a failed creation rolls back, and the same database helper can retry without merging partial rows. The importer never applies schema/row transformations to or deletes the legacy source. A one-time SQLite checkpoint and journal normalization make the source portable without changing its logical data. The user can export the vault, or explicitly delete it after either a successful import or successful external export.

The internal unreleased 27 through 40 work being folded into the public 26 to 27 boundary was intentional. It is not treated as unfinished work. With this plan, that work becomes the fresh v27 schema rather than a production in-place migration.

## Why this is the best maintenance tradeoff

| Area | Selected approach | Value and maintenance rationale |
|---|---|---|
| Upgrade safety | New v27 filename; keep `main_database` as the vault | Highest safety for very little code. Downgrade also leaves the v26 file available. |
| Retry | Retry the same Room helper after transactional `onCreate` rollback | Room already guarantees a clean target; a second delete/recreate state machine would add risk without value. |
| Import compatibility | One frozen v26 reader | The only importer contract that needs long-term tests. No adapter framework. |
| Older public versions | Migrate a disposable copy through the existing public migration chain to v26 | Supports skipped app updates without mutating the user's original file or maintaining many importers. |
| Import tracking | One small external state record plus aggregate counts | Per-row receipts would enlarge the database and create permanent cleanup/idempotency logic. |
| Schema | Keep the 51 tables with concrete production ownership; remove 14 dormant/legacy/OSM/stale tables | Removes code that otherwise has to be migrated and tested forever. |
| Derived data | Copy compatible daily summaries and exploration progress; leave incompatible achievement rows in the vault; discard caches | Preserves data whose meaning is stable without inventing a conversion for a superseded achievement schema. |
| Processor recovery | Fix the known segment-finalization bug and use durable Room/source-event replay | A generic binary checkpoint framework is unnecessary for the current processors. |
| Validation | Focused fixtures, automated failure tests, and a small representative device pass | Appropriate confidence without an enterprise device lab or long-lived test bureaucracy. |
| Deferred features | Remove their persistence surface until the feature is deliberately resumed | Git history is cheaper to maintain than dormant schema and DAO contracts. |

## Explicitly not building

- No multi-stage file-transition state machine.
- No per-row legacy-import receipt table or quarantine database.
- No generic multi-version import-adapter framework.
- No merge or resume of a partially imported v27 database.
- No multiple legacy-vault generations and no automatic 30-day expiry.
- No generic processor checkpoint serialization framework.
- No permanent table-ownership registry; the release gets a one-time retained-table audit and focused schema tests.
- No exhaustive OEM/device matrix, sudden-power-loss lab, or arbitrary enterprise performance targets.
- No trip intelligence, route hypotheses/cache, personal records, storage-history charts, or OSM persistence in v27.

## Binding decisions

- `main_database_v27` is the active filename for v27 and later normal migrations. Do not introduce a new filename every release.
- `main_database` is the legacy vault. It is retained until the user explicitly deletes it.
- Normal v27 startup must never point the current `AppDatabase` at `main_database`.
- The importer writes only into an empty, disposable v27 database before normal app services start.
- Canonical row corruption fails the import and leaves the legacy vault available. Intentionally skipped tables are reported by aggregate row count.
- Direct import preserves exploration progress and the compatible v26 daily summaries. The incompatible v26 `achievement_progress` representation is deliberately skipped and reported; it remains recoverable in the vault. Transient, diagnostic, cache, and incomplete-feature data is not imported.
- Existing public pre-v26 migrations may run only on a temporary copy to normalize it to v26.
- Unreleased internal schemas 27 through 40 have no production compatibility promise; developer installs use an explicit reset/export path.
- The production v26 to v27 in-place migration is not a second upgrade path. Once importer coverage is proven, unregister and remove it.

## V26 data-disposition contract

This is the complete released 26-table source matrix. Adding an importer outside the **Import** rows needs a concrete user-visible reason.

| V26 table | Action | Reason |
|---|---|---|
| `activity` | Import | User-defined activity metadata. |
| `network_operator` | Skip | Unread cache/reference data; cell samples already contain MCC/MNC. |
| `location_sample` | Import and normalize | Irreplaceable history. Populate canonical coordinates/altitude without retaining legacy columns. |
| `step_interval` | Import | Irreplaceable source history. |
| `activity_snapshot` | Import | Irreplaceable source history. |
| `cell_sample` | Import and normalize | Irreplaceable source history; discard legacy-only representations after canonical conversion. |
| `wifi_observation` | Import and normalize | Irreplaceable source history; discard legacy-only representations after canonical conversion. |
| `tracker_run` | Skip | Old runtime diagnostics, not user history. |
| `session_segment` | Import | User-visible history; the frozen v26 projection reads its canonical primary activity and no legacy fallback survives in v27. |
| `daily_summary` | Import | The v26 and v27 contracts are compatible. Direct copy preserves historical values that the production materializer cannot reconstruct for every past day. |
| `live_stats` | Reset source; retain v27 table | Do not import stale session state. The fresh Room row is canonical while tracking, written on `AggregatorProcessor` flush and cleared on stop. |
| `frequent_place` | Skip | Incomplete feature with no complete producer. |
| `inferred_trip` | Skip | Incomplete feature with no complete producer. |
| `trip_leg` | Skip | Incomplete feature with no complete producer. |
| `exploration_cell` | Import | Earned user progress; recomputation could change historical rewards. |
| `exploration_streak` | Import | Earned user progress. |
| `achievement_progress` | Skip and report count | Its v26 representation is incompatible with the current reward model and the folded unreleased migration reset it. Do not guess a conversion; retain it in the vault. |
| `personal_record` | Skip | Incomplete/derived feature; recalculate if it is implemented later. |
| `route_cache` | Skip | Cache with legacy session semantics. |
| `export_log` | Skip | Operational history with no lasting user-data value. |
| `storage_size_snapshot` | Skip | Incomplete diagnostic/charting feature. |
| `domain_event` | Reset | Old processor plumbing; do not replay it into the new architecture. |
| `domain_event_cursor` | Reset | Cursor is valid only for the old event stream. |
| `pressure_sample` | Import | Irreplaceable source history. |
| `ski_run_segment` | Import | User-visible feature history. |
| `pending_signal` | Skip and report count | Transient unfinished work; the preserved vault remains the recovery source. |

## Final v27 schema contract

The final audit found 65 candidate tables: 51 with concrete production ownership, 8 dormant/incomplete, 2 explicitly legacy, 3 feature-gated OSM tables, and 1 stale source-event binding table. V27 contains the 51 owned tables only.

Remove these 14 tables:

- Dormant/incomplete: `network_operator`, `frequent_place`, `inferred_trip`, `trip_leg`, `route_hypothesis`, `personal_record`, `route_cache`, `storage_size_snapshot`.
- Explicit legacy: `legacy_rejected_tracker_session`, `legacy_location_wifi_count`.
- Deferred OSM: `osm_import`, `osm_way`, `osm_way_cell`.
- Stale source-event design: `source_event_session_binding`. Immutable admission-time `logical_tracking_id` and `service_run_id` already live on `source_event_wal`; no producer or consumer needs a second binding ledger.

Remove these 17 legacy columns from the fresh schema:

- `location_sample`: `legacy_lat`, `legacy_lon`, `legacy_alt_m`.
- `cell_sample`: `legacy_alt_m`, `legacy_mcc`, `legacy_mnc`, `legacy_source_id`, `legacy_lat`, `legacy_lon`.
- `wifi_observation`: `legacy_first_seen_ms`, `legacy_alt_m`, `legacy_lat`, `legacy_lon`.
- `session_segment`: `legacy_user_initiated`, `legacy_distance_on_foot_m`, `legacy_distance_in_vehicle_m`, `legacy_activity_id`.

Also remove semantic legacy paths that a fresh v27 database no longer needs: the v0 pending-signal envelope decoder, Wi-Fi legacy source-ID fallback, deprecated `DomainEvent` timestamp API, OSM legacy encodings/names, and stale Room annotations on `TrackerSession`, `DatabaseLocation`, and `NetworkOperator`.

---

## Implementation TODOs

### 1. Freeze the release contracts

- [x] **V27-L001** Add constants for `LEGACY_DATABASE_NAME = "main_database"` and `ACTIVE_DATABASE_NAME = "main_database_v27"`; make the current `AppDatabase.databaseName` use only the active name.
- [x] **V27-L002** Add a short code comment explaining that the active filename remains stable for v27+ and the old name is reserved for the legacy vault.
- [x] **V27-L003** Freeze the released v26 Room schema JSON and use frozen-schema fixture builders for populated v26 and older-public importer inputs; never derive legacy projections from current entities.
- [x] **V27-L004** Record all publicly shipped schema versions that can update directly. Support them by normalizing a temporary copy through the existing migration chain to v26.
- [x] **V27-L005** Treat internal schemas 27 through 40 as unsupported legacy sources: leave them exportable and require reset rather than adding production adapters for unreleased builds.

### 2. Produce the clean v27 schema

- [x] **V27-L010** Remove the eight dormant/incomplete tables, their Room entities/DAOs, database accessors, DI bindings, and unreachable UI/query paths.
- [x] **V27-L011** Remove the two explicit legacy tables and their cleanup callbacks.
- [x] **V27-L012** Remove the three OSM tables, DAOs, importer/UI entry points, and other code used only by the unshipped OSM feature. Git history preserves it if the feature is deliberately resumed later.
- [x] **V27-L013** Remove all 17 `legacy_*` fields from current entities and DAO projections.
- [x] **V27-L014** Replace `SessionSegmentDao`'s `legacy_activity_id` runtime fallback with canonical-only reads; legacy v26 already exposes the canonical value imported by the frozen projection.
- [x] **V27-L015** Remove the v0 pending-signal decoder and Wi-Fi legacy source-ID fallback after confirming no fresh-v27 writer emits those formats.
- [x] **V27-L016** Remove the deprecated `DomainEvent` timestamp API and update its remaining callers to the canonical time contract.
- [x] **V27-L017** Remove stale `@Entity`/Room annotations from models not registered in `AppDatabase`.
- [x] **V27-L018** Regenerate and review the public v27 Room schema JSON; mechanically verify exactly 51 application tables and zero `legacy_%` table/column names.
- [x] **V27-L019** Complete the production call-site audit for every retained table/DAO. Remove the orphan `source_event_session_binding`, redundant live-stats Proto store, and unused DAO surface; all 51 retained tables now have concrete production ownership.

### 3. Preserve and identify the legacy vault

- [x] **V27-L020** Implement a small `LegacyDatabaseRepository` that detects `main_database`, reads `PRAGMA user_version`, validates `integrity_check`, and reports database plus sidecar-file size.
- [x] **V27-L021** Before import/export, checkpoint the legacy WAL and normalize its journal mode. If that fails, block import without logically modifying the source or opening v27 for normal use.
- [x] **V27-L022** Ensure application startup never constructs the current Room database with the legacy filename.
- [x] **V27-L023** For a v26 source, import directly from the validated legacy file in read-only mode.
- [x] **V27-L024** For an older public source, copy it once to a temporary staging database, run only public migrations through v26, validate it, and import through the same frozen v26 projections.
- [x] **V27-L025** Delete only the temporary staging copy after success/failure. Never automatically delete the original legacy vault.
- [x] **V27-L026** Keep the legacy vault outside retention and normal maintenance. It is deleted only by the guarded vault action or by the user's explicit full-data deletion.
- [x] **V27-L027** Add a disk-space preflight for the staging copy and show a recoverable import error when space is insufficient.
- [x] **V27-L028** Verify downgrade behavior: v26 can still open `main_database`; it must never see or mutate `main_database_v27`. On the disposable API 30 AVD, the historical v26 APK reopened and wrote the v26 vault while the stopped v27 database, WAL, and SHM hashes remained byte-identical.
- [x] **V27-L029** Verify a clean install creates only `main_database_v27` and never shows legacy controls. `LegacyV26ImportTest` opens a clean target and asserts that only the active file exists and the legacy repository state is absent; Data settings renders vault controls only for a present legacy state.

### 4. Implement the one-shot importer

- [x] **V27-L030** Implement frozen raw-SQL projections for exactly the 12 imported source tables in the matrix.
- [x] **V27-L031** Keep importer state outside the target with `NOT_STARTED`, `RUNNING`, `COMPLETE`, and `FAILED`, plus source version, aggregate counts, last error, and successful-export authorization.
- [x] **V27-L032** Gate normal database consumers, workers, lock/background-tracking rearm, and UI startup until import and validation are complete.
- [x] **V27-L033** Import only from Room's fresh-target `onCreate` callback. A failure rolls back the creation transaction, and user retry reopens the same Hilt/Room helper without partial-row merge or a second target-deletion state machine.
- [x] **V27-L034** Stream each ordered source cursor row-by-row inside Room's one atomic database-creation transaction; never load a whole table into memory or commit a partially imported table.
- [x] **V27-L035** Add an explicit post-import auto-increment assertion. The representative import test preserves location ID `9001`, inserts a new current row, and asserts that SQLite allocates an ID greater than `9001`.
- [x] **V27-L036** Import in dependency order: activity metadata, canonical source observations, session/feature history, then exploration progress.
- [x] **V27-L037** Copy only canonical v26 projections, backfill stable source identities/item indexes, and synthesize canonical `location_observation` evidence from imported location samples. No `legacy_*` field enters v27.
- [x] **V27-L038** Treat schema mismatch, constraint failure, or row-binding failure as an atomic import failure and retain the reason externally; do not silently skip canonical rows or build a quarantine subsystem.
- [x] **V27-L039** Count intentionally skipped rows by source table so the result explains what remained only in the legacy vault.
- [x] **V27-L040** Import exploration state. Deliberately skip and report incompatible `achievement_progress`, which prevents guessed conversions and duplicate unlock notifications.
- [x] **V27-L041** Directly copy compatible `daily_summary` history; initialize Room `live_stats` and new runtime event/projection state empty. `AggregatorProcessor` flush writes the canonical live row and stop clears it.
- [x] **V27-L042** Validate source integrity, v26 version, required tables/columns, per-table source/imported counts, synthesized observation count, Room's target schema, and the atomic completion marker.
- [x] **V27-L043** Activate `main_database_v27` only after the target completion marker is durable and the coordinator validates it.
- [x] **V27-L044** Use one aggregate `import_job_receipt` marker rather than general per-row file-import receipts; clean transactional target creation is the idempotency mechanism.

### 5. Add small user-facing recovery controls

- [x] **V27-L050** Reuse Data settings to show legacy presence, source version, total storage, import state, completion time, and aggregate imported/skipped counts.
- [x] **V27-L051** Show a blocking startup/import state and a retry action after failure, with a safe failure reason that does not expose raw data.
- [x] **V27-L052** Export the portable legacy database through the Storage Access Framework using the existing export plumbing, authorizing deletion only after the destination closes successfully.
- [x] **V27-L053** Add explicit legacy deletion with the exact recoverable storage amount and a strong confirmation.
- [x] **V27-L054** Allow deletion only after a successful import or a successful external legacy export. Do not offer an earlier destructive bypass in v27.
- [x] **V27-L055** Delete the legacy database and its `-wal`/`-shm`/journal sidecars together, then refresh UI state. Never delete `main_database_v27` from this action.
- [x] **V27-L056** Keep the legacy vault indefinitely when the user takes no action; do not nag or schedule automatic cleanup.

### 6. Close actual unfinished logic without adding frameworks

- [x] **V27-L060** Fix `SegmentDetectorProcessor.onStop` to force-end and persist the final open segment before resetting state; add empty/single/multi-segment tests.
- [x] **V27-L061** Remove the unused generic processor checkpoint/restore contract and its no-op implementations; the completed call-site audit found no production restore path.
- [x] **V27-L062** Keep restart durability at the existing Room/WAL, pending-signal replay, and source-projection seams, covered by focused recovery/replay and segment tests rather than a new cross-subsystem checkpoint framework.
- [x] **V27-L063** Keep source-projection checkpoints, which are active database state, distinct from the removed generic processor serialization API.
- [x] **V27-L064** Verify that active location, Wi-Fi, and cell persistence assigns canonical source identities/item indexes and is covered by focused persistence/DAO tests.
- [x] **V27-L065** Keep battery percentage/admission behavior disabled in v27 and move its broader evidence program to the deferred backlog; do not ship an unsupported metric.
- [x] **V27-L066** Verify the packaged SQLite runtime and process-kill recovery path used by production; fix only observed correctness failures. The release runtime/linkage gate and packaged SQLite instrumentation passed on API 30, and a real main-process SIGKILL preserved the logical session, closed the orphan run as process-death recovery, created a new run, drained pending work, and retained an integral database.
- [x] **V27-L067** Re-enable `DataRetentionWorkerTest` and `DatabaseMaintenanceWorkerTest`, replacing obsolete expectations with tests of current behavior.

### 7. Focused verification

- [x] **V27-L070** Build a representative populated v26 fixture with all 12 imported source tables, canonical edge values, exploration progress, compatible daily summaries, and intentionally skipped rows.
- [x] **V27-L071** Add one actual older-public-version fixture that proves disposable-copy normalization through public migrations to v26 while the original remains byte/logically unchanged.
- [x] **V27-L072** Test successful populated import, aggregate report, compatible daily-summary copy, synthesized location observations, canonical provenance identities, skipped achievement count, and unchanged source.
- [x] **V27-L073** Test a failed Room `onCreate` transaction followed by clean retry on the same database helper with no duplicate rows.
- [x] **V27-L074** Add a focused insufficient-space test seam. The older-source normalizer test injects zero usable space, asserts a recoverable failure before a staging database is created, and confirms the original v10 source remains readable and unchanged.
- [x] **V27-L075** Verify that retention, raw-data cleanup, backup cleanup, and normal maintenance do not target the legacy vault; explicit full-data deletion intentionally removes the vault first.
- [x] **V27-L076** Test export-close authorization and guarded sidecar deletion, and cover startup after the source is absent.
- [x] **V27-L077** Generate and mechanically review the v27 Room schema for the 14 banned tables and 17 banned columns alongside normal Room schema validation.
- [x] **V27-L078** Remove direct `MIGRATION_26_27` tests and replace them with importer tests; retain the public migration chain used to normalize older sources to v26.
- [x] **V27-L079** Run the full unit-test suite and lint/static analysis. After all final edits, `.\gradlew.bat testDebugUnitTest --continue --no-daemon` completed successfully in 6m 34s across 961 tasks (54 executed, 907 up-to-date); the owned daemon recorded `Runtime.exit(0)`, and all current-tree `TEST-*.xml` reports contain zero failure/error hits. An earlier clean run executed the broader uncached workload in 22m 44s. Repository-wide `.\gradlew.bat lintDebug --continue --no-daemon` was also run; it remains red on unrelated existing localization/baseline debt after every storage-related finding was fixed. A focused `:app:lintDebug` rerun removed all 21 new legacy-string `MissingTranslation` findings but remains red on the same unrelated debt.
- [x] **V27-L080** Run a serial-pinned v26-debug APK to current-debug APK upgrade/import smoke followed by focused instrumentation on an older API emulator. The API 30 boundary import completed without uninstall/clear, and `StandardFlowsTest` passed 4/4 for tracking start/stop, map, statistics, and settings. A permanent two-APK instrumentation harness and redundant primary-phone upgrade are intentionally not maintained for this personal project.
- [x] **V27-L081** Validate the v27 storage paths for location, Wi-Fi, and cell evidence on the representative emulator. The prelaunch full-matrix APK check imported canonical sentinel rows for all three sources with stable provenance identities/item indexes and then accepted new current-schema writes. Real radio/provider and OEM behavior is useful optional integration smoke testing, not a v27 storage gate.
- [x] **V27-L082** Run a process-kill recovery smoke test and a large-fixture import. A final-candidate SIGKILL/restart/stop cycle passed all lifecycle and integrity assertions. A packaged 100,248-location v26 import completed in 36 seconds at about 167 MiB peak observed PSS with exact source/destination counts, an unchanged source hash, and no corruption, crash, ANR, or OOM.

### 8. Remove the obsolete migration path and close the release

- [x] **V27-L090** Unregister the production `MIGRATION_26_27` path; `activeMigrations` is empty, so the importer is the only released v26-to-v27 behavior.
- [x] **V27-L091** Delete the folded migration/reconciliation code and tests that existed only for the abandoned in-place 26-to-v27 path. Keep public migrations only through v26 for disposable-copy normalization.
- [x] **V27-L092** Keep `DatabaseMigrationBackupStore` for future normal v27+ in-place migrations, remove its v26-to-v27 role from the legacy-vault flow, and avoid duplicate settings cards.
- [x] **V27-L093** Update current storage, source-event, stats, and migration documents so none claim dormant tables, OSM import, or generic processor checkpoints are shipped; label historical research/handover material as archival.
- [x] **V27-L094** Record the final schema count, import matrix, automated test commands/results, and representative device results in `V27_STORAGE_RELEASE_EVIDENCE.md`, the canonical release-facing artifact to link or copy into the release notes/PR.
- [x] **V27-L095** Perform a manual end-to-end check: v26 data -> update -> import -> use app -> export vault -> independently validate the exported SQLite file -> delete vault -> cold restart app. The disposable API 30 run completed the full sequence while retaining the active v27 data.
- [x] **V27-L096** Confirm the working tree contains no disabled release-critical tests, reachable TODO/stub exceptions, unused Room tables/DAOs, or current-schema legacy names before tagging v27. The final production-source and Room ownership sweeps found none; remaining disabled Android-context tests are not release-critical, and historical `legacy_*` names are confined to frozen v26 fixtures/projections and public migrations.

### Verification evidence (2026-08-12)

- `LegacyV26ImportTest` covers clean install, stable imported IDs followed by higher SQLite allocation, and insufficient staging space without source mutation.
- The generated v27 schema audit reports 51 application tables, zero legacy columns, and an exact match with the `AppDatabase` entity registration; the production TODO/FIXME/stub sweep reports zero actionable hits.
- The final audit also refreshed the generated localization reference, removed the unused `osmpbf` catalog entry and stale okhttp TODO wording, made the generator handle working-tree deletions, and removed the last checkpoint-only `StreamingAggregator` counters.
- The final persisted-state sweep removed three unreachable preference fields, reserved their Proto numbers/names, and removed the unexposed `export_before_purge` branch that previously returned success without exporting or purging.
- `V27_STORAGE_RELEASE_EVIDENCE.md` records exact APK/source hashes, database invariants, downgrade isolation, the packaged SQLite test, SIGKILL recovery, the 100,248-row packaged import, the 4/4 API 30 instrumentation smoke, and the complete export/delete/restart lifecycle.
- The reproducible prelaunch full-matrix script stages an exact v26 database with one valid row in every released table before the app process starts. It independently verifies the complete import/skip report, canonical source identities, unchanged vault hash, synthesized location evidence, completion receipt, and successful new v27 writes. No v27 implementation or release-verification TODO remains open.

## Definition of done

V27 is complete when:

- The app uses `main_database_v27`, and `main_database` is never mutated by the v27 Room schema.
- All relevant compatible v26 user history and exploration progress imports successfully from the representative populated fixture; incompatible achievement rows are counted and remain in the vault.
- A crash or error rolls back only the disposable v27 creation transaction; the legacy source remains valid and exportable.
- The final schema has 51 reviewed tables, none of the 14 rejected tables, and none of the 17 legacy columns.
- Every retained table has a current production owner/call path or a documented infrastructure reason.
- The segment finalization bug is fixed, and no uncalled generic checkpoint API remains presented as implemented durability.
- The two disabled maintenance tests are restored or replaced.
- Source-event writer gates are backed by the focused device/restart evidence appropriate for this project.
- The direct in-place 26 to 27 migration path and its dead compatibility code are removed.
- The user can see the legacy database size/state, export it, and explicitly delete it.

## Deliberately deferred backlog

These are not unfinished v27 work. They should return only with a user-facing feature proposal that justifies their schema and maintenance cost:

- Frequent places, inferred trips, and trip legs.
- Route hypotheses and route caching.
- Personal records.
- Storage-size history/charting.
- Offline OSM/PBF persistence and production import.
- A broader device certification matrix or quantitative battery program.

## Non-v27 cleanup candidates

These audit findings are intentionally outside the storage-release scope and are not v27 blockers:

- `StatsScreen` keeps a nullable `sessions` test seam whose null branch synthesizes five placeholder trips. Production always supplies paging items; a later UI-test cleanup can move the placeholders into test fixtures and make the production parameter non-null.
- `SpatiallySupportedTimeEstimator` and its focused tests are an intentionally retained stats-engine heatmap slice, not dormant storage or legacy-import code. Revisit it only as part of a deliberate heatmap redesign.
- A serial-pinned spare physical phone can later smoke-test real location, Wi-Fi, and cell callbacks plus OEM lifecycle behavior. The emulator full-matrix check already closes the v27 storage contract, so this is not unfinished release work.
