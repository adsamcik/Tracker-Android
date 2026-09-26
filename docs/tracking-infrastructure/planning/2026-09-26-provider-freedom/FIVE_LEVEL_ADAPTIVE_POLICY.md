# One adaptive tracker: five-level implementation proposal

2026-09-26. **PROPOSAL AWAITING APPROVAL. PLANNING_ONLY within IMPLEMENTATION_ONLY.** Static tracing and coordinated Astra Runtime, Data and Product review; no production/test implementation or execution in this pass. Earlier implementation remains IMPLEMENTED_UNVALIDATED. This document supersedes conflicting adaptive-policy details in the earlier plans; it does not approve unresolved organization/export/CONTROL-retention choices or activate providers.

## 1. Product contract

Users choose what to collect and one usual power/detail balance. One central policy continuously reconciles their choices, fresh authorized hints, current needs and device constraints into independent per-source plans. Live manual and automatic sessions temporarily favor detail for their participating sources and organize observations. Sessions created afterwards only organize retained observations.

Exactly five preferences, with Off kept separate:

| Level | Label | Meaning |
| --- | --- | --- |
| 1 | Power first | Prefer the least costly useful collection; accept sparse or delayed observations. |
| 2 | Favor power | Keep power use low while responding sooner to useful changes. |
| 3 | Balanced | Balance useful detail, response and power; proposed default for new adaptive setup. |
| 4 | Favor detail | Prefer timely, detailed observations and continuity; proposed normal live-session target. |
| 5 | Detail first | Prioritize the most useful available detail within source and device limits. |

All five remain adaptive. They are neither five motion states nor measured battery bands. Changing the balance never changes selected sources, enrolls background collection or turns on a helper sensor. Adjacent levels may share a physical mechanism when a provider has discrete capabilities.

For each source, baseline is the overall choice or its existing advanced source override. A participating live session requests `max(baseline, 4)`; level5 remains5. This is a **soft quality target** within explicit limits. Ending the last applicable live demand restores baseline; a baseline-only source continues at baseline even during a session. Retrospective creation/editing changes no demand, permission, source facts or past fidelity.

No second top-level session slider is proposed. Reuse the compact selected preset and Compare options disclosure, five accessible radio choices, and existing source Details for overrides/limits. One source row contains live participation and separately consented background participation; background means continuing eligibility, including during live sessions. Exact per-source custom ceilings remain binding and separate from baseline preference.

## 2. Single decision path

Extend existing machinery rather than introduce another independent engine:

1. Read a consistent revision of intent, purpose consent, source limits, live leases, deletion barriers and capability state.
2. Qualify a small bounded cache of fresh observations/control hints by source, purpose, authority, event lineage, boot and observed time.
3. Determine motion/uncertainty independently from intent. A session start requests detail; it does not prove movement.
4. Use the existing plan factory to construct an authorized desired plan for each source from balance, useful-data needs and current hints.
5. Use the source resolver as the final arbiter of legal/capability/safety constraints, accepted hard requirements, explicit caps and soft targets.
6. Persist the desired revision, apply through the existing broker/shared physical owners, and retain exact acknowledged per-source results.
7. Feed bounded delivery/coverage/failure information into the next reconciliation. Applied acquisition, durable admission and qualified saved data remain distinct.

`CollectionMotionController` supplies qualified finite-state fusion; `SemanticAcquisitionPlanFactory` supplies source profiles; `SourcePlanResolver` supplies final feasibility; the existing owners/writers apply and persist. Legacy `TrackingPolicyManager`, four-tier escalation and battery policy must cease independently actuating collection. Retain required compatibility outputs and evidence, derived from this one decision path.

Order of authority: privacy/Off/pause and valid consent → actual platform/legal/hardware constraints → mandatory safety/storage feasibility → accepted source contracts and explicit user limits → live quality preference → baseline preference and cost reduction. If constraints make a requirement impossible, report that fact. Do not imply both were satisfied.

**An unmet preferred upgrade must preserve valid baseline collection.** Reject or mark the affected consumer's unsatisfied requirement, retaining other eligible demands. Do not pass every soft target as an impossible hard floor into the current resolver, which can disable the whole source. Never weaken a true hard contract to claim success.

Reconcile on changes and scheduled expiry/backoff deadlines. Coalesce equivalent plans; only changed physical configurations reconfigure providers. Reason changes and organization edits cannot restart hardware. Observe only inputs needed by current authorized demands; release subscriptions when their last consumer leaves. Use existing Flow, coroutine/dispatcher, queue and wake scheduling seams. No new event bus, learning service, network service or always-running poller.

