# All-day Steps permission repair source trace

## Scope and evidence boundary

Independent static review of committed `d2bff19a88ba5224c4dd2e883fd313163c4a17dc`, using explicit Git object reads. The original user messages and settled decisions require one all-day Steps choice, supported on-device providers, choices preserved when permissions are missing, an actionable repair, and hourly plus enable/open copies. The current priority is completing tracking architecture and the system; backup work is excluded.

IMPLEMENTATION_ONLY: no execution, compilation, tests, schema checks, device/provider activation, Git mutations or recursive delegation. Existing test source is NOT_RUN. This review does not close any full-chain assembly prerequisite.

## Confirmed blocker S1: LocalRecording motion repair is not reachable from the selected Steps choice

1. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsCapability.kt:129-139` selects LocalRecording and requires Activity Recognition when needed. Its capability selection does not require a direct step-counter sensor.
2. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/AmbientStepsPurposeOwner.kt:140-148,181-185` publishes the exact `LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED` reason. `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/SourcePurposeSettings.kt:323-324` maps it to the existing permission explanation.
3. `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt:220-229` launches motion permission only when someone enables Steps and `stepCounterAvailable` is true. A supported LocalRecording provider without that direct sensor cannot obtain its required permission through this route.
4. The existing Steps repair slot at `TrackingSettingsScreen.kt:838-853` accepts only the two Health Connect reasons. It is reused in basic settings at line787 and advanced settings at line959. Thus a person whose already-selected Steps loses motion permission has no Steps repair action, even when a direct sensor exists; the checkbox would have to be turned off and on. Turning Activity or automatic tracking on to reach its permission launcher changes unrelated choices.

This is a production wiring gap, not a request for another provider or a new settings screen.

## Smallest approved correction

Limit production authorship to `TrackingSettingsScreen.kt` unless the author finds a concrete prerequisite.

- Reuse the existing per-Steps repair slot for `LOCAL_RECORDING_ACTIVITY_PERMISSION_REQUIRED`; route that reason through the already-declared `stepsPermissionLauncher` and Android's Activity Recognition permission.
- The enable-time motion predicate should accept either a direct step-counter sensor or the current LocalRecording motion-permission requirement, while retaining the actual permission, SDK and resumed-lifecycle guards.
- Keep Health Connect read/background routing in the same repair slot. A routine private helper/callback rename may describe the expanded provider repair honestly; no new public interface, model, storage, dependency or selection mechanism is needed.
- Reuse the existing motion action copy, for example `setup_perm_grant_steps_content_description` ("Allow motion access for Steps"), rather than introducing new text or a new screen.
- Repair must not switch Steps, Activity or automatic tracking on, change the selected provider, or resume Turn off tracking. Recheck current Steps selection and current reason at click time.

The callback completion is already implemented. `TrackingSettingsViewModel.kt:507-514` calls `refreshStepsProviderPermissionState`; lines285-303 reconcile the current all-day owner even when the same policy was previously reconciled. They probe real grants rather than trusting the callback argument. They do not write participation choices or collection-resume intent. There is no need for another reconciliation service.

## Existing source paths that already connect

- Onboarding already uses the provider-aware motion predicate in `app/src/main/java/com/adsamcik/tracker/app/onboarding/data/SetupUiState.kt:81-86`. `onboarding/ui/steps/WhatToCollectStep.kt:265-281` provides the Steps-only motion action; lines284-305 route Health Connect's required Steps read grant. `SetupViewModel.kt:218-247` reprobes grants and preserves choices.
- Settings Health Connect routing at `TrackingSettingsScreen.kt:231-265` checks current selection/reason and resumed lifecycle, checks SDK availability, and requests the Steps read grant plus background read only when the feature exists. It uses the existing permission contract, with declared permissions and usage/rationale components in `app/src/main/AndroidManifest.xml:28-32,99-112`.
- `AmbientStepsCapability.kt:93-120` keeps Health Connect read permission separate from optional background read. Foreground-only access remains usable, and the owner explains optional background repair rather than claiming background access.
- Provider selection falls back to LocalRecording when Health Connect is not capable on the platform; a missing Health Connect read grant remains a required grant rather than silently selecting another provider. Neither supported provider yields an honest unavailable state. No new fallback rule is needed for S1.
- `AmbientStepsPurposeOwner.kt:88-100` schedules immediate copies on registration/rearm and cancels copy work when inactive. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/AmbientStepsImportWorker.kt:27-47` retains hourly unique work only for background-capable access.
- `TrackerModuleInitializer.kt:73-80,223-240` requests a bounded copy on process foreground/open. `AmbientStepsImportRunner.kt:39-52,65-105` reconciles actual current settings before import, checks capability and startup authority, limits an attempt to30seconds and preserves foreground-only access restrictions. Returning from external permission settings therefore has an existing real owner-refresh path; no additional lifecycle framework is justified by this trace.

These are static call-chain observations, not proof of actual Android permission dialogs, provider counting or WorkManager delivery.

## Focused test-source work

`app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt` currently covers Health Connect required-read repair (587-618), LocalRecording READY summary (621-647), optional background repair (650-685), and unsupported provider copy (688-715). It does not cover LocalRecording permission-required repair.

Author the missing meaningful cases using existing UI fixtures:

- Selected Steps with LocalRecording motion permission missing and no direct step sensor offers its motion repair and dispatches the existing repair callback, in both basic and advanced source settings.
- The enable-time route recognizes the same provider requirement without a direct sensor, retains the SDK/lifecycle/current-grant guards, and does not request unrelated permissions.
- A stale repair when Steps is no longer selected cannot start permission-driven collection. Keep the existing Health Connect callback assertions intact.

`TrackingSettingsViewModelTest.kt:586-735` already authors current-owner refresh with a previously complete policy, Activity/Steps grant callbacks, actual-grant re-probing, and preservation of Off/disabled choices. Reuse these cases; do not add tests that merely duplicate the existing callback implementation. No new Room/schema test is required for this routing correction.

## Exact input and mixed-draft note

Primary committed blob IDs:

| Path | Git blob at reviewed commit |
| --- | --- |
| `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt` | `3b49501cc21afe00b0f473d83104aeb2bd12b577` |
| `app/src/main/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModel.kt` | `cb4ac7de31a597d3a7b3f4a710ac2cfa3532a3b8` |
| `app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt` | `f5e1f1d8c1e2ee1ea847e177b3917cbe5ac5ff43` |
| `app/src/test/java/com/adsamcik/tracker/app/settings/TrackingSettingsViewModelTest.kt` | `a5a341c8b9af2bbe178978187b7ce68a00d211d2` |

A read-only path-limited comparison of those four paths plus `SetupUiState.kt` and `WhatToCollectStep.kt` against this commit showed no content diff when inspected. This is not permission to select unrelated dirty files. The author should freeze exact BASE/SELECTED inputs and any composed test prerequisite; do not import held manual-start, history, numeric or export drafts.

## Conclusion

One confirmed gap remains in this bounded product repair trace: S1. It can be completed with the existing Settings repair button and permission launcher, provider-aware routing, and current-choice owner reconciliation. No genuinely new design decision is needed for this routine completion of the approved repair behavior. All test execution, device evidence and overall readiness remain deferred.

