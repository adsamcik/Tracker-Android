# Independent all-day Steps permission review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewer:
`/root/fresh_bundle_context_review_0930`. Original task/follow-ups/approved decisions
were inherited. Static source inspection only; no production edits, Git, gates,
compiler, tests, providers/device activation or recursive delegation.

## Result

No remaining actionable defect was found in the final three-path selection. It is
coherent for an **unvalidated implementation commit only**. The source preserves
one all-day Steps choice and fixes permission recovery without new UI, storage,
API, dependency, provider owner or settings mutation.

The initial two-path freeze correctly repaired `onStepsPermissionResult`, but left
the same permission granted through Activity, automatic start or Location motion
assistance outside the refresh. This concrete finding is **statically closed**:

- `TrackingSettingsViewModel.kt:507–515` uses the existing Activity permission
  callback as the shared completion: current permission/UI refresh, generic policy
  retry, then existing Steps purpose-owner refresh. Steps delegates to it.
- `onAutoTrackingPermissionResult` retains requested-mode validation and delegates
  without restoring the requested mode or invoking an enabling setter.
- `TrackingSettingsScreen.kt:101–104` binds the actual Location motion-assistance
  launcher to that same callback. Activity, Steps and automatic launchers retain
  their existing bindings. Wi-Fi/Cell callbacks are unchanged.
- Owner refresh was correctly kept outside `refreshPermissionState`, which is also
  called when owner refresh completes; no recursive refresh loop was introduced.

## Concrete production trace

`AuthoritativeTrackingParamsRepository.kt:482–488` returns cached Complete for an
unchanged policy/startup generation. That retry alone cannot rearm a previously
permission-required Steps owner. The selected callback additionally invokes the
existing `refreshStepsProviderPermissionState` (`TrackingSettingsViewModel.kt:286`),
whose production binding is `DefaultTrackingPurposePublicationRuntime` through
`TrackingPurposePublicationModule.bindSettingsReconciler`.

The runtime reuses current settings/retention/startup authority and its existing
serialized, shared owner-flight path. `builtInStepsCallback` reaches
`DefaultAmbientStepsPurposeOwner.reconcile`, which reprobes through the existing
lifecycle/demand/registration boundary. Inactive ownership cancels immediate and
hourly work; an active/rearmed registration requests the existing immediate copy
and supported background schedule. `AmbientStepsImportWorkScheduler` keeps unique
hourly work with KEEP; no additional polling or budget reset was added. The
existing runner still bounds attempts to 30 seconds and 32 provider windows.

Permission callback booleans do not become permission authority or recording
intent. Current choices and the existing startup/collection-stop gate govern the
owner; the callback never calls `resumeCollectionAfterUserExit`, changes consent,
enables Steps/Activity, or restores an automatic mode. Existing cancellation
propagation and visible owner-refresh failure handling remain intact. The existing
app/engine/contract dependencies and Hilt binding already supply this path.

## Authored source coverage and limits

Six added ViewModel methods contain eight callback scenarios: unchanged-policy
cached Complete; actual permission still missing despite a stale grant result;
disabled Steps; actual ViewModel Turn off followed by a late grant; Activity/Auto
granting already-selected Steps; and both adjacent callbacks preserving disabled
Steps plus pending Turn off. Assertions observe the actual callback, current
capability/choices, shared owner invocation and absence of enabling/resume calls.
The original four cases and two added adjacent-callback methods were inspected.

These use controlled Flow/coordinator/lifecycle fixtures. They do not execute the
production provider, scheduler, Hilt screen or Android ActivityResult delivery.
Location launcher wiring has static source evidence only. Existing hourly/open
copy, count-domain importer, cancellation, retained history and daily statistics
contracts were traced as dependencies, not claimed to pass from these VM cases.
All authored cases remain unexecuted; full Steps/provider/assembly/device evidence
and all 25 convergence chains remain open.

## Exact final pins

Base `eea8e76ac393c65ea7040bd2bd55dbeb51c3e6b8`. Independently verified actual,
SELECTED, HEAD and WORKING_BEFORE bytes for all three paths against `FILES.json`.
The earlier two-path packet remains preserved in `PRE_SHARED_PERMISSION_REPAIR`.

| Exact owned repository path | Actual/selected SHA-256 |
| --- | --- |
| app/src/main/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModel.kt | `6e0145339b1a645b3cdf4da05121a9e99e3970c91ae33711e44fddb63eccb59f` |
| app/src/test/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModelTest.kt | `523e14488e326bfbba51c861883540d9534b26dad6bad77a3e52f4f29e92a637` |
| app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt | `1d7e42930d0c04093b81c945151df2276aa8ac72c80a8af81851db7c9925918f` |

- Manifest: `43ceebc66d8f6c6aa879aea64864719c09ad1faa24f43645ff1d61cd1206dd49`.
- Selection patch: `058fc74350aa179d2d9332d33771cf097107f1d510c6e0bdee7c4f6696595bca`.
- Boundary: `f45722f66d12db595f3f26730e1eb0b2f1be2f27102b366509b745016c117edf`.

Acceptance is limited to this source packet; no held radio/export/History changes
or full-chain completion are accepted by this review.
