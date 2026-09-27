# Schema and dependency wiring: selected input trace

2026-09-27. **IMPLEMENTATION_ONLY; IMPLEMENTED_UNVALIDATED.**

Original task:

> **Converge the schema and dependency wiring.** Assemble the final entities, DAOs, indexes, migrations, triggers, invalidation and DI; preserve released and unknown databases.

Candidate `codex/source-native-map-context-root` starts at `a0b39fd96c2e031133660d390007f9a7eb55b1eb`.
Main `dev/v10` remains `13f1a99871`. The selected V1 families and preserved drafts remain intact.
This is a bounded correctness slice of the current selected assembly. Whole convergence is open:
no input freeze, schema generation, compilation, test execution, validation, activation, new main
integration or push. Authored tests are not evidence that they pass.

## Applied source corrections

- Pressure retention trigger admission now uses the existing typed name/table sentinel. The
  previous String argument had no matching overload. A same-name trigger on another table does
  not establish the owned Pressure retention barrier.
- All selected index sentinels reject partial indexes and non-BINARY comparison. The three old
  single-column checks now share the existing exact index verifier: Wi-Fi deletion scope must be
  unique; ambient Steps retention scope and caller status must be nonunique. Column order,
  descending direction and owning table continue to be checked. Unknown weakened schemas are
  blocked without repairing them, changing their version or replacing their files.
- Shared history invalidations now include generic source-event and captured-radio retained proof
  tables. Lifecycle uses this shared list, so radio proof-only updates can refresh its actual state.
- Ambient Steps history invalidations now include all four native and seven imported count-domain
  tables consumed by native ownership, imported graph/binding/fence and compatibility reads.
  These updates need not advance the generic evidence revision.
- Consolidated two companion objects in the existing containment test source into one valid
  companion. No dependency or schema version change was introduced.

## Static assembly trace

Paths below are repository relative. Counts describe source declarations, not a generated schema.

| Boundary | Current selected source | Static finding |
| --- | --- | --- |
| Module/dependency graph | `settings.gradle.kts`, `core/base/build.gradle.kts`, `stats/data/build.gradle.kts`, `build-logic/convention/.../AndroidFeatureConventionPlugins.kt` | Room/KSP schema location remains repository owned. Production layering stays API based; test-only tracker engine dependency already supplies numeric-chain fixtures. No new library or production cycle introduced. |
| Entity and DAO registry | `core/base/.../database/AppDatabase.kt` | Current annotation declares 195 entities; 76 DAO accessor declarations. Native/imported six-source facts, Location purposes, ambient radio, native/imported Steps count domains, retained proofs and staging families are registered. PreferenceDatabase owns its separate preference entities. Counts are not a proof of full Room parity. |
| Released boundary | `AppDatabase.kt`, `AppDatabaseMigrations.kt` | Released active v27 remains frozen. Selected development v28 refines the existing additive 27-to-28 migration. No destructive fallback or repair of unknown same-version structures is added. |
| Owned additive DDL | `AdditionalTrackingSchema.kt`, `ImportedPressureMaintenanceSchema.kt`, `ImportedAmbientStepsSchema.kt`, `ImportedPortableStepsCountDomainSchema.kt`, `StepsCountDomainSchema.kt`, `AppDatabaseMigrations.kt` | Migration composes owned DDL, installs count-domain contract before final marker and creates owner-validation triggers. Existing schema/migration test sources cover owned table parity and source-specific migrations; they remain unrun. |
| Fresh create/reopen | `AppDatabase.configureBuilder`, `TrackingOwnerValidationRoomCallback`, `FinalV28SchemaAssemblyRoomCallback`, `legacy/LegacyImportRoomCallback.kt` | Fresh production creates Room tables, installs count-domain contract, seeds actual destination owners and creates owner barriers. Reopen checks the exact Steps contract before staged imported cleanup. |
| Production opening | `app/.../di/InfrastructureModule.kt`, `AppDatabase.setupDatabase`, `DevelopmentV28DatabaseContainment.kt`, migration backup factory, SQLiteX runtime | Singleton production database uses containment outside backup outside Room/delegate. SQLiteX preserves corrupt files. Released v27 backup precedes migration; stale/unknown/unreadable active files are blocked before migration/backup delegate can mutate them. Contention remains retryable. |
| Runtime graph | `tracker/engine/.../di/SourcePipelineModule.kt` | Six keyed runtime bindings: Activity, Steps, Pressure, Location, Wi-Fi and Cell. Shared source controllers retain their existing single physical owner. No missing binding found by static trace; compiled Hilt proof deferred. |
| Reader/mutation/purpose graph | `stats/data/.../di/*Module.kt`, `tracker/engine/.../di/TrackingPurposePublicationModule.kt`, source deletion modules | Actual history/fact query, source-specific transfer/maintenance and purpose publication implementations are bound. Pressure's production persistence barrier is real. Wiring existence does not prove unresolved purpose/control behavior is complete. |
| Observer graph | `TrackingHistoryInvalidationTables.kt`, `DefaultAmbientStepsHistoryRepository.kt`, `SourceLifecycleProofCoordinator.kt` | Added verified reader dependencies only. Scope retention proof was not added merely because a maintenance audit consumes it; no direct lifecycle reader dependency was found. |

