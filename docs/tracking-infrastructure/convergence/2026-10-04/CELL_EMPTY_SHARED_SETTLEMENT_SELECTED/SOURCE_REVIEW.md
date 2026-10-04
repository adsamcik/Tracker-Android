# Independent source review: empty Cell settlement with a shared provider (R10)

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Review-only inspection of the exact two-path frozen selection, original R10 finding, existing raw retirement/completeness readers, source broker retirement, shared Cell controller/runtime and coordinator persistence. No source edits, Git mutations, executable gates, device/providers or recursive delegation.

## Exact input

- FILES.json: `d52475339c296e3c06529f2a63f6591a910ce8bced348265bf02274adfe397e3`.
- SELECTION.patch: `60c7c295823d62567ff4b0939b1ecebd9e0dee997107275a11b8a31233c2dc23`.
- SOURCE_BOUNDARY.md: `b247af57a336d24a804120512336955a7ab2c0bcf30a436114f500a5b1e69ca9`.
- Production BASE: `2ace9aec6738f2183a89ba0aa33d45dae2edb976b74f022030ae2e03baa1bb22`; SELECTED: `6c9d7711324876956bd2ddc963313d068a313a25a78b0240a7040479a970f35b`.
- Test BASE: `0e0dbfef3c683415e756d7d7873d74e80d3b89bc9c8ad27c15f9963e3f73f1b1`; SELECTED: `b8fa85b115f4ef25eeeb99bbb8d6161e6a90d959b3ac13df25c960409b276166`.

Both BASE and SELECTED files and all three packet hashes independently matched. Review accepts these selected snapshots only; actual working whole files and preceding fixture drafts are not implicitly selected.

## Result: narrow static acceptance

The concrete R10 ACTIVE-provider coupling is repaired. For an empty run with no fact/WAL evidence, the source-owned portable reader can now authenticate that run's independent completed prefix without requiring a sibling purpose to stop the physical registration.

The new branch requires exactly one validated existing run-retirement receipt for the precise source/run/instance/registration key. It requires ACKNOWLEDGED/COMPLETE, app drain and completeness agreement, no failed/unresolved admission, a callback-entry boundary, the expected shared shutdown outcomes and compatible run lease/cutoff chronology. Provider admission identity, boot, data epoch, process residency, physical fingerprint and accepted-registration conflict checks remain.

Authorization revisions are read with the existing explicit limits. Their complete immutable member snapshots are recomputed against the saved demand rows. Only the selected run's persistent capture demands participate in the run proof; an unrelated background/session demand cannot supply its missing membership. The final selected demand retirement must match the acknowledged elapsed cutoff, and a later authenticated nonempty sibling-only closing revision must exist at that exact cutoff with matching wall time. A revision still containing the ended capture or merely occurring later cannot substitute. Further valid sibling-only revisions do not require the shared provider to end.

The old retired-provider path preserves its prior identity/status/ORDERLY_STOP and accepted-to-retired chronology conditions. The shared path is specifically for a still ACTIVE accepted registration; this receipt does not declare every later provider-failure state covered. Empty output remains NO_RETAINED_OBSERVATION/PARTIAL, without asserting that Android produced no physical observations.

## Existing production closure

`SourceBroker.retireSessionDemandsInTransaction` retires the session demand and rotates current authorization at the supplied cutoff. `SharedCellSourceController.settleSessionCutoffLocked` refreshes the surviving purpose and calls `CellSourceRuntime.sharedSessionCutoff`; that method drains the callback prefix, returns COMPLETE with NOT_REGISTERED/NOT_SUPPORTED and leaves the shared provider joined. `AuthoritativeSessionCoordinator` persists the exact requested retirement then its acknowledgement and matching completeness. The new reader consumes these already existing rows through the bounded raw reader, which rejects invalid or ambiguous retirement rows. No runtime/schema/API, global callback barrier, new store or format was added.

## Test-source review

Ten new actual Room cases use the production portable exporter and a real sink:

1. Empty completed replacement exports while background remains ACTIVE, preserving empty/PARTIAL semantics and a zero global barrier.
2. Historical capture remains exportable after later consent revocation.
3. A later valid background authorization preserves the earlier completion.
4. Missing exact retirement receipt fails before sink output.
5. Drain receipt/completeness disagreement fails before output.
6. Missing closing revision cannot borrow another purpose's authority.
7. A closing revision still containing the ended capture fails.
8. A later sibling-only revision cannot impersonate the original cutoff.
9. A demand retired before the acknowledged elapsed cutoff fails.
10. Stale provider data epoch fails despite the completion records.

The fixture writes real existing Room entities and authorization snapshots. It exercises the new empty-settlement branch; original retired zero-callback and malformed-registration/authorization tests remain unchanged. These cases are meaningful source authorship, not executed results or real-provider evidence.

## Remaining boundary

This accepts only the R10 source repair and its ten authored cases after the committed historical Cell prerequisites. R11 Steps/Pressure acknowledgement, ordinary Pressure History/export ownership, Wi-Fi follow-ups, radio transfer representation/import membership, all 25 full-chain prerequisites and final convergence/device/integration remain open. No gate or assembly-readiness claim is made; original frozen rejected evidence remains preserved.
