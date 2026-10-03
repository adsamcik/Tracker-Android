# Exact Steps export prerequisite â€” frozen 2026-10-04

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** The nine source/test paths selected in
`SESSION_STEPS_EXPORT_FILES.json` are frozen. No tests, compilation, Gradle, lint, schema, device,
providers, CI, validation gates, Git operations or integration were performed by this author. Parent
confirmed the seven clean paths at `2d01ed0ee411bf2bc48d4c45fe070de6b945f102` and supplied exact
HEAD/working-before snapshots for the two mixed paths. The narrow patch contains only this slice.

## Approved behavior implemented

- Add exact-record overloads to existing V1/V2 Steps export contracts, sharing
  `ExportSelectedPortableStepsRequest(nativeSegmentIds, importedEntryIdentities,
  expectedCollectedDataEpoch)`. There is no new provider, file format, schema, store, Hilt binding,
  caller, fallback source, or bundle surface.
- Native IDs are looked up exactly, with missing/unmapped seeds rejected. Existing original logical
  identity, service-run paging, segment bindings, closed-session/run settlement, manifest, source
  facts, deletion/retention and count-domain checks construct the full original replacement group.
  Every explicitly requested native logical group must build exactly once. Exact mode rejects
  absent capture membership and any group that ordinary date exports would omit (all deleted,
  expired, or without exportable payload); a valid sibling cannot conceal it. This closes fresh
  review finding S1. The common native assembler accepts no date predicate for exact selection. Existing date
  discovery and its original overlap predicate remain on standalone date requests.
- Imported IDs use the existing source-owned retained reader in its existing 32-ID batches. Every
  requested ID must be returned exactly once, with complete portable data; failures and missing
  members cannot disappear into partial success or be replaced by nearby observations. Original
  opaque identities, counts, covered zero, gaps, run envelopes and deletion scopes remain intact.
  The existing export dependency budget and format limits are reused without a silent partial tail.
- V2 uses the existing native root/owner-lineage extraction and authenticated imported binding and
  graph conversion. The existing V1 representation remains available when the established V2
  outcome is `COUNT_DOMAIN_GRAPH_UNAVAILABLE`. The existing file adapter's preference/fallback
  behavior is unchanged; this prerequisite does not add an exact-session file caller.
- Original epoch and active retention settlement are checked in the same Room snapshot for both
  origins. Export snapshots finish before external sink calls. Input ID lists are copied once for
  each transfer. After emission, the exact original request is read again; V1 compares full typed
  selected products and V2 compares products plus graphs. Selected data, binding, membership,
  retention or epoch changes prevent completion; unrelated ingress and global evidence revisions
  do not invalidate unchanged selected contents. Cancellation propagates. A consumer must discard
  staged bytes after a non-success result; no midfile fallback is introduced.

## Read-only graph verification repair

The existing `loadAuthenticatedImportedSessionCountDomainBinding(RetainedImportedStepsEntry)` can
reconcile/write an older truncated legacy binding. Standalone date V2 continues using that existing
behavior. New exact V2 preflight and final reread use the existing `PortableStepsEntryV1` overload,
which strictly authenticates saved product/binding/graph/provenance without reconciliation or
durable writes. No core changes or replacement graph metadata are added.

An existing native V2 capability limit remains: `hasCompletePortableOwnerLineages()` requires an
owner lineage starting at revision 1, while `completenessOwnerRevision(row)` derives
`updatedAtMs + 1`. Missing/truncated native owner lineage can therefore yield the established
graph-unavailable outcome, while the same valid source facts remain representable as V1. This
slice does not invent or fill missing graph owners or change durable graph semantics. It does not
claim native V2 graph availability on ordinary devices.

## Selected ownership and held work

`SESSION_STEPS_EXPORT_SELECTED` stores HEAD-based before/after snapshots for the mixed
`ImportedStepsProductReader.kt` and `ImportedStepsProductRoomTest.kt`. The actual working files
retain these excluded held changes:

- Reader: `selectedInTransaction(List)` history overload and `toListEntry` original-envelope
  presentation change.
- Room fixture: the two leading original-envelope/unified-range cases that depend on held work.

The selected snapshots follow HEAD's LF convention; actual mixed-file hashes are pinned separately.
Canonical held hunks were matched unchanged and removed only from the selected snapshots. All
other working paths and drafts are excluded.

## Authored coverage and limits

The manifest enumerates **27 new, unexecuted Room cases**: fifteen native and twelve imported.
They cover complete replacement groups, coincident unselected records, duplicate requested IDs,
missing/unmapped/deleted members, closed/pending presentation, retention boundaries, original
epoch before/after emission, changed source contents, independent Activity/import/revision changes,
covered zero, sink cancellation, actual stored imported V2 graph preservation, unchanged stored
revision/binding/graph metadata, binding deletion during V2 emission, and traversal beyond the
existing 32-entry batch. Existing standalone file and importer test fakes explicitly reject
accidental exact-overload use; their existing date/import assertions are unchanged.

Native V2 graph-unavailable/V1 representation and actual saved imported V2 graph preservation are
separate authored cases. No direct codec/transport/UI chain or runtime provider/device behavior was
executed or newly authored here. Written cases and static source tracing are not passing evidence.

This is a source prerequisite. Shared session preview/write, all-six source availability, metadata,
organization/final handoff fences, UI and bundle integration remain unfinished outside these nine
paths. Pending overlapping imported-radio/native-Steps file membership is neither implemented nor
assumed. The approved measured-date filtering and optional complete-session expansion belong to
the future consumer; this prerequisite preserves whole original typed entries. Overall assembly
readiness is unproven.

Selection patch SHA-256: `cf4ec478b56fa62b64790e7fa14734052dc3b351d2dd4ebdac6a415a75bca683`.
Manifest SHA-256: `ccdcceeca19db048a7579a6f30d42537b093671feb4bddb931507a7d5266173c`.

S1 adds four unexecuted native Room cases: expired, no-payload, all-deleted and missing capture
membership selected groups beside valid siblings, including standalone retained-date preservation.
The native test before pin now identifies the raw immutable Git blob supplied by parent; its older
manifest pin described normalized bytes. No source or test execution informed this repair.
