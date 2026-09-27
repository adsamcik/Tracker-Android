# Temporary Location consumers: implementation trace

2026-09-27. **IMPLEMENTATION_ONLY; bounded slice IMPLEMENTED_UNVALIDATED.**

Original user task:

> **Unify temporary Location consumers.** Route My location and games through independent shared-owner leases with real freshness requirements and no unintended tracking persistence.

Selected candidate: `codex/source-native-map-context-root`, base
`ecf8c73de9515e1c0ebb2eadc7daf7bb39530cbd`. Main `dev/v10` stays at `13f1a99871`.
The full task remains open. No provider activation, convergence freeze, validation, integration
or push is authorized by this checkpoint.

## Authored bounded corrections

- `feature/map/.../presentation/sensors/LocationAndSensorsManager.kt`: admit valid `(0,0)`;
  reject nonfinite/out-of-range coordinates; represent absent, negative or nonfinite accuracy
  as nullable unknown. A declared zero accuracy remains distinct from unknown.
- `feature/map/.../presentation/udf/Contracts.kt`, `MapStore.kt` and `ui/MapScreen.kt`: propagate
  nullable accuracy; always render the valid user marker, but create an accuracy circle only
  for a declared finite nonnegative radius. Unknown accuracy removes an old circle.
- `feature/game/.../minigame/location/FusedMiniGameLocationSource.kt` and
  `LiveOrFusedMiniGameLocationSource.kt`: reject nonpositive observation wall timestamps;
  retain the actual positive timestamp rather than manufacturing `Time.nowMillis`.

These implement already-approved honest-data requirements. They do not add the shared owner,
observed-age qualification or a new host/UX/persistence contract.

Required test sources cover actual map provider-flow coordinate/accuracy admission, actual
MapStore overlay replacement, and both game adapters dropping unknown observation time while
preserving a later actual timestamp. Four test files are authored, **uncompiled and unrun**:

- `feature/map/src/test/java/com/adsamcik/tracker/map/presentation/sensors/LocationAndSensorsManagerTest.kt`
- `feature/map/src/test/java/com/adsamcik/tracker/map/presentation/MapStoreTest.kt`
- `feature/game/src/test/java/com/adsamcik/tracker/game/minigame/location/FusedMiniGameLocationSourceTest.kt`
- `feature/game/src/test/java/com/adsamcik/tracker/game/minigame/location/LiveOrFusedMiniGameLocationSourceTest.kt`

## Current code and remaining implementation

| Boundary | Current implementation | Required closure |
| --- | --- | --- |
| Feature interface/DI | `core/common/.../location/UiLocationProvider.kt` exposes Android Location flows; `DefaultUiLocationProvider.kt` registers a provider per subscription; `app/.../di/InfrastructureModule.kt` binds it. | Bind an engine-backed adapter with typed, independently cancellable feature leases and real host identity. |
| Physical owner | `tracker/engine/.../source/runtime/SharedLocationSourceController.kt` merges session and ambient durable purposes only. `LocationSourceRuntime.kt` requires a durable registration and routes callbacks through a WAL sink. | Add an ephemeral feature delivery path on the same physical owner, separate from durable tracking eligibility. Never fake a registration, ambient consent or a dummy durable purpose. |
| Request combination | Current UI requests bypass the tracker owner; game router chooses tracker solely from generic `isActiveFlow`. | Merge feature request floors with relevant capture/background demand. A Steps-only capture must not starve game Location, and low-quality Location must not pretend to satisfy a game lease. |
| Retirement | UI provider removal is not acknowledged; fallback may follow ambiguous fused registration. | Acknowledge removal before replacement; failed removal blocks replacement. Lease release affects only its own demand. |
| Map lifetime | `feature/map/.../ui/MapScreen.kt` subscribes when a permitted non-overlay map is visible. | Confirm explicit My location/follow acquisition versus preserving visible-map acquisition, then implement lifecycle-bound demand and stale-marker handling. |
| Game lifetime | `feature/game/.../session/GameSessionService.kt` owns the existing location FGS; runtime cancels its collector on pause/finish. UI provider nevertheless closes all requests on process background. | Authenticate the existing game host and retain its lease during lawful screen-off execution; stop game independently from tracker and vice versa. |
| Freshness | UiLocationProvider carries no boot/observed-elapsed proof. Game signal age is based on receipt time; corrected positive wall time alone is not freshness proof. | Require observed monotonic age in the current boot, exact request qualification and agreed max ages; delayed cached delivery cannot reset freshness. Preserve unknown real wall time as unknown at the shared contract boundary. |
| Game measurements | Missing speed still becomes zero; accuracy and sample validity are not consistently qualified across both adapters/engines. | Preserve honest unknown measurements and existing game accuracy requirements without inventing stationary movement or accepting nonfinite coordinates/radii. |
| Persistence | Feature streams do not themselves write tracking facts, but durable-only owner composition cannot host feature-only demand. `feature/statistics/.../util/SunSetRise.kt` registers a separate permanent passive listener and saves precise coordinates to preferences. | Feature-only observations must not enter tracking WAL/history/export/retention or retained Wi-Fi/Cell enrichment. Cache-only sunrise behavior remains pending confirmation. Intentional game score/reward persistence stays separate. |
| User-stop/deletion | Tracker collection suppression is distinct from general storage readiness. Game persistence uses general readiness. | Do not clear the tracker stop gate for a feature. Independently authorized feature demand must not revive capture/ambient/automatic tracking. Collected-data deletion may retire durable demand without silently revoking an independent lawful feature lease. |

`DefaultTrackingStartupGate.isReady` is not the same as `permitsNewCollection`. The latter adds
pending user-exit suppression; `withReadyGenerationOperation` already provides general storage
fencing. Static review corrected an initial hypothesis that games needed a new readiness framework.
No such framework or gate-clearing behavior is introduced.

## Required next source slices

1. Confirm the concrete feature lease, freshness and map/sunrise decisions recorded in
   `CONTRACT_DECISIONS.md`.
2. Implement ephemeral demand/delivery and acknowledged physical ownership transitions,
   preserving separately authenticated durable sink eligibility.
3. Replace map/game provider routing through the shared adapter; authenticate map/game hosts,
   maintain independent cancellation, permission/provider loss and screen-off behavior.
4. Qualify actual observed freshness and measurement validity, stale display/signal handling,
   and approved cache-only sunrise semantics.
5. Author focused owner, admission, cancellation, background-host, stop/deletion, stale/future/
   prior-boot observation and non-persistence regression sources. Independently review the
   complete applied slice before final convergence.

No Gradle, compilation, tests, lint/Detekt, Room schema/drift, diff-check, device/emulator, UI,
battery, CI/release/rollout gate was executed. Static source review is not runtime proof.
