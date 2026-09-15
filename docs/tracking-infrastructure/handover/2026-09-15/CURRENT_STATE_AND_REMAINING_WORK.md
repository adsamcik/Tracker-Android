# Current state and remaining implementation — 2026-09-15

## Scope-change handover - finish only what was already started

TI-D262 supersedes the earlier instruction to continue into every next lane. Keep the entire
remaining effort in `IMPLEMENTATION_TODO.md`, but do not begin an unstarted successor. Each
current owner may finish only the artifact already under implementation/review, its directly
necessary correction and matching test-source/static-review closure. Preserve all dirty work.
After closing that artifact, remain idle until a specific bounded request. The parent owns
dependency tracking and authorized local integration of closed scopes.

This documentation leaf had one started artifact: the five-ledger checkpoint
`3dd1ff004a34beb339539c4961704919d781371c`. It is committed and the user independently pushed
it. Its owned paths are:

- `docs\tracking-infrastructure\IMPLEMENTATION_STATUS.md`
- `docs\tracking-infrastructure\IMPLEMENTATION_TODO.md`
- `docs\tracking-infrastructure\DECISIONS.md`
- `docs\tracking-infrastructure\VERIFICATION_MATRIX.md`
- `docs\tracking-infrastructure\handover\2026-09-15\CURRENT_STATE_AND_REMAINING_WORK.md`

It has no production/test public interface, no source-code blocker and no successor lane. This
amendment records the scope freeze and complete backlog interpretation; after its clean local
commit this leaf is idle. The user's push does not authorize any agent push.

Current implementation disposition:

- **Closed local:** selected detail through `9c545380d6`, reviewed/rebased `cda6aff87c`, merged
  at `5ed7d1c5db`. Keep actions, radio detail and Location proof planned.
- **Finish only:** Cell `9d73fe4bb4`; close its frozen five-fix plus range/selected-delete review
  and necessary correction/tests. Do not start unimplemented range enumeration, retention, erase
  or files.
- **Finish only:** Wi-Fi `484c7d293f`; close its seven blockers and necessary tests. Preserve any
  range/selected-delete edits already present at the boundary, but treat any unstarted remainder,
  retention, erase, files and shared UI as planned.
- **Finish only:** Activity `483e8b59b7` final compatibility-fence review; Pressure files
  `f8ca404069` review; Purpose `3db3d5a955` containment/atomic-publication correction; Location
  `31c02288a8` seven-fix/real-chain closure; Ambient Steps `c8b96e328e` two-review closure; Runtime
  `410d26e28c` existing six-blocker/P0 correction; and passive radios `306e9070d2` active review.
  Do not extend any of them into the named future integrations in the older sections below.
- **Finish dirty work only:** preserve and close the roughly 24 Pressure-maintenance correction
  files based on `660e8c9ee8`, including the bounded parent additions, under the original owner.
- **Finish shared work only:** schema checkpoint `1f372e7a` is bounded to committed Ambient Steps
  `c8` plus Location `31c` full-clear/DDL/owner-WAL assembly; file checkpoint `3dc4c112` is bounded
  to Pressure `f8` plus Activity `287`/`483` and confirmed analogous Steps fixes; history checkpoint
  `7a88653c` is bounded to the already-started five-source recent/live/selected union.
- **Held:** original Ambient import `876ca17547`, schema input `3baf9e144` and file input
  `8e1a01df` remain preserved evidence. Do not revive or expand them.
- **Planned handover backlog:** every other unchecked stable ID, including future source retention/
  erase/files/actions, Purpose publisher/callback integration, new Location/runtime wiring,
  passive-radio 18-entity parent assembly, Location proof and structural day/range/UI history,
  Dashboard/Timeline/Calendar/Trips/widgets, ambient Location, non-Steps achievement qualification,
  final assembly accounting and the frozen convergence batch.

Scope expansion is frozen, but the assembly input is not frozen. **IMPLEMENTATION_ONLY** still
forbids compilation, tests, Gradle, lint/Detekt, schema generation/drift, diff-check, device/UI,
battery, CI, push, activation, release and rollout.

## Current direct-source checkpoint

This section supersedes older local-head and ownership statements below. Local `dev/v10` is
`5ed7d1c5dbf1b6437ea8bdec65dd2380e7812c95`. The user's request to complete the entire
implementation is still in progress: no assembly input is frozen and no execution, push,
provider/writer activation or readiness claim is authorized. All new work is
**IMPLEMENTED_UNVALIDATED**. Per TI-D261, the user has requested minimal parent coding; designated
direct source leaves own every current production/test-source implementation and focused static
review. The parent coordinates exclusive ownership, dependencies, preservation and authorized
local merges only. Blocked source deltas stay out of `dev/v10`.

