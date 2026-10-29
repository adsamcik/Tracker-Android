# Core-flow completion

Baseline: local `dev/v10` at `48119b0143`. This is the current execution checklist;
older unchecked continuation notes remain historical. The October 16 assembly gate is closed.
Results below apply to their recorded source input and execution scope.

## Current status — October 29

Current input: Full41, 334 source paths, fingerprint
`259413663f44a9ef8d2c40c19a55be8084295ab283c4a64ba4b141086de06779`.
All six APK assemblies, unfiltered `ciUnitTest` and `ciCheck --continue` pass in 1h 12m 15s:
16,406 cases across 2,175 suites and 30 Test tasks, zero failures/errors and seven existing skips.
Hilt, Room/schema, release lint, Detekt and SQLite linkage checks pass. All 334 source hashes
remain unchanged. Exact inputs, commands, XML reports and the final result are preserved under
`build/core-flow-verification-20261027`, including `FINAL_RESULT41.json`.

The installed Advention Dev package is `com.adsamcik.tracker.dev`, version `10.0.0-dev`/400,
SHA-256 `80069cccdc2732a4275b80edf54d04b40bfaf990148d26f5ecd1dc5ba92c70de` before and
after its quiet check. On the existing stable API-37 AVD it records for 11m 23.945s through
normal Settings quality changes 3 → 2 → 3 and text resizing. The same session stays active,
shows saved route data and accepts explicit End. All Location/Wi-Fi/Cell retirements are Complete,
with zero failed admissions, unresolved ranges or new projection failures. Ten genuinely new
Location points then appear without a session: both measurement clocks are after the cutoff,
the boot matches, and session/run/segment references are empty. No new session is created.

Global Off is durably saved and handled at generation 304, and TrackerService closes normally.
Balanced quality, source choices and font scale 1.0 are restored. All 1,392 pre-test Location rows
remain unchanged in every saved column; the final 1,432 rows include the new test data. Schema 32 passes
`quick_check`. Normal and 1.3-font Recording screens have readable status, metrics and gutters.
Independent source, host, rendered and saved-runtime result reviews find no further concrete
code blocker for this bounded functional MVP. Delivery is recorded by the enclosing local
`dev/v10` commit; no remote publication is part of this work.

**Material latency limit:** End-to-presentation takes 42.919s in this run. Cell/Wi-Fi retire at
11.416/12.451s and the two Location generations at 15.603/22.463s after cutoff. Required saved
Location settlement continues until 38.299s; finalization/quiescence follows 4.620s later. Source
review finds no unrelated global projection wait. This is a functional End pass, not a latency
improvement or a controlled performance comparison. The cycle includes font restoration, UI
sampling and database snapshots; its latency cause has not been isolated. Physical-device,
OEM background and battery acceptance remain explicitly user-deferred. The earlier Dev39
unrequested stop remains historical with its exact cause unproven; later success does not prove it.

### Prior follow-up inputs

Full40's unchanged complete six-APK, unfiltered `ciUnitTest` and `ciCheck --continue` batch
passes in 1h 21m 2s: 16,406 cases, 2,175 suites, 30 Test tasks, zero failures/errors and seven
existing skips. All 334 source hashes remain unchanged. Its reports and independent audit are
preserved; the new queued-renewal regression passes.
Full39's six-APK, unfiltered `ciUnitTest` and `ciCheck --continue` batch passes in 1h 42m 3s:
16,405 cases across 30 Test tasks and 2,175 XML suites, zero failures/errors and seven existing
skips. All 334 source hashes remain unchanged; independent source and host-result reviews agree.
The complete reports are archived. Its normal and 1.3-font recording header and metric layout
are readable, but the first final runtime session unexpectedly ends after 74.287 s with
INTERNAL_FAILURE during reconfiguration. That is a failed runtime acceptance, not a passing End.
An exact-method/exception diagnostic observer subsequently records no failures in its bounded
run; that session lasts over three minutes and accepts an explicit End, with Partial coverage.
All original 1,374 Location rows remain unchanged and global Off settles normally.

Independent source inspection identifies a concrete queued-renewal race: clock sampling before
Room writer admission can shorten a fresher lease. Full40 samples and renews inside the existing
Room transaction, preserving all authority predicates and the 30 s lease/10 s renewal. One real
Room regression holds the writer and verifies the current clock is used after admission.
The two-file source review and Full40 execution are clear; Full41 supplies final runtime acceptance above.
This fixes a concrete race but does not establish the cause of the preceding unexpected stop.

The cancellation review finds a second concrete cleanup defect: an actually cancelled job skips
the cancellable Room lease release. Full41 shields only that existing exact release transaction
with `NonCancellable`, retaining all owner/boot/generation checks, provider claims and error handling.
The existing cancellation case now actually cancels its job, verifies current-clock release and
keeps all newest-claim, physical-owner, predecessor and different-owner Stop assertions. It adds
no test case, timer, interface or schema. Independent source review is clear. The focused coordinator
test/Dev/Detekt preflight passes in 9m 31s: all 277 cases, zero failures/errors/skips and zero
quality findings. Full41's unfiltered unit aggregate also passes: 16,406 cases across 2,175
suites and 30 Test tasks, zero failures/errors and seven existing skips. All six APK assemblies
and release quality checks pass. Ordinary active End uses
the same owner and can reacquire its lease, so this defect does not explain the earlier End latency.

