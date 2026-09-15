# Standalone continuation prompt — Tracker Android

You are taking over a long-running tracking-infrastructure implementation in Tracker Android.
You have no usable previous-chat context. This prompt, the repository, commit history, and the
included tracking documents are the durable truth. Do not retrieve or depend on the corrupted
old chat projection. Read this prompt completely before changing anything.

## Immediate operating instruction

Continue **IMPLEMENTATION_ONLY**. Implement production logic and author focused unit, contract,
Room, migration, and UI test sources together. Do **not** execute or compile tests, run Gradle,
builds, lint, Detekt, Room schema generation/drift, `git diff --check`, CI, emulator/device,
UI evaluator, battery, release, or rollout validation yet. Do not iterate from earlier test failures.
The user wants all pieces assembled first, then one frozen validation/fix batch at the end.
Static source inspection/review, bounded fixes, exact-path staging, and coherent local commits
are allowed. Label new work **IMPLEMENTED_UNVALIDATED**; authored tests are not passing evidence.
This rule must survive your own compactions and every delegated task.

The user explicitly requested on 2026-09-15 that all reviewed tracking work be committed onto
local `dev/v10` for this handover. This is a narrow exception to older documents that prohibited
unvalidated integration. This checkpoint is **not** the implementation-complete gate, validation
readiness, rollout approval, or a completed six-source effort. No push, activation, release, tag,
deployment, hosting mutation, force push, or destructive/backward-incompatible migration is authorized.
Continue new slices in clean worktrees based on the transferred local `dev/v10`, then integrate
coherent handover work only within the user's current authority. Keep original source refs for archaeology.

## Product mission and scope

Complete six independently useful source-to-product verticals: Location, Wi-Fi, Cell, Activity,
Steps, and Pressure. A user can capture any one source while disabling all five others; manual
tracking must still work as well as that supported/permitted source allows. Unsupported or denied
capabilities produce typed truthful limitations, not hidden fallback hardware.

- Manual only-X means capture exactly X and no hidden control or ambient demand.
- Automatic only-X captures exactly X; start/stop controls are separately enabled, consented,
  explicitly explained CONTROL dependencies. Without a viable control strategy, automatic may be
  unavailable; manual must remain available. Control facts never become captured history/export.
- Ambient is a separate default-off, consented sessionless product where useful. Steps is
  opportunistic, not a hidden always-on service; Location passive/opportunistic; Wi-Fi and Cell
  callback-driven without default active attempts. Continuous ambient Pressure is not a product default.
- A fresh, valid, post-effective source observation becomes durable without waiting for enrichment.
  Requests, registrations, permissions, baselines, timers, and stale cache reads are not observations.
  Exact replay of identical provider content/time has zero effect. Fresh unchanged/empty radio
  deliveries may preserve compact new coverage, not repeated payload/state.
- Expensive providers are optional enhancements. Context may read a compatible source already
  collected but cannot start, retain, escalate, or delay providers/source qualification.
- Acquisition tiers must have real quality/latency/battery differences. Collapse fake renamed modes.
- Product UI distinguishes disabled, unsupported, permission required, OS limited, waiting,
  recording, materializing, partial, ready, degraded, failed, deleted, and unverifiable.
  Missing/unavailable/partial Steps is never fabricated complete zero. Pressure is direct hPa
  trend/statistics, not uncalibrated elevation. Wi-Fi/Cell default products are identity-free.
- A source is finished only with reproducible provider → durable fact → one canonical writer →
  production query → truthful visible product and listener retirement proof. Commits/host tests
  alone cannot establish this. The final batch must include proportional representative Android evidence.

This is a personal/open-source, local-first Android application. Preserve useful quality and
privacy/battery/lifecycle guarantees without enterprise infrastructure or an exhaustive OEM matrix.

## Architecture to preserve

Reuse SourcePolicy, immutable manifests and exact effective attribution, logical tracking entries,
service runs, physical segments, durable intent/lifecycle state, purpose-aware broker, one physical
owner per source, durable ingress/WAL, source-local writer/cursor fencing, Room transactions, Hilt,
Flow, dispatcher abstractions, and the existing Dashboard/History/Calendar/detail/settings/actions.
Compose logical product history over existing source facts; preserve exact physical run ownership.

Do not introduce a universal materializer language, generic tombstone platform, permanent observer
network, speculative all-source schema, per-row query fan-out, wholesale architecture replacement,
new UI platform, telemetry, analytics, tracking upload, or automatic diagnostics upload.
Existing NetworkGateway is the only production egress boundary for user-enabled map resources.
Tracebox diagnostics remain local and payload-free, without coordinates or radio identifiers.

Non-negotiable correctness rules:

- Never activate two canonical writers. Existing canonical Location remains protected until an
  evidence-backed shadow/cutover decision; all candidates remain dormant/default-off now.
- Never use QUIESCED as a deletion predicate or wall-time overlap as ownership.
- Never infer Location or source qualification from `sampleCount`/`sample_count`.
- Reject stale/wrong-boot/generation/consent/deletion callbacks and do not revive stopped/finalized runs.
- Control-only observations cannot appear in capture history, export, numeric effects, or goals.
- Wi-Fi/Cell caches qualify only if fresh and new in effect; active scans/refresh remain finite,
  direct-demand-only and cancellation-safe. No sleep/wake radio cadence promise without measurements.
- Export/import evidence integrity does not establish provider authenticity or grant local policy,
  consent, session, service-run, manifest, provider, WAL, writer, or rollout authority.
