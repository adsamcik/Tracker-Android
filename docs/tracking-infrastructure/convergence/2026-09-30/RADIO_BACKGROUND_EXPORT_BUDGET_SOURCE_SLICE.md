# Background radio export detail budget

**IMPLEMENTATION_ONLY — IMPLEMENTED_UNVALIDATED.** Production and six focused Room test sources
are authored. No build, compilation, tests, schema validation, provider activation or Git mutation
was performed by this author. Root owns selection and independent review.

## Approved repair and exact ownership

The root assigned the existing background detail ceiling repair after the read-only findings in
`RADIO_SOURCE_EXPORTER_ADVERSARY_TRACE.md`. This slice changes only:

- Wi-Fi `backgroundChildren`: replace its two unbounded per-revision `flatMap` accumulations with
  bounded reads; pass the native remainder to the imported group.
- Cell `backgroundChildren`: the same bounded collection and remainder handoff.
- The small shared `readBoundedBackgroundRadioChildren` loop in existing `CapturedRadioDateSelection.kt`.
- New `RadioBackgroundExportBudgetRoomTest.kt` in the existing exporter test package.

Both production files are preserved untracked drafts with other work. Their whole-file hashes in
`RADIO_BACKGROUND_EXPORT_BUDGET_FILES.json` identify this read snapshot, **not permission to stage
their entire unrelated contents**. Product owns `writeFile` candidate page-call changes; root owns
the broader source transfer assembly. The manifest names exact function/hunk boundaries.

The root requested relocation of the unchanged shared loop from the untracked Wi-Fi exporter into
the existing shared file. Its imports/helper hunk and six new Room tests are independently
selectable now; exporter call sites remain held for the complete transfer assembly.

## Static production trace

`PortableWifiSourceExporter.backgroundChildren` and `PortableCellSourceExporter.backgroundChildren`
resolve selected portable fact identities to native or imported storage, then pass each selected
`(factId, semanticRevision)` to the shared loop. Before each query it checks cancellation and asks
for only remaining capacity plus one overflow sentinel. After the query it checks cancellation
again and rejects excess **before** adding the page to the result. A later owner is never read after
overflow. A full budget still permits owners with no child rows because the one-row query proves
whether any detail would exceed that budget.

The imported group receives `MAX_CHILDREN - nativeRows.size`. The original background raw-row
ceiling, selected revision queries, checksum conversion, defensive deduplication, writer's final
whole-file child ceiling and existing failure/cleanup paths remain intact. No detail is silently
clipped, and session child counts are not redefined by this bounded background collection repair.

**Corrected review assumption:** imported fact tables enforce unique `(fact_id, semantic_revision)`
and child foreign keys bind that revision to its sole parent archive. Child primary keys prevent
duplicate item indices. Equal archive copies cannot inflate the query in a conforming database;
the existing defensive deduplication is preserved. No duplicate-scope decision was required.

## Authored scenarios

The new Room test source uses existing `AppDatabase.testDatabase` and imported radio parent/child
entities, with small explicit collector budgets to exercise production behavior without allocating
the 262144-row ceiling:

1. Wi-Fi overflow shrinks the next query and skips the later owner.
2. Cell overflow does the same, preserving the unread saved owner.
3. A second storage group receives only the capacity left by the first.
4. A full budget permits summary-only owners, but rejects one later child.
5. Exact selected Wi-Fi revision is not crowded out by older revision rows.
6. Cancellation after a Cell Room read stops before the next owner and leaves stored rows intact.

The two-group case deliberately uses the actual imported query seam for both groups: it exercises
the generic collector's remainder handoff, not native authority or native detail storage. Fixture
checksums are shape-valid placeholders because qualification/checksum verification is outside this
collector. These are actual Room query/collection scenarios, **not** complete source-reader-to-file,
native provider, Hilt graph, import receipt authorization or successful runtime evidence.

## Remaining findings

This slice does not close native retained-owner child selection, new Wi-Fi detail completeness,
date prefiltering, transaction/file-output scope, imported conversion indexing, intermediate
conversion cancellation or whole-file transport cleanup coverage. Their findings remain in the
adversary receipt; product/root own the separate follow-up. No source-transfer, assembly-gate or
`dev/v10` readiness claim is made.
