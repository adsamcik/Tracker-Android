# Fresh adversarial review: ordinary transfer quiescence

Date: 2026-10-03. Reviewer: `/root/transfer_adversary_1003`, independent of the implementation author.
**IMPLEMENTED_UNVALIDATED — IMPLEMENTATION_ONLY.**

## Result and exact input

No unresolved concrete defect found in the final **selected ordinary-transfer slice**. The cancellation,
ownership and successful-Off findings below are statically closed. This receipt supports staging the
exact reviewed selection for implementation continuation; it does not establish passing tests,
assembly completion, integration readiness or permission to activate providers.

The review inherited the complete user conversation, follow-ups, corrections, approved decisions and
AGENTS.md. It inspected production callers, existing deletion/control dependencies, selected patches,
test source and the author's scope boundaries. No production changes, Git operations, recursive
delegation or deferred validation were performed by this reviewer.

Candidate root: `C:/Users/adam-/GitHub/Tracker-Android/.worktrees/source-native-map-context-root`.
The packet records observed base `1e58017f391fbcdae966bab717f3d7dc17742ec8`; commit identities were
not independently queried with Git. The coordinator's separate retained-core commit does not change
these transfer paths.

Final inputs inspected:

| Input | SHA-256 |
| --- | --- |
| `TRANSFER_CLEAR_FILES.json` | `ab49698d6aa83fdf2c32bba37bb42595ba0aaf3c8d7f3e58e7d56d8b4786531e` |
| `TRANSFER_CLEAR_TRACKED.patch` | `75a78641291238972e16ec352c929b620233f913bc12ec0f27005c8a0cac5a86` |
| `TRANSFER_CLEAR_MIXED.patch` | `0373590633c1117c7807847b921defd98632209c9582802cbe357b22a4bc0697` |

Read-only file hashing matched **all 29 current source paths** and **all seven selected-after
snapshots** against the final manifest: 16 production and 13 test paths, no mismatch. Comparing the
preserved pre-cleanup packet showed exactly three changed working paths: Activity, OwnedTransferFiles
and ManualExportTransferBoundaryTest; the other 26 were unchanged. The final packet contains **40
newly authored, unexecuted cases**: 24 annotations in five new test files and 16 new cases in selected
modified test sources. Existing compatibility-only edits add no new cases.

Only `TRANSFER_CLEAR_MIXED.patch` and its seven selected snapshots are endorsed for mixed files.
The complete live mixed files contain held radio UI/export, Location review/worker/input/staging and
cleanup drafts. They must not be staged wholesale. Selected manual export still uses the committed
exporter contract and formats; selected DataImporter adds only the existing entrypoint's gate accessor.
The pre-cleanup packet remains historical evidence, not the accepted selection.

## Findings and closure

| Concrete finding | Final source closure | Authored source boundary |
| --- | --- | --- |
| Share preparation could remove another caller's open GPX/PNG file. | Ordinary Share prepares only its cache route; the old blanket files/sharable sweep is removed. | Manual case keeps concurrently open GPX/PNG siblings intact. |
| Save success/Activity.finish could arrive after Clear closed admission. | VM publishes success with the original retained generation, checks cancellation before the suspend result callback, and Activity finishes through `handOffExport`. | Actual VM with a feature gate fixture; synchronous completion followed by closure cannot finish the screen. |
| Concurrent same-trip GPX/map requests could own the same physical file. | GPX and PNG use atomic per-request temporary files and remove only their own unfinished output. | Same-trip GPX cancellation preserves the sibling file; PNG fixtures inspect closure/partial cleanup. |
| Native snapshot cancellation could run on deletion's IO thread and violate the SDK contract. | `awaitSnapshot` performs awaited `NonCancellable` cancellation on the creator/main dispatcher in finally; there is no detached cleanup launch/post. | Two real JVM dispatcher threads, mocked MapSnapshotter; blocked mocked cancel keeps the render job incomplete. |
| Successful Turn off during an admitted retained-history transfer could falsely reject its result. | `retainedDataReadyGeneration` is set only at full startup Ready. Admission/nesting and short final handoff use storage/deletion authority; existing live readiness retains the handled-Stop fence. Actual storage/history recovery failure revokes the retained cache. | Actual gate/barrier with lifecycle/recovery doubles; Stop is reserved and handled during a write, without reconciliation, followed by retained handoff and later Clear rejection. |
| Two ordinary Export screens with the same chosen filename could overwrite/delete each other's cache output. | Each request owns an exclusively created UUID directory under existing cache/sharable, preserving its chosen filename. Its cleanup is awaited within the admitted child and removes only that request. | Actual VM/local files with a feature gate fixture; one cancellation preserves the other request's bytes. |
| Ordinary aging/cancel cleanup could recursively delete unexpected nested or linked content that full clear rejects. | All three paths reuse `OwnedTransferFiles.removeShareRequestDirectory`. It preflights the recognized directory's canonical ownership and every immediate regular file before deleting any member. Unexpected nested/linked content raises existing cleanup debt; no recursive request deletion remains. Full clear also revokes file grants. | Two new cases cover cancellation and aging: nested content and its direct sibling survive; a recognized-name directory link is rejected and its outside target survives. Link case assumes host symlink support. |

### SDK evidence

The local dependency catalog still pins MapLibre Android **13.4.1**. Its exact-tag
[MapSnapshotter source](https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.4.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/snapshotter/MapSnapshotter.kt)
lines 453–460 require creator-thread cancellation and call the thread check before native cancellation.
The production construction/start path is already on the injected main dispatcher; the repair keeps
finally on that same dispatcher and waits for cancel to return. Source inspection and mocked SDK tests
do not establish actual JNI cleanup, rendering, GPU release or Android Looper/device behavior.

## Core trace

