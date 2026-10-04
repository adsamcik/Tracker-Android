# Independent source review: automatic CONTROL wiring

Result: **not accepted as frozen; repair R1 below**. IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Original user messages, approved independent tracking, existing Activity ownership, separate temporary CONTROL, 48-hour expiry, disabled-transition repair, lock/user-stop fencing and simplicity requirements were considered. Latest priority remains the tracking system. No source changes, Git mutations, recursive delegation, compilation/tests/schema checks or provider/device activation were performed.

## Exact input

- Primary BASE: `1056464a1376e3574f3a744a6eaf5e3402accaf7`; Activity arbiter test BASE: `fa340240624710a5e2c77cd8883492515f4b6b39`.
- FILES.json: `03e06afed82f108e3fad110d9b750f5cfa2fb9b400c91dd8d06876934b1b8a58`.
- SELECTION.patch: `71bc509ba4395afefcb0bf0e7bddc19a587bba3a703d1202cbcb7cb10cbf7d9f`.
- SOURCE_BOUNDARY.md: `a144928a95c68f155cae007c8bf97d9620999edc4a17892ed4dc57adbf1b93bf`.
- CASE_INVENTORY.json: `eb31a3ed1ecc9d0d42f89b2147c60b62a6c868572c910b1969bb1ac65dc3793d`.

All available BASE/WORKING_BEFORE/SELECTED manifest hashes match for the exact 50 selected paths: 12 production, 10 test and 28 locale resource files. Acceptance inspection used SELECTED, not mixed working runtime bytes. The preserved default-dispatcher and pending manual acceptance changes are not part of this selection.

## R1 — a required existing type import was removed

SELECTED BackgroundTrackingApi.kt removes `import com.adsamcik.tracker.activity.ActivityTransitionData`, but still uses the unqualified type in handleTransitionUpdate:273, its construction in handleDurableActivityEvidence:371, matches:1327 and selectLatestMatchingTransition:1346–1353. No corresponding declaration or import exists in its package. This is a concrete unresolved source dependency, found statically; no compiler was run.

Restore that existing import. No new behavior or architecture is required. Preserve this rejected packet and repin a separate import-only repair for recheck.

## Existing architecture blockers assessed

- **A1 / real owner:** DefaultTrackingPurposePublicationRuntime:610–614 now installs the built-in automatic owner before reconciliation. ensureBuiltInAutomaticOwner:843–863 records one existing callback and bound execution through a lazy Provider. The injected owner uses existing monitor/arbiter/broker paths. There is no additional physical backend, timer, table or owner framework.
- **A2 / flight authority:** DefaultAutomaticControlPurposeOwner obtains actual saved settings and state, awaits the shared persisted lock restore, stamps the existing suppression epoch, and runs the bounded CONTROL retention pass. It calls the existing monitor under the supplied exact in-flight lease. The monitor dispatches authenticated CONTROL demand and registers the existing Activity arbiter before returning an accepted state. Publication then passes existing owner/startup/authority checks and completes the flight (runtime:1215–1279). BackgroundTrackingApi requests reconciliation and consumes its owned result rather than invoking the monitor again after publication. Stale callers that never enter the mutation return BLOCKED without removing a successor.
- **A3 / temporary CONTROL:** exact and observed execution readers now allow supported bound CONTROL under its non-null existing consent without requiring ordinary product persistence. RoomSourcePolicyRepository remains unchanged and CONTROL persistence stays false. Ambient retained execution still requires persistence; no consent migration or new grant is introduced.
- **A4 / honest capability:** CONTROL availability uses the existing Activity permission/provider reader and describes its existing 48-hour retention. Its normal product writer/history/export remain unsupported. Settings preserve choices and offer a real transition repair with concise copy instead of internal reason codes. New reasons reflect actual failures rather than unconditional Ready.

## Cleanup and composition assessment

Authority loss preserves the previous exact cleanup lease until compensation succeeds. Rejected refresh invokes exact compensation or the built-in cold negative path. Cleanup stays bounded and serialized through existing operation/owner mutexes and flight tracking; unowned cleanup refuses to remove a later owned identity. Retirement requires APPLIED plus absence of the automatic owner, preserving debt when the real arbiter exposes DEGRADED with physical removal pending.

The existing arbiter's negative clear path hydrates its persisted generation, persists retirement, removes the exact provider handle and acknowledges it. The two new actual Room arbiter cases keep its asynchronous observer queued so that observer cannot substitute for the explicit negative operation. Off's actual DefaultTrackingTurnOffController first fences purposes and invokes closeForUserStop; BackgroundTrackingApi's later cancellation/legacy cleanup is not the physical retirement authority. Captured Activity sibling handling remains on the shared arbiter.

DefaultLockManager serializes the first persisted restore and publishes initialization only after the saved values, alarm restoration and lock state have been applied. The owner therefore does not pass startup's initial unlocked defaults. No new lock abstraction or synchronous reentry is introduced. The lazy injected provider preserves the existing Hilt ownership seam; Hilt/compilation are deferred.

## Authored source evidence and limits

The 14 AutomaticStepsControlRoomTest cases use real Room policy, exact authority, lease issuer/publication, accepted caller receipts, broker and projection registration, with a fixture arbiter/provider and mocked lock/retention dependencies. They cover Steps-only capture intent, in-flight activation, permission/provider/transition failures, lock stamping, revocation/Off, acknowledged retry, cold negative cleanup, timeout and stale successor fencing. They do not establish real Android registration or an accepted automatic Steps session through callback, service, storage and UI.

Remaining authored cases cover actual arbiter persisted-handle cleanup, concurrent lock initialization, policy epoch/persistence preservation, API result classification and settings/Compose repair. All 24 new cases and adjusted existing assertions are NOT_RUN. Existing 48-hour/reboot/shared-carrier retention test source remains the appropriate evidence seam; this slice adds no retention algorithm. Locale resources have the same four keys in all 28 selected folders and are structurally readable XML; translation/native UI quality has not been evaluated.

Apart from R1, no additional concrete blocker was found within this selected owner/wiring slice. This does not close automatic callback-to-start or all source combinations, source lifecycle/device evidence, the separate manual acceptance work, or any of the 25 full-chain prerequisites. No assembly, dev/v10 integration or release readiness is claimed.
