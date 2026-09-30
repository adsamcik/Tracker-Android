# Imported radio export mapping and cancellation

Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.

## Authored scope

Both held radio exporters now index the selected imported entry's `(run identity, observation identity)` once, then convert its child rows in their existing DAO order. This removes the previous per-child scan of all runs and observations. Each row must still belong to the selected entry, run and observation; its semantic revision comes from that exact selected observation. Existing V2 child constructors continue checking the revision-bound checksum. No child is dropped, reordered or given a new identity.

The existing imported-session loop checks cancellation for each evaluation. Conversion checks cancellation before reading the entry, while indexing observations, and before each child conversion. Background conversion checks cancellation during origin lookup queries, checksum/conversion loops and defensive duplicate handling. The earlier finite background collector and all size limits are unchanged.

Only the imported mapping call sites, cancellation hunks in `backgroundChildren`, and two new internal top-level mapping functions belong to this slice. The coordinator relocated both mapping functions without logic changes to `ImportedRadioExportChildren.kt` so those functions and the six-case test source can be selected independently. Exporter caller/background hunks remain held. The previous native `localChildren` functions remain frozen under `NATIVE_RADIO_EXPORT_OWNER_FILES.json`. Candidate prefilter/page-call changes belong to the product agent. Full exporters remain held.

## Static trace

- Wi-Fi `appendImportedSessions`: the existing evaluator's `isReExportable` and local collision checks precede the unchanged smart date selector and bounded exact import-revision child query. The new mapper receives that selected immutable entry and those rows.
- Cell `appendImportedSessions`: the existing reader's corresponding re-export/local-origin checks precede the unchanged date selector and bounded exact import-revision child query, then the new mapper.
- Both mapping functions build an observation revision lookup once and retain row order. Static complexity is proportional to selected observations plus children; no timing or benchmark claim is made.
- Background child selection, finite aggregate collector calls, origin qualification, native checksum checks, existing imported duplicate equality checks, and V2 output checksum checks retain their prior meaning.

The earlier possible imported retention-scope mismatch is **not present in the current re-export path**. `WifiImportedProduct.kt` defines re-export eligibility to exclude deleted entries/runs and retention-limited entries. `RoomImportedWifiProductEvaluator.kt` returns the complete latest immutable entry and reports retention limitations separately. `ImportedCellProductReader.kt` follows the same complete-entry/eligibility rule. Both exporters skip ineligible entries before querying children. No partial imported entry membership policy was changed.

## Authored scenarios

`ImportedRadioExportMappingTest.kt` contains six source scenarios:

1. Wi-Fi: two selected runs and 100 arbitrarily ordered rows; each run's observations are read once during mapping, and row order, values, revisions and checksums survive V2 construction.
2. Cell: equivalent mapping/order/value/revision/checksum coverage.
3. A child cannot borrow an observation from a different run or file.
4. A child checksum for an older semantic revision fails the unchanged V2 checksum contract.
5. An already-cancelled empty mapping does not read either source entry.
6. Cancellation while obtaining a Cell row prevents its conversion and further row reads.

These are coroutine conversion/portable-contract source scenarios with mocked selected-entry hierarchies and real child checksum constructors. They do not exercise Room authority, a complete file-writing job, cleanup, actual providers or physical device behavior.

## Evidence and remaining dependencies

Static source inspection and test-source authorship only. No tests, compilation, lint, schema checks or other convergence/device gates were executed. Independent review is pending.

This slice adds no schema, dependency, public API, format, date rule or output policy. The selected helper/test foundation depends only on existing selected child entities, portable entries, V2 child constructors and coroutine functions. Held exporter calls reuse the same package-local functions. Whole-exporter readiness still depends on the separately reported durable native Wi-Fi detail-version decision, snapshot/output boundary decision, and remaining full assembly review/evidence. This slice does not approve those paths as complete.

Exact source hashes and mixed-file hunk boundaries are in `RADIO_EXPORT_MAPPING_FILES.json`.
