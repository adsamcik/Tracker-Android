# Session End and Activity/Pressure coverage — 2026-10-25

## Scope and repairs

This change addresses slow manual End and interrupted Activity/Pressure coverage through the
existing shared tracking flow. It adds no database table, migration, provider owner or background
polling framework.

- Preserve compatible Pressure collection across settings changes and keep valid all-day data.
  Preserve real admission losses; do not count capture-only callbacks measured wholly after End as
  missing session data.
- Keep session-owned Location/Pressure listeners stable during background reconciliation. Temporary
  contention retains valid background demand and retries through the existing worker; actual
  permission loss, Off and unavailable hardware keep their existing behavior.
- Return Pressure's earned End acknowledgement before independent all-day startup. Persist its real
  metrics in the existing exact End receipt before replacement; cancellation, failed saves, Close
  and erase retain that result. Reuse actual Location predecessor receipts too.
- Preserve Activity's known admission losses and orderly retirement without inventing a complete
  Google Play services flush. Unrelated sessionless diagnostic failures do not hide later sessions.
- Release the command mutex during read-only readiness and ordinary background-provider waits.
  Keep serialized mutations, deletion cancellation and final generation checks. Retry known
  projection-lease contention promptly after physical retirement; other failures retain backoff.
- Include Activity/Pressure in availability and show their saved measurements during a session.
  Keep final coverage separate, preserving useful partial data rather than hiding it.
- Show **Finishing session…** immediately after End, freeze elapsed time at its saved cutoff and
  prevent another Start until shutdown is acknowledged.
- Renew the existing reconfiguration lease through publication and finalization as well as provider
  actions. A focused regression holds publication beyond the original lease lifetime.
- Accept the actual shared Pressure provider's qualified all-day endpoint when settling its exact
  retirement receipt. Keep it all-day data and preserve the receipt's real lost-callback range.
- Cancel the dashboard's live history query at End and retain its last same-session display until
  removal; a new session resumes its own reader. Finishing uses static disabled controls rather
  than continuing indeterminate animations.
- Avoid full Steps qualification for unrelated sensor sequence, projection-lane and global revision
  ticks. Keep every dependency observed; compare bounded Steps metadata and structural evidence
  before recomputing. Other dependencies, changed Steps state, overflow and metadata-read failures
  follow the normal read. Cancellation still propagates and storage recovery remains observable.
- Keep queued all-day Activity/Pressure callbacks on their actual acquisition plan during Start.
  A non-null older configuration is not authority to attach a newly published session. For already
  saved Pressure carriers with that exact misclassification, retain raw/all-day data, settle the
  rejected capture through the existing terminal projection record, and preserve valid sibling
  session readings with partial coverage.
  Pressure also snapshots the original applied caller in its existing callback/window attribution;
  all-day policy and capture acquisition numbers can coincide, so equality alone is insufficient.
  Queued windows reuse the existing `PlanAttribution` value rather than later session membership.
- Await normal failed-start compensation in its existing cancellable startup job. The 250 ms
  callback-cancellation reserve must not truncate ordinary database cleanup. Keep Starting visible
  until that attempt settles; exact generation guards and the bounded cancellation path remain.
- Preserve qualified Pressure windows when one aggregate crosses the exact saved End. Validate its
  complete original lineage before classifying that boundary; omit only the unsplittable aggregate,
  preserve raw data and mark Partial. Earlier withdrawal and malformed data still reject normally.
- Read the latest qualified live Activity observation through the existing bounded source snapshot.
  Show its movement and saved local time without inventing a band, active duration or coverage.
  Scope the lookup to the selected logical recording using its existing index, with no schema change.
- Serialize source reconfiguration together with its exact descriptor publication. Keep startup
  pending until presentation is bound; preserve independently required background hosting if End
  interrupts that preparation. Reuse the same mutex for initial apply and presentation mirroring.
- Recover retained, originally authorized readings when Stop precedes presentation setup. Reuse the
  existing source writers; retry Activity's already-settled missing-segment input transactionally
  without rewinding its cursor. Clear the old failure only after its actual writer succeeds.
- Initialize the existing notification component from the accepted session's manual/automatic type,
  without depending on provisional UI publication. Recover the original recording end separately
  from the actual quiescence acknowledgement time; keep existing counters and acknowledgements.
- Treat temporary prepared-start lease contention as the existing bounded deferred claim, retaining
  the original Start and rechecking its authority. Invalid or stale claims still reject normally.
- After Android foreground promotion for a fresh manual Start, allow its existing cancellable
  claim/setup to finish without the providerless 30-second completion deadline. Durable acceptance
  still must succeed. A default-false private Intent
  hint comes only from the resolved fresh manual origin and changes patience, never authority.
  Early foreground, startup Ready, automatic, passive, recovery and cancellation fences remain.
- Read already-ready Location writer state before taking the mutation lease, as the other source
  writers do. Actual setup keeps its existing lease and transactional recheck.
- Keep qualified Pressure values and partial coverage visible without a redundant Preparing label
  while the remaining history is still processing.
- Stop the shared app-scoped Location summary observation during Finishing, retaining its last
  useful value. Cancel the real query, reject raced publications after Stop, and clear normally on
  session removal or deletion. A successor session starts its own existing reader.
- Use the same qualified saved Activity/Pressure values in mixed Location sessions as source-only
  sessions. The existing grid and sensor details preserve healthy siblings and never substitute
  stale legacy Activity. Valid Pressure values have no redundant Preparing feedback.
- Let ordinary cancellable presentation persistence finish without an aggregate four/five-second
  deadline. A slower valid close must advance rather than commit repeatedly and receive timeout
  cancellation before the next teardown step. Cycle/provider and inner operation bounds remain.
- Keep a fully retired sessionless Pressure predecessor out of a successful first session join's
  capture acknowledgement. Preserve its raw data and stored retirement metrics; never attach the
  new session's IDs to it. Captured and unfinished predecessors retain their existing handling.
- Keep captured Activity/Pressure in compact live history when Location is also selected, using
  the existing bounded source union. Location without those sensors retains its lighter read.
- Defer a valid current run's prebinding Location point instead of recording a terminal missing
  segment. Normal qualification runs after the exact binding is published; stopped/unknown runs
  retain the existing recovery and rejection rules.
- Limit legacy raw-observation repair to the released carrier shape before loading payloads.
  Protected pending observations remain with their existing canonical handoff. This removes
  repeated loading of an ineligible shrinking tail during per-point offline recovery; full Kotlin
  validation and the broad instrumentation integrity check remain unchanged in scope.

## Verification

The final reviewed fixes pass the Dev/Hilt/APK build, 190 focused behavior cases and Detekt with
zero findings. A copied native database also verifies all 15 captured Pressure windows, including
the seven previously rejected after an authorization refresh. The latest host results and remaining
Android acceptance are recorded at the end of this document.

Final native Start/End acceptance remains **PENDING**. Batch 64's clean mixed-source Start 238
stopped internally after 106.931 seconds, before a user End; it is failed acceptance, not an End
latency result. The final APK is installed, but another chat is using the same emulator. A startup
delay was also observed; its cause is not established. The local commit preserves implemented,
host-verified work without claiming that the final Android workflow has passed.

The existing `Android_17_Google_Play` AVD is the only runtime target: API 37/Android 17 REL,
x86_64, Google Play 37.2/16 KB, `emulator-5554`. Installation preserves app data with
`adb install -r`; package `com.adsamcik.tracker.dev`, **Advention Dev**.
The installed verification APK is `10.0.0-dev` (400, Dev variant), SHA-256
`50506E5472142A819886289D2D500E8202B706CB8D9217F995F3F86A103206B5`, installed
October 26 at 09:44:16 with app data preserved. Fresh native acceptance remains pending. Batch 61's verified
Dev APK was `B57954A38D4720424C13399D4FFB2A2000145752C8CB81642DEF36633A5D87A9`;
its failed native attempt is recorded below.

Raw local evidence: `build/emulator-verification-20261025/` (ignored). No emulator reset, replacement,
older API, app-data clear, forced stop during measurement, or unrelated data deletion is used.

