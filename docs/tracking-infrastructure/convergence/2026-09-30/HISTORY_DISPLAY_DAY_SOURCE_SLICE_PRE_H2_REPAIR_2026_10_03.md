# Displayed-day history source slice

Status: **IMPLEMENTED_UNVALIDATED**. Frozen for independent static review on 2026-10-03.
Parent-reported candidate checkpoint before this freeze: `5f6fc0e559`.

The initial input was preserved in `HISTORY_DISPLAY_DAY_FILES_PRE_H1_REPAIR_2026_10_03.json`
and `HISTORY_DISPLAY_DAY_SOURCE_SLICE_PRE_H1_REPAIR_2026_10_03.md` before the H1 repair.
Current input fixes the receipt-only erase filter identified by
`HISTORY_DISPLAY_DAY_FRESH_REVIEW_1003.md`. This remains authored source, not an executed pass.

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
   receipt integrity, native writer identity and `matchesCanonicalSample` succeed. The
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
  internal snapshot, fill them only after the existing exact receipt/sample integrity and
  writer checks, and leave ordinary qualification and public map result contracts unchanged.
- `AmbientWifiFactDao.kt` / `AmbientCellFactDao.kt`: two new observation-window methods only.
- `RoomAmbientWifiRepository.kt` / `RoomAmbientCellRepository.kt`: new History observation
  window entry point and narrow shared qualified-reader branching only.
- `UnifiedHistoryCandidateQueries.kt`: new displayed-observation query/helpers before
  existing `native` relation only.
- `UnifiedHistoryRangeReader.kt`: day Location dependency/imports, background Location
  eligibility, displayed-day discovery, selected day windows/Location points, optional
  additional Location coverage sections, sample identity propagation and source set only.
  Existing organization, archive selectors and native/imported backend remain held.
- `UnifiedHistoryNativeReader.kt`: bounded imported-point lookup, exclude those points
  from coincident legacy display intervals, and canonical sample ID propagation only.

## Authored scenario sources

Eighteen new scenarios; none executed:

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
- `RoomAmbientRadioDetailCompletenessTest.kt`: one new native source-day/display-midnight
  composed Room case plus narrow wall-offset/stored-zone fixture parameters preserving all
  defaults. Original fact/child integrity and retained authority fixtures are reused.
- `RetainedImportedAmbientRadioReaderTest.kt`: one new imported earlier-child and gap-only
  composed Room case. Actual existing portable summary import writes receipts; typed children
  use the existing exact owner/checksum storage fixture. This is not a complete v2 codec
  import execution claim.
- `UnifiedHistoryRangeReaderRoomTest.kt`: only the genuine legacy fixture's explicit
  `imported=false` parameter/default plumbing, correcting its former imported-file label.

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
