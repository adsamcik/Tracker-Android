# Passive background Location: contract and current source trace

2026-09-27. **PROPOSED_NOT_IMPLEMENTED — confirmation pending.**

Original request:

> **Complete passive background Location.** Add system-rearmable delivery, authenticated receiver admission, recovery and safe transitions to the existing active Location owner.

## Phase and scope

Continue implementation-only. Static source inspection/review and coherent local commits are
allowed. Production changes require focused test-source authorship, but Gradle, compilation,
tests, lint, Detekt, schema generation/drift, diff-check, device/emulator, UI/battery, CI/release/
rollout gates are deferred. No activation, push or new dev/v10 integration. Preserve protected
paths, original source refs and excluded drafts. A planning receipt is not implementation evidence.

The selected candidate is `codex/source-native-map-context-root`, starting at `f0a36cef2c`.
Main `dev/v10` remains `13f1a99871`. This document proposes no rollout or default changes.
User AGENTS.md requires confirmation of new architecture and data-handling decisions before
implementation. The settled scope is passive, independently consented, sessionless Location,
with strict acknowledged retirement before switching to the existing active owner.

## Concrete confirmation requested

Use the existing shared Location controller and runtime for both transports:

- Passive delivery uses a PendingIntent through FLP or framework PASSIVE_PROVIDER fallback.
  Keep active FLP callback/framework listener acquisition. Do not create another ambient runtime.
- Reserve a distinct exact `SYSTEM_REARMABLE` generation in existing provider-registration
  storage, with no process-incarnation owner. Existing callback generations stay `PROCESS_BOUND`.
  Authenticate the delivery mechanism separately from quality: identical PASSIVE quality does
  not make a callback and a PendingIntent the same physical configuration or compatible refresh.
  Use the durable generation, source instance, boot, fingerprint and data epoch to reconstruct
  the exact explicit PendingIntent for cleanup. Identity must be in Intent filter fields, not
  solely extras; provider result extras cannot supply registration authority.
- Use one non-exported receiver, with off-main admission bounded to five seconds from receipt
  and at most 64 copied fixes. Always finish its PendingResult. Overflow or timeout is partial/
  unknown coverage, not successful complete delivery or a promise of later payload recovery.
- Authenticate current admission/startup permission and historical provider/authorization
  evidence, then use the existing unbound ingress and sole protected Location writer. No fake
  session, new canonical writer, feature-to-persistence authority or hidden source demand.
- Recover exact uncertain/pending old handles before reserving a replacement. Fence, remove,
  acknowledge and only then add; provider removal failure blocks replacement. Report gaps.
  A same-boot surviving delivered PendingIntent may authenticate its accepted generation;
  an ACTIVE database row alone does not prove the OS still holds a request.
- Reuse existing startup, purpose, deletion, retention and boot/package-update recovery seams.
  Never let receiver or worker clear suppression or reconstruct foreground-service permission.

The question tool offers the recommended FLP plus framework contract or explicit FLP-only scope
(truthful unavailable state without GMS). No answer has been received or inferred. No dependent
production or test-source implementation has been applied.

## Source trace: changes required together

All paths below are relative to the selected candidate, not the main checkout.

