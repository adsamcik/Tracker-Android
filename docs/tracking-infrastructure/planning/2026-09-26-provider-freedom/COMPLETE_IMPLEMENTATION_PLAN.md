# One adaptive tracker: implementation proposal

2026-09-26. **Revised after the adversarial simplicity review. Design approval remains pending.**

This is the current entry point and replaces the previous approval packet. Current detailed contracts are Runtime, Data, Product, Assembly and the five-level profile table linked below. Other planning reports are historical source evidence, not additional mechanisms to implement. This pass changes the proposal only; it neither implements providers nor opens deferred validation.

**2026-09-27 decision update:** [DECISION_CLOSURE.md](DECISION_CLOSURE.md) records approved 48-hour CONTROL retention and optional default-off Wi-Fi/Cell notification hosting. The user requires a clean backup of all data; the earlier raw-archive-only/no-restore proposal is superseded. Selected-data export and full-backup restore behavior await focused confirmation. These decisions do not approve the entire remaining implementation package.

## 1. The product

Users choose what to collect. One engine runs selected supported sources, adapts their effort, and stores qualified observations. A live session requests more detail and supplies an organizational interval. Ending it preserves separately enabled background collection. A session added later is an annotation over existing data.

**Choices + live focus + qualified hints + real constraints → existing factory/resolver → shared source owners → qualified stored facts → existing product readers.**

Source independence, sessionless background consent, five power/detail preferences, live quality preference, retrospective organization and optional enrichment are settled. Location/Steps/Wi-Fi/Cell must work independently where supported. Existing Activity/Pressure and all six-source assembly obligations remain required. There is no new remote AI, network service or telemetry.

The adversarial review found real gaps after the earlier review. The design below removes unnecessary machinery and names the additional correctness work.

## 2. Five responsibilities, using existing components

| Responsibility | Owner | What changes |
| --- | --- | --- |
| Intent and lifecycle | Existing source policy/settings and acquisition coordinator | Explicit source/purpose choices; existing live lifecycle owns start/end; annotations do not create another lifecycle |
| Adaptive decision | Existing CollectionMotionController, SemanticAcquisitionPlanFactory, SourcePlanResolver | One scoped motion input and explicit source-specific rules; remove competing legacy acquisition actuation |
| Physical acquisition | Existing shared controllers, provider owners and TrackerService | Host lifetime follows all lawful demands; one physical owner; independent temporary feature leases |
| Durable evidence | Existing ingress, source writers, count domains and receipts | Correct per-purpose qualification, zero sequence, generation compatibility, retained proof and recovery |
| Presentation and files | Existing qualified repositories/UI/import jobs | Bounded source-aware history; one annotation table; source adapters and a small bundle using current import receipts |

Keep desired versus applied plans, immutable consent/policy, exact registration identity, retirement debt and canonical writer proof: they distinguish real failure states. Do not collapse them into a mutable current-state object.

**Removed from the proposal:** qualitative candidate optimizer, Pareto comparison/fingerprint sorting, second savings-dwell clock, per-level radio validity tiers, second organization OPEN/CLOSED lifecycle, foreign organization synchronization/tombstones, export-time identity writes, bundle snapshot protocol and durable multi-page UI snapshot machinery.

## 3. Configuration and adaptive behavior

Exactly five preferences: **Power first / Favor power / Balanced / Favor detail / Detail first**. New setup defaults to Balanced3. Participating live sources prefer max(saved level,4); level5 stays5. Baseline-only sources retain their own preference. Existing custom limits/consent remain until explicit adoption; historical four-tier codes retain their meaning.

Use one source row with participation/availability summary and one Details editor. Details contains live participation, supported background consent, applicable host option, one inherited/five-level override, and collapsed Custom limits. This replaces the old OFF-plus-three-frequency and separate ambient lists. Off is participation, not another quality level. No hidden helper sources.

### Cost is a source rule, not an optimizer