Earlier Full36 input: 318 source paths, fingerprint
`fe3370aa7ff8ca35d4efeb6c23cebb28e3afc77d455fdb517732cbdd90aeb29e`.
Its unfiltered six-APK, `ciUnitTest` and `ciCheck --continue` batch completed in 1h 57m 53s:
16,398 cases across 30 Test tasks, one stale onboarding-text assertion failure, zero errors and
seven existing skips. Every source hash remained unchanged. The full tracking engine passes
4,744 cases; quality, release lint, schema and SQLite checks pass. The complete reports are archived.

The stale Wi-Fi permission assertion is updated without dropping its permission or source-independence
explanation. Rendered review's wrapping Recording label and touching metric values have small layout
repairs applied, retaining accessible status and all values. Dashboard cards share stable local
recording timestamps instead of ages that can become stale while idle. Equivalent recorded zone
names now share one numeric Steps total only when their UTC day boundaries match exactly, after
qualifying every original partition. Existing domain/overlap/completeness checks still apply.
Equivalent-zone provider reads continue the same progressive fact; a new window adopts its current
label without skipping counts or creating overlaps. Original IDs and export values remain intact;
there is no new storage, schema or public product interface. Seven real-Room regressions pass.
The four initial style findings are repaired without new suppressions or changed thresholds.
Final Full41 results and acceptance are recorded above; the enclosing local commit records delivery.

The source-reviewed follow-up retains Start in the existing ViewModels and fences pending Pressure
samples before Stop's database waits. It adds explicit sessionless Resume through the existing
command authority and application-owned purpose observer. Editing choices still preserves Off;
newer Stop/Start intent and failed saves remain fenced. Accepted Resume survives leaving Settings.
No new collector, provider, service, persistence or setup layer was added.

Fresh checks on the existing stable API-37 AVD establish:

- Dev41 remains active through quality changes and larger text, accepts explicit End and saves ten
  genuinely post-End sessionless Location points. Final Off, exact original-row preservation and
  matching installed APK hashes pass. The 42.919s End latency remains a material limit, as above.
- Dev34 Start survives navigation after 210 ms, before logical creation/foreground acceptance.
  Fresh Activity and Pressure appear, intentional End is acknowledged 5.473 s after its cutoff,
  both sources continue all day, and exact pending Pressure prefix/tail samples are preserved.
- My location shows a fresh position and centers a different viewport at street zoom. Both
  temporary checks preserve all 1,320 tracked Location rows byte-for-byte while other sources run.
- Dev36 Resume followed by leaving the Tracking settings page after 179 ms produces six ambient
  Location points with zero new sessions. Its 60 s batching explains the earlier empty 32 s check.
- Manual Location/Wi-Fi/Cell sessions save all three kinds. A route emitted only after capture
  readiness shows 113 m and an 11 km/h average with honest Partial coverage. End acknowledges in
  9.406 s after cutoff; every source retirement is COMPLETE, with zero failed admissions or new
  projection failures. The earlier same-input End has ten genuinely new post-End ambient points.
- Final Off is durably saved (handled Stop 295); normal cleanup removes TrackerService. Saved
  source choices remain restored and all original Location rows are unchanged.
- Reopening that same closed route while Off shows 155.97 m Partial, 6 m 45 s, overall average
  1.4 km/h and maximum 15.7 km/h. Independent reconstruction from its final 21 saved points gives
  155.9678329 m and agrees exactly. All 1,374 points remain unchanged; no provider or service starts.
  Closed average uses full session duration; the earlier live value uses moving-pair duration.

These are bounded emulator observations, not controlled performance comparisons or physical-device
and battery certification. The earlier unexplained Dev31 automatic stop is not retroactively
explained by later successful runs. Its invalid constructor observer is excluded from acceptance.
Already-settled historical crossing Pressure aggregates retain Partial coverage because their raw
samples cannot be reconstructed honestly.

Completed checks on their recorded unchanged inputs:
- Full26: 90 affected Location/Pressure host tests, zero failures/errors/skips; Detekt passed.
- Native SQLite contracts, released-main copying and full cold Application startup from released
  Location-only data pass. Six shared History cases, actual periodic retention, live backup snapshots
  and the corrected deletion/re-arm retry all pass. The latter preserves every original assertion.
- Dev25 Location-only: saved route and live distance/average, manual End acknowledged in 6.647 s,
  then four measured all-day points saved in the same process. Processing was delayed by database
  work; this is eventual continuation evidence, not prompt-delivery acceptance.
