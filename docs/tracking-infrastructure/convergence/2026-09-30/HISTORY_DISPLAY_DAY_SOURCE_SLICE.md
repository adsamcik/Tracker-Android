# Displayed-day history source slice

Status: **IMPLEMENTED_UNVALIDATED**. Frozen for independent static review on 2026-10-03.
Parent-reported candidate checkpoint before this freeze: `5f6fc0e559`.

The initial input was preserved in `HISTORY_DISPLAY_DAY_FILES_PRE_H1_REPAIR_2026_10_03.json`
and `HISTORY_DISPLAY_DAY_SOURCE_SLICE_PRE_H1_REPAIR_2026_10_03.md` before the H1 repair.
Current input fixes the receipt-only erase filter identified by
`HISTORY_DISPLAY_DAY_FRESH_REVIEW_1003.md`. This remains authored source, not an executed pass.
The H1 input was separately preserved in `HISTORY_DISPLAY_DAY_FILES_PRE_H2_REPAIR_2026_10_03.json`
and `HISTORY_DISPLAY_DAY_SOURCE_SLICE_PRE_H2_REPAIR_2026_10_03.md` before the H2 repair.
H2 keeps authenticated erase results independent of optional context dependency limits.
The H2 input was preserved under `HISTORY_DISPLAY_DAY_FILES_PRE_H3_REPAIR_2026_10_03.json`,
`HISTORY_DISPLAY_DAY_H2_FILES_PRE_H3_REPAIR_2026_10_03.json` and
`HISTORY_DISPLAY_DAY_SOURCE_SLICE_PRE_H3_REPAIR_2026_10_03.md` before H3. The latest packet
preserves actual optional read failure reasons alongside authenticated erase IDs.

## Approved behavior and narrow boundary

The user approved grouping sessionless observations by the displayed local calendar day,
including Location, and assigning each Wi-Fi/Cell reading to its own observation day. Sessions
organize the same saved data. Changing the displayed zone does not rewrite stored UTC time,
source day/zone, identity, correction revision, consent or count authority.

This slice implements displayed-day discovery and selection in the existing unified range
reader and existing source-owned Room readers. It adds an optional presentation window to
the existing radio history sections. Original facts, children, source zones and gaps remain
unchanged. Existing ordinary and export date queries retain their previous selection.

The parent approved preserving independently authorized ambient Location siblings, exact
current imported copies and ordinary points whose historical proof is missing. Positive
dataset epoch/high-water erase authority withholds native debris; missing proof alone does
not hide saved points. Exact current archive membership can independently retain an imported
copy. A capture-scope fence cannot erase the independently qualified ambient sibling.

No schema, saved fact digest, source-midnight projector partition, portable format, export
scope, provider demand, UI layout, import conflict policy or overlapping-file membership
policy changes are part of this slice. The proposed new digest for future source-midnight
partitions remains awaiting approval. These files do not implement that proposal.

## Production trace

1. `UnifiedHistoryRangeReader.backgroundKeys` keeps Steps' existing count-domain calendar
   path. Location/Wi-Fi/Cell use bounded newest-observation metadata probes, then skip the
   whole displayed day. At most 101 day keys are sought per source, using the existing
   pagination merge/frontier. Day bounds use `ZoneId` and real DST transitions.
2. `UnifiedHistoryCandidateQueries.backgroundObservationBefore` discovers actual child or
   summary timestamps and explicit recorded gaps, including imported revisions. These are
   candidates only; source-owned qualification runs in the existing single Room snapshot.
3. New `effectiveLocalOverObservationWindow` / `importedOverObservationWindow` DAO methods
   discover parent records that can own earlier readings. New
   `readObservationWindowInTransaction` repository methods reuse the same retained native,
   imported, child completeness and correction qualification as the ordinary readers.
   Previous `effectiveLocalOverWindow` / `importedOverWindow` behavior is unchanged.
4. The range reader retains a fact only when an actual child timestamp is selected, or a
   summary-only legacy fact's own observed timestamp is selected. Explicit recorded gaps
   may produce a gap-only day. `BackgroundWifi.networks` / `BackgroundCell.towers` expose
   only the selected children; `readingCount` is unknown when saved detail is incomplete
   or the section is gap-only. Full source facts remain available unchanged in `read`.
5. `LocationPurposeDao.sessionlessHistoryOverWindow` reads bounded saved coordinates,
   continuity metadata, descriptive origin and exact current imported membership.
   `LocationReadScope` discovery respects retained floor and settlement, and deliberately
   performs no unchecked receipt-based erase. The existing source-owned
   `RetainedLocationContextReader` reports erased native sample IDs only after retained
   receipt integrity, native writer identity and `matchesCanonicalSample` succeed, before
   loading optional purpose, run, manifest, policy or fence dependencies. Later dependency
   limits and unavailable/error outcomes carry that same authenticated erase set; coroutine
   cancellation still propagates. An optional internal `historyProblem` carries actual SQLite
   storage or runtime integrity failures, distinct from expected absent/older proof. Both day
   and native compatibility History callers report that existing problem reason while
   preserving usable saved points. Existing map gaps remain unchanged. The
   day reader preserves qualified ambient siblings and exact current imported copies
   first, then withholds authenticated native erase debris. Old, blank, corrupt or
   mismatched proof alone never hides ordinary saved points. Only matching qualified
   ambient points are labelled qualified; other saved points remain usable with
   `LEGACY_UNVERIFIABLE`. Proof lookup failures produce a problem while preserving points.