| Seam | Existing source and finding | Required closure |
| --- | --- | --- |
| Purpose | `tracker/engine/.../source/runtime/AmbientLocationPurposeOwner.kt`, `reconcile`/`applyPlanLocked`: only ADAPTIVE is admitted; PASSIVE_ONLY retires the purpose. | Admit the approved passive-only purpose independently of a service host. Keep active baseline on its lawful existing host. |
| Demand | `SourceBroker.kt`, `replaceAmbientLocationDemand`: passive floor/120s age already exist alongside current consent, retention, caller and rollout checks. | Reuse exact ambient authority; do not fabricate session/run fields or infer consent from Location capture. |
| Shared owner | `SharedLocationSourceController.kt`, `effectivePlan`/`applyEffective`: session and ambient demand already join one physical runtime; passive baseline yields to active capture. | Preserve independent joins while selecting passive versus active transport. End capture downgrades to passive only after acknowledged active retirement. Ambient revoke preserves lawful capture. |
| Acquisition | `LocationSourceBackend.kt`: both FLP and framework passive modes still use process-local callbacks/listeners. | Add passive PI transport to the same owner, with exact identity and retained failed-removal handle. No all-PI rewrite of active acquisition. |
| Prerequisites | `LocationPrerequisites.kt`: all starts currently require foreground-service Location capability; automatic background starts require FGS legality. | Separate passive background delivery prerequisites from active-service entry. Passive cannot require or confer an FGS exemption. Require applicable background Location permission. |
| Reservation | `SourceRegistrationRepository.kt`, `beginInternal`/`refreshActiveAuthorizationInternal`/`markAccepted`: physical lifecycle assumes PROCESS_BOUND and the current process ID. | Add bounded Location-specific system-residency lifecycle; preserve process-only semantics for callbacks and other sources. Never relabel old callback authority. |
| Retirement | Same repository's retirement token and `SourceBrokerDao.kt` process retirement queries filter by process residency/current process. | Reconstruct/fence/remove exact Location PI generations across process boundaries and originating boots. Existing prior-process reconciliation must not retire a surviving system generation as a callback. |
| Runtime transitions | `LocationSourceRuntime.kt`, `reconfigureWithClaim`/`retireProviderLocked`/`reconcilePendingRetirementLocked`: existing remove-before-add, drain and retained-debt machinery is process-local. | Integrate passive debt/reconstruction into that lifecycle, not a parallel coordinator. Drain an admitted prefix within bounds; never install a successor behind unresolved removal. |
| Manifest/receiver | `tracker/engine/src/main/AndroidManifest.xml` has no passive Location receiver. Activity receiver provides an existing cold-process/entry-point pattern. | Exact action/component/filter identity, defensive backend-specific result decoding, bounded work, and lazy Room dependency resolution after admission startup. |
| Ingress | `source/ingress/DurableSourceIngress.kt` resolves registration and authorization at observation time, per-purpose freshness and deletion/retention in the transaction. `DurableSourceEventSinkFactory.unbound` requests existing projection after durable admission. | Reuse these transactions; produce candidates from each fix's historical authorization, not the latest consent. Authenticate passive transport/residency before allocation; preserve replay identity and sequence zero. |
| Materialization | `source/location/LocationWalQualificationAdapter.kt`, `qualifyAmbient` and captured qualification expect PROCESS_BOUND provider intervals. | Accept SYSTEM_REARMABLE only with authenticated passive acquisition and interval evidence; do not indiscriminately relax the residency check. Keep one sample and lawful purpose associations. |
| Recovery | `PreviousExitRecoveryCoordinator` reconciles process-bound rows. `TrackerModuleInitializer` reconciles source policy and each purpose after startup authorization; `TrackingPurposeReconciliationRetryWorker` owns durable retries. Boot work explicitly rearms automatic control. | Use those existing recovery seams; Location owner must settle its passive debt before publication. Boot/update creates fresh authorized boundaries after exact prior cleanup, never resurrects capture. |
| Suppression | `DefaultTrackingStartupGuard` supplies positive API35 force-stop handling. Separately, `app/.../startup/DefaultTrackingStartupGate.kt` records observed user-requested exit through `SharedPreferencesTrackingLifecycleCommandAuthority`; `permitsNewCollection` and the resume boundary are already checked by grants/ingress. | Reuse both gates. Surviving PI delivery/rearm must not acknowledge user intent or bypass suppression. Explicit background re-enable/start may resume at a fresh boundary; preserve historical reads and already-admitted materialization. |
| Deletion/history/files | Existing protected ambient writer and purpose associations are retained; source deletion requires provider-settlement proof. | Carry passive provenance through the same qualification, source settlement, maintenance, read and export paths; one-purpose deletion must preserve a lawful sibling and full deletion must fence delayed PI delivery. |

`SourceProductFactories.LocationAmbient` remains contained and its portable capability unsupported.
An acquisition change alone does not close passive product readiness or authorize descriptor
activation. Review and complete the existing purpose/writer/read capability seams separately,
preserving the implementation-only and activation boundary.

## Required test sources after confirmation

- FLP and no-GMS framework passive registration, disabled hardware/provider, coarse/fine/background
  permission matrix, exact mutable explicit PI identity and no active request/FGS for passive-only.
- Cold same-boot delivery after process death; wrong action/backend/identity, process-bound
  impersonation, old boot, unaccepted/retired registration, pre-acceptance cached fix, changed
  epoch, revoked consent/permission, retention/deletion floor and malformed payload rejection.
- Historical authority across session join/leave, mixed observation times, exact replay, zero
  sequence, delayed old transport and one shared canonical sample with independent associations.
- Five-second deadline includes startup/storage/decoding/admission; cap 64, record honest lost/
  unknown coverage, durable prefix on timeout, always finish, no unbounded migration in receiver.
- Passive-to-active and active-to-passive removal failure, cancellation, acceptance failure,
  ambiguous apply-then-fail, actor drain failure and process death at reservation/removal/publication.
- Ambient disable preserving capture; session End preserving passive background; last-purpose
  removal; deletion during receiver admission; recovery after boot/update and no stale resurrection.
- Existing startup suppression and observed-user-stop receipt/explicit-resume boundary; scheduled
  workers and surviving PI callbacks cannot acknowledge user intent.
- Writer/query/maintenance/export retain passive registration proof and honest sparse coverage.

These are required authored cases, not tests already present or passing results. Whole passive
Location remains unchecked until the coordinated production and test-source slice is implemented
and independently source-reviewed. Runtime/provider/device evidence is deferred separately.

## Platform evidence consulted

- [FLP PendingIntent delivery](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient): supports background delivery after system process kill;
  reset/upgrade/force-quit/cancel can remove requests; cached and out-of-order passive batches need
  timestamp qualification. This is not a guaranteed cadence or reboot registration promise.
- [Framework LocationManager](https://developer.android.com/reference/android/location/LocationManager): passive provider and PendingIntent request/removal APIs; API31 batched result key must not
  duplicate its accompanying last-location extra.
- [PendingIntent identity](https://developer.android.com/reference/android/app/PendingIntent): exact explicit component/filter identity and result mutability need careful construction.

No production HTTP client or new application network purpose is introduced by this investigation.