- Dev23 Pressure-only: manual End acknowledged in 5.629 s and all-day Pressure continued. A separate
  failed reconfiguration exposed lost predecessor acknowledgement; its repair passes Full26's
  focused regression. A crossing shared Pressure window still lost its session prefix. Full28
  splits retained original samples at the atomic End boundary; native acceptance is pending.
  Dev27 subsequently stopped a Pressure session automatically with INTERNAL_FAILURE after 30.827 s,
  with 41 attributed failed samples. This was not manual End; the invalid copied Start action
  was explicitly renamed and annotated. A later diagnostic captured a Room mutation timeout.
  Dev27 lacked the completed full-union History cutoff. Quiet Dev28 subsequently retained the final
  pre-End Pressure samples and acknowledged manual End in 5.976 s. It remained STOP_PARTIAL because
  two earlier refresh buffers exhausted capacity. Its all-day lease had already retired before End,
  so that run does not establish shared-window splitting or post-End Pressure continuation.
- The real Location-file transfer preserves all 401 points, values, three paths and 150 sequence
  gaps byte-for-byte, including Clear/import in the same process and repeat-import deduplication.
  Same-process data-only restore, session bundles, corrections, Calendar and group-only removal
  have scoped native evidence. Dev originals remain preserved.

Full28 also bounds Dashboard's expensive source qualification to the complete requested union page, while retaining
older identity-conflict checks. Native stacks showed the previous eager 100-candidate read occupying
Room's transaction queue. Its focused regressions now pass, including a full page of newer source/imported rows requiring
zero older physical-source qualifications.
Zero-count session headers do not establish empty data: the native profile contains retained
Location, Activity and Pressure under those headers. Full29 reuses the qualified saved Location
reader before unrelated-source checks, and removes the header-only exclusion from recent distance.
It also budgets refresh samples by their actual Pressure windows, retaining the existing raw and
window limits, and preserves retry debt after unavailable retention authority is safely retired.
Full30 distinguishes unavailable policy (retry) from explicitly revoked authority (settled). The
existing Steps retirement-only entry point now performs serialized cleanup without re-reading
approval or reactivating; its caller retains the separate policy debt. Failed physical cleanup
still retries. This preserves the original contract rather than changing its failing assertion.
The breakpoint-only trace confirms retention-failure cleanup; it does not identify the exact
upstream rejection. No observer-assisted timing is treated as ordinary runtime acceptance.
The foreground-accepted timestamp is written after preparation and claim, not initial Android
foreground promotion. A 37-second recorded interval crossed the existing lease and rebound; no
unconditional timer delay was found or changed.

An earlier unfiltered run, Full16, completed in 2h 55m with 16,332 host cases, four stale-fixture
failures, zero errors and seven existing skips across 30 Test tasks. Those fixture repairs are
included and Full39/40/41 now pass. Full23–25 preflights
built APKs but stopped before affected tests on helper compilation errors, corrected by Full26.
All previous frozen inputs and reports remain preserved. Physical-device/OEM/battery acceptance
remains explicitly user-deferred; emulator and host evidence cannot establish it.

## Implementation and reconciliation

- [x] Audit current tracking, product and data paths against the approved behavior.
- [x] Author retryable module initialization: retain completed modules, retry failures, and make
  concurrent startup wait for completion. Add focused failure/concurrency regressions.
- [x] Author restore completion recovery: retain its completed journal until reader admission and
  writer release succeed. Add cancellation and release-failure Room regressions.
- [x] Reconcile the four preserved architecture commits against the newer shutdown implementation:
  radio callback ownership, bounded delivery, retention, replay and schema-31 index migration.
- [x] Author routed Dashboard/recent-history and Calendar fixes using shared qualified readers;
  correct local-day streak calculations and preserve useful source-only/partial data.
- [x] Author repairs for stale Android integration fixtures without weakening their assertions.
- [x] Apply and independently review the final repairs, including mandatory-host retirement,
  Clear-all/import retention and the fourteen Full17 slices. Affected execution remains below.
- [x] Independently review the assembled source and resolve concrete findings, including Activity
  retry-loss attribution and receipt-durability failure paths. Execution is recorded below.

## Validation and delivery

- [x] Freeze the repaired input before its checks. Full41 is frozen; earlier inputs remain byte-exact;
  schema 32 is generated by Room and schema 31 remains historical.
- [x] Build the final Dev and instrumentation APKs, including Hilt, Room and the checked-in schema.
- [x] Verify released-main through full production startup, plus final affected native SQLite
  contracts. Earlier data-copy, development-upgrade, backup-normalization and unknown-database
  preservation checks pass within their recorded scope. No destructive database fallback.
- [x] Pass repository `ciUnitTest` and `ciCheck --continue`; repair failures and rerun affected gates.
- [x] Finish latest-Dev Android acceptance on the existing stable API-37 AVD, reusing the scoped
  unchanged native evidence below. Do not create/clone an AVD or use an older API.
- [x] Trace the installed Dev build from provider callback through saved data and visible output.
- [x] Review actual results independently and record any capability or device-evidence limits.
- [x] Commit all completed task work into local `dev/v10`; the enclosing commit records delivery.

## Core acceptance coverage

