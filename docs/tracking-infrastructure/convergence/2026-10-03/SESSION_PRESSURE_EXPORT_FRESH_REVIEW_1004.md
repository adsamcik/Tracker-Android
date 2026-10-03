# Exact session Pressure export — independent source review, 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review by
`/root/fresh_bundle_context_review_0930`. Original user requirements and approved
answers, candidate `AGENTS.md`, the frozen six-path patch and actual source were
read. Parent supplied base `2d01ed0ee411bf2bc48d4c45fe070de6b945f102`; the packet
records the earlier clean-at-start base for its first five paths. No Git, build,
test, schema, device, provider, CI or integration operation was performed.

## Result

No actionable defect found in this exact six-path selection. It is coherent for
an **unvalidated prerequisite commit**, without claiming the session export
feature or any full tracking chain complete.

## Concrete source trace

- `PressurePortableTransfer.kt:15–54` adds one finite exact-record overload to
  the existing contract. Native segment seeds, imported entry IDs and the original
  data epoch remain distinct. There is no date fallback, provider demand, new
  persistence, dependency or format.
- `PortablePressureRoomReader.kt:58–134` reads the complete selected snapshot
  before any sink call. Native seeds must exist and pass the existing logical
  membership expansion and Pressure selector. `PressureHistoryBatchReader.kt:338`
  checks every original run's segment/logical/run correspondence, including missing
  replacement members. Imported selection uses `ImportedPressureDao`'s exact IDs
  and `ImportedPressureHistoryEvaluator.evaluateCandidateInTransaction`; it does
  not invoke a global native census or range selection. Its existing lineage
  checks retain correction, child completeness, original epoch, deletion and
  retention restrictions. Equal selected contents with the same portable identity
  are deduplicated; divergent contents fail before emission.
- Existing four-candidate batches traverse every requested ID. Existing
  entry/run/window and dependency bounds refuse excessive selections instead of
  exporting a truncated tail. Cancellation is checked between batches and records.
  Materializing/failed native members and unavailable imported retained payload
  cannot become a successful empty or replacement export. Native retention-loss
  markers preserve the loss without inventing measurements.
- `RoomExportPortablePressure.kt:52–83` uses the existing IO dispatcher, releases
  each Room snapshot before sink IO, then rereads exactly the same selection.
  Full typed-content equality preserves the selected-data/epoch fence without
  depending on unrelated global evidence revision changes. Cancellation propagates;
  failed final verification requires the consumer to discard staged bytes, as
  documented by the new API. The future caller still owns organization and final
  destination handoff fencing.
- `PortablePressureRoomReader.kt:136–142,204,270–321` repairs the existing range
  reader's missing closed logical header and separately preserves original saved
  run bounds. The shared wire contract requires the entry envelope to equal run
  minimum/maximum bounds; extending it to the session cutoff would be invalid.
  Original windows, uncertainty, duration and values are unchanged. Missing/open
  headers remain unavailable rather than supplying invented bounds.
- The existing Hilt binding already provides the sole production implementation.
  The only explicit interface fake, in `PortablePressureExporterTest`, supplies
  the new overload and rejects its accidental use by ordinary date exports.
  Existing module dependencies cover the new types and coroutines.

## Authored coverage and limits

Read the **20 new unexecuted methods**: ten native Room fixtures, nine imported
Room fixtures and one actual JSON codec case. They exercise complete replacements,
exact exclusion, missing IDs/windows, corrected content, retained gaps, native
materialization, deletion and epoch changes during sink IO, unchanged content
despite unrelated writes/revisions, equal/conflicting identities, cancellation,
traversal beyond one batch and the closed-session/source-envelope regression.
The fixtures call the actual selectors, importers and repositories; the JSON case
uses the actual encoder/decoder with the public typed result. They are separate
module fixtures, not an executed or authored single Room-to-file end-to-end test.

The shared session preview/write port, default available-source selection, UI,
annotation metadata, final handoff fence and bundle import remain outside this
packet. Retrospective timestamp filtering and unresolved other-source file
membership are not assumed. All 25 full-chain prerequisites remain open.

## Exact pins

All six actual working hashes matched the selected-after hashes on review.

| Path | SHA-256 |
| --- | --- |
| `stats/api/.../PressurePortableTransfer.kt` | `7a228737e81b2e514f2290702a69940d098ffd449c37cedc97a98bac1491a46c` |
| `stats/data/.../PortablePressureRoomReader.kt` | `6a635809b66ba252707beb9551cbe6baf1d40df41695c7608171c9d1822d84ad` |
| `stats/data/.../RoomExportPortablePressure.kt` | `33671149418111c0f425f0ba1206b0c638af6ec92706fa48cd5f8c19471a5830` |
| `stats/data/.../PressureHistorySelectorTest.kt` | `8be37fe46eaa239694018f7dcf19957c127993c501b377b713b57de40294d609` |
| `stats/data/.../ImportedPressureHistoryEvaluatorTest.kt` | `7ba48323cf15712663b0b6b84529162a6382d54daef600abf3993e46d60b10ee` |
| `feature/import-export/.../PortablePressureExporterTest.kt` | `46a005ba7b1557b541bc70fee2c55d9fe19da84fe7d6c2bac0eea8abd542cf99` |

Full paths and before hashes are in `SESSION_PRESSURE_EXPORT_FILES.json`.
Manifest: `50bb42e7ad037090312422d59c783cb38404b310b6685ce70bb49c194699acc7`.
Patch: `9a30b96910eca488573d392fe709b4848184321591efbb7dd4a4f9727977eea8`.
Boundary: `fbecb35d26c33d267de0279d63f937d5a19ede62b59f1b81e174c318df2678e8`.
