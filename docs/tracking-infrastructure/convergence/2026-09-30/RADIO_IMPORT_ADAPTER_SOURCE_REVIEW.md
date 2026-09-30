# Radio import adapter and detail bounds

Status: `IMPLEMENTED_UNVALIDATED`. Production and focused test sources only; no validation or Git actions.

## Small coherent source slice

`PortableRadioSourceFileImport.kt` supplies the two approved single-source file adapters. It is also
required by bundle metadata's existing `radioEntryKey` mapping. The codec, v2 source values, detail
attachment and focused codec/epoch/batch/Room tests are its eight adapter/detail companion paths in
`RADIO_IMPORT_ADAPTER_FILES.json`. Six of those paths were edited in this bounded repair; the
remaining two are preserved adapter/test prerequisites reviewed without changing them.

The adapters require the saved file receipt and original data epoch before decoding. Captured and
background imports use the existing source-owned importers and receipt keys. Valid parent data
commits before bounded detail batches; retries reuse the source receipt, attach missing details,
skip identical children and reject conflicting children. A failed batch rolls back its own sibling
inserts. Earlier completed source or detail batches remain available for retry. No import starts tracking.

Each detail's time and uncertainty now fit entirely inside its exact parent's measured interval.
DTO construction, decoded file validation and the streaming background writers apply the same
small pure predicate, including overflow rejection. Each 256-item Room attachment transaction
rechecks active cleanup, the original epoch, current retention floor and exact parent identity/revision.
It rejects parent coverage below the current floor, and checks the floor remains unchanged at commit.
Existing summaries with no detailed child group retain their existing meaning.

## Authored focused scenarios

The real Room attachment test isolates already-admitted parents using handcrafted valid rows and
production DAOs/attachment transactions. It covers all four radio lanes; exact boundary details;
uncertainty outside the parent; harmless retry duplicates; original background owner; missing or
conflicting later siblings; SQL abort/rollback/retry; retention advancing after parent admission;
active cleanup; and clear changing the original data epoch. It does not claim to run the physical
providers or source parent importers.

Codec scenarios cover real round-trip data, altered but correctly recomputed child checksums outside
the parent interval, streaming parity, partial detailed groups, empty legacy summary groups and
timestamp overflow. The existing epoch tests exercise decode/retry using source mocks; existing
batch tests exercise bounded handoff, stopping subsequent batches after failure, and cancellation
before an empty attachment. None were executed.

## Existing production dependencies and selection boundaries

- Four importer bindings: `WifiCapturedPortableTransferModule.kt`,
  `CellCapturedPortableExportModule.kt`, `AmbientRadioPortableTransferModule.kt`.
- Imported child entities/DAO, AppDatabase registration and full-clear hooks, and four-table
  foreign-key migration in `RadioObservationChildMigration.kt` already exist. This repair adds no schema.
- The format registry's radio imports and two Wi-Fi/Cell registration blocks use these adapters.
  Select those hunks together with the finalized `PortableWifiSourceExporter.kt` and
  `PortableCellSourceExporter.kt` and Wi-Fi/Cell label resources; do not select unrelated format drafts.
- ImportWorker's existing radio extension byte-limit branches remain required; bundle stager radio
  format alignment was already committed. Feature dependencies remain API/base modules, with
  application composition supplying tracker engine and stats data implementations.

## Explicit remaining limits

Background archive receipt alone does not retain exact file membership when overlapping source
imports deduplicate facts/gaps globally. Example: file B containing fact1 and fact2 after file A
already imported fact1 stores only fact2 under B's archive ID. Bundle membership for B must not
fall back to dates or omit fact1 silently. The small file-to-fact/gap membership repair is pending
the parent's requested approval and is not implemented in this slice.

Legacy unknown-origin export selection is also outside this slice pending the relevant decision.
This receipt accepts neither whole draft exporters nor a complete session-bundle product chain.
Native radio parent bounds must include fractional child rounding; parent owns those companions.
Independent review found the API/portable background lower bound still copies nominal
`coverageStartTimeMs` while native child uncertainty and retained readers use the measured
`coverageStartTimeMs - wallTimeUncertaintyMs` bound. Parent was notified; this companion must be
aligned before native exports with nonzero uncertainty can satisfy the strict portable interval.
Hilt/Room generation, compilation, test execution, device timing and final integration remain deferred.

## Coordinator selection

The coordinator statically traced the four source-owned importers and selected the
eight adapter/detail paths plus the two existing Hilt binding modules. This does
not select the preserved radio exporters or mixed format-registry/worker hunks.
The native upper-bound rounding review is recorded separately; the lower-bound
API companion is being authored before final source-file convergence. Exact
overlapping background archive membership remains pending its approved design.
No validation was executed and no complete file/UI or bundle chain is declared.
