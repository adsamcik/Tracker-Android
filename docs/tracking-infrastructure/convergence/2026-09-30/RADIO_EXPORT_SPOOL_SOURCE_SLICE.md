# Approved radio export temporary storage — 2026-10-03

Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.

## Authority and scope

The user approved today's question about first building a complete Wi-Fi/Cell file in existing temporary phone storage, then copying it to the chosen folder. Exact answer: **“Use existing temporary export storage.”** This implements the recommendation in `RADIO_EXPORT_SPOOL_REPAIR_PROPOSAL.md`.

Only the two held exporters' constructor byte-bound seam and `exportWithSessionSelection` wrappers change. Existing selection, candidate paging, source-owned readers, child ownership/completeness, date expansion, identities, codec, native/imported mapping and finite child budgets remain intact. This receipt does not approve the rest of either preserved exporter draft or mark the complete source-file flow ready.

## Production trace

1. Resolve the existing source entry point.
2. Create the existing `FileBackedExportSpool` with a source-specific owned prefix and the unchanged radio 64 MiB ceiling.
3. Inside `spool.stage`, open the same whole-file Room transaction. Create the existing source codec writer against the private spool stream and run the unchanged `writeFile`, including parent/child reads and final codec flush.
4. Only a completed stage proceeds. After the Room transaction has returned and the stage stream has closed, copy the complete file to the caller's destination.
5. Return the saved record count only after copy and spool `use` cleanup complete.

Encoded overflow returns the existing error path, with no copy. No-data/source-read failures also send no destination bytes. Cancellation propagates unchanged; `use` closes/removes the private spool. Copy failures cannot produce a success result. The exporter continues leaving destination ownership to the caller, whose existing manual/scheduled error and cancellation paths close/remove partial outputs.

The existing utility's cooperative stage/copy cancellation checks are reused without edits. Its deletion failure continues producing an error rather than promising successful cleanup. A destination's blocking `write` itself cannot be forcibly interrupted by these buffer checks; the separate admitted-transfer drain/clear-all work is not completed by this wrapper.

The internal constructor permits a smaller byte bound for focused source fixtures, constrained to `1..RadioSourceFileV2.MAX_FILE_BYTES`, following the existing Steps exporter convention. The production registry uses the unchanged default. The source lambda remains the final argument, preserving existing trailing-lambda fixtures. There are no new dependencies, schema, public APIs, format variants or transfer frameworks.

## Focused test source

New `RadioExportSpoolRoomTest.kt` authors seven scenarios through the actual radio exporter, actual Room database, actual V2 codec and existing private spool:

- Both sources: while a destination write is deliberately blocked, an independent Room writer completes. The destination callback is outside a database transaction, the exporter has not yet returned success, and the final file keeps its original selected gap rather than the later inserted one.
- Wi-Fi: a competing child deletion starts after the background parent was read, cannot complete while the export snapshot is held, then completes before copying. The file retains its original parent and network child together.
- Both sources: no data and a source-read failure copy no bytes and remove the owned spool.
- Both sources: encoded-size overflow using the constrained smaller fixture bound copies no bytes and never succeeds.
- Both sources: a destination accepting a prefix then throwing returns error and removes the spool; the stream remains caller-owned.
- Both sources: staging cancellation propagates without destination bytes and releases the snapshot/spool.
- Both sources: cancellation from the destination write prevents a success count and removes the spool without closing the caller's stream.

The fixture supplies explicit source qualification doubles backed by bounded real Room gap/parent queries and valid portable checksums. Child reads, codec, snapshot/transaction and spool/copy behavior are production. This is not evidence of native provider admission, retained authority completeness, Hilt assembly, SAF cleanup or complete manual/scheduled caller execution.

## Inspection and evidence boundary

Read: repository guidance, settings/module builds, test conventions, module manifest, source callers/registry, existing spool, Steps/bundle staging, codec, candidate/child Room fixtures, and manual/scheduled cleanup paths. Static self-review traced ordering, exception/cancellation propagation, count acknowledgement, constructor callers and fixture contracts.

No Gradle, compilation, tests, lint, schema checks, diff checks, device/provider activity or Git mutations ran. All seven test scenarios are **authored, unexecuted**. Independent static review is pending. Whole assembly convergence, transfer lifecycle and physical-device evidence remain separate requirements.

Exact file snapshots and owned function excerpt hashes are recorded in `RADIO_EXPORT_SPOOL_FILES.json`; the excerpt pins distinguish this wrapper from concurrent native Wi-Fi marker edits.
