# All-day Steps execution and remaining display seam

## Boundary

Static inspection of exact committed source at `879ee7f5d8ea06effa7da1002f600edb9d2b2db0`, using `git show` / `git grep` against that commit, not mixed working drafts. `ALL_DAY_STEPS_EXECUTION_INPUTS.json` pins the inspected source and test blobs. No production or test source changed. No gates, test execution, provider activation or Git mutation. Implementation and authored scenarios remain `IMPLEMENTED_UNVALIDATED` / `NOT_RUN`; this is not complete-assembly, hardware or integration readiness.

Approved behavior: one Steps choice enables all-day collection; supported on-device providers copy hourly where permitted and when enabled/opened, with a 30-second attempt; sessions add organization and supported direct sensor fidelity; keep local calendar days, independent purpose lifetimes, original real provider windows, deduplication and honest gaps. User priority is completing tracking architecture, not backup.

## Connected committed execution

| Link | Exact committed source evidence |
| --- | --- |
| One choice | `app/src/main/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModel.kt:319` changes Steps and its compatibility mirror together. `data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/AuthoritativeTrackingParamsRepository.kt:848,862,917-918,961-979` derives both from the one source selection. `DefaultTrackingParamsRepository.kt:243,328,339,491-508` migrates the released single preference, not an invented released split. |
| Purpose publication | `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntime.kt:877-899,970-993` installs and delegates the built-in all-day Steps owner, including retention compensation/full-clear cleanup. `di/TrackingPurposePublicationModule.kt:87-89` binds that production owner. |
| Enable / cold rearm | `source/runtime/AmbientStepsPurposeOwner.kt:90-112` schedules immediately only for a new/rearmed accepted provider generation, preserves cadence on ordinary reconciliation and cancels both schedules on inactive/retired authority. |
| Hourly / open | `worker/AmbientStepsImportWorker.kt:27-48` uses unique hourly `KEEP` work and a separate one-time `KEEP` copy. `module/TrackerModuleInitializer.kt:74-81,223-246` registers the process foreground observer before unrelated startup repair debt; every opening/reopening requests a bounded copy. |
| Worker composition | `app/src/main/java/com/adsamcik/tracker/app/Application.kt:84,162` supplies Hilt worker creation; `app/src/main/AndroidManifest.xml:28-29,78` declares Health Connect read permissions and the intentional WorkManager initializer replacement. `tracker/engine/build.gradle.kts:80,89` includes Hilt Work and WorkManager. |
| Bounded independent attempt | `source/ambient/steps/AmbientStepsImportRunner.kt:32-59,64-106` gates real collection authority, allows supported foreground-only reads only while foreground, bounds mutex/startup/provider work to 30 seconds and 32 structural windows, propagates cancellation and keeps unrelated source debt from denying independently authorized Steps. Worker retries are bounded at `AmbientStepsImportWorker.kt:61-67`. |
| Real platform collection | `AndroidAmbientStepsCapabilityResolver.kt:41-111` checks supported on-device mechanisms and permissions. `AndroidAmbientStepsProviderBackends.kt:17-35` checks Health Connect permission-driven current-device capture; `:46-71` owns the app-global Local Recording subscribe/unsubscribe. Provider generation reservation/acceptance, cold rearm, handoff drain and acknowledged cleanup are connected in `AmbientStepsProviderRegistrationCoordinator.kt:197-258,497-559`. |
| One app direct-sensor owner | `di/SourcePipelineModule.kt:92-93` routes Steps runtime through `SharedStepSourceController`. That controller's `:285-292` selects exact session capture joins; `StepSourceRuntime.kt:54-62,526,1009` owns the app's one `SensorManager` step-counter listener. It is distinct from the OS / Google Play services recording mechanism; the app cannot promise one physical platform sensor owner across those APIs. |
| Independent lifetime | `SharedStepSourceController.kt:167-214` releases only the selected direct-sensor session join. All-day provider publication/work remains owned by the independent all-day owner, not that session join. End does not unsubscribe all-day collection; Off/retention/full-clear retire its separate demand and work. |
| Real windows / no duplicate hourly sums | `AndroidAmbientStepsProviderReaders.kt:33-80,94-156` retains actual provider windows, current-device-only Health Connect origins and exact Local Recording buckets. `AmbientStepsStructuralWindowPlanner.kt:69-154` revises the same progressive civil-day window. `AmbientStepsFactImporter.kt:1103-1127,1140-1209` preserves logical identity, appends semantic corrections, and advances exact cursor state atomically. |
| No double-counting | `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/AmbientStepsDayComposer.kt:188-193,248-357,360-413` uses qualified all-day aggregates as the total, never adds session counts, and requires exact count-domain compatibility before subtracting session counts. `tracker/engine/.../source/summary/RoomStepsNumericSummaryRepository.kt:413-452` connects this source result to the numeric repository. |
| Actual daily consumers | `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/presenter/StatsPresenterViewModel.kt:128-147` observes the qualified week including today without session-count gating; `HistoryPresenterViewModel.kt:214,428-433` reads daily Steps for calendar/history. These are real consumers, not an unused coordinator overload. |

