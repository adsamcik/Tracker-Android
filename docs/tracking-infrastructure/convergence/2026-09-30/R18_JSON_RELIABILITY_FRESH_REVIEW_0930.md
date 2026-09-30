# Independent R18 JSON reliability review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Independent static inspection only. No compilation,
tests, schema/diff checks, device/provider activity, Git mutation or integration occurred.

## Input and scope

Reviewed the five selected files/hunks declared by `R18_JSON_RELIABILITY_FILES.json` and
`R18_JSON_RELIABILITY_SOURCE_SLICE.md`, with the full inherited user requests and approvals.
Traced the saved Location/entity mappers, existing JSON writer, manual document cleanup,
scheduled worker cleanup/progress and existing supporting test sources. This review covers
R18 trace findings 4 and 5 only. LastSession identity, geographic membership/scope, new JSON
representation, backup scope, remaining export drafts and schedule disclosure remain excluded.

## One concrete repair remains

**Reject negative accuracy before Float conversion.**
`JsonImport.kt:303-304` converts the input Double to Float before the nonnegative checks at
lines 334 and 336. A negative finite number such as `-1e-100` underflows to `-0.0f`, which
passes `>= 0f` and is stored as an accuracy. This contradicts the selected slice's explicit
negative-values-remain-null behavior. Filter the original Double for finite/nonnegative values
first, then retain only a finite converted Float. Add the tiny-negative codec case to the
existing focused test. No format, storage or architecture change is needed; valid stored Float
accuracy values keep the existing lossless export/import conversion.

## Static closure and trace

- `JsonExporter.kt:352` now throws `IOException` when a paging load is `Invalid`; it cannot
  finish the outer array or return a successful prefix. The source is invalidated by the
  existing `finally`. Load errors and writer/stream IO errors follow the same exporter error
  path; `exportAfter` propagates coroutine cancellation separately at lines 87-88.
- `ImportExportViewModel.kt:123-135` routes the exporter error to noncancellable partial-file
  deletion before reporting failure. A failed deletion reports the remaining destination.
  Stream closure remains owned by the existing writer and document-export caller. Cancellation
  has its separate deletion/rethrow branch at lines 138-145.
- `ExportPlanWorker.kt:248-252` removes partial scheduled output on `ExportResult.Error` and
  returns `PlanExportResult.Failed`. A write/close exception takes the existing deletion/error
  route at lines 226-233. Failure cannot reach successful progress resolution at lines 236-246,
  the watermark update at lines 100-110 or the completed-export notification. A deletion
  failure throws and becomes a logged retry; it still cannot advance the watermark.
- Both optional measurement fields are already emitted by the production writer. The new
  reader assignments preserve null/absent/zero/ordinary finite-positive saved values through
  the existing model/entity mappers (`DomainModelMappers.kt:93-95,137-139`). Nonfinite and
  positive Float-overflow values become null. Timestamp, coordinate, quality, speed, schema and
  existing optional-value conventions are unchanged. The tiny-negative case above is the
  remaining contract exception.
- The changes reuse the existing paging/error/cleanup seams. They add no source demand,
  dependency, schema, backup restriction, new transfer machinery or unapproved product choice.

## Authored evidence and limits

- `JsonImportTest.kt:205-226` covers absent/null/zero/finite positive/ordinary negative,
  nonfinite and numeric overflow values, while asserting unchanged speed and quality. Existing
  schema-1 coverage gains absent-field null assertions. Add negative-underflow input as above.
- `JsonExporterRoomIntegrationTest.kt:214-251` uses actual saved Room points and production
  JSON export/import into a separate Room database, comparing timestamp, horizontal/vertical/
  speed accuracy, speed and quality. This is a legacy/import-compatible observation fixture,
  not proof of complete six-source geographic membership.
- `ExportDocumentCleanupTest.kt:86-151` uses a real saved Room point/Trip with controlled
  paging invalidation after its first complete record. It delegates payload DAOs to Room and
  asserts the emitted prefix, second load, paging invalidation, failed manual export and removed
  document. This exercises the changed branch rather than merely asserting an error object.
- Scheduled cleanup and watermark behavior were statically traced. Existing
  `ExportOutputCleanupTest` tests the output-deletion primitives only.
  `IncrementalExportTest.watermarkUpdate_noUpdateOnFailure_preservesPrevious` asserts its
  unchanged fixture and does not execute a worker failure. Neither is scheduled
  emitted-prefix/unchanged-watermark execution evidence; that full worker scenario remains
  part of the deferred convergence coverage.

No test source was compiled or executed. Paging-invalid failure handling has static closure;
accuracy parsing needs the narrow negative-underflow repair/recheck. This receipt does not
close full R18, assembly, convergence or `dev/v10` readiness.

## Static repair recheck — 2026-09-30

The coordinator repaired the negative-underflow finding and refreshed the five-path input
manifest. `JsonImport.kt:303-306` now requires each original Double to be finite and
nonnegative before conversion. The final saved Float checks remain at lines 336 and 338, so
positive Float overflow still becomes null. Negative `-1e-100` can no longer become accepted
negative zero through rounding.

The existing codec matrix now includes `-1e-100` for both optional fields and expects null for
both (`JsonImportTest.kt:214,224-225`). Its point count, unchanged speed and unchanged quality
assertions include that new row. These sources were read, not executed.

This closes the sole concrete finding in the selected JSON reliability slice by static source
inspection. No further defect was found within the reviewed paging-failure and optional
measurement-accuracy repairs. The scheduled full-worker evidence gap and all other excluded
R18/assembly/convergence/device boundaries above remain open.
