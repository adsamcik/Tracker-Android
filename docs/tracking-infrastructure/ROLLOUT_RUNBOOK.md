# Tracking Infrastructure Rollout Runbook

Last updated: 2026-08-22

External rollout, publishing, deployment, and remote configuration remain unauthorized. This runbook defines local evidence and the future authorization boundary.

## Current containment

- Checked-out baseline: `068ebe052`.
- The local schema is now v28. Its policy authority row is inert until verified legacy settings activate a complete six-source revision; no external rollout is authorized.
- A v27 APK/database downgrade is not a rollback path. Any rollback build must understand v28 and continue enforcing Room policy/consent safety state.
- Current persisted rollout state force-converges to all-source `EVENT_CANONICAL`; it is not sufficient as the target rollback system.
- No new canonical writer may default active.
- Location remains on its existing sole canonical projection until a recorded shadow/cutover decision.
- Wi-Fi and Cell cadence is opportunistic; no wake-reliable claim is allowed.
- Ambient persistence is a confirmed product requirement, but every source's rollout defaults off until its consent, minimization, retention, export, deletion, query, and explanation contract passes.
- Legacy enabled automatic mode currently migrates to an Activity `CONTROL` grant derived from that explicit preference; it never grants captured Activity. Authorized callbacks now enter observation-first durability without session attribution, but the bounded control-retention cleanup and no-product/export proof remain rollout gates. Mode changes append control consent epochs. Optional Steps corroboration remains a separate unresolved control decision.
- A missing/unavailable/corrupt policy authority is presented as unavailable and blocks source controls; it is not represented as an all-disabled user choice.
- Append-only manifests/intents and source-runtime desired actions exist locally, but the Android service/FGS call still precedes them and no durable action reconciler exists. These records are evidence for integration work, not rollout authorization.
- Production automatic v2 entry fails closed because no durable trigger envelope reaches the coordinator. Do not weaken that containment or report automatic-mode success.
- Durable broker demands, physical-only registration generations, immutable observed-time authorization revisions, and callback admission fences exist locally. They do not yet make one process-wide reconciler the sole physical owner for all six providers; every `broker_v2:<source>` remains off/blocked.
- Activity automatic projection refuses capture-only generations, and app/control observations before their boot-aware effective boundary are rejected. The later automatic outbox action still lacks the complete current-epoch/boot/expiry CAS and must remain contained.
- `RETIRING` demands may retain the physical provider only through the callback drain barrier, but they are excluded from new product authorization at the exact retirement boundary; final physical retirement is half-open and rejects exact/post-boundary callbacks.
- The unused generic Phase 3 ledger/attribution/activation/receipt APIs and 12 tables were removed from unreleased v28. New materialization must begin with typed source provenance, stable fact identity, idempotent write/recompute, correction/deletion, and production-query evidence.
- Fresh R4 and the current R1 data/migration review blocked schema freeze on physical/authorization coupling, authorization-homogeneous batches, logical correction ranges, scoped deletion/import fences and destination ownership. The first coupling slice is now locally mitigated; the remaining batch, correction, deletion/import, owner-fence and realistic device-migration gates still prohibit schema freeze or source activation.
- Migrated `LEGACY_UNKNOWN` WAL rows remain capture-ineligible and must produce partial/unavailable historical completeness rather than fabricated facts.
- Fresh pre-wiring R1b is complete. WAL byte authentication and raw poison quarantine are mitigated locally; source materializer and day-product wiring remain prohibited until typed correction/day repair is atomic and uncertainty-aware, force-stop retires ghost demands, scoped deletion exists, and actual legacy destinations are fenced.
- Existing tracking-settings authority/error presentation is the only allowed Phase 1 production UI safety exception; it exposes fail-closed policy state and is not evidence of source history, materialization, or queryability.
- Fresh adaptive-collections R3 was `BLOCK_RESCOPE`; TI-D056–TI-D069 defined its correction. Fresh R4 is now complete and TI-D070–TI-D076 integrate every new blocker/high. No source activation is authorized until the accepted implementation and device gates pass.

## Scope-proportionality containment

Three fresh product, architecture, and delivery adversaries concluded `BLOCK / RESCOPE`. Until TI-D047/TI-D048 and the current v28 compatibility boundary are resolved:

