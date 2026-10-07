# Automatic transition to independent Steps capture

Phase: IMPLEMENTATION_ONLY. Status: IMPLEMENTED_UNVALIDATED. Every test is NOT_RUN.
No Gradle, compilation, tests, lint, Detekt, Room/schema, diff-check, CI, device/emulator,
provider activation, integration, push, Git mutation or recursive delegation occurred.

Base: 0cdd3575fa5893d96986caa680d24ab08f3b7ad8.
Current dependency comparison: 2d979ea9850158529614bf8fd03f505a7ae91e9c; all pinned inputs unchanged.

## Authority and scope

The original user requires source-independent manual/ambient/automatic tracking, automatic trigger
control separate from recorded Activity, coherent End/Off fencing and meaningful full-chain test
source before the final convergence gate. The root explicitly approved this bounded test-source
composition on October 7: two End cases and one Off case using existing real components and Room,
with controlled Android/provider boundaries and no production architecture change.

One new test class is selected. There is no production, API, dependency, schema, UI, resource,
permission, policy or provider configuration change. It is private under SELECTED; the shared source
path remains absent. No existing test is rewritten and no preserved draft is modified.

## Real production composition

- Existing RoomSourcePolicyRepository and canonical test-lane installation establish Steps-only
  capture and a separately authorized automatic CONTROL demand. The lane installation is an explicit
  test setup bypass, not production promotion or activation evidence.
- CurrentTrackingPurposeAuthorityReader, CurrentSourceCallerAuthorityReader, ExactSourceCallerGuard,
  GuardedSourceCallerDemandDispatcher, RoomSourceCallerAcceptedAuthorityRepository and SourceBroker
  create and authenticate the actual demand/receipt. Neither capture nor control demand acceptance
  is replaced with a fake permitted result.
- A captured ActivityRecognitionEvidenceBatch reaches ActivitySourceDeliveryFactory,
  RoomActivityRecognitionEventIngress and RoomDurableSourceIngress. The writer allocates its
  sequence, WAL receipt and observed authorization. No automatic action/outbox row is hand-created.
- ActivityAutomationProjection/Lane and SourcePipelineRecovery feed the actual outbox validator,
  action repository, BackgroundTrackingActivityAutomationEffectConsumer and BackgroundTrackingApi.
- TrackerServiceApi reserves the actual command under SharedPreferencesTrackingLifecycleCommandAuthority,
  including its observed-time Stop fence. DefaultTrackingStartRequestCoordinator and the actual
  TrackerServiceSourceSession/AuthoritativeSessionCoordinator create the prepared Room envelope,
  guarded staged demands and a descriptor written through DefaultActiveTrackingSessionStore/DataStore.
  Android enqueue is the controlled outer boundary; no Start API or coordinator returns fake Enqueued.
- The actual claim, foreground Room acknowledgement and command acceptance run through
  DefaultTrackingStartRequestCoordinator before its actual apply starts StepSourceRuntime.
- StepSourceRuntime, SourceRegistrationRepository, durable atomic admission and
  StepsSessionFactProjectionLane save the real native product derived from two controlled sensor
  counter callbacks. The test checks the saved count and exact logical session/service run lineage.
- End uses the actual durable Stop reservation, TrackerServiceSourceSession stop and provider/runtime
  retirement. Off uses DefaultTrackingTurnOffController.reserveAndFence (including its real Room
  purpose fence), then the same source-session stop. Saved products remain present.

## Controlled boundaries and remaining evidence

The Activity PendingIntent receiver itself is not invoked: the batch contains a controlled callback
identity and captured command generation. Activity's physical arbiter implementation belongs to
sensor:activity while tracker:engine compiles against its API. Its already accepted system registration
is therefore a fixture, with authorization rows generated from the real guarded demand using
SourceBrokerAuthorization.rows. CONTROL Ready publication is also a fixture; its current check
uses the actual exact authority and full identity vector. This does not prove real Activity activation.

The Android SensorManager, sensor identity/callbacks, hardware/permission checks, package features,
Play Services availability, battery input and foreground enqueue/promotion are controlled fixtures.
StepSourceRuntime and SourceRegistrationRepository are real. Startup/deletion readiness is a fixed
ready fixture with Off read from the actual durable commands; the four-argument recovery constructor
has no application driver or deletion enrollment. This slice does not prove the app startup gate,
Clear cancellation, actual TrackerService onStartCommand/APPLY child lifecycle, Hilt dispatch,
notification promotion or platform FGS/battery/provider behavior. Those remain separate cases/gates.

Off's actual durable/Room fence and Steps retirement are covered. The test does not call
closeOwnersAfterSessionStop or claim complete retirement of the fixture Activity provider or other
ambient owners. It does not exercise the full awaitable public Off router or sessionless Steps import.
T19, the 25 full-chain prerequisites, device evidence and final convergence remain open.

## Fixture durability and cleanup

The descriptor uses a unique app-cache DataStore file and an IO-owned scope, which is canceled and
joined before the test database closes; the file is then deleted. The actual Start API entry point and
Background API dispatcher are restored, and Background API state and the Assist fixture are cleared.
The real Start apply and counter WAL-arrival waits are each bounded to five seconds. Production apply
can retry a retryable invalid intent indefinitely; this test timeout fails that await rather than hanging
the host test process, while retaining the real apply and existing teardown. The counter test waits on
actual committed Room ordinals; it never substitutes an expected writer result. No reporting-interval or wall-time stretching assumption is used.

SELECTED is UTF-8/LF. FILES pins the one new source and its patch. DEPENDENCY_BASE contains exact
committed source bytes and DEPENDENCIES pins every listed input. Existing dirty working files are
unselected and preserved; root owns independent review, selection and Git.
