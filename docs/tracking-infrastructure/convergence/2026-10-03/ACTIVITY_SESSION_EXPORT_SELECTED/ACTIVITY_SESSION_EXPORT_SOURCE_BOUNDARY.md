# Exact Activity selection for session export

Date: 2026-10-04. Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Five clean-at-start owned paths are pinned against immutable base `2d01ed0ee411bf2bc48d4c45fe070de6b945f102` in `ACTIVITY_SESSION_EXPORT_FILES.json` and its narrow selection patch. No whole preserved draft, schema, new store, format, Hilt binding, provider demand or UI was added. No Git mutations or validation occurred.

## Production chain

`ExportPortableCapturedActivity` has a separate exact-request overload, matching the existing Pressure contract shape: native segment IDs, existing imported opaque entry IDs and the original collected-data epoch. The original date request and standalone export still use their existing range semantics. The standalone fake explicitly rejects accidental exact selection.

`RoomExportPortableCapturedActivity` copies the requested ID lists, reads one qualified Room snapshot, emits the existing lossless Activity envelope outside the transaction, and re-reads the same selected typed contents and epoch after the sink. Independent evidence revision updates do not invalidate unchanged contents. Changed, erased, unavailable or retention-crossing selected data cannot produce `Exported`; the outer transfer must discard staged bytes on any refusal. Sink cancellation propagates.

The existing Room reader resolves every native seed through its exact reciprocal segment/service-run binding. Legacy, missing and mismatched seeds are refused. Only those original logical IDs expand to their complete replacement-run membership through the existing bounded keyset query; no other session is discovered by dates. Source-local `ActivityCapturedAuditScope` reuses the existing lineage/cursor authentication, while cumulative revision, fragment, evidence and window budgets remain bounded. Complete manifests, terminal projection settlement, immutable header/run membership, retention and deletion checks reuse the existing builder. Not-captured replacement runs and real gaps remain intact.

Imported selection calls only the existing `ImportedActivityProductReader.selectIdentityInTransaction` for the named identities. Missing rows never borrow a native counterpart or date neighbor. Deleted or truncated whole entries are refused. A private source-reader accumulator checks the existing whole-file entry, run, window, fragment and zone-epoch limits incrementally before expanding opaque ownership or retaining a new hierarchy. Identical native/imported typed entries count once, divergent entries remain conflicts, and selected imports are qualified and included one at a time. The distinct selected native logical-ID count is bounded before scoped audit. The same ownership merger handles explicitly selected native/imported identities; no extra archive member is injected. This does not solve the separately pending overlapping-file membership decision or change existing imported authority lookup semantics.

## Authored evidence — not executed

Nineteen new methods reuse actual existing Room fixtures and the contract source:

- Native replacement-group expansion from a factless sibling seed; latest corrections; explicit gaps; stale epoch; missing and mismatched seeds; legacy rejection; existing read-budget exhaustion.
- An unrelated malformed captured fact plus an independent evidence revision change does not invalidate a selected valid native group.
- Selected cursor loss during sink copying refuses output; cancellation occurs outside Room and leaves saved source data intact.
- Imported latest correction among overlapping entries; missing exact identity without date fallback; deleted run; retention crossing; a new selected correction during copy; epoch advance during copy.
- An imported ID without an actual imported row cannot borrow its existing native counterpart.
- The actual Room boundary case imports four valid 16,384-window entries (65,536 total), accepts that exact limit, then refuses one additional window before sink or a later missing identity read; no millions-row fixture or new production test-only limit is introduced. Exact native/imported typed copies count once at the existing one-entry/one-run read limits, and a divergent shared ID still refuses.
- Exact request bounds and required nonempty selection; standalone exporter fake preserves its range-only routing.

These are authored host/Room source scenarios, not passing evidence. Compilation, Room generation, tests, native provider/storage behavior, performance and integration are unverified. The actual shared-session preview/write caller, pending file membership work and all 25 assembly chains remain separate; this backend prerequisite does not make the bundle feature complete.

The original 16-case freeze is preserved in `PRE_A1_REPAIR/`; the current five-path patch includes the A1 repair and three additional authored cases. No test or performance result was executed or inferred. The latest measured-date / Include complete sessions approval is separately owned and is not implemented by this exact whole-record prerequisite.