- Freeze new generic all-source materializer/day-history expansion. Ambient persistence is source-specific product behavior: implement it only where the source provides useful, privacy-appropriate history under an honest acquisition promise, as independent verticals using existing retained foundations until each production contract proves additional schema is necessary.
- v28 never shipped. The 12 unused generic Phase 3 tables have therefore been removed and `MIGRATION_27_28`/`28.json` regenerated in place; exact populated migration execution is still mandatory.
- Automatic upgrade intent semantics are implemented locally: the existing enabled automatic preference grants Activity `CONTROL` only. The observation-first admission seam persists its authorized callbacks without session capture attribution; bounded operational retention, device migration, export exclusion, and end-to-end no-control-product evidence remain release gates.
- Database merge import uses an explicit 24-table user-fact allowlist. It never merges policy/consent, demands/registrations, nonterminal lifecycle state, leases/actions, rollout state, cursors/failures, or deletion fences from another install. Validated whole-database replacement is a separate empty-target restore protocol.
- Keep every eligible `AMBIENT_PRODUCT` rollout default off until its source-specific setting/consent, acquisition limits, minimization, retention, export, deletion/key rotation, between-session query, and explanation pass. Ambient Location is passive/opportunistic; Wi-Fi consumes successful passive scan-result broadcasts without default active scans; Cell consumes timestamp-qualified change callbacks without forced refresh/wake promises; cache reads are bootstrap/payload retrieval rather than acquisition; Activity capture is independent from bounded control evidence; Steps selects one system continuity adapter and uses the direct counter only for live direct demand. Continuous ambient Pressure remains off pending explicit approval.
- Block radio rollout until mixed-age item tests prove stale, pre-effective, future and missing-time identity/signal payload is absent from WAL, typed facts, history, export and enrichment. Rejected inputs may emit only bounded non-identifying reason counters; fresh unchanged/empty completions may emit compact coverage headers.
- Block radio rollout until exact repeated cache reads prove zero database/product effect. A fresh provider callback with advanced observed times may extend coverage without duplicating unchanged children; a reread with identical provider times may not.
- Treat sole-source capture as a release invariant. Manual only-X registers and captures only X. Automatic only-X captures only X plus exactly the declared control demands; control-only events never become history. Every X must reach its own qualified `RECORDING`, durable typed fact, production day/session query and truthful UI without Location or another captured source.
- Treat provider activation as the battery boundary and source-qualified durable admission as mandatory. Enrichment cannot create a demand. Every valid provider delivery under current physical/authorization generations gets the source-native durable representation defined by TI-D056; capture/ambient eligibility controls typed product materialization, while control-only evidence uses a bounded operational retention class and stays out of source history. Bulk transactions and conflated asynchronous per-source drains are required before performance rollout.
- Treat quality improvement as a rollout objective, not a hope. Every source has a protected quality vector and target under TI-D060. Shadow comparison must show a quality gain within the selected acquisition ceiling or materially lower cost at equivalent quality; a candidate that is both worse and no more efficient cannot activate.
- Preserve the existing Location canonical writer. Prove Steps first using the smallest idempotent fact/recompute path; reintroduce generic activation/receipt machinery only if a concrete non-recomputable destination proves it is necessary.
- Repair existing Dashboard/Calendar/History truth states before starting a Days-first journal. Full hero/ribbon/ambient/explanation UI is not a tracking-safety gate without a separately approved product epic.
- Use only runnable evidence for the current release: host invariant/source-contract tests, exact Android migration/recovery execution, and a small representative physical-provider smoke. No production cohort, p95/SLO, OEM-distribution, or remote-canary claim is permitted without an approved evidence channel.
- Stop mandatory adversarial rounds while the blocking fact is missing implementation or device capacity. Start a fresh round at an actual migration, writer-cutover, or release boundary.

R2 confirmed that v28 never shipped. The migration/schema has been regenerated in place with the 12 unused generic Phase 3 tables and duplicate lifecycle lease removed; the remaining shared coordinator lease is boot/process-aware and monotonic. No v29 compatibility shell was introduced. Merge-import isolation, automatic-control upgrade semantics, payload authentication, raw quarantine, and the compact state/recovery comparison are implemented locally. The first rollout candidate remains Ambient Steps after exact populated v27→v28 execution and the Health Connect/Recording capability-and-overlap spike pass.

Ambient Steps uses one selected system continuity provider: prefer Health Connect on-device Steps on API 34 plus SDK Extension 20 when permission and current-device-origin checks pass; otherwise evaluate the accountless Recording API. The direct hardware counter remains live-session evidence. Overlapping intervals are canonicalized, never added. Coverage gaps, delayed imports, provider switches and resets produce explicit `PARTIAL`/`UNAVAILABLE`, never zero or assumed completeness. Its minimum product surface is the existing Today, Timeline, Calendar and selected-day flow with an outside-session Steps summary; the full Days-first redesign remains backlog.