### Host checks

These are affected checks, not a full repository test run. Batch 14 fixes both failures from batch 13;
unchanged reader/shared-owner/replay results are reused. Production changes received independent
source review.

| Batch / local log | Result | Checks |
| --- | --- | --- |
| 13 — `host-final-shared-coverage.log`, 18m05s | Failed; two failures repaired in 14 | 83 engine cases, including 11 Shared Pressure, 18 ambient-owner and one timeout-replay pass. All 151 reader cases passed: 113 Pressure, 38 Activity. |
| 14 — `host-retirement-repair-final.log`, 13m53s | Passed | 58 cases: 51 Pressure runtime and seven selected repository cases. Dev/Hilt/APK and Detekt passed. |
| 15 — `host-shared-cancellation-final.log`, 12m46s | Passed | 30 cases: 12 Shared Pressure and 18 ambient-owner. Dev/Hilt/APK and Detekt passed. |
| 16 — `host-native-gap-repair-final.log`, 21m56s | All behavior checks passed; two brace style findings repaired afterward | 248 cases: 89 Room ingress, four coordinator, 115 Pressure history and 40 Activity history. Dev/Hilt/APK passed. |
| 17 — `host-final-format.log`, 4m37s | Passed | Dev/Hilt/APK and Detekt after adding braces; no behavioral changes or test rerun. |
| 18 — `host-retirement-tail-lease.log`, 8m41s | Failed before affected engine tests | Kotlin captured-variable smart cast and one retirement-function complexity finding; repaired before the next batch. |
| 19 — `host-retirement-tail-lease-repair.log`, 30m40s | All behavior checks passed; one complexity finding remains | 34 coordinator cases passed, with zero failures/errors/skips; Dev/Hilt/APK passed. The null-or-zero endpoint check is subsequently extracted without changing behavior. |
| 20 — `host-foreground-order-final.log`, 14m43s | Failed; repairs continue | Dev/Hilt/APK passed. 16 of 17 service cases passed; one new MockK getter-chain verification failed and is corrected. Detekt identified host complexity/function-count findings, addressed in the next source slice. |
| 21 — `host-cancelled-delivery-final.log`, 17m13s | All 40 behavior checks passed; two style findings remain | Dev/Hilt/APK passed. Four host, 15 cancellation/service and 21 foreground-type cases passed with zero failures/errors. The final simplification also addresses a compound guard and long line reported by Detekt. |
| 22 — `host-unified-delivery-final.log` | Cancelled; no passing result claimed | Independent review found that stale manual rejection also needed the existing pending-background-work guard. Only this batch's Gradle client was cancelled; shared daemons were retained. |
| 23 — `host-unified-delivery-verified.log`, 15m15s | Passed | Dev/Hilt/APK, Detekt and all 41 cases: four host, 16 service/cancellation and 21 foreground-type cases; zero failures/errors. |
| 24 — `host-shutdown-read-isolation.log` | All 65 behavior cases and Dev/Hilt/APK passed; quality interrupted | 41 Room Steps, 14 dashboard live-view-model and ten tracking-action cases passed with zero failures/errors. The client was cancelled to narrow test discovery, but reports establish that the tests had just completed and only Detekt was interrupted. Their passing results are preserved and reused. |
| 25 — `host-read-isolation-quality.log`, 2m21s | Packaging passed; one test-layout finding | The new stopping regression exceeded the method-length limit. Constructor layout is shortened without changing behavior or assertions; the 65 passing cases remain valid. |
| 26 — `host-read-isolation-quality-final.log`, 1m06s | Passed | Dev/Hilt/APK and Detekt passed after the layout-only correction. All 65 unchanged behavior results are reused. |
| 27 — `host-capture-membership-final.log` | Cancelled before tests; no passing result claimed | Independent review found that recovery must wait until the Pressure writer has passed the misassigned carrier before saving its terminal capture rejection. Only this attempt's identified Gradle client was cancelled; shared daemons were retained. The same positive regression now covers that ordering. |
| 28 — `host-capture-membership-verified.log` | Cancelled before tests; no passing result claimed | A fresh-database trace established that initial policy and acquisition revisions can both be 1. The producer must preserve original runtime ownership as well as the revision, instead of relying on number equality. Only this attempt's identified Gradle client was cancelled. |
| 29 — `host-capture-attribution-verified.log`, 11m44s | Packaging passed; 285 of 290 cases passed | All 52 Pressure runtime, six recovery, 17 window-lane and five coordinator cases passed. Three new Room ingress fixtures lacked canonical Pressure writer metadata; two new history fixtures reused an existing intrinsic delivery identity. Both setups are corrected without changing assertions. The runtime formatting and exact-writer predicate extraction address two quality findings. |
| 30 — `host-capture-fixtures-verified.log`, 6m49s | All ten focused reruns and packaging passed; one quality finding | Three repaired ingress, two repaired history and five ownership cases passed. The 290 unique cases now have zero remaining failures/errors/skips, independently reconciled by exact test identity. The additional helper exceeded its class method-count limit. |
| 31 — `host-capture-quality-final.log`, 3m04s | Packaging passed; one quality finding | Moving that pure helper exceeded the file function-count limit. The final source removes it and applies the same persistence predicate during the existing binding lookup, without adding a layer or weakening checks. Independent review confirms semantic equivalence; behavior results are reused. |
| 32 — `host-capture-quality-verified.log`, 1m00s | Passed | Detekt has zero findings across 3,303 root-checkout files; alternate checkouts are excluded. The unchanged 290 affected behavior results are reused. |
| 33 — `host-capture-dev-final.log`, 2m52s | Passed | Dev/Hilt/APK packaging passed. Its native run below is not acceptance. |
| 34 — `host-normal-compensation-verified.log` | Packaging passed; test run cancelled during broad discovery | The ordinary compensation repair compiled and packaged. Gradle still forked unrelated classes despite method filters; only this run's client was stopped. No test or quality pass is claimed for this attempt. |
| 35 — `host-normal-compensation-focused.log`, 3m19s | Passed | All 41 cases passed: four host, 16 cancellation/service and 21 foreground-type cases, with zero failures/errors/skips. Discovery is bounded to those same three classes. Detekt has zero findings across 3,303 root-checkout files. Batch 34's unchanged Dev/Hilt/APK is reused. |
| 36 — `host-product-readers-final.log` | Cancelled before checks | Independent review found the new Activity lookup selected the all-retained-source index. The exact client was stopped; the existing logical-recording index now bounds the lookup. No pass is claimed. |
| 37 — `host-product-readers-verified.log`, 13m54s | All 188 source-reader cases and packaging passed; Dashboard test compilation and quality failed | Activity composer 44, native Activity Room 26 and Pressure history 118 cases passed with zero failures/errors/skips. Dashboard had one unresolved test-only import for an existing assertion member. Five quality findings were missing braces and two functions at complexity 11. The import, braces and exact guard placement are corrected without weakening assertions or changing reader behavior. |
| 38 — `host-product-presentation-final.log`, 4m13s | Passed | Dev/Hilt/APK, all 12 Dashboard cases (eight mapper, four rendered-card) and Detekt passed with zero failures/errors/skips/findings. Independent review confirms the 188 stats results remain valid after exact guard relocation and braces. The final reader/presentation evidence therefore covers 200 passing cases. |
| 39 — `host-startup-binding-final.log`, 12m26s | Packaging passed; 94 of 95 cases passed | Lifecycle 13, service 18, Activity lane 12, Activity adapter 49 and three selected Wi-Fi cases ran. The sole failure was MockK recursion from the new service fixture's virtual context attachment. Detekt reported complexity 12 in the new recovery candidate loop. The fixture now sets its base context directly; equivalent positive guards simplify the loop without a new helper or suppression. |
| 40 — `host-startup-binding-verified.log`, 5m51s | Passed | Dev/Hilt/APK, the repaired permission-deferral fixture, retained Activity writer rollback/replay and Detekt all passed. Independent review confirms the unchanged 93 other cases remain valid: all 95 unique cases now have zero remaining failures/errors/skips; quality has zero findings across 3,303 root-checkout files. |
| 41 — `host-notification-start-final.log`, 5m20s | Passed | Dev/Hilt/APK, all seven actual-notification and 14 presentation lifecycle cases, and Detekt passed with zero failures/errors/skips/findings. The notification cases include accepted manual/automatic intent before UI publication; lifecycle cases separate original End from real recovery acknowledgement. Together with unchanged cases from 39/40, the final startup/recovery/notification evidence covers 103 unique passing cases. |
| 42 — `host-start-lease-final.log`, 4m55s | Passed | Dev/Hilt/APK, all 11 prepared-start resume cases and Detekt passed with zero failures/errors/skips/findings. The new cases retain the same Start through temporary lease contention and preserve stale-claim rejection. The unchanged 103 engine and 200 reader/presentation results remain valid. |
| 43 — `host-location-ready-final.log`, 6m36s | Passed | Dev/Hilt/APK, ten selected real Room Location setup/clear/ownership cases and Detekt passed with zero failures/errors/skips/findings. Ready state no longer competes for a lifecycle lease; nonready setup remains Busy without mutation under another owner. |
| 44 — `host-pressure-status-final.log`, 2m12s | Passed | Dev/Hilt/APK, all five rendered Pressure card cases and Detekt passed with zero failures/errors/skips/findings. A retained value no longer displays redundant Preparing text; missing/error states remain. |
| Diagnostic — `host-location-replay-diagnostic.log`, 1m23s | Confirmed the native failure | A temporary Room test replayed the preserved database copy and reproduced `Unsupported captured Location QoS 0`. The diagnostic source was removed; its failure receipt is retained locally. |
| 45 — `host-location-ambient-qos-final.log`, 3m40s | Packaging and six Room cases passed; one quality finding | The all-day QoS fix passed its regressions. An equivalent two-guard form replaced the initial three-branch mapping to meet the existing complexity limit. |
| 46 — `host-location-ambient-qos-verified.log`, 2m56s | Passed | Final Dev/Hilt/APK, all six affected Room cases and Detekt passed with zero failures/errors/skips/findings. The reviewer confirmed the equivalent mapping. |
| 47 — `host-final-coverage.log`, 5m05s | Packaging, quality and 35 of 36 cases passed | All seven shared Location presentation cases and 28 dashboard cases passed. The new mixed-source mapper fixture retained unrelated qualified Steps sources; corrected without changing assertions or production rules. |
| 48 — `host-final-coverage-fixture.log`, 1m24s | One later fixture invariant failed; quality passed | The focused rerun reached the failed-Activity phase, whose fixture incorrectly retained Partial coverage after changing its state to Failed. It is corrected to the existing None/no-observation contract. Production and assertions remain unchanged. |
| 49 — `host-final-shutdown.log`, 4m36s | Packaging and all 22 behavior cases passed; one layout finding | All 21 shutdown cases and the corrected mixed-source case passed with zero failures/errors/skips. The helper exceeded its existing method-length rule by five lines; only constructor/result layout is shortened. The reviewer confirmed equivalence and reuse of these behavior results. |
| 50 — `host-shutdown-quality.log`, 3m23s | Packaging passed; one line-length finding | One compacted internal timeout message exceeded the line limit. Its wording is shortened; cancellation behavior is unchanged. |
| 51 — `host-shutdown-quality-final.log`, 2m41s | Passed | Final Dev/Hilt/APK and Detekt passed with zero findings across 3,303 files. The 21 shutdown cases, seven shared-summary cases and 29 dashboard cases all pass, reusing unchanged inputs and the corrected mapper rerun. |
| 52 — `host-pressure-join.log`, 4m37s | Passed | Dev/Hilt/APK, all 15 shared Pressure cases (including three new predecessor regressions) and Detekt passed with zero failures/errors/skips/findings. The unchanged 57 shared-summary/shutdown/dashboard results remain valid. |