## 3. Input inventory and trust rules

These are all in-scope input families. A row is a design obligation, not a claim that its adapter is already implemented.

| Input | Relevant types | Use and limit |
| --- | --- | --- |
| User configuration | Source/purpose selections, five-level choice, source overrides, explicit cadence/distance/accuracy caps, pause, background consent, automatic rules | Defines intent and authorized envelope. Defaults or hints never grant consent. |
| Live intent | Active manual/automatic session, participating sources, lease lifetime, explicitly requested current-position/game capability | Temporary quality/latency need. History, maps of stored data, exports, session labels and retrospective edits create no acquisition demand. |
| Activity | Qualified still/walking/running/bicycle/vehicle classifications, enter/exit transitions, provider confidence | Strong or supporting motion evidence according to its contract. Automatic CONTROL remains separately authorized and retained; never turn it on silently. |
| Steps | Fresh positive counter deltas/rate, observation interval, counter reset, continuity domain, batch age | Supports movement. No steps, no callback and old delivered totals do not prove stillness. |
| Location | Qualified speed/displacement, horizontal/speed uncertainty, age, fix quality and provider yield | Supports motion and bounded recovery. Missing fixes or poor reception mean uncertainty. No added Location acquisition for radio map enrichment. |
| Cell | Already authorized fresh callback/change evidence, freshness and subscription availability | Weak corroboration and availability feedback. Handover is not sufficient motion proof; identity-free facts cannot assert tower identity changes. |
| Wi-Fi | Fresh permitted aggregate observations, age, useful change, stale/cached result and existing callback health | Delivery/value feedback. Existing controller has no Wi-Fi motion handler. Do not invent an AP fingerprint or rely on counts alone as movement. No extra scans solely to manufacture a motion hint. |
| Pressure | Qualified changes/trends, interval and noise | Supporting continuity only; weather/elevators are ambiguous. Does not prove horizontal movement or altitude. No hidden Pressure acquisition or invented burst provider. |
| Evidence quality | Observed and received time, source confidence, uncertainty, conflicts, duplicate lineage, freshness deadline | Distinguishes useful hints from stale/ambiguous evidence. Derived motion, projections, imports and replay do not become independent fresh votes. |
| Device energy | Valid battery level, charging, Battery Saver, thermal state | Applies bounded cost/safety response. Unknown battery stays unknown; charging cannot override thermal/privacy/legal limits. |
| Platform capability | Hardware/provider availability, permission precision/background grant, legal service host/start, Doze/app restriction, batching support, radio throttling, SIM/topology changes | Constrains what is achievable. Missing capability state does not default to permission. One unavailable source leaves eligible siblings usable. |
| App lifecycle | Foreground/background, relevant visible live consumer, screen state, user stop, process restart/reboot | Controls ownership and permitted delivery needs. Screen-off is not stillness or the end of a live session. Opening a screen cannot enroll tracking. |
| Delivered value | Time since qualified observation, current coverage/gaps, quality/freshness need, cached/novel delivery, failures, canonical processing lag | Prefer useful new evidence and recoverable continuity to redundant attempts. Do not discard valid new history merely because it adds little to motion inference. |
| Runtime/storage health | Desired/applied revision, registration generation, retirement debt, outstanding work, bounded queues, retry/backoff, disk/admission failure | Prevent duplicate owners and unbounded work; distinguish source-local limitation from a global durability failure. Never report successful recording on failed persistence. |
| Time and authority | Boot/monotonic domain, expiry/dwell deadlines, consent/policy epochs, deletion/retention barriers, lease expiry | Bounds inference and recovery. Wall-clock changes cannot prolong a hint. Permission/capture-off does not by itself erase retained lawful history. |

Use native/control observations already authorized for the current purpose. Cross-source adaptation uses a minimal transient qualified hint; it does not copy raw data into another source or authorize a broader historical join. Radio-to-Location map enrichment keeps its separate compatible-provenance/range contract. Underlying Android Location services may internally use radios; an app source setting governs Tracker's independent collection/storage, not all physical operations performed by Android.

No new microphone, camera, accelerometer, Bluetooth scan, geofence, calendar, remote context, identifier archive or remote AI is needed. A named legacy significant-motion method does not prove a physical sensor is owned; use such a callback only if its existing lawful implementation is traced. No new hint purpose or retention is implicitly granted.

