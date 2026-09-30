# Independent export spool cancellation review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Static source inspection only. No production edits, Git,
compilation, tests, device/provider work or integration.

## Reviewed source

- `FileBackedExportSpool.kt`, SHA-256
  `21ca142d86eca8446dc095774d646fdf3bd5b10954a09a360bff570ff955972d`.
- `FileBackedExportSpoolTest.kt`, SHA-256
  `249e2391540377e4cd34bf150fdd35e4d7424ec32164bc6b89164c1d239042bb`.
- All five production copy calls: Steps v2/v1 (`PortableStepsExporter.kt:83,107`), Ambient
  Steps v2/v1 (`PortableAmbientStepsExporter.kt:100,125`) and bundle archive
  (`SessionBundleArchiveWriter.kt:70`), plus their caller cleanup branches.

## Static assessment

No concrete production defect was found in this narrow cancellation companion.

`write` captures the calling coroutine context, checks before work, after the staging block,
and after its private buffered stream closes before setting `writeCompleted`
(`FileBackedExportSpool.kt:90-100`). A cancellation at those boundaries cannot mark a staged
file complete. `stage` catches only `IOException` and maps only the existing typed byte-limit
failure; coroutine cancellation escapes rather than becoming size fallback (`:80-87`).

`copyTo` is now suspend and checks before opening, before/after each input read and after each
destination write (`:103-120`). All five callers are already suspend functions; their inline
`use` scopes introduce no non-suspending boundary. Steps does not catch cancellation; Ambient
Steps explicitly rethrows it; the bundle writer lets it escape. No failed copy can reach those
callers' success result or format-fallback decision.

Private input and staging streams close through `use`; the outer spool `use` deletes its own
backing file even when cancellation escapes. The destination remains caller-owned and is
neither closed nor flushed by the spool. Existing manual/scheduled stream scopes close it and
their error/cancellation branches remove partial output. Ownership, format, byte/file/age
limits and cleanup policy are unchanged. This adds no radio exporter usage or demand.

## Authored evidence and small test gap

The normal-copy source uses 20,000 patterned bytes across multiple buffers, compares exact
output, verifies that the destination remains open and that the private spool was deleted
(`FileBackedExportSpoolTest.kt:145-158`). The cancellation source cancels the child coroutine
from its first actual destination write, requires exactly one write, cancellation/no normal
return and deletion of the backing file (`:162-186`). These assertions exercise real file
staging/copy, not a replacement copy implementation.

The case named `cancelled final stage cannot become a completed spool` (`:190-207`) currently
calls `spool.write`. It meaningfully covers cancellation of the write primitive and deletion,
but does not directly invoke the `stage` wrapper named by its claim. Use `spool.stage` in that
case, or narrow its name. The production wrapper was traced separately above; no executed
pass is claimed for any case.

## Required work outside this companion

- `SessionBundleArchiveWriter.kt:25-33,79-93` hashes child files before staging without
  cancellation checkpoints; its `:41-67` ZIP child-copy loop also lacks them. The spool's
  after-block check prevents successful publication, but cannot stop those loops promptly.
  Retain this as the separate preparation/writer-loop cancellation repair.
- Cooperative checks cannot interrupt an arbitrary destination blocked inside `write`.
  Shared transfer admission, original lifecycle/epoch checks, cancellation and physical drain
  before Clear-all cleanup remain open, including interactive exports and durable external
  residual-file notices. The WorkManager cancellation operation alone does not establish the
  physical stream-closure boundary.
- The radio whole-snapshot/private-spool architecture proposal still awaits user approval.
  This companion repairs the existing utility only and does not approve or complete it.

This receipt supersedes only the missing cooperative copy checkpoints described in
`RADIO_EXPORT_SPOOL_REPAIR_PROPOSAL.md`. It does not close full transfer cleanup, bundle/radio
exports, convergence, on-device evidence or integration readiness.

## Dated static recheck — 2026-09-30

The small test gap above is closed: line 197 now calls `spool.stage`, so cancellation must
escape the actual completed-stage wrapper before the normal-return flag can be set. The
existing child-job cancellation and owned-file deletion assertions remain in place. No
additional defect was found in the final narrow utility/test selection.

Final exact SHA-256 values:

- Production `FileBackedExportSpool.kt`:
  `21ca142d86eca8446dc095774d646fdf3bd5b10954a09a360bff570ff955972d`.
- Test `FileBackedExportSpoolTest.kt`:
  `5fdaec92da3f6e61c3df0742a239f2564841916a971f304868e49b510fd2f69f`.

This is static closure only. The separate preparation-loop, transfer-drain and radio-spool
approval boundaries above remain unchanged; no tests or other gates were executed.