Additional final batches:

| Batch | Result | Evidence |
| --- | --- | --- |
| 53 — `host-final-binding.log`, 7m13s | Passed | Dev/Hilt/APK, three Location Room cases, all 19 source-union cases and Detekt; zero failures/errors/skips/findings. Covers prebinding deferral, saved failed-row recovery and mixed compact sensor values with sibling isolation. |
| 54 — `host-legacy-repair.log`, 10s | Invocation failed | Dev instrumentation compile task does not exist. The next batch uses the repository's existing Debug instrumentation variant. No production task ran. |
| 55 — `host-legacy-repair-verified.log`, 8m55s | Affected checks passed; full AndroidTest compilation failed | Dev/Hilt/APK, all eight raw-repair cases and Detekt passed. Existing unrelated AndroidTest fixtures fail against current DAO/DTO/internal APIs and Guava classpath; no instrumentation ran. |
| 56 — temporary timing probes, 2m32s | Diagnostic packaging passed | Located the wait after manual preflight returned Ready; this is not native Start/End acceptance. Probe source was subsequently restored. |
| 57 — `host-startup-command.log`, 7m04s | Packaging and 21 initializer cases passed; two quality failures | Two LongMethod findings were repaired without changing behavior; the race regression was split into two focused cases. |
| 58 — `host-startup-command-final.log`, 6m02s | Packaging and all 45 behavior cases passed; two quality failures | 22 initializer and 23 policy cases passed. Detekt reported initialize at 61 lines and an oversized policy test class. Bound gate references and moving the two new cases into their own class address those findings without changing assertions. |
| 59 — `host-startup-command-activity-persistence.log`, 7m04s | Passed | Dev/Hilt/APK and all 77 cases: 56 PersistenceProcessor, 19 Activity runtime and two moved policy cases, including nested tests. Detekt has zero findings across 3,303 files, with no alternate checkouts. 713 tasks: 26 executed, 687 up-to-date. Reuses unchanged 58 results for 22 initializer and 21 original policy cases, plus 55's eight raw-repair passes. Native acceptance remains pending. |
| 60 — `host-compact-activity-continuity.log`, 13m41s | Failed; reviewed repairs await 61 | Dev/Hilt/APK and all 34 shared history API cases passed. Engine passed 143 of 144 cases; the new writer fixture used invalid manifest-2 MANUAL metadata and failed before writing facts. ActivityHistoryComposerTest:171 failed compilation on nullable consentEpoch arithmetic; this was unrelated to the nullable radio DTOs. Four Detekt findings were repaired with line wrapping, braces and two small local radio-filter helpers preserving null omission. Three handoff fixtures now use POLICY_RECONCILIATION metadata. |
| 61 — `host-compact-activity-continuity-final.log`, 8m18s | Passed | Dev/Hilt/APK and all 270 cases in six archived XML reports: 144 engine and 92 data cases fresh, 34 unchanged API cases up-to-date; zero failures/errors/skips. Detekt has zero findings across 3,304 files, with no alternate checkouts. 715 tasks: 60 executed, 655 up-to-date. Results are independently reconciled. Reuses unchanged 59's 77 passes, 58's 22 initializer and 21 original policy passes, and 55's eight raw-repair passes. |
| 62 — `host-manual-completion.log`, 7m24s | Packaging and all 37 cases passed; one quality failure | Dev/Hilt/APK, four Intent, 12 Resume and 21 Prepared cases passed. Detekt reported LargeClass in the existing Prepared fixture. Three pure tests are moved into same-file PreparedForegroundCompletionTest with no production change. The reviewed seven-file repair has four production and three test files. |
| 63 — `host-manual-completion-final.log`, 2m23s | Passed | All 21 cases: 18 cancellation and three foreground-completion helper cases. Detekt has zero findings across 3,304 files. Independent review archived and reconciled the results; production is unchanged from the installed 62 APK. |
| 64 — `host-preflight-history.log`, 1m53s | Passed | Dev/Hilt/APK and Detekt for the reviewed conditional route-preflight change. 657 tasks: 16 executed, 641 up-to-date. Unchanged behavior results from 63, 62, 61 and 59 are reused; no new behavior test pass or native acceptance is claimed. |