### Newly integrated bounded leaf

Selected-detail source `69571dbf6a2263cafc2807020f42622adbcbdbf3` plus corrections
`d2aac46d7f6e247c1637f6f5e12a4f6d4f2c3136` and
`9c545380d65042ee6f85dc0d300353cc6640d50d` closed all four reported static findings.
Rebased tip `cda6aff87cb87b9642bde8f352458d31a8cd57fd` preserved the same 13
`feature\statistics` paths and was locally merged at the current root. The reviewed input is
retained at `refs/remotes/handover/reviewed/ti-source-detail-product-20260915`; the worktree is
retained for follow-up. This closes only existing Activity/Steps/Pressure/imported-Steps and
truthful no-Location detail presentation. Real actions, radio variants and Location-fact proof
remain. TI-B327 is authored/static evidence, not execution proof.

### Active source inputs and blockers

- **Cell:** `9d73fe4bb404e3e2daa1f5dece56686ca7476496` contains the `97a490` range,
  `6620` selected delete, `26757` owner fix, `d238` facade, `371fff5` adversarial fixes and
  `9d73` tests. Frozen `.worktrees\ti-cell-product-review-20260915` is reviewing the five
  original fixes plus new range/delete work. Parent assembly needs two deleted-receipt/identity
  entities and indexes. Deletion-range enumeration, retention, source erase and files remain.
- **Wi-Fi:** first product/opaque/shared-facade artifact
  `484c7d293f2b2a82bab68f24c167fc179ede8248` is not accepted. Seven review blockers remain:
  reverse physical membership, exact fact-WAL carrier, value-free-only intent, member recency
  and imported-oldest cutoff, deleted/ready classification, semantic reexport retry and query
  amplification. The owner is continuing range/selected deletion.
- **Activity:** `483e8b59b790c5970d11b947d76d82746308bdac` follows the `9d32`, `251d`,
  `981` and `287` lexical-helper corrections. Earlier findings are closed; the last compatibility
  fence is in focused review at the frozen `483` tree. Full UI/settings actions remain.
- **Pressure files:** `f8ca404069c28a60c541084a52a987569f317aa8` follows `512` format-prefix
  and `30e` lexical/pre-copy corrections and adds tests; focused review is running. Activity uses
  the exact Pressure-owned `PortableJsonTokenLimitInputStream`. Held shared input
  `8e1a01dfcd34cf1c83cf51cce6c3ce33cce0b763` has structurally closed picker/warning wiring but
  does not yet contain all corrected source commits.
- **Purpose:** focused review of `3db3d5a955405e34f51ea06bee2108dea4e68c54` closed
  default/matrix/shape findings but found that enqueue-retry failure can prevent cleanup/kill
  collection and that freshness is not atomically consumed with publication. The owner is adding
  dedicated containment and atomic publication; real publisher/callback integration still follows.
- **Location:** `31c02288a8d8fc18ffab246dbca7121d1f680307` includes `529` owner receipts,
  `fe4` actual wall time/legacy handling and `31c` curation/offline endpoints. Seven fixes and a
  real-chain source are in focused review. Parent assembly still needs nullable WAL
  `received_wall_time_ms`, a permanent Location owner row, source catalog/manifest/DI/router/sink/
  recovery and shared query. Database version stays 28; no schema was generated.
- **Ambient Steps:** product `c8b96e328e749ac157fa299429bc7d975d04b10e` follows `9aee`
  union/numeric and `4ad0`/`c8` base fixes. Distinct reviews cover base authority/fences/full clear
  (`217bf6d2`) and product day/numeric (`bac200cd`). Original import `876ca17547` remains blocked.
  `SourceFence` requires `deletion_completed`, `completed_at_ms`, `reopened_consent_epoch` and
  `reopened_at_ms`. Full clear must prepare old-to-new fences, publish the next revision/epoch,
  then delete payload while preserving footprints. Parent nine-table DDL must be updated.
