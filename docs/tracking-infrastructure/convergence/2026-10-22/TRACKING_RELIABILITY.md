# Tracking reliability coverage

Date: 2026-10-22. Base: `e4e815454fe0918b623b06734165f93cdc6c1f0c`.
Task delivery is the enclosing local `dev/v10` commit; no push.

## Coverage audit

The audit traces production ownership, admission, durable projection and retained
readers against existing tests. It adds nine focused regressions, extends Off and
startup checks, aligns older capability/presentation checks and repairs Start test
fixtures. No test framework or dependency is added.

| Reliability risk | Existing and strengthened coverage |
| --- | --- |
| Sources depend on another source or become enabled accidentally | `SourceParticipationMatrixTest`: all 64 subsets at all five balance levels, source-specific permission loss, live focus and quality overrides |
| A stale Start overrides End/Off or starts after permission choices change | Prepared Start cancellation/acceptance tests, manual-start persistence tests and `AutomaticStartCommandFenceTest` |
| Providers overlap, or cleanup releases another owner's listener | Shared Location/Pressure/radio owner tests, Activity registration arbiter tests and Steps retirement tests |
| Quality changes or restarts cause extra battery work | `RadioActiveRequestAllowanceTest` covers persisted budgets, cooldowns, reboot and failed saves; Steps continuity/import-worker tests cover unchanged sensor ownership, hourly copies and bounded retries |
| An adaptive sensor refresh failure disables healthy sources | Four new `AmbientSensorPurposeOwnerTest` cases: Activity failure isolation, lost Pressure hosting, acknowledged retirement before host release, unchanged cancellation propagation |
| All-day collection bypasses the real adaptive policy | One new owner test uses the real authorized demand, planner, resolver and battery policy: severe thermal pressure reduces continuous collection cost, then normal conditions restore the same plan without host/lease churn |
| A failed native save loses a reading or advances its cursor | New real-Room `AllDaySensorProjectionTest`: failure after native insert rolls back both reading and checkpoint, leaves Activity usable, and retries exactly once with a recreated dispatcher |
| A blocked recovery actor stalls siblings or spins after failure | New `SourcePipelineRecoveryTest`: blocked/failing Pressure leaves Activity usable, retries only after another hint; startup now checks all four retained consumers |
| Off leaves a newly supported source running | Existing real-Room `DefaultTrackingTurnOffControllerTest` now also fences all-day Activity and Pressure while preserving historical demands |
| Removing a source breaks an adaptive session or loses continuing quality | Two new real-Room coordinator cases exercise adaptive-to-adaptive and legacy-to-adaptive narrowing; the legacy case remains, retired Steps must actually stop, and Location retains exactly its requested preferences |
| Reconfiguration, deletion or replay corrupts retained data | Existing source ingress, native sensor projection, `RetainedLocationPurposeLifecycleRoomTest`, Steps count-domain, erase/retention and History invalidation tests |
| Database upgrades lose released data | Existing released-data upgrade and `FinalTrackingAssemblyMigrationTest` scenarios remain in the authoritative gate |

The recovery tests intentionally use controlled scheduling and a temporary SQLite
failure instead of timing sleeps. Recreating the dispatcher over saved Room state
tests durable replay, not Android process death. Existing planning combinations
are not duplicated as a new runtime matrix.

## Verification

### Final host result, October 23

The unfiltered `ciCheck --continue` passes, including `ciUnitTest`, release lint,
`checkRoomSchemaDrift`, Detekt, reviewed dependency metadata and release SQLite
linkage. The command exits zero in **1 h 26 m 3 s**: 2,009 actionable tasks,
66 executed and 1,943 up to date. Final independent source and evidence review
passes with no remaining blocking findings.

| Scope | Final result |
| --- | --- |
| Repository JVM/host reports | 16,022 cases in 2,157 suites; zero failures/errors; seven existing skips |
| Tracking engine | 4,508 cases in 401 suites; zero failures/errors/skips; all 18 new regressions included |
| App | 1,027 cases; zero failures/errors; two of the existing skips |
| Statistics/data | 1,610 cases; zero failures/errors/skips, including all 213 export/selection cases |
| Release-evidence Python validators | 30 cases pass; separate from the JVM/host total |
| Test-task reconciliation | All 36 expected tasks accounted for: three executed, 27 up to date, six `NO-SOURCE` without stale reports |

The final combined input includes the [all-day Location optimizations](LOCATION_OPTIMIZATION.md).
The 134-input freeze includes the unchanged original resolver test and 133 changed
source/resource/build files. Source bytes were checked again after the gate.
Completed results are reused only where the final task inputs remain unchanged.
Detekt reuses the passing final four-case policy/whole-Detekt diagnostic; there is
no test filter in the final command and no quality rule or baseline is weakened.