## 4. Concrete initial profiles

The following is a bounded proposed initial table for **explicit adoption of the new adaptive envelope**. Values are requested targets, not delivery or battery guarantees. Existing contracts, platform restrictions, explicit limits and source-specific retry rules can be stricter. Version the table; never rewrite historical plan bytes or reinterpret old QoS codes.

| Source/control | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- |
| Active permitted Location interval / mode | 60s / low power | 30s / balanced | 10s / balanced | 5s / high accuracy | 1s / high accuracy |
| Location min interval / displacement / max batch | 30s / 50m / 120s | 15s / 30m / 60s | 10s / 20m / 50s | 2.5s / 5m / 10s | 0.5s / 2m / 2s |
| Captured Activity classification interval | 60s | 45s | 30s | 15s | 5s |
| Direct Steps max report latency / projection checkpoint | 300s / 60s | 120s / 30s | 60s / 15s | 15s / 5s | 5s / 2s |
| Captured Pressure sample period / max batch / aggregation | 1s / 60s / 60s | 0.5s / 30s / 30s | 0.2s / 10s / 10s | 0.1s / 5s / 5s | 0.05s / 1s / 2s |
| Wi-Fi acquisition | Existing broadcast/cached observations | Same mechanism | Same mechanism | Broadcast plus useful bounded active attempts where authorized | Same, stronger finite attempt preference |
| Cell acquisition | Existing change callbacks | Same mechanism | Same mechanism | Callbacks plus useful bounded sparse refresh where authorized | Same, stronger finite refresh preference |
| Wi-Fi / Cell maximum provider-observation age | 10min | 7.5min | 5min | 2min | 1min |
| Wi-Fi observation deduplication window | 30min | 20min | 10min | 4min | 2min |
| Passive-only Location / system-continuity Steps | Supported passive/continuity mechanism | Same | Same | Same unless a supported active envelope is explicitly enabled | Same restriction |

Location3 is a new reference using the app preset's 10s/20m shape; the current factory reads actual stored custom values, which must remain exact for existing configurations. Timing and precision are separate; preserve explicit accuracy filters and approximate-access limits. Activity capture quality acceptance cannot become less truthful at higher levels: use one explicit quality contract across new profiles, with current Balanced65 as the proposed classification threshold; transition/control rules remain separate.

For new `forProfile` contracts, keep genuinely mandatory useful-source floors distinct from preferred timing. Proposed maximum provider-item ages for Location are120/60/50/10/2s and captured Activity120/60/30/15/5s across levels1–5. Direct Steps keeps its existing300s age/report-latency safety envelope; captured Pressure keeps its existing60s age, at most1s sample period and60s report/aggregation safety envelope. Preferred delivery/checkpoint/sample timing follows the table. Ambient Steps retains its explicitly selected system-continuity contract with no invented delivery cadence; Activity CONTROL retains its separate transition/continuation purpose contract. Radio floors remain callback/broadcast capability with no promised delivery latency. An age limit qualifies an observed item; it does not prove continuous coverage or a fresh motion hint. Apply the stricter qualified hint expiry separately.

Radio initial attempt lower bounds: level4 Wi-Fi120s and Cell300s; level5 retains current Wi-Fi60s and Cell120s. Preserve the existing maximum of **one optional active attempt per registration**, carried deadlines and stricter platform/backoff limits. These are spacing bounds, not recurring scan cadence. After the budget is consumed, continue callbacks; only a genuine supported lifecycle can permit another attempt. Profile changes, authority updates and artificial unregister/re-register cycles must not replenish it. Attempt only when authorized, useful freshness is unmet, motion is qualified moving or unknown during live focus, no request is outstanding and a plausible result is available. Fresh stationary evidence suppresses optional redundant attempts. A denied request or unchanged cached result is not a new observation. Higher preference cannot promise continuous fresh radio results; show the actual limitation. Lower levels can share the same callback mechanism with different declared freshness/deduplication contracts; do not fabricate five reception rates. The table's new intermediate age/window values belong to new profile contracts, never silent legacy QoS lookup.

Stationary reduction must preserve a supported wake/recovery path and the accepted consumer's maximum latency/continuity contract. If the only selected source has no adequate independent wake mechanism, retain a useful permitted sentinel or active profile; do not strand it in passive-only sleep. A delayed Steps batch cannot serve as a fast wake guarantee for high-detail Location. Unknown/stale/conflicting motion alone cannot lower a live session's desired level4-or-higher target: apply that requested profile unless an actual cap, legal/safety restriction or verified safe reduction limits it. Baseline unknown uses its configured envelope with bounded recovery. Unknown is neither movement proof nor permission to maximize unrelated work.