Use existing tests and real providers where available. Destructive restore/erase scenarios must use
isolated test databases or the test application's data, preserving the Dev application's history.
Generated provider inputs, unavailable hardware and physical-device evidence are distinguished.

| Flow | Required outcome |
| --- | --- |
| Startup and onboarding | Opens reliably; six source choices survive denied permission and retry; setup failures recover. |
| Settings and permissions | Participation and quality remain separate; repairs work; unavailable sources do not block healthy siblings. |
| Manual Start / End / Off | One shared flow; no silent Start; acknowledged End; Off fences every collection path without deleting history. |
| Independent and all-day collection | Each supported source works without Location or a session; End preserves independently selected all-day sources. |
| Automatic sessions | Trigger authority remains separate from capture; same collection pipeline; unsupported capabilities have an honest repair/state. |
| Location and temporary consumers | My location gets a fresh point and centers the map; shared temporary requests do not create tracking history by themselves. |
| History and Calendar | Measured local dates, paging, useful partial data, source-only entries, details and retry; live writes do not erase results. |
| Statistics and notifications | Shared saved-data totals; daily Steps independent of sessions; unknown values are not fabricated zeros. |
| Session organization | Retrospective/overlapping annotations, editable closed intervals and group-only removal preserve observations. |
| Corrections and deletion | Keep/Replace imports affect history, summaries, maps and exports; deletions retract derived effects and remain replay-safe. |
| Source and geographic files | Exactly the selected format; selected dates/optional complete sessions; original timestamps, gaps and overlap deduplication. |
| Session bundles | Selected sources and annotations round-trip through existing receipts, including retry, partial failure and data-epoch changes. |
| Data backup / restore | All durable data, no settings; logical reimport without restart; cancellation and same-process completion recovery. |
| Privacy cleanup | CONTROL expiry, retention, export cancellation/private-file cleanup and external-file notices preserve independent purposes. |
| Games and rewards | Shared sensor requests, truthful source availability, finalization and correction/deletion retraction. |

## Preserved candidate dispositions

Branch `codex/tracking-architecture-fixes-20261025` remains preserved. Its source-only commits
`4070cc21cd`, `3c9d8510f9`, `a33b6f57d3` and `278a16c14b` are reviewed against current code;
they are not applied wholesale. Newer Activity/Pressure and caller-retirement fixes take precedence.
Dormant legacy `TrackerRoute` has no app-navigation callsite; this work does not create a duplicate
screen to make those candidate UI hunks reachable. The actual Dashboard flow is the product target.

## Earlier convergence history

At baseline, 190 focused host passes and an installed Dev APK did not yet close manual End and
post-End all-day acceptance. The dated history below preserves those earlier failures and repairs;
the current status and checklist above take precedence. Receipts apply only to their unchanged
inputs and recorded scope. Shared-emulator windows were coordinated before native acceptance.

The frozen combined batch has generated schema 31 and passed all 2,129 core/database tests
(299 suites; zero failures, errors or skips), including the new replay-index upgrade. Schema 31
preserves the 215 tables and existing views, adding only the source/ordinal replay index.
Import/export also completed: 852 passing tests, one host-specific skip and no failures.
The first complete batch ended with six failed tasks: the History return-type inference error
in Debug and Release, two Activity fixture failures, a stale foreground-service description
assertion, uncommitted generated schema 31, and 59 quality findings. Source repairs preserve
the checked behavior and simplify the flagged functions without weakening rules or assertions.
These partial results do not establish a passing full gate or native acceptance.

The reviewed storage unit is committed locally at `7ad1f6e18e587900506c6a074e027499f85e0db3`.
Quality repairs add no suppressions or weakened rules. Full02 was stopped after a Calendar
cross-module nullable-property compile error; caching that value locally preserves the same
checks. Full03 completed on 83 frozen source/schema/test files. Dev, Debug, app-test and
database-test APKs built; the full gate still failed: three released-import schema assertions,
one obsolete all-or-nothing Steps page assertion, 19 engine failures and four quality findings.
Exact inputs, commands, logs and original XML reports are preserved in the ignored verification
directory. Repairs are in progress; no full-gate pass is claimed.

## Native findings from Full03

The existing `Android_17_Google_Play` target reports stable API 37, x86_64, 16 KB pages,
1080 x 2424 at 420 dpi. Initial checks used its then-configured 12 GB RAM; the other chat
subsequently restarted the same preserved AVD at 4 GB and released exclusive access to Tracker.
The emulator was not created, cloned or reset by this run. Its generated Location inputs do
not establish physical sensor, vendor background, automatic-classification or battery behavior.

- [x] Data-preserving installation of Full03 Advention Dev, package `com.adsamcik.tracker.dev`.
- [x] Real schema-30 to schema-31 opening passes `quick_check`; all 1,078 original effective
  Location points retain their saved values (zero missing or changed).
- [x] After explicit Off, My location obtains a fresh generated Android fix, shows the position
  and zooms/centers the map. It saves no new tracking Location point or durable Location event.
- [x] Manual Start is accepted by Android and reaches Recording; real provider delivery produces
  saved route points and visible distance, Activity and Pressure.
