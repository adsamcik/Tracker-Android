# Collection controls source trace

2026-09-27. **IMPLEMENTED_UNVALIDATED bounded slice; whole task open.**
Candidate codex/source-native-map-context-root starts at b3dc968a1b. Main dev/v10 remains 13f1a99871.
No Gradle/compile/test/lint/Detekt/schema/diff-check/device/UI/battery/CI/release validation, activation,
freeze, integration or push. Tests are uncompiled/unrun; static trace is not runtime evidence.
Protected files/drafts and preserved refs unchanged.

## Authored corrections

- TrackingSettingsScreen saves Activity/Steps/Wi-Fi/Cell before permission launch. Its post-save
  callback checks actual capability and RESUMED lifecycle. TrackingSettingsViewModel uses per-source
  local edit generation and existing committed-policy/debt boundary to suppress superseded prompts.
  Grant/deny callbacks refresh actual capability and reconcile; none writes selection intent.
- DefaultTrackingStartRequestCoordinator previously resumed suppression under the startup lease,
  which already holds the lifecycle commandGate. Resume now precedes that lease through atomic
  resumeCollectionForManualStart, requiring the exact exit and latest allowed Start. A newly observed
  exit advances existing persisted lastGeneration, invalidating pre-exit resume acknowledgment.
  This does not alone make every old Android delivery stale in resolveStart; descriptor/startup/
  admission fences remain required. Under the startup lease, only read/reject pending suppression,
  including recovery/redelivery and a new exit racing the handoff.

No new schema, dependency, network purpose, telemetry or activation.

## Authored focused tests

- TrackingSettingsViewModelTest: stale true grants with denied actual capability cannot enable four
  sources; save-before-prompt/denial preserve intent; superseded suspended save and debt suppress prompt.
- TrackingLifecycleCommandAuthorityTest: pre-exit, superseded and STOP-blocked Start cannot resume;
  fresh current Start acknowledges exact exit.
- SharedPreferencesTrackingLifecycleCommandAuthorityTest: exact resume precedes startup fence;
  persistence/recreation, receipt non-reapplication and newer Start/exit rejection.
- DefaultTrackingStartRequestCoordinatorResumeTest: actual prepare uses real nonreentrant lifecycle
  authority and a startup gate harness; fresh resume progresses to descriptor-read failure rather
  than hanging, new exit before lease rejects, ambient/recovery cannot acknowledge exit. Deliberate
  descriptor failure avoids claiming Room/provider success.

Full platform/Room/widget/notification/UI and final host/device proof remain deferred.

## Every entry point and remaining work

| Path | Existing trace | Remaining |
|---|---|---|
| Tracker/Dashboard Start | shared readiness/PREPARE/enqueue | Fence permission and Location-settings continuations |
| Widget Start/End | both labels use ToggleTrackingAction and fixed 500 ms refresh | Explicit rendered action, exact capture identity and acknowledgment |
| Shortcut | shared manual Start; STOP_COLLECTION global stopService | Capture-scoped End; Turn off placement |
| Notification | immutable PendingIntent, nonexported receiver; lock manager stops automatic capture | Exact session identity, plain Stop now, Turn off |
| UI End | durable global STOP/runtime or inactive finalizer | Separate capture scope; no-capture/stale End preserves background/successor |
| Turn off | approved planning only | Durable intent/fence/retirement, surfaces, failure/retry |
| Background re-enable | Location acknowledges exact exit after save | Steps/Wi-Fi/Cell equivalent plus stale edit fencing |
| Feature leases | global permitsNewCollection rejects new demand | Independent feature scope without clearing tracker suppression |

DefaultLockManager already stops automatic capture when a lock becomes active. The initial
hypothesis that notification pause never stops was corrected against this code. TrackerService
currently preserves backgroundHost only when sessionInfo is also nonnull; stale End against a
background-only host can reach full teardown. Internal maintenance quiescence must keep its wider
shutdown scope. Full collection controls remain unchecked; decisions are in CONTRACT_DECISIONS.md.
