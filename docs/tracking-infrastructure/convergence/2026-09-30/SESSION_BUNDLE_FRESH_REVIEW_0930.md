# Fresh session bundle source review

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. Independent review by
`/root/fresh_bundle_context_review_0930` against candidate HEAD
`1d3100a9fb02ffa3281db07c0cca367f2ac4f510` and the explicitly proposed working-tree
bundle selection. Review only: no production edits, Git mutations, compilation,
tests, lint, schema generation, device checks, activation or integration.

## Findings requiring repair or explicit selection

1. **Native Steps duplicates cannot become bundle membership.**
   `tracker/engine/.../source/importer/RoomImportPortableSteps.kt:343-346` returns
   `Duplicate` for an authenticated identical native product before the imported
   product/count-domain/file-receipt insertions at lines 375-386. In contrast,
   `SessionBundleMetadataImport.kt:205-207` requires both an imported file receipt
   and an imported Steps entry. A bundle imported back into the recording phone
   therefore saves a generic successful skipped-child receipt and permanently
   fails metadata as missing membership. This is a legitimate duplicate, unlike
   a child refused by a deletion or retention fence. The source must preserve an
   exact file association to its authenticated native counterpart, and the exact
   selected history reader must resolve that counterpart. Currently
   `UnifiedHistoryRangeReader.kt:486-506` resolves captured selectors only through
   imported products. Do not restore an empty selector or add a date fallback.
   Add an actual native Steps duplicate/receipt/metadata/history source scenario.

2. **Preparation turns transient read failures into permanent input errors.**
   `SessionBundleMetadataImport.kt:159-160` catches every `IOException`, including
   failure to open/read a private staged child or its Location content hash after
   successful source imports. It wraps that failure in `PermanentImportInputException`;
   `ImportWorker` consequently ends the job instead of applying its existing
   generic-I/O retry behavior. Catch the known malformed-format exceptions as
   permanent, and let ordinary transport/storage I/O remain retryable. The typed
   codecs already distinguish malformed input from transport failures. Add a
   preparation-I/O failure and retry scenario that retains completed children.

3. **The import selection is not yet dependency-complete.**
   `FormatRegistry.kt:13-14,223-248` references the untracked
   `PortableWifiSourceExporter.kt` and `PortableCellSourceExporter.kt`. The bundle
   stager requires those registry rows, but its manifest does not select those
   exporters or the registry changes. Selecting the bundle manifest alone does
   not produce a coherent committed registry. This is a source assembly blocker,
   not a compilation result.

   Minimal incremental selection: register the approved one-Wi-Fi and one-Cell
   descriptors with their existing labels/MIME/extensions, `supportsImport=true`,
   `supportsExport=false`, and their committed source importers. Omit the two
   unreviewed exporter imports/factories. Select the current exact Location row
   with its already committed importer/exporter separately. This makes import
   dependencies concrete while leaving the approved optional exports and export
   UI explicitly required for later reviewed slices. It does not remove or change
   the final approved one-format export behavior.

4. **Bundle extraction applies its small child limit after generic materialization.**
   `SessionBundleStager.kt:77,132-153` checks the 64 MiB child cap when copying the
   extracted member. Before that callback, `ZipArchiveExtractor.kt:216-225`
   materializes the complete member with its generic 512 MiB per-entry cap.
   A rejected child can therefore use up to 512 MiB private disk before the
   advertised bundle limit is enforced. Those synchronous extraction/copy loops
   also contain no coroutine cancellation checks (`ZipArchiveExtractor.kt:346-363`).
   The path is bounded, but not at the bundle cap. Reuse the extractor with smaller
   caller limits and cancellation checks rather than adding another extraction
   pipeline. Add a focused source scenario rejecting during materialization and
   cleaning the partial file.

## Traced behavior that is coherent in source

- The original epoch is pinned by the existing worker and passed through the
  existing runner's bound child stream. Metadata rechecks epoch and active
  retention settlement inside its existing WORKER_MANAGED Room transaction.
- A successful child keeps its owning source transaction/prefix contract. A
  failed child prevents metadata publication without rolling back saved siblings.
- Metadata annotation and its generic success receipt commit atomically. A
  successful metadata receipt skips preparation/publication on retry, preserving
  subsequent user edits or removal.
- The missing-source-receipt repair now throws a permanent membership failure;
  a generic skipped-child success alone cannot publish an empty annotation.
  The two new test methods explicitly cover this refusal, including a real
  staged Location prefix and a nine-claim absent-receipt matrix.
- Location association uses the existing source-format content identity and exact
  file ordinals/portable IDs. It checks complete membership count and epoch.
- Captured identities preserve real source shapes: Steps and Pressure use
  `sha256:` identities; Activity/Wi-Fi/Cell use their existing raw digests.
  Background Steps checks its existing ordered archive/day memberships.