- [ ] Manual End: the retained-data profile remains STOPPING. Every physical owner retired, but
  Activity's terminal drain asks for a boundary beyond its target and an old rejected Wi-Fi
  record pins later projection. The independent review requires preserving recoverable pending
  start acknowledgements while settling definitively rejected historical records.
- [ ] Post-End all-day Activity/Pressure: no facts arrive while End remains unfinished. Fix and
  rerun the two proven End blockers before changing otherwise shared ownership logic.
- [ ] Native database tests: two of four passed. A migration fixture omits required custom-v28
  schema. Exact source-line comparison shows the strict SQLiteX corruption test already blocked
  the open correctly, but then found a missing file-family member. A preservation-only 16-byte
  header check now rejects malformed files before native opening can clean recovery sidecars;
  new/empty and valid-reopen cases are authored, with all original hash assertions retained.
- [ ] Native app tests: one of five passed. Fix a test-only Hilt entry point incorrectly requested
  from the production component, and wait for real startup navigation before UI interaction.

Receipts, screenshots and stable database snapshots are under the ignored
`build/core-flow-verification-20261027` directory. An APK build, written test or generated input
is not a passing native acceptance result. Existing Dev history is retained; destructive tests
use the newly installed disposable Debug profile.

Full04 was frozen at 98 source/schema/test files, fingerprint
`b1be732aea0ed7458c44f6af56b073e549c60dbf58c9dd7848584dd17a257ac3`. Its Dev APK built;
the coordinator then stopped only its authenticated daemon before host tests completed, after
review confirmed another necessary history fix. This intentionally superseded batch is recorded
in `full04-aborted.json`; its daemon-disappearance ending is not an unexplained source failure
or a passing full gate. Independent source review cleared the End, publication-race, v6 per-reading
scope, fault-fixture and SQLite header repairs. Review found and corrected recoverable-ack,
unacknowledged-terminal-owner, delivery-header/original-plan and post-freeze fault-boundary
mistakes before this input. No rules, suppression baselines or preservation assertions were waived.

The remaining reader repair preserves independently valid Wi-Fi/Cell readings when a different
input from the same recording is rejected. Own-carrier corruption and identity ambiguity still
block that reading; unrelated rejected input produces honest Partial coverage. No interface or
schema is added. This draft is reviewed before the next combined input and complete gate run.

An actual empty-profile Statistics visit displays settled missing-data values with no Updating
message (`debug03-empty-statistics.png`). Native fixture changes wait for real navigation readiness
and expose test access only in the Debug component. Full-gate passing results remain pending.

## Source05 input and later native diagnostics

- Full05 freezes 109 source/schema/test files at fingerprint
  `16141ac7c8a44268e9f8abec307cf0624c301fe85aedaccdb8dc9ef9e32eb80a` on local `dev/v10`.
  It runs the same complete packaging, host-test and quality gates without filters or waivers.
- Full04 Dev APK `B5C422E3B8DC8C7FCF6566354204A195CFF2127D400868B21F1FBA0EA5CC69C4`
  built before that batch was superseded. Reopening recovered the earlier pending run through
  the service-absent terminal path. That is recovery evidence, not a successful normal End.
- A fresh Start reached Recording and saved Location/Pressure, but normal End remained STOPPING.
  A valid retired shared Activity generation had completeness without an adopted runtime claim.
  Source05 authenticates that additional membership from its original grant, retired registry,
  seal, real admission boundary and retained run lease. It invents neither a Start nor retirement.
  Scoped callback loss still produces Partial/Unavailable coverage. Four Room regressions are
  authored, including a canonical-but-foreign lease rejection.
- The same snapshot contains Pressure windows admitted after the durable End request but before
  Room recorded its cutoff. Source05 applies the existing lock-free command authority's cutoff
  to the existing Stopping admission rules sooner. Four Room regressions preserve independent
  all-day windows, exact bounds, newer Starts and different boots. Display/window policy is unchanged.
- The all-day diagnostic query now follows saved all-day identities rather than requiring a
  shared raw carrier to have empty session header fields. One independently saved Pressure
  window survives End in this failed run; ongoing post-End collection is still unverified.
- Active candidate `466f29f12d` in `.worktrees/close` was compared without changing it: 65 of
  80 paths match, and newer root fixes supersede most differences. Its unique provider/grant
  validation ordering and two regressions are retained. The complete candidate is not applied
  wholesale; its ref, active worktree and evidence remain preserved.
- Another Tracker chat also began native checks and replaced the installed Dev binary. Final
  acceptance must pin the installed APK before/after and use a single coordinated owner. Later
  shared-screen observations are diagnostics, not passing source05 acceptance. Messaging that
  chat awaits the requested user authorization; host verification continues independently.

## Source05 results and repair input

