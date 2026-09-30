# Session bundle extraction bounds and cancellation

Status: **IMPLEMENTED_UNVALIDATED**. Production and focused test sources were authored and statically inspected. No build, tests, lint, schema checks, device activation or Git mutation was performed.

## Repair and source trace

The existing session contract is unchanged: 64 MiB per child, 256 MiB total child data, 1 MiB manifest, and the existing finite compressed wrapper allowance. Previously the generic ZIP extractor could materialize a 512 MiB entry before `SessionBundleStager.copyBounded` rejected it.

- `ZipArchiveExtractor` accepts three optional stricter constructor limits: byte limit by entry name, total expanded bytes, and compressed input bytes. Defaults remain 512 MiB per entry/input and 2 GiB total, with unchanged entry-count, compression-ratio, receipt, path and classification rules. Overrides cannot expand the generic limits.
- `SessionBundleStager` creates its default ZIP extractor inside the staging operation, using 1 MiB for the manifest and the smaller of 64 MiB or the remaining approved child budget for each child. Both advertised and unknown entry sizes are enforced before excess bytes can be written to the ZIP temporary file. Total ZIP expansion includes the existing manifest allowance; compressed reads use the existing wrapper allowance.
- Duplicate manifest, duplicate child and child-count checks run before materialization through the existing `shouldExtract` callback. Defensive checks during staging remain. The injected `ArchiveExtractor` seam is preserved and still has the existing staging-side copy limits.
- ZIP classification/extraction and their bounded read helpers are suspend functions. Cancellation is checked around header, entry and body reads. A blocked platform read still must return before cooperative cancellation can act; no new thread or interrupt framework was added.
- Source-copy and staged manifest/child-copy loops check cancellation before and after reads and before returning ownership of completed private files. Cancellation during stream closure therefore cannot return an abandoned private source copy.
- Existing `use`/catch cleanup removes the materialized ZIP temporary file, the current staging copy and earlier staged children. Failed ZIP entries are not drained by `closeEntry`. Existing raw provider `IOException`/`EOFException` unwrapping stays retryable; size/structure failures stay `PermanentImportInputException` and cancellation is propagated.
- Production classification is called only from the existing suspend worker extraction path. Four classification-only tests and one worker failure test now use `runTest` to call the suspend helper. Worker, registry, metadata publication, receipts, original epochs, schema and transport behavior were not edited.

## Exact authored selection

Production:

1. `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/archive/ZipArchiveExtractor.kt` — stricter optional caps and cancellable classification/extraction loops.
2. `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/archive/SessionBundleStager.kt` — per-operation default extractor limits, early entry selection checks and copy/ownership cancellation checks only.

Tests:

1. `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/archive/ZipArchiveExtractorTest.kt` — four new source cases: declared cap before temp creation/default compatibility; unknown-size cap and cleanup; remaining total cap before the next temp; cancellation during extraction/classification with one buffer and closed input. Four existing classification tests gain `runTest` wrappers.
2. `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/archive/SessionBundleStagerTest.kt` — four new source cases: cancellation during source close; real STORED ZIP headers exceeding manifest/child limits without allocating large payloads; wrapper cap before opening; cancellation during child/manifest staging removes prior children and closes inputs. Related imports and the small header fixture only.
3. `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/worker/ImportWorkerFailureTest.kt` — only the `runTest` import and wrapper on `ZIP structural and resource violations are terminal worker failures`.

Mixed draft boundaries:

- `SessionBundleStager.kt` already contained the `manifest.selectedSources` mapping/check; that draft was preserved and is not authored by this slice.
- `SessionBundleStagerTest.kt` already contained `unselected source child is rejected and cleaned before any source dispatch`; it is preserved and excluded from this slice's authorship.
- Other dirty worker/failure/metadata/registry drafts are preserved. Parent selects any required companion drafts separately.

`SESSION_BUNDLE_BOUNDARY_FILES.json` records the exact current source hashes and these selection boundaries. Hashes identify read/review input, not executed validation.

## Review boundary

Separate source review and the deferred convergence batch remain required. This slice closes the extraction/copy reliability finding only; it does not declare complete bundle metadata, original-epoch import, backup restore, whole assembly, Hilt or device readiness.

## Coordinator selection

The coordinator statically inspected the tighter default extractor caps, transport failure handling,
cancellation and temporary-file cleanup. The preserved selected-source format check and its existing
unselected-child test are also accepted as a required staging contract companion: a bundle must not
dispatch a source absent from its declared selection. Both are selected with this slice. The mixed
worker test selects only its suspend wrapper and import from
`SESSION_BUNDLE_ZIP_WORKER_COMPANION.patch`; all other worker drafts remain excluded.

This is source review only. Nine new focused test methods are selected including that companion;
none has been executed. Bundle metadata, native Steps duplicate membership and exact overlapping
radio archive membership remain separate unfinished work.
