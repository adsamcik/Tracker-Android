# Clear-all transfer quiescence: source trace and smallest repair

Date: 2026-10-03. Independent read-only inspection. **IMPLEMENTED_UNVALIDATED**.
This is a repair proposal and source finding, not approval of completed cleanup. No gates, Git,
providers, device work or production edits were performed.

## Confirmed gap

The approved behavior is to cancel ongoing manual, scheduled and import transfers, physically
await their file closure and cancellation cleanup, then clear app-owned files and prevent old
work from publishing into the new data epoch.

- `app/.../settings/CollectedDataDeletionService.kt:275,287,310` cancels import/export work but
  awaits only WorkManager's cancellation `Operation`. This does not join the admitted coroutine
  body, its output stream, or its `NonCancellable` partial-file cleanup.
- `feature/import-export/.../exporter/activity/ImportExportViewModel.kt:78-154` has no startup
  generation admission or registered transfer job. Its manual output creation, `stream.use`
  closure and noncancellable partial-file deletion can overlap Clear all.
- `CollectedDataDeletionService.kt:560,586,597,627,1268` already provides the correct reusable
  order: close admission; provider fences and startup quiescence; durable epoch/deletion; owned
  transfer-file clear; eventual reopen. Do not replace this journal or move file deletion ahead
  of actual quiescence.
- `OwnedTransferFiles.kt:19,71-91` explicitly requires stopped import/export writers and clears
  `sharable`, spool, export, import staging and Location index directories. Its physical file
  cleanup cannot establish writer quiescence itself.

## Reuse the existing startup boundary

The existing public contract is declared in
`core/common/.../startup/TrackingStartupGate.kt:43-56`; `core/base/build.gradle.kts:39` exports it.
The app's existing singleton binding is `app/.../di/StartupModule.kt:22-27`. The affected features
already depend on these modules. No new controller, module, database table or Hilt binding is
necessary.

Add one narrow, structured **transfer-operation method/overload** to this existing contract.
Use `TrackingStartupDeletionBarrier`'s existing `admissionMonitor` and generation, with a set
containing only the dedicated child jobs of admitted transfers. This is an implementation of
the already approved cancel-and-wait behavior, with no new user-visible decision or transfer
framework. Exact naming remains the implementation author's choice.

Required semantics:

1. Reconcile readiness before admitting a worker's transfer body. Register a dedicated structured
   child job atomically with the existing open-generation/readiness check, before its first
   source read, temporary index, folder creation or output. Do not register a ViewModel's whole
   scope, the application scope, a provider job or the startup recovery flight.
2. Execute admitted I/O without holding `startupRecoveryMutex` or the handled-stop mutex.
   `DefaultTrackingStartupGate.kt:318-356` currently holds both around
   `withReadyGenerationOperation`; wrapping long transfers in that control-plane method would
   serialize arbitrary I/O with startup and Stop. Keep that existing method's behavior intact.
3. `beginCloseAdmission` (`DefaultTrackingStartupGate.kt:185-190`) must atomically mark closing
   and snapshot the admitted transfer jobs. Cancel that snapshot **outside** `admissionMonitor`
   so cancellation handlers cannot reenter a held monitor. The closing flag rejects new jobs,
   including a delayed caller that captured the generation just before closure.
4. `awaitQuiescence` (`:216-225`) must join those actual jobs, including structured descendants,
   output `use` closure and `NonCancellable` cleanup, then finish the existing startup quiescence
   protocol. Remove a registration only on actual job completion, not at exporter-result return
   or before cleanup. A nested transfer context may reuse its outer registration.
5. Preserve the existing bounded wait and retry debt. If blocking output does not cooperate,
   the deletion remains closed/retryable and does not reach database/file clear. A later attempt
   joins the same unfinished physical job; WorkManager acknowledgement or CANCELLED state is not
   sufficient. Do not forcibly close arbitrary user streams from another job.
6. Keep the original epoch checks in imports. Capture the applicable generation/epoch before
   other transfers begin and reject stale final success, receipt, watermark and share handoffs.
   No new persistent transfer receipts are needed. A short `withReadyGeneration` handoff can
   linearize `startActivity`/publication with closure; do not hold it across suspend I/O.
7. Use readiness, not `permitsNewCollection`, for history transfers: turning tracking Off must
   still allow export of retained history. Do not cancel unrelated tracking/provider jobs through
   this new scoped operation.

Do not inject `DefaultTrackingStartupGate` into the deletion service: the gate already depends
on `CollectedDataDeletionService` (`DefaultTrackingStartupGate.kt:273`). The deletion service
already owns `TrackingStartupDeletionBarrier`; its existing close/quiescence calls provide the
required draining path without a DI cycle.

## Actual caller boundary: cover file ownership and handoff

