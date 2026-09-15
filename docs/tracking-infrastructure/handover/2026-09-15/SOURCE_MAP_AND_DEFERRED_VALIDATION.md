# Navigation and deferred evidence

## Module/file map

Use `rg --files` and `rg -n` to resolve current class names and callers; do not assume all DI
bindings are active. Exact changed-path inventories are generated for every original tracking ref
in the package. Full source and Git ancestry are included, not merely excerpts.

| Concern | Primary code/search entry points | Integration owner |
| --- | --- | --- |
| Module/runtime/dependencies | settings.gradle.kts, build-logic, module build.gradle.kts, gradle/libs.versions.toml | Coordinator |
| Durable authority/schema | core/base/.../database/AppDatabase.kt, AppDatabaseMigrations.kt, data/SourcePolicy*, SessionManifest*, SourceServiceRun*, SourceEvidenceState*, SourceDeletionFence*, SourceDestinationOwner* | One shared Room owner |
| Clean creation/migration | database/migration/LegacyImportRoomCallback.kt, LegacyV26Importer*, AppDatabaseMigration27To28Test, source-specific Imported*Migration27To28Test | Same Room owner |
| Physical broker/purpose | tracker/engine/.../source/runtime/SourceRegistrationRepository.kt, SourceAcquisitionFloor.kt, SourcePlanCodec.kt, SourceProviderPurposeScope* | One broker owner |
| Lifecycle/startup/FGS | tracker/engine source lifecycle/actions/runtime, app startup and service callers; search exact SourcePolicy/manual-start/trigger callers | One lifecycle owner |
| Direct Steps | core/base database steps facts/DAO/fencing; tracker/engine/source Steps session projection/admission/provider; stats/data StepsSegmentHistorySelector, LogicalTrackingHistoryReader | Steps lane |
| Imported Steps | core/base/database/steps/imported; stats/data ImportedStepsProductReader, PortableStepsRoomReader, RoomExportPortableSteps; feature/import-export portable registry/file actions | Steps lane + shared actions |
| Qualified numeric effects | stats/data AchievementMetricQualification*, qualified Steps repositories/effect identity; stats API goals/metrics; dashboard/game/widgets/notifications consumers | One effect consumer owner |
| Ambient Steps | tracker/engine/source/steps/ambient; AmbientStepsMaintenance, storage/cursor/gap/authorization/import classes; stats/data ambient product read/export; preferences ambient consent | Ambient lane |
| Pressure | core/base pressure facts/integrity/maintenance/ImportedPressure*; tracker/engine source pressure acquisition/projection; stats/data PressureHistorySelector/PageReader, ImportedPressureHistoryEvaluator, PortablePressureRoomReader | Pressure lane |
| Activity | core/base ActivityCapturedFactMaintenance, ImportedActivityLineageAuthenticator, RoomImportPortableCapturedActivity, RoomTruncateImportedActivityRetention, ImportedActivity entities/DAO | Activity lane |
| Activity projection/read | tracker/engine source/activity WAL qualification/applied-plan/projector; stats/data captured and imported Activity repositories/evaluators/transfer | Activity lane |
| Wi-Fi | tracker/engine/source/wifi/WifiCapturedFactMaintenance, WifiWalQualificationAdapter, RoomImportPortableCapturedWifi, ImportedWifiLineageAuthenticator; core/base ImportedWifi entities/DAO; stats/data Wifi history; stats/api WifiCapturedPortable* | Wi-Fi lane |
| Cell | core/base/database/CellCapturedFactMaintenance, CellCapturedPortableTransfer, RoomImportPortableCapturedCell, ImportedCellLineageAuthenticator, ImportedCell entities/DAO; stats/data Cell history | Cell lane |
| Protected Location | tracker/engine source/location qualified observation/WAL/provenance classes, core Location storage and existing canonical writer/callers | Protected Location owner |
| Retention callers | app/.../app/maintenance/RetentionPipelineWorker.kt and app/.../maintenance/DataRetentionWorker.kt | One shared worker owner |
| Product read facade | stats/api TrackingHistoryRepository.kt + source-specific History/Portable contracts; stats/data DefaultTrackingHistoryRepository.kt and source readers | One history composition owner |
| Existing product surfaces | feature/dashboard DashboardHistoryRepository/ViewModel/RecentTripsCard/live state; feature/statistics TripDetailPresenter/SourcePresentation/ViewModel/TripDetailRoute; history/calendar/day/settings/actions call sites | One shared UI owner |