Batch 59 XML receipts are preserved under `fifty-ninth-batch-tests/engine` and
`fifty-ninth-batch-tests/preferences`, with `fifty-ninth-batch-detekt.xml`. Its existing
`verify-startup-command-final.ps1` script restricts discovery to the moved policy class and the
PersistenceProcessor/Activity runtime classes and their nested tests.

The AndroidTest errors are in `FiveSourceTrackingHistoryFixtures`, `ManualStepsOnlyDeviceGateTest`
and `DataRetentionInstrumentationTest`, including old Steps constructor fields, unavailable DAO
extensions/internal access, and missing `ListenableFuture`. This repair does not alter those
files or production contracts to make the optional full instrumentation compile pass. The changed
`SourceRecoveryIntegrityInstrumentationTest` has independent source review; its execution remains
unverified. The manual native walkthrough is separate evidence.

The two repaired failures were preservation of a real Pressure timeout status and a test fixture
using a different deletion-epoch store. Focused regressions also cover cancellation after physical
drain, failed acknowledgement persistence, immutable replay and atomic receipt/completeness writes
with real sparse loss metrics.
Batch 16 contains 248 passing cases, with no failures, errors or skips. The
unchanged 58 runtime/repository, 30 shared-owner and one coordinator timeout-replay passes remain
valid. Earlier unchanged affected checks remain recorded in the local batch evidence.

Commands use the repository wrapper and JDK 21. Scripts run `:app:assembleDev` and `detekt` as
applicable; the table records split checks and reuse of unchanged inputs. Invocations use
these common flags; dependencies, SDK levels, quality rules and baselines are unchanged:

```text
--continue --offline --max-workers=1 --console=plain
  -PtraceboxLocalRepository=build/tracking-control-evidence-20261017/verification-20261014/verified-maven
  -I build/verification-20261022-reliability/reliability.init.gradle
```

The scripts under the raw-evidence directory record the exact invocations:

The final startup scripts are `verify-startup-binding-final.ps1` (39) and
`verify-startup-binding-verified.ps1` (40). The first selects the five classes named above, limiting
Wi-Fi to retained-presentation recovery, initial binding retry and unfenced missing-segment rejection.
The second reruns only the repaired service fixture and retained Activity rollback/replay case,
with Dev packaging and Detekt. Their exact XML receipts are preserved under
`thirty-ninth-batch-tests/engine` and `fortieth-batch-tests/engine`.
`verify-notification-start-final.ps1` selects the full notification and presentation lifecycle
classes for batch 41; its receipts are under `forty-first-batch-tests/engine`.
`verify-start-lease-final.ps1` selects `DefaultTrackingStartRequestCoordinatorResumeTest` for batch
42; its receipts are under `forty-second-batch-tests/engine` and `forty-second-batch-detekt.xml`.
`verify-location-ready-final.ps1` selects Location adapter methods matching `*setup*`,
`actual completed clear reuses*` and `missing post-clear receipt*` for batch 43. Its receipts are
under `forty-third-batch-tests/engine` and `forty-third-batch-detekt.xml`.
`verify-pressure-status-final.ps1` selects `PressureOnlyTrackingContentTest` for batch 44; receipts
are under `forty-fourth-batch-tests/dashboard` and `forty-fourth-batch-detekt.xml`.
`verify-location-ambient-qos-final.ps1` selects the two new QoS cases, ambient writer, Off,
previously admitted all-day data and physical-prefix End cases (six total). The final receipts
are under `forty-sixth-batch-tests/engine` and `forty-sixth-batch-detekt.xml`.

| Script | Unit-test task and `--tests` selectors |
| --- | --- |
| `verify-live-readers.ps1` (13) | `:tracker:engine:testDebugUnitTest`: `*PressureSourceRuntimeTest`, `*SharedPressureSourceControllerTest`, `*AmbientSensorPurposeOwnerTest`, `*SourceRegistrationRepositoryTest.Location retirement*`, `*SourceRegistrationRepositoryTest.Pressure retirement*`, `*AuthoritativeSessionCoordinatorTest.Stop replays a completed receipt committed before its retirement timeout*`; `:stats:data:testDebugUnitTest`: `*PressureHistorySelectorTest`, `*ActivityHistoryComposerTest`. |
| `verify-retirement-repair.ps1` (14) | `:tracker:engine:testDebugUnitTest`: `*PressureSourceRuntimeTest`, `*SourceRegistrationRepositoryTest.*Pressure*`, `*SourceRegistrationRepositoryTest.*pressure*`, `*SourceRegistrationRepositoryTest.Location retirement*`. |
| `verify-shared-cancellation.ps1` (15) | `:tracker:engine:testDebugUnitTest`: `*SharedPressureSourceControllerTest`, `*AmbientSensorPurposeOwnerTest`. |
| `verify-native-gap-repair.ps1` (16) | `:tracker:engine:testDebugUnitTest`: `*RoomDurableSourceIngressTest`, `*AuthoritativeSessionCoordinatorTest.*terminal Activity*`, `*AuthoritativeSessionCoordinatorTest.settled unavailable source product completes End with partial status`; `:stats:data:testDebugUnitTest`: `*PressureHistorySelectorTest`, `*ActivityHistoryComposerTest`. |
| `verify-final-format.ps1` (17) | Packaging and quality only; the two brace additions preserve all passing behavior results. |

### Android observations

The AAC653 build's Start 209 reached live recording, foreground acceptance and saved segment 53.
Location and Wi-Fi were off; Activity, Pressure and Cell participated. The rendered display showed
the actual saved Activity **Still**, its **12:09 AM** measurement time and **1010.8 hPa** Pressure.
Activity delivery was delayed; its measurement time is preserved instead of substituting delivery.

End 210 was tapped at `1792970184564`; the saved cutoff is `1792970184881`. The actual quiescence
acknowledgement is `1792970203192`: **18.311 seconds** after cutoff. Finishing was displayed, Start
was disabled and the session reached FINALIZED / STOP_ACCEPTED / QUIESCED. The original recording
cutoff and actual acknowledgement remain separate. This improves on the preceding committed
walkthrough's approximately 324 seconds; it is not an instant shutdown or physical-device benchmark.

`acceptance42-ended` preserves one Activity observation and partial derived revision, 116 Pressure
windows / **5,366 samples**, and one Cell observation, with no new projection failure. Pressure
retirement retained **six real admission failures** and their sparse unresolved range; Activity and
Cell cannot prove a complete provider flush. The overall result is truthfully `STOP_PARTIAL`.
This run establishes retained useful data and completed shutdown, not lossless provider delivery.
There were zero session Location points and all **662 original Location rows** remained unchanged.
Raw UI, tap, snapshot, retirement and preservation receipts retain the `acceptance42-` prefix.

After End, the emulator was put on Home at `1792970421885`. The bounded
`acceptance42-independent-home` snapshot records **one sessionless Activity observation** at
`1792970531140` and **26 Pressure windows / 1,006 samples**, with zero measured, received or written
Location observations and no intersecting session run. Original accelerometer and Pressure inputs
were restored. This proves the observed independent background interval, not reboot/Doze survival.

Batch 41's cold Start 206 failed before source admission with
`PREPARED_START_LEASE_UNAVAILABLE`: the existing Location writer recovery briefly owned the shared
lifecycle lease. Its snapshot contains no session observations or segment. This is failed evidence,
not an accepted Start. The service mapping now defers only that transient busy result through the
existing claim loop and retry delay; it adds no new retry owner or timeout extension.

The AAC653 build's Location-enabled Start 208 also failed before provider admission with
`PREPARED_FOREGROUND_COMPLETION_TIMED_OUT`. No segment or session observations were admitted.
The subsequent read-only Location readiness repair removes confirmed no-op lease contention;
this failure does not establish that contention was the entire timeout cause.

