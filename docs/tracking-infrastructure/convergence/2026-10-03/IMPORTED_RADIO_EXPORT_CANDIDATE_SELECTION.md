# Imported radio export candidate selection

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Source selection only; no
assembly freeze, executed checks, provider activation or integration.

## Selected core

From `../2026-09-30/IMPORTED_RADIO_EXPORT_CANDIDATE_FILES.json`, select the twelve
paths whose rule is `entire current tracked diff only`. The two DAOs discover
latest-revision records from real scan times/uncertainty and explicit gaps rather
than only recording-header bounds. Separate source-owned export reader methods
reuse existing bounded qualification; existing History methods remain unchanged.

Select only the new `selectExportWallRangePageInTransaction` method in
`ImportedCellProductReader.kt` and the two new export-date tests in
`RoomImportPortableCapturedCellTest.kt`. Both use committed dependencies. Their
exact index patch is `IMPORTED_RADIO_EXPORT_CANDIDATE_CORE_MIXED.patch`; it excludes
the older held Cell header-page reader and pagination test.

Fourteen meaningful new DAO/real-qualifier scenarios accompany this selected core.
They are authored source, not compilation or passing evidence.

## Preserved drafts

Do not select either full radio exporter, their five routing/mock hunks, or the
new `ImportedRadioExportDateCandidatesRoomTest.kt` solely from this core receipt.
Those depend on complete held exporter definitions, spool/detail companions and
other open assembly inputs. Their two new file-boundary scenarios remain held.
The separate reviewed 101-entry Wi-Fi cursor repair is preserved in its draft.

The complete agent snapshot/owned and mixed patches and independent review are
retained in the September 30 directory. The independent reviewer found no concrete
defect in this isolated slice and checked all twenty source hashes. This does not
close the unrelated global native-owner census or full source-file readiness.

No tests, compilation, schema generation, diff checks, devices or gates ran.
Main `dev/v10` stays unchanged; all twenty-five full-chain prerequisites stay open.