## Required rollout controls

| Control | Scope | Default before evidence | Rollback behavior | Status |
| --- | --- | --- | --- | --- |
| `policy_authority_v28` | policy snapshot/consent safety state | always-on after v28 opens; bootstrap is fail-closed | Room revision/consent epochs remain authoritative; a schema-capable adapter may change execution behavior but cannot lower or bypass safety state | implemented locally; device migration/rollback rehearsal pending |
| `legacy_automatic_control_gate` | pre-broker Activity and optional Steps automatic control registrations | fail closed unless independently granted by effective `CONTROL` epochs | revocation/unavailability removes legacy automatic control use; manual session state is not implicitly stopped | implemented locally; full broker/reconciliation and device evidence pending |
| `policy_manifest_v2` | immutable session/effective manifest creation | off until TI-110 | stop creating new manifests only after active sessions are safely reconciled; retain existing immutable records | local schema/coordinator path implemented; executable flag and full requested-source contract missing |
| `lifecycle_v2` | desired actions/reconciler/state evidence | off | stop applying new v2 actions; reconcile safe stops | source-runtime actions implemented locally; blocked on Android service/FGS actions, CAS, recovery reconciler, and rollback flag |
| `broker_v2:<source>` | purpose demand/provider ownership | off per source | remove v2 demands; restore one proven owner only | authorization/generation foundation local across source runtimes and Activity arbitration; blocked on accepted-before-retire replacement, global provider reconciler, durable flag, ambient/control continuity, automatic trigger, and device matrix |
| `source_shadow:<source>` | optional diff-only typed source projector/query | off | stop the shadow cursor; no canonical mutation | add only where a legacy destination exists and parity must be measured |
| `source_owner:<source>` | source-specific canonical destination owner | legacy owner until the source gate passes | stop the candidate, preserve facts/WAL, and restore only the last proven sole owner | no generic activation/receipt platform; each destination proves uniqueness, stable identity and idempotent typed writes |
| `tracking_history_v2` | one production history facade and compatible source readers | source-by-source dual read | retain compatible readers and surface truthful retained-unavailable state; never hide v2-only facts | not implemented |
| `days_journal_v2` | optional future Days-first product | off | restore existing navigation only if every retained fact remains discoverable/explainable/exportable/deletable | backlog; not a source-safety prerequisite |
| `ambient:<source>` | app-scoped acquisition/materialization/product | off | technical rollback removes demands/stops writes and preserves data; consent revocation/deletion is a separate authorized tombstone/key-rotation operation | not implemented |

Flags may be represented by a typed local rollout repository rather than literal string keys. The contract is per-source/per-writer control with durable revision, telemetry, and non-destructive rollback.

## Source order and independent gate

Default order: Steps, Pressure, Location, Activity, Wi-Fi, Cell. A change requires an entry in `DECISIONS.md` with code/device evidence.

For each source:

1. Contract and qualified-evidence tests pass.
2. Existing writer ownership is mapped; shadow/dual-read comparison passes where applicable.
3. Manual only-X passes through `TrackingHistoryRepository` and every applicable existing consumer.
4. Automatic only-X passes with exactly declared controls and no control leakage.
5. Allowed ambient behavior has explicit consent/minimization/retention/export/deletion/UI evidence, or is explicitly disabled.
6. Dynamic enable/disable, permission, and consent transitions pass.
7. Crash, process death, reboot, clock/zone, deletion, and upgrade pass.
8. Internal/reference-device evidence passes.
9. Canary thresholds and rollback rehearsal pass.

Portable export is part of step 5/8: it must include every released source from typed product facts/membership/completeness, exclude control-only evidence and raw radio identifiers, and remain distinct from empty-target whole-database restore. Merge import stages provenance-bearing base facts through `TrackingWriter`; it never inserts derived summaries directly or uses `INSERT OR IGNORE` as semantic conflict resolution.

No source inherits another source's gate decision.

## Source shadow and activation protocol

1. Name the actual typed destination and inventory every current mutation path. No candidate starts until the existing owner is known.
2. Give the candidate a source-scoped WAL cursor and stable typed fact/interval identities. Choose `FROM_RETAINED_FLOOR` or `FROM_NOW`; use genesis only when every earlier input is qualified and retained.
3. Reauthenticate WAL bytes before decode. Quarantine deterministic poison for that source, commit its completeness gap, and continue unrelated sources.
4. Run the candidate read-only or into a noncanonical shadow table only when a legacy path exists. Compare fact identity, totals, session/outside classification, uncertainty/completeness, deletion state, and every applicable `TrackingHistoryRepository` consumer.
5. Record the outgoing drain boundary and prove source-cursor coverage, bounded gaps, shadow parity, scoped deletion/no-resurrection and protected quality/energy results.
6. Under one writer transaction, advance the narrow persisted `(source,destination,ownerGeneration,owner)` fence. Every legacy/candidate destination mutation rechecks it in its own transaction, so a paused old commit fails after the switch; no generic activation subsystem is required.
7. Query Today, Timeline, Calendar, selected-day/detail, applicable map, export and deletion using the production facade.
8. Rehearse rollback by fencing the candidate and restoring the last proven owner without deleting committed source facts or lowering privacy epochs.

