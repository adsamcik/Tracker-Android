# One adaptive tracker: complete implementation proposal

2026-09-26. **Current planning entry point. Recommendations await approval.**

This document consolidates the earlier provider, whole-product and five-level proposals. Its decisions and the four gap-closure reports take precedence over conflicting earlier recommendations. Earlier documents retain code evidence, compatible detailed contracts and the exact profile table; they are not competing plans. No production implementation, validation, provider activation or integration is claimed by this planning pass.

## 1. What we are building

Users choose what to collect. One central engine runs only the selected, supported work, adjusts its effort to current needs, and stores trustworthy observations. A live session requests more detail from its participating sources and creates an organizational interval. Ending it leaves separately enabled background collection running. A session added later organizes retained observations; it cannot improve what was collected in the past.

One logical flow:

**User choices + live focus + qualified hints + device limits → central policy → shared source owners → qualified stored observations → history, sessions, maps, statistics and files.**

Each arrow has a specific owner and authority contract. Optional enrichment reads compatible observations already available. A Wi-Fi or Cell selection never silently turns on the app's Location collection. A map, game or explicit current-position action can request temporary Location for its own disclosed feature lifetime; those feature-only points do not become tracking history.

### Settled requirements

- Location, Steps, Wi-Fi and Cell work independently and in supported combinations across manual, automatic and sessionless background uses.
- Automatic triggering is separate from recorded sources. Existing Activity and Pressure verticals also remain part of the complete tracker and global assembly.
- Background collection is separately consented. Sessions are organization plus live fidelity preference, including later-created sessions.
- Exactly five power/detail preferences. Live sessions favor detail. Adaptation considers source **and acquisition mode**, marginal extra work and actual useful evidence.
- Local-first storage, no added network services/AI/telemetry, one physical owner per shared resource and one protected canonical Location writer.
- Every current product surface and export must follow the same evidence semantics. Tests, independent code tracing and physical-device evidence remain required before readiness claims.

### Recommended defaults in this proposal

| Decision | Recommendation | User-visible consequence |
| --- | --- | --- |
| Balance | Power first / Favor power / Balanced / Favor detail / Detail first; new setup starts at3 | One understandable control, with source overrides in advanced settings |
| Live focus | Participating sources prefer at least4; an existing5 stays5 | Better detail without silently removing hard user limits or enabling other sources |
| Background Location | Finish passive delivery; offer a separate active mode **off by default** | Active mode can provide more detail between sessions, with a continuing notification and higher cost |
| Background radio continuity | Default to opportunistic listening; offer **Keep running with notification**, off by default | Wi-Fi/Cell can maintain their own lawful shared host without a session or another source; this does not request extra scans or guarantee callbacks |
| Session organization | One live session; any number of editable closed/retrospective sessions, including overlaps | Names/activity labels and history organization without copying observations or double-counting totals |
| Remove versus erase | Remove session removes grouping; erase observations is a separate scoped action | Organizing history cannot accidentally destroy its source data |
| Radio map context | Show an observed device position only through a valid same-purpose join; retain accuracy/uncertainty | Coarse useful points remain available; no invented router/tower positions |
| Automatic CONTROL | Minimal local evidence becomes ineligible after24h, physically cleaned at the next permitted run | Short-lived automation evidence stays out of ordinary history, exports and rewards |
| Files | Preserve existing source formats, add missing adapters and a small session bundle; show any whole-entry expansion | Users know exactly which data will leave the app |
| Old schedules | Keep compatible Location incremental plans; pause misleading JSON incremental and raw-archive plans for review | No silent widening of existing exports |

All new constants, schema/API shapes, behavior and copy in the linked contracts form one reviewable recommendation. Existing permissions/consent/custom settings are preserved until explicit adoption. Choosing the five-level UI does not grant background access or activate a source.

## 2. Supported mechanisms and meaningful alternatives

“Supported” is determined from the actual device/API/permissions and lawful host. A missing capability yields an actionable limitation for that source while other valid sources continue.

