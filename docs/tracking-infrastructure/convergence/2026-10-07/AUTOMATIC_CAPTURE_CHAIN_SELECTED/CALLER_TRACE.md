# Source trace and prior coverage gap

Prior committed test sources were inspected before adding this class:

- ColdActivityAutomationOutboxTest calls the real outbox/consumer/Background API, but mocks action
  storage and TrackerServiceApi.startServiceAndAwaitEnqueueResult to return Enqueued. Its source
  authorship does not cover actual Start reservation or preparation.
- AutomaticStepsControlRoomTest covers policy/lease/caller/CONTROL registration using Room and a
  recording Activity arbiter fixture. It does not deliver a transition through Start or a saved capture.
- AutomaticStartCommandFenceTest uses the actual public Start API/command store, but a mock coordinator
  and only rejection cases. It cannot establish successful prepared capture.
- AuthoritativeSessionCoordinatorTest's automatic v2 Steps case covers prepared/claimed/applied Room
  capture and a saved product, but hand-seeds its automatic action and uses FakeSourceCallerDemandDispatcher.
- PreparedStartForegroundAcceptanceTest covers actual command/coordinator acceptance against a mock
  Room acknowledgement. StepSourceRuntimeRetirementTest controls both hardware and registration storage.

The new class closes their bounded composition gap using actual guarded demand/receipts, writer-created
Activity WAL/outbox/action, the actual Start API, Room preparation/claim/acceptance, real Step runtime and
registration storage, and the existing saved product/End/Off fences. It adds no testing access API.

Module graph: settings.gradle.kts -> tracker:engine -> tracker:api, tracker:control, core:base/common/model,
preferences, stats:api/engine and sensor:activity-api. Engine host tests already have core:testing,
Robolectric/MockK/Room test conventions and stats:data at runtime. No dependency addition is needed.
The engine manifest already declares optional step hardware and ACTIVITY_RECOGNITION/HEALTH FGS;
the controlled context exercises the existing Steps capability envelope with Location/radio hardware
and permissions unavailable. Physical Android approval and foreground promotion remain fixtures.

The Activity arbiter implementation is outside the engine's compilation API boundary, so this source
slice does not silently add sensor:activity or an app/Hilt dependency. Real system registration/receiver
and actual TrackerService lifecycle/device evidence remain pending rather than hidden by a fake pass.
