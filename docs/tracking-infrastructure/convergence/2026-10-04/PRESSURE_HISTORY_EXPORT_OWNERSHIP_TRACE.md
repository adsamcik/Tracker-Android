# Independent Pressure History and export ownership trace

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Static review only. No source edits, Git mutations, executable gates, providers or recursive delegation. Existing approved native ownership lookup and source qualification are sufficient; no new storage, public API or architecture is proposed.

## Concrete gap in ordinary recent History

`PressureHistoryPageReader.selectRecent:80-109` reads evaluated imports and only the visible recent native page, then sends those directly to the legacy composer. It never calls the existing exact native-owner qualifier at 245-291. `PressureHistoryPageComposer:491-495` suppresses whole-entry equality only; a divergent same-entry copy or a different imported entry claiming a native run/window is otherwise converted into a public row with its measurements.

This path is user-visible through `DefaultTrackingHistoryRepository.observeRecentPressureOnlyEntries:585-594`. Shared and selected History already reject the same claim through the exact qualifier. The ordinary list therefore disagrees with the source-aware reader and can display duplicated or foreign-owned measurements as ordinary imported data. Offscreen or retained native ownership also escapes its visible-page comparison.

Smallest seam: use the existing `qualifyWithExactNativeOwners` on each selected import before composition, including `Retained.protectedIdentities`. It already queries only that import's entry/run/window keys, expands complete original native membership, applies the existing pure portable comparison, and returns Accepted/Suppressed/Unavailable with bounded integrity/budget distinctions. Reuse its existing failure mapping; do not invent a success row on qualification failure. Preserve the current native page, order, frontier and limit. Comparison-only owners must not become displayed rows; duplicate suppression must still respect whether the corresponding native row is actually represented in this result.

## Concrete gaps in date and exact exports

`PortablePressureRoomReader.readInTransaction:159-175` accepts portable-origin records after imported-lineage qualification. Its merge at 238-245 compares only entry identity against native entries selected for the dates. It never resolves imported run/window ownership against native owners outside that selected set. A foreign entry borrowing a native run or window is therefore appended, even when the native entry is selected; a conflicting same-entry import can also escape when its native owner is outside the selected dates.

`readSelectedInTransaction:110-129` similarly accepts each chosen imported entry, then `addEntry:79-92` compares only entry identity among entries explicitly being exported. An imported-only request can export a divergent native-origin copy; a foreign entry borrowing a native child can export alongside the native entry because its entry identity differs.

These snapshots are emitted by `RoomExportPortablePressure` after the Room transaction. The missing comparison is therefore an actual data-selection gap, not an external file-writing race.

Smallest seam: for each already-selected readable import, resolve its existing entry/run/window keys through `AppDatabase.nativePortableOwnerSeeds`, then `PressureHistorySelector.selectLogicalBySegmentIdsInTransaction` and `portableEntryForComparison`. Existing run ownership remains meaningful when a source is deleted or retained without numeric values. Apply the same foreign-owner and exact-content rule already used by shared History. Keep comparison owners internal: never append them to `entries`, never widen dates or selected IDs, and never substitute another native entry for the chosen import. An identical imported-only copy may still export as that chosen record; an identical native/import pair selected together remains one entry. A conflicting owner yields the existing `CONFLICTING_ORIGIN_IDENTITY`; bounded owner/member overflow yields `DEPENDENCY_OVERFLOW`; missing/integrity-invalid source authority keeps the existing unverifiable classification. Cancellation propagates.

Existing limits remain: requested-key SQL chunks of 256, at most 64 exact Pressure owner seeds with one-row lookahead, complete bounded member expansion, four-entry export chunks, existing total entry/run/window limits. Unrelated native history must not become a census or consume the requested export scope.

## Existing meaningful test seams

`PressureHistorySelectorTest.kt` already contains real Room fixtures, original native portable snapshots and actual importer operations:

- 1832: identical imported copy exports once and round-trips.
- 1875: divergent same-entry copy is rejected by shared History and date export.
- 1953/1966: unrelated incomplete native history beyond the former census cap and exact-copy suppression.
- 1990/2002: foreign entry claiming a native run, and foreign run claiming a native window.
- 2016/2029: deleted and retained native run ownership.
- 2054: actual imported retention operation produces a retained shell whose protected native run claim is rejected.
- 2200/2215: exact native+import pair and conflict before any sink call.

Extend these fixtures through `sourcePageReader().selectRecent`, both date and selected exports, and imported-only selected export. Include an independently valid import beyond unrelated-history caps, native owner outside requested dates/frontier, native value-free ownership, and a different original native window key under a foreign run. Inspect the emitted entries/empty sink and exact existing result reason, not only an abstract predicate. These proposed authored cases remain unexecuted.

## Inspected current-source SHA256

- PressureHistoryPageReader: `df969e83678410dabfb59f5010104e1ffb1e116f607195d52144dffb871e30db` (includes previously inventoried selected-import working prerequisite; not a whole-file selection approval).
- PortablePressureRoomReader: `6a635809b66ba252707beb9551cbe6baf1d40df41695c7608171c9d1822d84ad`.
- NativePortableOwnerLookup: `9959049e7a26cde6091c07f77675989cbb762c2c75699a6f685c90bd1e62da4a`.
- PressureHistorySelectorTest: `9228380a55aae3d6958c0999f772a779f5fab9014bac45d207b954f553000508`.

The previously reviewed five-case shared-reader packet remains accepted only for its narrow scope. Ordinary History/export parity is still open until its source repair and focused test source are frozen and reviewed. All 25 full-chain prerequisites and convergence/device/integration gates remain pending.
