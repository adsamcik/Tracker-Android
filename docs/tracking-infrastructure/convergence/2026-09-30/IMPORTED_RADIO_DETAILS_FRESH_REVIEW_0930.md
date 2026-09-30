# Independent imported radio detail review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Independent static source inspection only. No compilation,
tests, schema checks, device activity, Git mutation or integration was performed.

## Reviewed selection

`IMPORTED_RADIO_DETAILS_FILES.json` owns the two imported detail helpers and their two test
sources, and selects only `CELL_PRESERVED_DETAILS.patch` from the mixed
`DefaultCellHistoryRepository.kt`. The R11 selected-import bridge is excluded. I traced the
existing mappers, imported parent/child entities and DAOs, V2 source-file validation and
attachment, native current-child query, Cell composer authentication, and shared-page call sites
as dependencies. The full inherited user requirements and approvals apply.

## Remaining concrete findings

1. **Wi-Fi shared-page cancellation is swallowed.**
   `DefaultWifiHistoryRepository.kt:335` calls the suspending `withImportedWifiDetails` in
   `recentInTransaction`; its `RuntimeException` handler at line 343 catches
   `CancellationException` and returns `IMPORTED_EVIDENCE_UNVERIFIABLE`. The selected Cell
   companion already adds the corresponding cancellation rethrow. Add the same narrow rethrow
   to Wi-Fi before the generic handler, with an authored cancellation scenario at this seam.

2. **The detail budget includes erased children and misclassifies valid overflow.**
   `ImportedWifiHistoryDetails.kt:20-22` and `ImportedCellHistoryDetails.kt:20-22` load the whole
   entry/revision with a 4,097-row limit and check the 4,096-row budget before filtering to
   surviving owners at lines 29 and 28 respectively. Consequently a deleted run with many
   retained child rows can hide a small valid surviving run. A valid multi-scan V2 file can also
   exceed this detail budget: `RadioSourceFileV2.kt:20` permits 262,144 children per file, and
   session validation does not impose a 4,096-child-per-entry limit. The generic check becomes
   an integrity failure rather than the existing honest read-budget outcome. Query only the
   exact visible owners in finite chunks and map genuine remaining overflow to
   `READ_BUDGET_EXCEEDED`; do not silently truncate a network/tower list or broaden eligibility.
   Add real Room sources for deleted-child overflow with a surviving sibling and genuine
   visible-detail overflow.

3. **Cell file validation accepts technology values that its reader cannot present.**
   `RadioSourceFileV2.kt:251` accepts any nonblank `radioType` of at most 32 characters.
   `ImportedCellHistoryDetails.kt:54` requires `CellHistoryTechnology.valueOf(row.radioType)`.
   A structurally valid, correctly checksummed edited file can therefore import successfully
   and only later become unreadable. Validate this field against the existing supported Cell
   technology values at the file boundary. This is an input-contract companion outside the
   frozen helper selection, not a request for a new technology mode or schema.

## Static strengths and scope

- The qualified public mapper and helper derive surviving observations in the same order. Wi-Fi
  also filters its explicit retained observation identities. Empty/unavailable product shells
  receive no details.
- Existing optional detail groups remain valid summaries. A present group must match its
  parent's complete declared count; partial groups fail rather than showing a truncated list.
  Parent observation identity, run identity, import revision and semantic revision are retained.
- Each surviving child checksum uses the actual parent semantic revision. Both uncertainty
  endpoints must fit the original measured interval with overflow-safe arithmetic. A recomputed
  checksum cannot widen that interval.
- Deleted-run rows are excluded from checksum/time/count qualification. The remaining budget
  issue above occurs earlier, during the whole-entry load.
- The Cell patch obtains native children through the exact writer/fact/latest-cursor join,
  finite 32-fact chunks and cohort budget, and passes exact revision groups through the existing
  WAL/purpose qualification. It introduces no coordinates, recording authority, demand, schema
  or source coupling.
- The selected Cell individual, shared-page and eligible-import catch branches preserve
  cancellation. New Wi-Fi helper calls expose the sibling cancellation gap above.
- Six new Cell test-source cases use actual Room and the production parent importer; six new
  Wi-Fi cases use actual Room parent/child DAOs and a qualified-product fixture at the detail
  seam. The Wi-Fi fixture is not full importer-authorization evidence. Tests cover complete
  details, absent summaries, partial groups, semantic-revision mismatch, rehashed measured-time
  violations and deleted-run isolation. They were inspected, not executed.

This receipt does not close full R04/R11, exact radio archive duplicate membership, export UI,
session bundles, the convergence gate or device evidence. The three findings require narrow
source repair/recheck before claiming this detail selection closed.

## Dated static recheck — 2026-09-30

The three findings above are closed by static source inspection of the repaired selection in
`IMPORTED_RADIO_DETAILS_FILES.json`. All seven whole-source hashes and both exact mixed-path
patch hashes match that manifest. The original finding history remains above; this is not
execution evidence.

1. `WIFI_IMPORTED_DETAILS_RECOVERY.patch` adds the explicit cancellation rethrow around shared
   recent-entry detail attachment. The authored actual Room scenario cancels after imported
   product qualification, then requires cancellation to escape without returning a failure
   page (`ImportedWifiHistoryDetailsRoomTest.kt:160`). The detail helper's pre-query
   `ensureActive` reaches that exact catch seam. The corresponding Cell selected catch paths
   retain their cancellation rethrows.
2. Both detail helpers now derive exact surviving owners first and query only their entry,
   import revision, run and observation IDs. The DAO queries use finite 64-owner chunks and a
   remaining-budget-plus-one limit; erased or unretained siblings cannot consume the detail
   budget. Genuine surviving overflow produces the existing `FAILED`/`NONE` entry with
   `READ_BUDGET_EXCEEDED`, keeps its original selection and returns no partial child list.
   The selected Cell eligibility companion maps that cause to the existing
   `SOURCE_READ_BUDGET_EXCEEDED` outcome. Authenticated parent semantics, complete optional
   groups, checksums and measured uncertainty bounds remain enforced. Actual Room sources
   cover deleted-sibling overflow and genuine surviving overflow for both sources; the Wi-Fi
   source also covers explicit retained-owner filtering.
3. `RadioSourceFileV2.kt:253` admits only the existing six
   `CellHistoryTechnology` enum names for session and background children. The codec revalidates
   decoded children and converts malformed values to its existing format error. Authored
   constructor and rehashed-codec cases cover unsupported, lowercase and empty values, and
   preserve all six supported values. No new radio mode, schema or marker is introduced.

No additional concrete defect was found in this repaired, bounded detail selection. Its source
dependencies are coherent for an implementation-only local commit. This closure excludes the
R11 selected-import bridge, full radio export/archive membership, bundle completion, product
surfaces, assembly readiness and device evidence. Compilation and all validation gates remain
deferred; the inspected tests have not been executed.
