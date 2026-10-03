# Exact Pressure export prerequisite — frozen 2026-10-04

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** Six owned source/test paths are frozen in
`SESSION_PRESSURE_EXPORT_FILES.json`. No tests, compilation, Gradle, lint, schema, device, providers,
CI, validation gates, Git operations or integration were performed by the author. Parent supplied
clean-at-start provenance: the first five paths at `e183d7485778b28d341f736bf4ef4a31e01b69a8`, the
sixth at `2d01ed0ee411bf2bc48d4c45fe070de6b945f102`. Before bytes were captured locally; the narrow
patch and manifest contain the exact selected delta and byte hashes. All other working changes are
excluded.

## Approved behavior implemented

- Add one exact-record overload to the existing `ExportPortablePressure` contract. Its request uses
  existing native segment IDs, imported opaque entry IDs and the original collected-data epoch.
  No new identity, storage, binding or source activation is introduced.
- A native seed resolves through the existing Pressure selector to its complete original logical
  replacement group. Imported IDs resolve only through exact imported candidates and the existing
  correction/deletion/retention evaluator. Neither selection uses dates or falls back to another
  record. Coincident unselected native or imported data is not added.
- Missing seed/member, incomplete materialization, unavailable retained payload and conflicting
  selected content fail before the first sink call. Equal native/imported typed contents sharing an
  identity are emitted once. Format bounds remain the existing entry/run/window limits; existing
  four-candidate batches traverse the whole selection or return a failure, never a partial tail.
- Exact snapshots check the original epoch and active retention settlement. The Room transaction
  completes before any external sink call. After emission, the same exact records are read again
  and their complete immutable typed contents compared. Selected correction, deletion, membership
  or epoch changes prevent completion. The global evidence revision is not a selected-content
  dependency, so unrelated collection/import/revision changes do not invalidate an unchanged
  selection. Verification creates no durable state or identities.
- IO uses the existing injected dispatcher. Cancellation propagates. Non-success after emission
  requires the consumer to discard its staged child; this is explicitly part of the API contract.

## Existing native export defects repaired

The standalone range reader previously composed native Pressure entries without loading original
logical-session headers. Its converter required a closed header, so valid native exports became
unverifiable. Both range and exact readers now supply the existing exact source-owned headers to
the existing composer.

`PressurePortableFormatV1.kt` requires a portable entry's bounds to equal its original source-run
envelope. The old converter used the session interval instead, which rejected a source ending at
2000 when its session closed at 3000. The converter now separately validates the closed logical
header and preserves the saved run envelope. Original measurements and timestamps are unchanged;
no duration, sample or interval is invented. This follows the existing Activity conversion in
`ActivityCapturedPortableTransfer.kt` (portable run min/max envelope). Standalone date selection,
file format, identities, checksums and consumer behavior otherwise retain their existing contracts.

## Authored coverage and evidence limits

The manifest enumerates **20 new, unexecuted cases**: ten native Room fixture cases, nine imported
Room fixture cases and one actual JSON codec case. They cover full replacements, unrelated
coincident imports, missing native/imported members, missing imported windows, corrected content,
retained gaps, epoch and deletion races, unrelated revision/record changes, equal/conflicting
identities, materialization, cancellation and traversal beyond one existing four-item batch. The
range regression uses the actual native session cutoff 3000/source end 2000, followed by an open
header. The codec case checks the same source-envelope constraint through the actual existing
encoder/decoder, preserving original windows. The date-only test fake rejects accidental use of
the new exact overload; existing date assertions remain unchanged.

Room and codec cases use their existing separate module fixtures. There is no new test dependency
or access seam, and no authored single-test Room-to-JSON cross-module chain. Static tracing links
them through the existing public typed Pressure entry. Authored cases and static inspection are
not proof that compilation or tests pass.

## Deliberately unfinished surrounding work

This is one source prerequisite, not an exposed partial session-export feature. Shared session
preview/write, organization revision/final handoff fencing, all-six availability/default choices,
UI, Hilt and bundle metadata/import integration remain outside these six paths. Retrospective
timestamp filtering and pending imported-radio/native-Steps file membership decisions are not
implemented or assumed. Overall tracking assembly and integration readiness remain unproven.

Selection patch SHA-256: `9a30b96910eca488573d392fe709b4848184321591efbb7dd4a4f9727977eea8`.
Manifest SHA-256: `50bb42e7ad037090312422d59c783cb38404b310b6685ce70bb49c194699acc7`.
