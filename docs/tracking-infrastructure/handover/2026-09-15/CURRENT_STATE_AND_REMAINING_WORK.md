# Current state and remaining implementation — 2026-09-15

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
Slice 008 still requires correction/review closure: retain physical segment authority until
radio retention accepts. No whole-assembly gate is complete.

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
