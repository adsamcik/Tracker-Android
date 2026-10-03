# Ordinary transfer quiescence and request ownership

Date: 2026-10-03. **IMPLEMENTED_UNVALIDATED — IMPLEMENTATION_ONLY.**

This freezes a production/test-source slice for independent static review. It is not the assembly
freeze, a passing test receipt, an integration gate, or device/provider evidence. No Gradle,
compilation, tests, lint, schema checks, diff checks, UI/device/battery/CI, activation or Git mutations
were performed by the implementation author.

## Exact selection

`TRANSFER_CLEAR_FILES.json` pins 29 current working source files at observed HEAD
`1e58017f391fbcdae966bab717f3d7dc17742ec8`, their SHA-256 values, staging dispositions and **38 newly
authored, unexecuted cases**. It lists 16 production and 13 test paths.

- `TRANSFER_CLEAR_TRACKED.patch` selects complete task-only tracked deltas. Newly authored test
  files are separate exact paths in the manifest, because Git diff does not include untracked files.
- `TRANSFER_CLEAR_MIXED.patch` selects only this task's changes in seven mixed paths. Its normalized
  before/after files are under `transfer-clear-selection/`. Before files are exact observed-HEAD
  source; after files apply only this slice. The live mixed-file hashes also remain in the manifest.
- Those selected snapshots exclude preserved radio export switch/routing/UI/test drafts, Location
  conflict worker/input/staging drafts, and Location private-staging cleanup companions. **Do not
  stage the whole mixed working files.** Preserved live drafts were retained. The standalone selected
  manual export VM/activity continue to use the committed exporter contract and unchanged formats.
- No LastSession, geographical scope, restore, bundle membership, typed source identity, or format
  selection change is selected here. No held radio exporter is required by these selected changes.

## Reused authority and drain order

`core/common/.../startup/TrackingStartupGate.kt` adds the dedicated structured file-operation seam
`withReadyGenerationTransfer` and the short retained-data handoff `withRetainedDataGeneration`.
The latter is for retained data only and never permits acquisition. Existing live control methods
remain fenced by collection Stop authority.

`app/.../startup/DefaultTrackingStartupGate.kt` implements them using the **existing** deletion
barrier's generation and monitor. It registers only an admitted operation's lazy structured child,
before that child starts. It neither registers an entire ViewModel/application scope nor holds the
startup or Stop mutex across file I/O. Nested operations reuse their admitted child context.

The barrier marks closing and snapshots children atomically, cancels them **outside** the monitor,
and joins actual completion including stream `use` closure, descendants and `NonCancellable`
cleanup. Registration is removed only at actual child completion. A bounded wait failure leaves
admission closed and retains unfinished children for a later retry. Reopen refuses unfinished jobs.

The original `CollectedDataDeletionService.kt:560,627,951,1268` sequence is preserved: close admission;
provider/WorkManager fences plus physical startup quiescence; durable epoch/database clear; owned
file clear; successful recovery/reopen. The new tests compose the real deletion service with the
actual barrier; WorkManager acknowledgement alone cannot advance to epoch/database/private cleanup.
No dependency from deletion service back into the startup gate was introduced.

Retained-data readiness is cached only when the existing full startup publishes `Ready`. Collection
Off/handled Stop changes do not invalidate that storage generation. Actual storage/released-history
recovery failures revoke it; lifecycle supersession alone does not. First-startup or damaged-history
failures cannot acquire it. The deletion barrier always rejects it during closure and after a new
generation. Workers' source-access callbacks and final result/notification/share handoffs now use
this retained-data seam, so turning tracking Off during a valid transfer does not discard its file.

## Production callers

| Caller | Selected operation and final boundary |
| --- | --- |
| `feature/import-export/.../exporter/activity/ImportExportViewModel.kt` | Capture original generation, reconcile before admission, then include destination preparation, source reading/writing, stream close, partial cleanup, suspend result/summary and owned-directory cleanup in one registered child. Save success state and `activity.finish` use the original retained generation. Cancellation restores Failed state so Back is not stuck. |
| `feature/import-export/.../exporter/activity/ImportExportComposeActivity.kt` | Remove blocking onCreate folder sweeping. Share owns a memory-allocated UUID request path under cache/sharable, creates its directory exclusively on admitted IO, preserves the visible filename within it, and removes only its directory on failure/cancellation/no handoff. Non-radio summary is awaited by the same child; final chooser launch is short and generation-fenced. |
| `feature/import-export/.../importer/worker/ImportWorker.kt` | Reconcile and validate original queued data epoch before admission, then include DB access/hash/staging/receipt/typed import and cleanup. Original-epoch transaction fencing remains intact. Existing Blocked-recovery error notification remains available, while ordinary transfer notifications use the original retained generation. |
| `feature/import-export/.../exporter/automation/ExportPlanWorker.kt` | Reconcile before admitting its existing DB/output/cleanup/log/watermark lifecycle. Completion notification is short and generation-fenced. Existing deletion watermark reset follows physical drain. Export scope and cursor semantics are unchanged. |
| `feature/import-export/.../exporter/automation/ExportAutomationController.kt` | Reuse this module's existing ImportAdmissionFence algorithm for schedules. Capture generation before queued interval/after-session work; check it around each actual enqueue/cancel. Pause before WorkManager tag cancellation, remain paused on failure, and resume only through existing successful deletion recovery. |
| `feature/import-export/.../importer/file/LocationFileImportPreview.kt` | Include metadata/input/index/DB reads and original-epoch verification under admission. Contrary to the earlier proposal receipt, **there is an active caller** at `DataSettingsViewModel.kt:135`. Only the existing entrypoint's gate accessor is selected in DataImporter. |
| `feature/statistics/.../export/GpxShareHelper.kt` | Include directory/file ownership, paged Location read, outer stream, failure/cancellation cleanup and final Share handoff. Atomically create a distinct temporary file even for simultaneous requests for the same trip. Failed exporter results remain Failure even when no points were emitted. |
| `feature/map/.../ui/MapRoute.kt`, `MapScreen.kt`, `export/MapImageShareHelper.kt` | Capture generation with the user request, then include rendering and PNG stream/cleanup/handoff in one operation. PNG has an atomic per-request temporary filename. No stale rendered bitmap may freshly admit itself after clear. |
| `feature/map/.../export/MapSnapshotRenderer.kt` | SDK cancellation runs in an awaited `NonCancellable` finally on the creator/main dispatcher. The deletion IO thread never invokes native cancellation through a continuation cancellation handler. Completion waits for that cancellation call; no detached post or launch is used. |
| `feature/import-export/.../OwnedTransferFiles.kt` | Existing full clear additionally recognizes only UUID request directories directly beneath the owned share roots. Validate canonical paths/no symlink, revoke each contained file's share grant, delete contained files and the directory. Unrecognized/nested directories still produce the existing cleanup debt. Ordinary Share no longer sweeps GPX/PNG sibling files. Existing 24-hour aging recognizes owned request directories and preserves fresh/unrecognized folders. |