- **Runtime:** `410d26e28c648b5a35a17c024f2c1f8b63339314` (`75d` + `fa619` + `410d`)
  remains blocked by six findings plus a P0: Activity null hardware/unobservable late callbacks;
  lost exact retirement acknowledgement/synthetic completion; rollback-owner-3 manifest reject;
  nonfunctional rearm/hard-coded generations; Pressure demand TOCTOU; non-exact `AlreadyApplied`;
  and pending/unfenced legacy Pressure writes. Constants cannot substitute for generation-2/
  owner-4 writer and reader support plus repeated cycles. No runtime input is accepted.
- **Pressure maintenance:** base `660e8c9ee864bf8dd9b2d3068f06a4c0e6791f67` is still blocked.
  Preserve the roughly 24 dirty correction files. The parent added uncommitted pure-Pressure
  format relocation to `core:modelAndroid` plus `stats:api` aliases, common lineage authentication
  in `core:base` plus a `stats:data` facade, full semantic full-clear authority and two tests.
  All completion ownership is back with the original Pressure owner; the parent is not continuing
  the code. The owner must finish, commit and review it. Five new tables, entry-deletion columns
  and the legacy writer barrier token/settlement proof remain.
- **Passive ambient radios:** `306e9070d296a9b91f96177e9948090754779917` (`fd110` + `ea70` +
  `306`) is the first 18-table/shared-controller/purpose-aware passive-fact/maintenance/transfer
  and versioned-report artifact. It is under adversarial review at `174a326e` and is not accepted.
  Parent assembly still needs all 18 entities/DDL, DI, registry, lane, recovery, query and UI.
  Known payload-coverage limits remain explicit.

### Shared direct-leaf assembly

- Existing held schema input `3baf9e144bc35accac88a7a636e791fa5056b42d` is being replaced in
  its worktree by ownership checkpoint `1f372e7a`, merging only committed Ambient Steps `c8` and
  Location `31c` plus correct full-clear, DDL and owner-WAL wiring. Never copy dirty Pressure or
  blindly drop source fences.
- Existing held file input `8e1a01dfcd34cf1c83cf51cce6c3ce33cce0b763` is being updated at
  `3dc4c112` from committed Pressure `f8` and Activity `287`/`483`; apply analogous Steps lexical/
  permanent-format corrections only if confirmed and preserve the reviewed picker/privacy wiring.
- New `codex/ti-shared-history-union-20260915` ownership checkpoint `7a88653c` is implementing a
  five-source recent/live/selected union through Cell, Wi-Fi and Activity producers. Location
  proof, structural day/range and UI remain follow-up. Never manufacture qualification.

### Still required before freeze

Finish truthful source day/today/range/facade products; Dashboard, Timeline, Calendar, Trips and
widgets; exact source actions and source-erase settings; remaining radio/ambient file paths;
correct Ambient Steps execution authority rather than its current wrong session-lane gate;
ambient Location; the legacy Pressure writer fence; non-Steps achievement qualification; and
complete stable-ID `CONT`/`ASSEMBLY` accounting. Leaf review or merge reports do not close those
broad items. Only after every production and test-source slice exists may one convergence input be
frozen and the deferred execution/fix batch begin.

## Checkpoint meaning

Superseding receiving-wave authority: the user has now requested parallel worktree completion
and parallel adversarial static reviews for TODO-HANDOVER-20260915-007/008/009, followed by
local `dev/v10` integration (TI-D257). The committed Location/ledger baseline `13ca528ccb` has
been fast-forwarded locally. These local operations do not lift IMPLEMENTATION_ONLY or authorize
any execution gate, push or activation; all work remains IMPLEMENTED_UNVALIDATED.

Slice 007 is locally merged at `8767472d62` after adversarial fixture corrections and focused
static closure (reviewed `fc1e540f5b`, rebased `285deba80c`). The original reviewed tip remains
under `refs/remotes/handover/reviewed/ti-wifi-full-clear-20260915`. Slice 009 is locally merged
at `3b13656923` after valid unavailable-fixture correction and focused closure (reviewed
`771ce960b3`, rebased `a390b97645`); its input is retained under the matching
`refs/remotes/handover/reviewed/ti-factless-steps-history-20260915` ref.
Slice 008 is locally merged at `5fc5f91e1c` after the authority-ordering correction and focused
closure (reviewed `afa939b0fd`, rebased `0e99c246cc`); its input is retained under
`refs/remotes/handover/reviewed/ti-legacy-radio-retention-20260915`. All three requested slices
are implemented, statically reviewed and locally merged, still **IMPLEMENTED_UNVALIDATED**.
The clean task-owned worktrees/branches were removed; original handover refs, reviewed inputs,
unrelated worktrees and quarantine are preserved. No whole-assembly gate is complete.

