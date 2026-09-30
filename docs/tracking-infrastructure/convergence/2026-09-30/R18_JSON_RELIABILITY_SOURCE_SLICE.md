# R18 JSON paging and measurement accuracy repair

Status: **IMPLEMENTED_UNVALIDATED**. Production and focused test sources were authored and statically inspected. No build, compilation, tests, lint, schema checks, device/provider activation or Git mutation ran. The coordinator owns selection, independent review and commits.

## Approved scope and source trace

This slice addresses only findings 4 and 5 in `R18_GEOGRAPHIC_SCOPE_TRACE.md`. It does not decide a new JSON representation, session scope, native radio payload, export selection, LastSession behavior or schedule disclosure.

- `JsonExporter.forEachOverlappingTrip` now throws `IOException` for `PagingSource.LoadResult.Invalid` instead of returning a successful prefix. The existing `finally` invalidates the paging source; writer closure preserves normal stream ownership. `exportAfter` turns the failure into its existing `ExportResult.Error` (and still propagates coroutine cancellation).
- The existing interactive `ImportExportViewModel.exportDocument` removes a partial document on that error. The existing scheduled `ExportPlanWorker` removes partial output and returns `PlanExportResult.Failed`; only success can produce export progress and an advance-watermark result. Those caller implementations were read, not edited, and scheduled execution was not performed.
- `JsonImport.readLocationAsSample` reads the already-exported `verticalAccuracyM` and `speedAccuracyMps` fields through the existing nullable-number convention. Only finite, nonnegative values survive the conversion to the saved Float fields. Absent/null/negative/nonfinite/Float-overflow values remain null, matching the existing horizontal accuracy handling. No whole-point rejection or invented accuracy is added.
- Existing JSON schemas and measurement quality are unchanged. Older files that omit these optional fields continue to import with null accuracy values. No new entity, migration, dependency, worker, transfer framework or product control was added.

## Authored test sources

1. `JsonImportTest`: one codec case covers absent, null, zero, finite positive, negative, nonfinite and numeric-overflow values; checks unchanged speed/quality. The existing schema 1 test gains explicit null assertions for absent optional accuracy.
2. `JsonExporterRoomIntegrationTest`: actual Room observations with null/zero/finite accuracy are exported through the production JSON codec, imported through `JsonImport` into a separate Room database and read through the actual saved Location DAO. Timestamps, vertical/speed/horizontal accuracy, speed and quality are compared. This uses legacy/import-compatible saved points and the existing session shape; it is not a proof of all geographic source memberships.
3. `ExportDocumentCleanupTest`: a real saved Room Trip and Location point form the first page; a controlled paging source returns `Invalid` for the second page. Production JSON serialization runs through the existing manual document-export path. The source asserts two loads, paging invalidation, one complete emitted record without a completed outer array, failure and removal of the private partial document. All other reads delegate to the actual database so ignoring `Invalid` cannot be hidden by an unrelated missing mock.

These are authored scenarios only; none has been compiled or executed. Real Room code is present in the test sources, not executed Room evidence.

## Exact selection

Production:

- `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/JsonExporter.kt` — IOException import and Invalid load failure only.
- `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/JsonImport.kt` — two optional values, two existing field reads, two finite/nonnegative saved assignments only.

Tests:

- `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/JsonImportTest.kt` — codec case and schema 1 absence assertions only.
- `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/JsonExporterRoomIntegrationTest.kt` — Room roundtrip case and its two imports only.
- `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/activity/ExportDocumentCleanupTest.kt` — invalid-prefix cleanup case and its imports only.

`R18_JSON_RELIABILITY_FILES.json` pins these five current code files for review. Their current diffs contain only this slice; other transfer drafts, the frozen ZIP slice and parent-owned ambient bounds repairs remain untouched. Hashes identify input, not executed validation.

## Remaining boundary

Independent source review and the deferred convergence gates remain required. R18's other selection, JSON shape, exact membership and schedule review findings remain separate work. No whole-assembly completion or `dev/v10` readiness is claimed.
