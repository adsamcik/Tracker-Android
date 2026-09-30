# Imported radio export mapping: independent static review

Date: 2026-09-30. Reviewer: `/root/fresh_bundle_context_review_0930`.
Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.

## Reviewed input and exact scope

Reviewed `RADIO_EXPORT_MAPPING_FILES.json`, `RADIO_EXPORT_MAPPING_SOURCE_SLICE.md`,
the two selected imported mapping functions, their imported-loop call sites, the named
background cancellation additions, and all six `ImportedRadioExportMappingTest.kt`
source scenarios. Traced the existing imported evaluator/reader, exact revision DAO queries,
entity foreign keys, V2 child/session constructors and module dependencies. Full inherited
user instructions and approvals apply. No production edits, Git operations, delegated review
or validation commands were performed.

All three whole-file snapshot hashes matched the input manifest at inspection:

| Path, relative to repository root | SHA-256 |
| --- | --- |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/PortableWifiSourceExporter.kt` | `3040580e3ef8c44967ec75484af70358fed6010a904b397fdd37f036e457df42` |
| `feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/PortableCellSourceExporter.kt` | `05371a293e1bf4c6de2fd6849ee68b3ab72f893c99e80d2d2332d575f7c64d18` |
| `feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/ImportedRadioExportMappingTest.kt` | `5e6ac239af91801f95170eff68cac34e71d705c6e52d8b01096c78835b01e239` |

These pins identify inspected snapshots; they do not approve whole mixed exporter files.
Native `localChildren`, candidate/page queries, background collector allowance policy, whole
file/output consistency and other draft contents remain outside this selection.

Exact UTF-8 helper excerpts retain original line endings, from `internal suspend fun
mapImported...SessionChildren(` through the following blank line, before the next declaration:

| Helper | Bytes | SHA-256 |
| --- | ---: | --- |
| Wi-Fi, lines 360–386 before `internal fun LongRange.toRadioDates()` | 1,015 | `9790ffe840c474c1dc3e80ce6130945da191f5ee45c98ca8e474b303039af481` |
| Cell, lines 340–366 before `/** v5 summaries remain valid;` | 1,026 | `50b898e95b4be84bae90352b9146c8a9be0db9d1436644f65c4aaa27c4452421` |

## Findings and concrete trace

No new concrete defect was found within the selected mapping/cancellation scope.

- Both helpers index `(run identity, observation identity)` to the exact selected semantic
  revision once, rejecting duplicate index keys. Each child must name the selected entry and
  an indexed run/observation. The returned child keeps its original identity, item index,
  timing, network/tower values and checksum; `rows.map` preserves DAO input order. The existing
  V2 constructor checks the checksum against the selected revision, so an older-revision
  checksum cannot become a current child. Complexity is proportional to observations plus
  children, rather than scanning all runs/observations per child.
- Wi-Fi imported-loop lines 232–247 and Cell lines 227–240 keep re-export eligibility and
  local-origin collision checks before smart date selection and child loading. Their existing
  DAO queries select the exact entry/import revision and order by run, observation and item,
  capped at the existing maximum plus one. The helpers do not create an import revision,
  alter date scope or grant collection authority. Selected entry plus child query still requires
  the caller-owned Room snapshot.
- `WifiImportedProduct.kt:61` and `ImportedCellProductReader.kt:946` exclude deleted entries,
  deleted runs and retention-limited entries from re-export. The Wi-Fi evaluator returns
  the complete latest immutable entry at lines 310–332; Cell does so at lines 981–993. The
  mapper receives that complete entry after eligibility checks; it does not substitute a
  clipped history entry or widen a partial retained entry into exportable data.
- Cancellation is checked before reading either entry, while indexing runs/observations,
  after indexing and before each child conversion. Imported loops check each evaluation.
  The added background checks surround origin lookup queries and execute per fact, native
  checksum conversion, imported conversion and defensive duplicate group handling. Existing
  finite collector/count checks, origin partition checks, V2 checksums and duplicate equality
  meaning are unchanged. This is cooperative source-level cancellation, not proof of
  interrupting blocking I/O or draining an entire transfer.

## Authored test evidence

The six source cases exercise both real child checksum constructors and supplied immutable-entry
hierarchies. Two 100-child/two-run cases verify one observation-list read per run, exact input
order, values, checksums and per-run revisions, then invoke the real V2 session constructor.
Other cases reject a foreign run/file and a stale revision checksum. Already-cancelled empty
conversions verify neither entry is read. The custom Cell list cancels while its first row is
obtained; the conversion check prevents that row's conversion and a second row read.

The test entries/runs are mocks; these tests are conversion/portable-contract evidence only.
They do not exercise Room selection/authorization, a complete export job, cleanup, Hilt, provider
activation or a device. All tests remain unexecuted. No compile, test, lint, schema, device,
CI or integration success is claimed.

## Maintainability and publication boundary

The two small internal helpers introduce no generic framework, public API, schema, dependency,
output format or membership policy. Moving their exact bodies into one package-local
`ImportedRadioExportChildren.kt` is coherent routine source organization: existing same-package
calls and tests can retain the same names and visibility. Dependencies already exist in the
selected assembly: core/base imported child entities and Cell portable entry, stats/api Wi-Fi
portable entry, the existing feature/import-export V2 child DTOs, and coroutine APIs. The module
already depends on core/base, stats/api and coroutines. This relocation may publish the helper
and six tests without selecting unfinished exporter definitions; it requires a final byte/pin
recheck after relocation.

The caller/background cancellation hunks remain held with their full exporter dependencies.
This receipt does not close native Wi-Fi missing-detail version/completeness, imported candidate
date/cursor parity, exact background archive membership, output snapshot/spool approval,
shared transfer admission/draining, optional export UI, assembly gates, device evidence or
`dev/v10` integration. The previous native-owner excerpt pins remain independent and unchanged.

## 2026-09-30 final relocation recheck

The coordinator relocated the two helper functions into
`feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/ImportedRadioExportChildren.kt`.
The complete selected helper SHA-256 is
`8fd32d464672a5e1d2ce03ec85a0972149156ea353b9b85de5b3c2dbaa365b01`.
The six-case test source is unchanged at
`5e6ac239af91801f95170eff68cac34e71d705c6e52d8b01096c78835b01e239`.
Both match the refreshed manifest.

After normalizing line endings to LF and retaining the reviewed excerpt's trailing blank
line boundary, both relocated function excerpts match their original reviewed pins exactly:
Wi-Fi 1,015 bytes / `9790ffe840c474c1dc3e80ce6130945da191f5ee45c98ca8e474b303039af481`;
Cell 1,026 bytes / `50b898e95b4be84bae90352b9146c8a9be0db9d1436644f65c4aaa27c4452421`.
There is no function-body change. The new file retains the same exporter package and internal
suspend declarations, with only their existing required entity/portable-entry/V2-child/coroutine
imports. Those dependencies and module declarations were already traced above.

Static search finds each declaration only in the new helper file. The existing held exporter
calls remain `mapImportedWifiSessionChildren(evaluation.entry, rows)` and
`mapImportedCellSessionChildren(evaluation.entry, rows)` at their respective lines 245 and 238.
No copied helper declaration or imported child entity import remains in either exporter.
Their refreshed held snapshot pins also match the manifest:
Wi-Fi `1b0c27716f70d3901153e285dd5f949da446e4f6e8e51764389757d74739b840`;
Cell `f1f27a3bf396961b9bbf68b9ef5b4bea4934b72315dd0933d22f4c84fdb03041`.

The selected helper plus unchanged six-case test is a coherent implementation foundation
independent of the held exporter classes. No new scoped defect was found in relocation.
Caller/background cancellation hunks, full exporters and all previously listed completion
dependencies remain held. This is static closure only; no gates were run and no caller,
assembly, device or integration completeness is inferred.
