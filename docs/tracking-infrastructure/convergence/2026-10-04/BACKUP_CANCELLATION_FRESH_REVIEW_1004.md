# Independent backup cancellation review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewer:
`/root/fresh_bundle_context_review_0930`. Original user task/follow-ups/approvals,
including data-only backup and cancel-and-wait cleanup, were inherited. Static
source review only: no production edits, Git, recursive delegation, gates,
compiler, tests, providers/device activation or integration.

## Result and finding history

No remaining actionable defect was found in the final three-path **selected HEAD
V1 cancellation prerequisite**. It is coherent for an unvalidated implementation
commit only. Whole working V2 archive/exporter/test drafts are not accepted.

**B1 — statically closed.** In the original selection, a final lock/output close
could cancel the caller and throw IOException before the post-close `ensureActive`.
The writer would propagate IOException and the exporter would return Error rather
than cancellation. Ordinary `use` correctly preserved cancellation already thrown
inside the body, but did not cover cancellation first arising in failing cleanup.

`DatabaseBackupArchive.kt.after:25–49` now catches escaping failure only after all
nested synchronous `use` cleanup has unwound. For a non-cancellation failure it
rechecks the captured caller, preserves the original error as suppressed on the
cancellation, and propagates cancellation. An active caller still receives its
original failure unchanged. `DatabaseExporter.kt.after:53–54` rethrows cancellation
before the generic Exception handler.

The initial input was manifest
`4215d52e9932384183668674f2de7446825dbbc2515deaf14f8f3e91d0d574ff`,
patch `47968f58e704f2bc5d4244d265326bbd9e217b549b7eb6802873584babb61ae2`.
`PRE_B1_REPAIR` preserves that packet; the final pins below supersede it.

## Scoped trace and test-source review

- The internal writer becomes suspend and captures the admitted coroutine context.
  Checks surround lock acquisition, bounded reads/writes, entry/manifest output,
  and ZIP completion. The sole production writer caller is the existing suspend
  exporter. The two original tests are adapted with runTest/runBlocking while
  retaining their assertions. Existing coroutines dependencies already supply the
  added APIs; no dispatcher, registry, public API or owner was added.
- Input, lock and ZIP cleanup remain synchronous, nested in the admitted child.
  Cancellation does not skip cleanup, acquire a subsequent lock after observation,
  or report success after final cancellation. Primary errors retain suppressed
  cleanup errors. Blocking platform operations are not forcibly interrupted; Clear
  must still await actual child completion and retain closing debt on timeout.
- Manual export and scheduled export already invoke the exporter on their IO
  dispatchers inside `withReadyGenerationTransfer`; their cancellation/partial-file
  cleanup remains separate from this source-local writer repair. No new cancellation
  authority, transfer framework, discovery or file-lifetime policy was introduced.
- Eight added, unexecuted selected cases cover initial refusal; acquisition
  cancellation with failing release; cancellation during a partial copied entry;
  primary copy failure plus release failure; actual exporter/SQLite lock release;
  actual exporter final-close cancellation plus IOException; failing lock release
  before output cleanup completes; and an active final-close IOException unchanged.
  The three B1 cases at selected test lines 315–414 assert error identity/suppression,
  absence of Error return, cleanup completion, no sibling acquisition, and/or a
  subsequent real SQLite write. Deterministic stream fixtures do not prove actual
  SAF/file-descriptor failure behavior. None of these cases was run.
- V1 ZIP entries, manifest, discovery, file ordering and per-file lock lifetime are
  unchanged. The held V2 mirror was inspected only to distinguish its semantics;
  its conversion occurs after attempting every acquired lock release. Its caps,
  scope, preflight, multi-lock coordination, restore and extra tests remain held.

## Exact selected inputs

Baseline `eea8e76ac393c65ea7040bd2bd55dbeb51c3e6b8`. All three selected `.after`
and `.head-before` hashes were independently checked against the manifest. Actual
working archive/test hashes deliberately differ and match the separately held V2
snapshots; those whole files must not be selected.

| Exact repository path | Selected after SHA-256 |
| --- | --- |
| feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/DatabaseBackupArchive.kt | `cdca94e016b9da492815fffdd0e3bef717f36d7c52cbba4ead2f319db2469944` |
| feature/import-export/src/main/java/com/adsamcik/tracker/impexp/exporter/DatabaseExporter.kt | `946f878e582045a1247d978754ad7d393ee170331baa2372b240d2df14b786c5` |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/DatabaseBackupArchiveTest.kt | `077c703feda709070d1078be86053cbb10392dacc933774379caef76fdb5088c` |

- Final manifest: `95be0844fb7186db3757b92fbc2fc13a667a8f9d3fd31d3b2ebec9ec9a2266dc`.
- Selected patch: `f7f1d06209e46b528b424b9e0f44c90a11be7d03da4b37b0c82333d7617fa8df`.
- Boundary: `e20377bb3c4f5ba48c441932083c582beb6aeec5b4d5b9922eec2354bfa154bf`.
- Held-only mirror patch: `5e26791e024cfe5154db569c889e7bd88778af0d5ed16cd3c0016338564d86f9` (excluded).

The existing V1 archive remains a database archive, not evidence of the complete
approved data-only backup/restore. Cross-store snapshot coherence, raw archive
scope, restoration, full Clear/transfer behavior, device evidence and every
assembly/convergence gate remain open. Eight authored tests do not establish
passing results or readiness for integration/release.