Before the final End repairs, a settled Home-screen interval
`[1792931780000, 1792931992227)` demonstrated independent all-day collection: two native Activity
observations and 27 Pressure windows containing 1,082 samples, with zero Location observations and
no new session. The existing foreground host used `SPECIAL_USE`. This proves source independence
for that observed interval; it does not prove the final End behavior.

End latency is measured from the original `cutoff_at_ms` to the actual stored
`presentation_acknowledged_at_ms`. Backdated logical completion timestamps are not used as elapsed
shutdown evidence. The preceding committed walkthrough measured about 324 seconds. Intermediate
runs measured 38.776 seconds and 67.775 seconds; both exposed false retirement/recovery results and
are failed intermediate evidence. The later AAC653 acceptance above measured **18.311 seconds**;
the final Location-enabled run is still pending.

The F965 emulator pass exposed two further concrete gaps, now covered by focused repairs:

- A native all-day Activity callback arrived before the immutable session plan existed. Its raw
  observation was valid, but the WAL incorrectly combined a session tuple with no configuration.
  The new delivery keeps that observation all-day rather than assigning it to the session.
- Pressure and Activity history compared durable consent against the current boot's monotonic
  clock. Consent from boot 1 incorrectly hid valid observations on boot 14. Elapsed boundaries
  now apply only within the same boot; saved policy, epoch, grants and provider checks remain.

The unmodified F965 session `0d98b16d-f83e-4c1b-a518-b403f15d087d` reached ACTIVE and saved
Pressure. End preserved its original cutoff, `1792937643246`; actual Activity and Pressure retirement
acknowledgements arrived 45.103 and 65.312 seconds later. Finalization then repeatedly failed the
association audit on that terminally rejected pre-plan Activity row. This is a failed intermediate
run, not a latency acceptance result. The repair must preserve the raw data and report its real
gap without holding End indefinitely.

The first post-install Start (187) reached Android delivery but did not reach foreground acceptance.
It was superseded by diagnostic Start 188; debugger suspension invalidates 188 as timing evidence.
The cause of 187's wait has not been established. It is not silently counted as a passing Start.

The final source repair keeps the existing association audit. Its narrow Activity exception requires
one exact rejected row, a matching terminal failure for its manifest-bound active writer, and exact
run/provider/lease identity. Another unknown row still fails the audit. The request then follows the
existing retirement validation and writer/router, which reports partial data. Independent review
approved this slice and the reboot-consent/early-callback changes before the affected batch.

The AF7 session `213e48e9-33c9-4885-8422-fc3184e90957` displayed numeric Pressure and saved
590 samples, but stopped with `INTERNAL_FAILURE` after 92.682 seconds without an End tap.
The exact cause of that stop is not established. Source inspection separately found a lease renewal
gap before reconfiguration publication; the repair and regression cover that demonstrated gap
without claiming it caused this native stop.

That failed session also established a definite End blocker: its exact retired Pressure provider's
last admitted window was valid sessionless all-day data. The drain checked only a session-bound
endpoint and returned `SOURCE_DRAIN_SETTLEMENT_INCOMPLETE`. The endpoint repair retains exact
provider, clock, registration, sequence and original all-day authorization checks, plus the original
session retirement claim and cutoff. It does not relabel that window or erase the known lost callback.
An independently reviewed pre-cutoff regression also accepts a valid earlier ambient endpoint.

The C8 build's first post-update preparation failed before a new Start was accepted. Android recorded
`ForegroundServiceDidNotStartInTimeException` at 17:09:10 for the shared all-day host (PID 21637).
The saved stack identifies `enqueueBackgroundLocationHost` through `BackgroundLocationHost.acquire`.
The host awaited database-backed authority checks before its first `startForeground()` call. This is
a failed intermediate run; its previous-session recovery and preparation time are not accepted End
or Start measurements. The repair posts the existing shell notification before those checks while
leaving provider/lease acceptance behind the same full authority validation.

Independent review also corrected a pending-request cancellation race and preserved the live
session notification during host startup. A confirmed foreground promotion uses the existing
eight-second acknowledgement budget for later authority work; an unpromoted request retains its
three-second pre-promotion limit. The same two service test classes cover ordering, overlapping
pending requests, cancellation, and authority taking four seconds.

The ACF intermediate then exposed cancelled delivery before foreground promotion: Android allowed
the host start at `1792946372832`, brought the service down at `1792946373622`, and crashed PID 7463
at `1792946373972`. This happened during preparation's Turn off action, before any fresh session.
The platform explicitly rejects stopping an `fgRequired` service before notification promotion;
see [AOSP ActiveServices](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActiveServices.java).
The follow-up uses one providerless foreground acknowledgement at service entry, before rejecting
any queued delivery. Existing typed permission and source-authority checks still precede actual
collection. This also closes the equivalent cancelled manual Start race and removes a separate
background Intent hint. Failed ACF preparation is not native acceptance.

The D7 intermediate (`D7CC8A9FB42384F3390D2B04193C0A95A46D015FA9EA9277FC7DE003703E004E`)
accepted native Start 195 with Activity/Pressure selected and Location off. It reached the second
configuration, saved numeric Pressure and complete windows, and preserved their history. No Activity
callback reached WAL during this interval despite accepted continuous recognition registration.
End 196 saved cutoff `1792949973663`, but remained pending before diagnostic profiling began.
This remains failed shutdown evidence, not final acceptance.

During subsequent diagnostic sampling, Android ART's trace-writer thread crashed PID 2057 with
SIGSEGV. Its native stack is entirely inside `libart` trace preprocessing/writing; exit-info identifies
a native crash rather than an app Java foreground exception or OOM. No debugger attached. The
diagnostic trace has timestamp jumps and cannot support precise CPU percentages or latency claims.
Source inspection identified repeated full Steps qualification on unrelated sensor invalidations
and live history reads continuing through STOPPING; the next focused slice addresses those paths.

The 4B8 intermediate accepted Start 197 with Activity/Pressure and Location off. Native Activity
produced a saved observation; Pressure produced 47 complete windows (2,350 samples) and six partial
windows (153 samples). End 198 saved cutoff `1792953769932`; both Activity retirements acknowledged
within nine seconds and Pressure within 9.868 seconds. Presentation nevertheless remained pending.
The exact remaining blocker was the first Pressure carrier (14310): an old ambient provider 81,
configuration 13, had acquired the new session's manifest 1/acquisition 114 tuple. Actual capture
used provider 82. Its 25 samples remain independently saved in all-day history. The ownership audit
correctly rejected that extra capture association, while the history reader hid all valid later
session windows. This is failed intermediate End evidence; the following repair prevents the
membership leak and settles only this positively identified outside-capture class of carrier.

The 1FC intermediate (`1FC2919C4915636EA51648694E2E7FEF6791352658428E4F54E24774BB723C8A`)
recovered that old run through `SERVICE_ABSENT_EXPLICIT_REQUEST`, retaining its 2,503 Pressure
samples and Activity observation. This bypassed normal live retirement, so it does not prove the
retained-carrier router's Android path or End latency. The raw carrier, captured fact and separately
saved all-day samples remained unchanged. Its narrow settlement is covered by the passing Room tests.

Fresh Start 199 reached prepared delivery but timed out before typed foreground acceptance. It
saved no session observations. Its descriptor remained STARTING after the UI returned idle;
explicit Turn off 201 later finalized it through absent-service recovery. Code tracing found that
normal compensation incorrectly used the 250 ms callback cleanup reserve. The follow-up awaits
that compensation cancellably and retains Starting until settlement. The separate cause of the
30-second foreground-claim delay has not been established; this failed Start is not accepted evidence.

The 8F2 verification APK accepted fresh Start 202 with only Activity and Pressure participating.
Session `74369e78-38ff-4536-a4a7-64a3b2d05eca`, run
`0a168a6b-450c-4bf4-8e6f-8460384a4150`, remained ACTIVE through three configuration revisions.
The Dashboard displayed saved Pressure, including `1012.3 hPa`. End 203 preserved cutoff
`1792960317806`, finalized with `EXPLICIT_REQUEST` / `STOP_ACCEPTED` and acknowledged presentation
at `1792960359203`: **41.397 seconds**. The last Activity acknowledgement was 12.042 seconds after
cutoff; Pressure was 15.492 seconds. Every real app-side retirement reported zero failed admissions
and no unresolved range. Google's Activity completeness remains unobservable, not falsely Complete.

