# Adaptive five-level collection: data and evidence review

Date: 2026-09-26. Author: Astra Data. Static source review against main `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`, plus the latest `ADAPTIVE_COLLECTION_AND_SESSIONS.md`. This is a proposal inside IMPLEMENTATION_ONLY. No production/test changes, execution, schema generation, activation, integration or commits were performed. Exact implementation choices below require approval.

## 1. Direction and recommendation

The latest user requests a central adaptive system using multiple hints, exactly five power/detail choices, and a live-session preference for quality. The preceding direction also settles retrospective sessions as organization of existing observations. It does not make a retrospective interval an acquisition request or change the original purpose/consent of its contents.

Recommend one bounded evaluator in the existing collection-control path, one source-specific plan factory/resolver, and existing independent durable source writers. Extend existing seams rather than build a universal sensor framework, second scheduler, observation warehouse, or per-hint journal. The five choices describe permitted collection budgets, not five guarantees of delivered accuracy or five evidence-trust standards.

Product's final proposed labels are 1 Power first, 2 Favor power, 3 Balanced, 4 Favor detail, 5 Detail first. Existing Saver/Balanced/Responsive factory profiles are useful anchors for 1/3/5; 2/4 and all resulting parameters remain unvalidated design proposals. The concrete cadence and freshness proposal is in `RUNTIME_ADAPTIVE_FIVE_LEVELS.md` sections 5–6 and belongs in the same approval package, rather than another deferred design round. Level 1 does not mean off. Level 5 does not override consent, source caps, permissions, hardware or platform constraints.

Proposed live target is `max(saved level, 4)` for participating sources, subject to their permitted caps. This number is an approval choice. Treat this quality preference as a **soft target**, separate from genuinely mandatory source floors. When an upgrade is unavailable, continue a valid authorized baseline and explain the limited improvement. Do not turn every preferred upgrade into a hard floor that disables an otherwise useful source. Ending focus restores baseline, including baseline-only sources that continue during a session.

## 2. Existing seams and concrete gaps

Paths below are relative to the main repository, not the planning worktree. References are static evidence, not runtime proof.

