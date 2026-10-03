# Exact session Location export — independent source review

2026-10-03. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewed the original user requirements and approved shared session preview/write behavior, exact four-path selection, actual source/tests, selected mixed DAO before/after, and the independently cleared eleven-path longest-trip prerequisites. No production edit, Git operation, compiler/test/gate, provider activation or recursive delegation was performed.

## Selected source result

No additional membership, scope-widening or dependency defect was found, but the global-revision reliability finding below remains open. Its source dependencies are present in the separately selected longest-trip packet; it adds no schema, public port, provider demand, registry or bundle UI. Any selection is prerequisite-only and must preserve this explicit open gap. The mixed DAO review is only `rawLogicalEntrySegmentPage`, not its two held working hunks or any R11 consumer.

- `SessionLocationFileReader.kt:36–57` resolves one exact organization identity, rejects active/incomplete selections, and checks expected organization revision, original collected-data epoch, existing evidence revision and retention settlement. A hidden/missing adapter or malformed imported selector does not become a time-range fallback.
- Native selection at `:95–159` traverses every 256-row run and raw segment page. Reciprocal bindings, unique segment run IDs and complete run/segment counts prevent orphan rows from disappearing in a presentation join. Original completion/retirement and time bounds are checked. Source-specific capture fences omit the erased contribution while retaining an independently saved sibling. The native Location query selects explicit purposes and run/segment membership, not coincident timestamps or edited display dates.
- Legacy selection at `:162–167` retains original closed intervals and real singleton timestamps, excludes receipt-bearing other native/ambient points, and excludes imported-only identities. A later archive that deduplicates into an originally local point does not remove that local membership. Migrated logical legacy intervals cannot overlap and duplicate observations.
- Retrospective annotations intentionally select actual saved points in `[from,to)` at `:81–89`; missing coordinates and real observation spacing remain unchanged. This is organization of existing data, not new recording permission.
- Imported selection at `:170–205` reads only declared archives and sample IDs, requires current archive epoch, retained complete membership, contiguous ordinals and exact portable-ID binding, and deduplicates overlapping archives by canonical sample ID within the existing format bound. Missing members fail before destination copy. Saved/imported origin labels remain descriptive; they confer no collection or map-enrichment authority.

`LocationFileExporter.kt:104–138` stages identity writes and the existing codec in one Room transaction/private spool, then closes that snapshot before arbitrary destination I/O. It rechecks source/organization/retention input before and after copying. Cooperative cancellation propagates through reader pages/items and the existing spool, with private-file cleanup. Post-copy failure may leave bytes in the caller destination; the still-unfinished outer transfer owner must discard them and withhold handoff. This helper does not itself publish a partial bundle.

## Concrete remaining reliability finding

**Unrelated recording invalidates selected Location export.** The request requires the global `source_evidence_state.revision` at `SessionLocationFileReader.kt:40`, and `verify` at `:55–56` compares the full global evidence row. Unrelated ongoing source writes also increment it (`DurableSourceIngress.kt:461,527`; ambient Wi-Fi/Cell projectors `:486,:475`). Thus a closed Location session export rejects between preview/staging/copy when only independent background data changes. This conflicts with the approved source-independent collection/export behavior and the rule that changed *selected inputs* should stop the export. The coordinator confirmed this is a concrete reliability gap. Repair selected-input qualification while preserving collected-data epoch, erase/retention, organization and actual selected-data change fencing, preferably before selecting this packet, and author an actual unrelated-write-success plus selected-write-rejection case. This review introduces no replacement token/storage design and does not silently remove a safety check.

The all-six availability preview, session detail choices/exclusions, annotation-bearing bundle write/import path, external destination cleanup/handoff and pending radio/native Steps membership decisions remain excluded. Pending Location Replace must eventually feed effective saved values without bypassing exact membership. No full R17/R18 or assembly completion is accepted.

## Authored scenarios and evidence limit

Exactly **17 new unexecuted methods** use the real Room database, exporter, codec and private spool. Their source checks cover native replacements and unrelated points; display edits/stale revisions; original legacy bounds/import exclusion; migrated intervals; real singleton observations; retrospective end exclusion and missing coordinates; exact overlapping archives/missing members/native duplicate origin; 257 runs and 257 tail points; missing/orphan members; erased sibling independence; missing original end; stale epoch/revision; current corrections and post-copy mutation; organization mutation; and staging/copy cancellation with cleanup. They do not prove provider capture, migration, real End/Off lifecycle, native Android I/O, Hilt, device behavior or performance. None was compiled or run. All 25 full chains remain pending.

## Exact pins

Hashes were read directly and match the frozen packet; actual dirty DAO bytes are not the selected DAO snapshot.