Finalization retained two partial Activity window revisions and 56 Pressure windows (2,608 samples;
49 complete and seven partial), with zero Location capture. Subsequent sessionless collection saved
one native Activity observation and 29 Pressure windows / 1,151 samples, including values changed to
`1010.75 hPa`; no new session or Location was created. Stable database snapshots passed
`quick_check`. This establishes the unchanged provider and End paths, not the later reader repairs.

That run exposed two remaining product restrictions addressed by the next reader slice: live Activity
classification waited for finite End windows, and one Pressure aggregate crossed the exact cutoff by
about one second, hiding valid sibling measurements. The latter aggregate cannot be split accurately;
only a fully validated exact-End boundary can be omitted with Partial coverage. Raw data stays saved.

With all-day Location still Off, My location then accepted six fresh emulator fixes and visibly
zoomed/centered its current-position marker. No Location point was persisted for the temporary
consumer. All 662 original Location rows remained present and byte-equivalent in the effective view.

Restoring adaptive all-day Location and returning to Android Home saved another 14 sessionless
Location points, all more than a minute after End, alongside six Activity observations and 159
Pressure windows / 5,931 samples. No replacement session or session Location capture was created;
the original 662 rows remained unchanged. The pressure sensor was restored to its original value.

The C827 reader build's Start 204 accepted Activity, Pressure and Wi-Fi while Cell remained
unavailable. It saved a native Activity callback and 55 Pressure windows / 2,599 samples, but never
bound a presentation segment before an unrequested `INTERNAL_FAILURE` stop. This is failed native
evidence. Source tracing found a concrete race: a second successful reconfiguration could replace
the stored descriptor between the first result and its separate lookup, turning valid advancement
into `SOURCE_CALLER_DESCRIPTOR_MISSING`. Session state also became visible to background observers
before presentation initialization. The saved snapshot is consistent with that race; it does not
record the precise service branch that requested the stop. The following repair serializes the
existing reconfiguration/publication step and binds presentation before publishing running state.

The F17 intermediate recovered that old run: segment 51, FINALIZED and QUIESCED, with one partial
Activity fact, all 55 Pressure windows / 2,599 samples and three Wi-Fi revisions. Raw observations
remained and both missing-segment failures cleared. Its recovered end metadata used cleanup time;
the subsequent endpoint repair uses the verified original End instead and never backdates a new
acknowledgement. Fresh Start 205 reached foreground acceptance and created segment 52, then failed
initialization: notification setup still required the UI session information now deliberately
published after binding. The factory already knows the accepted session type; the next repair
passes that Boolean directly. F17's failed fresh Start is not accepted native behavior.

## Final Location backlog repair

Native Start 211 reached Recording with all-day Location enabled. End saved cutoff
`1792971896971`; all three Location registrations retired with zero failed admissions, but
canonical processing remained at ordinal 15030 and End stayed incomplete. A bounded Room replay
of a private copy of the preserved database identified the exact exception: an independently
authorized all-day reading at 15044 had session Location off (`qos_code = 0`), and its metadata
construction called the capture-only QoS mapping. No saved reading or provider was erased.

The repair uses the existing non-capture saver fallback for that valid configuration. Other valid
quality levels retain their mapping; unknown values return a typed policy rejection. Capture
qualification is unchanged. Two focused Room regressions cover real canonical writing and cursor
advancement after capture is later enabled, unchanged original WAL, all-day-only ownership, and
unknown QoS rejection. The temporary copied-database diagnostic is excluded from source.

The final 4F7D Dev APK recovered the original backlog without deleting data: cursor 15030 advanced
to 16502 with zero pending Location rows and no new projection failures. It retained 18 capture
points for the interrupted run, whose original cutoff remains unchanged. The run used
`SERVICE_ABSENT_EXPLICIT_REQUEST` recovery; this is not a clean live End or latency result.
The first retry during catch-up (Start 213, run `728a80c0-358e-49a0-b143-7b885284a301`) timed out
before typed foreground acceptance and saved no capture data. Its failure remains recorded.
Subsequent sessionless raw delivery includes Location, Activity and Pressure. All 662 original
Location rows remain unchanged. A fresh retry after catch-up is recorded below after verification.

The next fresh Start 214 reached Recording and segment 55 with Location, Activity, Pressure,
Wi-Fi and Cell selected. The Dashboard showed a saved route. End 215 retained cutoff
`1792974743810`; every physical registration acknowledged retirement within 19.166 seconds.
It saved 33 curated Location points, two Activity revisions and 54 Pressure windows / 2,497
samples. Three real Pressure admission failures and one Wi-Fi failure remain recorded. No new
projection failure occurred, and no captured Location point is after the cutoff.

Presentation nevertheless stayed Pending after the source run finalized. The closed segment,
completed projection cursors, absent SessionEnded event and continuing policy heartbeat locate
the remaining wait before processor/policy shutdown. Source review found that four/five-second
aggregate teardown budgets can repeatedly cancel a slow valid close after its commit but before
the next step. The final repair removes those aggregate deadlines; it does not invent an
acknowledgement or remove the real inner provider/persistence limits. The same run exposed the
mixed-screen legacy Activity/Pressure display and still-active app-scoped Location summary;
both now reuse/cancel their existing qualified shared reader appropriately. This intermediate
run is not final End acceptance or a final latency measurement.

The behavior-equivalent 0669 APK's cold Start 216, attempted while the final host build was
running, reached a prepared run but timed out before foreground acceptance. It then compensated
normally to Failed/Terminal Failure with no segment or capture data. No expired activation grant
or unavailable writer was found. This failed attempt is kept separate; it does not establish a
cause in Android scheduling or prove that later native behavior passes. The final 50B1 package
is installed after host checks finish for a fresh warm test.

The 50B1 warm Start 217 reached foreground acceptance and saved Location, Activity and Pressure,
but remained before presentation binding. Native generation 115 correctly rejected a stale
registration acceptance after ambient authorization changed. Ambient generations 116/117 then
saved genuinely sessionless Pressure at policy 25. The next captured join (118, plan 139) returned
117's sessionless retirement as if it belonged to capture, producing
`STOP_ACK_MEMBERSHIP_MISMATCH`. The fix omits only an actually removed, complete/app-drained,
sessionless predecessor from a successful first claimed join. Coordinator membership checks stay
strict; no identity or metric is rewritten. Three shared-owner regressions cover this exact retry,
captured predecessors and incomplete/unknown retirement. Turn off tracking stopped this obstructed
collection after the run had already failed initialization and quiesced through recovery, with
segment 56 retaining its data. It is not final Start/End acceptance.

The EBCD Start 220 reached Recording and bound segment 57, with saved numeric Pressure and native
Activity. Its UI exposed an older compact-history shortcut: any Location selection skipped the
Activity/Pressure union and returned NotFound for both. The final guard keeps that shortcut only
when neither sensor was captured. This is independent of a second early Location race: point
16836 arrived before binding and was temporarily recorded as missing-segment failure. Its exact
current/unbound case now defers; original replay already handles saved failures after binding.
The successful mixed Start is retained as evidence, while its pre-fix missing sensor display is
not accepted as correct UI behavior. Final reader/native results are recorded separately below.

The EBCD session closed in the same process before the next install: cutoff 1792978994887,
presentation QUIESCED at 1792979718334, **723,447 ms** later. This is not acceptable latency.
All physical retirements were acknowledged; four Pressure and one Wi-Fi admission failures
remain visible as STOP_PARTIAL. No new Start or restart was needed to close it. Source review
found no remaining command/readiness lock in the physical Stop chain. It did find repeated
legacy scans during offline Location recovery: 153 missing rows were all ineligible protected
carriers. The final SQL change avoids hydrating these on every point; the evidence does not
attribute the whole delay to that query.

