# Native Activity/Pressure ownership lookup

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Original authorization: `call_NVOtz1YnQRq4DukPr7u0Wh8B`, item 5 proposed a small
rebuildable local lookup so imported Activity/Pressure can be shown and acted on without
scanning all history. The human answered **Use the small lookup**. No conflict-display UX,
file format, new provenance hierarchy, or declined exploration lookup is introduced here.

## Exact source selection

- Immutable base: `07d5c664918affa8cc843fd243b38032bb9c782c`.
- Parent at freeze: `640a27da4ff8edeb16311d2461ef35dd4f6d076c`.
- `FILES.json` pins 13 production and 7 test-source paths, their actual bytes and selected LF bytes.
- `SELECTION.patch` selects a coherent HEAD-based lookup and existing shared imported-reader repair.
- Four mixed paths have explicit `BASE/` and `SELECTED/` snapshots. Their other R11/runtime drafts
  remain in the working files and are excluded from this selection.
- `WORKING_BEFORE/` preserves the eight original production snapshots taken before edits. The
  Pressure selector's one constant-visibility change has a reconstructed LF before snapshot;
  clean test companions use the immutable Git base rather than claiming saved pre-edit bytes.
- `OWNED_WORKING_DELTA.patch` additionally pins the repair to the held selected Pressure bridge.
  `R11_BRIDGE_DELTAS.json` isolates that change. The bridge itself is not in HEAD and still needs
  the separately pinned R11 `UnifiedHistoryNativeVisibility` and candidate-query foundation.
  An isolated change to that held method is not a runnable production selection.

## Production trace

The six-column `native_portable_ownership` table stores source kind, existing identity kind,
opaque ID and its original raw logical/run owner. It contains no sensor values or permission
claims. Activity includes its existing deletion-scope digest because the current composer
checks those collisions as well as entry, physical-run and window identities.

Existing `SourceSessionDao.insertServiceRun/updateServiceRun`, Activity revision insertion and
Pressure revision insertion keep the lookup in the same Room transaction. Ignored fact inserts
do not claim a new owner. Existing source deletions, retention SQL and run changes prune only
keys without a remaining original owner; correction revisions preserve their stable key.
Run-level ownership remains while the original run still exists, including value-free retained
history. The ordinary source reader still applies consent, retention, deletion and lineage.

The existing unreleased 28-to-29 migration creates and completely rebuilds the lookup from all
original service-run and fact metadata, without a capped partial scan. Fresh Room creation
installs the same deletion triggers. Full collected-data clear explicitly removes the lookup.
Version-29 preflight now requires the exact table, indexes and pruning triggers; older incompatible
development databases remain preserved under the existing blocked-open behavior. No schema was generated.
Unhashable legacy identifiers remain in their source tables and cannot be valid portable owners.

Activity shared import qualification looks up only the selected import's entry/run/window/scope
keys, then uses the existing complete native source selector and unchanged ownership composer.
Pressure's shared reader resolves only exact selected entry duplicate authority through its
existing complete selector and unchanged comparison. No global native history census remains
in these selected shared-reader paths. Metadata queries use 256-key batches and true lookahead;
relevant owner/member overflow remains a typed budget failure, and missing exact owner membership
is an integrity failure. Cancellation propagates. Other source collection and UI are untouched.

## Authored test scenarios — not executed

Fifteen new cases use actual Room boundaries and existing source fixtures:

- Successful/ignored Pressure writes, correction identity, last-revision pruning and full clear.
- Source-specific capture intent, erased run cleanup and a complete 300-run rebuild.
- Additive DDL parity with fresh Room and repeatable creation without data loss.
- Older exact owner lookup amid 140 unrelated incomplete native groups, source independence,
  relevant owner overflow and an orphaned exact owner.
- Activity import visibility amid 140 unrelated groups, exact duplicate suppression and native
  run/window/scope conflict preservation, plus correction/window pruning.
- Pressure import visibility and exact duplicate suppression amid 270 unrelated groups.

Existing divergent Pressure-copy and Activity ownership-conflict cases remain unchanged.
No compilation, tests, lint, schema checks, device/provider execution, CI or Git mutation occurred.

## Explicit remaining boundaries

- Pressure native cross-entry run/window collisions are not compared by the current shared
  entry-only comparator. That pre-existing correctness gap remains a separate follow-up;
  this packet deliberately preserves current Pressure comparison behavior.
- The general history UI, R11 foundation selection, exact archive membership, data-only restore,
  correction representations and complete six-source lifecycle assembly remain separate work.
- A future raw database restore must preserve or completely rebuild this lookup before history
  admission; a partial rebuild must never establish absence. No new restore path is authored here.
- No assembly completion, integration readiness, device evidence or passing gate is claimed.