- Corrections/deletion/retention/import/restore must remain source-specific, atomic, fenced,
  bounded, idempotent, and no-resurrection. Deletion markers precede payload cascade.
- All released v27 facts must survive additive migration/reopen. v28 is presumed never shipped;
  if that premise is false, stop extending `MIGRATION_27_28` and request a migration strategy.

## Required startup procedure

1. Read the package README and receipt. Determine whether your local `dev/v10` includes the new
   checkpoint or is only the old remote `0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2`.
   No push happened during this handover. A remote clone alone does not contain the new work.
2. Use read-only `git status --short --branch`, `git rev-parse HEAD`, `git log -15 --oneline
   --decorate`, and `git worktree list --porcelain`. Do NOT run diff-check. Investigate divergence;
   never reset/discard/overwrite. Use the bundle/source snapshot only by the documented safe procedure.
3. Read all applicable AGENTS.md. Inspect settings.gradle.kts, relevant module build files,
   build-logic, call sites, resources, manifests, and existing test sources before behavior edits.
   JDK21 drives Gradle; application bytecode17; compile/target SDK37; min26. Module graph and
   versions come from settings.gradle.kts and gradle/libs.versions.toml.
4. Read complete documents in order (do not merely skim headings). Except the explicitly named
   root design and dated handover files, these are under `docs/tracking-infrastructure/`:
   VISION_AND_SCOPE.md; docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md;
   ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md; CONTINUATION_HANDOVER.md; EXECUTION_PLAN.md;
   DECISIONS.md; IMPLEMENTATION_STATUS.md; VERIFICATION_MATRIX.md; ROLLOUT_RUNBOOK.md;
   IMPLEMENTATION_TODO.md; this handover's CURRENT_STATE_AND_REMAINING_WORK.md and ASSEMBLY_MANIFEST.md,
   then the generated branch inventory. Older documents are chronological evidence, not a new phase.
5. The 2026-09-15 handover notice supersedes older chronological checkpoint/head/publication and
   no-integration passages, not the essential product/safety rules. Repository evidence wins over
   either prompt or old docs. Some conservative broad TODOs remain unchecked despite partial slices;
   inspect their recorded commits and call sites before rebuilding anything.
6. Create a detailed active goal/TODO ledger for the whole effort if the product supports it.
   Record branch/commit/files/dependencies/next bounded slice/review status/deferred commands after
   every chunk. Do not mark the whole mission complete at an implementation-only checkpoint.
7. Reconcile assembled shared API/Room/UI seams by static source inspection, addressing concrete
   merge defects with authored regression sources. Do not start a validation batch yet.
8. Continue remaining source-local implementation in dependency order. Do not start speculative
   horizontal frameworks. Finish imported ownership/read/reexport/delete/retention/actions, shared
   truthful product consumers and runtime convergence. Ask the user for genuinely unresolved
   product/privacy choices; do not ask routine choices already constrained by code/design.

## What already exists — do not restart it

The transferred checkpoint contains the broad safety spine, manual/session Steps and portable
Steps import/read/deletion/day repair/numeric consumers/action wiring, automatic Steps contracts
with separate Activity control, default-off Ambient Steps capability/lifecycle/storage/partitioned
reads/maintenance/export primitives, and thin dormant Pressure/Activity/Wi-Fi/Cell source-native
facts/admission/projection/read/maintenance/export/import primitives. Protected Location qualified
observation/WAL/provenance work is included without authorizing a writer replacement.

Recent closure slices include imported Activity retention with one-lineage bounded payload
compaction and both real retention-worker invocations; portable Wi-Fi import; portable Cell import
and static-review closure; shared Pressure/Activity product UI union; additive shared Room assembly;
and preservation of imported Steps origin in coordinated recent/live/detail presentation.
See the source-specific map and original branch histories for exact boundaries. None of these
statements means all applicable consumers/actions/runtime/default-off ambient products are finished.

## Working conventions and closure criteria

Use one primary owner for overlapping files. Independent bounded source work can use separate
clean worktrees; shared AppDatabase/migration/history/worker/UI integration is serialized. Delegated
agents inherit the implementation-only directive and never push/activate. Use static independent
review proportionally at storage/lifecycle/product gates, not endless rounds or model agreement as proof.

Commit coherent chunks separately as adsamcik <adsamcik@users.noreply.github.com>, staging exact
reviewed paths. Inspect staged source without executing deferred gates. Do not run commit hooks
that silently launch validation in this phase. Preserve concurrent changes. Update status,
decisions, verification matrix, and TODO with exact authored/unvalidated evidence, not invented outcomes.

Protected root and two older frozen importer/awards drafts remain unaccepted. The package preserves
their patch/untracked evidence in a quarantine. Never apply/stage/merge those wholesale; do not
confuse drafts with reviewed source. Other nontracking worktrees and private configuration are out of scope.

When every planned production/test-source piece exists and unresolved decisions are resolved or
contained, freeze one convergence input and explicitly announce the phase switch. Then run the
full deferred compilation/schema/focused/module/ciUnitTest/ciCheck batch, fix failures, and repeat
gates on exact final HEAD. Use one representative device, distinguish provider/process-death/reboot/
FGS/battery/UI evidence from host results, and measure real tier differences before activation.
Final integration/readiness, publication, and candidate activation are distinct approvals.

End each work session at a precise committed checkpoint, with next work and blockers durable.
Do not claim success merely because everything is committed or because the prior agent stopped.