The full batch finished with four failed tasks: one file-backed Windows SQLite fixture, one
pending Wi-Fi authority regression, eight heterogeneous SQL-binding test compilation errors,
and five quality findings. Original reports are preserved before the next batch. App host tests
executed 1,033 cases with no failures and two skips; engine tests did not compile in this input.
The pending Wi-Fi failure is a real reader defect: with no saved facts, incompatible pending
input must be failed rather than indefinitely materializing. The fix retains independently valid
saved siblings, restores pending-input validation, and tests terminal no-fact failures for both radios.
Quality repairs preserve narrow decoding exceptions and move existing cohesive publication helpers;
they add no suppressions. The shorter fixture database name avoids long Windows journal paths;
the original exception did not record the exact failing path, so that diagnosis remains provisional.

On the same existing API-37 AVD, all seven native migration/containment/schema checks pass.
Four of five app smoke checks pass. The remaining test searched for the obsolete hidden Stop
description; its repair waits for and clicks the actual enabled End session control. Fresh-profile
History fixtures also need existing retention preparation before requiring its lazily initialized
evidence state. The deletion test must assert the public typed Retryable result while retaining
all real fence, clear, epoch, re-arm and retry assertions; that path still needs a passing rerun.

The installed Source05 Dev APK is pinned before and after the native journey at
`2fb92a747bd0bcdf70c6047a760c862b7a0ef93ac0bb84ad8e8a3feebcdcebf0`.
Manual Start records a saved route; normal End reaches FINALIZED, STOP_ACCEPTED and QUIESCED.
Independent all-day Activity and Pressure then save one Activity observation and 22 Pressure
windows containing 938 samples. History displays those saved values and expands their details.
No new projection failure is recorded. End presentation takes 62.503 seconds: physical retirement
completes after 8.710 seconds, but 60 queued canonical Location writes finish only after 60.390
seconds. This is finite saved-data completion, not acceptable low-latency evidence. Repeat one
bounded run after host gates are idle before attributing the remaining latency to production logic.
STOP_PARTIAL correctly preserves earlier scoped callback gaps; it is not a claim of complete coverage.

The apparent 0 m distance / 141.5 m/h average in an earlier live frame is consistent rounding:
the same saved snapshot contains 0.434 m over 11.045 seconds. No separate metric fix is warranted.
Independent comparison retains all 1,100 baseline physical Location rows exactly through this run
(zero missing or changed). Both Tracker chats now coordinate: this checkout owns the final gate,
AVD and integration; the other chat preserves its worktrees and supplies read-only review.

## Source06 native continuation

Full06 freezes 111 source/schema/test paths at
`1c47f793a11d6506db36a4089e176f914622630b03c51af73724eb3bb19ab681`.
Core/database 2,129 cases now pass, including real file-backed repeated-clear/reopen. App host
tests again have no failures (1,033 cases, two skips). The Android smoke fixture has one unresolved
top-level import for a Compose member function; removing that import is prepared for the next
input. The new Wi-Fi terminal-empty fixture used its pre-insert ordinal zero instead of Room's
assigned ordinal, and the Cell fixture incorrectly expected a failed detail selection to be absent.
Repairs retain failed/pending source isolation and add actual persisted-ordinal/detail round trips.
The full batch is still collecting engine and quality results.

Fresh disposable Debug testing records Activity and Pressure with Location off and unsupported
Steps still selected. It saves no Location point; normal End is FINALIZED/STOP_ACCEPTED/QUIESCED
after 6.430 seconds with no failure code. Subsequent data includes sessionless Activity and Pressure.
The first setup attempt used Android's Clear data after earlier instrumentation, which preserves
the OS's historical user-stop record. The resulting command-one collection fence explains why
all-day capture waited for accepted Start command two. This does not justify weakening Stop or
changing Steps ownership. A clean disposable-package reinstall removes the old Android exit record
for the before-any-session check. All failure snapshots remain preserved; Dev data is untouched.

That zero-Location session exposes a real Dashboard defect: recent candidates come only from
sampled Location trips or exact single-source captures, so mixed Activity/Pressure is omitted.
The reviewed ignored repair discovers a bounded manifest/run-bound candidate set and reuses the
existing source-qualified session reader and physical detail route. It requires saved qualified
data, preserves healthy siblings, and leaves distance unknown. It adds no API, schema or fake route.
Application and native verification remain pending until the current batch finishes.

An actual Android folder-picker export completed as one `.trackerlocation` file: 1,173 unique
saved point IDs, original metadata and an exact end count. Import/overlap/correction and complete
logical backup acceptance remain pending in the disposable profile. A small reviewed follow-up
also corrects misleading import/incremental descriptions in existing locales and removes only the
duplicate format-selection warning; the actual Export/Share confirmation and picker stay in place.

The clean Debug reinstall now proves before-any-session collection: zero sessions and Location
points, one Activity observation and 17 Pressure windows / 706 samples. Unsupported Steps does
not suppress those sources. A real `.trackerpressure` export contains 231 sessionless observations.
Turn off tracking retires both sources; their 14 Activity observations and 308 Pressure windows /
12,713 samples remain exactly unchanged through the subsequent temporary-map check. A real
Location-file import adds 1,173 points; repeating it leaves exactly 1,173 points and no sessions.

