# Independent exact Steps file adapter review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Static independent review by
`/root/fresh_bundle_context_review_0930`, using the inherited original user requests,
follow-ups, approvals, `TASK_CONTEXT.md` and current `DECISION_STATUS.md`. No production
edits, Git operations, compilation, tests, gates, provider/device work or delegation.
All 25 full-chain assembly prerequisites remain open.

## Result

No remaining concrete defect found in this exact two-path selection. It is a coherent
unvalidated **backend prerequisite**, not completion of session export or the bundle flow.

- `PortableStepsExporter.kt:92–110` copies caller-owned lists once and invokes only the
  committed exact V1/V2 overloads with the original collected-data epoch. There is no
  date fallback, new identity, current-epoch adoption or provider demand. The exact
  Room readers still own original complete replacement membership, imported IDs,
  count domains, correction/retention/deletion checks and bounded source qualification.
  The previously reviewed S1 omission repair remains present in the native reader.
- The common staging helper preserves ordinary date export's single source read.
  V2 size overflow or its initial `COUNT_DOMAIN_GRAPH_UNAVAILABLE` result may select
  V1 before destination bytes (`121–133`, `158–180`). Refusal after emission cannot
  publish a staged file. Once a format is staged, its same codec and exact selection
  are used for both rechecks; later missing data/graphs or changed content cannot
  restart another format or produce Success.
- Selected codec bytes and entry count must match before and after destination copy
  (`169–201`). Source-local checks plus canonical bytes cover graph/product changes,
  while unchanged selected content survives unrelated writes. SHA-256 is transient
  comparison only; no new backup proof, signature, schema or authority is persisted.
- Both existing codecs enforce their 64 MiB encoded ceiling during re-encoding as
  well as staging. Existing structural/source dependency caps remain unchanged.
  The existing bounded private spool copies in chunks with cooperative cancellation,
  closes its input and removes its owned file through `use`. Source/caller cancellation
  escapes. The adapter leaves the destination stream open for its owner.
- Reviewed the actual API overloads, `RoomExportPortableSteps` V1/V2 adapters,
  selected native/merged reader branches, existing codecs/spool, unchanged Hilt
  bindings, format registration and module dependencies. No additional framework,
  public format, dependency or source-neutral revision check is introduced.

## Authored coverage and retained limits

Inspected all fourteen new test methods and their real codecs, fake exact source
interfaces, spool files and destination callbacks. They exercise exact request/list
propagation, original/stale epoch, V1 graph/size fallback, pre-copy and copy-time changes,
unrelated writes, empty/unavailable input, post-emission refusal and cancellation.
The child-job copy cancellation case asserts one destination write, no final recheck,
no normal return and private-spool removal. Eleven original date-export methods remain;
the shared entry fixture only gains a count parameter with its original default.
**None of the 25 methods was executed.** Source fakes do not prove Room, Hilt or device
behavior; the separately committed exact-reader fixture sources also remain unexecuted.

No production session caller exists in this slice. The approved shared preview/write
entry point must still select organization/source inputs, use the existing retained-data
transfer admission on injected IO, own closure and failed-destination cleanup, and check
organization plus every selected child after the entire bundle copy and final handoff.
A post-copy failure here requires that caller to discard its destination. Retrospective
measurement subsets, background Steps exact-day selection, duplicate bundle receipts,
UI/default exclusions and all convergence/integration/device gates remain separate.
Root's manual-start repair files and held radio/backup/History drafts were excluded.

## Exact reviewed pins

Independently hashed all actual files and raw before/after snapshots; they match the
manifest. Reviewed the selected patch and base-source comparison; no Git was used.
Parent-supplied base: `8bbb29490c0488d26b25ffec04a7977d1482bd9b`.

| Input | SHA-256 |
| --- | --- |
| `FILES.json` | `be7a33cd5a1def8b0de8c2bb07d95a9be7027ca0f1bed68d444bc4c352ba9b38` |
| `SELECTION.patch` | `7beda040d50b7b9b6efed8b419239859903ca4dbada3e5cddcc08bb3ce9c1278` |
| `SOURCE_BOUNDARY.md` | `08d03a2cd1d120240033b6d20d2489bf5b05bd77b4922ace3bd3780ecbc15d05` |
| Exporter actual / after | `3c284c9639cd3d2c6b8023639fc14c7e00f6d32a00eb28904afef95dc2b6f410` |
| Exporter before | `3bd9daa4cb3e22a730df78630aed9413c2106756f9b4c8e2b890022b777cd952` |
| Test actual / after | `82d426617a0db566377527f54d077d976651bb98f04aae5c547c6f99ff9b180c` |
| Test before | `92f7742f5598916841e8345855882ffb30beb9af817a11d12c77fa08b07f66a0` |
