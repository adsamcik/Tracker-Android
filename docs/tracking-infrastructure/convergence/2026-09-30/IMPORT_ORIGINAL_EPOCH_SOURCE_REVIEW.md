# Original typed-file epoch: independent source review

Date: 2026-09-30. Reviewer: `/root/completion_requirements_0930`.
Status: **IMPLEMENTED_UNVALIDATED**. Bounded independent static acceptance of the
original-data-epoch slice; no concrete correctness blocker found within this scope.

## Input and boundary

Reviewed the 12 production and 10 test paths in `IMPORT_ORIGINAL_EPOCH_FILES.json`,
the required preserved Location preview and radio child-attachment drafts, and the
existing owning Room importers at the epoch/transaction admission boundaries.
Original user instructions and approvals apply: an existing job must retain its
original data epoch after Clear all, while a new explicit import is a new request.

No production edits, Git writes, executed tests, compilation, Hilt/Room generation,
schema checks, devices, providers, or convergence gates were performed for this
review. Written tests below are source evidence only.

## Traced production paths

All paths below are relative to the candidate root. Line anchors describe this
review's source snapshot and may move during later assembly.

| Boundary | Source evidence | Finding |
| --- | --- | --- |
| Enqueue before file bytes | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/DataImporter.kt:69`, `:77`, `:161`; `importer/worker/ImportWorker.kt:84`, `:102`, `:108`, `:123`, `:147` | Enqueue persists the lifecycle epoch in WorkManager input. Worker reads that fixed value before content hashing, checks current authority before and after hashing, and gives the same value to the runner. It never substitutes the post-read epoch. |
| Immutable stream context | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/FileImportStream.kt:61`, `:73` | Binding validates a nonnegative value, is idempotent for the same value, rejects rebinding, and does not open the lazy stream. Missing values are refused by typed adapters. |
| Direct/archive/bundle child retries | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/DataImporter.kt:284`, `:308`, `:326`, `:352`, `:361`; `importer/worker/ImportWorker.kt:283` | All runner child dispatches pass through the same receipt binding and original epoch. The common worker adapter dispatch binds the same value again idempotently. Retrying a failed child cannot adopt a new epoch. |
| Location | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/LocationFileImport.kt:28`, `:29`, `:59`, `:137`, `:174` | Requires the original epoch before staging, checks after staging, and uses it at admission/completion of each existing 256-point Room transaction. It does not obtain a replacement value during preview or mutation. |
| Activity | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortableActivityFileImport.kt:57`, `:80`, `:100`; `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/RoomImportPortableCapturedActivity.kt:46`, `:70` | Before-decode epoch is forwarded to every source-owned transaction; owning writer rejects another Room epoch before mutation. |
| Pressure | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortablePressureFileImport.kt:70`, `:72`, `:81`; `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomImportPortablePressure.kt:56`, `:88` | Prefix entries retain one original value. No later entry snapshots a new epoch; Room checks the supplied value inside its transaction. |
| All-day Steps v1/v2 | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortableAmbientStepsFileImport.kt:80`, `:92`, `:104`, `:116`; `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomImportPortableAmbientSteps.kt:132`, `:136`, `:169`, `:193` | Both versions preserve the supplied epoch through their shared owning transaction. Existing retention approval remains tied to that same epoch/floor. |
| Session Steps v1/v2 | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortableStepsFileImport.kt:70`, `:108`, `:123`; `stats/api/src/androidMain/kotlin/com/adsamcik/tracker/stats/api/repository/StepsPortableTransferV2.kt:62`, `:82` | Both file APIs require a non-null original epoch. The compatibility adapter also forwards it; v2's request has no default that could silently drop it. |
| Steps mutation and rollback | `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/importer/RoomImportPortableSteps.kt:264`, `:267`, `:316`, `:320`, `:366`, `:390` | Existing lifecycle snapshots, retained floor, day locks, Room admission, native evidence recheck and final lifecycle check remain. Epoch/floor changes during payload/summary repair throw and roll back the transaction; dirty effects publish only on Applied. |
| Wi-Fi/Cell sessions and background | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortableRadioSourceFileImport.kt:58`, `:130`; `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/RoomImportPortableCapturedWifi.kt:64`, `:89`; `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/RoomImportPortableCapturedCell.kt:48`, `:81`; `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomAmbientWifiPortableTransfer.kt:186`, `:199`; `RoomAmbientCellPortableTransfer.kt:185`, `:198` | Decode cannot select a newer value. Each captured/background request uses the original value, with rejection inside its source-owned Room transaction. |
| Radio child attachment | `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/ImportedRadioChildAttachment.kt:18`, `:36`, `:54`, `:72`, `:95`, `:100`, `:102` | Parent-first attachment retains the same value for every bounded Room batch, including empty children. Background parent epoch is also checked. Clear between parent and children prevents old child attachment rather than rebinding to new data. |
| Legacy direct Steps API | `stats/api/src/androidMain/kotlin/com/adsamcik/tracker/stats/api/repository/StepsPortableTransfer.kt:86`; `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/importer/RoomImportPortableSteps.kt:121`, `:258` | Existing one-argument calls remain source-compatible through the optional epoch. A direct call without a file job takes one lifecycle snapshot; typed file paths always supply their original value. Repository search found the implementation, compatibility wrappers, v1/v2 constructors and test overrides updated consistently. This is not a binary compatibility claim. |

## Authored scenarios inspected

- `FileImportStreamTest.kt:95`: lazy direct/archive binding, same-value idempotence,
  changed-value rejection, negative value and closed stream.
- `TypedFileImportEpochTest.kt:21`: all seven typed adapters reject missing epoch
  before opening bytes or resolving writers.
- `ImportJobRunnerTest.kt:141`, `:423`, `:482`: bundle child retry, direct retry and
  direct/ZIP routes retain one epoch and receipt; inspected surrounding receipt
  transactions and rollback cases.
- `LocationFileImportTest.kt:73`, `:84`, `:101`: missing/stale direct/archive epoch,
  clear during staging and removal of private staged bytes. These are Room adapter
  scenarios, not a real WorkManager/provider process test.
- `PortableActivityFileImportTest.kt:41`, `:88`: forwarded original epoch and stale
  decoded input rejection before source writer.
- `PortablePressureFileImportTest.kt:126`: a committed prefix cannot switch its
  next entry to the later epoch.
- `PortableAmbientStepsFileImportTest.kt:59`, `:100`: exact archive epoch and stale
  original job rejection; inspected v2 forwarding assertions.
- `PortableStepsFileImportTest.kt:134`: both version dispatches retain the original
  epoch; compatibility test helper and versioned overrides match the new contract.
- `PortableRadioFileImportEpochTest.kt:36`, `:70`: read-time clear and Wi-Fi retry
  retain the original value. These cases exercise refused background requests,
  not a real Room clear between parent commit and populated child attachment.
- `RoomImportPortableStepsTest.kt:115`, `:131`, `:883`: stale legacy/v1/v2 file epoch,
  lifecycle change during mutation with payload/summary rollback, and existing
  retention-floor refusal source. No test result is claimed.

## Scope limits and remaining work

- This accepts only the epoch propagation and owning transaction boundary changes.
  It does not close the whole source-transfer, archive, cancellation or bundle item.
- The inspected Worker still uses its ordinary archive route; this review does not
  claim the standalone runner's bundle helper is a completed production bundle route.
  R17's owner is separately assembling/reviewing that transport.
- Location Replace still explicitly refuses replacement. The approved conflict
  choice and durable correction behavior need their own completed implementation
  and review; this receipt does not approve them as complete.
- Full data backup/replace restore was outside this slice and is not approved here.
- Existing non-Steps Room importers were inspected at epoch, parent, retention and
  transaction admission boundaries, not exhaustively re-reviewed for all lineage,
  corrections, retention concurrency, native provider or UI behavior.
- Hilt composition, compilation, migration/schema convergence, executed Room/JVM
  scenarios, actual worker process recovery and device lifecycle evidence remain
  deferred. No assembly freeze, provider activation or `dev/v10` readiness follows
  from this bounded source acceptance.