| Source/use | Selected approach | Available alternatives considered |
| --- | --- | --- |
| Location, live | Existing GMS fused callbacks or framework listener backend, exact shared demand and sole protected writer | Replacing all modes with PendingIntent would change a working active path; duplicate owners or make-before-break would need unnecessary overlap machinery |
| Location, background | GMS passive PendingIntent or framework passive PendingIntent, same durable pipeline; explicitly opt-in active service mode | Passive-only is the lower-cost alternative; process-only callbacks cannot provide equivalent background continuity. Periodic polling cannot promise reliable routes |
| Steps, live | Hardware step counter and existing count-domain/reset logic | Step detector or software accelerometer pedometer would change capability, accuracy and power behavior; no silent fallback |
| Steps, background | Existing deterministic Health Connect current-device Steps when supported, otherwise on-device Local Recording; bounded import opportunities | Permission denial is not authorization to switch providers. No cloud fitness account, hidden direct-sensor FGS or wearable integration |
| Wi-Fi | System/passive observations, with optional explicit sessionless visible host; at most one authorized active attempt per genuine live intent under existing throttles/backoff | Opportunistic hosting saves service overhead but can have process gaps. No background scan loop, throttle bypass, RTT, identifier collection or scans merely to infer motion |
| Cell | Subscription callbacks/cached evidence, with the same optional visible host; at most one supported optional live refresh | Opportunistic hosting remains default. No privileged network scans, modem resets or invented coverage on unsupported APIs/SIMs |
| Activity, capture | Existing classified observations and explicit capture quality | Transitions alone cannot substitute for captured classifications |
| Activity, automation | Separately authorized Transition control and lawful current callback envelope | Significant-motion sensor, geofences, alarms and custom ML are possible different products; none is a hidden helper. No-GMS automatic triggering is unavailable in this scope |
| Pressure | Existing continuous hPa source, bounded batching/windows and correction-aware history | No new ambient Pressure, burst/wake contract, altitude inference or weather feature |

No new dependencies are needed. Official Android/Google source links and API/version details are in [Runtime closure](RUNTIME_GAP_CLOSURE.md). The plan specifies degradation rather than promising delivery through force-stop, prohibited background starts, missing hardware or OEM restrictions.

## 3. One central policy, with efficient activation

Retain the existing `CollectionMotionController`, `SemanticAcquisitionPlanFactory`, `SourcePlanResolver`, source registry, shared controllers, durable ingress and protected writers. Extend their contracts instead of adding a parallel engine. Existing legacy policy/battery paths translate compatibility settings or consume the applied result; they cannot remain independent actuators.

Decision order:

1. Read saved source/purpose consent, current policy revision, live participants and explicit limits.
2. Resolve actual permissions, capabilities, service legality, deletion/startup authority and storage/thermal feasibility.
3. Combine only fresh qualified hints from already-authorized work; deduplicate one physical event delivered to two purposes.
4. Build a small finite set of feasible source modes and profile candidates. Separate useful hard source contracts from preferred detail.
5. Compare **additional** sensing/radio, wake/delivery, processing/storage and switching cost against the current shared plan. Unknown cost stays unknown; near-equal candidates keep a compatible current plan.
6. Apply through the existing single owner, with exact revision, retirement acknowledgement and durable admission boundary. Publish requested/applied state and a useful limitation reason.

### Inputs considered

