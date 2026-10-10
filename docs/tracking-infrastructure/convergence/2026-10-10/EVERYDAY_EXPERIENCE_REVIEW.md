# Everyday experience review — 2026-10-10

Request: “Source choices, permissions, Start/End/Off, saved History and daily
Steps must agree and remain usable when another source is unavailable.”

This is a bounded continuation of the explicitly authorized host verification.
All 25 assembly prerequisites remain open. Source tracing, host tests and
compilation do not prove real providers, UI interaction, process death, battery
behavior, a runnable APK, or readiness for integration into `dev/v10`.

## Reviewed input and repairs

Base: `d63e51a2edc253935e4e2d507730ca5f954d6743` in the isolated
`codex/tracking-verification-1010` checkout. The original candidate and its drafts
are preserved; the main `dev/v10` checkout remains unchanged.

| Commit | Completed source change |
| --- | --- |
| `1b7d1906d0199b59969e64831051eb676c2d9b75` | Remove both Android launcher widgets, their receivers, implementation, resources, translations and unused Glance dependencies. Cancel their exact old update job during existing app-update maintenance. Keep in-app Dashboard cards. |
| `62dfa6b4e3b636d5b2c9081d3a14c2f9b04de05f` | Repair four Start gates so accepted healthy sources proceed while unavailable siblings remain blocked and unresolved. Preserve zero-ready retries, prepared-source constraints and reconfiguration behavior. Let both permission callbacks use reread readiness so useful approximate Location can finish the original unchanged Start. |
| `71a992b12d4022d7a158f3cc2cae47a2c721b86e` | Bind the weekly Steps request to one local day/zone value. Refresh on Statistics resume and reschedule the existing midnight timer. Serialize resume and timer writes on the ViewModel main scope. |
| `1d9e45c9a5a3fb04e9bccb1624282ab12fdf1b65` | Repair three host-test fixtures to clear every created ViewModel before test-scheduler cleanup. Assertions, fixed clocks and rollover checks are preserved. |

The original review agents traced separate source-choice/permission and
History/Steps paths. A fresh review-only agent received the full original user
history, corrections and approvals, inspected the combined diff, and rechecked
the concrete architecture-test and timer-serialization findings. No recursive
delegation was used.

## Whole-flow source findings

| Area | Current finding |
| --- | --- |
| Choices and denial | Six-source onboarding preserves denied choices; Steps has one all-day choice. Settings still lacks convenient permission repair for several already-selected sources. |
| Start with an unavailable sibling | Repaired at preparation, foreground acknowledgment, prepared apply and direct/recovery Start. Six focused coordinator scenarios are authored and compile; their execution is still blocked by Stats/data. |
| Start after permission repair | Two routes now retain useful coarse Location. The shared continuation still fences source-choice changes, End, Off, user exit and duplicate delivery. Two new API cases passed. Direct whole-route permission interaction is not tested. |
| End and Off | The shared API/coordinator paths distinguish session-only End from durable all-purpose Off and invalidate old Starts. Their code retains preferences and facts. Tracker and automatic notifications still need the approved separate controls; device behavior is unproven. |
| Saved observations | Timeline and Calendar share the measured-observation reader. Per-source failures have their own Retry while healthy rows remain. Retained Location and ambient Steps use recording-time authority, not current collection state. This is source-traced; no new device or whole-History acceptance is claimed. |
| Daily Steps | Statistics shows the independent seven-day summary without session-count gating and preserves saved Steps when collection is off. Its date/zone refresh is repaired and tested. A malformed native recording can still hide other native days. |
| Live displays | Missing Location points or a Location-summary failure do not themselves hide Dashboard Steps. A Wi-Fi/Cell/Activity/Pressure union failure can hide all Dashboard live content. Tracker hides its daily progress card during a session and has no live Steps value. |
| Provider-only Steps | All-day recording can use a supported system provider, but Steps-only Start requires a direct counter. An organization-only session over provider Steps is not implemented. Do not falsely mark the direct-counter runtime available. |

Relevant traces include `AndroidForegroundSourceCapabilities`,
`TrackingSettingsScreen`/`TrackingSettingsViewModel`,
`AuthoritativeSessionCoordinator`, `ManualTrackingStart`, `TrackerServiceApi`,
`DefaultTrackingTurnOffController`, `HistoryPresenterViewModel`,
`UnifiedHistoryRangeReader`, `RetainedLocationContextReader`,
`RoomStepsNumericSummaryRepository`, `StepsDailySummaryRepairComposer`,
`DefaultTrackingHistoryRepository`, `TrackingHistorySourceUnionReader`,
`DashboardViewModel` and `TrackerDashboard`/`TodayProgressCard`.

## Remaining everyday work — not completed

- [ ] Add the proposed Settings “Allow access” actions for selected sources,
  reusing existing prompts; session-only Location must not require all-day opt-in.
- [ ] Finish distinct End session and Turn off tracking controls on Tracker and
  automatic-session notifications, retaining automatic pause choices.
- [ ] Resolve and implement a session over supported all-day provider Steps when
  the device lacks a direct step counter, preserving real provider count windows.
