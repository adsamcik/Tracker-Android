# Imported radio export date candidates

Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.
This is a source freeze for an isolated repair, not the convergence freeze or complete imported-flow readiness.
No compilation, tests, lint, schema generation/drift, diff check, device/UI/battery/provider execution,
CI, activation, Git mutation or integration was performed by this agent.

## Approved behavior and source trace

The approved smart selection includes a complete Wi-Fi/Cell session only when a saved source scan
or recorded gap touches the chosen dates. Its switch, original entry identities, whole-entry behavior
and outside-date observation count remain in `CapturedRadioDateSelection.kt` without changes.

- `ImportedWifiDao.exportCandidateRangePage` discovers only latest-revision entry metadata through
  exact entry/revision observation and run relations. A scan uses its saved possible-time interval;
  a gap uses the existing unresolved/retention flags and capture coverage. The key remains the
  newest physical member's start time and identity, descending.
- `ImportedCellDao.exportCandidatePageInWallRange` uses the same source-time rules, Cell's existing
  retention-gap flag and its original entry start/identity descending key.
- Integer types, checked uncertainty/coverage relationships, overflow, invalid original/run bounds
  and unknown time remain conservative candidates. Qualification reports unavailable/corrupt data;
  discovery does not silently skip a source whose time cannot be safely determined.
- `0..Long.MAX_VALUE` discovery preserves all entries, including empty gapless records, because
  a no-date export already includes all saved complete records. The final exact selector still
  receives the original nullable date scope and applies its existing rules.
- `ImportedWifiProductEvaluator.selectExportRangeInTransaction` and its engine implementation reuse
  the existing bounded page/read budget, lineage, epoch, deletion, retention and ownership checks.
  The existing history `selectRangeInTransaction` query and semantics remain unchanged.
- `ImportedCellProductReader.selectExportWallRangePageInTransaction` reuses the existing bounded
  candidate validation and product qualification. Its existing wall/structural history methods stay
  unchanged.
- Only the two held exporters' imported-page call names change. The separately reviewed Wi-Fi
  continuation tuple still uses `newestMemberStartTimeMs/newestMemberIdentity`, including the
  101-entry routing test. Seven true interface implementers receive explicit unexpected-export
  fixture stubs; there is no implicit delegation to header-filtered history.

No schema, index, SQLite function, new ownership storage or changed import-file rules were added.
The queries use the existing entry/revision/run/observation relations and index prefixes. Metadata
discovery remains an SQLite query over retained metadata; query plans and performance are unmeasured.
Qualification/materialization remains bounded to the current source page (100 plus Wi-Fi lookahead).

## Exact source selection

`IMPORTED_RADIO_EXPORT_CANDIDATE_FILES.json` records 20 current file hashes and selection rules at
HEAD `9b98d3f3acc10c75c295d177374ef7f3c510dd76`.

- `IMPORTED_RADIO_EXPORT_CANDIDATE_TRACKED.patch`: 12 entirely owned tracked diffs.
- `IMPORTED_RADIO_EXPORT_CANDIDATE_MIXED.patch`: seven narrow deltas relative to preserved drafts.
  In the Cell reader select only the new `selectExportWallRangePageInTransaction`; do not implicitly
  accept the older held `selectWallRangePageInTransaction` method. In the Cell test select only the
  two new date-reader tests; the earlier pagination test is a preserved independent draft.
  In the held exporters and three held test files select only their one method-name change.
- `ImportedRadioExportDateCandidatesRoomTest.kt`: one new complete test file.

The held exporter/test mixed hunks assume their preserved draft baseline exists. The two tracked Cell
hunks use only following committed context so the coordinator can select them without accepting the
older held method/test. The patch is not approval to stage the whole
held exporters, their spool/details helpers or the preserved Cell pagination draft. Whole-file hashes
describe the review snapshot; later changes outside an owned hunk require the coordinator to preserve
and separately reconcile those inputs.

## Authored evidence, not executed evidence

Sixteen new scenarios are authored:

1. Five actual Room Wi-Fi DAO scenarios cover uncertainty beyond headers, instant scans/gaps,
   inclusive possible end/exclusive date end, gapless exclusion, latest revision isolation,
   newest-member keyset paging, all-data empty entries and overflow clocks.
2. Five actual Room Cell DAO scenarios cover the corresponding Cell source rules and header cursor.
3. Two actual Wi-Fi importer/evaluator scenarios qualify saved scans beyond headers, exclude an
   unrelated corrupt run cohort, select a source-only gap and retain invalid-time failures.
4. Two actual Cell importer/reader scenarios cover equivalent qualification and gap behavior.
5. Two held exporter/Room/codec scenarios verify Wi-Fi dedicated-query routing, original whole entry,
   smart switch and outside-date count, and Cell real-reader zero-length gap routing. Wi-Fi product
   qualification is explicitly supplied in this file; the separate engine tests exercise it.

Required companion changes preserve the existing seven fake implementers and three exporter fixtures.
The existing 101-entry Wi-Fi cursor test retains its scenario and only names the new export API.
None of these scenarios was compiled or executed.

## Required remaining boundary

The existing global native owner census is **not solved** by candidate date isolation. One relevant
imported record can still become unavailable when unrelated native history exceeds its source-owner
budget or contains an incompatible owner shape. See `IMPORTED_RADIO_NATIVE_OWNER_LOOKUP_TRACE.md`.
The user/coordinator must reconcile the final source-owned lookup design before calling the whole
imported reader/export flow ready. No history-wide hash scan, reverse table or ownership-rule weakening
was implemented here. The held exporters also retain their other separately reviewed/unresolved slices.