| Input family | Specific usable inputs | Limits |
| --- | --- | --- |
| User intent | Enabled sources, background consent/envelope, global/source balance, legacy numeric caps, explicit live participants, automatic restart lock | A hint cannot override Off, consent or a hard cap |
| Motion and value | Qualified captured Activity/authorized transition, direct Steps deltas, accepted Location movement/accuracy; source novelty and coverage where meaningful | Wi-Fi/Cell novelty and Pressure feedback initially affect value/health, not standalone motion classification; no new sensors activated to obtain hints |
| Evidence quality | Original observation age/uncertainty, lineage, clock/boot, delivery gaps, provider accuracy, accepted quality and useful recent yield | Missing callbacks are not stillness; imports/replay are not live movement |
| Power and conditions | Battery level/known charging state, Android Saver, thermal restrictions and actual storage/backpressure | No measured battery-percent estimates from generic priors; charging does not authorize sources or remove OS limits |
| Execution | Foreground/actual host and service type, permission/background grants, GMS/backend/sensor/SIM support, registration health, pending requests and finite radio budget | A stored desired plan is not proof of active collection or legal restart |
| Timing and stability | Hint expiry, stillness dwell, bounded hold/hysteresis, retry/cooldown and lifecycle revision | No perpetual wake loop or budget renewal through profile churn |

The exact profile1 timing table remains in [Five-level policy §4](FIVE_LEVEL_ADAPTIVE_POLICY.md). New metadata records explicit profile version and level; historical four-tier QoS bytes retain their meaning. Runtime closure specifies per-purpose contracts, passive Location30s throttle/120s age, receiver5s/64-location admission bounds, Steps import opportunities, feature freshness and test cases. These values are initial proposed contracts, not measured optimums.

At battery<=20% or Saver, baseline work first avoids expensive optional acquisition and considers the next lower profile's eligible modes and safe batching. At<=10%, it may consider all lower allowed profiles, applying the same sustained-cost comparator and fixed tie order; there is no assumed total cheapest mode across conflicting cost drivers. Cheap Steps continuity/passive observations survive; batching must respect actual durability and accepted latency. Live participants keep their preferred detail within genuine constraints. Recovery uses15/25% hysteresis. Baseline-only sources do not receive another source's live upgrade.

Only observers needed by authorized scopes remain subscribed. The bounded transient hint cache, expiry and meaningfully changed plan are sufficient; there is no general optimizer, persistent hint warehouse, telemetry or separate cost database.

Cost-driven reductions must improve at least one sustained driver without worsening another; crossed/unknown comparisons keep the compatible current plan. Switching has its own acknowledged-retirement and stable-saving dwell gate, so a one-off transition cost neither disappears nor permanently prevents every useful reduction. Runtime closure defines the finite tie order. A user changing intent or a hard safety/authority loss follows its own immediate reconciliation rule.

## 4. Lifetimes, interruption and user control

- Reuse TrackerService as a demand-owned legal host, separating its lifetime from the organization record. Source-only capture requests only its actual required Android types; a radio permission/type requirement does not enable Location capture.
- Passive Location is system-rearmable and sessionless. Active background Location requires explicit consent and a lawful start; keep its visible host while that mode remains enabled so stationary reductions do not make later escalation impossible. Passive-only mode has no permanent service.
- Background Wi-Fi/Cell default to opportunistic callbacks while the process can run. Their optional **Keep running with notification** permission holds the same demand-owned service without any live session, Location capture or active radio attempts. Charge that host's marginal cost once. Correct Android location/specialUse prerequisites still apply; API26–28 Wi-Fi delivery differs from API29+ shared scan broadcasts. A host improves listener continuity but cannot manufacture scans, fresh-empty proof or survival after user stop.
- If an active host cannot lawfully restart, retain permitted passive collection and show the active limitation. Force-stop is not bypassed; reboot invalidates monotonic leases/hints.
- Stopping a session releases focus/capture while preserving separately enabled baseline. Source changes apply forward at an atomic revision. Explicitly removing the last live participant ends focus and closes its group; transient capability loss is an interruption, not a user Off action.
- Keep the existing just-stop,30-minute automatic-restart pause and until-charging choices with their actual scope. Do not add a generic resumable Pause state machine.
- A lost sibling source does not stop valid siblings. A shared deletion/startup authority failure can block all writes. Failed persistence cannot be displayed as successfully applied intent.
- Map current-position and games use temporary shared nonpersisting Location leases with their own actual host/freshness. Tracker Stop and game Stop remain independent.