The 6D17 cold Start 222 spent several minutes preparing on the same emulator. It saved run
cc4980b4-d1cc-47d8-8876-799e85507301 at 1792980359104, with no segment or observations in the
preflight snapshot, and was cancelled through visible Cancel at 1792980582500. This is not
accepted Start/End evidence. Bounded logs retain a five-second UWB HAL crash loop and very slow
ART verification; neither establishes a Tracker source-code cause. The standard Java dump
was not readable under this Google Play image's permissions.

## Final startup command-lock repair

The C26D warm Start 224 also waited for minutes and was cancelled. Pressure continued saving
sessionless data after cancellation: ten windows/430 samples in `acceptance55-cancelled`.
The standard Android package-compile command returned Success but retained `status=verify` for
the Dev app; this is not optimized-build or cold-start performance evidence.

A temporary diagnostic APK (batch 56, packaging passed in 2m32s) identified the next wait.
Start's preflight began at 03:39:08.015 and returned Ready at 03:39:31.284; several minutes later,
the command store still had generation 225 and no new manual reservation. The initializer's
outer Ready operation held the command mutex across all source preparation and physical all-day
reconciliation. Internal Work boundaries could not release that outer mutex.

The final repair uses existing cancellable Ready-generation Work for preparation, including the
normal guarded public policy reconciler. Only final installation and force-stop suppression release
remain under the short exact Ready operation, with release last. A new latched regression reserves
Start and End while preparation waits, rejects stale publication after Stop, and permits the next
Ready handoff. Independent review approved this source slice. The three diagnostic-probe source
files were restored byte-for-byte; no timing logger or new runtime mechanism is delivered.

The initial observation after batch 57 did not prove a fresh Start: generation 226 belonged to the
preceding diagnostic process, and that claim was corrected. The pre-58 preferences subsequently
record accepted Start 227 and handled Stop 228, requested at `1792983830414`. This confirms eventual
command handling, not timed Start/End acceptance. Source tracing also found that normal policy
reconciliation held a Ready operation across retention and Steps callbacks. Its normal path now uses
existing cancellable Work, while final readiness-state publication uses the existing synchronous
Ready fence. This avoids reversing the maintenance caller's command/reconciliation mutex order.
Retention authority and source leases remain independently checked; the explicit post-deletion
operation contract is unchanged. Two new regressions reserve commands during each awaited phase
and reject stale completion after Stop. Independent review traced and approved those callees.

Batch 57 packaged the initializer repair and passed all 21 then-current initializer tests in
`host-startup-command.log`; its overall 7m04s result failed two LongMethod checks. Final quality
repairs extract the existing install body into a small named method and split the regression into
responsiveness and stale-release cases, retaining every assertion (22 cases). Two touched files'
mixed line endings are normalized to LF; `git diff --check` then passes.

Batch 58's fresh Start 229 reached foreground acceptance at `1792984650738` and bound segment 59
for logical session `940e3ca7-81d3-41cc-b8dc-1819f8eadd1f`, run
`09ea6a06-9825-4cfa-a790-edb833586f9f`. The Dashboard showed Recording, **Still** and **1013.3 hPa**.
It then entered Finishing without a user End: intent 3 records `INTERNAL_FAILURE` and cutoff
`1792984954362`, while the command store still has accepted Start 229 and latest Stop 228.
This is failed native acceptance, not an End latency measurement.

The consistent `acceptance58-ended` snapshot places the failure during manifest 2/plan 147's first
Location action: APPLYING, with all sibling actions still unattempted. No replacement Location
registration was created. The exact returned exception/rejection was not recorded; lease loss
must not be asserted as its proven cause. The snapshot retains 45 complete Pressure windows /
2,250 samples and one native Activity observation, with no new projection failure.

Location 17451 had valid-checksum observation and canonical pending signals, plus prepared curation,
but no destination receipt. Its final persistence transaction was still pending. The follow-up removes
the ordinary three-second aggregate transaction deadline so a finite valid write can finish on slow
storage; bounded database retries, authority checks, structured cancellation and unknown-commit
reconciliation remain. The four-second regression and full persistence class pass in batch 59.
This source risk does not prove that a timeout caused the native internal Stop.

Activity 178 had already retired with its exact capture barrier, while all-day successor 179 was
active, yet the End receipt remained requested. The follow-up reuses the existing exact orderly
retirement proof after an interrupted local handoff, preserving known losses and unknown provider
flush coverage. It does not restart or claim the all-day successor. Focused positive and negative
regressions and the full Activity runtime class pass in batch 59. A fresh native pass remains
**PENDING**.

After installing batch 59, the old Start 229 run finalized through
`PREVIOUS_PROCESS_INTERRUPTED` at `1792986347004`. This is recovery, not normal End acceptance.
Fresh Start 230 was tapped at `1792986585480`, prepared at `1792986744771`, and failed at
`1792986841604` with `PREPARED_FOREGROUND_COMPLETION_TIMED_OUT`. Logical session
`0a1309e3-fd73-41a1-87ad-35f59314a50c` / run `e78c0f4d-8063-418f-bc31-864c315e2f26`
has no segment or observations. Exact cleanup completed; the timed-out await is not identified.
A lease rebind during compensation does not establish earlier claim-stage progress.

The warm second tap at `1792986913385` produced Start 231: logical session
`57a04783-c8f0-4b10-a640-1b20f3718bab`, run `d3ed4a4c-16f2-4caa-8a35-c625fb432d2c`,
started at `1792987058811`, foreground-accepted at `1792987104036`, and bound to segment 60.
The consistent `acceptance59-before-repair` snapshot contains 64 Location, two Wi-Fi and one Cell
WAL observations for that run, but no captured Activity or Pressure observations. Activity 17676
remained all-day with its original plan 149 while the current manifest used plan 150, despite an
unchanged physical configuration. Pressure registration 135 failed with
`REGISTRATION_ACCEPTANCE_STALE`; its all-day successor 136 remained healthy. Claimed replacement
137 was cancelled before acceptance. The reviewed shared same-session handoff repair is frozen;
its affected host verification passes in batch 61. These older native observations do not establish
repaired capture.

The later manual End attempt found the tracking pill already disabled as Finishing. No End action
receipt exists, and no Stop 232 is claimed. This run therefore supplies neither a successful manual
End nor an End latency measurement.
The later consistent `acceptance59-late` snapshot records `INTERNAL_FAILURE` cutoff
`1792987750540` and finalization at `1792987923468`, with presentation still pending. After that
cutoff, independent all-day recording saved five Activity observations and 55 Pressure windows /
2,586 samples. This preserves useful background evidence without establishing captured A/P or a
successful manual End.

One bounded JDB inspection around 05:00, resumed and detached afterward, captured the live compact
Location summary running the full Cell history reader inside its Room transaction
(`acceptance59-thread-stacks.txt`). This is concrete unused work: the standard Location screen uses
existing radio counts, while the compact read only needs qualified Activity/Pressure products.
It does not establish that this query caused the entire Start timeout or internal stop.
The independently reviewed four-file repair omits those radio detail reads only for compact
Location presentation, retains Activity/Pressure and their shared qualification, and represents
unrequested radio products as null. Full and Location-free reads retain their existing paths.
Batch 60 compiled its Dev/Hilt/APK, SHA-256
`1BDF2DE7FF62A444888B813B1D4A92A08516BA8FC0DB02A74AC760E9CD6AA7FA`, but did not pass the
affected checks. The fixture and quality repairs are source-frozen, independently reviewed and pass
batch 61. Fresh native acceptance remains **PENDING**.

## Batch 61 native attempt and manual completion repair

Start 232 was tapped at `1792991258064`. Logical session
`2df2c477-79c3-4e73-afa9-cfa2a6cdac30`, run `9584ae1c-b2a5-4c83-a683-80232fd4dc88`,
earned foreground acceptance at `1792991402474`, but runtime acknowledgement stayed pending and
no presentation segment was created. Every session source action had zero attempts; the command
store's latest accepted Start remained 231. The UI returned to Idle while the run was unresolved.