Paths beginning with `source/`, `di/`, `worker/` or `module/` above are relative to `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/`. The JSON uses full repository-relative paths.

## Concrete remaining production seam

### S1 — A saved ongoing day is labelled as pending materialization

`stats/data/.../NativeAmbientStepsRangeReader.kt:347-352` marks a day materializing when an active cursor has `importedThroughTimeMs < day.endTimeMs`, even when the reader already has qualified saved facts. At noon, a successful copy ends at noon, so the not-yet-elapsed remainder of today always meets that condition. `DefaultAmbientStepsHistoryRepository.kt:709-714` replaces the already-composed count-bearing `Partial` with `Unavailable(MATERIALIZING)`. A normal ongoing day can therefore remain “Updating” until midnight.

The existing cursor is a durable imported high-water, not a pending job: `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/AmbientStepsImportStateEntities.kt:9-16,32-65,120-122` contains provider lifetime, observed boundaries, authority and revision, with only ACTIVE/RETIRED status. It stores no pending WorkManager target or in-progress read. The runner already knows its fixed `throughTimeMs` and reports COMPLETE once a result reaches it (`AmbientStepsImportRunner.kt:69-86`); that target is not a new durable requirement. Importer commit stores the fact/count-domain/evidence/cursor in one existing Room transaction (`AmbientStepsFactImporter.kt:223-230,1164-1200`).

**Smallest recommended source correction, subject to the pending display approval:** retain the existing day composer result when it contains a qualified saved `Exact` or `Partial` count. Apply waiting/materializing replacement only when there is no qualified count, preserving the earlier `native.unverifiableDays` branch and all typed dependency/authority failures. This can be a narrow `composePublicDay` change; no clock, job ledger, new writer, schema or new proof subsystem is needed. The existing reader's materializing progress metadata can remain available to source-status consumers. Do not manufacture coverage for the unelapsed remainder of today.

### S2 — The strict numeric consumer drops safe partial counts and whole-week sibling values

`tracker/engine/.../source/summary/StepsNumericDayWindowAccumulator.kt:596-624` intentionally drops the count in `AmbientStepsHistoryValue.Partial` and makes any partial day render the entire request unverifiable. `StatsPresenterViewModel.kt:128-135` asks for a week including today; `StatsScreen.kt:700-759` displays only Updating/Partial/Unavailable when that strict result cannot be Ready. `HistoryPresenterViewModel.kt:428-433` and `ui/CalendarContent.kt:317-325` likewise lose today's safe subtotal. Thus even after S1, a valid count through the latest copy is not shown today, and one incomplete day hides complete numbers from other requested days.

The strict API also feeds games/achievements (`feature/game/src/main/java/com/adsamcik/tracker/game/repository/SourceQualifiedStepsSummary.kt:36,118`). **Do not relax it or present an incomplete subtotal as a complete daily total.** The pending user proposal is display-only reuse of the existing daily reader: show trustworthy saved counts as “Recorded so far”, label real gaps “Incomplete day”, keep independently valid days visible, and withhold conflicting/invalid counts. No UI or behavior change was authored in this trace.

## Which existing outcomes contain trustworthy numbers?