Transport handoff uses acknowledged removal before adding a successor, preserving exact observed-time authority and an honest possible gap. The design does not claim uninterrupted collection when Android or storage cannot provide it.

## 5. Trustworthy storage and organization

[Data closure](DATA_GAP_CLOSURE.md) defines the schema, query result types, bounds, format fields and compatibility paths.

### Observations remain the source of truth

Every accepted fact retains its original source, time quality, purpose, lineage, historical contract and current deletion/retention qualification. Location uses one canonical sample/common authenticated receipt with independent capture/background purpose associations. A callback serving two lawful purposes does not create two samples. Removing one purpose cannot erase or expose the other improperly. Feature-only fixes are not persisted.

First source sequence0 is valid. Native Wi-Fi/Cell generation2/owner4 manual+automatic support coexists with immutable generation1/owner2 history. Retained readers authenticate the historical writer; only current mutation requires today's writer. Exact Pressure correction/retention proof, Steps count domains, replay and erase fences remain mandatory.

### Sessions are an overlay

Use one organization metadata table and a read adapter for old recordings, with bounded portable source references for imported bundles. No per-observation membership warehouse, mass backfill or rewriting acquisition manifests. Editing an old virtual group creates an override; removal leaves only the minimal identity suppression needed to avoid resurrection.

Use UTC half-open intervals with a saved display zone. DST gaps are rejected inline; repeated local times require offset selection. Zone-only edits preserve instants. One live group is open; its active edits are name/label only. Closed/retrospective groups can overlap. Clock reversal shows an explicit uncertain-boundary state rather than inventing an end time.

Global totals count underlying evidence once. Steps crossing interval boundaries remain explicitly unallocated unless exact attribution exists. Pressure/Activity windows retain their original bounds. Organizing a session grants no XP. Existing effects depend on qualified captured evidence and exact correction/delete retraction, with persisted metric/earned-receipt compatibility preserved.

### Complete history without an unbounded scan

Extend the existing history facade with indexed range discovery, per-stream cursors, complete replacement-group qualification and one snapshot. Calendar/timeline request their actual interval, not a recent-page filter. Use existing page100/member256 and stricter source-specific limits. Partial scans show partial state, never false empty days. Queries acquire no provider and perform no repair write.

All six sources have meaningful summary/detail/actions. “Recording time” means recorded acquisition intervals; “Session duration” means the organization interval; neither is movement time. Captured Activity is displayed as its actual detected-type evidence. Pressure shows hPa and coverage, without implied elevation.

## 6. Maps and enrichment

Native radio maps show an **observed device position**, never a claimed access-point/tower position. Wi-Fi uses an exact point or qualified30s bracket and chooses the nearest observed endpoint; Cell uses a qualified preceding point within60s. Preserve measured accuracy and uncertainty instead of an extra arbitrary100m cutoff. No interpolation is presented as observation.

Capture joins match exact capture scope/boot/clock/epoch; background joins require independent ambient associations and compatible intervals/fences. An organization interval is not join authority. Imported data remains imported. Without suitable Location, radio lists/counts remain useful and the geographic layer is absent.

Read bounded paired sets and invalidate from either source, correction, retention or deletion. No per-map-point query fanout or extra Location demand. Existing legacy maps/heatmaps/exploration use only their actual qualified geographic inputs; identity-free native radios do not gain fake legacy identifiers.

## 7. Product and files

[Product closure](PRODUCT_GAP_CLOSURE.md) is the surface/action/copy ledger. Every dashboard, Tracker, session editor/detail, calendar/timeline, statistics, map/game, settings/permission, files, notification/widget/tile, goal/reward and cleanup path is accounted for. Existing shells are reused. Routine healthy state stays quiet; only actionable limitations need attention. Copy must distinguish desired, collecting, delayed, unavailable, partial and no observations. Counts are source-appropriate; maps/distance appear only with the relevant evidence. Locales, large text and accessibility are part of the implementation and final proof.

### File behavior

