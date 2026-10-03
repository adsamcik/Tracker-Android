# Imported radio export candidates: independent source review

Date: 2026-10-03. **IMPLEMENTED_UNVALIDATED**, review only, IMPLEMENTATION_ONLY.
No production edits, Git, compilation, tests, schema checks, device/provider work or other gates.

## Scoped result

No concrete defect found in the selected export-candidate slice. It is coherent to select the
12 owned tracked diffs, the new two-scenario test file, and the seven specifically bounded mixed
deltas. This is **not** acceptance of either whole held exporter, the preserved Cell header-page
method/test, the global native-owner census, or full source-transfer readiness.

I read the actual DAO queries, shared exact selector, qualification adapters, source calls, DI,
all seven explicit fake implementations and the authored scenarios, alongside the original user
requests and approved smart-date rule. All 20 current file hashes match
`IMPORTED_RADIO_EXPORT_CANDIDATE_FILES.json` at this inspection.

## Concrete branch trace

- `ImportedWifiDao.kt:511-567` selects the latest entry revision and matches observations and
  runs by **entry identity plus import revision**. The derived newest-member pair uses the same
  exact revision and tie-break as the existing source reader. The outer cursor predicate applies
  to the complete candidate selection and orders exclusively descending by that pair.
- `ImportedCellDao.kt:327-371` applies the corresponding latest-revision relations. Its outer
  parenthesized candidate predicate preserves the separate original entry-start/identity cursor.
  Neither query can select an older revision merely because its scan touched the dates.
- Both queries use `coverage_start < dateEnd` and `latest_possible >= dateStart`: the last
  possible scan instant is inclusive, while the selected date end is exclusive. Explicit gaps
  use interval overlap, with an additional equal-start/end branch for instantaneous gaps.
  Wi-Fi recognizes unresolved-provider or retention markers; Cell uses its existing retention
  marker. An empty run with no explicit gap does not acquire invented outage meaning.
- Clock type, nonnegative/ordering and uncertainty guards precede arithmetic in the `CASE`
  predicate. The `observed > MAX - uncertainty` check protects positive addition overflow;
  `observed < uncertainty` prevents a negative earliest possible instant. Invalid relationships
  are candidates for honest qualification failure rather than being silently omitted.
- The all-data `0..Long.MAX_VALUE` query shortcut deliberately retains empty gapless entries.
  The held exporters pass the **original nullable** date scope to the unchanged final selector
  (`CapturedRadioDateSelection.kt:37-114`), so this discovery shortcut does not broaden a scoped
  export. `dates == null` keeps all complete entries; otherwise a saved source observation/gap
  must touch the dates. The approved switch controls complete cross-date records, and outside-
  date observation counts still use each scan's own observed time. No record is cut or re-keyed.
- `RoomImportedWifiProductEvaluator.kt:124-161` factors only the candidate loader. History still
  calls `historyCandidateRangePage`; export calls the new query. Both share existing limit+1
  lookahead, page-shape validation, lineage/receipt, epoch, deletion, retention and ownership
  qualification. Cancellation is rethrown in `evaluateSafely` (`:175-176`) and existing bounded
  qualification loops retain their cooperative checks (`:244`, `:598`, `:624`, `:650`).
- `ImportedCellProductReader.kt:208-225` requires the enclosing transaction and existing bounded
  limit/cursor shape, then invokes the existing `evaluateCandidates`. It depends on no call to
  the preserved `selectWallRangePageInTransaction` (`:188-205`). Existing wall/structural history
  methods remain on their original queries. Existing qualification cancellation checks and
  cancellation rethrows remain in the reused path (`:327-328`, `:345`, `:502`, `:682`).
- The held exporters' owned changes are **only** the imported-query method names, at Wi-Fi
  `PortableWifiSourceExporter.kt:255` and Cell `PortableCellSourceExporter.kt:238`. Existing loops
  check cancellation per page and item. Wi-Fi continues from the last candidate's actual
  `newestMemberStartTimeMs/newestMemberIdentity` (`:279-284`), including candidates skipped by
  exact selection; Cell continues from its source's entry-start/identity pair (`:260-263`).