Location cannot enter step 8 until the protected existing writer has explicit parity evidence and an approved cutover decision.

## Telemetry and integrity checks

Required dimensions: source, mode, purpose, policy revision, consent epoch, manifest revision, physical registration generation, authorization revision, automation epoch, start origin, writer/materializer generation, schema capability, OS/device/OEM class, cohort, and completeness.

The repository is local-only and prohibits automatic remote analytics. Until an explicit evidence-channel decision is approved, cohort evidence means controlled device-lab runs and/or user-initiated redacted local diagnostic bundles. No production p95 or OEM distribution may be claimed from an unimplemented telemetry channel.

Initial calibration signals (not SLOs):

- Unexpected captured-source writes: 0
- Duplicate contribution balance: 0
- Stale automatic entries after recovery: 0
- Manual start acceptance: proposed p95 < 3 s
- Foreground materializer lag: proposed p95 < 60 s
- Deferred ambient day lag: proposed p95 < 15 min
- Day rebuild failure: proposed < 0.1% per day

Before each expansion, query and record:

- source destination owner uniqueness and cursor coverage;
- typed identity/correction balance and quarantine backlog by source;
- old consent-epoch attribution count;
- tombstoned fact resurrection count;
- stale nonterminal automatic entry count;
- oldest eligible desired action/projector/history lag;
- production history visibility by source and completeness;
- battery, wakeup, storage, and provider failure distribution.

Power evidence uses Power Profiler/system tracing and Macrobenchmark `PowerMetric`/on-device power rails where supported. Battery Historian is not an acceptance tool because Android no longer maintains it. Shared process-death, reboot, low-storage, saver/restriction and relevant API-boundary cases apply where the source mechanism can encounter them; unrelated hardware variants never block a source.

| Source | Minimum source-relevant physical boundaries |
| --- | --- |
| Location | passive/active handoff, permission and FGS/background-location boundaries, batching, Doze/restriction, representative OEM |
| Wi-Fi | scan-permission/Location-Service boundaries, passive broadcasts, throttled finite active budget, Doze/restriction, representative OEM |
| Cell | callback/refresh behavior, multi-SIM and partial slot failure, Doze/restriction, representative modem/OEM |
| Activity | supported Transition acceptance, Sampling comparison, GMS/no-GMS truthfulness, background-start origin and reboot |
| Steps | hardware counter/reset/reboot, selected Health Connect or Recording continuity provider, unavailable/old provider and overlap correction |
| Pressure | missing sensor, actual FIFO capacity, rate/report-latency modes, batching and high-rate write/CPU behavior |

Each source gate records its baseline fixture, hard quality floor, maximum provider-request/wakeup/write budget and pass/fail threshold before measurement. This is a risk-based local/device matrix, not a claim of fleet coverage.

## Rollback triggers

- any unexpected captured source write;
- any duplicate effective contribution or deletion resurrection;
- two active writer generations for one contract;
- stale automatic entry after recovery;
- old consent epoch receives new attribution;
- source is reported `ACTIVE` without accepted runtime evidence;
- product reports zero where the state is disabled, unavailable, OS-limited, materializing, partial, degraded, or failed;
- device cohort shows unbounded battery/wakeup/storage regression;
- production repository cannot reproduce the claimed source result.

Rollback stops new writes/actions for the affected generation, preserves committed canonical data and audit records, drains or quarantines bounded work, restores the last proven sole owner, and enqueues affected days for recomputation. Destructive cleanup requires separate authorization.

Technical rollback never implies consent revocation, fact tombstoning, identifier-key rotation, or data deletion. Those operations require a distinct user/privacy event and durable privacy epoch.

## Authorization boundary

The lead may implement and verify local flags, migrations, shadow modes, queries, and rollback logic,
and may create clear scoped local commits after each verified chunk. Enabling a remote/external
canary, publishing an APK, deploying, pushing, choosing final retention/legal copy, or performing
destructive migration/deletion requires separate explicit user authorization.