- The existing barrier monitor atomically checks generation/closing and registers a lazy, dedicated
  structured child before starting it. It does not register the whole ViewModel/application scope.
  Rejected lazy children perform no transfer I/O; completion-handler registration also handles a child
  cancelled before it starts.
- Closing marks admission closed and snapshots children under the monitor, then cancels outside it.
  A cancellation callback may reenter without being invoked while that monitor is held. Actual child
  completion includes structured descendants, stream use/close and awaited noncancellable cleanup;
  only that completion removes the registry entry.
- The bounded drain joins actual children before the existing startup mutex can publish the closed
  generation. Timeout leaves closing/debt and unfinished children intact; retry joins them, and reopen
  refuses an unfinished registry. New transfer I/O holds neither the startup nor handled-Stop lock.
  Existing typed-import transaction fences remain intact and were not replaced by this seam.
- Nested transfers reuse the admitted child's coroutine context for the same gate/generation. Final
  share, success and ordinary worker notification handoffs are short and linearized with deletion by
  the existing admission monitor. Clear closing or a later generation rejects stale handoff.
- The real deletion service's ordering remains provider/WorkManager fencing and physical barrier drain,
  then durable epoch/database clear, then owned-file cleanup and recovery/reopen. WorkManager
  acknowledgement alone cannot advance destructive work. No deletion-service-to-gate dependency was
  introduced.
- Retained history is separate from acquisition: a successful Off changes live Stop authority while
  a previously ready storage generation remains usable. Actual storage/released-history failures,
  first-startup failure, closing and stale generations cannot gain retained admission. The new test
  covers the actual gate transition during a write, not merely admission when already Off. It simulates
  service markHandled; it does not execute TrackerService or providers.
- ExportAutomationController reuses the existing enqueue fence around actual WorkManager calls;
  delayed pre-pause interval/after-session work cannot re-enqueue after resume. Failed cancellation
  leaves scheduling paused. Workers retain original-data-epoch checks before source access and within
  existing import transaction authority.
- The Location preview has an active caller at DataSettingsViewModel.kt:135. The current author boundary
  correctly supersedes the earlier trace's no-caller statement. Its admission fixture checks the direct
  preview entrypoint, not the outer settings UI or Hilt composition.

## Test-source inventory and limits

Every case below is **authored but unexecuted**. Counts concern new cases in this selection.

| Test source | New | What the inspected source exercises |
| --- | ---: | --- |
| TrackingStartupTransferQuiescenceTest | 4 | Actual barrier, fake stream and delayed noncancellable cleanup; timeout/retry, child ownership, no startup lock across I/O. |
| DefaultTrackingStartupGateTest | 4 | Actual gate/barrier, dependency doubles; nested/off history, stale generation, Off during write, recovery failure invalidation. |
| CollectedDataDeletionServiceTest | 2 | Actual deletion service/barrier and private FileOutputStream; mocked destructive Room callback and writer quiescer. No WorkManager runtime. |
| OwnedTransferFilesTest | 2 | Actual private-file clear; recognized request and unknown directory handling. Existing grant-backed case skips Windows hosts. |
| ImportExportViewModelTest | 0 | Existing tests updated for selected constructor/result and suspend-summary signatures. |
| ExportDocumentCleanupTest | 0 | Existing document-cleanup fixture updated for gate injection. |
| ManualExportTransferBoundaryTest | 12 | Actual VM/local DocumentFiles and ownership helpers; dedicated-child feature gate fixture, awaited callbacks, Off/same-name requests, aging and unexpected nested/linked preservation. No actual Activity UI or default app gate. |
| ExportAutomationControllerTest | 2 | Actual controller and admission fence; fake WorkManager Operation for delayed enqueue/cancel and failed pause. |
| TransferWorkerAdmissionTest | 3 | Actual workers with mocked parameters/providers and declining gate; rejected epoch/admission does no hashing/DB/destination work. No accepted Room/Hilt/WorkManager execution. |
| LocationPreviewTransferAdmissionTest | 1 | Mocked entrypoint and declining gate; reconcile before rejection and no index/source/collected DB work. |
| GpxShareHelperTest | 4 | Actual outer files/streams/helper; repository/exporter fixtures for cancellation, failure, same-trip ownership and stale admission. |
| MapImageShareTransferTest | 4 | Actual helper with render/Bitmap/FileProvider fixtures; stale render, cancellation, output closure and encoder failure. |
| MapSnapshotRendererTest | 2 | Actual awaitSnapshot with mocked SDK and real dispatcher threads; creator-thread cancel and awaited completion/start-failure cleanup. |
| **Total** | **40** | **No executed pass claim.** |

## Still outside this receipt

1. Blocked-startup migration/raw legacy recovery exports remain excluded pending the user's access
   decision. Their outer writes, cleanup and UI callback still need separately approved recovery
   admission. Readiness-only wrapping would remove the repair action; this reviewer neither inferred
   authority nor required those callers to become ordinary Ready transfers.
2. The approved later notice for an unfinished external file that could not be deleted is still absent.
   Immediate failure/suppressed cancellation information does not persist that notice for the next
   Export visit.
3. Data-only backup/restore, geographic/LastSession/incremental scope, bundle membership, held radio
   selection/UI and source identity/deduplication are separate work. This slice awards none of those
   assembly prerequisites.
4. Compilation/Hilt/Room/schema/migrations, tests/lint/CI, UI/native rendering, provider/device lifecycle,
   real destination cancellation and battery evidence remain deferred to convergence. A noncooperative
   destination or native SDK may cause bounded clear failure and retry debt; forced unsafe closure is
   not claimed. No dev/v10 integration readiness is established here.

Any later change to the pinned selection requires repinning affected hashes and reviewing its actual
source before this receipt is reused.
