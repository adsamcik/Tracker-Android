# Exact-session Steps file adapter — selected source boundary

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Two source paths are frozen for
fresh independent static review. No compilation, tests, lint, schema/drift,
diff-check, device/provider, activation, CI, Git or integration was performed by
the author. Authored scenarios do not establish a passing or runtime result.

## Exact selection and provenance

- Parent confirmed both assigned paths clean at base HEAD
  `8bbb29490c0488d26b25ffec04a7977d1482bd9b` before editing. Their exact actual
  baseline bytes are preserved as `.before`; selected actual bytes are `.after`.
- `FILES.json` pins exactly `PortableStepsExporter.kt` and its existing test,
  their before/after/current SHA-256 values, the LF review patch and inventory.
  Snapshots preserve original bytes; the review patch uses normalized LF only.
- No held source file, schema, format or module dependency is included. Existing
  reflected constructor, registry metadata and Hilt entry point remain unchanged.

## Approved behavior implemented

1. The internal `exportSession` takes the already committed
   `ExportSelectedPortableStepsRequest`: exact native segment seeds, exact imported
   identities and the original collected-data epoch. It snapshots caller lists
   once and uses the same selection for every read. No range query, current-epoch
   adoption, history-summary conversion, provider request or new identity is added.
2. Existing source-owned V1/V2 readers retain qualification, complete original
   replacement membership, original count domains/graphs, imported bindings,
   corrections, retention and epoch authority. Their read-only exact overloads
   check selected content before/after sink emission. The file adapter adds no
   copied qualification or global source-evidence revision check.
3. Date and exact exports reuse one private staging/copy helper. Ordinary date
   exports still perform the existing single source read. Exact exports stage
   canonical codec bytes in the existing bounded private spool, then re-encode
   selected data to a discarding digest stream before and after destination copy.
   Count and SHA-256 must match. Unrelated source writes do not invalidate the file.
4. V2 encoded-size overflow or initial genuine missing graph may select existing
   V1 before any destination bytes. Once a format succeeds, both content rechecks
   use that same codec and exact source overload. A later graph loss/refusal is a
   changed selection, not permission to restart with another format.
5. Pre-copy refusal leaves the destination empty. Post-copy refusal returns failure;
   the enclosing transfer must discard its destination. Cancellation propagates;
   existing `use` and spool ownership physically close/remove the private file.
   The adapter leaves the caller-owned destination stream open.

## Existing caller boundary, still required

This is a backend prerequisite without a production session-bundle caller or UI.
The forthcoming approved shared entry point must execute on the injected I/O
dispatcher inside `TrackingStartupGate.withReadyGenerationTransfer`, own child
staging/destination closure/failure cleanup, and perform the short final handoff
through `withRetainedDataGeneration`. Collection Off permits retained-history
export; Clear cancels and drains it. No control lock should surround I/O.

That caller must also re-read exact organization metadata and all selected inputs
after composing/copying the complete bundle. This adapter's rechecks cover the
Steps child copy only, not later copying of the entire bundle. The existing
`SessionOrganizationRepository.find` owns exact organization ID, revision, epoch,
name/activity and selector metadata. No public partial bundle action/default is
exposed by this slice.

## Authored coverage and exclusions

- Fourteen newly authored test methods use exact source API fakes plus actual
  V1/V2 codecs, destination copying and existing private spool files. They cover
  native/imported request propagation, immutable lists, graph and size fallback,
  pre-copy edits, V1/V2 copy-time edits, unrelated writes, original/stale epoch,
  copy-time Clear, empty/unavailable data, source cancellation, actual child-job
  destination cancellation and post-emission refusal. `FILES.json` names each.
- Eleven original date-export methods are preserved; only the existing entry
  fixture gains an optional count argument with the original default. No new
  fixture/module/test dependency is introduced.
- These adapter tests do not prove Room qualification; the separately committed
  exact-reader Room sources cover that prerequisite and remain unexecuted.
- Shared preview/write, bundle manifest assembly, exclusions/detail navigation,
  background Steps exact-day export and retrospective measured subsets remain
  separate. Pending radio sparse-format/overlap membership and duplicate Steps
  bundle receipts are excluded. All full-chain assembly gates remain deferred.