Source roots use the existing `com.adsamcik.tracker` packages. Search the repository rather than
copying paths from another machine verbatim. The complete package contains all tracking modules,
existing manifests/resources/test sources and build logic needed to inspect behavior.

## Commands to record now, execute only after frozen completion

The repository-owned host aggregate is `./gradlew.bat ciUnitTest`. Full QC is
`./gradlew.bat ciCheck --continue`. Relevant focused suites, Detekt/lint and checkRoomSchemaDrift
must be selected from current settings/module/task definitions when the final batch begins.
These commands are not evidence of any outcome at this checkpoint.

```powershell
.\gradlew.bat ciUnitTest
.\gradlew.bat ciCheck --continue
.\gradlew.bat checkRoomSchemaDrift
```

Cell importer focused source cohort (deferred):

```powershell
.\gradlew.bat :core:base:testDebugUnitTest --tests '*RoomImportPortableCapturedCellTest' --tests '*ImportedCellDaoTest' --tests '*ImportedCellEntityTest' --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Wi-Fi import focused source cohort (deferred):

```powershell
.\gradlew.bat :tracker:engine:testDebugUnitTest --tests '*RoomImportPortableCapturedWifiTest' :core:base:testDebugUnitTest --tests '*ImportedWifiDaoTest' --tests '*ImportedWifiEntityTest' :stats:api:testAndroidHostTest --tests '*WifiCapturedPortableFormatV1Test' --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Activity invocation cohort (deferred):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ActivityRetentionWorkerRobolectricTest' --tests '*RetentionPipelineWorkerRobolectricTest' --tests '*DataRetentionWorkerTest' --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Assembled shared recent/live/detail imported Steps regression source is
ImportedStepsProductRoomTest plus DashboardHistoryRepositoryTest and TripDetail source/presenter/
route tests. Shared Activity/Pressure recent/live/page tests and all importer/maintenance regression
sources must run together at convergence; isolated source success cannot prove composed behavior.

v28 JSON is knowingly stale across the new source tables. Generate/review only after final SQL/
entity composition, then run populated v27-to-v28 migration and production reopen on one
representative emulator. Do not modify released v27 JSON to hide drift. Existing development v28
databases need explicit safe handling if their old shape cannot open; no silent destructive reset.

## Known earlier execution debt

The previous Tracker Gradle session was stopped after a wall-uncertain materializer assertion
failure and an invalid imported-Room test initialization. No result-driven repair/restart is
authorized during implementation. The old exact publication had passing host/migration evidence,
but none applies to the new assembled input. Do not stop unrelated Gradle/Kotlin daemons.
Record and resolve the earlier failures with the complete final batch.

## Device/product gate template

For each source X, capture manual set exactly {X}. Disable all other capture sources, controls
and ambient. Record actual device/API/permission/source capability. Show exact accepted demands
and physical registrations (no hidden Location/Activity/etc); source-native fresh post-effective
evidence; durable ingress; exactly one canonical source writer; production repository query;
truthful visible state; listener removed after stop. For Steps require positive post-baseline delta;
baseline/unknown/partial is not complete zero. For Pressure inspect actual FIFO or truthful fallback,
windows/gaps/hPa. For Wi-Fi/Cell distinguish fresh item timestamps/callback coverage from caches,
attempts/throttling and unproven multi-SIM completeness. Do not invent wake reliability.

Separately exercise automatic controls, supported ambient default-off consent, disable/revoke,
delayed callback, process death, reboot/update, FGS rejection, deletion/replay/import/rollback
proportionally. Measure tier quality/latency/writes/provider restarts/energy on representative
hardware before activation. Never generalize host tests to provider/process/reboot/FGS/battery/UI/OEM proof.

## Original goal, still active

Complete Tracker Android tracking infrastructure across all remaining source-to-product verticals
using coordinated non-overlapping worktrees; preserve correctness/privacy/battery/lifecycle/
ownership/migration/deletion/retention/export/UI guarantees; author focused tests without executing
validation until implementation convergence; maintain an exhaustive durable TODO/evidence/handover
ledger; then converge, validate, fix, and locally integrate when ready and authorized.

The 2026-09-15 local dev/v10 handover assembly is a user-requested transport checkpoint, not goal
completion. Initialize the receiving flow's goal with the remaining work, not the old tool's usage counters.
