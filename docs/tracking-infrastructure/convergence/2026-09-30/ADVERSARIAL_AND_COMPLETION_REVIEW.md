# Adversarial and completion review — 2026-09-30

**NOT READY for a convergence freeze, provider activation or integration into local `dev/v10`.**
The complete source-to-product assembly is unfinished. This review records concrete source
and assembly findings; it does not turn authored tests into passing evidence.

## Input and review boundaries

- Candidate: `codex/source-native-map-context-root`,
  `bd55222095c81a5cf9c68ac98898492ef85c1697`.
- Target: clean local `dev/v10`, `13f1a99871b59d29b3d059f374fc415490da6943`.
- The user identifies `master` as the released boundary. No local `master` ref
  exists; available historical `main` is `59fda665c7099d10b16aa60f505de88bd05d3131`
  with schema 12. Pin the actual released baseline in the freeze receipt; no
  development schema or old document titled “release evidence” proves release.
- The [exact input inventory](REVIEW_INPUT.json) records 178 candidate-only commits,
  54 local refs, 863 committed changed paths, 328 changed test paths, and 139
  preserved source/test drafts: 117 modified and 22 untracked files.
- Five independent reviewers performed three adversarial passes and two completion
  passes. The product reviewer additionally inspected Git/input/evidence readiness.
  A sixth fresh reviewer could not be created: both attempts returned
  `agent thread limit reached`. This is six passes by five reviewers.
- Reviewers inherited the original user conversation, including corrections and
  approvals, and were directed to the full `TASK_CONTEXT.md` packet. The task
  covers every tracking component, not only the latest radio slice. Earlier
  registry text about a frozen backlog does not supersede the user's finish-all
  requests. Sessionless background consent remains approved; the single all-day
  Steps choice does not remove every other purpose's independent consent.
- All passes were read-only, with no recursive delegation. Root independently
  checked source anchors, Git/input facts and draft preservation. All 139 original
  draft SHA256 values remained unchanged; the main checkout remained clean.
- **IMPLEMENTATION_ONLY remains in force.** No compilation, Hilt/Room execution,
  schema generation/drift gate, tests, lint, `git diff --check`, device/emulator,
  battery, CI, provider activation, rebase or merge occurred. This receipt is
  neither the convergence freeze nor the final review of validated results.

### Review coverage

| Pass | Reviewer | Scope |
| --- | --- | --- |
| Adversarial runtime | `adversary_runtime_0930` | Independent providers, purpose lifetimes, adaptive feedback, Start/End/Off, passive delivery |
| Adversarial data | `adversary_data_0930` | Downstream radio admission, retained readers, old details, backup/restore, files, bundles, clear |
| Adversarial product | `adversary_product_0930` | History/detail/actions, map wiring, settings recovery, period statistics, copy/accessibility |
| Completion requirements | `completion_requirements_0930` | Fresh production traces against all 25 assembly prerequisites and authored scenarios |
| Completion wiring | `completion_wiring_0930` | API/module/DI, Room entities/migrations, workers/permissions, transfer companions |
| Additional Git/evidence | `adversary_product_0930` | Exact refs/drafts, candidate ancestry, selection/freeze receipts, applicable evidence |

## Concrete work remaining

Paths below are repository-relative. Prefixes expand as follows:

| Prefix | Directory |
| --- | --- |
| `ENGINE` | `tracker/engine/src/main/java/com/adsamcik/tracker/tracker` |
| `API` | `tracker/api-module/src/main/java/com/adsamcik/tracker/tracker/api` |
| `DATA` | `stats/data/src/main/java/com/adsamcik/tracker/stats/data` |
| `STATS` | `feature/statistics/src/main/java/com/adsamcik/tracker/statistics` |
| `TRANSFER` | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp` |
| `APP` | `app/src/main/java/com/adsamcik/tracker/app` |
| `DB` | `core/base/src/main/java/com/adsamcik/tracker/shared/base/database` |

`HEAD + drafts` means the gap persists even with every reviewed draft present.
`HEAD only` means the working tree contains a prospective repair which has not
yet been selected, committed or executed. Line anchors refer to the reviewed
working tree unless explicitly marked `HEAD`.

| ID | Priority / state | Finding and required closure |
| --- | --- | --- |
| R01 | P0 / HEAD only | Committed `API/TrackerServiceApi.kt:632–779` places Turn off methods inside the unfinished `stopServiceAndAwaitQuiescence` body. Callers expect object members. The dirty draft moves the complete Stop function and repairs the structure. Review and commit this repair with its callers/test source; later compilation must establish correctness. |
| R02 | P1 / HEAD + drafts | `ENGINE/source/ambient/steps/AmbientStepsFactImporter.kt:160` defines `importNext` with no production invocation. The handoff coordinator is not a regular collection path; `SharedStepSourceController.kt:285` selects session capture. Finish the approved all-day direct/provider path, bounded enable/reopen/background import, cursor/count-domain/reboot/gap handling and daily product consumers. Cadence remains a user decision. |
| R03 | P2 / HEAD + drafts | `ENGINE/source/ambient/steps/AndroidAmbientStepsCapabilityResolver.kt:75–82` requires Health Connect background-read permission for that capability, but `app/src/main/AndroidManifest.xml:28` declares only `READ_STEPS` and no app acquisition route requests the background permission. Assemble the supported capability/repair route with R02; unsupported access must remain explicit. |
| R04 | P1 / HEAD + drafts | `ENGINE/source/ambient/wifi/AmbientWifiFactProjector.kt:156–186` and `ambient/cell/AmbientCellFactProjector.kt:153–170` retain callback-wide/current-demand checks. `ENGINE/source/wifi/WifiCapturedFactMaintenance.kt:1957–1967,2047–2085,2171–2178` and `DATA/repository/WifiHistoryComposer.kt:480–482` have incompatible whole-batch predicates. Valid v6 per-reading WAL admission can fail publication, history or maintenance. Propagate the existing qualified subset, callback-receipt authentication and historical purpose membership through every destination, with the saved-version boundary intact. |
| R05 | P1 / HEAD + drafts | Legacy network/tower children are copied by `DB/RadioObservationChildMigration.kt:211–230`, but `DB/dao/RadioObservationChildDao.kt:234–240` legacy readers have no callers. Draft `TRANSFER/exporter/PortableWifiSourceExporter.kt:99–145` and `PortableCellSourceExporter.kt:105–146` select captured/background products only. Make recoverable old details accessible through the new history/export infrastructure with honest origin labels. Finish native child propagation and correction/erase behavior. |
| R06 | P1 / HEAD + drafts | `ENGINE/service/TrackerService.kt:365,1341,1366` hosts background Location only. There is no independently opted-in background Wi-Fi/Cell foreground host lease. Use the approved existing service with independent purpose lifetimes and correct FGS requirements; ending a session must leave an opted-in background source hosted without lending it active scan requests. |
| R07 | P1 / HEAD + drafts | Without Location, `ENGINE/service/TrackerService.kt:1323` starts session-bound motion feedback, while `ENGINE/source/runtime/StepSourceRuntime.kt:1487` emits unbound raw Steps and `source/control/CollectionMotionController.kt:91` rejects missing IDs. Real Steps cannot supply adaptive motion to participating radio/Pressure sources. Connect admitted Steps feedback to the existing collection scope, preserving its actual identity rather than relabeling raw events. |
| R08 | P1 / HEAD + drafts | `ENGINE/source/runtime/PassiveLocationReceiver.kt:60–65,120–149` drops admission outcomes, bounded overflow and timeout/failure without durable coverage loss. Record bounded delivery debt/gaps in the existing recovery/history path. Do not promise Android redelivery after loss. |
| R09 | P1 / HEAD + drafts | `feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardRoute.kt:99–106,208,363–374` stores prerequisite state and issues a new Start after repair. `API/TrackerServiceApi.kt:589` checks current readiness without an original one-use request/source-choice/Stop fence. Complete the approved saved original Start across Dashboard, Tracker, widget and shortcut repair; End/Off or changed choices must invalidate it. |
| R10 | P1 / HEAD + drafts | `ENGINE/source/lifecycle/DefaultSourceLifecycleProofAdapter.kt:203–207` and `DATA/repository/DefaultSourceFactQueryRepository.kt:110–117` explicitly reject `AMBIENT_PRODUCT` as unimplemented. Separate background readers exist; the shared recording→materialization→retained-query lifecycle proof remains incomplete. Assemble supported ambient scopes without treating CONTROL as recorded history. |
| R11 | P1 / HEAD + drafts | `STATS/presenter/HistoryPresenterViewModel.kt:227–235,288–314` reads a fixed trip-only range and distance calendar; `DATA/repository/DefaultTripRepository.kt:23–60` reads `TripDao`. Source-only/background data can yield “No history,” mutations are not observed, and retrospective groups lack observation detail. Bind the bounded unified source history, paging/calendar/invalidation and annotation detail. |
| R12 | P1 / HEAD + drafts | `STATS/presenter/TripDetailSourcePresentation.kt:192–194` reduces mixed non-Location sessions to source names; `ui/TripDetailSourceOverviews.kt:173–202` has an unfinished detail placeholder. `ui/TripDetailRoute.kt:282–293` and `SourceHistoryDetailRoute.kt:84` expose disabled actions. Compose qualified per-source summaries and approved export/delete selectors for solo and mixed data; preserve truthful timestamp-only Activity/Pressure readings. |
| R13 | P1 / HEAD + drafts | `stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/RadioLocationContextRepository.kt:122–123` has no production implementation/binding/caller. `feature/map/src/main/java/com/adsamcik/tracker/map/data/RadioLocationContextResolver.kt:101` is uncalled; the same directory's `GeoRepository.kt:29–51` and `RadioQueryBuilder.kt:16,44` query legacy tables. Implement the approved source-owned bounded reader and connect observed-device context to the existing radio layers, with uncertainty/gaps and invalidation of either input. No Location demand or invented tower/access-point coordinates. |
| R14 | P2 / HEAD + drafts | `DATA/repository/DefaultWindowedMetricsProvider.kt:113–119` returns cumulative values for bounded longest trip, variety, seasons, streaks and export metrics; its authored test preserves that behavior. Calculate the requested periods as explicitly approved. Complete qualified effects and correction/deletion retraction as part of the full-chain prerequisites. |
| R15 | P1 / HEAD + drafts | `TRANSFER/importer/worker/ImportWorker.kt:256–264` rejects Tracker backups. Draft `exporter/DatabaseBackupPreflight.kt:15` has no production caller. There is no confirmation→stop writers→replace all data→interruption recovery route. `DatabaseExporter.kt:17–18,61–73` requires a caller barrier which `ImportExportViewModel.kt:251–299` does not own; database locks are acquired sequentially by `DatabaseBackupArchive.kt:38–45`. Finish a coherent data-only snapshot/restore, preserve a recoverable safety copy, exclude settings, reset live runtime state and leave tracking stopped. |
| R16 | P1 / draft remains incomplete | `TRANSFER/importer/file/LocationFileImport.kt:76–78` explicitly rejects approved `REPLACE_WITH_FILE`; `APP/settings/data/DataSettingsScreen.kt:377–399` offers Keep/Cancel only. The draft preview/Keep route is wired. Finish one import-wide Keep/Replace choice, duplicate handling, correction/invalidation and original-data-epoch protection. Accept structurally valid user-edited data; do not add authenticity restrictions to user backups. |
| R17 | P1 / HEAD + drafts | `TRANSFER/exporter/SessionBundleArchiveWriter.kt:15–21` and `importer/DataImporter.kt:308–314` bundle functions have only test callers. `ImportWorker.kt:246–277` uses generic archive import without bundle annotation/job staging. Connect approved typed-source bundle export/import, annotations, retry/partial-failure receipts and original-data-epoch fencing. |
| R18 | P1 / HEAD + drafts | `TRANSFER/exporter/activity/ImportExportViewModel.kt:78–90` owns interactive exports without a shared epoch/cancellation lease. `APP/settings/CollectedDataDeletionService.kt:234–287` cancels workers/scheduled exports only. Clear all cannot cancel and await the interactive writer; `ImportExportViewModel.kt:137–145` retains external deletion failure only as an exception. Finish approved clear cancellation, private-file removal and durable later unfinished-file notice. |
| R19 | P1/P2 / HEAD + drafts | `APP/settings/TrackingSettingsViewModel.kt:652–679` does not handle ordinary persistence failures with preserved edits/Retry. `APP/settings/tracking/TrackingSettingsScreen.kt:736–747` disables an already-selected Pressure toggle when hardware is unavailable. `app/src/main/res/values/strings.xml:380,385,389` and `feature/statistics/src/main/res/values/strings.xml:159–167` expose internal authority terminology and suppress missing translations. Complete approved recoverable saves, editable choices, concise localized copy and accessible surfaces. The notification failed-save exit choice is still pending. |
| R20 | P1 / assembly pending | Dirty `TRANSFER/format/FormatRegistry.kt:234–261` depends on untracked radio/Location adapters. `DATA/di/AmbientRadioPortableTransferModule.kt:17–20` and `ENGINE/source/cell/CellCapturedPortableExportModule.kt:20–28` are untracked companion bindings. HEAD does not contain the prospective transfer assembly. Select/review/commit companions coherently before freezing; do not infer readiness from a working-tree path alone. |
| R21 | Deferred gate + source closure | `DB/AppDatabase.kt:311` declares v29 and wires the migration/child callbacks, but the latest checked-in schema is `core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/28.json`. Complete final source/migration/released-data recovery first, then generate and validate the final schema in convergence. Development v27/v28/v29 are not released compatibility baselines. Preserve unknown files. |
| R22 | Assembly/evidence pending | The input inventory has no final dispositions for the 139 drafts; 22 local branch tips lie outside candidate ancestry. The September 27 selection manifest explains some copied slices, not disposition of entire refs or the current assembly. Freeze one coherent committed input with final schema/capability/test inventory and current gate commands only after all source and required scenario slices exist. |

## What the static review accepted

- The runtime multibinding identifies all six sources and routes Steps, Location,
  Wi-Fi and Cell through shared controllers. Source/purpose catalogue and product
  drain implementations exist; inspected bindings did not reveal a definite
  duplicate binding.
- The bounded v6 radio ingress preserves exact callback identity and historical
  per-reading purpose qualification. Durable active-radio spent-request state,
  passive callbacks and retained background detail readers have source slices.
  R04/R05 mean that these slices do not establish the full radio product chain.
- Room source lists include v29 radio children/DAOs, and normal builders connect
  their migration/callback. This is not schema, migration or reopen evidence.
- Temporary map/game Location leases, the authenticated non-exported passive
  receiver with Google/framework fallback, and 48-hour CONTROL cleanup have
  concrete source paths. Delivery/lifecycle/device proof remains outstanding.

## Full-chain coverage

The [fresh 25-prerequisite matrix](PREREQUISITE_COMPLETION_REVIEW.md) records
22 partial source chains and three full chains whose inspection is incomplete
(automatic Steps, Activity, exhaustive caller guard coverage). None is signed off.
The separate uninspected status does not assert those implementations are absent.
Authored scenarios and executed host/device evidence are kept distinct.

The Git pass also found obsolete v27→v28 and exact-v28 validation targets in
`IMPLEMENTATION_TODO.md`. This review's documentation update replaces those
targets with the actual released-baseline/final-schema gate and explicitly keeps
every assembly and execution box unchecked. It does not generate a schema.

## Integration sequence after source closure

1. Finish the source/test gaps above and resolve the two remaining choices: Steps
   database-copy cadence and leaving notification customization after an actual
   failed save. Do not silently decide either.
2. Reconcile every candidate commit/ref/draft; record selected, replaced,
   superseded, preserved or excluded dispositions without discarding work.
3. Complete an independent source-assembly review and freeze the exact committed
   input, final schema inputs, capability limits, scenario inventory and commands.
   Only a genuinely complete assembly lifts the implementation-only boundary.
4. Run compilation/Hilt/Room, final schema and populated released-database
   migration/reopen checks, `ciUnitTest`, and `ciCheck`; repair and rerun the
   required gates on the same final input. Existing handwritten tests and old
   branch results do not establish this candidate's pass.
5. Obtain bounded activation authority and real provider→storage→UI→file→Stop
   evidence, lifecycle/recovery and battery observations on the recorded device
   configuration. Keep unsupported capabilities and evidence gaps explicit.
6. Give a fresh reviewer the original messages, final diff and all gate/device
   results; fix findings. Rebase onto latest clean local `dev/v10`, rerun the
   required integrated checks, then merge locally and record its final commit.
   No push is authorized.

The reviews performed here are complete as static review work. Implementation,
convergence, physical-provider proof and `dev/v10` integration remain open.