Receiving continuation: the local package receipt and clean `dev/v10` match `29323cfab`.
The original 39 tracking refs have been preserved from the local bundle under
`refs/remotes/handover/codex/ti-*`. Source follow-up `57073947f8fa77021b75f5c16eff6bb15d78e0e5`
on `codex/ti-location-wal-bounds-20260915` adds the dormant Location payload-bound slice described
below and in TI-D256/TI-B319. It remains **IMPLEMENTED_UNVALIDATED** on its own source branch;
the original transfer receipt and local `dev/v10` are not advanced by this continuation.
All earlier transport statements below remain historical evidence, not current readiness.

Local `dev/v10` is being assembled for transfer at the user's request. This is an
**IMPLEMENTED_UNVALIDATED handover**, not a complete assembly gate or proven usable six-source
product. No test, compilation, Gradle, lint, Detekt, schema generation/drift, diff-check, device,
UI, battery, CI, release, activation, or push was performed during closure. Static review findings
and their authored fixes are source evidence only. Earlier passing gates apply only to their old
exact commits, not to this much larger assembled checkpoint.

The original visible chat history is not trustworthy. All original clean tracking source refs,
commit ancestry, source snapshot, durable designs, and excluded-draft evidence are retained in the
package. The automated assembly inventory records inclusion/equivalence/supersession/quarantine.

## Implemented boundaries to preserve

| Area | Existing boundary | Still not a completion claim |
| --- | --- | --- |
| Safety spine | SourcePolicy, purpose-aware demand, manifests, logical/physical binding, lifecycle, ingress/WAL, source-local fencing | All runtime callers and six provider/device gates |
| Session Steps | Exact reverse binding, qualified capture evidence, replacement grouping, dormant writer, read/list/detail, selected deletion, repair, numeric/effect audits, portable import/export/actions | Executed proof, all assembled consumer regressions, activation |
| Automatic Steps | Exact Steps capture + writerless/nonpersistent Activity CONTROL, durable fresh trigger/action/FGS acceptance and no-revival contracts | AUTO-005 control-only evidence fixed lifetime/shape, device proof |
| Ambient Steps | Default-off consent, HC-first capability precedence, separate owner/continuity lifecycle, bounded sessionless facts/cursors/gaps/zones, handoff/partitioned read, retention/deletion and portable export | Import/roundtrip, settings/permission/remediation, production product/numeric wiring and complete scenario |
| Pressure | Real acquisition/window semantics, session-only purpose, WAL-first materialization primitives, exact read/grouping/retention markers, selected local/imported deletion, portable import/read/reexport, contained shared UI | Imported retention/source erase, file/UI actions, day/Calendar/full product, runtime/automatic convergence |
| Location | Protected qualified observation/admission/WAL/provenance candidate primitives | Sole canonical path remains protected; shadow/cutover and full product/lifecycle proof |
| Activity | Purpose-separated captured bands, WAL admission/projection, bounded live/import history, export/import, exact imported deletion, bounded imported retention and real worker invocation | Source-wide erase, file/UI actions, runtime/shared product final convergence, control TTL |
| Wi-Fi | Privacy-minimized fresh/new-in-effect admission, captured facts/projection, bounded history/maintenance/export and portable import | Imported product/reexport/delete/retention/source erase, file/UI, shared surfaces/runtime/automatic/ambient |
| Cell | Privacy-minimized per-child freshness, captured facts/projection/history/maintenance/export and portable import | Imported product/reexport/delete/retention/source erase, live selected-session deletion, file/UI, shared surfaces/runtime/automatic/ambient |

Do not infer provider activation from classes, DI bindings, test fixtures, or this table.

## Dependency-ordered next work

### 1. Static assembly seam closure

Inspect additive Room entity/accessor/migration/full-clear unions and merged worker ordering;
reconcile source acquisition/purpose checks, public live/recent history variants, observer failure
semantics, exact imported Steps origin, and shared Activity/Pressure UI guards. Preserve test-source
coverage from both parents. Original source refs make any merge decision inspectable. Unknown
compilation/Hilt/schema/test defects remain deferred; do not guess that absence of conflict proves parity.

