# Background Steps execution trace

2026-09-27. Candidate `codex/source-native-map-context-root`, base `fee73e09c4`.
**IMPLEMENTED_UNVALIDATED bounded correction; whole execution item open.**

## Current code and remaining wiring

Paths below are relative to the repository root. No production import scheduling caller was
found for `AmbientStepsFactImporter.importNext`; reader/importer and provider handoff test sources
do not establish background execution.

| Boundary | Current source | Evidence and remaining requirement |
| --- | --- | --- |
| Support/permissions | `tracker/engine/.../source/ambient/steps/AndroidAmbientStepsCapabilityResolver.kt`, `AmbientStepsCapability.kt` | Select supported current-device Health Connect mobile Steps or Local Recording. Health Connect read access can be FOREGROUND_ONLY; a worker must require BACKGROUND_ALLOWED and recheck permissions each execution. Denial must not select another provider silently. |
| System ownership | `AmbientStepsProviderLifecycleOwner.kt`, `AmbientStepsProviderRegistrationCoordinator.kt`, `AmbientStepsProviderRegistrationRepository.kt` in the same package | Accepted system-rearmable registration, immutable authority and independent ambient purpose exist. Provider subscribe/remove awaits have no finite operation deadline. Reuse existing owner/recovery; do not add a direct counter, FGS, detector or always-on sensor fallback. |
| Import | `AmbientStepsFactImporter.kt`, `AmbientStepsStructuralWindowPlanner.kt` | One structural-day window per importNext. Provider read is outside Room; exact state/authority is revalidated before transactional fact/cursor/evidence commit. Scheduling caller, access-mode enforcement and finite provider read bounds remain unwired. |
| Handoff | `AmbientStepsProviderHandoffCoordinator.kt`, registration coordinator | Handoff command is dormant. Registration reconciliation currently cleans the predecessor without invoking final import. Wire bounded drain or explicit gap before completeRetirement; preserve replay, privacy boundaries and recoverable cleanup. |
| Count domains | `core/base/.../database/StepsCountDomainStore.kt`, `RoomStepsCountDomainCompatibilityQuery.kt` | Native facts use authentic provider windows and semantic revisions. Real aggregate readers expose no shared counter token: UNPROVEN remains terminal without exact proof. Never infer common count domain from equal totals/times or add a session total to an overlapping system aggregate. |
| Recovery | `tracker/engine/.../worker/TrackingPurposeReconciliationRetryWorker.kt`, `TrackerModuleInitializer.kt`, startup gate/lifecycle authority | Existing initialization and unique purpose-debt retry reconcile registration, not aggregate import. Reuse consent/retention/boot/deletion/suppression boundaries. Bounded execution opportunities and retries need a confirmed scheduling contract. |

`...` abbreviates the established Java package path, not a proposed module or new framework.
Relevant engine dependencies already include WorkManager, Hilt Work, Health Connect and Local
Recording; no new dependency is proposed.

## Authored bounded correction

In `AmbientStepsFactImporter.importNext`, return `NoEvidence(NO_WINDOW_AVAILABLE)` when the
current retention floor exceeds this attempt's requested high-water. Previously preflight could
try advancing a retention gap/cursor beyond that high-water or misclassify the mutation failure as
storage unavailable. The return makes no provider call, Room mutation or coverage claim.

The boundary is already second-aligned. Comparing the raw floor is equivalent to rounding it
forward first, avoiding overflow near Long.MAX_VALUE. Startup suppression still runs first;
actual reads/commits keep their existing exact authority checks.

Authored importer regression sources cover 6001ms retention with a 6000ms high-water and the
Long.MAX_VALUE floor. Each asserts typed absence, no provider read and unchanged cursor, gap,
fact and evidence state. These tests have not been compiled or executed.

## Honest coverage limits

- Null/empty aggregate is missing evidence, not a covered zero. A missing current-day window
  remains retryable at its cursor. A completed missing day is an explicit PROVIDER_NO_EVIDENCE gap
  so later days can progress.
- Unknown historical timezone creates INITIAL_ZONE_AUTHORITY_UNOBSERVED coverage gaps; do not
  guess a past calendar. Enable/startup import opportunity must establish current zone early.
- Current progressive aggregate replaces/revises its stable fact rather than adding its total.
  Once a completed day advances, existing importNext does not revisit late provider corrections.
  The execution item cannot claim correction-complete historical coverage.
- Missing runtime opportunity, platform restrictions, permission loss and user-stop suppression
  cannot be presented as measured zero or exact uninterrupted collection.
- Native aggregate/direct counter overlap stays partial when exact count-domain proof is absent.
  The pending generic source product-proof contract is distinct from native count-domain storage.

## Completion boundary

Scheduling cadence and numerical limits remain proposed, not approved. Permission-aware worker,
bounded provider operations, registration/handoff recovery and their test sources are still open.
The whole item cannot be checked complete from this correction or static source trace.

No Gradle, compilation, tests, lint/Detekt, schema/diff-check, emulator/device, UI/battery, CI/release/
rollout gates, provider/writer activation, dev/v10 integration or push performed. Independent review
is recorded in REVIEW_RECEIPT.md. Final convergence and on-device execution evidence remain deferred.