| Current code | Consequence for the design |
| --- | --- |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionController.kt:38–76` | Existing controller accepts Activity, Steps, Location, Cell and Pressure, but requires an active logical session and matching service run. General baseline adaptation needs a qualified non-session authority scope; simply removing the scope check would be unsafe. |
| Same file `:107–182` | Activity confidence, positive Steps, accuracy-qualified Location speed/displacement already contribute; Cell change and Pressure change only extend recent motion. Preserve that distinction instead of treating every source as an independent motion vote. Wi-Fi is not currently a controller input. |
| Same file `:204–246` | Stationary confirmation has spacing and dwell, but its final stationary condition has no maximum age for the latest confirmation. Stale stillness can survive after other motion windows expire. Add freshness expiry to unknown. |
| `source/control/CollectionMotionPolicy.kt:51–69` | Compatibility `unrestricted()` reports MOVING/100 with NO_ACTIVE_SESSION; it is not a measured confidence or a valid UI movement claim. Awaiting evidence is separately UNKNOWN. Keep policy permissiveness separate from observed state. |
| `source/ingress/DurableSourceEventSink.kt:86–123,125–145,183` | Hints are fed after durable admission and before canonical drain. Deduplication exists, but its stream key is only source kind and latest elapsed time. Preserve admission fencing; extend freshness/dedup scope rather than claim canonical product acceptance has already happened. |
| `source/coordinator/SourcePlanResolver.kt:53–158,171–179` | Already combines demands, hard prerequisites, ceiling/floor checks and accepted reductions. A floor above a policy ceiling fails closed. This remains correct for mandatory floors; live quality preference needs soft-target handling. |
| Same file `:141–154` | Motion reductions are attributed to POWER_SAVER even when power saver is false. Central status needs a truthful separate motion/budget reason. |
| `source/coordinator/SemanticAcquisitionPlanFactory.kt:22–123` | Already creates six source-specific plans with different acquisition, batching and freshness settings. Reuse it; do not impose one common frequency knob on fundamentally different mechanisms. |
| `source/model/SourceDemand.kt:3–23`; `source/model/SourcePlan.kt:13–154` | Existing source requirements, adaptation permission, concrete plans, runtime identity, apply status and degraded reasons cover most of the required contract. A new global framework is unnecessary. |
| `source/coordinator/RoomSourcePlanStore.kt:28–96,100–160,168–185` | Desired plans are immutable and checked by identity/checksum; applied plans preserve exact accepted payload and runtime registration identity. Never substitute desired plans when applied reconstruction fails. These records are the durable base for explanation/recovery. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/policy/TrackingPolicyManager.kt:90,180`; `stats/engine/src/commonMain/kotlin/com/adsamcik/tracker/stats/engine/policy/DefaultPolicyEscalationEngine.kt` | Older policy/demand and escalation paths coexist with collection motion. Runtime must converge their actuator authority; do not leave two independent controllers changing acquisition. Older tiers are not the five user choices. |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/policy/BatteryAwarePolicy.kt:54–101` | Battery rules and charging exceptions exist; unknown battery currently falls back to 100. New input/status must preserve unknown rather than pretend a full battery. Existing thresholds are evidence of current code, not newly approved defaults. |

## 3. Complete bounded input inventory

The evaluator should distinguish authority, constraints, measurements and preferences. A missing input stays unknown; no source must be added just to make adaptation function.

| Input family | Eligible inputs and use | Limits |
| --- | --- | --- |
| User intent | Selected sources, baseline/live participation, saved five-level choice, per-source override/cap, custom settings, live start/stop/pause and explicit foreground consumer demand | Strongest expression of intent; never infer consent from a level or a session label. Retrospective edits have no demand. |
| Strong motion evidence | Fresh qualified Activity classifications/transitions; positive hardware Steps delta; qualified Location speed/displacement and its uncertainty | Respect observation time and counter/reset/accuracy semantics. A received batch of old Steps is not proof of movement now. A speed value without sufficient quality is not an exact movement claim. |
| Supporting context | Already permitted Cell changes; already permitted Pressure change; optional already available radio-change hint only after a concrete bounded contract | Existing Cell/Pressure behavior only corroborates recent motion. Wi-Fi absence/stability is not stillness; Cell reselection can occur while stationary; Pressure does not establish elevation. No new identifier collection or durable fingerprints. |
| Device resources | Battery level/charging, power saver, thermal state, doze and relevant lifecycle/foreground state | Preserve unknown. A screen state is not movement. Exact policy thresholds and override priority require approval; no claimed battery savings without later measurement. |
| Capability and legal state | Hardware/provider availability, permission/precision, background legality, foreground-service viability, registration state, known throttling/backoff and topology changes | Hard constraints dominate preference. A unavailable source remains locally limited while eligible siblings continue. No repeated forbidden requests. |
| Delivery health | Last fresh accepted observation, observed age/uncertainty, callback-to-receipt delay, cached/batched/throttled flags, known coverage gaps, owner restart/backoff, bounded ingress/storage pressure | A timeout, missing callback, disk failure or delayed writer is not stillness. Distinguish provider delivery failure from canonical persistence backlog. |
| Temporary focus | A live session's soft quality target and existing explicitly scoped live consumers | End/release restores baseline. UI browsing, history queries, map enrichment and exports do not enroll sources. |

Do not add accelerometer acquisition, remote weather/context, learned places, remote AI, telemetry or a network service for this design. Additional hardware hints would be separately approved scope. Existing enabled observations may inform adaptation only when their purpose permits that use; source selection is not universal cross-purpose permission.

## 4. Hint trust and feedback-loop prevention

Use a bounded transient cache per source and current authority scope. Minimum qualification is source, purpose, consent/policy authority, relevant runtime/registration identity, boot domain, observed interval, receipt age, quality flags and original event lineage. Reuse existing envelope identities; do not duplicate payloads into a new durable hint table.

1. Admit native/control evidence through the existing guards. Durable ingress admission may support a current control hint; it is not proof that a canonical history fact has been committed or that a coverage interval is complete.
2. Evaluate freshness from observed time in a compatible boot/elapsed domain, with separate delivery age. A newly delivered old batch does not acquire a fresh motion timestamp. Wall-clock corrections must not extend a stale hint's life.
3. Deduplicate original lineage across retries, projections, dual-purpose routing and multiple views. Derived motion state, imported facts, replayed recovery facts and the evaluator's own output do not become fresh independent corroboration. Two projections of one event are one hint, not two votes.
4. Reset or invalidate affected hints on incompatible boot/runtime/registration transitions and applicable consent/privacy barriers. Do not carry a last-elapsed watermark across unrelated streams and reject new valid evidence accidentally. Retained history can remain readable even after a transient hint expires.
5. Stillness requires fresh positive evidence, a bounded dwell and no stronger contradictory evidence. Expiry yields UNKNOWN. Contradictory or unreliable evidence lowers confidence; it does not trigger an unbounded oscillation.
6. Strong qualified motion can raise an authorized source's target promptly. Weak hints can support continuity, but cannot independently upgrade uncertain evidence into a confident movement classification. Existing heuristic percentages must not be presented as calibrated probabilities.
7. Unknown has a source-specific useful fallback within the selected budget. Maintain permitted wake/sentinel opportunities where supported; do not secretly enable Activity or Location to recover another source's motion knowledge.
8. Coalesce equivalent effective plans and add hysteresis/backoff to meaningful changes. Reason-only changes should not restart hardware: `CollectionMotionPolicy.kt:74–89` already separates acquisition profile from explanatory state.

For identity-free native radio products, adaptation should use already available qualified aggregate/change information, not invent stored AP/tower uniqueness. If an existing internal control hint depends on identifiers, its authority/retention must remain separate and explicit. A new durable fingerprint design is unnecessary here.

## 5. Information value without fabricated precision

Recommend a deterministic ordering rather than a universal numeric information score:

- First preserve authorized continuity, required counters and genuinely mandatory minima.
- Prefer closing an actual eligible coverage gap or obtaining a fresher useful observation over repeating known stale/cached evidence.
- Increase permitted effort when motion or live focus makes additional detail useful; reduce redundant effort when fresh evidence supports stillness.
- Respect decreasing yield: repeated poor fixes, duplicate radio deliveries or throttled attempts should invoke bounded backoff, not endless escalation.
- Preserve valid new observations through existing writers even when they do not change the adaptation decision. Low novelty is not authority to discard measurement history.

A small rolling in-memory view of useful arrivals, age, known gaps, retries and plan changes is sufficient initially. Callback count, attempted scans and active registration duration are not interchangeable with successful observations, complete coverage or measured energy. No new durable analytics subsystem is warranted.

Source-specific useful output remains distinct: Location accuracy and spacing/gaps; Steps continuity and boundary uncertainty; Activity observation confidence/coverage; Pressure window coverage and units; Wi-Fi observations/scan evidence; Cell readings/coverage. Repeated child observations are not unique networks or towers. Sparse samples do not prove inactivity, and no observations is not zero.

## 6. Desired, applied and observed must remain separate

| Layer | Authoritative meaning | Must not imply |
| --- | --- | --- |
| Saved policy and desired plan | User choice, applicable profile version, temporary demand and requested source setup | Successful provider registration or recorded data |
| Applied source state | Exact acknowledged effective plan and registration generation, including limitation/rollback | Delivered accuracy, persisted observations or complete coverage |
| Durable admission / materialization state | Accepted ingress and whether canonical processing remains pending or failed | That every admitted payload is a qualified product fact |
| Qualified product evidence | Retained accepted facts, timestamps, uncertainty and explicit coverage/gap semantics | Complete history beyond its supported scope or the quality promised by today's setting |

Product can collapse these into a small understandable state surface (ready/waiting, recording with evidence time, limited with reason), with detail on demand. Exact copy is Product-owned and pending. A callback or an applied plan alone cannot substantiate recorded history. Do not falsely mark an otherwise functioning source stopped just because it delivered no new event recently; event-driven and continuous sources need different freshness expectations.

Reuse desired/applied records and current degraded reasons. Add only the missing immutable `BalanceLevel`/profile-version attribution and bounded reason vocabulary needed to explain a decision. If a small decision summary is necessary, record it on a meaningful plan transition using existing lifecycle retention, not on every hint. It needs no precise coordinates, radio identities, raw payloads or new long-term movement trail. No new retention duration is implicitly approved.

Restart reconciles the exact stored policy/desired/applied state with actual ownership; it does not replay old motion into fresh confidence. A failed replacement leaves a correctly identified surviving baseline where safe, or a truthful blocked state. An applied-state database transaction does not mean hardware registration and storage were physically atomic.

## 7. Storage, privacy and migration

### Five levels are new metadata, not renumbered old QoS

`data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/SourceCollectionSettings.kt:3–12` and `SourcePolicy.kt:10–19` persist OFF=0, BATTERY_SAVER=1, BALANCED=2, RESPONSIVE=3. `RoomSourcePolicyRepository.kt:766` directly maps frequency stable codes to QoS, and `AuthoritativeTrackingParamsRepository.kt:897` maps them back. Existing floors also inspect QoS codes in `SourceAcquisitionFloor.kt:259–269`.

**Recommendation:** introduce explicit five-level balance plus immutable profile-version metadata in authoritative settings/plan attribution; keep existing QoS identifiers compatible. Never reinterpret persisted 2 as the new level 2 or 3 as the new level 3. Do not modify historical encoded plans, checksums or manifests to fit new labels.

The concrete minimal placement proposed for the same approval package is:

| Existing record | Additive metadata | Compatibility and ownership |
| --- | --- | --- |
| `source_policy` / `SourcePolicyEntity` (`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/SourcePolicyEntities.kt:50`) | Nullable `overall_balance_level`, nullable `source_balance_override`, nullable `balance_profile_version` | Overall level/profile version repeat consistently across the six immutable rows of a revision; this avoids a new policy-header table. All-null means legacy/custom vector. An adopted profile requires known version and overall level 1–5, with override null or 1–5. Validate complete-revision consistency in `SourcePolicySnapshot`/repository before publishing authority. Existing fields still carry source eligibility, custom settings and consent. |
| `acquisition_plan_revision` / `AcquisitionPlanRevisionEntity` (`SourceSessionEntities.kt:1007`) | Nullable `balance_profile_version` | Immutable attribution for newly generated desired plans; null preserves legacy meaning. Keep `source_policy_revision` link. Validate profile agreement with linked policy, and include metadata in immutable revision collision checks. |
| `source_desired_plan` / `SourceDesiredPlanEntity` (`SourceSessionEntities.kt:1020`) | Nullable `requested_balance_level`, `focus_requested` default false | Store source target before feasibility reductions, including a level-5 source under level-5 live focus. Its saved baseline/override comes from linked immutable policy. Legacy rows remain null/false. Exact effective cadence is still the applied payload, never inferred from this target. |

This intentionally needs no metadata in each observation, no new hint rows, and no additional policy authority pointer. Update `SourcePolicy`/snapshot, `TrackingParamsState`'s authoritative projection, the Room policy repository, factory/domain plan attribution and `RoomSourcePlanStore` read/write/collision checks. If settings are mirrored to DataStore, Room remains authoritative and existing reconciliation must preserve the new fields atomically with policy publication; do not create an independent slider authority.

**Codec rule:** existing `SourcePlanCodec` payloads encode concrete source plans, so these descriptive columns do not require rewriting or bumping their payload format. Preserve old floor codec bytes and QoS mappings as well. The soft target belongs in intent/resolution metadata, not a rewritten mandatory floor. An actual new serialized plan/floor field would require an explicit new version with retained old readers; this proposal does not need one merely to show five choices. Unknown profile metadata must not silently become Balanced: refuse new profile synthesis, expose the incompatibility, and retain an independently authenticated accepted plan only where current authority still permits it. Historical qualified records remain interpretable from their existing provenance.

Do not silently expand the existing payload checksum domain to include these columns. Preserve payload checksum calculation exactly; validate additive metadata with immutable row equality/collision checks in the same plan transaction. Repeated overall policy metadata is published only with the complete six-row revision and current authority pointer transaction, never six independently editable settings.

**New-plan versus old-history contract:** `SourceDemandContractFactory.forQos` in `source/model/SourceAcquisitionFloor.kt:159–275` supports the old three enabled QoS values. Add an explicit `forProfile(profileVersion, level, purpose)` path for new adopted-profile demand contracts, including each source's maximum provider age, delivery target and actual hard floor; never feed new level numbers into `forQos`. Existing manifests keep their serialized exact contracts. Historical reconstruction, including `source/wifi/WifiCapturedFactMaintenance.kt:1411,2067,3011`, must choose the original profile-attributed path or legacy `forQos` using the original immutable policy, not today's preference. Retain exact writer, lifecycle, manifest and privacy authentication.

A new cadence alone does not require inventing a new observation lane generation: main `WifiWalQualificationAdapter.kt:923`, `CellWalQualificationAdapter.kt:617` and `LocationWalQualificationAdapter.kt:793` historical-shape predicates validate ranges/modes, not a fixed list of old cadence tuples. Keep those structural checks and authenticate the exact new plan/contract; do not broaden them into an arbitrary-payload acceptance shortcut. A genuinely changed observation identity, payload grammar, purpose or lane semantic still needs its appropriate explicit version. Runtime's profile table must specify intermediate-level provider-age/contract values rather than silently deriving them from an old QoS ordinal. This is part of the same approval envelope, not an excuse to rewrite historical facts.

Portable files that currently do not serialize policy/profile metadata keep their format. Do not claim profile settings or organization round trips until their separately proposed versioned export contract includes them. A raw database archive retains the additive columns under its existing explicitly sensitive archive scope.

Legacy source vectors/custom Location intervals remain exact until explicit adoption of a new profile. A default level 3 for a new installation is a proposed default, not permission to flatten existing different source settings into a global value. Imported older settings cannot silently enable sources, grant consent or change baseline/live participation.

### Keep acquisition authority and organization separate

`SourcePolicy.kt:69–77` ties `enabled` to capture eligibility/QoS; background consent is independent. A unified source row cannot use that field as an all-purpose collection-on switch. Preserve each existing purpose's consent epochs, effective boundaries and retention/deletion fences. One physical owner may satisfy compatible demands while source-local writers enforce their distinct destinations.

The new organization metadata already proposed in `ADAPTIVE_COLLECTION_AND_SESSIONS.md` remains a separate change. A retrospective session neither changes original purpose nor asserts a past high-detail profile. Overlap must not duplicate global totals. Batched Steps crossing an interval cannot be prorated into invented exact counts. Dynamic views remain subject to qualified range, correction, deletion and retention.

Source disable/Android permission loss governs future collection and does not automatically erase lawful retained history. Actual consent/privacy revocation, deletion and retention apply their established authority barriers. Drop affected transient hints promptly when their continued use is no longer authorized; do not convert that into a blanket hidden-history rule.

CONTROL observations remain separate from ordinary source-product exports. The previously proposed explicitly sensitive raw operational database archive has a different disclosure contract; do not silently filter its bytes or call it a complete restore feature. Five-level metadata is descriptive, not new export authority. Organization and profile metadata need explicit portable-version support before claiming a round trip.

Use the existing final convergence migration approach for unshipped schema changes, with migration/reopen test source. Preserve zero-based source sequence validity, immutable generations, imported-vs-native authority and current writer receipts. No fact-table merge or new universal membership table is required by central adaptation.

## 8. Small implementation slices and deferred acceptance

1. **Policy contract (Runtime/Data):** approve five-level/profile-version serialization, source overrides/caps and soft focus target. Preserve old codes/custom vectors and purpose masks. Author migration and compatibility test source.
2. **Single decision authority (Runtime):** extend existing controller with scoped fresh hint cache; integrate legacy escalation as input or compatibility presentation; converge factory/resolver ownership. Correct motion reason attribution and unknown-battery handling.
3. **Truthful outcome (Data/Product):** reuse desired/applied state, qualified source freshness/coverage and pending materialization. Add only necessary bounded explanations. Keep optional context reads demand-free and source-local limitations visible.
4. **Complete source behavior (existing owners):** reconcile baseline/live focus joining, source caps, safe replacement and source-specific backoff with existing writers/continuity/privacy work. A central evaluator does not complete unfinished source runtimes.
5. **Freeze/converge later:** include all production and test-source slices in one frozen input. No validation starts from this report, and no battery/reliability claim follows from authored tests.

Existing test source provides useful extension points: `CollectionMotionControllerTest.kt:23–91` covers initial motion, stillness dwell, step wake, tunnel/Cell support and wrong-run evidence; `SourcePlanResolverTest.kt:42–455` covers constraints, floors, disabled sources and stationary adaptation. Author missing cases without executing them now:

- Stale stillness expires; a delayed batch does not imply movement now; backward wall-clock jump and reboot cannot extend freshness.
- Duplicate lineage, dual-purpose projection, imported/replayed history and derived motion never create independent corroboration or a self-amplifying loop.
- Contradictory sources and weak Cell/Pressure changes alone remain uncertain; absent callbacks/empty radio/cache-only receipts do not assert stillness or completeness.
- Each sole-source configuration works or states its exact limitation without enabling another source; loss of one source preserves siblings.
- Live focus cannot meet its preferred level but valid baseline continues; true mandatory-floor failures remain explicit. Ending focus restores baseline, and retrospective edits create no demand.
- Failed apply/rollback, pending writer work and startup reconciliation never report desired settings as actual recorded quality.
- New five-level metadata round-trips without reinterpreting old codes, custom source vectors, encoded plans, consent epochs or disabled-source masks.
- Deletion/revocation invalidates affected hints and range views; retained permitted historical records stay usable after capture off.
- Poor-yield repeated attempts back off within budget; equivalent plans/reason-only changes do not cause registration churn or per-hint persistent growth.

## 9. Pending choices and peer agreement

Runtime and Data agree on separate BalanceLevel/profile-version metadata, ephemeral hints, existing desired/applied storage, and no full-payload decision journal. Product agrees on one source list, distinct saved/live/actual states, historic achieved coverage, and no retrospective demand. Runtime owns concrete cadence/freshness/hysteresis numbers; Product owns final level labels and compact status wording.

The single approval package should include the concrete Runtime five-level profiles/defaults/overrides and freshness constants, the proposed live target of at least 4, explicit active baseline escalation beyond previously approved passive Location scope, the included hint purposes, and the concrete additive metadata placement above. These are proposals awaiting approval, not deferred requests to invent another architecture later. Existing unresolved CONTROL retention and organization/export decisions remain unresolved; this request does not choose their numeric values or file contracts.

Most important invariants: **one decision authority; source-local durable writing; no implicit enrollment; scoped fresh hints; no feedback-loop corroboration; desired is not applied is not observed; an unavailable soft improvement preserves a valid baseline; historical authority is immutable.**