The final statistics boundary case accepts 2,048 records and rejects 2,049 before
emission (74.317 s body, 60.064 s fixture). The 400/401 case retains query counts
100/140 and reads in 22.889/24.153 s; all 4,201 equal-time windows retain their
identities across 43 pages (30.746 s reading). These are host measurements, not
acceptable-device-latency or battery evidence. Large-history reader cost remains
visible and needs practical device measurement.

Raw logs, the source manifest, original-report hashes and task reconciliation are
retained in `build/verification-20261022-reliability`. SHA-256 receipts:

- `source-input-final-validated.json`: `3feb0d0f4a7b2e579132741316b1d2096159bb147604ad9ea1edc6d492464432`
- `gate-14.log`: `5100032d9a1c92e2941cd316e612814283d8a6fe4ec176aac47d64bd47d54eae`
- `host-receipt.json`: `318e66a6d6c5fcb9fb5f1a0ae88088dab7d1485cfe34540caa7b8f0470099601`
- `reliability.init.gradle`: `5f1cb9c1b4bc978861d3c816f4af2d21ab87545443306ceee000db75872f10b1`

Actual command, with Gradle running on the existing JDK 21:

```powershell
./gradlew.bat --offline --no-configuration-cache --no-parallel --max-workers=1 `
  '-PtraceboxLocalRepository=C:/Users/adam-/GitHub/Tracker-Android/build/tracking-control-evidence-20261017/verification-20261014/verified-maven' `
  --init-script build/verification-20261022-reliability/reliability.init.gradle `
  ciCheck --continue --console=plain