- The stager checks the whole wrapper's paths, counts, bytes, hashes, registry
  format/extension relationship and selected source before importing children.
  No arbitrary ZIP-to-session routing or date-based membership is introduced.

## Additional map companion review

Commit `1d3100a9fb` adds native `wifi_captured_child`, `ambient_wifi_child`,
`cell_captured_child` and `ambient_cell_child` to the existing map observer.
These names match the four Room entities in `RadioObservationChildren.kt`.
They are source-specific dependencies of the retained paired reader committed
in `3522f218f4`; a child-only change now emits the corresponding radio source
invalidation. No acquisition demand, layer, UI or new observer infrastructure
is introduced. The existing mapping test source includes all four names.

## Boundaries

This review does not accept full R17 or the entire feature. Exact overlapping
background radio archive membership, nested Location conflict choice, session
bundle export UI/caller and imported label-clearing behavior remain open.
Most positive metadata integration fixtures use the actual Location adapter;
the Wi-Fi positive fixture inserts its source receipt through a test seam rather
than running the actual radio adapter. The absent-receipt matrix tests refusal,
not successful source binding for all source types. No authored test is claimed
to pass. Convergence/Hilt/Room/schema/test/device evidence remains deferred.

## 2026-09-30 static ZIP repair recheck

Finding4 is **closed in source** after inspecting the coordinator-identified
`6682365973` selection, `SESSION_BUNDLE_BOUNDARY_SOURCE_SLICE.md`,
`SESSION_BUNDLE_BOUNDARY_FILES.json`, the actual extractor/stager and focused
test sources. Earlier findings remain as review history. This is not an executed
pass or full R17 approval; all code remains **IMPLEMENTED_UNVALIDATED**.

- `SessionBundleStager.kt:64–75` constructs its per-operation default extractor
  with the1MiB manifest cap, at most64MiB per child and the remaining256MiB child
  budget, manifest-inclusive total cap and existing compressed-wrapper allowance.
  `ZipArchiveExtractor.kt:186–195,265–282` checks advertised size before entry
  materialization. Unknown size is checked in the bounded body loop before excess
  bytes are written (`:335–336,367–385`), including skipped/unsafe entries.
- Duplicate manifest/child and child-count checks run before materialization in
  the existing callback. Defensive staging checks, whole-wrapper membership/hash/
  format checks and the explicitly selected `manifest.selectedSources` companion
  still finish before any child importer dispatch.
- Signature/header/body reads check coroutine cancellation; staging source and
  child/manifest copy loops also check before/after reads and before returning
  owned completed files. A blocking platform read must return before cooperative
  cancellation can act; the slice adds no interrupt/thread framework.
- Existing `use`/failure cleanup covers materialized ZIP temporary data and the
  current/previous staged copies. A rejected partial entry is not drained by
  `closeEntry`. Ordinary provider I/O is unwrapped and remains retryable, while
  malformed ZIP/size violations stay permanent input failures. Cancellation
  propagates separately.
- The production classification caller is the existing suspend worker extraction
  method (`ImportWorker.kt:244–255`). Actual classification test call sites use
  suspend test wrappers; the worker test companion selects only its wrapper and
  import, leaving unrelated worker drafts outside this source packet.
- Eight focused size/cancellation source cases and the selected unselected-source
  companion cover declared/unknown caps, remaining total cap before the next
  materialization, source-close cancellation, first-buffer ZIP cancellation,
  staged-copy cancellation and cleanup. They were inspected, not executed.

No further concrete defect was found in this extraction/cancellation repair.
Native Steps duplicate membership, exact overlapping radio archive membership,
source registry/export selection and remaining bundle product work retain their
own review/implementation boundaries. The earlier generic preparation-I/O finding
has a separate static repair inspection recorded in
`RETAINED_AMBIENT_AUTHORITY_FRESH_REVIEW_0930.md`; no old finding here is evidence
that the entire bundle or convergence input is complete.

## 2026-09-30 selected caller/registry foundation recheck

Earlier findings 2 and 3 now have **static source closure** for the explicitly
selected foundation. I reviewed `bundle-import-selection/BUNDLE_CALLER_REGISTRY_SELECTION.patch`
and the five `after` snapshots, not the whole dirty mixed production files.
The selected snapshots' bytes match all five manifest hashes. No Git operation
or deferred gate was executed. The source remains **IMPLEMENTED_UNVALIDATED**.

- The selected `FormatRegistry` registers one Wi-Fi and one Cell importer using
  their approved source format IDs, extensions, MIME values and labels.
  `supportsExport=false` and no exporter factory/import appears for either radio
  format. Existing importer discovery still resolves the real supported
  extensions. The exact Location row uses the existing committed Location
  importer/exporter. Only the three labels in each of the two locales are added.
  This is the incremental importer-only remedy proposed for finding 3. The
  approved optional exports remain required later work.