| Input | SHA-256 |
| --- | --- |
| `SESSION_LOCATION_EXPORT_FILES.json` | `fbe21d9dc4a663307f266469686fde2285878bd9f92403d77868de29723ae7c4` |
| `SESSION_LOCATION_EXPORT_SOURCE_BOUNDARY.md` | `cb9d2454fa65c6d8712e30abc368ca944200f936e46eb2c4b372c95201dd7779` |
| `SESSION_LOCATION_EXPORT_SELECTION.patch` | `8d88bfd2a9e163d3b795af7a9fd5903c69910faf01a2da7582c2d35e8f6a86f5` |
| Selected `TrackingHistoryReadDao.kt.after` | `1b380ae3222d263601f793dcb811322999a9865ab728f7164d1fa64e884dbba1` |
| `LocationFileExporter.kt` | `806eccbf2e510ab16145c81d2a5a88db5683ed7eebe927ade7e45391c11fba4e` |
| `SessionLocationFileReader.kt` | `3718bc20449e33e5bfb8ac60c5a5298c9fe34818eb9b55b4fe1d2dc6bba27b38` |
| `SessionLocationFileExporterTest.kt` | `ee02f45cf3f04b6834fab4b7df46788def45ff058b6d8183b5f2c41b4ec3fce7` |
| Required longest-trip manifest | `658580e07b43d3486b1066c3093bcc9ce0cb52d2d2610b573f43a1082814b76c` |
| Required longest-trip patch | `f243043752f61134b335d6d8f46acb08dd79a3860c0503811085961a4bbdc75a` |

## 2026-10-03 corrected selected-content recheck

The preceding global-revision finding and its original input pins are preserved as review history. **That finding is now closed by static source inspection of the repaired packet.** No additional concrete defect was found in this four-path backend prerequisite; this is not completion of the session export UI, preview port, complete bundle or any full assembly chain.

`SessionLocationFileRequest` now contains only the exact organization identity/revision and original collected-data epoch (`SessionLocationFileReader.kt:19–26`). Its snapshot compares those values, organization fields and existing retention/deletion state (`:34–55`); global evidence revision and updated time do not participate. The existing `savedFileExportAuthorityStamp` contains full-delete epoch/high-water and retention settlement, not ordinary source revision. The high-water is written by the existing full-deletion operation, not unrelated ordinary recording.

`LocationFileExporter.kt:106–120` stages the existing typed Location file in one Room snapshot, then reserializes the exact same selected contents before and after destination copy. `writeSessionPoints` (`:158–188`) uses the same codec, measurement conversion, portable IDs and descriptive origin labels for all three passes. The checksum is transient SHA-256 over the real emitted UTF-8 bytes, including the flushed end record; it is neither stored nor used as a file authenticity rule. The unchanged codec enforces its existing byte/point limits on verification too. Only initial staging can call `localIdentityFor`; verification selects existing identities with `bySampleId` and rejects missing bindings without creating new ones (`:173–175`). Epoch/organization/retention checks occur before and after each read-only verification transaction. The database snapshot is closed before caller destination I/O.

The complete native/legacy/imported/retrospective membership trace above remains applicable. The selected mixed DAO hunk is unchanged and still excludes both held R11 hunks. Repeated traversal remains keyset paged and cancellation-aware; no new schema, store, public interface, source demand, registry or framework was added. Post-copy failure still requires the eventual outer transfer owner to discard bytes and withhold handoff. A future all-six preview/write port must connect the approved preview semantics; this internal prerequisite does not expose that product action.

All **24 authored methods remain unexecuted**. The seven new actual Room/exporter/codec/spool methods cover unrelated saved Steps plus Wi-Fi and revision increments before and during export; exact archive membership loss with a coincident nonmember; newly saved background origin; lost portable identity without recreation; post-copy epoch change; and cancellation during content verification. The existing correction method now mutates selected measurements during copy without incrementing global revision, so its source assertion meaningfully targets content comparison rather than the removed guard. The new unrelated-source fixtures save actual StepInterval/WifiObservation rows and explicitly update the existing global revision, then assert successful unchanged Location output; they do not run real sensors. The other 17 methods remain part of the reviewed selection. Runtime stability, performance, generated Room/Hilt wiring and all 25 full chains remain unverified.

### Repaired exact pins

| Input | SHA-256 |
| --- | --- |
| `SESSION_LOCATION_EXPORT_FILES.json` | `d5a95877939ba77fa6b09cec1f8049492f3f4527d0b786cc44bcd3cab87120b6` |
| `SESSION_LOCATION_EXPORT_SOURCE_BOUNDARY.md` | `69b1dd9d6f24997dd4063726dcedbc64fab51138bae459cc49080451a610496c` |
| `SESSION_LOCATION_EXPORT_SELECTION.patch` | `32e52e090f130f42e015e666e4aab9ae53999a25c534343a5252f04144431fbd` |
| Selected `TrackingHistoryReadDao.kt.after` (unchanged) | `1b380ae3222d263601f793dcb811322999a9865ab728f7164d1fa64e884dbba1` |
| `LocationFileExporter.kt` | `0ff1f59cfa051be27734d63d07a91a43b0a0081762df061124ffa0b25d58c349` |
| `SessionLocationFileReader.kt` | `d49f75d43695d751d72bf7b0351b72ab0e7645da7b4a6b6eb6ae044805bafcc3` |
| `SessionLocationFileExporterTest.kt` | `571d7933ebb78b6f94544a553336f1cddb2cfcb0150e168f8c990d0c83ef8eed` |

The separately reviewed longest-trip manifest `658580e07b43d3486b1066c3093bcc9ce0cb52d2d2610b573f43a1082814b76c` and patch `f243043752f61134b335d6d8f46acb08dd79a3860c0503811085961a4bbdc75a` remain prerequisites. All source and selected snapshot hashes were read directly and match the repaired manifest. No gate or Git operation was executed during this recheck.
