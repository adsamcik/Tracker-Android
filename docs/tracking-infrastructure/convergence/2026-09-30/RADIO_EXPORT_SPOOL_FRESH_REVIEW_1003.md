# Independent radio export spool review — 2026-10-03

Reviewer: `/root/fresh_bundle_context_review_0930`. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Review only: no production changes, Git operations, compilation, tests, device/provider activity or other convergence gates.

## Selection and conclusion

Read `RADIO_EXPORT_SPOOL_SOURCE_SLICE.md`, `RADIO_EXPORT_SPOOL_FILES.json`, both selected constructor/wrapper excerpts, all seven methods and fixtures in `RadioExportSpoolRoomTest.kt`, the existing spool/codec, constructor call sites and manual/scheduled output cleanup. The user's approval, “Use existing temporary export storage,” authorizes this reuse of the existing bounded spool.

**No concrete defect found in the selected constructor/wrapper behavior or new test source by static inspection.** The owned excerpts remain equal to the frozen hashes. This closes the narrow destination-inside-Room finding at the source level. It does not select or approve the rest of either held exporter, its registry/UI drafts, source authority, archive membership or transfer lifecycle.

## Concrete trace

- Wi-Fi `PortableWifiSourceExporter.kt:90–107` and Cell `PortableCellSourceExporter.kt:85–102` create the existing private spool, stage the existing codec/file writer inside the whole Room transaction, and call `copyTo` only after that transaction and stage have returned. The codec finishes/flushed encoding in the snapshot; the private stream is closed before publication. The record count becomes `Success` only after destination copy and spool cleanup.
- The production default remains the existing 64 MiB radio file bound. The smaller internal fixture bound is constrained to `1..MAX_FILE_BYTES`; keeping the source lambda last preserves the actual trailing-lambda callers. Registry callers use the default constructor. No dependency, public contract, schema or generic export framework is introduced.
- No-data, source-read failure and encoded overflow cannot call destination copy. Cancellation is rethrown by both wrappers. Existing `FileBackedExportSpool.kt:80–100` classifies only the typed encoded limit as overflow and refuses a completed stage after cancellation; `103–120` checks cancellation around each buffered copy; `123–130` deletes the owned file and reports deletion failure. A copy/cleanup failure cannot produce a success count. The utility does not close or flush the caller's destination.
- Manual `ImportExportViewModel.kt:230–240` owns destination close; `124–154` removes failed or cancelled partial documents using non-cancellable cleanup. Scheduled `ExportPlanWorker.kt:202–251` closes the output, rechecks its existing generation, deletes partial output on error/cancellation and advances progress only after success. These are inspected caller paths, not exercised by the new fixtures. `OwnedTransferFiles.kt:19,71–85` already includes the spool directory in full-clear cleanup and explicitly requires writers to have stopped first.

## Test-source evidence and limits

The seven authored methods use the actual exporter, Room database, V2 codec and spool. Their source assertions are meaningful for this boundary:

1. For both sources, a destination is held open while a separate Room writer completes; success has not yet returned, and the decoded file contains the original gap rather than the later inserted gap (`90–134`).
2. Wi-Fi starts a competing child deletion after the parent read; it cannot finish during the snapshot, finishes before copying, and the final file still contains the original parent/network child (`136–177`). The short blocked-writer assertion alone is timing-dependent; the actual transaction assertion, later writer completion and decoded parent/child result provide the remaining evidence. This is one Wi-Fi snapshot case, not a claimed Cell equivalent.
3. No data and injected read failure copy no bytes and leave no spool (`179–196`).
4. A one-byte fixture ceiling reports an error without publishing bytes (`198–205`).
5. A destination that accepts eight bytes then throws reports error, removes the spool and remains caller-owned (`207–223`).
6. Staging cancellation escapes, publishes nothing and releases the snapshot/spool (`225–235`).
7. Cancellation triggered by the first destination write escapes without success or a second write, removes the spool and leaves the destination open (`237–261`).

The source qualification collaborators are explicit doubles backed by bounded real Room queries and valid portable DTO checksums (`268–337`). They bypass real retained-authority qualification. Child loading, encoding and the spool transaction/copy are production paths. These sources do not prove native admission, Hilt assembly, SAF deletion, manual/scheduled caller execution or actual Android lifecycle/battery behavior. All tests remain **authored, unexecuted**.

## Exact pins and remaining boundary

SHA-256 of exact UTF-8 excerpts, preserving existing line endings:

| Selected input | SHA-256 |
| --- | --- |
| Wi-Fi constructor excerpt | `29ac2e6195a72cf4265b3e2d719b40fcf8ac87260511734c943ca9bcafa76ce9` |
| Wi-Fi wrapper excerpt | `64d971902bf0c0b90fe24e758b1f77e090b82213c1269df1b977039643f3b053` |
| Cell constructor excerpt | `dedeba780ed07dae07915935c5e0312237880fc409fe6f7460087b9cbab5b0fe` |
| Cell wrapper excerpt | `a529c40c6a5b9fcdcdc982a501837db8745ba207f55c2a6dd014f248d342b1f0` |
| Whole new `RadioExportSpoolRoomTest.kt` | `6bb6a4b6b5199af553f8dbc84d4096d7a88586c6c5b334c0a6d7e8658ff28ef5` |
| Unchanged `FileBackedExportSpool.kt` | `21ca142d86eca8446dc095774d646fdf3bd5b10954a09a360bff570ff955972d` |

The Cell whole snapshot remains `5649a7e585d819e7a5f5d86fbb2923693186fac162694aa518457be3bc6e3fd7`. Wi-Fi's current whole snapshot is `ff4e59db354a70701b73a8534e8559995e58bb2a07645193673e5f51f5b45152`, differing from the manifest whole-file snapshot during concurrent native-detail work; all owned spool excerpts still match. No acceptance is inferred for those other bytes.

Blocking destination I/O cannot be forcibly interrupted by cooperative checks. Shared transfer admission, epoch fencing and physically waiting for writers before full clear remain separate required work. A private file deletion failure remains a reported error/recovery condition, not a promise that data was removed. Full exporter publication and final convergence/device evidence remain pending.