Turn off tracking was tapped at `1792991754396`; Stop 234 was handled and the run finalized through
`SERVICE_ABSENT_EXPLICIT_REQUEST`. This is not normal session End or an End latency measurement.
The original 662 Location rows were preserved. Fourteen dual-purpose Location WAL rows came from
the independent ambient providers 155/156, both now retired; 156 retired after Off. The canonical
cursor was still before those rows, so absent destination receipts establish backlog, not a lost captured route
or an unretired session provider. The existing ambient fallback remains available when they qualify.

The reviewed manual-completion repair removes only the manual completion timer after Android
foreground promotion, including the wait for durable acceptance. It keeps the exact claim,
startup, origin and cancellation checks. Batch 62 packaged it and passed
its behavior checks; batch 63 passed the test-layout/quality recheck.

- [x] Manual-completion source repair, independent review and affected host checks.
- [ ] Fresh native acceptance and final commit.

## Batch 62 diagnostic run and batch 64 runtime preparation

The installed 62 APK (`07339886FB96A30D7EB94C098799670A8355F2A536DB294E108844C2267B82B3`)
accepted Start 235 and saved 12 Pressure windows / 600 samples. A bounded JDB inspection paused
the run for roughly 45 seconds, exceeding the 30-second lifecycle lease. Its initialization failure
is therefore timing-contaminated and is neither an unpaused regression result nor End acceptance.
No manual End was pressed. Off 237 fenced that attempt.

The PID 9190 ANR names ActivityRegistrationCleanupWorker, but its retained dispatch trace shows
27.436 seconds between scheduling and entering JobStart, followed by a nine-millisecond callback.
Main was in nativePollOnce at the dump. Earlier BindApp and Looper delays and high guest kernel/CPU
pressure do not identify an app cleanup method as the cause. Repeated emulator NFC/UWB failures
remain environmental evidence, not proof of an app defect.

Reboot stalled offline. After Off, the same existing Android 17 AVD was cleanly stopped and cold
started, preserving its data and configuration; boot completion and the 16 KB page size were
confirmed. No reset, new AVD or older API was used. The 64 APK was installed for the clean run below,
with no concurrent debugger or build during that measurement.

## Batch 64 clean failure and pending final repairs

Start 238 was tapped at `1792994556976`. Logical session
`fe8f32f7-3453-41c9-8fb8-753031e292ae`, run `ed827000-bca8-49de-a2fe-50644c77bb48`,
reached Active/Start accepted and bound segment 62. It entered Stop with `INTERNAL_FAILURE` at
`1792994664870`, 106.931 seconds after the run began. No End was tapped. All five selected sources
have complete physical-retirement receipts, but caller references for manifests 3, 4 and 5 remained
active. The saved descriptor names current reference 5 and recorded retirement debt 3. These are
concrete unresolved caller-publication/cleanup states, not a clean manual End.

`host-native-pressure-probe-exact.log` preserves one actual copied-database diagnostic, completed
in 3m01s. Of 15 native captured Pressure windows, eight authenticated and seven were rejected.
The proof compared the acknowledged capture start with a later sibling-purpose authorization
refresh. The temporary diagnostic test was removed after preserving its result.

The bounded follow-up repairs use the existing owners and immutable records:

- Pressure proof resolves the original authenticated capture authorization while retaining the
  later same-consent envelope. Real withdrawal, malformed lineage and session boundaries retain
  their existing handling; valid sibling windows remain useful.
- The service's existing reconfiguration mutex also covers tier read/CAS/publication, preventing
  a tier update from restoring a stale caller or plan identity.
- Caller retirement validates every additional active predecessor before retiring any: exact
  logical run, authentic older manifest, boot and data epoch. It preserves current, live Steps and
  unrelated authority. Stop retries with no caller reference use the authenticated original
  Active/Stop predecessor solely for teardown, without granting collection or reopening the run.
- Compatible captured Activity keeps its original persisted physical-plan binding across a global
  plan-revision change. Its existing compatibility proof must succeed before registration reuse;
  the two real Room regressions and full arbiter suite now pass.

- [x] Pressure proof and tier/caller-debt source repairs authored and independently reviewed.
- [x] Activity registration-reuse test source and final source freeze.
- [x] Affected host verification for these final repairs.
- [ ] Fresh native Start, useful saved values, manual End and independent all-day recording.
- [x] Local `dev/v10` source/host checkpoint in the enclosing commit; Android acceptance stays open.

## Latest host verification and outstanding Android acceptance — October 27

The final production fixes reuse existing owners, transactions and compatibility rules. No schema,
provider owner or polling framework was added. A fresh review-only agent independently rechecked
the Activity handoff, original Pressure authorization and authenticated predecessor retirement;
it found no concrete source blocker. This does not replace native verification.

The effective affected host set contains **190 unique passing cases**, with no failures, errors or
skips: 65 Activity arbiter cases, 120 Pressure history cases, one descriptor-publication race,
three real Room caller-retirement cases and one existing ambiguous-authority/storage regression.
`host-handoff-recovery-final.log` records the five engine and 120 Pressure passes. After correcting
an existing test-fixture race, `host-frozen-handoff-final.log` completes successfully in 3m21s:
Dev/Hilt/APK, all 65 arbiter cases and Detekt (3,304 repository files, no alternate checkouts,
zero findings). Its 689 tasks comprise eight executed and 681 up to date. XML receipts are kept
under ignored `final-host-tests/` with `final-detekt.xml`.

Earlier failures remain separate evidence: two malformed test assertions were corrected; private
validation helpers resolved complexity/return-count findings without weakening rules. An old
arbiter fixture cancelled its observer without awaiting completion, allowing a later case to see
an already-closed Room database. Cleanup now joins observers before closing or replacing them,
using the fixture's existing scheduler. One quality attempt stalled in a PSI tree walk; only this
task's Gradle daemon was stopped, caches were preserved, and the successful run used a fresh
single-use daemon with frozen source. These failed attempts are not counted as passing checks.

`host-native-pressure-probe-fixed.log` executes exactly one temporary diagnostic against a copy of
the actual emulator database in 4m32s. All 15 captured Pressure facts authenticate; the public
reader returns available partial/materializing data for that still-active snapshot instead of
integrity failure. `sixty-fifth-pressure-probe.xml` preserves this separate diagnostic. The temporary
test source was removed; original native data was not changed by the probe.

The installed APK retains its verified SHA-256 above. On October 27 the same existing Android 17
AVD was cold-started with its existing data; no reset or replacement AVD was used. The copied
`acceptance71-startup` database has `quick_check=ok`, schema 30, all **662 original Location rows
unchanged**, and unchanged choices for all six sources. Its old interrupted session is finalized
as `PREVIOUS_PROCESS_INTERRUPTED`, with presentation still pending; this is not a successful manual
End. No new Start/End was performed for final acceptance.

Tracker displayed its startup spinner before another active StarlitCoffee chat opened MindLayer
on the shared emulator. Final UI/timing checks require coordinated exclusive access; permission to
ask that chat to pause is pending. Brief startup-only debugger diagnostics were detached and their
port forwarding removed. They are not timing evidence. One Room worker was reading Steps history,
but source tracing found no direct startup dependency on that reader; the proposed query
optimization was deliberately left out because its causal role was unproven.

Remaining required Android checks: resolve/verify startup readiness; tap Start and observe useful
saved Activity/Pressure and route data; tap End and measure actual presentation acknowledgement;
then verify independently selected all-day sources after End. Physical-device battery and broader
release checks remain outside this targeted acceptance.

## Remaining verification limits

These targeted checks do not establish physical-device battery usage, Doze/reboot behavior,
six-source acceptance or release readiness. Google Play Activity delivery has no observable complete
flush; a truthful partial coverage label can remain even after clean app-side admission and shutdown.
The existing later Activity retry-spool expiry/corruption attribution limitation is not resolved by
this patch. Full `ciUnitTest` and `ciCheck` are not rerun for this targeted repair.
