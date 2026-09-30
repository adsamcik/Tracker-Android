# Native Cell per-reading storage and history

Status: **IMPLEMENTED_UNVALIDATED**. Static source inspection and independent review only.
No compilation, tests, schema generation, lint, device/provider activation or integration ran.

## Selected source slice

- Cell admission authenticates the valid provider items inside the recorded physical lifetime.
  A cached pre-registration or post-retirement sibling cannot discard valid items in version 6.
  Version 5 keeps its original callback semantics.
- The writer saves only qualified tower children atomically with their parent revision and cursor.
  Each child's original provider time, identity, signal and millisecond rounding uncertainty remain.
- A shared uninterrupted-demand window is reused by writing, maintenance and retained history.
  Unrelated authorization rotation preserves a continuing purpose; a newly joined purpose cannot
  claim earlier items. Effective callback authority remains checked at receipt time.
- Cell parent bounds include rounding uncertainty of every accepted child. Original WAL uncertainty
  remains separate in reconstructed evidence so an unchanged replay remains unchanged.
- Bounded native history loading supplies exact current-revision children to the existing composer.
  Selected deletion, consent erasure and retention delete children with their parent facts.

The `CELL_NATIVE_SOURCE.patch` records the narrow shared-file selection: maintenance excludes
the unrelated deletion-fence exception hunk; repository changes include only native child loading;
Room tests exclude the separately authored map API methods; WAL tests preserve unrelated drafts.
Whole selected files are `CellPurposeItemQualification.kt`, `CellWalCarrierAuthenticator.kt`,
`CellCapturedFactClassifier.kt`, `CellCapturedFactWriter.kt`, `CellWalQualificationAdapter.kt` and
`CellHistoryComposer.kt` in their existing modules.

## Authored coverage and review

Focused source scenarios cover exact children, missing/tampered children, legacy callback data,
fractional milliseconds, continuing authorization, newly joined capture, pre-registration and
post-retirement siblings, close rounded siblings, stale callback authority, retention cleanup,
unchanged replay and actual Room history composition. They have not been executed.

The independent wiring reviewer inspected the native adapter, writer, maintenance and initial
test source. Its raw-latest-registration finding is addressed here. Final review of the resulting
history and rounding changes remains part of convergence; this receipt is not a full-chain pass.

## Outstanding boundaries

Imported Cell detail attachment and compatibility remain a separate slice. Historical multiple
capture-session groups, midnight grouping, full history UX, map presentation and export assembly
remain open. This commit does not freeze assembly or establish readiness for `dev/v10`.
