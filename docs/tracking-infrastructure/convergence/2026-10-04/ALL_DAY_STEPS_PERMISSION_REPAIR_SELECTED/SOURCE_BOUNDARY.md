# All-day Steps permission repair — selected source

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Base: `d2bff19a88ba5224c4dd2e883fd313163c4a17dc`. Production and test source authored only; no compilation, tests, Gradle, lint, schema checks, device/provider activation, CI, Git mutation or integration occurred. Every listed test is **NOT_RUN**. This is not a complete assembly or device-readiness claim.

## Approved requirement and bounded scope

The user approved one all-day Steps choice, supported on-device providers, preserving unavailable selected sources with actionable permission repair, and reusing the existing owner when permissions change. The current priority is tracking architecture. The independent `ALL_DAY_STEPS_PERMISSION_REPAIR_SOURCE_TRACE.md` identifies the missing LocalRecording permission route; parent assigned its routine repair without a new provider, interface, state, permission, storage or UX text.

Only these source paths are selected:

- `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt`
- `app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt`

Both working inputs matched the exact committed BASE bytes at preservation. `WORKING_BEFORE` preserves them; `BASE` records committed input; `SELECTED` records authored output. Other working drafts and the earlier `ALL_DAY_STEPS_PERMISSION_SELECTED` packet are unchanged.

## Implementation

- Expand the existing Steps repair slot to `LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED`, in both basic and advanced Settings. Rename its internal Health-Connect-only callback/helper to describe provider permission repair.
- Reuse `stepsPermissionLauncher` and Android Activity Recognition for that current reason. On each click, read the current ViewModel state and require selected Steps, current provider reason, Android Q+, resumed lifecycle and a genuinely missing current motion grant.
- The enable callback uses the same small same-file internal predicate. A direct sensor **or** the current LocalRecording motion requirement can need motion access. It rechecks the current Steps selection after the settings mutation; cached UI permission values do not override the real Context permission probe.
- Health Connect retains its required-read and optional-background routes, SDK capability handling, current selection/reason/resumed guards, permission set and existing owner-refresh failure handling.
- Reuse the existing “Allow motion access for Steps” string for visible and accessible action text. Motion repair failure reuses the existing generic “Could not check the step provider. Try again later.” string. Existing Health Connect failure copy stays specific to Health Connect. No resources changed.
- Permission completion continues through existing `onStepsPermissionResult` → actual grant re-probe → `refreshStepsProviderPermissionState` → current purpose owner. The UI routing does not modify Activity/automatic tracking choices, select a provider, resume Turn off tracking, or authorize collection.

## Authored test-source cases

Ten new cases use the existing Compose test fixture and the same internal predicate used by the production launch sites:

1. Provider-only Steps motion repair in basic Settings; accessible text; no Steps/Activity/automatic choice mutation.
2. Provider-only Steps motion repair in advanced Settings; no frequency mutation.
3. Deselecting the unified Steps choice removes its motion repair and fails the current-choice predicate.
4. Changing the provider requirement replaces motion repair with the existing Health Connect repair.
5. Repair dispatch while tracking is Off leaves the supplied Off state and source choices intact.
6. LocalRecording repair failure uses the existing generic provider error and retains the repair action.
7. Enable-time predicate recognizes either the direct sensor or LocalRecording without requiring Activity/automatic selection.
8. Android Q boundary, resumed lifecycle, current selection and real-grant guards.
9. Current grant wins over both stale cached UI permission states.
10. Unsupported and reconciliation-pending provider states without a direct sensor do not request motion permission.

Existing required Health Connect repair, optional background repair, unsupported-provider copy and LocalRecording readiness cases are retained; only their internal callback references are renamed when applicable. No Hilt, Android permission-dialog, real provider, sensor or worker execution is represented by these source fixtures.

## Existing prerequisites, preserved rather than selected

`DEPENDENCY_INPUTS.json` pins exact committed prerequisite snapshots at the BASE commit. The unchanged ViewModel test source supplies the already-authored current-owner refresh, actual-grant re-probe, unrelated-choice preservation and Off fencing cases. The unchanged provider capability/owner source supplies the supported LocalRecording reason and permission semantics. These source paths are read-only dependencies, not selected edits.

No missing source companion or held draft is required for this two-path repair. This claim is bounded to its source selection; compilation, Compose execution, real permission denial/grant/revocation and Android provider behavior remain deferred. Assembly checklists remain open.