6. Canonical `HistoryLocationPoint.sampleId` is carried through session, file and day history
   to identify the same saved point across sibling purposes. A file point does not join an
   unrelated legacy session just because its timestamp lies within that session. Genuine
   released legacy grouping is preserved; hidden/narrowed legacy intervals leave remaining
   points in day history. A window's missing details never become a measured zero.

## Selection inventory

`HISTORY_DISPLAY_DAY_FILES.json` records exact full-file SHA-256 snapshots for 11 production
and 6 test paths. Full-file hashes pin review input, **not permission to stage unrelated held
drafts**. Select only the method/class hunks listed in each entry. Parent owns Git.

Production boundaries:

- `UnifiedHistory.kt`: optional radio display windows and selected-detail getters;
  `HistoryLocationPoint.sampleId` and validation; `HistoryObservationWindow` only.
- `LocationReadScope.kt`: four new `HISTORY_*` / `SESSIONLESS_HISTORY` constants only.
- `LocationPurposeDao.kt`: `LocationHistoryDayRow`, `importedHistorySampleIds`,
  `contextReceiptsForSamples`, `sessionlessHistoryOverWindow` only, including the descriptive
  current-import Boolean used before applying an authenticated native erase.
- `RetainedLocationContextReader.kt`: append authenticated erased-native sample IDs to the
  internal snapshot, fill them only after the exact receipt/sample integrity and writer checks
  before optional context loading, and preserve them through subsequent limit/unavailable/error
  outcomes. The default-null internal History problem distinguishes actual read failures;
  ordinary purpose qualification and public map result contracts are unchanged.
- `AmbientWifiFactDao.kt` / `AmbientCellFactDao.kt`: two new observation-window methods only.
- `RoomAmbientWifiRepository.kt` / `RoomAmbientCellRepository.kt`: new History observation
  window entry point and narrow shared qualified-reader branching only.
- `UnifiedHistoryCandidateQueries.kt`: new displayed-observation query/helpers before
  existing `native` relation only.
- `UnifiedHistoryRangeReader.kt`: day Location dependency/imports, background Location
  eligibility, displayed-day discovery, selected day windows/Location points, optional
  additional Location coverage sections, sample identity propagation and source set only.
  Existing organization, archive selectors and native/imported backend remain held.
  H3 changes only the existing day problem assignment to include the retained reader's
  internal actual-read failure.
- `UnifiedHistoryNativeReader.kt`: bounded imported-point lookup, exclude those points
  from coincident legacy display intervals, canonical sample ID propagation, and H3's existing
  native compatibility problem assignment including the internal actual-read failure only.

## Authored scenario sources

Twenty-nine new scenarios; none executed:

- `UnifiedHistoryDayWindowTest.kt` (new whole file): Wi-Fi half-open midnight selection and
  immutable fact/day; Cell exact timestamp/details; legacy missing-detail count stays unknown.
- `UnifiedHistoryDayGroupingRoomTest.kt` (new whole file): UTC versus New York; Prague
  23-hour day; 251 sparse days and stable 37-row paging; imported versus genuine legacy
  interval membership; narrowed legacy interval; explicit 4097-point day overflow.
- `RetainedLocationContextReaderTest.kt`: seven added day-history cases for source Off,
  absent older proof, positive epoch/high-water debris versus exact current imported copy,
  independent ambient sibling versus capture fence, and blank/corrupt/canonical-mismatched
  receipts after an epoch/high-water erase boundary. Existing fixtures unchanged.
  The last case installs the valid ambient receipt and an old captured-purpose marker;
  it is not evidence of a real capture writer or provider lifecycle.
  H2 adds four direct Room qualifier cases for the optional run dependency limit with
  authenticated high-water erase, stale dataset erase, a blank receipt, and corrupt or
  canonical-mismatched receipts. Two separate composed day cases check native debris versus
  exact current import and blank-proof saved-point preservation with the same dependency
  limit. A shared 101-run metadata fixture exercises the real bounded query; those markers
  carry no capture grants and claim no real capture writer/provider behavior. The four direct
  methods and that fixture have no held R11 backend reference and can be selected separately.
  H3 adds three direct Room qualifier methods: an optional policy-table read failure preserves
  authenticated erase IDs with `STORAGE_UNAVAILABLE`, an invalid optional purpose interval
  decoded through Room preserves IDs with `INTEGRITY_UNAVAILABLE`, and blank older proof has
  no actual-read failure. A separate held day method checks an exact current imported point
  survives an actual optional SQLite read failure with an explicit problem.