- [ ] Recover valid native Steps days independently when a shared read is
  unverifiable. The proposed exceptional per-day fallback reuses qualification in
  the same Room snapshot; it must not invent calendars or counts.
- [ ] Preserve healthy live source results when a source-local union read fails.
  Narrow the existing all-or-nothing snapshot rule without weakening identity or
  snapshot checks; failed products must not acquire numeric qualification.
- [ ] Show a compact Steps today value in both live screens using the existing
  per-day display reader. Keep goals/achievements strict; do not label a daily
  total as a session total or fabricate missing readings.

These proposals were sent through the questions tool and remain unanswered.
The earlier Cell-reader interface, Detekt file-selection and two Location-gap
proposals also remain open; this slice does not silently implement them.

## Host verification evidence

Batch 13 froze `71a992b12d4022d7a158f3cc2cae47a2c721b86e`:

- The two selected API classes passed: 14 continuation cases and four launch
  architecture cases, zero failures/errors/skips.
- Engine Debug/Release and all engine unit-test source compilation succeeded.
  Tracker Debug Kotlin and app Debug manifest/resource merging succeeded.
- Statistics was **not a pass**. Its owned worker was stopped after a thread
  dump proved an uncancelled fixed-clock ViewModel timer looping during
  `runTest` scheduler cleanup. The remaining checks completed under `--continue`.
- Exit 1, 20m 27s; 509 tasks: 386 executed, 120 from cache, three up-to-date.

Batch 14 froze `1d9e45c9a5a3fb04e9bccb1624282ab12fdf1b65`:

- All 17 cases in the three Statistics presenter classes passed, zero
  failures/errors/skips, including both new zone-change regressions.
- The same compilation, API, manifest and resource tasks succeeded as up-to-date
  results from batch 13; they were not executed afresh in this rerun.
- Exit 0, 1m 11s; 509 tasks: seven executed, 502 up-to-date.

Thus 35 selected host cases passed across the two batches. This is not the full
`ciUnitTest`/`ciCheck` result. Engine coordinator execution, Dashboard compilation,
app/Hilt compilation, app widget-removal fixture execution, direct permission UI,
and device/provider evidence remain pending. The already documented Stats/data
Cell interface compiler blocker and full quality failures remain unresolved.

### Exact commands

Run from the verification checkout with the JDK, reviewed pinned Tracebox mirror
and short-temp init script documented in
[the storage verification results](STORAGE_COMPILATION_VERIFICATION_RESULTS.md).

```powershell
$verificationArgs = @(
  '-PtraceboxLocalRepository=C:/Users/adam-/GitHub/Tracker-Android/.worktrees/tv1010/build/verification-20261010/tracebox-alpha7-reviewed',
  '--init-script', 'build/verification-20261010/short-temp.init.gradle',
  '--continue', '--no-daemon', '--no-parallel', '--max-workers=2',
  '--no-configuration-cache', '--console=plain'
)
# Batch 13
.\gradlew.bat :tracker:api:testDebugUnitTest --tests '*ManualTrackingStartContinuationTest' --tests '*TrackerServiceLaunchArchitectureTest' :feature:statistics:testDebugUnitTest --tests '*StatsPresenterViewModelSessionStatsTest' :tracker:engine:compileDebugUnitTestKotlin :tracker:engine:compileReleaseKotlin :feature:tracker:compileDebugKotlin :app:processDebugMainManifest :app:mergeDebugResources @verificationArgs
# Batch 14
.\gradlew.bat :tracker:api:testDebugUnitTest --tests '*ManualTrackingStartContinuationTest' --tests '*TrackerServiceLaunchArchitectureTest' :feature:statistics:testDebugUnitTest --tests '*StatsPresenterViewModelSessionStatsTest' --tests '*StatsPresenterViewModelWifiStatsTest' --tests '*StatsPresenterViewModelCellSignalTest' :tracker:engine:compileDebugUnitTestKotlin :tracker:engine:compileReleaseKotlin :feature:tracker:compileDebugKotlin :app:processDebugMainManifest :app:mergeDebugResources @verificationArgs
```

Clean input, free space, native launch failure, exit status and completion markers
were guarded. No source edits occurred during either batch. No tests were
disabled, assertions weakened, rules suppressed or baseline expanded.

Evidence is preserved under `build/verification-20261010/`; ten selected logs,
exit records, the thread dump and XML suites are copied into
`everyday-host-pass-14-evidence` and match its inventory.

| Evidence | SHA256 |
| --- | --- |
| Batch 13 log | `26c73c5d2e5c3e498b600c2bde7dd66ecb3d7a52c1f350d7c904356c69bdf0f0` |
| Stalled worker thread dump | `c351a2f4882b4de7b7a503bf9da500056434912b145927999eef567a704a6d71` |
| Batch 14 log | `b84b15a740462805402dcbe47a834633f3a41e6fcc56bc25f4f1e5b9796e156f` |
| Evidence inventory | `97bb581c9ae6f9dcb2d3badfceaffcecfd47f033bf54a2ecb0190664164a9724` |

The implementation commits remain labelled IMPLEMENTED_UNVALIDATED because
the complete feature has not passed convergence. No provider activation,
device validation, integration, push or worktree cleanup is claimed.
