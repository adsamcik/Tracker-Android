# Independent review: all-day Steps permission repair

## Result

**ACCEPTED_SOURCE_ONLY.** The selected two-path repair closes the LocalRecording permission-routing gap identified in `ALL_DAY_STEPS_PERMISSION_REPAIR_SOURCE_TRACE.md`. No confirmed source blocker, unapproved design change or missing held companion was found in this bounded slice.

Review used committed BASE `d2bff19a88ba5224c4dd2e883fd313163c4a17dc`, exact SELECTED snapshots, the selection patch and 18 frozen committed dependency snapshots. This review did not alter production/test source. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED:** no compilation, tests, Gradle, lint, schema checks, device/provider activation, Git mutation or integration occurred. Ten new and 51 total selected test methods are **NOT_RUN**. All 25 full-chain prerequisites remain open.

## Exact inputs

| Artifact | SHA256 |
| --- | --- |
| FILES.json | c351d7d82c464a61f77a83cece4452cff2f729303aa3a5f0e69fba3bf6bfb7ea |
| SELECTION.patch | 0d63e2b58ce0d12b8de5c8b06cb9ff4e73ce0121f81f4a0b7560a43f7a1bc506 |
| SOURCE_BOUNDARY.md | 91f296260d76539d6383e6a9cf5d6c8ddb9a171cfaa127c7883f86a8b67a4fb1 |
| DEPENDENCY_INPUTS.json | cba7ab2f85059e71671f18af1e56c986cd869fb681ca6fde471810e81c21aa64 |
| SELECTED/app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt | 9e7df2287dff60903eb6e6febd26736003f66099304a0cf1727b9784ab58111c |
| SELECTED/app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt | 2428754faaec9bc24a76154acb7a4c2d2c4884d1680345fcf73f47f3bb2592ac |

The two BASE/SELECTED source pins and all 18 dependency snapshot SHA256 values match their manifests. These are exact-byte source identity checks, not validation-gate results. The initial working mismatches recorded by the author are not acceptance inputs. In particular, resource use was checked against committed English and Czech snapshots; unrelated held backup text is excluded.

## Requirement and code trace

The user's approved choices are one all-day Steps switch, the supported on-device provider, preserving unavailable selected choices with a clear repair, and current-choice owner refresh after permission changes. The fix reuses the existing Settings repair button, motion permission launcher, current provider reason and already committed owner callback.

1. **No direct counter required for LocalRecording.** Selected `TrackingSettingsScreen.kt:223–233` replaces the direct-sensor-only enable guard with the same internal predicate used by the repair route. The predicate at `889–897` accepts either a direct counter or the current LocalRecording Activity-permission requirement. It still requires current selected Steps, Android Q or later, a resumed screen and an actually missing motion grant.
2. **Real permission wins over stale UI state.** Both launch sites pass `context.hasActivityPermission`, not cached `activityPermissionGranted`. Frozen `ContextExtensions.kt:211–214` probes the actual Android permission and treats pre-Q as granted. The cached field cannot suppress a needed prompt or cause an unnecessary prompt after a grant.
3. **Repair is connected in both Settings forms.** The existing basic slot at `TrackingSettingsScreen.kt:799` and advanced slot at `992` invoke the shared repair control. `849–886` now includes LocalRecording's required permission with existing visible/accessibility motion copy. Selected-source and current-provider conditions keep an obsolete repair from launching the wrong provider's request.
4. **Health Connect behavior is preserved.** The selected callback at `235–283` dispatches LocalRecording to the motion launcher and keeps Health Connect's existing SDK check, required Steps read permission, optional background-read feature check, lifecycle guard and failure re-probe. The callback rename is consistently applied to all actual committed call sites found in application/module source; no external old named argument was found.
5. **Permission completion uses the current shared owner.** The existing launcher at `TrackingSettingsScreen.kt:169–172` calls the unchanged `onStepsPermissionResult`. Frozen `TrackingSettingsViewModel.kt:507–524` re-probes real capability and refreshes the existing Steps purpose owner. Its `285–303` reconciliation uses current settings, reports repair debt and preserves cancellation. No new provider, sensor owner, consent, persisted state or public interface is introduced.
6. **Choice and user-stop fencing remain intact.** The unchanged ViewModel's saved mutation generation guard at `337–358` suppresses superseded enable callbacks. Permission callbacks do not rewrite Steps, Activity or automatic choices or acknowledge Turn off. Frozen current-owner tests cover stale result, disabled Steps and a permission completion after Turn off.

The selected UI changes contain no database, provider selection, worker cadence, notification, Start continuation or backup logic. Provider selection and hourly/enable/open copies remain existing committed prerequisites, not claims newly proved by this repair.

## Test-source review

The ten new methods at `TrackingSettingsScreenTest.kt:655–876` use the existing real `TrackingSettingsContent` Compose fixture, with a LocalRecording permission requirement and `stepCounterAvailable=false`. Their assertions cover both existing Settings modes, accessible motion action, unchanged source/automatic choices, deselection and provider-reason replacement, Off preservation, repair-error action retention, Android/lifecycle/current-selection guards and both stale cached-grant directions.

The `Step count` checked-state assertion targets the existing toggleable Settings row; committed `SettingsComponents.kt:171–207` supplies its switch role and checked semantics. New imports and dependencies follow the existing application test convention. Existing Health Connect required/optional repair and unsupported/readiness cases remain present. Unchanged ViewModel test source at `588–803` supplies actual current-grant/current-owner and late-callback/Off fixture coverage.

These fixtures do not launch the real Android permission dialog, execute LocalRecording/Health Connect, prove Compose tests pass, or establish all-day device readiness. No extra production/test change is required for narrow source acceptance.
