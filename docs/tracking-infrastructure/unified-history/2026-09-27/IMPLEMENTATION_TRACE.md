# Unified history continuation — 2026-09-27

Original request:

> **Implement unified history.** Finish bounded day/range queries, paging, calendar, timeline and detail views across native, background and imported observations.

## Phase and inputs

IMPLEMENTATION_ONLY remains in force. Production and focused test sources, static review and local
IMPLEMENTED_UNVALIDATED commits only. No Gradle, compilation, tests, lint, Detekt, schema generation
or drift, diff-check, emulator/device, UI evaluator, battery, CI, release or rollout validation.
No activation, final freeze, new dev/v10 integration or push. Main remains13f1a99871. Candidate is
codex/source-native-map-context-root, base835302e86c. Protected root paths and preserved drafts are
untouched. This task does not complete the whole assembly gate.

## Implemented foundation

- `UnifiedHistory.kt`: half-open finite range and view-zone/source/origin/purpose selection; strict
  global `(startTime DESC, originTag ASC, stableId ASC)` key; range-bound continuation; typed source
  sections/problems and read snapshot. CONTROL has no general-history purpose. Geographic legacy
  uncertainty is distinct from qualified Location. Steps carries no sibling source payload.
- Pure bounded merge consumes equal representatives together and rejected heads only when reached.
  Prefetched un-emitted heads remain unconsumed. It stops before an exhausted stream's unknown
  lookahead frontier; filtered empty pages may continue. No per-stream persisted cursor/high-water.
- Common facade range contract and Android paging interface are authored. They are **not yet wired**
  to the production repository, Hilt binding or UI; compatibility defaults are unavailable, not a
  promise of supported history.
- `UnifiedHistoryCandidateQueries`: bound code-owned native/legacy and five imported key-only
  range/seek discovery (100 candidates plus lookahead). Native representative uses original logical
  start and explicit identity, independent of replacement run recency. Import identities stay in
  their own source namespace. Closed reversed intervals remain uncertain candidates. Simultaneous
  live/retained imported Activity/Pressure shells yield one conflict-marked key, not duplicate keys
  or silent suppression of conflict evidence. Candidates never grant source/action authority.
- `selectedLogicalMembersInTransaction`: existing bounded complete membership proof is exposed
  with typed absent/incomplete/overflow results. Content is membership proof, not source proof.
- Imported Steps exact selected-key read reuses the retained checksum/deletion/retention reader.
- Ambient Wi-Fi/Cell expose their unchanged authenticated day composition inside the caller's Room
  transaction. Public day reads delegate to the same helper; no acquisition/capability contract changes.
- Background Steps exposes sessionless day composition; native/imported selection is applied before
  reading/composing each origin. Capture partitions cannot be reintroduced as background history.

## Authored test sources — unexecuted

- `UnifiedHistoryMergeTest`: six interleaved250-candidate streams (1500 rows), repeated lookahead
  refetch, exact ties, equal representatives, rejected/empty continuation, unknown frontier, filter
  scope mismatch and invalid stream order.
- `UnifiedHistoryCandidateQueriesRoomTest`:251 old records paged past a recent window, origin/id
  tie seeks, half-open overlap/point boundaries, reversed intervals, missing/incomplete logical
  membership and complete explicit replacement membership without manufacturing qualified facts;
  simultaneous live/retained imported shells yield one conflict-marked key and strict continuation.
- `AmbientStepsDayRepositoryTest`: capture count999 cannot contaminate background count10;
  imported-only selection excludes local facts/counts and corrupt excluded native lineage without
  changing stored sibling data.
- `ImportedStepsProductRoomTest`: exact selected missing/valid/corrupt retained evidence, plus
  independently surviving sibling after exact deletion aftermath.
- Existing ambient radio day/import/retention sources exercise public day reads through the extracted
  same-transaction helper. No redundant equality-only test was added for that behavior-preserving move.

## Required decisions presented through tool questions

1. Preserve previously authorized historical Location on collection/background off or permission loss
   until explicit erase/retention (recommended), versus current-policy visibility gating. The written
   unified-history plan prefers preservation; retained-authority checklist still marks this unresolved.
2. Approve a small rebuildable exact local ownership index for Activity/Pressure import collision
   resolution (recommended), versus retaining bounded whole-history ownership scans and reporting
   unavailable at their budget. This is a changed storage design; supplied AGENTS requires approval.

No answer has been inferred from elapsed time. No dependent policy/index change is implemented.

## Remaining implementation — whole request stays open

- Exact selected imported Activity/Pressure/Wi-Fi/Cell qualification, collision equality and suppression;
  resolve ownership-index decision without whole-history scans or timestamp/native-origin aliasing.
- Complete retained Location source reader: durable canonical/ambient receipts, historical purpose and
  registration/authorization/manifest proof, correction/deletion/retention checks. Current generic
  source-fact lookup is capture-only and WAL-dependent; background proof is not implemented there.
  Do not require generic WAL retention proofs permanently: maintenance caps them at4096 per source.
- Stored-zone-aware background day candidate discovery, selected origins, range-boundary truth and
  bounded source dependencies. GPX/KML/JSON legacy points have descriptive import/legacy scope but
  lack exact segment/archive membership; no coincident-time native authority.
- One consistent shared Room snapshot with retention/epoch guarding, successful sibling sections on
  source-local failures, common-authority corruption failure, and complete consulted-table invalidation.
- Production facade/Hilt/paging, refresh-anchor preservation and private-payload invalidation fencing.
- Calendar queries actual selected month/day; stale request fencing, truthful incomplete/overflow state;
  timeline and selected details across all sources/origins. Existing UI remains unchanged in this slice.
- Organization overrides/suppression before range/limits and authenticated interval contents, preserving
  raw source facts outside organization intervals; no timestamp-only imported membership.
- Authored full-source Room/Paging/UI/privacy/authority chains, fresh final independent static review,
  then only the separately authorized final convergence batch may execute gates.

This foundation is not unified-history completion, device readiness, or passing test evidence.