- Preserve cheap Steps continuity and useful passive radio callbacks.
- Optional Wi-Fi/Cell requests require direct-live L4/L5, unmet desired freshness, moving/unknown motion, no outstanding work, cooldown and remaining finite budget. No repeated scan loop or budget renewal through reconfiguration.
- Baseline active Location uses its saved profile, capped at3 for qualified stationary/moderate power pressure and1 for strong pressure, subject to genuine hard floors and a viable wake/sentinel. Live retains4+ within real constraints.
- Activity capture keeps classifications; Pressure keeps its continuous hPa contract. Neither gains a new background product or cheaper substitute.
- Reuse a compatible owner; count shared host/hardware once. An exhausted scan budget is not recurring scan cost; a Steps checkpoint is not a sensor cost.
- Existing motion dwell, battery hysteresis and source cooldowns provide stability. BatteryImpactEstimator stays an honest explanatory preview; its unmeasured categories do not actuate hardware.

Moderate pressure is battery<=20% or Saver; strong<=10%; recover at25%/15%. Charging removes battery pressure only. Unknown battery is unknown. Actual OS/thermal/storage/consent constraints win. No blanket source-wide downgrade.

### Separate evidence validity from desired detail

For newly adopted profiles, hard useful contracts are fixed across levels: Location capture age/delivery120s with LOW_POWER minimum; background Location age120s with PASSIVE minimum and no delivery guarantee; classified Activity age/delivery120s and confidence65, direct Steps age/report300s, Pressure age60s with existing sample/report bounds, radio item age10min and fixed new-profile Wi-Fi dedup10min. System Steps/CONTROL keep their distinct contracts. Timing targets stay in the [five-level table](FIVE_LEVEL_ADAPTIVE_POLICY.md).

This prevents a two-second level5 hard age from defeating every legitimate fallback. Live display, motion hints and game freshness remain separately qualified. Legacy constraints are never silently relaxed. Update runtime admission as well as factory mapping.

Hints use actual authorized observation time, lineage, uncertainty and boot: Activity/control transitions, positive Steps, accepted Location movement, and relevant delivery/value/health feedback. Radio novelty/Pressure do not become standalone motion classifiers. Missing callbacks are not stillness; delayed batches can be useful history without being a timely wake signal. No added helper sensor or persistent hint warehouse.

Full rules, capability matrix and API evidence: [Runtime contract](RUNTIME_GAP_CLOSURE.md).

## 4. Shared acquisition without purpose interference

One source becoming stricter must not discard another source's valid evidence. Current radio physical filtering uses the strictest shared age and newest authorization boundary, so live focus can suppress continuing background observations. This is required corrective work.

Keep one immutable minimized radio WAL snapshot and one delivery identity/unit. Reuse the existing captured per-item qualifier for admission and ambient projection. Retain only items lawful for at least one destination; each destination computes its own eligible subset, intervals and partial coverage. Authenticate historical per-item authority across a live join without lending new consent backward. Mirror the rule in maintenance/replay; preserve old-generation behavior. Do not change min to max without destination checks or duplicate payloads per purpose.

Location keeps one protected canonical sample and authenticated common receipt with independent lawful purpose associations. One callback serving live/background does not duplicate a sample. All readers, erasure and retention respect those associations. Feature-only points are not persisted.

Required proofs include live-stale/background-valid and mixed-age snapshots; live join/leave; before-consent/after-revoke observations; deletion/correction/replay; one-purpose removal preserving the other; and source failure isolation.

## 5. Lifetimes and controls

Finish passive background Location using a system-rearmable PendingIntent and the same ingress/writer. Keep existing active callback backends. Strict acknowledged retirement precedes replacement; report possible gaps rather than promise zero-gap handoff.

Offer two explicit options, **off by default**:

- Active background Location through the lawful shared service, with ongoing notification and required access.
- **Keep running with notification** for passive background Wi-Fi/Cell. This improves listener lifetime without a live session, hidden Location capture or active scan budget. Opportunistic listening remains default.

Both use the same host. A host cannot guarantee provider cadence, arbitrary restart, delivery after user stop or API capabilities the device lacks. No-GMS automatic triggering remains unsupported; valid manual sources continue. Steps background keeps the supported system continuity provider, not a new always-on direct-sensor service.