Active background adaptation is a separate explicit adoption choice. Recommended: allow it per selected background source where a lawful host and implemented provider contract support it, using the same five-level policy and finite budget. Preserve existing passive-only consent unchanged until adoption. Background eligibility continues during sessions. This does not add unsupported Activity/Pressure background capture or promise arbitrary Android service starts.

The proposed background scope is bounded to the existing four MVP sources:

| Source | Permitted background envelope to implement | Limitation |
| --- | --- | --- |
| Location | Preserve passive points; explicit new adoption can allow active profiles through a lawful location host with required background/precision permissions | Host/permission denial retains permitted passive behavior and reports unmet detail. No promise of autonomous service start. |
| Steps | Existing selected system continuity mechanism; join a compatible live direct-counter owner when already authorized | No new always-on direct counter or alternate backend simply to meet a latency preference; continuity/count domains stay exact. |
| Wi-Fi | Existing permitted cached/broadcast observations and shared live-owner results | No new repeating background scans. Optional active attempts remain tied to the existing authorized direct live-capture contract and finite budget. |
| Cell | Existing permitted change observations and shared live-owner results | No new recurring background refresh; optional active refresh remains within the existing authorized direct live-capture contract. |
| Activity / Pressure | Existing live capture and separately authorized automatic CONTROL where applicable | No new baseline capture product or Pressure burst mode in this slice. |

For a new background enablement or adoption that expands Location beyond passive operation, present the source-specific effect and required ongoing notification/permissions before saving that explicit opt-in. Collection continues while enabled, including during sessions, until disabled/paused/revoked or limited by capability. Ending a live session releases only its demand. Existing background users retain the old envelope unless they choose the expansion; unsupported future providers are not preauthorized by this proposal.

Store that independent choice in immutable `source_policy.background_acquisition_envelope`: legacy/null retains the existing envelope; `ACTIVE_LOCATION_ALLOWED` is valid only for Location with explicit background consent. Choosing a new five-level profile or granting Android permission cannot set it implicitly. Expansion is a separate explicit background-consent update with its own effective boundary/epoch; preserve capture/CONTROL authority and historical rows. Factory, resolver, ingress authority and recovery all check the stored envelope. Background off still wins over either envelope.

## 5. Motion stability and power response

Proposed named initial constants reuse existing motion rules wherever their meaning fits:

- Positive qualified motion restores permitted detail promptly. Session start also raises the quality target, while motion remains unknown until observed.
- Stationary needs two valid confirmations at least20s apart, at least90s dwell, and no newer conflicting strong motion.
- Latest stillness expires after120s or the source's stricter freshness deadline. Expiry returns unknown; missing callbacks never refresh it.
- Retain existing general90s, pedestrian120s and vehicle10min continuity holds, separately tracking the last primary evidence. Weak radio/Pressure hints cannot renew those holds indefinitely. A tunnel can retain continuity without manufacturing a fix.
- Use monotonic observed time with compatible boot and source authority. Reject wrong/future/expired evidence and deduplicate original event lineage across purposes/adapters. Clear transient state when its authority expires.
- Changes in explanation alone do not restart providers. Fresh movement, explicit user focus and revocation take effect without waiting for a cost-saving dwell.

Proposed battery rules are runtime adjustments, never changes to the saved setting or source selection:

| Condition | Baseline-only source | Live participating source |
| --- | --- | --- |
| Valid battery outside a hysteresis-held low/critical band, saver off | Saved baseline | At least4, or saved5 |
| Battery at/below20% | One level below saved baseline, minimum1 | Keep live detail target; reduce redundant work within its constraints |
| Battery at/below10% | Target1 while preserving required useful continuity | Keep live target; one meaningful low-battery notice, no repeated per-callback warning |
| Recovery | Leave critical band at15%, low band at25%; recompute from saved baseline, never ratchet | Same live rule |
| Android Battery Saver | Same one-level preference pressure, not additive with low battery | Reduce optional work within live obligations; actual OS restrictions always apply |
| Charging | Remove battery-percentage reduction only | Same live rule; no automatic level5 |
| Unknown battery | Preserve unknown and avoid percentage-based adjustment | Same |
| Severe thermal / actual OS restriction | Minimum useful safe supported plan or truthful blocked state | Same safety rule; mark unmet quality and retain allowed baseline where possible |
| Storage/admission failure | Reduce/defer optional work; bounded retry or explicit gap/failure | Same durability rule; never claim saved data |

