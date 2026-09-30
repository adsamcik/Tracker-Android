# Fresh prerequisite completion review — 2026-09-30

**No full-chain prerequisite is signed off. IMPLEMENTATION_ONLY remains in force.**
The requirements reviewer freshly traced production connections against all 25
dependencies of `WI-ASSEMBLY-GATE-001`; this table does not copy the older TODO
verdict. Three full chains were not fully inspected and remain unverified,
rather than being declared absent. The other rows identify concrete unfinished
connections despite existing production slices.

Input: candidate `bd55222095c81a5cf9c68ac98898492ef85c1697` plus the 139
SHA256-pinned source/test drafts in [REVIEW_INPUT.json](REVIEW_INPUT.json).
Main `dev/v10` remains `13f1a99871b59d29b3d059f374fc415490da6943`.
The [consolidated review](ADVERSARIAL_AND_COMPLETION_REVIEW.md) records priorities,
committed-versus-draft distinctions and detailed closure items `R01`–`R22`.

## Reading the matrix

- **Partial:** production pieces exist, but an actual required connection is missing.
- **Uninspected full chain:** the cited component exists; this pass did not inspect
  the entire chain and cannot certify it. This does not establish absent code.
- **Authored scenario:** test source exists. No test result is implied.
- No row has current-input compilation, executed host tests, migration/reopen or
  physical-provider/lifecycle/battery evidence from this review.

Paths are repository-relative. Prefixes are defined exactly:

| Prefix | Directory |
| --- | --- |
| `T` | `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source` |
| `S` | `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository` |
| `F` | `feature/statistics/src/main/java/com/adsamcik/tracker/statistics` |
| `I` | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp` |

## All 25 prerequisites

| Prerequisite | Source status | Fresh trace | Required closure |
| --- | --- | --- | --- |
| `WI-STEPS-MANUAL-001` | Partial | `T/runtime/SharedStepSourceController.kt:57`; `T/projection/StepsSessionFactProjectionLane.kt:296`. Dashboard permission callback issues another Start. | Original Start continuation and composed provider→fact→numeric/UI/file→End/Off scenario. Existing session writers are present. R02/R09. |
| `WI-STEPS-AUTOMATIC-001` | Uninspected full chain | `T/runtime/AutomaticStartTransitionMonitor.kt:109`; `T/coordinator/TrackerServiceSourceSession.kt:1693` selects automatic shared capture. | Inspect the complete automatic Steps-only saved-product/Stop chain and add any missing composed scenario source; trigger dispatch/shared selection alone is insufficient. |
| `WI-STEPS-AMBIENT-001` | Partial; normal execution missing | `T/ambient/steps/AmbientStepsFactImporter.kt:160` has no production `importNext` caller. | Approved all-day enable/open/background execution, bounded recovery/cursors and daily/export/delete connection. Cadence pending. R02/R03. |
| `WI-PRESSURE-VERTICAL-001` | Partial | Native Pressure TripDetail exists at `F/ui/TripDetailRoute.kt:358`; `F/presenter/SourceHistoryDetailPresenter.kt:53` and `ui/SourceHistoryDetailRoute.kt:123` reject Pressure source-detail selections. | Complete native/imported selected detail, timestamp presentation, actions, export/delete and original portable bounds. Preserve existing native detail. R12. |
| `WI-LOCATION-VERTICAL-001` | Partial | `T/runtime/RearmablePassiveLocationBackend.kt:177`; `AmbientLocationPurposeOwner.kt:239`; `S/DefaultSourceFactQueryRepository.kt:201` still requires WAL. | Durable retained qualification, passive delivery-gap/recovery and manual/automatic/background history/map/file composition. R08/R10/R13. |
| `WI-ACTIVITY-VERTICAL-001` | Uninspected full chain | `T/runtime/AutomaticStartTransitionMonitor.kt:43` separates CONTROL; `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/SourceEventStorageMaintenance.kt:580` implements the 48-hour lifetime. | Finish full-chain inspection of native/imported Activity actions, effects, timestamp/portable semantics and Stop. Component existence is not certification. |
| `WI-WIFI-VERTICAL-001` | Partial | `T/ambient/wifi/AmbientWifiFactProjector.kt:156–169,180` retains observation-time/one-member checks. | Carry v6 per-reading semantics and network children through publication/history/maintenance/files; complete optional host and source-only product. R04–R06. |
| `WI-CELL-VERTICAL-001` | Partial | `T/ambient/cell/AmbientCellFactProjector.kt:153–170` retains observation-time, denied-callback and callback-member checks. | Historical per-reading qualification, tower children, readers/export and independent optional host. R04–R06. |
| `WI-SCHEMA-CONVERGENCE-001` | Partial | `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/AppDatabase.kt:311,851` declares v29 and composes development migrations. | Select final source/schema inputs; after freeze generate/check schema, populated released-data conversion/reopen and unknown-file preservation. Pin the actual released baseline; available historical `main` is v12. R20–R22. |
| `WI-AUTHORITY-PURPOSE-001` | Partial | Radio projectors reject valid historical readings before per-item qualification; `S/DefaultSourceFactQueryRepository.kt:201–204` requires WAL for retained Location. | Consistent historical radio/durable retained Location read/erase authority; preserve independently valid sibling purposes. R04/R05/R10/R13. |
| `WI-PURPOSE-PUBLICATION-001` | Partial | `T/runtime/TrackingPurposePublicationRuntime.kt:1050,1297` registers/reconciles ambient owners; downstream radio checks differ from new ingress. | Final publication→registration→receipt→product for every supported purpose and late-revoke scenario. Complete race review. R04/R10. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | Partial | `T/runtime/SharedUiLocationProvider.kt:56,90` acquires/releases shared leases; `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/TrackerService.kt:837,1366` retains a Location host. | Approved independent radio host lifetimes and composed retirement; preserve one physical owner and feature overrides. R06/R08. |
| `WI-SOURCE-CALLER-GUARD-001` | Uninspected complete call graph | `T/runtime/ExactSourceCallerGuard.kt:159,334–363` has acceptance/replay checks. | Inventory and trace every UI, worker, feature and legacy caller. Guard implementation alone does not establish caller coverage. |
| `WI-QOS-BATTERY-001` | Partial | `T/coordinator/SemanticAcquisitionPlanFactory.kt:33` separates participation; `T/runtime/RadioActiveRequestAllowance.kt:66,108` persists reservations and prevents reuse. | Final host/provider/permission/thermal transitions and admitted motion feedback; five-level energy/lifecycle evidence is deferred. R06/R07. |
| `WI-LIFECYCLE-RUNTIME-001` | Partial | Dashboard stores only repair flags and issues new Start; `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/shortcut/ShortcutActivity.kt:46–48` opens generic repair navigation. | One-use original Start across screens, invalidated by changed choices, End/Off or user-stop. End/Off external actions already have authored paths. R01/R09. |
| `WI-LIFECYCLE-PRODUCT-PROOF-001` | Partial | `T/lifecycle/DefaultSourceLifecycleProofAdapter.kt:164–187` dispatches six sources, but `:203–207` rejects background; retained Location/radio paths remain incomplete. | Supported ambient scopes, durable retained readers, radio per-reading lineage and composed provider→writer→query proof. R04/R10/R13. |
| `WI-STEPS-NUMERIC-001` | Partial | `T/summary/RoomStepsNumericSummaryRepository.kt:323–355` reads ambient/calendar states. | Make normal all-day facts arrive; exact daily/session count-domain deduplication into summaries/files/effects with local-calendar semantics. R02. |
| `WI-CROSS-SOURCE-RECOVERY-001` | Partial | `T/coordinator/SourcePipelineRecovery.kt:245–275` kicks independent source drains; stale-generation/timeout scenario sources exist. | Steps execution recovery, manual transfer/full-clear coordination and composed joined-purpose process/reboot/host-refusal cases on final schema. R02/R08/R15/R18. |
| `WI-CROSS-SOURCE-PRIVACY-001` | Partial | `app/src/main/java/com/adsamcik/tracker/app/settings/CollectedDataDeletionService.kt:252–287,627` cancels imports/scheduled work and clears files; manual export cancels only its own document. | Cancel/await interactive exports, durable external residual notice and approved uncertain old-private-file handling. Complete sibling-purpose retained erase/retention. R18. |
| `WI-SHARED-FILE-ASSEMBLY-001` | Partial; substantial draft-only assembly | `I/format/FormatRegistry.kt:234–260` registers draft adapters; `I/importer/worker/ImportWorker.kt:256–263` rejects backups. | Coherently commit source adapters, Location Keep/Replace, data-only replacement restore, bundles and original-data-epoch round-trip scenarios. R15–R18/R20. |
| `WI-SOURCE-ACTIONS-EXPLANATION-001` | Partial | `F/presenter/SourceHistoryDetailPresenter.kt:45–55` excludes Pressure/Steps variants; `S/DefaultSessionStatsRepository.kt:63–75` uses legacy radio tables for summaries. | Qualified selected actions/detail, retained source metrics and true requested-period calculations. R12/R14. |
| `WI-SHARED-HISTORY-DAY-RANGE-001` | Partial; production facade missing | `S/UnifiedHistoryCandidateQueries.kt:8` and `stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/UnifiedHistory.kt:92` merge helper have no production callers. | Bounded source-union facade, paging/calendar/timeline/detail/invalidation and retrospective annotations. R11/R12. |
| `WI-INTEGRATION-SCENARIOS-001` | Partial | `SourceLifecycleProofCoordinatorTest.kt:154` enumerates subsets; manual-start/subset/owner test source exists. | Author composed solo/mixed provider→WAL→fact→UI/file→retirement cases, late permission, joined purposes and Off. Pure subset tests do not prove production connections. |
| `WI-UI-SURFACES-001` | Partial | `feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/DashboardViewModel.kt:422–552` has source-specific live presentation; permission/history/map connections are unfinished. | Complete corresponding product routes, daily Steps freshness/gaps, concise localized accessible copy and recoverable saves. Notification failed-save exit choice remains pending. R09/R11–R13/R19. |
| `WI-NONSTEPS-EFFECTS-001` | Partial; qualified revision/retraction connection missing | `domain/points/src/main/java/com/adsamcik/tracker/points/event/PointsDomainEventConsumer.kt:49–52` handles SessionEnded; `work/PointsWorker.kt:45–61` scores legacy trip distance/duration. | Eligible qualified facts/session annotations, correction/deletion retraction and no duplicate legacy awards. Existing fallback scoring is not the approved effect chain. |

## Evidence and inspection limits

The pass inspected provider entry points/owners, projector authorization branches,
query/presenter wiring, clear/export boundaries and selected authored scenario
source. It did not inspect every statement in every writer/importer/migration,
every localized string, or every caller. The three uninspected full-chain rows
remain explicit review work before any completion declaration.

Authored test families include Steps recording/projection/numeric, Pressure and
Activity projection, radio allowance, caller guards, purpose publication,
recovery, lifecycle subsets, transfer cleanup, portable Location and history
candidate queries. Their existence establishes intended coverage only. The
convergence gates and physical-device evidence remain unexecuted for this input.

All 25 acceptance prerequisites therefore remain open: none has complete reviewed
source-chain and required evidence sign-off. This is a static completion audit,
not permission to start validation or merge.