| Format/operation | Concrete result |
| --- | --- |
| Existing source portable files | Preserve exact immutable entries and source version/checksum semantics; finish Wi-Fi/Cell and ambient radio file adapters |
| Location point file | Add a narrow typed point format for capture/background/imported geography and timing/accuracy/gaps; importing grants no native runtime authority |
| Session bundle | Optional ZIP manifest plus existing source files and organization metadata; bounded size/children, foreign identity, idempotency and partial import outcomes |
| Selection boundaries | Geographic points can be exact; source formats needing whole entries/days disclose expanded actual bounds before writing. Never fabricate clipped Steps or window statistics |
| GPX | Proven route segments only; break at scope/boot/gap/uncertain continuity. Sessionless passive points become waypoints |
| KML | Import explicitly timestamped Points; reject untimed geometry with honest partial counts. Do not synthesize current time or add a new undated-geometry store |
| JSON3 | Keep declared whole-session geography/legacy scope and descriptive quality; do not claim it contains all native source products |
| Last recorded session | Select the actual latest recorded session; no compatible data is a typed outcome, never fallback to all history or a silently older session |
| Scheduled output | Existing supported geographic incrementals remain; JSON incremental/raw archive plans pause for explicit conversion or recurring-sensitive-scope review |
| Database archive | Preserve exact reviewed database files, all their tables/operational content and some DB-backed preferences; explicitly not a complete settings/resource restore |
| Cancel/delete | Close/clean owned output on failure/cancellation; report residual cleanup and only advance watermarks after complete success. Full clear covers app-owned export/import caches; external shared files cannot be recalled |

Raw archive preview lists the actual database set and sensitive scope. Settling due CONTROL/deletion/retention precedes archive creation; new/unreviewed file scope blocks the operation. No filtered “safe raw database” or automatic restore is invented.

## 8. Decisions closed and alternatives retained

These are concrete recommendations for approval, not outstanding engineering questions. The alternatives are recorded so the user can choose a different tradeoff without another exploratory pass.

| Earlier seam | Recommended closure | Meaningful alternative |
| --- | --- | --- |
| D1 CONTROL | Minimal24h ineligibility/next-run cleanup, separate capture proof and trigger freshness | Delete after terminal decision for less retained evidence, or7days for longer local troubleshooting |
| D2 Location proof | One authenticated sample receipt plus typed purpose associations, atomic protected handoff and batch reads | Per-purpose duplicated points complicate totals/deletion; pruned-WAL dependence loses retained proof |
| D3/D5 history/background | Dynamic organization plus bounded range API and reactive stored-day reads; unified dashboard | Mass backfill/frozen membership or timed polling adds cost and divergent state |
| D4 maps | Qualified observed endpoint/preceding-point joins and visible accuracy | Hard100m filter hides valid coarse points; interpolation adds inferred geometry |
| D6 radio empties | Unknown/no qualified fresh observation when the platform gives no provable observation timestamp | Positive empty coverage needs a separate provider-completion identity contract; receipt time cannot manufacture it |
| D7 global scope | Complete passive Location and all six existing vertical/shared prerequisites | Any reduced scope would require an explicit named deferral; none is recommended |
| P1 setup | Preserve intent through permission denial, idempotent completion and explicit adoption | Silent reset/fallback would violate choice |
| P2 metrics/effects | Source-qualified metrics, Recording time/Session duration, exact effects/retractions | New movement-time estimator is an additional product, not implied by elapsed tracking |
| P3 feature Location | Shared temporary nonpersisting lease through existing interface | Duplicate providers waste work; disabling existing supported features is unnecessary |
| P4/P5 portable data | Existing source contracts + point file + small organization wrapper, explicit whole-entry expansion | Universal clipped format needs new numeric/provenance semantics; metadata-only cannot carry selected data |
| P6 geographic/schedules | Timed KML only, exact GPX gap rules, descriptive imports, no LastSession widening | Untimed geometry storage/reader and generalized incremental multisource export would expand scope |
| P7 raw archive | Actual file inventory and recurring review receipt; preserve some preferences/operational scope honestly | Removing archives is simpler but loses existing diagnostic portability |
| P8 recovery/deletion | Source-owned erase tokens, grouping removal separate, cancellation-safe owned cleanup and pending status | Treating a row deletion as complete evidence erasure is incorrect |
| Five-level defaults | Versioned profile1, default3/live4+, hard limits separate, mode/marginal cost and bounded hints | Preserve custom/legacy profile until adoption; no ordinal reinterpretation or blanket source-wide battery downgrade |
| Active background | Offer explicit active Location opt-in with continuing visible host, default passive | Passive-only avoids host cost but cannot offer dependable active detail between sessions |
| Independent radio background lifetime | Offer explicit passive-radio visible hosting in source Details, default opportunistic | Opportunistic-only is smaller/lower overhead but leaves process-lifetime gaps; a host never authorizes periodic scans |