This intentionally favors the user's live-session detail request over ordinary low-battery optimization. An alternative would automatically reduce live detail at20%; that trades recording quality for runtime and requires an explicit product choice. Neither policy can override actual device restrictions or guarantee continuous execution.

## 6. Durable truth and compatibility

Keep source-local canonical writers, existing purpose consent, immutable acquisition sessions/runs/manifests, privacy/deletion fences and count domains. User-facing session organization remains separate and can combine only qualified retained observations without rewriting capture authority.

Five-level preference/profile attribution is new metadata. Legacy `SourceCollectionFrequency`/`SourceQos` codes OFF0/Saver1/Balanced2/Responsive3 keep their existing meaning; new level2 is not old Balanced2. Preserve all existing encoded desired/applied plans, checksums, floor contracts and historical readers. New profiles require explicit versioned attribution and any required immutable demand generation, authored with migration/reopen/codec test source.

Concrete additive placement reuses existing tables: immutable `source_policy` rows receive nullable `overall_balance_level`, `source_balance_override` and `balance_profile_version`; overall metadata must agree across the complete atomic six-source revision. All-null means legacy/custom. `acquisition_plan_revision` receives nullable `balance_profile_version`; `source_desired_plan` receives nullable `requested_balance_level` and `focus_requested` (false for legacy). Baseline resolves through the linked immutable policy revision. Validate metadata and collisions in the same transaction; do not alter the historical concrete-plan checksum domain. No new policy header or per-hint table is required.

New demand construction uses explicit `forProfile(version, level, purpose)` mapping with the table's actual parameters. Historical maintenance/readers select the original profile mapping or legacy `forQos`, then authenticate exact original floors, plan and writer receipts. Profile metadata cannot merely decorate a demand still derived from the wrong three-code mapping. Current Location/Wi-Fi/Cell historical shape predicates accept parameter ranges rather than fixed timing tuples, so new timings alone do not require a new writer lane or plan codec. Introduce an immutable generation only where the actual changed contract requires one; never mutate an old contract in place.

Use existing desired/applied plan records for explanation and recovery. Add only the necessary selected balance/profile version and bounded reason attribution for meaningful plan transitions, with existing lifecycle retention. No per-hint durable journal, raw coordinates/identifiers in diagnostics, new analytics store or new retention duration. Admission-qualified transient hints may precede canonical materialization; historical product truth must still come from qualified durable facts.

Migration reads actual stored policy and custom values, not preset names: setup and settings currently disagree on some preset values. Preserve source masks, purpose epochs, active demands, caps and custom numerical settings. Never use the current `applyPreset` migration path, which overwrites source selections. Existing vectors remain Custom source settings until explicit new-profile adoption; new setup proposes Balanced without enabling unchosen sources. If a migrated cap prevents level4, show the limitation and preserve the cap.

Recovery rebuilds authorized current demands and checks actual ownership. It cannot replay old motion as new confidence, resurrect ended live demands or fabricate applied state from a desired plan. One source failure remains local when possible; global authority/storage corruption is surfaced honestly. Late callbacks remain fenced by generation and deletion barriers.

History, maps, statistics, widgets, games/awards and exports consume actual retained facts and coverage, never today's preference as proof of past quality. No eligible retained Location means no geographic context for that source; radio/Steps history still works. Turning Location off now does not remove valid compatible historical context. Retrospective labels do not create activity evidence or rewards. Export preserves original purpose/quality and exact selected scope; unsupported profile/organization metadata round-trip must be explicit until a compatible format exists.

## 7. Current gaps this design closes

Full one-based code references/read inventories are in [Runtime](RUNTIME_ADAPTIVE_FIVE_LEVELS.md), [Data](DATA_ADAPTIVE_FIVE_LEVELS.md) and [Product](PRODUCT_ADAPTIVE_FIVE_LEVELS.md). Main inspected at `13f1a99871b59d29b3d059f374fc415490da6943`; candidate implementation branches remain unassembled.

