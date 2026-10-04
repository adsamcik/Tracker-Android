# Automatic CONTROL owner source selection

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.
No compilation, test execution, lint, schema checks, devices, CI, provider activation, Git mutation or integration was performed. Written cases do not establish a pass. This is a source slice, not the complete convergence input or completion of any full-chain prerequisite.

## Revision R1

The original packet and rejected independent receipt remain unchanged in `AUTOMATIC_CONTROL_WIRING_SELECTED`. This revision restores only the existing `ActivityTransitionData` import still used by `BackgroundTrackingApi.handleTransitionUpdate`. All other selected source, tests and locale resource bytes remain exactly the original frozen input. No execution was added.

## Exact input

`FILES.json` pins 50 selected source paths: 12 production, 10 test and 28 locale resource files. `SELECTION.patch` is a HEAD-based delta. Original before bytes are retained in `WORKING_BEFORE`; committed reference bytes in `BASE`.

Primary base: `1056464a1376e3574f3a744a6eaf5e3402accaf7`. The later, unchanged existing Activity arbiter test uses the separately supplied `fa340240624710a5e2c77cd8883492515f4b6b39` base (`COLD_ARBITER_HEAD_BASE.json`). Root reported that unrelated commits did not change the earlier owned paths. Root must compose these exact deltas against its current committed input.

The only mixed production selection is `TrackingPurposePublicationRuntime.kt`: selected bytes include this automatic slice while excluding the preserved default-scope `DefaultDispatchersProvider` draft. The full working file is deliberately not the selection. `RoomSourcePolicyRepository.kt` remains unchanged and is pinned as an existing dependency. No pending manual Start/Off acceptance drafts, radio helpers, history UI, import, export, backup or provider activation are selected.

## Settled behavior implemented

- The existing runtime installs one built-in Activity CONTROL owner before its first reconciliation, using its existing callback/registry and a lazy provider. No new table, independent Activity backend, timer or owner framework.
- The callback obtains the actual settings, permission and provider state, awaits persisted lock restoration, stamps suppression and runs the existing bounded CONTROL cleanup. It activates the existing transition monitor only while its supplied exact lease is in flight. Ready publication follows accepted registration. Disabled transition detection never falls back to continuous samples.
- Temporary CONTROL remains separate from recorded Activity: **product-persistence=false and existing consent epochs are preserved**. Exact and observed publication gates permit a bound CONTROL execution under its own existing consent and supported rollout. Ambient product execution still requires product persistence.
- BackgroundTrackingApi consumes this owned publication and requests new reconciliation on changes/recovery. It no longer tries to arm the same lease after publication. An independently unavailable ambient source does not cancel already accepted automatic ownership. Existing trigger, suppression and live Android start-context checks remain.
- Loss of current authority retains the previous exact cleanup lease until compensation succeeds. A stale callback that never entered the guarded mutation does not retire a successor.
- Cold mode-disable and Off with no process-local lease invoke the monitor's existing disabled path through bounded cleanup. It retires only the automatic consumer and owner. It does not fabricate a lease or touch independent capture demand. The owner mutex serializes delayed negative cleanup with positive activation; a cleanup arriving after a new owned identity exists returns false without retiring it.
- Retirement requires **APPLIED and absence of the automatic owner**. DEGRADED is not accepted: the actual arbiter can expose no owner while physical removal is still pending. Repeated cleanup retains debt until acknowledged.
- Existing catalog metadata now reflects real Activity capability and the already-approved 48-hour CONTROL expiry. Ordinary CONTROL source history/export/writer contracts remain unsupported because it is trigger evidence, not recorded Activity.
- Settings expose concise permission/provider/transition recovery without internal codes. The transition repair retains the selected automatic mode. Four new text resources are authored in all 28 already-declared locale folders. Motion-detection copy makes no claim that an unsaved edit has been saved.

## Lock and ownership trace

`TrackerModuleInitializer` reconciles purposes before its later explicit LockManager initialization. The owner therefore awaits the same authoritative restore on every first entry, including direct settings/worker calls. `DefaultLockManager` now serializes initialization through its own mutex and publishes completion only after the persisted reads and restoration finish; concurrent callers cannot pass a default unlocked state. Its `refreshLockState` reaches the existing asynchronous/no-op watcher bridge and does not synchronously reenter this initialization.

The outer runtime may hold a ready-generation operation while callback work runs in its existing application scope. The monitor's guarded dispatcher uses current authority, receipts, Room/broker operations and startup predicates. It does not reacquire `withReadyGenerationOperation`. The arbiter's startup observer is launched separately, not awaited by callback construction. This slice adds no coroutine-context reentrancy mechanism.

Cold cleanup explicitly constructs the built-in owner/monitor/arbiter even when no lease was ever issued in this process. The actual arbiter's negative `clearDemand` hydrates the saved provider identity, fences its generation and awaits provider removal. The two new arbiter cases keep the startup observer queued so its background cleanup cannot substitute for this explicit negative operation.

## Authored evidence and limits

`CASE_INVENTORY.json` enumerates 24 newly authored test cases and existing assertion changes. The composed `AutomaticStepsControlRoomTest` uses real Room policy, current authority, lease publication, caller receipt, broker demand and projection registration with a fixture OS arbiter/device capability. It covers a Steps-only selection without Location or recorded Activity, lock restore, transition/permission/provider failures, cleanup retry, capture sibling preservation, cold cleanup, timed-out negative cleanup and stale successor protection. It does **not** claim real Android registration or a complete callback-to-service accepted Steps session.

The existing Activity arbiter Room fixture exercises actual saved provider-generation hydration, exact removal/retirement state and failed-removal debt, with a mocked Play Services backend. Other authored cases cover concurrent persisted lock initialization, policy/epoch preservation, availability, recovery classification and Compose repair binding.

Existing `ActivityControlRetentionMaintenanceTest` and `SourceEventStorageMaintenance` cover the existing retention implementation; no new retention algorithm is introduced here. The original 48-hour/reboot/shared-carrier fixtures are not newly executed. Actual sensor callbacks, cold Android foreground start, user-stop/system restrictions, process death, battery and on-device history behavior remain unvalidated.

Remaining full-chain assembly must include the actual callback-to-accepted-start/storage/UI path, all supported source combinations, the separate pending manual acceptance decision, coherent all-day/lifecycle composition and the final convergence gates. No all-25 completion, dev/v10 readiness, release or integration claim is made.