## Authored regression evidence

- `DevelopmentV28DatabaseContainmentTest`: wrong-table Pressure trigger and seven same-name
  wrong-uniqueness/partial/collation index cases must block admission while preserving existing
  database-family hashes. Existing released, unknown-version, stale-marker, corrupt-file-family,
  contention, migration failure and Steps exact-schema cases remain.
- `RoomStepsCountDomainCompatibilityQueryRoomTest`: a real native completeness-marker-only delete
  changes actual compatibility from ExactCompatible to Unverifiable through Ambient history's
  Room dependency set, without modifying the shared evidence state. This is a dependency/query
  seam, not a combined native/session/ambient public-history proof.
- `RoomImportedAmbientStepsTransferTest`: authenticated imported Ambient data initially presents
  Exact(5); receipt-only damage wakes the actual public range observer and withdraws the trusted
  total as CORRUPT_RETAINED_STATE, with shared evidence unchanged.
- `SourceEventRetentionProofLifecycleTest`: captured-radio proof-only insertion refreshes actual
  Cell materialization from Pending to Deleted(RETENTION_EXPIRED). A generic retained-proof-only
  insertion refreshes actual Pressure recording through shared history dependencies. Evidence
  revision remains unchanged. Existing sibling Wi-Fi assertions remain in the base fixture.
- New observer waits use a real dispatcher timeout around Room executor delivery; unchanged
  emissions are suppressed where the seam can emit duplicate invalidations. None has been run.

## Remaining convergence boundary

- Pending source/purpose/control/temporary Location/restore decisions can still change the final
  production contracts and storage needs. Do not invent their final tables or freeze this input.
- Generated Room v28 JSON, released-to-selected migration execution, table/index/foreign-key/
  trigger parity, invalidation delivery and Hilt compilation remain in the single final batch.
- Production opening combines preflight selected table/column/index/trigger sentinels with exact
  Steps schema authentication on Room create/reopen. Preflight alone checks Steps sentinels and
  marker, not the full Steps SQL/FK/trigger-body contract. Other tables' types and owner-trigger
  bodies are not exhaustively authenticated. Pressure trigger checks name and table, not its body.
  Do not claim arbitrary altered v28 is fully trusted.
- `AppDatabase.fileBuilder` is configurable and lacks the production containment factory by
  default. Current call sites are test/instrumentation sources; production DI uses guarded
  `AppDatabase.database(context)`. No public-builder architecture change is inferred.
- Full assembly gate and user-facing schema/DI checklist stay open until remaining implementation
  contracts exist, final inputs are frozen and execution is expressly allowed by the phase rule.