Two additional concrete display/file gaps have reviewed repairs held outside the frozen source:
all-day data alone can still show Dashboard's first-use empty state, and exact Location files omit
known route continuity. The latter can reconnect a short recorded gap on re-import. The proposed
bounded repair adds optional descriptive path/sequence fields to the file and existing identity
table. Installed schema 31 is preserved through an additive 31-to-32 migration; Room must generate
the actual schema and upgrade/round-trip checks must pass before completion. Native My location
currently gets an active high-accuracy request but one clean-profile run leaves the camera at
world zoom; camera/lifecycle tracing is in progress. These are not marked complete.

Full06 finished after 2h 41m 58s. Its reports contain 15,199 host cases, 15 failures, zero errors
and six skips. Only three tasks failed: Android smoke-source compilation, two new radio fixtures
and thirteen engine fixture cases. Android lint, Detekt (3,314 source files; zero alternate
checkouts), release-evidence checks and SQLite runtime linkage passed. The repairs preserve the
asserted behavior: actual persisted admission IDs, failed-detail round trips, synchronous crash
injection, thread-safe parallel-drain recording and a warmed Room contention test that still
requires retention to complete while caller sampling remains suspended.

The next reviewed input is applied and frozen as Full07: 229 source/schema/test paths, fingerprint
`fbcc8b9d99c45be9b3e6574cd7db3c9a19ba4528977cf7f936bca4cf084915c2`.
Its packaging pass will generate schema 32 before the final full gate. The camera fix replaces
cancel-and-join ordering around MapLibre's non-cancellable native animation bridge with immediate
replacement and guards against late completion; two focused callback-ordering regressions are
authored. It has independent source review, with native reproduction still pending.

Room generated schema 32 with SHA-256
`d1095eab6dc123af48f9784ffe6a74737a18a3838f3ae713ee069716834c8c3d`.
It retains 215 tables and identical views, changing only the two nullable identity fields; the
checked-in schema 31 input is unchanged. Packaging07 ended with the missing Dashboard `Flow`
import as its sole error. That import is repaired. Focused08 freezes all 230 paths, including the
generated schema, at `d818b92a87f3be57daf34283c7b78f35b5334b5ada6f177172cedb4f22b9711a`.
Its recorded script explicitly selects the affected failure/regression classes; it is not the
full repository gate and cannot be reported as one.

The exact released baseline is local `main` at `59fda665c7099d10b16aa60f505de88bd05d3131`,
database version 12, identity `603376ce119f1b4f1d3e4866aec15b4e`. The preserved schema-12 fixture
matches it structurally. Existing host tests seed all seven released tables and exercise their
logical import into the current database, original values and idempotence. One focused native
released-v12 import check is being authored because the previous native 27/28 tests and planned
Dev 31 upgrade establish different inputs.

Focused08 was deliberately stopped without a test-result claim: its CLI class filters still
launched separate JVMs for excluded classes under the repository's `forkEvery=1` setup (executor
78 was observed). Focused09 keeps the same selected tests and assertions, restricting only the
early class scan through an ignored init script. Full repository gates do not load that script.
Its 231-path frozen input is `28d660a2cb9416c23a13a2133f825f77b5976fe09a146dff4d6bdd20ff63c0cd`,
including the reviewed native released-v12 test. Engine results are 276 coordinator and eight
caller-authority cases, all passing with no skips; the rest of this focused run is still pending.

The reserved existing API-37 emulator ran the current schema-32 core test APK
`15bcf4d17991ae9dbc2955e0eed4291e28f5daf1f68dd62e688239f6c91dad1e`:
seven schema/parity/old-development/malformed-family tests passed with zero failures or skips
in 54.246 seconds. These are fresh schema-32 results, not reuse of the earlier schema-31 APK.
The newly authored released-v12 native case is not in that APK and remains pending.

Focused09 completed successfully in 23m 42s: 19 selected suites, 556 tests, zero failures, errors
or skips. The counts are engine 284, statistics-data 196, database three, import/export 56,
camera six and Dashboard 11. All 231 frozen files still match. An independent reader confirmed
the XML results and input hashes. Whitespace checking also passed.

The coherent 26-file Location continuity/readers/export/schema unit is committed to local
`dev/v10` at `d43d9eab7f1b6373e093c0f4bad5feeebf24481d`. It includes the actual generated schema
32; schema 31 remains immutable. The rest of the task is still uncommitted and not complete.
Packaging10 runs Dev/Debug/test assembly, Detekt and committed-schema consistency without the
focused scan init script. The full `ciUnitTest`/`ciCheck --continue` aggregate remains required.
HouseAggregator has acknowledged the human-authorized emulator reservation and paused Android
commands; Tracker owns the native acceptance window until it sends a release.

## Current schema-32 convergence results