```

The local init selects JDK 21 for host tests, sets the existing short Windows
native-test directory `C:/t/tr22`, enables full test exception text and logs the
Detekt source count. Production bytecode remains Java 17. The Tracebox repository
contains the actual pinned publication; no replacement dependency is fabricated.

### Diagnostic history

The following receipts retain their original selected scope and interruptions;
the final result above supersedes them.

The affected diagnostic batch passes **108 cases in 11 existing classes**, with
zero failures/errors/skips. It includes the seven initial new regressions and the real
adaptive planner. This selected-class result is not full-gate evidence; the
final command has no test filter.

A separate exact-method diagnostic passes all three narrowed-source coordinator
cases, including the two additional adaptive regressions, with zero
failures/errors/skips. Both large-fixture cases below also pass. These separate
receipts retain their selected scope; the earlier combined diagnostic was
interrupted after the scale cases and is not reported as a complete pass.

The command uses the existing offline Tracebox publication, JDK 21 and one worker.
A local init script selects the same JDK for host tests and a short isolated
Windows temporary directory; it does not filter tests or exclude quality gates.
Production bytecode/toolchains, quality rules and baselines are unchanged.

Diagnostic method filters use fully qualified names. Gradle 9.6.1's class matcher
admits every candidate when a filter starts with a wildcard; combined with the
existing engine limit of five classes per JVM, broad filters caused repeated
workers for unselected classes. Only diagnostic command selection changed. The
final full gate has no filter, and test heap/fork settings remain unchanged.

The first batch exposed three stale architecture expectations: a real native
Location boundary test was missing from the reviewed boundary list; generated
`build/` output made retired logging directories look like source modules; and a
manifest check still expected session-only Pressure text. Corrections preserve
the native-runner requirements, prohibition of retired source/modules/APIs and
specific foreground-service purpose. No preserved directory is removed.

The full engine batch also exposed outdated Start fixtures and expectations for
the concise waiting state, all-day rollout, native projection inventory and
Pressure's rejection message. Repairs preserve the real controller, exact
ownership/cleanup assertions and forbidden frame-pipeline checks.

Execution also caught two verification chains that accidentally checked a spied
service getter; direct controller references preserve the intended exact status
assertions. Two native Room observation tests now wait for observer registration
and use the same five-second bound on real time, avoiding a race between virtual
test time and Room's executor. Actual writes, invalidation and cleanup remain
exercised. No other matching unsafe Room wait was found in the bounded search.

The new real-planner regression caught a production failure in the shared all-day
Activity/Pressure owner: narrowing the source plans retained all six quality
preferences, violating the version-one plan invariant. The minimal repair narrows
both maps together. It preserves the selected preference and legacy empty
metadata, with no new abstraction or storage.

A bounded search found the same key invariant in source retirement. A valid
narrowed adaptive plan gained disabled cleanup plans without their metadata,
throwing before retirement. The temporary runtime plan now carries matching
cleanup metadata with focus off; desired and applied data retain only the
requested sources and preferences. A legacy predecessor uses neutral metadata
only for disabled cleanup. This is a supported coordinator contract, not a claim
that every Settings Off path failed on a device.

Two existing scale tests exceeded their default one-minute harness limit on this
host: 401 export records and 4,201 measured Steps windows. Each now uses the same
five-minute limit as adjacent large fixtures and reports setup/read timings on
completion. All records, exact totals, query-count and paging bounds remain.
Both focused scale cases complete with zero failures/errors/skips. Timings inside
the test bodies, excluding outer JUnit/Robolectric setup:

- Export: fixture 74.133 s, warm-up 1.845 s, 400-record read 114.232 s and
  401-record read 76.029 s. Query counts are 100 and 140, within the original bound.
- Steps paging: fixture 71.973 s and reading 168.015 s; all 4,201 windows across
  43 pages retain their exact identities and equal timestamps.

These results establish finite correctness and query/page bounds. Real reader
work is slow on this contended host; the delay is not solely fixture setup.
Acceptable on-device latency remains unverified. No further limit is relaxed.

The next unfiltered gate passed app tests (1,027 cases, no failures/errors,
two existing skips) and release lint before an existing 2,048/2,049 Steps boundary
case exceeded its five-minute limit. That run was interrupted; it is not a full
pass. The fixture now uses the same existing validated producer batch as the
neighboring scale tests, avoiding repeated schema inspection for every row.
Each carrier, token and owner is still checked; transaction completion rechecks
the schema. The original five-minute limit, all 2,048 accepted facts, rejection
of fact 2,049 and zero emission on overflow remain. Phase timings distinguish
fixture work from reads in the final run.

The combined follow-up completed statistics/data tests and release lint, then
the session ended while engine tests were still running. No final engine XML
or repository result was produced; that interrupted run is not a full pass.
On October 23 the same 132-file frozen input resumed the unfiltered gate,
reusing valid completed tasks. Partial output from the interrupted run is retained.

That run completed `ciUnitTest`, including all 4,508 engine cases, with no engine
failures/errors/skips. Root Detekt then recursively scanned 46 preserved alternate
checkouts containing 125,894 historical Kotlin/KTS files outside the active module
graph. The run was interrupted; it is not a complete quality-gate pass. The existing
source filter now excludes `.worktrees`, preserving those files and every active
module, rule, severity and baseline. A local read-only source-count hook initially
failed on the included build, which has no Detekt task; the hook now tolerates that
build. No production or test source changed for that diagnostic correction.
Detekt now reads 3,294 active files and no alternate checkouts. It exposed 19
maintainability findings in the new code: missing braces, one oversized test class
and two helpers over the complexity limit. Braces and a strict-proxy extraction
preserve the fixtures. Four unchanged all-day resolver cases now live in their own
focused class; the original resolver test returns to its prior content. The
stationary resolver reuses its existing helper with equivalent explicit timing
tuples. Independent review confirms unchanged behavior and assertions. The final
freeze records 134 input files, including the unchanged original test; it contains
133 changed source/resource/build files. A named Balanced-level constant and line
wrap clear the remaining static findings without changing values. Standalone
Detekt passes with the original rules and baseline; the final full gate reuses
that valid result. Earlier interrupted quality runs are not full passes.

The final cross-feature review also rejected the proposed 60-second Balanced
stationary interval. Wi-Fi's approved join needs both Location endpoints within
30 seconds, so that interval would exclude nearly all intervening readings.
Balanced and higher retain the existing 30-second stationary interval with
balanced power accuracy; the two lower levels keep 120/90 seconds. The planner's
five-level test and direct-floor assertion cover the repair; existing radio-join
tests retain their rules. No cross-module test dependency or conditional radio
acquisition is added. The preceding full run completed app/statistics tests and
lint but was interrupted during engine tests; it is not a full pass for this
revised input.

An earlier completed full statistics/data task also confirms the original Steps bounds:
all 68 export and 145 paging/selection cases pass. In that run the 2,048/2,049
boundary took 99.451 seconds inside its body (fixture 73.531 seconds), the 400/401
case took 91.182 seconds (fixture 20.129 seconds; reads 35.617/35.379 seconds), and
4,201-window paging took 56.348 seconds (fixture 18.889 seconds; reads 37.458
seconds). Query counts and all 43 pages retain their original bounds. These host
timings vary with contention and do not establish acceptable device latency.

Release lint also found missing translations from the preceding all-day feature.
The seven messages are now localized in the existing locales, preserving Activity
name placeholders and air-pressure terminology. Map snackbars use Compose's
current resources without restarting Location effects. The saved-read helper
retains Room's inherited-writer safety check with one documented `RestrictedApi`
exception on that helper; removing the check would permit an unrelated snapshot
to replace uncommitted writer data. Existing Room tests cover cancellation and
that off-thread rejection. No lint baseline or rule changed.

Independent source review found no blocking issues in the nine new cases,
complementary coverage, localized messages or the narrow repairs. Final evidence
review independently reconciles all 2,157 raw report paths and hashes, all 18 new
cases, the source freeze and the full-gate receipt. The seven unchanged skips are
two Windows FileProvider assumptions, one symlink assumption, three Android-only
map cases and one missing GPX fixture. No new regression is skipped.

## Device boundary

Host tests do not prove real sensor/provider delivery, Android process survival,
screen-off/reboot recovery or battery use. Those still require the planned device
checks. The existing API 37 emulator and physical devices were not operated in
this test-source task.