- `WifiImportedProduct.kt:109` adds an explicit source-owned export method. The existing
  `WifiCapturedPortableTransferModule` binds the same production evaluator. The seven concrete
  test fake objects implement an explicit unexpected-export failure, preserving history fixture
  assumptions rather than silently using header discovery for exports. Existing production
  history consumers still call the old range methods (`DefaultWifiHistoryRepository.kt:514`,
  `DefaultCellHistoryRepository.kt:844`). No new dependency, schema/index, provider demand or
  Location requirement was introduced.

## Authored evidence and limits

The claimed **16 new scenarios** correspond to actual source:

1. Five `ImportedWifiDaoTest` methods (`:270-345`) cover beyond-header uncertainty, inclusive
   possible-time edge, instant scans/gaps, absent-gap exclusion, latest revision isolation,
   newest-member paging, all-data empty records and invalid/overflow clocks. These exercise real
   Room discovery; their intentionally simple DAO payloads are not qualification evidence.
2. Five `ImportedCellDaoTest` methods (`:150-215`) exercise the equivalent real Room rules with
   Cell's retention-only gap and original-header cursor.
3. Two `RoomImportedWifiProductRecentScanTest` methods (`:221-266`) import canonical entries and
   use the actual evaluator. The touching observation extends beyond the saved header; an
   unrelated corrupt run is excluded before qualification. The gap-only and invalid-time paths
   assert an actual readable entry and typed unverifiable result respectively. The fixture helper
   changes keep the previous defaults (`:378-439`).
4. Two selected `RoomImportPortableCapturedCellTest` methods (`:77-130`) use real import and
   qualification for the same distinction, an instantaneous retained gap and an invalid-clock
   failure. Their `RunDefinition`, canonical checksum and importer helpers are existing source;
   the older held header-page pagination method/test is not needed by these additions.
5. Two new `ImportedRadioExportDateCandidatesRoomTest` methods (`:65-139`) exercise the held
   exporter/Room/file-codec routes. Wi-Fi uses the actual new DAO query but deliberately supplies
   qualification; it asserts the original whole entry, outside-date count and switch-off empty
   sink. Cell uses the real reader with a canonical receipt-backed zero-length gap and checks
   exclusion of a corrupt gapless cohort. Neither fixture asserts real provider behavior.

The previously authored 101-entry Wi-Fi paging scenario remains unchanged except for its mocked
method name. The three other held exporter fixtures similarly change only that method name.
None of these tests was compiled or run. Query-plan performance, Hilt/Room compilation, generated
schema compatibility, external-file behavior and runtime cancellation remain deferred gates.

## Exact pins and source boundary

| Frozen packet | SHA-256 |
| --- | --- |
| `IMPORTED_RADIO_EXPORT_CANDIDATE_FILES.json` | `f802a9dff19dafde7ca6616526a9d3ac822c5b9f965b449ebd0e8544fe1d63e2` |
| `IMPORTED_RADIO_EXPORT_CANDIDATE_SOURCE_BOUNDARY.md` | `14b2fd35f70bc09e878ef42cf1c2917e34c7a85e0b4e53c808ecc0fa831f2d59` |
| `IMPORTED_RADIO_EXPORT_CANDIDATE_TRACKED.patch` | `a593238e61bdf1c5dedbfced722a6b1cafb344341f58b7f51f029e820234a095` |
| `IMPORTED_RADIO_EXPORT_CANDIDATE_MIXED.patch` | `5957c574e1e359ce420c7557286e81ddecb2caf113d2636b816fe33cbd314ec8` |

The manifest pins all 20 reviewed current snapshots, including
`ImportedRadioExportDateCandidatesRoomTest.kt`
`69b4e8b3892c9dc7d20bf2290d4ed154b1dd5223a584a8a5b5965bdef53e7c99`.
Whole held file hashes identify the inspection input; they are not whole-file approval. In the
Cell reader select only the new method immediately before the committed structural-range method,
and in its test select only the two new tests before the existing setup. Retain the seven mixed
selection rules exactly; do not stage preserved exporter classes or earlier Cell page drafts.

## Remaining required work

`IMPORTED_RADIO_NATIVE_OWNER_LOOKUP_TRACE.md` accurately documents a separate current defect:
qualifying a relevant imported entry still depends on a global native-owner census. Unrelated
native history can exhaust that budget or invalidate the census. This slice removes unrelated
**imported date candidates**; it does not remove that native dependency, and I confirmed the
production calls still use it. Final exact duplicate/collision membership storage remains an
unanswered design decision. Do not close full imported-reader/export readiness or the assembly
gate from this narrowly coherent source selection.
