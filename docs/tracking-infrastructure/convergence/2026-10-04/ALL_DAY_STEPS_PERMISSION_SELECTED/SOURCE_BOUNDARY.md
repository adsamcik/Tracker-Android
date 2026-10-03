# All-day Steps permission continuation source boundary

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.
Immutable base: `eea8e76ac393c65ea7040bd2bd55dbeb51c3e6b8`.

## Approved behavior and scoped repair

One Steps choice authorizes supported all-day collection without a session. Hourly Android background copies and enable/open copies use the existing system provider, 30-second attempt and retained count-domain importer. Permission denial preserves the saved choice; a late callback never enables a disabled source or acknowledges Turn off.

The Steps motion-permission callback previously retried only source-policy reconciliation. `AuthoritativeTrackingParamsRepository.reconcilePolicyRevisionAtReadyGeneration` returns cached Complete for an unchanged policy/startup generation, even when its earlier owner result was permission-required. `DefaultAmbientStepsPurposeOwner` cancels import work for that inactive result. The callback could therefore leave no registration, immediate copy or hourly copy until a later app open or other reconciliation.

`TrackingSettingsViewModel.onActivityPermissionResult` now also calls the existing `refreshStepsProviderPermissionState`, already used after Health Connect permission. Steps and automatic-start permission callbacks delegate to that same motion-permission completion; automatic requested-mode validation and saved-choice fencing remain unchanged. The Location motion-assistance ActivityResult launcher binds that same completion. It reads current capability through the existing purpose owner, which owns registration and scheduling. The platform callback boolean remains descriptive; it never becomes recording intent, a source-policy change or a collection-resume command.

## Static full-chain trace

- Single selection: `TrackingSettingsScreen.onStepsEnabledChanged` -> `TrackingSettingsViewModel.setStepsEnabled` -> authoritative/default preference repositories mirror the compatibility field to the single released toggle.
- Permission callbacks: Activity/Steps/automatic/Location motion assistance -> `onActivityPermissionResult` -> current permission/UI refresh + policy-debt retry + existing purpose-owner refresh. `TrackingPurposePublicationRuntime.builtInStepsCallback` invokes the same Steps lifecycle owner; no cached policy-only shortcut substitutes for this call.
- Active registration: `DefaultAmbientStepsPurposeOwner.updateImportWork` requests immediate work on new/rearmed registrations and hourly work only with supported background access. Inactive/retired owners cancel both unique workers.
- App open: `TrackerModuleInitializer.registerAmbientStepsForegroundImports` requests one immediate worker on ProcessLifecycle onStart, independently of other source repair debt.
- Worker: `AmbientStepsImportWorker` -> production `AmbientStepsImportRunner` prepares current startup/settings, checks permission per window, captures one local zone/calendar boundary and bounds each attempt to 30 seconds and 32 windows. Retry is finite; no second polling pipeline.
- Provider: Health Connect uses current-device aggregate origin where supported; Google Local Recording uses its subscribed on-device exact aggregate window. Missing evidence stays missing rather than zero. Unsupported/foreground-only capability is published honestly.
- Storage: `AmbientStepsFactImporter` checks live collection admission and original authority before reads and commits, resumes durable cursors, records actual provider windows/count-domain identity, and retains explicit permission/zone/retention/resume gaps. Stop during read cannot persist returned counts; resume skips the stopped interval.
- Daily product: retained `DefaultAmbientStepsHistoryRepository` and `RoomStepsNumericSummaryRepository` supply calendar/history, Dashboard goal progress, and weekly Steps statistics without requiring legacy trips. Partial coverage is represented as Partial; unrelated native session counts are not added to an authoritative all-day provider total.

## Authored cases and evidence boundary

Six added test methods cover eight actual ViewModel callback scenarios:
1. Unchanged policy reports cached Complete while a newly granted permission still invokes the shared owner refresh.
2. A stale grant callback rechecks a missing/revoked actual permission and preserves the selected Steps choice and permission-required state.
3. A late grant preserves a disabled Steps choice and never writes an enabling setting.
4. The actual ViewModel Turn off path precedes a late grant; owner reconciliation sees the pending stop and no resume CAS is issued.
5. Activity and automatic-start permission grants each refresh already selected Steps without rewriting Activity, Steps or a saved automatic Off choice.
6. Both adjacent motion-permission callbacks preserve disabled Steps and a pending Turn off, without issuing a resume CAS.

These are controlled ViewModel/Flow/coordinator/lifecycle fixtures, not native provider or Hilt/device execution. Existing composed Worker -> production Runner -> Room importer source cases cover cold sessionless cursor reuse, stopped-worker no-read and Stop during provider read; existing scheduler cases cover hourly KEEP and foreground-only behavior. None was executed for this packet.

## Exact selection and exclusions

Only the three paths in `FILES.json` are owned. All had no source delta from the immutable base when reserved. `HEAD` and `WORKING_BEFORE` preserve exact bytes; `SELECTED` and `SELECTION.patch` select only this repair. Other existing source drafts, the thirteen measured-radio paths, nine exact Steps export paths and pending radio representation are untouched. No public API, UI control/copy, schema, dependency, provider activation, validation, Git mutation or dev/v10 integration occurs.

The previous two-path freeze is preserved under `PRE_SHARED_PERMISSION_REPAIR`. The content-only Compose fixture does not inject the Hilt screen or dispatch real ActivityResult callbacks, so the Location launcher binding has static source evidence only. Provider-specific permission-repair UX is unchanged. Android scheduling delay, real provider lifecycle/cancellation, Hilt/Room wiring and all source/device evidence remain convergence obligations. This coherent callback slice does not establish full Steps or all-25 implementation completion.