- The selected worker stages a `.trackersession` wrapper once; job hashing and
  dispatch both use that owned copy (`after/2-ImportWorker.kt:119-126,150-163`).
  Existing ordinary source imports retain their path. Wrapper staging and child
  staging are bounded and cleaned by their existing `use` owners. Source limits
  now include the actual session/radio/Location format-owned caps.
- The selected runner skips a successful metadata receipt before preparing
  metadata (`after/1-DataImporter.kt:302-304`). Preparation runs outside Room,
  after children retain their source-specific transaction/prefix contracts.
  Publication runs through the existing worker-managed receipt transaction;
  the current job and every generic child receipt must still be successful.
  Startup/original-epoch fences remain before and after preparation and
  publication. Atomic annotation/receipt success prevents retry resurrection
  after a later user edit/removal.
- `SessionBundleMetadataImport.prepare` makes only named typed codec/format
  failures permanent. Ordinary preparation IO escapes for the existing worker
  retry route; the worker separately maps bundle SQLite failures to retryable
  IO. Missing source-owned membership still fails permanently before annotation
  publication. Exact Location content identity reuses the existing job-key
  namespace through the explicit Location source-extension override; dates are
  not a membership fallback. Pressure uses its existing subordinate entry-key
  function through the narrowly shared `PortablePressureFileReceipt` object.
- Job hashing checks cancellation before and after every bulk read and after
  the zero-read single-byte fallback (`after/1-DataImporter.kt:552-558`). The
  finite source cap is checked before digest inclusion and the stream's `use`
  closes on cancellation. `ImportWorkerFailureTest` authors the changed
  final-read cancellation/close scenario, zero-read parity, source-extension
  parity, bounded hashing and terminal-versus-retry classification.
- Actual Room metadata sources cover successful/duplicate Location membership,
  child-prefix retention and retry, temporary preparation IO, annotation/receipt
  rollback, original epoch, expired metadata, no resurrection and missing-source
  refusal. The positive radio metadata fixture uses real typed Wi-Fi bytes but
  inserts a receipt through its seam; it is not an actual radio adapter positive
  chain. None of these tests was executed.

### Exact reviewed input bytes

Path bases are stated below. The five selected snapshot paths and hashes are
also recorded in `BUNDLE_CALLER_REGISTRY_SELECTION.json`.
Hashes identify reviewed input; they are not runtime validation evidence.

| Selected snapshot or whole source | SHA-256 |
| --- | --- |
| `bundle-import-selection/after/1-DataImporter.kt` | `9a287eae1e0719f2c521d740173c8944098875dff1f44fdde91ecd7f2106a8c1` |
| `bundle-import-selection/after/2-ImportWorker.kt` | `7dd7d93069674ef6f720f9f11eba85049d0b36df87caef348bdc4b0fdf984cbc` |
| `bundle-import-selection/after/3-FormatRegistry.kt` | `14c6d3bfb987350b24046c1fdb7421e0cc29076d5d2eb129e861e4468bb29cc6` |
| `bundle-import-selection/after/4-strings.xml` | `4b0cbbcf980cd0caae6b21235ea836b97b82350f907c73c111562e92ae8c8ef8` |
| `bundle-import-selection/after/5-strings.xml` | `aa2950440e5ce728951f8cd58d905c31313375d7e2fb41617e1e1cce73cb41a8` |
| `bundle-import-selection/BUNDLE_CALLER_REGISTRY_SELECTION.patch` | `54ce20b2134f27e15169aeb44f4dda4bc4e066cc91e953f27168634a34b774bd` |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/archive/SessionBundleMetadataImport.kt` | `2120794d1fb8ccc0094f65e4c9e73cb6542c2a0df998481ff0c2bad30ca01bb8` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/archive/SessionBundleMetadataRoomTest.kt` | `e55d9d267f1e6be0ae88a276509f4f8edbfbe04ac40505b25d13752a665c3390` |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/DataImport.kt` | `b4eea52d2533b7b138ccb9ed57031c7d73daa7ef500d0859a7cd2bd494e71ae0` |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/PortablePressureFileImport.kt` | `f734fb25ddb8bc8665064eede99d35839bb9a891a856c0853581e2dcaa00415f` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/worker/ImportWorkerFailureTest.kt` | `fc63235430031030b4cb870727f408109227cdec32fb80771d07d89cb67bfe33` |

The `bundle-import-selection` paths in the table are relative to this receipt's
convergence directory; whole source paths are relative to the candidate root.

No additional concrete source defect was found in this selected foundation. It
is coherent for an implementation-only local source commit with the previously
selected committed helpers/adapters. Finding 1 (native Steps duplicate source
association), exact overlapping radio archive membership, nested Location
conflict-choice flow, bundle export UI/caller, imported-label clearing and the
full positive source matrix remain open. This is not full R17 completion,
assembly completion, integration readiness or evidence of a passing gate.