### Pinned SDK contract

The catalog remains `maplibreAndroid = "13.4.1"`. Its authoritative
[MapSnapshotter source](https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.4.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/snapshotter/MapSnapshotter.kt)
lines 453–460 require `cancel()` on the creator thread and call `checkThread()` before native
cancellation. This source inspection supports the dispatch repair. It is not a native rendering or
device pass.

## Authored source scenarios

The manifest reports the case count per path. Existing tests were retained; seven mixed-path
selection snapshots explicitly exclude unrelated draft tests.

- Real barrier: open stream close and delayed noncancellable cleanup before clear; cancellation
  outside monitor; dedicated-child ownership; nested admission; no control mutex held across IO;
  timeout/retry/open fencing.
- Real deletion service: WorkManager cancellation acknowledged while physical writer cleanup is
  still active; epoch/database/file clear waits; timeout leaves durable retry debt.
- Real startup gate: history when already Off; Off reserved/handled during an admitted file write;
  live acquisition remains refused while retained file/handoff finish; later Clear rejects the
  original generation; actual released-history failure revokes retained readiness.
- Manual VM/document path: refused/old admission does no file work; destination failure recovers
  state; clear after synchronous success cannot finish the UI; awaited summary remains in the
  child; cancellation preserves ViewModel usability; Off during a write retains successful data;
  two independent screens using the same visible filename have separate folders and cancel only
  their own output; aging preserves fresh/unrecognized folders and GPX/PNG sibling ownership.
- Workers and enqueues: stale original import epoch rejected before DB; reconciliation precedes
  refused admission; old queued interval/after-session tasks cannot enqueue across pause/resume;
  failed WorkManager cancellation keeps the admission paused.
- GPX/PNG: real outer streams close before private cleanup; exporter/encoder failures remove
  partial copies; same-trip GPX requests own separate outputs; old request cannot render/recreate
  files; final handoff remains fenced.
- Snapshot renderer: mocked SDK start/cancel with two real dispatcher threads; cancellation from
  IO invokes cancel only on the creator thread, and child completion waits for that call; native
  start exception still closes the cancellation boundary. These are SDK-boundary fixtures, **not
  execution of native MapLibre**.
- Real owned-file clear: recognized nested share copies removed; unrecognized folders preserved
  with explicit cleanup failure. The existing FileProvider host-path limitation is retained: the
  grant-backed case skips Windows hosts, and remains unexecuted here.

## Required work outside this frozen ordinary slice

1. **Blocked-startup recovery exports remain excluded pending the user's access decision.**
   `app/.../settings/DataSettingsViewModel.kt:350–418` has `exportMigrationBackup` and
   `exportLegacyDatabase`; `app/.../activity/MainActivityCompose.kt:87–111` has its raw legacy
   export launcher. They can export preserved recovery files while normal startup is Blocked.
   A readiness-only wrapper would remove that repair action. Their outer output, cancellation
   cleanup and UI callback still require the approved bounded recovery admission after this
   decision is answered. The ordinary cached-retained seam is not used to open damaged databases.
2. **Persisted later unfinished external-file removal notice is absent.** Existing manual partial
   document deletion reports its current failure; scheduled output deletion can fail with a URI.
   The next Export screen does not yet remember/show that URI. This remains an approved separate
   recovery slice, not a passing capability of this packet.
3. Full data-only backup/confirmed restore, exact geographic/LastSession/incremental scope, held
   radio export UI/selection and source-native counterpart membership are independent work.
   Physical cancellation does not prove any of those scopes or imports correct.
4. Compilation/Hilt/Room/host/UI test execution, migration/schema, native rendering/provider
   lifecycle, permissions, device I/O cancellation and battery behavior are deferred. A
   noncooperative provider/SDK/output can cause bounded clear failure with retry debt; no unsafe
   forced stream close is claimed. There is no final dev/v10 integration readiness claim.

The author will keep these selected source paths frozen for the fresh review. Later repairs must
repin the affected source hashes and selected excerpts before this packet is used for staging.