Distinguish **hasLiveCapture** from **hasCollectionHost** in existing API/state. Widgets, shortcuts, notifications, automation and locks must use the correct predicate/command. End session releases capture/focus only; baseline-only notification has background controls rather than phantom session actions. Internal quiescence remains separate for maintenance/erase.

Add **Turn off tracking** through existing controls: end capture, disable background purposes and automatic starts, preserve manual source preferences/quality and stored history. Independently requested game/My-location features retain their own controls; do not claim tracker-only Off stops them. No global resumable Pause subsystem.

Off/Edit/End always remain available despite missing hardware or permission. A permission result refreshes capability; it never blindly enables capture or revives an intent changed while the dialog was open.

Handle observed Android user-requested exit separately from confirmed force-stop in the existing startup gate. Prevent autonomous rearm and new collection admission, including surviving passive deliveries/import workers, until explicit tracker Start/Resume or background re-enablement. Game/My-location actions authorize only their temporary feature lease and cannot clear this tracker gate. Preserve cleanup, historical reads and admitted-fact materialization. Resume from fresh authority with an honest gap; do not backfill a suppressed interval using old leases or a newly received system-Steps import covering the suppressed period. One bounded handled-exit/acknowledgement receipt is sufficient; no new recovery framework.

## 6. Sessions, history and metrics

Use existing logical acquisition and legacy recording adapters for live/recorded groups. Add **one annotation table** for names/labels, closed retrospective intervals, edited bounds, optional recording adapter key and bounded imported source selectors. Live/ended/uncertain state comes from acquisition, not another organization enum. Only adapter-backed removal needs hidden suppression; free annotations delete outright. Export does not write metadata. Apply recording-override suppression before range filtering/limiting so moved annotations cannot expose the old recording again.

Use half-open UTC intervals, saved display zone and explicit DST gap/fold handling. Closed groups may overlap; facts/totals are not copied or double-counted. End live acquisition before editing time/removing its view. Remove session removes grouping; erase observations is a separately reauthenticated source operation with actual whole-entry scope.

History/calendar query the requested range. Use existing PagingSource/Flow, one global consumed-frontier key, bounded complete-group qualification and ordinary mutation invalidation registered for every newly consulted annotation, ambient, purpose/proof and deletion table. No persistent per-stream/high-water protocol. Preserve scroll anchor during live appends; private invalidated payload must disappear. Frozen file selection belongs to the export job.

All six sources get truthful summaries/detail/actions. Steps crossing boundaries remain unallocated unless attribution is exact; Pressure/Activity windows retain original bounds. Recording time, Session duration and detected Activity are different concepts. No pressure-derived elevation, inferred movement duration or XP for annotations. Preserve existing qualified effects and exact correction/delete retraction.

Exact schema/read/action/retention contracts: [Data contract](DATA_GAP_CLOSURE.md). Surface/action/copy/accessibility inventory: [Product contract](PRODUCT_GAP_CLOSURE.md).

## 7. Maps and existing features

Radio context is a qualified observed device point, never an inferred router/tower position. Wi-Fi uses exact/qualified30s bracket and nearest observed endpoint; Cell uses preceding/equal within60s. Preserve reported accuracy/uncertainty rather than a new100m cutoff. Native capture/background joins require their actual compatible authority; an annotation grants none.

Read bounded paired sets and invalidate from either source. No extra Location acquisition for history/maps. Missing geography leaves primary source history useful. Existing maps/heatmaps/exploration use actual Location evidence.

Explicit My location/games use temporary shared nonpersisting Location leases with actual freshness and feature host lifetime. Generic tracker service activity is not evidence that a Location feed exists. Keep independent End/Stop semantics and existing game behavior.

## 8. Files and privacy

