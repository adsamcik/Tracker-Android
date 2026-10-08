# Retained native portable V1 source findings

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Independent review only; all ten authored methods NOT_RUN. No compilation, tests, schema, lint, providers, device, CI or Git mutations. All 25 full-chain prerequisites and T25 remain open.

## Exact input

Packet `RETAINED_NATIVE_PORTABLE_SELECTED`, base `f3b265d943af7973ee9aa781a8cac33ebb53460c`; reviewed current commit `3a3476e63e5c3bc40e2697f5e503a94cb18592ed`.

- FILES `89f06f692193377cb48ea62f2e8c44f937de0650a0338ca920062f24787c824a`
- patch `ddcea8bf33b628a71ea3f30591cf82721b902c6815e97222ba26e325be005f66`
- dependencies `fc882ce19ad800c8ce6421329226cdeb550de9ae8cc3f06525128e02b2d48911`

All seven selected LF files, BASE and raw WORKING_BEFORE bytes match the manifest. All 35 exact committed and saved dependency pins also match current committed HEAD. This is source identity checking, not executable validation. Mixed live source was not used as authority for dependency semantics.

## Concrete blockers

1. `WifiCapturedFactMaintenance.kt:3465` adds mandatory `WifiPortableAuthoritySnapshot.savedSessionCutoff`. The existing committed `WifiHistoricalBalanceContractTest.kt:61` constructor supplies no such argument. Its exact additional source pin is `fb2d0acf16b624b01fad51f0cf36d9cf30f7c637dcf35877acdb18b5e71ab33b`. Smallest repair: explicitly provide `SavedSessionCutoff(null, null)` in this pure historical-demand fixture and select that eighth source path. Do not introduce an implicit production default that could mask a missing cutoff read.

2. The new Cell positive case at selected `CellHistoryRepositoryRoomTest.kt:401-403` references `BuiltRun.actions` and `BuiltRun.admissions`, but `BuiltRun` at :2688-2703 defines neither property. The historical cases already in BASE at selected :624/:626/:643 contain the same nonexistent references. Also `persist` at :1909-1957 inserts no lifecycle actions. Thus the new negative action UPDATE at :415 affects no real fixture action rows; the actual native Cell portable/maintenance path does not read lifecycle actions. That assertion cannot establish its claimed original-action refusal.

Use existing real stored authority to repair the Cell source without adding a new production proof requirement: retain `run.demands` through `sourceBrokerDao().demandsByIds`, and original WAL from `sourceEventWalDao().getByEventId` for distinct `run.facts.sourceEventId` (the existing `walFor(fact)` constructs the saved values). Change only the exact capture demand's real `source_demand.lifecycle_lease_generation` while its original authorization/WAL remain unchanged. Existing `CellCapturedFactMaintenance.kt:1558-1601` matches the original member to fact and recomputes the authorization from those demands, so the mismatch is a reached existing rejection. Restore that exact stored demand before the inherited subsequent fence case. Rename source claims/case inventory to demand/receipt rather than actions. Root explicitly authorized this focused repair; no new action architecture is recommended.

## Remaining source trace

No additional concrete production blocker was found in the bounded retained guard change after tracing the complete changed predicates and their callers. This does not accept the broken V1 composition.

Activity keeps immutable WAL integrity, source/member/manifest identity and exact authorization generation; accepted actions now match the original manifest/provider clock and are coupled to the same original authorization generation/boot. Positive original generations are bounded by the current owning run using the committed helper. Capture closing authorization still refuses original observations at/after its elapsed boundary.

Cell keeps the original carrier/fact/authorization/demand checks, named prepared-manifest provider clock, logical hierarchy, complete native audit, retention floor/aggregate ownership and exact source fences. The current source does not authenticate Cell lifecycle actions in this reader; review and fixture claims must describe the actual demand/WAL authority rather than invent that requirement.

Wi-Fi's saved cutoff is loaded once on its existing private portable snapshot; nonportable maintenance reuses the existing indexed original Stop helper. No latest-operation boot is substituted for a missing Stop clock. Exact original demand/snapshot/item/action/manifest/sequence checks remain, as do provider acceptance and closing authority. Current session/run pairing, current replacement action generations and shared retirement operation-generation equality remain unchanged. No physical acquisition or new command is authorized by these readers.

The approved original saved-data rule is executed with existing helpers. No schema, file bytes, store, facade, dependency, UI, source selection or new permission decision is added. Three later-operation positives invoke actual Room exporters/native readers with sinks outside Room, but later operation metadata is constructed on already closed fixtures. They are not real coordinator, provider, End, reboot, process death or battery evidence. Fence/retention/partial states preserve existing whole-entry format behavior; the individual-reading format and complete exports remain outside this slice.

`ADAPTER_BACKLOG_TRACE.md` and `ADAPTER_REPAIR_SCOPE.md` correctly leave actual Activity/Wi-Fi/Cell saved-WAL drain guards open. This retained read slice does not fix those adapters, prove provider-to-product flow or complete any full-chain prerequisite. Pressure/provider wall chronology remains a separate disclosed trace.

## Disposition

V1 is held/rejected for the concrete source composition and Cell fixture findings. Preserve V1, author the focused V2 repair, and independently recheck its exact bytes before selection. All executable gates remain deferred.
