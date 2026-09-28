# Assembly coverage trace — 2026-09-28

**IMPLEMENTATION_ONLY.** This is a static check of the twenty-five dependencies of
`WI-ASSEMBLY-GATE-001` in `WORK_ITEMS.json`, against the current candidate and its authored
test source. The registry is a historical scope list, not a live verdict. No test has been
executed, no final schema has been generated, and no provider has been activated. An existing
test name establishes an intended case, not a passing source-to-product chain.

`P` means a required production or test chain is still missing. **All twenty-five prerequisites
remain pending.** The cited tests are authored scenarios only; none is a passing result. The
exact unfinished work below remains part of the one assembly input set.

| Prerequisite | Static evidence and scenario source | Remaining assembly coverage |
| --- | --- | --- |
| `WI-STEPS-MANUAL-001` | `StepsRecordingBoundaryIntegrationTest`, `StepsSessionFactProjectionLaneTest`, `RoomStepsNumericSummaryRepositoryRoomTest` (`P`) | Compose provider→retained query→selected UI/file→Stop on one final binding; preserve zero and reboot count-domain cases. |
| `WI-STEPS-AUTOMATIC-001` | `TrackerAutomaticStartIntentTest`, `ActivityAutomationOutboxDispatcherTest`, Steps projection tests (`P`) | Complete separate CONTROL owner and automatic Steps-only source-to-product/Stop case without recording trigger-only Activity. |
| `WI-STEPS-AMBIENT-001` | `AmbientStepsFactImporterTest`, `AmbientStepsProviderHandoffCoordinatorTest`, `AmbientStepsDayRepositoryTest` (`P`) | Supported Health Connect/Google on-device import scheduler, background permission/suppression, reboot/cursor recovery and export/delete chain remain. Daily Steps must work without a session; the provider fills supported gaps rather than defining a session-only product. |
| `WI-PRESSURE-VERTICAL-001` | `PressureSessionFactProjectionLaneTest`, `PressureHistorySelectorTest`, imported Pressure deletion and migration tests (`P`) | Complete selected detail/actions, original portable bounds, qualified summary and solo/mixed automatic visible route. Show each reading at its measured timestamp; only an actual provider/session window may have a bounded interval. |
| `WI-LOCATION-VERTICAL-001` | `LocationSourceRuntimeTest`, `ProtectedLocationCanonicalHandoffTest`, geographic export tests (`P`) | System-rearmable passive delivery and historical retained reader; final manual/automatic/ambient query/map/file chain. |
| `WI-ACTIVITY-VERTICAL-001` | `ActivityCapturedFactProjectionLaneTest`, `ActivityHistoryComposerTest`, `ActivityControlRetentionMaintenanceTest` (`P`) | CONTROL owner, truthful automatic availability and selected actions/effects. Show each activity change at its observed timestamp without inventing duration. |
| `WI-WIFI-VERTICAL-001` | `WifiWalQualificationAdapterTest`, ambient/captured radio transfer and shared controller tests (`P`) | Versioned per-item shared admission through writer/read/transfer, durable active-request budget, optional host and final source-only product. The newly approved available network names/access-point IDs need storage, history and export. |
| `WI-CELL-VERTICAL-001` | `CellWalQualificationAdapterTest`, gap-only portable replay and shared controller tests (`P`) | Same per-item/host/budget chain; the newly approved available tower IDs need storage, history and export, while SIM/subscriber IDs stay excluded and capability gaps stay typed. |
| `WI-SCHEMA-CONVERGENCE-001` | `AppDatabaseMigration27To28Test`, source migrations, `DevelopmentV28DatabaseContainmentTest` (`P`) | Final pending entities/contracts, generated schema and populated final-v28 migration/reopen/unknown-file proof. Preserve v27 and unknown files. |
| `WI-AUTHORITY-PURPOSE-001` | `SourceBrokerTest`, `TrackingPurposePublicationRuntimeTest`, source consent/retention tests (`P`) | Complete historical per-item radio and passive Location authority; sibling-purpose erase and retained-read qualification. |
| `WI-PURPOSE-PUBLICATION-001` | `TrackingPurposePublicationRuntimeTest`, source policy/authorization DAO tests (`P`) | Final atomic policy→demand→registration→receipt chain for all supported purposes and late-revoke race. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | `SharedLocationSourceControllerTest`, `SharedAmbientRadioSourceControllerTest`, `SharedStepSourceControllerTest` (`P`) | Feature Location leases, system passive retirement and optional radio host must join without a second physical owner. |
| `WI-SOURCE-CALLER-GUARD-001` | `ExactSourceCallerGuardTest`, `GuardedSourceCallerDemandDispatcherTest`, accepted-authority tests (`P`) | Final static call-graph/host test must include every UI, worker, shortcut, legacy and feature entry point. |
| `WI-QOS-BATTERY-001` | `FiveLevelSourceConfigurationTest`, `SourcePlanResolverTest`, radio runtime tests (`P`) | Durable radio spent allowance/cooldown and final host/permission/thermal transitions; later device energy measurement is separate evidence. |
| `WI-LIFECYCLE-RUNTIME-001` | `DefaultTrackingStartRequestCoordinatorResumeTest`, `TrackerServiceRedeliveryRecoveryTest`, shared controller tests (`P`) | Exact End/Off across every surface, manual permission continuation, passive/system recovery and user-stop races. |
| `WI-LIFECYCLE-PRODUCT-PROOF-001` | `SourceLifecycleProofCoordinatorTest` already enumerates all 64 six-source subsets and one-source failure; source WAL tests (`P`) | Ambient aggregate Steps truth, ambient radio carriers, retained Location and final bounded production query adapters. |
| `WI-STEPS-NUMERIC-001` | Steps numeric/count-domain/goal tests, `AmbientStepsDayRepositoryTest` (`P`) | Carry exact qualified all-day totals and unavailable intervals into daily statistics, every summary, file and effect consumer, independent of session boundaries. |
| `WI-CROSS-SOURCE-RECOVERY-001` | `SourcePipelineRecoveryTest`, provider handoff, import replay and full-clear tests (`P`) | Compose process loss/reboot/FGS refusal with joined purposes, pending file jobs and no-resurrection after erase on final schema. |
| `WI-CROSS-SOURCE-PRIVACY-001` | `ActivityControlRetentionMaintenanceTest`, `SourceEventStorageMaintenanceTest`, `OwnedTransferFilesTest`, diagnostic validator (`P`) | Implement approved archive due-cleanup/scope, active manual-export/full-clear cancellation, safe cleanup of custom private export destinations and later external residual notice; finish purpose-scoped read/erase. |
| `WI-SHARED-FILE-ASSEMBLY-001` | Portable source/ambient files, session bundle and exact Location file tests (`P`) | Complete selected-format dispatch, approved one-choice-per-import conflict warning for edited Location IDs, approved all-data backup without settings, and available network/tower details in chosen radio formats. |
| `WI-SOURCE-ACTIONS-EXPLANATION-001` | `SourceHistoryDetailPresenterTest`, source deletion tests and dashboard action tests (`P`) | Production selectors/dispatch, per-source honest unavailable/repair and exact selected export/delete semantics; calculate requested month/year statistics from qualified data instead of displaying all-time values. |
| `WI-SHARED-HISTORY-DAY-RANGE-001` | `UnifiedHistoryMergeTest`, `UnifiedHistoryCandidateQueriesRoomTest`, ambient day readers (`P`) | Production bounded facade, paging/calendar/timeline/detail and invalidation over final retained readers and annotation overrides. |
| `WI-INTEGRATION-SCENARIOS-001` | Existing 64-subset plan/proof tests, source join/leave/controller tests; new `ManualTrackingStartTest` 64-selection API readiness case (`P`) | End-to-end provider→WAL→fact→history/UI/file→retirement cases for each solo source and representative combination remain. Dashboard route must still honor End/Off during permission repair. |
| `WI-UI-SURFACES-001` | Setup, Dashboard, Tracker, notification/widget, History and map test sources (`P`) | Approved six-source setup/recovery and trustworthy chosen-source live values; final source-only calendar/detail/map and accessible external actions. |
| `WI-NONSTEPS-EFFECTS-001` | `StepsGoalRewardProjectorTest` and existing points/XP tests (`P`) | Non-Steps eligible-session effects and correction/delete retraction must use qualified facts without re-awarding legacy receipts. |

