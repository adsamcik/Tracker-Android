# All-day Steps execution: source review

Status: **IMPLEMENTED_UNVALIDATED**. Reviewed candidate working tree over
`413d818263e4091f98ccf43e08d9c18b63a753a5`; this receipt is not a convergence freeze.
Main `dev/v10` was not changed by this slice.

## Approved behavior and implemented chain

- One Steps choice enables supported on-device collection outside sessions. Sessions may organize
  the data; they are not required for the native provider subscription or copies into Tracker.
- The accepted Steps purpose owner reuses the existing provider lifecycle and WorkManager binding.
  It installs unique hourly work with `KEEP`, requests a copy when enabled or rearmed, and cancels
  both work names when inactive or clearing collected data. Reconfiguration does not reset cadence.
- The application process lifecycle requests a copy on opening Tracker. The worker reconstructs
  startup and current settings authority before accessing the provider. A missing permission or
  unavailable provider defers rather than polling continuously or substituting another source.
- Each runner attempt has a cooperative 30-second timeout, a process mutex and at most 32 windows.
  The existing importer resumes its durable cursor, reads outside Room, and rechecks recording and
  data-lifecycle authority before committing counts, cursor and evidence together. Two bounded
  retries leave later hourly/open work to resume remaining progress.
- UTC observation times retain local-calendar structural days. Missing provider evidence remains
  a gap; provider windows retain real identity and existing count-domain deduplication. No count,
  sensor event, provider counter domain or calendar history is invented.
- A provider change drains the accepted predecessor before removing its subscription. Permission
  loss produces an honest gap; transient failures retain retirement debt for retry. Activation,
  removal and the handoff attempt are individually bounded.
- Health Connect background access is explicitly requested through the existing Settings repair
  action only where supported. Refusal preserves foreground-only use and its visible repair state.

## Exact authored production manifest

1. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsImportRunner.kt` (new)
2. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsFactImporter.kt`
3. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsProviderHandoffCoordinator.kt`
4. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsProviderRegistrationCoordinator.kt`
5. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/AmbientStepsPurposeOwner.kt`
6. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/module/TrackerModuleInitializer.kt`
7. `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/AmbientStepsImportWorker.kt` (new; worker and scheduler)
8. `app/src/main/AndroidManifest.xml`
9. `app/src/main/java/com/adsamcik/tracker/app/settings/tracking/TrackingSettingsScreen.kt` (shared with the product-recovery slice)
10. `app/src/main/res/values*/settings_recovery.xml` (the exact 28 paths are in
    `PRODUCT_RECOVERY_FILES.json`; the background-update label is localized there)

## Exact authored test manifest

1. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsImportRunnerTest.kt` (new)
2. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsFactImporterTest.kt`
3. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsProviderHandoffCoordinatorTest.kt`
4. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsProviderRegistrationCoordinatorTest.kt`
5. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/AmbientStepsPurposeOwnerTest.kt`
6. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/module/AmbientStepsForegroundImportTest.kt` (new)
7. `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/worker/AmbientStepsImportWorkerTest.kt` (new)
8. `app/src/test/java/com/adsamcik/tracker/app/settings/compose/TrackingSettingsScreenTest.kt` (shared with the product-recovery slice)

Focused scenarios cover hourly `KEEP` and enable/open scheduling, foreground-only access, provider
and preparation timeout, bounded retries, cancellation, real Room cursor progress without a session,
missing evidence, repeated copies, handoff ordering and retry, optional permission repair, and a
composed Worker → production Runner constructor → Room importer path. That composition covers a
fresh worker reusing retained progress, a stopped worker making no source/provider calls, and a stop
during a provider read withholding returned counts. Platform/startup/settings endpoints and native
provider responses are controlled test inputs; these are not Hilt or real-device results.

## Existing companion dispositions

`AmbientStepsProviderCleanupStore.kt` and `AmbientStepsProviderCleanupStoreTest.kt` in the same
production/test ambient Steps directories were already dirty before this slice. They were inspected
but not edited here. The new scheduling/handoff code uses the unchanged constructor and journal API
and works with the committed AtomicFile implementation; it does not require the preserved draft's
`java.nio` atomic-write replacement. Select that draft separately as a journal-reliability slice.

The existing `TrackingSettingsViewModel.refreshStepsProviderPermissionState` method and application
WorkManager/Hilt worker-factory wiring are reused. No new module or dependency was added.
The process observer uses the existing injected `DispatchersProvider.main` abstraction.

## Independent static review and evidence limits

Reviewer `/root/completion_requirements_0930` independently traced the scheduler, lazy database/Hilt
composition, worker preparation, current authority and permission checks, read/commit stop fences,
resume gaps and drain-before-retire wiring. After the composed test scenarios were authored, the
reviewer reported no definite new source correctness blocker.

The runner timeout is cooperative, not a measured hard wall-clock maximum. The existing publication
runtime dispatches owner callbacks in its independent application scope; the runner cancels its own
await, not an accepted owner operation. Existing noncancellable publication compensation has a
bounded two-second wait. Android may delay hourly or immediate WorkManager execution, and denied
Health Connect background access limits copies to foreground-capable execution.

No Gradle, compilation, Hilt generation, tests, lint, Detekt, schema generation/drift, `diff --check`,
CI, emulator/device, UI, battery, provider activation, release, push or integration gates were run.
The architectural worker scanner examines direct persistence markers and does not independently
prove this runner indirection; the composed scenario source closes that specific authorship gap.
Actual Hilt/WorkManager process recreation, native timing, device coverage and battery behavior
remain convergence/device evidence requirements. This receipt closes the Steps source slice only;
it does not declare the full six-source assembly or local `dev/v10` integration ready.