1. Session-only motion controller excludes authorized baseline hints; stop currently resets to MOVING/100 and service starts imply movement.
2. Stillness has no final freshness expiry; weak continuity can outlive primary evidence. Hint dedup scope is too broad for unified baseline/live authority.
3. Several legacy/current paths influence collection; old four-tier and global battery rules cannot be the five-level source-independent policy.
4. Current direct-floor failure can disable a source despite a valid baseline. Separate unmet preferred upgrades from mandatory accepted contracts.
5. Only three enabled frequency profiles exist. Some preset application changes the selected source mask, and preset definitions differ across setup/settings.
6. Unknown battery becomes100%; low-battery global caps can remove Location. Motion adaptation is incorrectly explained as Battery Saver.
7. Wi-Fi motion fusion, wider background host capability and stronger profile contracts are not completed merely by this plan. Preserve actual supported mechanisms.
8. All original provider/writer/query/history/enrichment/export/lifecycle gaps and the global six-source assembly gate remain in the parent implementation plan.

## 8. Delivery and proof

After concrete design approval, fold into the existing implementation sequence:

1. Profile/preference and compatible contract source; migration/reopen/codec test source; preserve existing consent and old bytes.
2. Scoped transient hints and stable finite fusion; baseline/live lifetime and freshness tests.
3. Single factory/resolver authority with soft-target handling; remove competing actuators and fix battery/reason semantics.
4. Source-specific apply/retirement/recovery and supported background hosts; finite resource budgets and source independence.
5. Existing settings/status/history/data/export consumers and organization integration; concise copy and original evidence meaning.
6. Complete every other planned production/test slice, freeze one convergence input, then explicitly open deferred validation. Run authoritative host/full gates, migrations/reopen and relevant representative device checks, fix failures and rerun required gates. Fresh independent assembled-code review receives all original messages, approvals, diff and actual results before readiness/integration claims.

Acceptance covers all15 nonempty four-source combinations plus empty across five levels, permitted baseline/live/both purposes, manual/automatic/retrospective transitions, Activity/Pressure compatibility, source-off changes, stale/duplicate/conflicting hints, delayed batches/counter reset/tunnel, permission/SIM/provider loss, low-battery/charging/thermal boundaries, host denial, reboot/crash/lease expiry, apply rollback/late callback/deletion, disk/backlog failure, existing settings migration and all affected readers/exports. Verify selection independence and source-local failure isolation, not just preset constants.

Device evidence later must measure useful freshness/coverage alongside requests, wakeups, CPU/writes and battery on matched stationary/walking/vehicle/outage workloads. Static review cannot establish optimal timing, measured savings, every device behavior or trustworthiness by itself. No tests or deferred validation ran in this pass.

## 9. Approval packet and platform references

Two focused decisions can approve this adaptive slice without reasking settled direction:

- **Recommended core:** this five-level design, new-setup default3, live preference at least4, exact initial profile/hint/power rules above, preserved source choices/caps/legacy data and existing UI/engine seams. Technical defaults are explicit in this packet and later tuning needs evidence; no hidden broader redesign is authorized.
- **Recommended background envelope:** explicitly opt-in active Location through a lawful host, alongside the exact existing Steps continuity and Wi-Fi/Cell passive/shared mechanisms listed in section4; preserve existing envelopes until adoption. Alternative: keep Location baseline passive/opportunistic, which can make higher Location balance targets unattainable between sessions. No unlisted future background capability is approved.

Earlier unresolved session overlap/removal, portable interval representation, CONTROL retention and map-context decisions are not silently approved by accepting this adaptive slice. Record actual answers in TASK_CONTEXT and the implementation plan before production changes.

Official Android documentation checked2026-09-26: [Location and battery](https://developer.android.com/develop/sensors-and-location/location/battery) distinguishes accuracy, frequency and delivery latency; [Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan) documents cached results, passive callbacks and throttling; [foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) constrain background starts and while-in-use permissions. These support bounded target/host design, not a guarantee of the proposed timing on a device.

## 10. Static peer review disposition

The three focused Astra roles cross-reviewed the consolidated proposal. Product identified the unknown-motion/live-target ambiguity and insufficiently concrete background scope; both are corrected in section4. Runtime identified the finite radio budget, battery recovery-band wording and retained historical Location distinction; these are corrected in sections4–6. Data required explicit profile-to-floor compatibility, immutable metadata/collision handling and a separately stored active-background Location envelope; these are recorded in sections4/6, and Data rechecked the final envelope paragraph without remaining material findings in that scope. This is review of a proposal, not the requested final fresh-agent review of assembled production code.