## Scenario accounting

| Category | Authored now or already present | Still required when its production contract is assembled |
| --- | --- | --- |
| Solo and subsets | `TrackingSessionOwnershipStressTest` covers all 64 configured six-source masks; `FiveLevelSourceConfigurationTest` covers all five levels × 64 masks; `SourceLifecycleProofCoordinatorTest` covers 64 selected proof subsets. This continuation adds all 64 manual-start API readiness decisions with Location permission absent, asserting that Activity/Steps/Pressure can start independently and radio-only selections expose a Location repair. | Actual Dashboard-to-provider-to-product/file/Stop for each solo source and representative combinations, including no Location. Pure subset tests cannot substitute for that chain. |
| Join and leave | Shared Location, Wi-Fi and Cell controller tests retain an authorized background join when capture ends; source-specific tests cover failed retirement. | Passive system Location, temporary feature leases, radio notification host and purpose-scoped deletion joined to the final physical owner. |
| Lifecycle | Exact admission/sequence and zero baseline, source proof, start budget, service redelivery, user-stop and CONTROL expiry have authored cases. | Implement approved late manual permission/Location Services result fencing after End/Off or source change, exact external actions, active/ambient automatic transitions and finalized owner generation/registration proof. |
| Privacy | CONTROL plus captured/Ambient sibling retention, diagnostic redaction, partial export and temporary-file full clear have authored cases. This continuation adds spool, session-bundle staging and completed files in the default private-export directory to the full-clear fixture and cleanup. New scheduled private exports use this single owned directory even when a saved plan has a legacy custom path. | Implement approved data-only backup, active manual export cancellation during clear, safe cleanup or disclosure of older custom-folder files, edited-file conflict choice and external residual UX. |
| Recovery | `SourcePipelineRecoveryTest`, provider handoff, retained-import and startup recovery cover bounded replay and several process/reboot failures. | System passive Location and scheduled background Steps recovery; durable radio active-attempt budget; joined-source FGS denial with truthful gaps. |
| Migration | Populated v27→v28 and real Room reopen/full clear, source migrations and unknown development-v28 containment are authored. | Regenerate and inspect the one final schema only after all approved contracts land; add populated fixtures for any new table/column and verify every final read/erase path. |

The user resolved the per-item radio contract, spent active-request limits, optional host, passive
Location fallback, CONTROL owner, external End/Off actions, and several file/privacy decisions.
They remain implementation work. The user approved one choice for all edited Location-ID conflicts,
all-data backup without settings, and one dedicated future private-export folder with an honest
notice for unidentifiable old custom files. The user approved supported on-device background Steps
providers and removal of the unused sunrise/sunset passive Location listener, which has no
production callers. The user also approved saving available network/tower details whenever their
sources are enabled, excluding SIM/subscriber IDs. Recoverable older radio details must migrate
into the new child-observation model; missing old details remain honestly absent. The user
approved one Steps choice for all-day recording. Sessions annotate their time and may prefer
higher quality, but do not own or gate the daily Steps total. A supported on-device provider may
fill background gaps; unsupported periods remain visible gaps. These are approved contracts,
not completed implementation or executed evidence.
This trace does not declare the assembly gate complete or permit the deferred validation batch.