- `RoomAmbientRadioDetailCompletenessTest.kt`: one new native source-day/display-midnight
  composed Room case plus narrow wall-offset/stored-zone fixture parameters preserving all
  defaults. Original fact/child integrity and retained authority fixtures are reused.
- `RetainedImportedAmbientRadioReaderTest.kt`: one new imported earlier-child and gap-only
  composed Room case. Actual existing portable summary import writes receipts; typed children
  use the existing exact owner/checksum storage fixture. This is not a complete v2 codec
  import execution claim.
- `UnifiedHistoryRangeReaderRoomTest.kt`: only the genuine legacy fixture's explicit
  `imported=false` parameter/default plumbing, correcting its former imported-file label,
  plus H3's one held native compatibility method checking both internal returned failure
  reasons. That method uses real saved-point/legacy Room reads and a mocked retained-reader
  result; it does not claim actual native provider or retained query failure execution.

## Remaining whole-feature dependencies and evidence

- New v6 source-midnight parent partition/digest remains undecided and unimplemented here.
  The radio Room scenarios cross **display midnight within a valid saved source day**.
- The broader held R11 sources, DI, organization/archive exact membership and final polished
  History UI require their own coherent review/selection. This slice does not prove all
  six source types, full R11 completion or all 25 assembly prerequisites.
- Current Location save/capture/ambient proof writers and retained authority helpers are
  preexisting dependencies; this slice creates no native authority or sensor request.
- Source checksums, SQL/Room generation, compilation, paging and all written tests remain
  unexecuted. No Hilt, migration, device, battery, provider, UI or convergence gate result
  is claimed. No integration into local `dev/v10` or readiness claim is made.

Static self-review inspected the changed method bodies, DAO projection/column names, current
source qualification seams, section consumers and scenario fixture constructors. Fresh
independent review and the eventual approved convergence batch are still required.

## H1 repair boundary

Only four production files and one existing test file changed after the first freeze:
`LocationReadScope.kt` (conservative `HISTORY_RETAINED`), `LocationPurposeDao.kt`
(`LocationHistoryDayRow.hasCurrentImportCopy` and matching query projection),
`RetainedLocationContextReader.kt` (authenticated erase IDs in its existing qualification
loop/internal snapshot), `UnifiedHistoryRangeReader.kt` (sibling/import-first selection),
and the three added `RetainedLocationContextReaderTest.kt` cases. No Location replacement
implementation, export predicate, schema, source-midnight digest/projector or UI action changed.

## H2 repair boundary

Only `RetainedLocationContextReader.kt` and `RetainedLocationContextReaderTest.kt` changed
after the H1 snapshot. The reader authenticates bounded receipt/sample candidates and dataset
erase authority before optional context loading. Every later limited/unavailable outcome keeps
the authenticated erased IDs, including optional database/runtime failures; cancellation is
rethrown. The existing candidate-size/uniqueness guard and missing dataset guard still return
unavailable before authentication, because those inputs cannot supply bounded exact authority.
The day reader already provides bounded unique candidates. No ordinary saved-point SQL, public
API, storage, export scope, source-midnight digest or Location replacement behavior changed.

Six focused test methods were authored: four direct qualifier/Room methods and two held composed
day methods. The run-limit path is covered in source; other optional overflow/error branches
were statically inspected and have no separate executed coverage claim. Exact native samples
remain physically saved in these fixtures, so the tests distinguish presentation/authority from
deleting data. Fresh independent review and all deferred convergence gates remain required.

## H3 repair boundary

Only three production and two existing test paths changed after the preserved H2 packet.
`RetainedLocationContextReader.kt` appends a default-null internal `historyProblem` and sets
existing storage/integrity reasons only in its optional SQLite/runtime catches. The same
authenticated erased IDs and existing map gaps remain in each result; expected absent/old
proof and cancellation behavior are unchanged. `UnifiedHistoryRangeReader.kt` and
`UnifiedHistoryNativeReader.kt` each add the internal reason to their existing problem
precedence. The latter two files remain held except for these narrow reviewed caller hunks.

Three new direct qualifier methods in `RetainedLocationContextReaderTest.kt` are independently
selectable with the core reader: actual Room optional SQLite failure, actual Room purpose
constructor failure, and expected blank proof without error. A fourth method in that file is
held with the composed day reader. One new held native compatibility method in
`UnifiedHistoryRangeReaderRoomTest.kt` asserts both returned typed reasons at the existing
retained-reader seam. The four direct H2 methods and their run fixture remain unchanged.
`RetainedLocationContextReaderTest_DIRECT_H2_H3.kt.txt` supplies the requested desired core
test snapshot based on the read-only HEAD source: original six methods/fixtures plus only the
four direct H2 methods, three direct H3 methods and run fixture. It contains no held day/native
method or R11 reference. No schema, public API, export, source identity, Location replacement or provider
behavior changed. All five new methods are authored only; none was run.