The protected Location `deliveryEvents` per-BLOB follow-up now has source implementation in
`57073947f8`: it reuses the newer Activity payload-preflight APIs, bounds selected and complete
delivery reads in SQL, and uses a payload-free sequence lookup. The 65,586-byte codec-derived
ceiling and actual cap-plus-one 256-unit guard have authored Room/adapter assertions, including
exact covering SELECT projections. No heap measurement, compilation or execution occurred;
this closes that implementation slice only, not all Location ingress/recovery/runtime bounds.

Receiving static review identified three concrete follow-ups, recorded as
TODO-HANDOVER-20260915-007/008/009: captured Wi-Fi full clear must remove coverage dependents
before self-FK-restricted aggregate owners; the legacy DataRetentionWorker must invoke captured
Cell/Wi-Fi retention before deferral/WAL pruning; and factless exact Steps-only source-aware
history needs intent-first replacements instead of disappearing after physical suppression.
The Location query-callback test builder retains the original in-memory factory setup.
These three bounded findings are now closed in the local receiving wave (TI-B323). The worker
review exposed an additional ordering defect, corrected by keeping `session_segment` until both
radio services accept retention; the active pipeline was already correctly ordered and unchanged.
Other broad assembly/runtime consumers remain open. Continue with the Wi-Fi imported product lane.

### 2. Wi-Fi imported product lane

1. Preserve independent full-lineage static acceptance of import `88c0d310b` with correction
   `315ac6a83`: global owner discovery includes cursorless immutable revisions and aggregate-owner
   references; all-source/all-purpose opaque fences are bounded and authenticated. Earlier review
   found concrete namespace holes; the closure authors regressions without executing them.
2. Typed imported evaluator and bounded live/imported history with exact ownership, complete
   correction/replacement membership, scopes/fences/current epoch/retention, and typed unavailable.
3. Authenticated latest-v1 reexport and export → import → read → reexport authored roundtrip.
4. Exact selected imported/coexisting deletion producer, complete hierarchy authentication,
   durable marker-before-cascade and authenticated replay/no-resurrection.
5. Uncertainty-safe imported retention with compact receipt/typed child-owner authority, retained
   value-free state, export omission and replay suppression; invoke real maintenance paths.
6. Wi-Fi source erase covering imported and local privacy authority while preserving other sources,
   CONTROL and unrelated WAL. Full collected-data clear is not a substitute for source erase.
7. Bounded file encoding/decoding, explicit import/export/deletion UI, provenance and typed errors.

### 3. Cell imported product lane

1. Preserve independent static importer acceptance through `f8dd5d6d1`: unclamped uncertainty
   lower-bound checks and exact known/weak quality-summary buckets apply to incoming and stored
   graphs. Rehashed stored corruption and boundary regressions are authored, not executed.
2. Reconcile sibling live product reads `79056b454` with final maintenance/export/import contracts,
   including source sequence zero, completeness, reciprocal owners, deletion and retention.
3. Typed imported evaluator/bounded live+import composition with truthful partial/unavailable/
   deleted states and exact-origin deduplication; latest reexport and authored roundtrip.
4. Exact selected imported deletion receipt and marker-before-cascade producer/replay. Existing
   stored entry/run tombstones honor deletion but do not themselves produce selected deletion.
5. Imported uncertainty-safe retention/compact authority and real invocation/source erase.
6. Exact live logical selected-session deletion; consent-revocation capture deletion is not this contract.
7. Identity-free Dashboard/History/Calendar/detail/live/list and source actions. No tower/SIM/local
   identity or unique/new tower product is inferred from counts or opaque imports.

Wi-Fi and Cell source-local readers/maintenance can be independent worktrees; shared public facade,
Room/migration and feature consumers have one serialized owner. Do not generalize tombstones/import
materializers preemptively because these source-specific contracts look similar.

### 4. Activity and Pressure maintenance/actions

- Activity: source-wide local+import erase invocation; explicit portable file/import/export and
  selected-deletion product actions; final shared product/runtime convergence. Retention workers
  now invoke imported truncation before deferral/physical pruning. Exactly empty dormant legacy
  captured store skips canonical pruning; any row in four captured stores or exact canonical owner
  invokes authentication. Do not restore the ordinary-retention permanent-retry bug.
- Activity imported retention pages headers, authenticates one bounded lineage at a time, compacts
  immediately, and retains complete typed child/scope authority. Never restore batch-of-four payload
  accumulation or receipt-only lost ownership. Statically reviewed, not heap/Room/runtime proven.
- Pressure: imported retention and explicit source-wide erase including imports; file/UI actions;
  repair/Calendar/full Today/Timeline product wiring and automatic direct capture+separate control.
  Local selected deletion, stored-zone repair and authenticated retention-loss reads already exist.
  Keep pressure window quality/gaps and no elevation claims; no default continuous ambient demand.