Packaging11, the startup/containment preflight, Detekt and Room drift checks pass: 100 selected
tests, zero failures or skips. This is a focused preflight, not the full host aggregate. Its
233-path input fingerprint is `1b5296a2f67ec2a31c421d81630fc0dfc6ea61402e4d67c25697c56b824e9408`.
Full12 now runs the unfiltered `ciUnitTest` and `ciCheck --continue` with that same frozen input.
Its known failures are three released-import fixtures still expecting version 31 and one
historical-DDL comparison missing the actual 31-to-32 migration. The historical v28 DDL remains
immutable; reviewed fixture corrections wait until the running batch finishes.

Actual Dev APK `83b5bc3d85bfac098719792400c532c4fe8727f0e472fa1f8d28003f7e2ae4ed`
upgrades the installed database from 31 to 32. All 1,173 original physical Location rows remain
byte-for-byte equal, with no missing rows and `quick_check=ok`. The containment admission list
now derives from the existing registered migration endpoints, preserving unknown-version blocking.
The separate real released-main v12 import test passes on the existing API-37 emulator: one test,
zero skips, 9.466 seconds, all seven released data kinds and repeat-open preservation checked.

My location in this Dev build displays the position and centers at street zoom. With tracking
off, all 1,182 saved physical Location rows remain unchanged before/after the temporary request.
The screenshots and stable main/WAL snapshots remain private ignored evidence. A separate native
History failure is reproduced: 65 physical candidates exceed Pressure's existing 64-item query
bound before source filtering. The reviewed repair chunks within the same Room snapshot and
deduplicates identical complete results, preserving the bound, qualifications and healthy siblings.
Its real-reader regression and ordinary user-facing failure copy await the next frozen input.

The disposable Debug unknown-version cold-start check now passes: version 99 reaches the recovery
screen, Retry keeps the process alive and the database byte-identical, and no Export control is
offered. The original version-32 fixture was then restored exactly. This is test-fixture setup,
not the product's restore workflow.

App instrumentation attempts `native-app11` and `native-app11b` execute zero cases: Android kills
their processes for LOW_MEMORY before the runner starts. They are not passing smoke tests and
will be retried on this same API-37 AVD with the host idle. Separately, a real data-only Database
export writes a valid 454,127-byte v2 ZIP with the two databases and no settings, then the live
Debug process crashes with SIGBUS in SQLiteX on Room's invalidation thread. Its live WAL files
disappear; all 1,173 saved Location rows remain in a healthy database. The exporter opens live
SQLiteX-owned files through Android's separate SQLite engine, whose default journal mode is
DELETE. The next reviewed input must remove that mixed-engine live access. An ignored draft
reuses SQLiteX with the existing journal mode and checkpoint/lock protocol, with a native test
that requires Room observation and further writes to work after export. Backup/restore acceptance
remains pending; a written ZIP alone does not close this failure.

The same AVD later disappeared during a coordinated Coffee test window. Its existing drive and
data disks remained accessible; restarting that AVD without a reset restored Dev with the same
APK and saved data. Normal Map departure is now positively verified: Dev PID 7346 remains alive,
GPS/fused/network requests are OFF after settled Home navigation, all 1,182 physical points and
all source WAL/Activity/Pressure counts remain unchanged. This supersedes the earlier inconclusive
immediate dump and force-stop check.

The unfiltered engine suite finishes 4,683 cases with one watchdog failure in the first six-source
deletion-fence test. Its Room fixture is already initialized; the actual large adapter graph is
first constructed inside `runTest`. A reviewed fixture-only draft prepares those same stateless
adapters in ordinary setup, retaining every real deletion assertion and the original timeout.
This does not establish the timeout's exact cause; the final gate must pass.

Native Location-file testing confirms the conflict warning and Keep behavior, but finds that a
later Replace from the same file is skipped by the prior completed import job. The saved original
and 1,173-point count remain unchanged, so this is a failed replacement, not data loss. The repair
uses the existing worker request UUID only for explicitly reviewed replacements, keeping file and
point identities stable. Its regression includes repeated A-to-B-to-A-to-B edits and same-request
retries. Actual post-repair replacement, deduplication and export acceptance remain pending.

Full12 finished in 2h 52m 25s: 15,217 host cases, five failures, zero errors and six existing
skips. Its complete reports and unchanged 233-path input are preserved before applying repairs.
The failures are the four stale schema fixtures above and the deletion-fence watchdog. This is
not a passing convergence result. Reviewed repairs now cover these fixtures, Pressure batching,
same-engine backup copying, explicit Location replacement job identity and History discoverability.

Independent source review also found that an ordinary Android process recreation cannot rebuild
Pressure-only or active all-day Location hosting from a process-local host ticket. The shared
service now acknowledges its foreground shell first and reuses the existing bounded startup and
chosen-source reconciliation. It does not start a session. Accepted manual delivery keeps its
exact redelivery path. Actual unattended process-recovery and Off-negative checks are still due.

A held Steps callback can also retain the issuer's global mutation lock and delay healthy source
owners. The next input separates only those long mutations by their existing source purposes,
keeping shared identity bookkeeping short. Review requires unfinished-flight checks inside the
existing operation locks, including automatic control, before issuer access. Collection Off must
close owners without requesting new recording grants. These changes remain subject to the next
full gates and native acceptance; none of the new source is claimed validated yet.