| Operation | Minimal complete contract |
| --- | --- |
| Existing source files | Finish missing radio adapters; preserve source versions, identity, correction and partial-prefix import semantics |
| JSON3/GPX/KML | Preserve declared geographic scope and actual imported quality; GPX breaks unproven routes, passive points can be waypoints, untimed KML is not assigned invented time |
| Exact Location point file | One narrow codec, imported archive receipt and point identity association for exact timing/purpose/gap roundtrip and overlapping-file dedup; JSON3 already supports sessionless geography and remains available |
| Session bundle | Small annotation manifest plus unchanged typed source files; no foreign organization revision/hash/snapshot protocol |
| Bundle import | Existing content-hash job and child receipts; recover selectors from bounded typed-file decoding on retry; final annotation write commits with its success receipt under the original job data epoch and current retention. Partial child failures keep current partial/retry behavior |
| Reimport | Same-file retry uses existing receipts; another file creates a new local annotation and never updates local edits. Source receipts deduplicate observations |
| Selection | Disclose whole-entry/day expansion; never invent clipped Steps or aggregate windows. Imported annotation selects only its listed source entries |
| Schedules | No LastSession fallback to all history/older entries. Keep compatible Location increments; pause misleading JSON incremental and sensitive raw archives for review |
| Full backup | User requires all-data backup. Proposed complete durable-data/settings/files snapshot and recoverable restore in DECISION_CLOSURE D4; restore choice pending. Existing database-only archives are legacy evidence, not a complete backup |
| Cancel/clear | Existing output cleanup with actual residual/pending status, exact app-owned paths, durable watermark only after success; external copies cannot be recalled |

CONTROL is ineligible after the approved 48h TTL, with active-store rows pruned at the next permitted cleanup. Trigger freshness remains much shorter. This is not forensic erasure: SQLite free pages/WAL, older database files and external archives can contain residue. Backup disclosure must remain accurate after cleanup. No secure-delete/VACUUM/encryption redesign is added.

## 9. Implementation, alternatives and proof

All original D1–D7/P1–P8 obligations remain accounted for in the current contracts; the unnecessary mechanisms above are replaced, not silently deferred. Required passive Location, Activity/Pressure, effects, privacy and source-only paths remain in scope. Rejected alternatives include a second policy engine, source-wide battery downgrades, replicated organization metadata, mass backfill, generic all-source format replacement and a new pause framework.

CONTROL retention is settled at 48h; the optional visible Wi-Fi/Cell host is approved off by default. Selected-data file behavior and full-backup restore behavior remain in DECISION_CLOSURE. The stated initial profiles and other unapproved detailed choices remain proposals; do not treat these focused replies as blanket approval.

[Assembly contract](ASSEMBLY_GAP_CLOSURE.md) maps all 25 prerequisites and exact candidate dispositions. The [input receipt](PLAN_CLOSURE_INPUTS.json) records seven checkouts and preserved source hashes at the prior checkpoint; it is not an assembly freeze. [Review history](PLAN_REVIEW.md) records the initial review and the later adversarial corrections.

Implementation order:

1. Fix physical-versus-purpose radio admission and preserve existing immutable contracts.
2. Separate stable useful floors from soft timing; implement explicit rules inside the current factory/resolver, with one motion owner.
3. Finish shared host/feature ownership, correct session controls, permission races and user-stop recovery.
4. Reconcile retained source proof/schema; implement the annotation/read adapter, bounded history and all existing product/effect/file paths.
5. Author meaningful tests with each coherent IMPLEMENTED_UNVALIDATED slice. Complete every global prerequisite, then freeze one exact convergence input.
6. Only then run the deferred compilation/Hilt/Room/schema/migration, ciUnitTest and ciCheck batch, fix actual failures and rerun required gates.
7. Obtain the separately required bounded activation/device authority, collect real provider/lifecycle/UI/energy evidence, and use a fresh independent reviewer on the final assembled code/diff/results before local integration.

Preserve released v27 and unknown development databases. If distributed v28 evidence exists, use an explicit next-version migration; never reset unknown data. No push/deployment or provider activation is authorized by this plan.

## 10. What remains

The adversarial review improved the proposal; it did not prove the implementation. Required work remains: design approval, source/test authorship and candidate reconciliation, actual migration/build/device evidence, and final independent code review. Exact external schema distribution and OEM delivery/cost cannot be established by static planning.

There is no claim of universal edge-case completeness. Every identified finding from this pass has a concrete disposition in the revised contracts, and the later proof gates remain explicit.