### 5. Ambient Steps completion

- Keep opportunistic default-off model and one HC-first provider; missing HC grant must not silently
  fall back, and capability failure fails closed. Local Recording fallback only for genuine unavailability.
- Complete authoritative ambient portable import/roundtrip without local provider/session authority.
- Wire settings, permission/consent/remediation and retention explanation to real lifecycle.
- Wire partitioned local/imported/session/between-session facts once into Today/Timeline/Calendar/day
  detail/numeric consumers; no additive overlapping providers or fabricated complete zero.
- Finish coherent scenario sources for handoff, DST/zone authority, gaps, revoke/delete/replay/import,
  process boundaries and exact provider/demand membership.

### 6. Protected Location and six-source runtime/product convergence

- Finish protected Location-only capture through the existing canonical writer, real passive/low/
  balanced/high/first-fix plans, exact durable provenance/replay/correction/retention/export/deletion,
  optional context reads and lifecycle/actions. No second writer. A shadow comparator/cutover is only
  justified by concrete replacement need and later measured evidence.
- Close every manual start caller, platform prerequisite decision, accepted demand/FGS action,
  runtime registration and source-local RECORDING → MATERIALIZED → QUERYABLE advancement.
- Complete automatic only-X with declared Activity CONTROL or truthful unavailable when no legal
  control exists; control-only trigger evidence cannot leak into captured products or effects.
- Complete approved default-off passive Location/Wi-Fi/Cell ambient with separate consent/purpose/
  retention/history/export/delete. Radio active attempts remain bounded direct-demand-only.
- Finish shared Today/Timeline/Calendar/detail/list/live/settings/export/import/deletion semantics;
  expose trustworthy facts from source proof, not Location-shaped `sample_count` assumptions.
- Reaudit numeric/effect consumers (goals/streaks/achievements/widgets/notifications) for any new
  ambient/imported source facts and corrections; preserve revision/identity idempotence and retraction.
- Complete exact source-wide erase/consent/key epoch/no-resurrection, source-isolated recovery,
  control retention and tests authored for all six. Avoid per-row query fan-out.

### 7. Frozen final validation batch — not authorized to start now

Complete all IMPLEMENTATION_TODO assembly items first, explicitly freeze exact inputs and lift the
phase stop. Then compile affected main/test/AndroidTest/Hilt/release sources, reconcile/generate
v28 schema, run focused/module tests, populated v27 migration/reopen, Detekt/lint/schema drift,
ciUnitTest and ciCheck --continue, fix and repeat on exact final HEAD. Older failed assertions
remain validation debt, not instructions to resume execution during implementation.

Representative manual only-X device proof must show exact capture/demand registration; fresh
source-qualified evidence RECORDING; one writer MATERIALIZED; production history QUERYABLE;
truthful visible state; listener retirement after stop. Separately cover automatic controls,
approved ambient, process-death/reboot/FGS/deletion/rollback proportionally. One device is not an
OEM matrix or measured wake reliability. Measure genuine tier quality/latency/writes/battery and
shadow/rollback safety before any writer/provider activation.

## Unresolved product choices / stop conditions

- AUTO-005: exact fixed lifetime and retained shape for control-only Activity WAL/outbox evidence.
  Do not invent a retention duration or allow persistent product leakage.
- Final per-source/per-purpose retention/privacy copy and final cross-midnight presentation/counting.
- Ambient Location beyond passive points; user-selectable paid radio modes; keyed identity lifecycle
  if unique/new products are ever approved; calibrated Pressure vertical estimates. Contain these
  rather than making them mandatory dependencies.
- Has v28 actually shipped? Design says no. Existing development v28 databases predate added tables;
  require explicit safe post-freeze handling, not a silently destructive migration or developer wipe.
- Dirty/divergent checkout, missing bundle commits, unresolved live owner authority, or overlapping
  concurrent files: inspect and report; never reset/overwrite or activate to make fixtures pass.

## Durable accounting

IMPLEMENTATION_TODO.md is the exhaustive stable-ID ledger. Its broad unchecked items are
conservative composition tasks, not proof that all named primitives are absent. The latest status,
decisions and verification entries identify partial completion. Update those ledgers after each
coherent commit with exact paths and deferred commands. New handover notices supersede earlier
dated heads. Keep both old evidence and new limitations visible.
