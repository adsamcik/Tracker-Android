# Independent blocked-startup export source review

Reviewer: `/root/adversary_product_0930`, who did not author this slice.
Phase: **IMPLEMENTATION_ONLY**. Source status: **IMPLEMENTED_UNVALIDATED**.

Reviewed input: `BLOCKED_EXPORT_SELECTED/BLOCKED_EXPORT_FILES.json`, SHA-256
`e8a90aae3b4f8e1ad074b341c4a2034952052caad6922b8e6177e2ff0f0dced5`;
selection patch SHA-256
`33b3700ecf2f6eea3a27b8c471f2f19dd8f203fb1850a493f417cf6bebc54ce4`;
base `03d5ede560591d1c6a0e143218a3af6a4a82fa3e`. All five declared actual,
selected and existing-base pins match. Mixed selection was reviewed from the
supplied snapshots, not accepted wholesale from the live drafts.

## Finding requiring repair

**P2 — Provider cleanup failure can replace cancellation or escape the result API.**
Selected `DataSettingsViewModel.kt.after:403–408` awaits `cleanupFailedExport`
without protecting the original exception. Its delete path at `451–460` catches
only SecurityException, IllegalArgumentException and UnsupportedOperationException.
A document-provider runtime failure, such as SQLiteException, therefore escapes
the cancellation handler before `throw cancelled`, or escapes the ordinary failure
handler without delivering Failure/CleanupRequired. The stream closes first, but
the cancellation/result contract is broken.

Smallest repair: return CleanupRequired for failed provider cleanup, and retain the
original CancellationException, adding an unexpected cleanup exception as suppressed
before rethrowing it. Author a cancellation plus throwing-delete provider case that
asserts stream closure, original cancellation and child completion; no execution yet.
Source acceptance awaits that repair and its affected-input recheck.

## Supported static trace

- The user explicitly chose “Disable exports until the database opens.” The actual
  blocked legacy screen no longer has an independent Export launcher or control;
  Retry and confirmed eligible deletion remain. Its fallback copy matches the choice.
- Both normal Settings writers capture the generation before reconciliation and use
  the existing retained-data transfer child. A changed generation is rejected; the
  production retained-data gate ignores collection Off without granting acquisition.
- Stream creation, copying, closure and failed-output cleanup remain on IO inside
  the admitted child. Write checks stop cooperative copies after cancellation, while
  close still runs. There is no detached cleanup job or new transfer controller.
- Actual `LegacyDatabaseRepository.export:158–172` owns output closure and sets
  KEY_EXPORTED only afterward. A successful legacy close is remembered before the
  wrapper's post-write cancellation check, preserving an already completed external
  copy instead of deleting it after the source marker has committed.
- The six new ViewModel and two Compose cases use the real selected entry points;
  their gate and provider/stream behavior are fixtures. Relevant constructor, Hilt
  contract and existing Compose test dependencies were statically traced.

## Boundaries

The existing delete-or-truncate fallback can leave an empty external file without
a later notice. Root explicitly owns the separate approved notice follow-up; this
review does not call that gap closed. Export/backup scope, full privacy cleanup,
pending exact data restore and other held drafts are excluded.

No builds, tests, lint, schema/diff checks, devices, provider activation, Git mutations
or integration were performed. Source agreement cannot establish Android document
provider behavior, compilation or readiness for dev/v10.

## Repair recheck

Repaired manifest SHA-256:
`b4550ae4e9b56801efc75a21607b705479dd58ed670b146cd563321cb7592c44`.
Repaired selection patch SHA-256:
`38a94971f36e4abd5c2594ba921f8b810f2a0e4ea67b338b9a3cf407dc7dbeff`.
The same five actual/selected/base source pins match.

The P2 finding is **closed by static source recheck**. Ordinary provider exceptions
are handled as failed cleanup, with CleanupRequired when the destination remains;
the cancellation handler retains the original exception and adds secondary cleanup
failure as suppressed. Cleanup cancellation is rethrown rather than interpreted as
successful removal. The two added ViewModel cases use the selected writers, real
wrapper close behavior and managed child fixture: one observes the original
cancellation plus suppressed cleanup exception after closure/draining, and one
requires CleanupRequired for ordinary provider delete/truncate failures. Required
cancellation imports and the fixture's observation/reset paths are present.

No further concrete finding remains in this selected five-path slice. Source
acceptance is bounded to the database-open admission, stream lifecycle and blocked
screen behavior; the separately owned persistent notice/delete-versus-truncate gap
remains open. Eight ViewModel and two Compose cases are authored and unexecuted.
Phase and validation/integration limitations above remain unchanged.