Specific numbers, table fields, import bounds and feature age limits are in the Runtime/Data/Product contracts and the compatible five-profile table. They are fully specified initial choices to implement and evaluate; static planning does not prove optimal battery calibration.

## 9. Implementation and proof sequence

[Assembly closure](ASSEMBLY_GAP_CLOSURE.md) maps **all 25 global prerequisites** to implementation/test outcomes, records candidate-family selection and serial shared-file ownership, and defines the final evidence gates. [Input receipt](PLAN_CLOSURE_INPUTS.json) records seven current checkouts, exact refs, all preserved source-draft hashes and the 26 lifecycle commits. No branch is accepted wholesale.

1. Approve this concrete package/explicit alternatives. Keep all settled user choices.
2. Reconcile exact source authority/lifecycle and data foundations; finish sequence0/generation/retained proof and schema source together.
3. Implement the single adaptive policy, lawful provider/host lifecycle and live editing; author focused tests without running deferred gates.
4. Complete bounded organization/history/maps/effects, all existing product surfaces and files/cleanup. Commit coherent static-reviewed slices `IMPLEMENTED_UNVALIDATED`.
5. Once every planned production/test slice and all 25 dependencies exist, freeze one exact convergence input set. Then run compilation/Hilt/Room/schema/migration, `ciUnitTest`, `ciCheck --continue`, meaningful connected checks and required fix/rerun batch.
6. Obtain the separately required bounded candidate/device activation authority before changing preferred production bindings. Use a dedicated fixture installation; record actual source/device limitations, lifecycle, UI/accessibility and energy/quality evidence.
7. A **fresh independent code reviewer** traces the actual final implementation with all original messages, approvals, diff and real results. Resolve findings and verify the final integrated input, then merge locally into clean current `dev/v10` when ready. No push/deploy is authorized.

Preserve v27 data and released schema. Consolidate into unshipped v28 only while that premise is supported; an actually distributed v28 requires an explicit next-version migration. Unknown/corrupt development state is preserved and blocked, never reset. Exact external distribution history and device delivery/energy evidence cannot be manufactured by further planning.

## 10. Remaining gates, not missing design

The fresh independent Astra planning review is recorded in [PLAN_REVIEW](PLAN_REVIEW.md). Its pagination, controls, map-policy, cost-comparison, imported-binding and export-snapshot findings have been resolved in these contracts. Final review of implemented code is a separate future gate.

- User approval of the recommended package, especially active-background availability, CONTROL lifetime, session/file semantics and the explicit fresh-empty radio limitation. The repository's **Questions and approval** rule requires this before new behavior/architecture/data handling is implemented.
- Actual selected source-family integration review, production/test authorship and complete global assembly. This plan does not claim those steps already happened.
- Exact deployed-schema evidence if there are externally distributed development databases to preserve.
- Deferred compilation/tests/migration/device/battery proof and fresh final code review. Passing static review is not on-device reliability proof.

The completion claim for this artifact is limited: the identified planning seams now have concrete recommended contracts, options, ownership and acceptance criteria. There is no claim that every possible Android device can provide every source or that implementation is already trustworthy merely because the plan is detailed.