| Caller | Smallest complete operation boundary |
| --- | --- |
| `ImportExportViewModel.kt:78-154,230-240` | Admit before creating the destination; include source reads, output close, cancellation/error cleanup and final result delivery. A format adapter alone is too narrow. |
| `ImportExportComposeActivity.kt:398-459` | Regular Share currently cleans/creates its private folder before the ViewModel operation. Move that preparation into the admitted IO operation. Non-radio success launches `resolveShareTripSummary` in a **second** ViewModel coroutine (`ImportExportViewModel.kt:176-227`); make it part of the same structured transfer or preserve the original admitted generation through the final handoff. Do not freshly admit that delayed callback after Clear all and then share an old file. |
| `ImportWorker.kt:87-115` | Admit after startup reconciliation and original-epoch validation, before DB access/file hashing/bundle staging; cover source receipts and staging/index cleanup. Admitting before the worker's own reconciliation could make deletion recovery wait for itself. Keep queued work's original epoch (`ARG_ORIGINAL_DATA_EPOCH`, line410). |
| `ExportPlanWorker.kt:77-113,142-250` | Admit after reconciliation but before DB/source reads or output creation. Include output close, existing noncancellable cleanup (`:226`), log/watermark updates and success notification. Current generation checks do not join the body. |
| `feature/statistics/.../export/GpxShareHelper.kt:36-98` | Admit before `filesDir/sharable` creation, through paged Location reads, file close, cleanup and share-sheet handoff. Its caller-owned file lifecycle is outside `TripGpxExportAdapter`; wrapping only the adapter misses it. Add cancellation/failure deletion of its unfinished private file before the registered job completes. Statistics already has the startup contract through core:base; no feature implementation dependency is needed. |
| `feature/map/.../ui/MapScreen.kt:511-540` and `.../export/MapImageShareHelper.kt:24-41` | Include the share request's rendering plus PNG creation/close/cleanup and handoff, or carry the request's captured generation into the helper. A bitmap rendered before Clear all must not acquire a new generation afterward and recreate cleared private data. The renderer already has coroutine cancellation (`MapSnapshotRenderer.kt`, `invokeOnCancellation { snapshotter.cancel() }`); the helper currently lacks partial-file cleanup. `Bitmap.compress` may block, so join completion rather than treating cancellation acknowledgement as completion. |
| `LocationFileImportPreview.kt:46-63` | When the preserved preview UI is wired, include source/index creation, input/index close and original epoch check. No current call site to `LocationFileImportPreviewer.inspect` was found; do not call this an active completed route. |

The Dashboard's `TrackerDashboardSessionCards.kt:153-160` shares plain text directly and writes
no owned file; it does not require joining a file writer. The ordinary file-sharing routes found
in production are the Export activity, Statistics GPX helper and Map image helper above.

Activity `onCreate` currently performs share-directory cleanup synchronously
(`ImportExportComposeActivity.kt:95-98`), as does `startShareExport`. Keep blocking disk work on
the injected IO dispatcher when moving that work into its admitted transfer. Do not broaden
cleanup to user-owned destination folders or delete unrelated files.

## Scheduled enqueue race remains separate and necessary

`ExportAutomationController.kt:49-55,62-95,103-133` can asynchronously schedule interval and
after-session work. `pauseForDataDeletion` (`:112-114`) only cancels the tag; an already queued
observer launch can enqueue after it. Reuse the existing private admission-fence pattern from
`DataImporter.kt:48,100-106,133` at **both actual enqueue sites**: pause/increment the admission
generation before cancelling, and allow enqueue only for a captured current generation.
Checking a boolean only before launching is insufficient, including after reopen.

Keep WorkManager cancellation for queued work. The startup transfer-job join covers work
already admitted to physical I/O. These are complementary existing boundaries, not a new job
system. Resume only at the existing successful deletion/reconciliation owner.

## Focused source tests required before selecting the repair

- Actual admitted transfer body blocked in a fake output while cancellation `Operation` is
  complete: Clear all cannot advance to database/file clear until close and cleanup finish.
- Cancellation during noncancellable partial-file cleanup: same job remains registered and joined.
- Timeout retains closed admission and retries the same physical drain; delayed old-generation
  manual/worker/share publication is refused after reopen.
- Registration/close race, nested operation, and cancellation of one operation leave unrelated
  ViewModel/application/provider jobs alive.
- Queued old import epoch is rejected; delayed interval and after-session enqueue cannot bypass
  pause or reuse an old admission generation after resume.
- Ordinary Share's separate summary callback, GPX outer stream and Map render/PNG path are
  covered, with final short handoff fenced. Retained export remains possible while tracking Off.
- Existing deletion journal preserves failed owned-file cleanup/retry debt; no user-owned
  completed destination copies are swept by private-file cleanup.

These are tests to author, not tests executed in this review. Full transfer cleanup and the
assembly gate remain pending until the selected production slice and its tests exist and later
pass the single convergence batch.