| Existing retained outcome | Meaning and permitted interpretation |
| --- | --- |
| `AmbientStepsHistoryValue.Exact(count)` | Qualified complete structural-day coverage; a complete daily total. |
| `Partial(count, PARTIAL_COVERAGE)` | Count is the safe sum of qualified, nonoverlapping saved windows, not a complete day. It can be a display subtotal. This cause alone does not distinguish normal ongoing time from enable/Off/retention missing intervals; do not assert “ongoing” from it alone. |
| `Partial(count, EXPLICIT_GAP)` | Safe saved subtotal with a real missing interval; display only with incomplete coverage. |
| `Partial(count, DELETED/RETAINED)` with surviving qualified facts | Only the surviving saved subtotal; never restore erased/retained-away counts or call it complete. Imported source causes are added at `DefaultAmbientStepsHistoryRepository.kt:923,954-956,1018-1020`. |
| `Unavailable(FACT_OVERLAP/ORIGIN_IDENTITY_CONFLICT/COUNT_OVERFLOW/STRUCTURAL_AUTHORITY_CONFLICT/SOURCE_AUTHORITY_UNVERIFIABLE)` | No trustworthy composed count. Never use another field, session total or zero to fill it. |
| `Unavailable(NO_EVIDENCE/MATERIALIZING)` / typed overflow, storage or calendar failure | No number is supplied. Keep the distinct state; do not guess zero. |
| Session comparison unavailable | Does not itself invalidate a qualified all-day total. Session subtraction is independent and may remain unavailable. |

Public variants are in `stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/AmbientStepsHistoryRepository.kt:248-275,319-336`.

Before a count reaches `Partial`, the existing native reader validates latest effective correction/retraction lineage and checksum (`NativeAmbientStepsRangeReader.kt:70-104,381-421`), original fact/registration/cursor/policy/consent timelines (`:245-282,423-454`), exact local day/zone, owner generation, data epoch and retention floor (`:434-454`). The day composer rejects structural mismatches, conflicting repeated identity, mixed count domains, overlapping windows and overflow before summing (`AmbientStepsDayComposer.kt:262-343`). `composePublicDay` preserves native authority-unverifiable precedence (`:703-708`). These qualification guards must remain unchanged.

## Supported limits, not missing wiring

- Android can delay hourly work. Foreground-only Health Connect permission disables hourly reads but keeps foreground copies and a repair state (`AmbientStepsPurposeOwner.kt:114-144`). Unsupported provider/service or missing permission does not become a measured zero.
- Real Health Connect / Local Recording aggregate APIs expose no authenticated cumulative sensor epoch (`AndroidAmbientStepsProviderReaders.kt:60-64`). Exact subtraction against direct session sensor totals is therefore honestly unavailable. This must not become fabricated WAL admission, borrowed counter identity, or duplicate addition.
- Initial unobserved calendar authority, observed zone changes, stop/resume and unavailable predecessor reads preserve explicit gaps (`AmbientStepsFactImporter.kt:966-1046,1357-1460`). Unknown travel history is not reconstructed using today's zone. UTC observation times and local civil-day grouping remain separate.
- Multiple independent provider/origin domains are conservatively nonadditive even for adjacent windows (`AmbientStepsDayComposer.kt:306-329` and its `AmbientStepsDayComposerTest.kt:260` contract). Changing that policy would be a separate decision, not a scheduler repair.

## Authored coverage and remaining source cases

All listed tests are source inventory only, **NOT_RUN**. Existing committed scenarios cover hourly KEEP/cancel/retry, reopen enqueue failure, enable/rearm scheduling, timeout/cancellation, real importer+Room cold Worker without a session, stop before/during copy and resume gaps, native provider activation/cleanup/handoff, original windows/correction replay, daily count deduplication and retained qualification.

Representative exact cases: `AmbientStepsImportWorkerTest.kt:34,63,80,91,98,105`; `AmbientStepsForegroundImportTest.kt:18`; `AmbientStepsPurposeOwnerTest.kt:132`; `AmbientStepsImportRunnerTest.kt:14,82,96,104`; `AmbientStepsFactImporterTest.kt:218,261,677,727,849,882,909,930,955`; `AmbientStepsProviderRegistrationCoordinatorTest.kt:122,167,181,260,282,333,394,409`; `RoomStepsNumericSummaryRepositoryRoomTest.kt:156,177,193,211`.

Focused source cases needed for S1/S2, after the pending approval: successful mid-day real-provider-window import through the existing daily reader preserves its qualified subtotal without fake complete-day coverage; enabled/no-fact remains waiting; genuine gap/correction/retraction/retention invalidation remains distinct; conflicting/overlapping/invalid-clock inputs expose no count; today's incomplete value does not hide yesterday's exact one; strict numeric/game consumers still withhold partial counts. The existing `AmbientStepsDayRepositoryTest.kt:126-145` asserts cursor progress metadata, not permission to discard an already-qualified subtotal, and `:494-506` should keep its honest no-fact materialization case.

No other concrete missing production execution link was established in this bounded trace. The remaining immediate slice is the saved-day composition/display connection, not another all-day collection driver. Full-chain, device, performance and integration readiness remain unproven under the current phase boundary.
