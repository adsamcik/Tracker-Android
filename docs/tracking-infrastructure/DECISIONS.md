# Tracking Infrastructure Decisions

Last updated: 2026-08-24

Each entry records repository evidence and does not duplicate the final architecture document.

## TI-D001 — Audit the checked-out commit without synchronizing the branch

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: pull 90 remote commits; audit the checked-out tree
- Evidence: `dev/v10` is at `068ebe052`, 90 commits behind `origin/dev/v10`; the worktree contains three user-owned untracked tracking documents
- Decision: preserve and audit the checked-out repository. Pulling, rebasing, committing, or pushing is outside current authorization.
- Consequences: all evidence is pinned to `068ebe052`; remote changes are neither assumed nor silently incorporated.

## TI-D002 — Treat current event ingestion as a foundation, not the target broker/ledger

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: rename current `SourceDemand`/WAL as complete; evaluate contracts field by field
- Evidence: `SourceDemand.kt` lacks purpose, consumer, consent, persistence, logical id, and policy revision. `SourceEventWalEntity.kt` stores an optional logical id directly and lacks purpose eligibility/attribution identity.
- Decision: reuse proven deduplication, generation, clocks, and payload infrastructure, while introducing explicit policy, purpose, attribution, and writer contracts.
- Consequences: existing local tests remain valuable but do not satisfy the target phase gates by name alone.

## TI-D003 — Protect the current location canonical writer

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: add a new location writer immediately; retain current projection until shadow evidence
- Evidence: the Location review found the established `LocationTrackerComponent` → `PersistenceProcessor` product path still active. `LocationDomainProjection` emits four effect kinds with no production consumer, while rollout metadata nevertheless describes event projections as canonical.
- Decision: no second active location output writer may be introduced. Any replacement remains shadow/read-only until an activation decision records parity and rollback.
- Consequences: generic materializer work must exclude the protected location contract unless activation proves sole ownership.

## TI-D004 — Wi-Fi and Cell schedules are opportunistic

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: describe in-process delays as periodic; require measured wake strategy
- Evidence: final design §9.4; current Wi-Fi/Cell runtimes expose attempt/refresh intervals but no wake-reliable device evidence
- Decision: product and telemetry language remains opportunistic. Wake reliability requires a separately measured device/OEM decision.
- Consequences: no sleep-cadence acceptance claim is permitted from unit tests.

## TI-D005 — Step corroboration policy

- Status: `DEFERRED_BY_EXPLICIT_DECISION_REQUIRED`
- Owner/date: user/product owner, unresolved
- Alternatives: remove corroboration; retain it as a visible, consented `CONTROL` demand/setting
- Evidence: `BackgroundTrackingApi` owns a hidden `StepActivityCorroborator` whenever confidence-based automatic detection is active, even when Steps capture is disabled
- Decision needed: choose removal or explicit control product behavior before broker rollout.
- Consequences: TI-200 is blocked at the automatic demand contract. Read-only analysis and independent work may continue.

## TI-D006 — Persistent target records require a forward migration decision

- Status: `ACCEPTED`
- Owner/date: user/release owner, 2026-08-21
- Alternatives: authorize additive Room v27→v28; delay persistent implementation. Folding new tables into v27 is no longer considered safe unless all existing v27 data is explicitly disposable.
- Evidence: `AppDatabase` is version 27, the stable filename is `main_database_v27`, and `activeMigrations` is empty. An existing v27 install would fail Room identity validation if its schema changed without a version bump. A previous v27 APK also cannot open a migrated v28 database; the backup store exports backups but does not automatically downgrade/restore them.
- Decision: the user's explicit `Authoritative SourcePolicy, consent epochs, and v28 migration` implementation scope authorizes the additive v27→v28 migration. Rollback remains feature-level inside a schema-capable v28 binary; v27 APK/database downgrade is not supported.
- Consequences: v28 may add inert policy schema and the nullable acquisition-plan policy binding. Exact v27 migration/device evidence and schema-capable rollback verification remain release gates; no external rollout is authorized.

## TI-D007 — Retention, ambient products, and mandatory control evidence

- Status: `PARTIALLY_RESOLVED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-21; retention/privacy details unresolved
- Alternatives: source/purpose-specific durations; ambient modes disabled; automatic control disabled until its minimized durable-evidence policy is approved
- Evidence: final design intentionally leaves durations open. All automatic scenarios need Activity control evidence, but R0 found no approved field set, TTL/state, export/deletion behavior, or diagnostic retention for that purpose. On 2026-08-21 the user explicitly confirmed that ambient persistence is wanted as a product capability.
- Decision: ambient history is in product scope and must not be removed by the proportionality rescope. Acquisition remains source-specific: Steps may use one selected system continuity provider; Location is passive/opportunistic; Wi-Fi uses successful passive scan-result broadcasts without default ambient active scans; Cell uses timestamp-qualified change callbacks without forced refresh/wake-cadence promises; cache reads are bootstrap/payload retrieval rather than acquisition; captured ambient Activity is independent from bounded, purpose-limited `CONTROL` evidence. Continuous ambient Pressure remains disabled unless explicitly approved. No legal copy or final retention duration is invented. TI-D079/TI-D080 refine the radio freshness boundary.
- Consequences: every enabled ambient source needs its own visible setting/consent, minimization, retention state, export, deletion/key-rotation behavior, truthful acquisition limits, between-session query, and UI explanation before production enablement. Implementation may be phased source by source and defaults remain off until each gate passes. Automatic-control evidence remains a separate purpose and cannot become ambient/captured history implicitly.

## TI-D008 — Cross-midnight counting, allocation, and traveling-zone identity

- Status: `DEFERRED_BY_EXPLICIT_DECISION_REQUIRED`
- Owner/date: product owner, unresolved
- Alternatives: count on start day; count each overlapping day as a continuation; omit count and show slices. For traveling sessions, key each fact by its capture-zone boundary or use a declared entry-zone rule.
- Evidence: `DailySummaryAggregator` currently counts on the start day and time-prorates metrics. R0 showed this fabricates allocations when event timing is asymmetric, and the target has no singular zone rule for a session that changes zones.
- Decision needed before final UI copy, session-count semantics, and traveling-zone day identity.
- Consequences: timestamped facts must split exactly. Legacy facts lacking defensible boundaries are `PARTIAL`/`UNAVAILABLE`, never prorated as invented detail. Current-device zone changes cannot move old facts.

## TI-D009 — Confirmed STILL/inactive defect

- Status: `MITIGATED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: preserve current branch order; fail disabled automation closed
- Evidence: `resolveAutoTrackingPreferenceAction()` returns `ENABLE` for `STILL`, inactive, permission granted because the generic enable branch precedes the disabled-mode fallback. No regression test covers that combination.
- Decision: disabled automatic mode must not enable the watcher. The branch now returns `NONE` for inactive `STILL` regardless of permission, and the missing truth-table test is present.
- Consequences: scoped regression and full tracker-engine unit/lint verification pass. This fixes the preference inversion but does not claim automation freshness/epoch/recovery safety.

## TI-D010 — Current radio identity handling is not accepted as the target privacy contract

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: retain deterministic unkeyed hashes and raw legacy UI/export; introduce rotatable per-install keyed identity and minimized products
- Evidence: `stableIdentifierToken` is truncated unkeyed SHA-256. Wi-Fi legacy rows/UI/export retain SSID/BSSID and Cell legacy rows/UI/export retain tower/subscription-related identifiers. Neither source has key rotation on deletion or relevant consent reset.
- Decision: new Wi-Fi/Cell canonical contracts must use per-install keyed HMAC with a key epoch and expose minimized counts, band/technology/quality mix, availability, and completeness by default. Existing raw history will not be destructively reinterpreted or deleted without explicit migration approval.
- Consequences: radio materializers remain blocked until consent/key epochs, deletion fencing, and no-resurrection tests exist. Legacy raw-data retirement is a separate explicit cutover/migration decision.

## TI-D011 — Treat current `eventCanonical` metadata as unsafe rollout state

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: trust force-promoted metadata; require output-contract activation and terminal product evidence
- Evidence: `RoomTrackingRolloutStateStore.load()` promotes all sources to `eventCanonical`, but Location effects have no production consumer and Cell/Wi-Fi have no terminal event-canonical materializer/day query.
- Decision: current rollout labels are not proof of canonical ownership. New writer activation must be output-contract scoped, generation-fenced, and default shadow/off until receipts, correction/deletion, rollback, and production-query assertions pass.
- Consequences: no source writer will be cut over on the basis of the existing rollout value alone.

## TI-D012 — Preserve baseline failures instead of absorbing them into this program

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: repair all repository lint findings; record the untouched baseline and verify scoped deltas
- Evidence: serialized unit tests and Room schema drift pass. Debug assembly succeeds. `lintDebug` fails on the untouched checkout with 43 app errors and 48 warnings; the first failure is a pre-existing Irish plural resource error.
- Decision: unrelated lint cleanup is outside the tracking-infrastructure scope. Every changed module must still pass its scoped tests/lint, and the final full lint result must be compared against this recorded baseline without introducing new findings.
- Consequences: the Phase 0 gate is not falsely marked passing, and future tracking changes cannot claim unrelated baseline lint as their regression or silently weaken lint rules.

## TI-D013 — Do not treat legacy Steps aggregates as replay-safe canonical output

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: rely on unique `StepInterval.sourceSignalId`; treat all effective Steps destinations as contribution-receipted
- Evidence: `EventTrackingFrameOutboxDispatcher` invokes its consumer before outer acknowledgement, while `SessionTrackerComponent` additively increments `SessionSegment.steps`. A crash after the increment and before acknowledgement can replay the same effective contribution. Disable/re-enable can also restore an old counter checkpoint and include the disabled interval.
- Decision: legacy Steps output may be used only for shadow comparison. Target activation requires capture-boundary snapshots plus durable contribution receipts and crash-point tests for every aggregate destination.
- Consequences: Steps remains `DEGRADED`; a positive delta and legacy day total alone cannot close the materialization gate.

## TI-D014 — Pressure trend is the only approved default Pressure product assumption

- Status: `ACCEPTED_FOR_CONTAINMENT`; final vertical-metric scope remains `DEFERRED_BY_EXPLICIT_DECISION_REQUIRED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: expose current standard-atmosphere altitude as elevation; expose raw Pressure trend/coverage; add a calibrated fused estimate
- Evidence: `EventTrackingFrameProjection` derives `altitudeM` with fixed 1013.25 hPa sea-level pressure and `pressure_sample.altitude_m` is non-null. Weather drift can change that value at fixed physical altitude. The plan forbids an undefended vertical claim.
- Decision: target Pressure history defaults to pressure trend, range, sample/window coverage, and completeness. No new elevation/ascent/vertical product may be activated unless calibration/fusion, provenance, error behavior, estimate labeling, and product scope are explicitly approved.
- Consequences: existing rows are preserved and not reinterpreted. Any destructive rewrite or canonical-table retirement remains a separate migration/writer decision.

## TI-D015 — Privacy safety state is monotonic and non-rollbackable

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: let `policy_v2` rollback return to legacy booleans; keep consent/deletion fences authoritative across execution rollback
- Evidence: legacy settings cannot represent source/purpose consent generations. Returning to them after revocation could re-register a source and resurrect capture.
- Decision: consent epochs, deletion/key epochs, and the monotonic effective policy revision are always-on safety state. A legacy execution adapter may consume the effective snapshot but cannot bypass or lower it.
- Consequences: technical rollback fails closed when the effective snapshot cannot be projected. Tests must revoke every source/purpose, toggle execution flags, reboot, and prove no registration/admission/attribution/export resurrection.

## TI-D016 — Writer activation and correction identity contract

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: rely on one `ACTIVE` row and revision-specific receipt keys; fence each destination transaction and separate identity from revision/generation
- Evidence: an outgoing writer can pass a pre-check, lose activation, and still commit after the incoming writer. A receipt key containing semantic revision permits `+10` and corrected `+12` to sum to `22`.
- Decision: every canonical destination/receipt transaction conditionally validates the output contract's active generation in the same Room transaction. `ContributionIdentity` excludes writer generation and semantic revision; current effective revision advances through immutable signed replace/retract transitions.
- Consequences: deterministic interleaving and `10→10→12→12→0→0` correction tests are prerequisites to source materializer activation.

## TI-D017 — Legacy purpose is unknown unless independently proven

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: infer capture from legacy logical IDs/`CAPTURED_REGISTRATION`; quarantine legacy facts from capture backfill
- Evidence: v27 WAL cannot distinguish automatic `CONTROL` from `SESSION_CAPTURE`, and current Activity ingress can attach a location-only session to control callbacks.
- Decision: migrated legacy observations default to `LEGACY_UNKNOWN` and are ineligible for capture materialization. Backfill is permitted only where independent source/settings/time evidence proves eligibility.
- Consequences: ambiguous history is `PARTIAL` or `UNAVAILABLE`; upgrade must never create captured Activity/Steps history from old control-only evidence.

## TI-D018 — Day queries and metrics are typed product state

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: `Flow<DayOverview?>` plus generic additive breakdown; sealed query state plus source-specific metric algebra
- Evidence: null cannot distinguish nothing enabled, no observation, materializing, unavailable, or failure. Unique radio sets, pressure trends, quality distributions, routes, and activity bands do not satisfy scalar `total = in + outside` addition.
- Decision: production day queries return a sealed state with per-source policy/capability/evidence/materialization/completeness/reason/repair data. Only declared additive metrics use additive breakdowns; other source products have typed merge/classification rules and stable explanation handles.
- Consequences: direct Room row and fake Compose tests cannot prove product wiring. Each only-X scenario must traverse the production graph into Dashboard, Days, Calendar, and day detail presenters.

## TI-D019 — Technical rollback is non-destructive and cannot hide retained facts

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: tombstone/rotate on operational rollback and return UI to trips-only legacy reads; preserve data and a compatible truthful reader
- Evidence: ambient rollback instructions conflicted with the runbook's preservation rule, and legacy History cannot display v2-only between-session facts.
- Decision: technical rollback stops new demands/actions/writes but does not delete, tombstone, or rotate keys. Presentation rollback retains a compatible v2 reader or a truthful retained-but-unavailable entry with explanation/export/deletion access.
- Consequences: only explicit consent revocation or authorized deletion performs destructive privacy actions.

## TI-D020 — Rollout evidence channel

- Status: `DEFERRED_BY_EXPLICIT_DECISION_REQUIRED`
- Owner/date: user/product/privacy owner, unresolved
- Alternatives: controlled device-lab evidence; user-initiated redacted local diagnostic bundles; separately approved privacy-designed remote evidence
- Evidence: repository instructions require local-only operation and prohibit remote analytics, while source gates call for OEM/cohort distributions and production-like latency calibration.
- Decision needed: choose and approve the evidence channel before cohort/canary claims.
- Consequences: no production p95, OEM distribution, or remote canary evidence is claimable from the current app. Host tests and device-lab work may continue.

## TI-D021 — Room is the effective SourcePolicy authority

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: keep Proto DataStore authoritative; introduce a separate consent DataStore; make the v28 Room ledger authoritative and retain DataStore only as a compatibility mirror
- Evidence: planning, service, status, FGS, and settings already consume `TrackingParamsRepository`; v28 must also bind policy revision to Room-owned acquisition/session records. Collected-data deletion clears rows rather than deleting the database, allowing monotonic policy/consent fences to survive. The legacy repository now exposes an explicit durable-migration readiness boundary.
- Decision: the active six-source Room policy revision is the only effective runtime authority. Proto DataStore supplies the verified one-time bootstrap and non-source preferences, then mirrors the Room projection for compatibility. A Boolean and frequency are normalized as one mutation; a false Boolean and non-`OFF` frequency cannot revive capture. `CONTROL` and `AMBIENT_PRODUCT` begin denied.
- Consequences: Room commits and consent epochs precede the compatibility mirror; mutations serialize and reapply against the latest active snapshot; startup repairs a stale mirror from Room. Any schema-capable rollback adapter must still consume the monotonic Room safety state. Purpose-stamped provider ownership, immutable session manifests, retention durations, and observation/materializer fences remain later workstreams.

## TI-D022 — Automatic controls require their own policy purpose and consent epoch

- Status: `ACCEPTED_FOR_CONTAINMENT`; step-corroboration membership and control-data retention remain `DEFERRED_BY_EXPLICIT_DECISION_REQUIRED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: let the legacy automatic-mode preference register Activity and Steps; infer control permission from a captured-source consent; require an independently eligible `CONTROL` epoch for each physical control registration
- Evidence: the legacy background API registered Activity whenever automatic mode was enabled and could register Steps for corroboration without an authoritative source/purpose decision. The v28 bootstrap deliberately imports only verified capture intent and denies new control/ambient purposes. Reusing capture consent would violate purpose separation and make an upgrade silently grant new processing authority.
- Decision: automatic Activity registration requires an eligible Activity `CONTROL` decision and epoch. Optional step corroboration independently requires an eligible Steps `CONTROL` decision and epoch. Missing, corrupt, unavailable, revoked, or superseded control policy fails registration closed and stops legacy automatic control use; it does not stop an independently started manual session.
- Consequences: automatic tracking remains unavailable after migration until explicit control decisions are granted. No retention duration, export behavior, or final product copy is inferred. Whether step corroboration remains part of automation and the minimized control-evidence retention/export/deletion contract still require user/product/privacy direction before TI-200 can pass.

## TI-D023 — Expand the unreleased v28 migration for manifests and lifecycle intent

- Status: `ACCEPTED`
- Owner/date: user and lead orchestrator, 2026-08-21
- Alternatives: freeze the policy-only v28 and create v29; expand the local unreleased/untracked v28 schema
- Evidence: the user explicitly added immutable manifests and durable lifecycle intent to the current scope. Repository evidence shows `28.json` is newly generated and untracked, with no authorized external release or device rollout from this worktree.
- Decision: add the manifest, lifecycle-intent, desired-action, service-run fencing, and WAL identity columns/tables to v27→v28. If evidence appears that any external database already opened the earlier v28 shape, this decision is invalid and a v29 migration is required before rollout.
- Consequences: the single exact v27→v28 migration test must cover both policy and lifecycle additions. Database downgrade remains prohibited, and device execution is still a gate.

## TI-D024 — Separate immutable intent from mutable execution acknowledgement

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: mutate one session row as both request and result; append manifest/intent versions and reconcile mutable action rows
- Evidence: policy changes, stop/failure, crash recovery, and external Android actions need an auditable requested state that is not overwritten by later acknowledgements.
- Decision: session manifests and lifecycle intents are append-only facts. Desired-action rows hold mutable execution/acknowledgement state and must eventually be reconciled idempotently under boot/generation/revision CAS. Mutable logical-session/service-run rows are current indexes, not the historical source of intent.
- Consequences: starts/reconfigurations/failures now append immutable versions. Phase 1 remains blocked until service/FGS/control actions and a recovery reconciler obey this split.

## TI-D025 — Migrated lifecycle state is inert and never silently resumed

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: synthesize v28 manifests/actions from v27 active rows; mark legacy state unknown and require explicit safe recovery/finalization
- Evidence: v27 rows lack purpose, consent generation, manifest revision, automation epoch, boot identity, and accepted external-action evidence. Synthesizing those fields would fabricate authorization and lifecycle proof.
- Decision: migration preserves legacy rows but labels new lifecycle fields `LEGACY_UNKNOWN`/zero/null and creates no manifest, intent, action, or lease records. Such entries are not eligible for silent v2 recovery and must be conservatively finalized/interrupted or explicitly migrated with independent evidence.
- Consequences: upgrade cannot manufacture an active session. Exact recovery behavior and device tests remain required before enabling lifecycle v2.

## TI-D026 — Automatic production entry remains contained until a durable trigger gateway exists

- Status: `ACCEPTED_FOR_CONTAINMENT`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: accept an unstamped service intent; infer freshness from current motion state; fail closed until the trigger envelope is durable and consumed transactionally
- Evidence: the coordinator validates a boot/elapsed/expiry/automation envelope, but `TrackerServiceApi` and `TrackerServiceSourceSession` do not carry one and Android service launch currently happens first.
- Decision: do not weaken the coordinator validator. Production automatic v2 starts remain unavailable until trigger identity, control registration generation, policy/consent purpose, current automation epoch, consumption state, and Android action identity are durably connected before service launch.
- Consequences: automatic-mode tests must currently expect fail-closed containment, not successful tracking. This is a structural blocker, not a product decision request.

## TI-D027 — Expand unreleased v28 with durable broker demand and registration generations

- Status: `ACCEPTED`
- Owner/date: user and lead orchestrator, 2026-08-21
- Alternatives: keep demands process-local; create a later migration; extend the unreleased v28 schema already authorized by TI-D023
- Evidence: the user added the purpose-aware broker/generation slice while `28.json` remains local and untracked. Session manifests already contain the policy, consent, purpose, QoS, and logical identity needed to create durable demands atomically with intent.
- Decision: v28 also stores append-only demand identities, mutable retirement boundaries, immutable physical registration generations, and the exact eligibility vector for each generation. WAL rows carry the accepted generation's purpose mask and eligibility fingerprint.
- Consequences: exact v27→v28 validation covers the new inert tables/columns. Any evidence that the earlier v28 shape escaped this worktree invalidates this decision and requires v29.

## TI-D028 — Demand eligibility changes rotate registration identity

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: reuse a physical generation when provider configuration is unchanged; mutate eligibility on an existing generation; reserve a new immutable generation
- Evidence: capture can be added or revoked while the provider's interval/transitions remain identical. Reusing the callback identity would make control-only and captured callbacks indistinguishable.
- Decision: the broker hashes the complete active demand vector and reserves a globally monotonic per-source registration generation before the provider side effect. Provider acceptance changes only `RESERVED` to `ACTIVE`; superseded, failed, or removed generations cannot admit callbacks.
- Consequences: a QoS-equivalent owner/purpose change still rotates generation. Provider adapters must compare immutable callback tokens and the WAL admission transaction revalidates source instance, boot domain, data epoch, mask, and fingerprint.

## TI-D029 — Never relabel delayed callbacks across an effective boundary

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: stamp callbacks from the runtime's latest mutable manifest/registration; retain the callback's immutable identity and reject stale delivery
- Evidence: Location, Wi-Fi, Cell, Steps, Pressure, and Activity can deliver callbacks after unregister/reconfigure. A mutable sink or listener can otherwise turn an old observation into new consent/manifest evidence.
- Decision: every installed provider callback/listener captures its registration identity and immutable policy/manifest sink. A callback that no longer matches the installed generation is rejected; accumulators cannot carry partial state across an eligibility generation.
- Consequences: conservative boundary loss is allowed and must be reported as partial/degraded where material; attribution under the wrong generation is not. Eligible drain/handoff optimization requires a later explicit temporal contract and tests.

## TI-D030 — Retiring demands remain eligible through their callback drain barrier

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: invalidate a demand as soon as stop begins; keep it fully active indefinitely; mark it `RETIRING` and retain admission only until the source callback barrier is durably drained
- Evidence: immediate invalidation rejects observations that were captured under valid pre-stop intent but reach Room during provider teardown. The lifecycle contract already permits pre-cutoff evidence to drain while rejecting post-cutoff evidence.
- Decision: `RETIRING` demands remain part of the immutable registration eligibility lookup until the source-specific callback barrier completes and the demand becomes `RETIRED`. The observation must still satisfy its original generation, purpose, boot/effective-time boundary, manifest cutoff, consent, and policy fences.
- Consequences: teardown may durably admit qualified pre-cutoff observations without relabeling them. A provider adapter cannot report stop complete merely because unregister was requested; device interleave tests remain required.

## TI-D031 — Registration eligibility snapshots purpose activation time and boot domain

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: validate only the currently active purpose/consent epoch; infer freshness from receipt time; snapshot the demand's boot-aware monotonic activation boundary into each immutable eligibility row
- Evidence: a delayed event observed before a control or ambient grant could arrive through a new registration after consent, and receipt time cannot prove it was observed under the new authority. Durable demands already record request boot and elapsed time.
- Decision: every registration eligibility row copies the demand's effective boot ID and elapsed-realtime boundary. Generic and Activity ingress reject cross-boot or pre-boundary observations before WAL insertion or sequence allocation.
- Consequences: delayed pre-grant and pre-reboot evidence fails closed even if the current consent epoch is eligible. Provider observations that lack a defensible observed-time clock must remain degraded/unqualified rather than substitute receipt time silently.

## TI-D032 — Automatic effects require explicit `CONTROL_AUTOSTART` eligibility

- Status: `ACCEPTED_FOR_CONTAINMENT`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: project every Activity callback; let captured Activity implicitly drive automation; project only callbacks whose immutable registration mask includes `CONTROL_AUTOSTART`
- Evidence: a shared Activity registration may serve captured history and control. A capture-only Activity event must not become an automatic-start/stop side effect under purpose separation.
- Decision: the automation projection ignores generations that lack `CONTROL_AUTOSTART`; automatic monitoring clears its in-memory owner when the broker cannot create an eligible control demand.
- Consequences: capture-only Activity cannot drive automation. Phase 2 remains blocked until the projected side effect also persists and revalidates the complete trigger, boot, registration, consent, policy, automation epoch, and expiry envelope at actuation time.

## TI-D033 — Expand unreleased v28 additively for the Phase 3 audit ledger

- Status: `SUPERSEDED_BY_TI-D049_AND_TI-D051`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: reserve a later schema version; destructively reinterpret v28; add the observation/attribution/activation/receipt records to the still-unreleased v28 migration and generated schema
- Evidence: v28 is local and unshipped, the migration is already additive from v27, and the new records do not replace or activate a legacy canonical writer. The exact `MigrationTestHelper` path compiles but cannot run without a connected Android device.
- Decision: this local-only foundation was permissible while v28 remained unshipped, but its generic ledger portion is no longer retained. TI-D049 authorizes reshaping v28 and TI-D051 replaces the unused generic layer with typed source facts and recomputation.
- Consequences: the required untracked `28.json` must ship with any authorized commit. Device migration/rollback rehearsal remains a blocking gate; the historical generic tables have no compatibility promise.

## TI-D034 — WAL session identity is admission provenance, not canonical membership

- Status: `ACCEPTED_WITH_BLOCKER`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: rewrite a raw event when another session claims it; treat its embedded session ID as sole membership; preserve the immutable envelope and append zero-to-many attribution decisions
- Evidence: one provider registration may serve multiple logical consumers, and retry/correction cannot safely rewrite the raw fact. The new attribution/head model proves two session associations without changing the WAL row.
- Decision: preserve WAL logical/run identity only as acquisition/admission provenance. Canonical session membership is append-only attribution with supersession/retraction.
- Consequences: the current ingress is still session-bound and can discard a fact before another eligible session is considered. Global observation admission before association is required before this decision is fully realized.

## TI-D035 — Writer activation binds source, purpose, counting contract, and retained floor; production activation remains prohibited

- Status: `SUPERSEDED_BY_TI-D051`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: infer writer scope at each callback; activate on registration; reserve a durable shadow generation with explicit contract metadata and atomically change the owner pointer
- Evidence: the Room transaction fence rejects retired generations and preserves a single owner per declared output contract. Fresh R1 review proved that free-form contract names still do not identify an actual destination and legacy writers are outside the owner table.
- Decision: the generic activation tables were removed before release because they had no production caller. A source may add a narrow writer-generation fence only when an actual coexistence/cutover requires it and its destination-level uniqueness can be tested.
- Consequences: Location remains on its protected established writer. No replacement writer is authorized; source-specific idempotence and query evidence precede any new activation machinery.

## TI-D036 — Contribution identity excludes writer generation and semantic revision

- Status: `SUPERSEDED_IN_IMPLEMENTATION_BY_TI-D051`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: include writer/revision in the logical key; use destination row uniqueness only; normalize stable fact/contract/partition identity and store revision/writer as audit metadata
- Evidence: including generation or revision makes retained-floor replay after cutover look novel and lets one logical fact change an aggregate twice. Focused tests cover exact duplicate, replacement, retraction, delayed older revision, same-revision drift, and identical replay after writer handoff.
- Decision: stable logical identity remains the invariant, but the generic receipt/head representation is removed. The first source vertical must enforce it through a typed unique fact identity and deterministic recomputation/correction.
- Consequences: a generic receipt platform may return only for a demonstrated non-recomputable destination. Typed output algebra and deterministic counting ownership remain required.

## TI-D037 — Retraction may outlive raw observation retention, but deletion requires a scoped orchestrator

- Status: `SUPERSEDED_IN_IMPLEMENTATION_BY_TI-D051`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: retain all WAL forever; forbid correction after pruning; retain minimal receipt/attribution provenance and allow zero-sum RETRACT while deletion fences block additions
- Evidence: a focused test prunes the raw observation and balances the retained contribution to zero. Consent revocation and tombstones reject new APPLY while RETRACT remains possible. Full data deletion advances the collected-data epoch and retains the global tombstone/activation configuration.
- Decision: the zero-sum invariant remains, but the generic receipt/tombstone implementation is removed. Typed retained facts plus source/purpose deletion fences must support deterministic recomputation without resurrecting deleted inputs.
- Consequences: no production scoped-deletion enumerator yet atomically fences admission, removes source facts/caches, rotates applicable keys, and dirties affected days.

## TI-D038 — Migrated unqualified WAL cannot be represented as processable genesis

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: trust migrated rows with a fabricated checksum; silently skip them while claiming genesis; label them `LEGACY_UNKNOWN` and refuse historical activation across the ambiguous prefix
- Evidence: v27 did not persist the complete immutable envelope identity now required for attribution/materialization. Focused tests prove `FROM_GENESIS` and `FROM_RETAINED_FLOOR` refuse a legacy-unqualified row while `FROM_NOW` may register shadow after it.
- Decision: preserve migrated rows as `LEGACY_UNKNOWN`; they are not eligible evidence and make affected historical activation explicitly unavailable/partial.
- Consequences: product completeness must expose the unavailable prefix. Backfill cannot wait forever for, invent, or silently count pruned/unqualified ordinals.

## TI-D039 — The pre-product wiring boundary permits only the existing policy-safety settings surface

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: prohibit every production UI change until Phase 5; allow arbitrary product prototypes; permit only the Phase 1 authority availability/error/repair surface while prohibiting materializer/day-product wiring
- Evidence: settings, planning, status, and service legality must agree with the effective policy snapshot and fail closed when authority is unavailable. Repository search finds no production caller of the Phase 3 attribution/activation/receipt repositories and no `DayOverview` or `DayHistoryRepository` implementation.
- Decision: the existing tracking-settings authority/error surface is an allowed Phase 1 safety exception. No source history, metric, day, Dashboard, Calendar, or materializer result may be production-wired before the R1b blockers are resolved.
- Consequences: R1b remains a hard pre-wiring gate without hiding actionable policy failure from users.

## TI-D040 — Persisted payload integrity must authenticate bytes at every trust boundary

- Status: `MITIGATED_FOR_WAL`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: trust a stored checksum string; include raw bytes in every wider envelope hash; recompute the payload checksum before decode/use and bind the verified checksum into the wider identity
- Evidence: WAL verification now recomputes the payload checksum from bytes, binds it into the wider envelope identity, and rejects a valid-but-mutated encoding before decode. Generic retained receipts were removed under TI-D051.
- Decision: recompute payload hashes before decode, attribution, APPLY, correction, or RETRACT use. Verify prior receipt payload against its receipt/head checksum before destination mutation. Integrity failures quarantine the affected raw/contract partition without invoking consumer code.
- Consequences: the valid-encoding mutation and raw-quarantine tests pass. Future typed retained facts must apply the same byte-authentication rule at any serialized trust boundary.

## TI-D041 — Counting ownership uses one global decision revision and atomically moves derived scopes

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: optional caller-selected counting heads keyed by day; target-local revisions with manual retractions; one centrally derived decision per observation/counting contract with atomic old/new scope movement
- Evidence: a corrected day inserts a second counting-head key without retiring the previous day, and ownership transfer compares unrelated target-local revisions. Both make totals dependent on caller order.
- Decision: additive contracts require a non-optional registered counting algebra. One global counting decision/revision selects the effective classification and derived day scope; correction atomically retracts the old scope, applies the new scope, and dirties both days.
- Consequences: source materializers cannot be wired until order-independent overlap, day move, retry, and crash tests pass.

## TI-D042 — Force-stop recovery retires session-owned demands and eligibility with terminal lifecycle state

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: finalize only session/run rows; clear all broker state indiscriminately; atomically finalize the session and retire its owned demands/actions while fencing prior registration eligibility
- Evidence: current force-stop finalization terminates Room session/run state but leaves ACTIVE session demands consumed by the Activity arbiter, creating ghost capture eligibility and rejecting later valid sessions.
- Decision: one fenced recovery transaction must finalize the logical session/runs, retire `session:<logicalId>` demands, supersede pending lifecycle actions, and make prior registration eligibility stale before provider reconciliation.
- Consequences: control/ambient demands from other owners survive. Recovery tests must prove stale callbacks fail and a new session can capture normally.

## TI-D043 — Structural day identity must account for timestamp uncertainty

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: derive one day from the stored wall-time point; assign by receipt time/current zone; derive the observation's wall-time interval in its captured structural zone and preserve ambiguity
- Evidence: WAL stores `wallTimeUncertaintyMs`, but attribution currently converts only `wallTimeMs` and labels the result `KNOWN`. An observation at 23:59:59.900 ±250 ms spans two days and cannot honestly increment either as an exact fact.
- Decision: day qualification derives an interval from wall time plus uncertainty in the immutable attribution zone. If the interval crosses a day or an unresolved clock/DST boundary, the fact remains ambiguous/partial until a defensible timestamped subfact or correction resolves it.
- Consequences: uncertainty, DST gap/overlap, cross-midnight, and current-zone-change tests gate counting and product query. Missing certainty never becomes a fabricated exact day or zero.

## TI-D044 — Coroutine cancellation is control flow, never poison or retry evidence

- Status: `ACCEPTED`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: map cancellation to storage/runtime failure; count it as a poison attempt; rethrow cancellation before failure classification and use only a narrowly documented non-cancellable fence when required
- Evidence: the Phase 3 transaction repository correctly rethrows `CancellationException`, but production ingress, Activity recovery, projection, coordinator, and retention paths use broad `runCatching`/`Exception` handling. Projection cancellation can consume attempts and advance a terminal checkpoint without applying the fact.
- Decision: every asynchronous acquisition/projection/materialization/recovery boundary rethrows cancellation before recording failure, advancing a cursor, scheduling retry, or publishing lifecycle status.
- Consequences: focused cancellation injection is required at codec, Room admission, runtime start/reconfigure, projection apply, recovery drain, and retention boundaries.

## TI-D045 — Freeze structural expansion at the proportionality gate

- Status: `ACCEPTED_FOR_CONTAINMENT`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: continue the original Phase 3→5 dependency path; immediately delete generic infrastructure; freeze expansion while the unshipped schema and minimum product slice are re-decided
- Evidence: three fresh product, architecture, and delivery adversaries independently concluded `BLOCK / RESCOPE`. v27 deliberately retained 51 production-owned tables and rejected permanent generic frameworks; v28 currently adds 23 tables before any new source materializer, dirty-day path, `DayHistoryRepository`, or source-day product caller exists. The tracked diff is 5,648 additions/576 deletions across 62 files, and the app supports one active logical session with all ambient products disabled or unresolved.
- Decision: do not add another schema table, source materializer, ambient path, or production day-history UI until each retained abstraction has a named next-milestone production caller and the v28 compatibility boundary is resolved. This is a containment decision, not authorization to delete user data, rewrite an escaped schema, or weaken policy/generation/privacy fencing.
- Consequences: the next work is release-scope reconciliation and concrete lifecycle/migration blockers, followed by one thin Steps vertical slice. Generic Phase 3 and full DayOverview remain unused/off. If the current v28 shape escaped this worktree, reductions move additively to v29.

## TI-D046 — Database merge import excludes control-plane state

- Status: `MITIGATED_LOCALLY`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: import every matching Room table; validate and replace an entire closed/empty database; merge only an explicit allowlist of user-owned facts
- Evidence: production merge import now uses an explicit 24-table user-fact allowlist. A hostile-backup test seeds foreign `source_policy_authority` and ACTIVE `source_demand` rows and proves they are excluded while an allowed Activity fact imports.
- Decision: data merge uses an explicit user-fact allowlist and never imports runtime authority, control, lifecycle, rollout, materializer-control, cursor, or lease rows. A full backup restore is a distinct closed-database operation that may replace only an empty target after schema and monotonic safety-state validation.
- Consequences: the control-plane import blocker is closed locally. Derived recomputation after imported source facts and device-level restore/upgrade coverage remain required before rollout.

## TI-D047 — Product and verification breadth is tiered, not enterprise-fleet symmetric

- Status: `PARTIALLY_RESOLVED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22; day UI/verification budget unresolved
- Alternatives: keep all six sources, ambient modes, Days-first UI, telemetry cohorts, OEM matrices, and soak as one GA gate; approve a release-sized tiered product contract
- Evidence: the app is explicitly local-only with no remote telemetry; CI has unit/lint/assembly/schema checks but no emulator/device farm; v27 intentionally rejected exhaustive OEM matrices and arbitrary enterprise targets. Existing workflows are session/trip, map, Today/Calendar, export, retention, and deletion. Location/Activity/Steps are primary onboarding/default behavior, Pressure is an advanced session source, and Wi-Fi/Cell default off.
- Decision: ambient persistence is source-specific. Implement it where a source produces useful, privacy-appropriate day history under an honest Android acquisition promise; do not require it where collection would be misleading or disproportionate. Steps is the first proving vertical. Location, Activity, Wi-Fi and Cell remain independently eligible behind explicit product/consent gates and source-specific acquisition limits. Continuous ambient Pressure remains disabled without a separate product case. Days-first versus incremental history UI and the representative-device verification budget still require resolution.
- Consequences: ambient architecture may proceed only behind per-source default-off gates defined by TI-D007. Ambient eligibility does not imply continuous coverage: gaps must be reported as `PARTIAL` or `UNAVAILABLE`. Full DayOverview/M3 journal, remote canary/SLO machinery, exhaustive OEM/soak coverage, and equal all-at-once six-source rollout remain frozen rather than silently removed or treated as release blockers.

## TI-D048 — Automatic-control upgrade semantics preserve explicit automatic intent

- Status: `PARTIALLY_SUPERSEDED_BY_TI-D055`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: treat the existing explicit automatic-tracking preference as Activity `CONTROL` authorization on migration; require a new explicit grant flow; continue failing automatic tracking closed after upgrade
- Evidence: automatic tracking is a shipped, explicit user preference. v28 bootstrap now grants nonpersistent Activity `CONTROL` only when that preference is enabled; automatic-mode settings transitions append Activity-control grant/revoke epochs in the same policy transaction and do not alter Activity capture consent.
- Decision: treat the existing enabled automatic-tracking preference as Activity `CONTROL` authorization on v28 migration. Do not infer Activity capture or Step corroboration consent from it.
- Consequences: focused upgrade/settings tests pass for preserving automatic intent without granting Activity capture. TI-D055 supersedes only the nonpersistent admission choice: authorized control callbacks must be durably admitted into a bounded operational class while remaining outside captured history. Exact populated device migration and end-to-end proof that control-only Activity never appears in captured history remain release gates.

## TI-D049 — The current v28 schema never shipped and may be regenerated

- Status: `ACCEPTED`
- Owner/date: user/release owner and lead orchestrator, 2026-08-22
- Alternatives: preserve the current local 74-table v28 and simplify in v29; reshape the unreleased v28 migration/schema before it becomes a compatibility contract
- Evidence: the user explicitly confirmed that v28 was never released. Repository state also shows `28.json` is local/untracked and prior execution artifacts contain no device or external rollout evidence.
- Decision: reshape and regenerate v27→v28 in place; do not create v29 merely to preserve the current unused local v28 shape. This does not authorize reinterpretation of v27 user facts or omission of exact populated migration/open/reopen verification.
- Consequences: TI-D023/TI-D027/TI-D033 no longer require retaining every current addition. Retained v28 tables must pass a production-ownership audit; removed local shapes have no public compatibility promise.

## TI-D050 — Eligible ambient persistence requires sessionless provenance, not a fabricated session

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: wrap ambient facts in synthetic sessions; admit one sessionless provider fact and classify it using purpose/session intent; persist generic zero-to-many attribution for every fact
- Evidence: the user confirmed ambient persistence is required where it makes product sense. `source_event_wal.logical_tracking_id` is already nullable, source runtimes can emit sessionless candidates, and the app currently permits one active logical session. Synthetic sessions would corrupt product semantics; session-bound admission can drop a shared ambient fact during session teardown.
- Decision: admit one immutable sessionless observation before product classification. Retain source, registration generation/eligibility, purpose, consent/policy epoch, observed/received clocks, structural time/zone and integrity. Session membership is optional and never fabricated. The shipping representation may be a typed fact plus deterministic intent-interval classification; generic zero-to-many attribution remains unapproved until concurrent logical consumers demonstrate it.
- Consequences: ambient-only days must be queryable without a trip. Control-only Activity remains excluded. Every released typed fact/export/deletion path must retain or resolve its purpose provenance.

## TI-D051 — Remove the unused generic Phase 3 platform from v28

- Status: `ACCEPTED_AND_IMPLEMENTED_LOCALLY`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: retain all 12 generic tables; remove them and use typed idempotent facts plus recomputation; retain a smaller subset after a concrete destination proves need
- Evidence: three fresh R2 reviewers independently found no non-test production caller for `ObservationAttributionRepository`, `WriterActivationRepository`, or `MaterializationTransactionRepository`. Existing typed source tables carry stable source-signal identities and current derived day summaries are recomputable. Ambient requirements make provenance/deletion fences necessary but do not by themselves require free-form writer activation, contribution algebra, counting heads, or partition cursors.
- Decision: remove `observation_ledger_state`, `observation_attribution`, `observation_attribution_head`, `observation_counting_head`, `materializer_activation`, `canonical_writer_owner`, `materialization_tombstone`, `contribution_receipt`, `contribution_head`, `contribution_correction`, `materializer_partition_state`, and `materializer_failure` from unreleased v28. Replace them only with typed provenance fields, source-specific idempotent writes/recomputation, and a narrow source/purpose deletion fence proven by the first ambient vertical.
- Consequences: the entities, DAO/repositories, migration SQL, and synthetic-only tests are removed; this initially reduced v28 from 74 to 62 entities, and TI-D052's lease unification reduces it to 61. Location keeps its existing sole writer. Generic receipts/activation may return only for a demonstrated non-recomputable destination or real in-app writer coexistence.

## TI-D052 — State-model reduction requires an executable recovery comparison

- Status: `ACCEPTED_AND_EXECUTED_LOCALLY`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: retain all 11 current policy/lifecycle/broker additions; remove the five lifecycle additions and keep three broker tables; collapse to authority+policy, one compact immutable session intent, one desired-action outbox, one boot-aware lease mechanism, one durable registration-generation record, and one narrow deletion fence
- Evidence: R2 reviewers disagreed materially. The test-only `LeanTrackingRecoveryModelTest` proves 8 scenarios with policy plus one immutable intent stream, one action outbox, one boot-aware lease and one generation row carrying a bounded eligibility vector. The focused comparison plus current coordinator tests passes 21 tests (`BUILD SUCCESSFUL in 2m 14s`). Static ownership inspection also shows that normalized manifest/source/intent, demand, generation and eligibility rows all have real production SQL callers today.
- Decision: keep the normalized manifest/source/intent rows, desired-action outbox, durable demands, provider generations and eligibility rows for this release; replacing their queryable rows with encoded vectors would create substantial churn before a product vertical. Collapse only `lifecycle_reconcile_lease` into the existing `source_coordinator_lease`, upgrade that shared table to boot-aware elapsed-time generations, and use distinct lease names for projection and lifecycle reconciliation.
- Consequences: v28 had 61 entities at this decision point. TI-D088 later adds only two concrete released-v27 recovery records, bringing the current schema to 63. `source_coordinator_lease` retains v27's diagnostic wall times but authorization uses boot/process identity, elapsed realtime and generation. The deeper compact model remains test evidence, not production code, and may be reconsidered only when Ambient Steps demonstrates a concrete maintenance or product benefit.

## TI-D053 — Ambient Steps is the first proving vertical; its continuity promise is unresolved

- Status: `PARTIALLY_ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22; continuity promise unresolved
- Alternatives: begin with Activity; begin with Steps; implement all ambient sources together
- Evidence: Steps already has a sessionless runtime candidate, generation-bound counter accumulator, stable canonical identity, day/range queries and existing Today/History value. It avoids radio identifiers, background-location permission and multi-SIM complexity. Android process death/reboot can still create unavoidable counter-observation gaps unless a user-visible legal foreground lifecycle is approved.
- Decision: sequence Ambient Steps first after schema/import/automatic-upgrade safety. Do not implement the other ambient sources in parallel. The product owner must choose whether the promise is opportunistic with explicit completeness or continuous/all-day with a visible foreground-service/notification and battery contract.
- Consequences: the first vertical must expose `Loading`, `Unavailable`, `NoData`, `Partial`, and `Ready`; support portable export, full deletion and per-source ambient deletion; and remain useful without fabricating complete daily totals.

## TI-D054 — Every source must support sole-source capture

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: require Location or another primary source for every tracking entry; support only selected source combinations; make each of the six sources independently useful as the sole captured source
- Evidence: the user explicitly requires any one of Location, Wi-Fi, Cell, Activity, Steps, or Pressure to work while all other sources are disabled. The source contracts already define source-qualified `RECORDING` evidence and product output for each family.
- Decision: manual only-X captures and registers only X. Automatic only-X captures only X and may register exactly the separately declared automatic-control demands needed to start/stop safely. Control callbacks are durably admitted as purpose-limited operational evidence, but do not become captured/product history unless the source was independently eligible for capture or ambient product use at observed time. A missing optional source may degrade fidelity but cannot invalidate X when X's own minimum contract is satisfied.
- Consequences: every source gets an independent provider-to-durable-fact-to-production-query acceptance path. Location metrics cannot be a hidden prerequisite for day/session visibility. Zero-source requests fail closed; unsupported or unavailable X is reported honestly rather than silently substituted with another captured source.

## TI-D055 — Acquisition authority, not persistence, is the battery and privacy boundary

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: drop callbacks unless a persistence flag is enabled; allow an enrichment request to start another provider; durably represent every source-qualified delivery from an authorized active registration and make enrichment piggyback-only
- Evidence: the user clarified that when an authorized provider runs its observations must persist, while any provider mode with material battery cost must remain optional and can never be required for another source to work. The repository currently couples admission to `controlPersistenceEligible`/`ambientPersistenceEligible`, even though registration identity already carries the exact purpose vector and generation. Current Wi-Fi and Cell runtimes can actively request radio work, while their callback/broadcast modes are cheaper; Location and hardware sensors likewise have materially different cost profiles within one source family.
- Decision: every structurally valid source-qualified delivery matching current provider and authorization generations receives a durable representation before attribution or product use. The source contract defines the quality-preserving unit: Location provider batch/fixes, Wi-Fi or Cell snapshot header with children/reference, Activity PendingIntent delivery batch, Step counter boundary, or Pressure statistical window. Attempts, timer ticks, permission checks, stale generations, malformed payloads, and cached data without defensible provider time are not observations. Capture- and ambient-eligible units materialize as typed product facts. Control-only units remain durable purpose-limited operational evidence with an explicit bounded retention class and never silently become source history or product enrichment. Cross-source enrichment cannot create or keep alive a `SourceDemand`; it may use only already-persisted observations whose observed-time eligibility includes `SESSION_CAPTURE` or `AMBIENT_PRODUCT`. Provider cost is classified per acquisition mode, not once per source.
- Consequences: remove persistence eligibility as a pre-WAL admission gate and replace it with purpose/use/retention eligibility after durable admission. Source policy must expose explicit acquisition ceilings for capture, ambient, and control. No source waits for optional context and no expensive provider starts merely to improve another source. Wi-Fi without Location remains a complete Wi-Fi observation; when independently enabled Location observations exist, a versioned event-time resolver may attach approximate location and uncertainty without rewriting either raw fact. This decision does not grant broader product visibility, indefinite retention, or export rights to control-only evidence.

## TI-D056 — Durable admission is source-qualified and batched without reducing approved product quality

- Status: `ACCEPTED_AFTER_R3`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: one Room transaction and projection drain per raw callback; drop repeated callbacks after receipt; define a lossless or quality-preserving source-native admission unit and batch its transaction/projection wakeup
- Evidence: the Android adversary found responsive Pressure can checkpoint at 20 Hz and then separately admit windows; Activity currently admits each item in a PendingIntent delivery using separate transactions; each admission synchronously triggers projection draining; bounded runtime queues can still drop under storage stalls. The product adversary correctly requires fresh empty and unchanged Wi-Fi/Cell deliveries to remain distinguishable from no callback.
- Decision: persist one compact source-qualified unit per provider delivery/product boundary using bulk validation, contiguous sequence allocation, one transaction, and one conflated asynchronous drain signal. Location retains every qualified fix inside its provider batch. Wi-Fi/Cell retain a callback/snapshot header even when empty or unchanged; unchanged headers may reference an earlier payload rather than duplicate children. Activity retains the complete delivered batch. Steps retain monotonic boundaries and explicit gaps. Pressure retains completed windows with count, range, mean/variance/trend, time coverage, and gap evidence; raw per-sample archival is outside the approved pressure-trend contract.
- Consequences: no valid qualified observation is dropped because it is control-only, ambient, repeated, or inconvenient. Batching changes transaction granularity, not the approved source quality. Attempts/ticks use compact bounded diagnostics rather than immutable product observations. Stress tests must inject slow Room and assert bounded memory, explicit gaps, transaction/write budgets, and no unreported qualified-unit loss.

## TI-D057 — Physical provider configuration and authorization revisions have separate lifetimes

- Status: `ACCEPTED_AFTER_R3`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: restart every provider whenever demand identity/manifest changes; require universal make-before-break overlap; preserve one physical registration while rotating immutable authorization intervals and reconfigure only when the physical plan changes
- Evidence: current eligibility fingerprints include demand/session/manifest identity, global plan revisions are rebuilt during motion changes, and `SourceRegistrationRepository.begin()` retires the old generation before replacement acceptance. Steps lose baselines and Pressure partial windows on generation changes. Android provider APIs do not share one universal overlap/update contract.
- Decision: track a physical configuration generation separately from an immutable authorization revision. Purpose/consent/session intervals may rotate without restarting an unchanged provider. Reconfigure only the affected source when its normalized physical plan changes. Handoff is provider-specific: update in place when supported; make-before-break only when the API can prove it safely; otherwise break-before-make with an explicit completeness gap. Consent/purpose revocation fences use immediately regardless of provider cleanup success.
- Consequences: unrelated Wi-Fi or manifest changes cannot reset Steps, Pressure, Location, or Activity. Callbacks carry both identities. Tests count physical starts/stops across policy, manifest, motion, and unrelated-source changes and inject replacement failure, late callbacks, reboot, and revocation.

## TI-D058 — Direct demands own a minimum useful quality floor

- Status: `ACCEPTED_AFTER_R3`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: allow the motion optimizer to disable/reduce any enabled plan; disable adaptation entirely; let each direct demand declare a quality floor and whether adaptive reduction is allowed
- Evidence: `SourcePlanResolver` calculates a dominant demand but does not use it to choose the plan. Current stationary policy can disable Pressure and make responsive Location passive, including when either is the sole manual capture source.
- Decision: every direct demand declares a source-specific minimum useful plan, latency/age bounds, and `adaptiveReductionAllowed`. Policy supplies the maximum acquisition ceiling; the broker/optimizer chooses the cheapest legal plan that meets every accepted floor without exceeding the ceiling. It never disables the sole capture source. Pressure may slow and batch but not disappear; a manual responsive Location request remains at its selected floor unless the user explicitly enabled adaptive reduction. An unsatisfied floor produces named `DEGRADED` or `UNAVAILABLE`, not silent substitution.
- Consequences: property tests cover every policy × demand × constraint combination. Device tests measure first-fix/distance, Pressure continuity, provider restarts, callback latency, CPU/wakeups, and battery per mode before defaults are tuned.

## TI-D059 — Source rollout uses existing Tracker products before a full day-journal rebuild

- Status: `ACCEPTED_AFTER_R3`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: require persisted `DayOverview` and a full Days-first UI before any source ships; let WAL presence count as success; require a minimal typed non-trip history contract through existing Tracker consumers first
- Evidence: the product adversary found Timeline is trip-driven, `DailySummaryAggregator` reads `SessionSegment`, Wi-Fi/Cell-only sessions can disappear, radio maps require coordinate-bearing query results, and opportunistic Steps can currently look like complete goal progress. The architecture adversary found all sources are force-promoted event-canonical although several projections have no terminal consumer.
- Decision: before a source becomes canonical, its typed fact must reach existing Today, Timeline, Calendar, selected-day/session detail, applicable map, export, and deletion paths with truthful completeness. Logical session/manifests are the minimum non-trip history spine. A full Days-first journal and persisted day cache remain deferred until product scope or profiling proves them necessary. Source ownership is independent; no all-source force-canonical state is allowed.
- Consequences: Wi-Fi/Cell require compact snapshot headers, bounded direct-capture start-probe semantics, and radio heatmap shadow parity. Activity-only automatic capture requires an explicit trigger-to-capture handoff. Opportunistic Steps cannot award complete-day goals/streaks across coverage gaps. Product copy must describe independent source value rather than imply that Wi-Fi/Cell/Pressure merely enhance Location.

## TI-D060 — Optimize for better tracking quality per unit of battery

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: preserve quality while minimizing cost; maximize quality regardless of battery; use a hard quality floor and seek non-dominated quality improvements within the user's acquisition ceiling
- Evidence: the user clarified that the redesign should ideally improve quality, not merely avoid regression. R3 found concrete current quality losses: provider-generation churn resets Step/Pressure state, Activity-only may lose its trigger interval, cached radio data can be mislabeled fresh, sole-source history can disappear, and synchronous/bounded pipelines can lose evidence under load. Fixing these improves correctness and coverage while also reducing wasted work.
- Decision: every demand has a hard minimum useful quality and a target. For each source, compare freshness, accuracy, coverage, continuity, completeness, error/outlier behavior, and useful optional context against the protected current path. Battery Saver selects the least-cost plan meeting the floor; Balanced selects the best measured quality/energy tradeoff inside its ceiling; Responsive selects the highest useful supported quality. Reject dominated candidates and any cutover that is both lower quality and no more efficient.
- Consequences: source rollout requires representative shadow comparisons and source-specific quality vectors rather than one fake global score. Optional enrichment may improve information but cannot alter primary fact identity/totals or start hardware. Device evidence records provider events, accepted facts, gaps, first-evidence latency, source-specific error metrics, CPU/wakeups, SQLite transactions/bytes, and battery. Numeric energy targets remain proposals until measured.

## TI-D061 — One supervisor, one physical runtime per source, and one Room mutation owner

- Status: `ACCEPTED_AFTER_EXPERT_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: retain independent callback-owned transactions and a synchronous global projection coordinator; introduce one actor per table; use one application-scoped supervisor and one serialized tracking writer with source-local read/projector lanes
- Evidence: repository review found multiple tracking `withTransaction` owners, synchronous projection draining after ingress, a global poison/progress path, and `trySend` delivery sites that can silently lose qualified callbacks. SQLite already serializes writes. Source order matters within a source but does not justify blocking all six sources behind one projector.
- Decision: `SourceSupervisor` is the concrete app-scoped owner of effective demands and one physical runtime per source. Provider callbacks create source-native deliveries. A singleton `TrackingWriter` is the only owner of tracking Room mutation transactions and deletion/import fences. Six independent workers compute source-specific fact mutations from source-scoped WAL cursors; the writer atomically applies each mutation and advances only that source cursor.
- Consequences: callbacks cannot run projection or cross-source queries, `trySend` is not an acceptable durability boundary, poison is quarantined per source, and all tracking mutation DAOs must be reachable only through writer commands. This is a concurrency simplification, not a new generic event framework.

## TI-D062 — Automatic cold starts require a durable, platform-qualified trigger before the service call

- Status: `ACCEPTED_AFTER_PLATFORM_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: allow any Activity Sampling callback to start the foreground service; start the service and then persist intent; admit only documented trigger origins and persist the complete action envelope first
- Evidence: Android foreground-service guidance lists Activity Recognition Transition events as a background-start exemption; a Sampling callback is not the same exemption. The current [`ActivityRecognitionClient`](https://developers.google.com/android/reference/com/google/android/gms/location/ActivityRecognitionClient) reference lists `ON_FOOT` among supported Transition activities, while the older [Activity Transition guide](https://developer.android.com/develop/sensors-and-location/location/transitions) still enumerates only `IN_VEHICLE`, `ON_BICYCLE`, `RUNNING`, `WALKING`, and `STILL`. This repository pins `play-services-location` 21.4.0 and currently requests `ON_FOOT`, `WALKING`, and `RUNNING`; therefore documentation alone does not prove the deployed provider accepts the full request. While-in-use restrictions remain separate: a background-created Location foreground service still needs background-location eligibility. Android 14+ additionally requires declared service type and permission compatibility.
- Decision: a cold automatic start may originate from a fresh Google Play services Activity **Transition** `PendingIntent` delivery or another separately documented legal origin. Request only activities accepted by the pinned client/provider path. `ON_FOOT` may remain in the candidate set because the current API reference supports it, but it is not rollout-qualified until a real request-success assertion passes on the pinned dependency and representative provider/device matrix; if that gate fails, omit `ON_FOOT` while retaining `WALKING` and `RUNNING` coverage. Before `startForegroundService()`, persist and CAS the trigger identity, observed/received clocks, expiry, boot/automation/policy/consent epochs, intended capture manifest, real origin, and exact intended foreground-service type mask. Sampling is capture evidence only after a legal runtime already exists.
- Consequences: automatic mode fails closed when its explicit control or start legality is unavailable; manual mode remains usable. The foreground-service type union is derived from accepted direct demands: Location uses `location`; Activity Sampling and direct live Step sensing use `health`; Wi-Fi, Cell, and Pressure use the narrowly documented `specialUse` subtype; mixed sessions use the union. This mapping requires manifest/start-path tests across supported API levels and Play-policy review before release.

## TI-D063 — Ambient acquisition uses provider continuity and one inexact maintenance chain, never a fake scheduler

- Status: `ACCEPTED_AFTER_PLATFORM_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: keep a background service for every ambient source; use periodic WorkManager as the sampling clock; use provider-owned callbacks/subscriptions plus one consolidated maintenance chain
- Evidence: WorkManager periodic work has a 15-minute minimum and remains inexact, quota/Doze constrained; Android 16 also applies job quotas to jobs started from a foreground service. FLP `PendingIntent` requests can survive ordinary process death but are removed by app upgrade or force-stop. Wi-Fi scans are throttled and unavailable in Doze; radio callbacks are process/OS opportunity driven.
- Decision: provider-owned callbacks/subscriptions carry ambient observations when a legal, explicitly consented host exists. One unique WorkManager chain reconciles registrations after boot/update/user launch, imports system continuity data, repairs projections, and applies retention. It records actual execution and observation times and never promises cadence. In-process timers are live-runtime optimizations only.
- Consequences: reboot/upgrade/force-stop recovery re-arms eligible provider registrations but does not resume a finalized automatic session. Wi-Fi and Cell remain opportunistic; maintenance may reconcile eligible registrations but cannot poll cache to manufacture observations. No per-source periodic worker fleet is introduced. TI-D081 supersedes the earlier cache-only experiment option.

## TI-D064 — Ambient Steps selects one continuity provider and canonicalizes overlaps

- Status: `ACCEPTED_WITH_IMPLEMENTATION_SPIKE_GATE`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: keep Tracker alive around the hardware counter; add direct counter, Health Connect, and Recording API results; select one system continuity provider and reconcile it with live-session boundaries
- Evidence: Android’s Recording API is accountless, device-local, retains up to ten days while subscribed, and is the documented replacement path for Google Fit recording. Health Connect exposes on-device Steps on Android 14 when SDK Extension 20 and the required permissions/origin APIs are available. Health Connect data is written approximately once per minute rather than as live per-step evidence, and June 2026 changes synthetic package-name handling. The direct `TYPE_STEP_COUNTER` remains the lowest-latency supported live-session signal.
- Decision: the ambient Steps setting authorizes one selected continuity adapter: prefer Health Connect on-device Steps where capability/origin checks pass; otherwise evaluate the Recording API; otherwise report opportunistic/unavailable continuity. Direct sensor boundaries remain live-session evidence. A Step canonicalizer assigns deterministic precedence to overlapping intervals and never sums overlapping direct/Health Connect/Recording data. Use aggregation or `getCurrentDeviceDataSource()`; never hardcode the former synthetic `android` origin.
- Consequences: the first spike must prove capability detection, permission UX, provider switch, delayed import, reboot/reset, overlap/correction, no-double-counting, background-read legality, and no-GMS/no-Health-Connect fallbacks before product activation. Wearables and the broader Health Connect ecosystem remain outside this decision.

## TI-D065 — Pressure persists quality-preserving microsegments rather than every raw sensor callback

- Status: `ACCEPTED_AFTER_SOURCE_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: commit every 5–20 Hz callback before it can affect state; retain only a final session mean; persist bounded source-qualified microsegments with explicit crash gaps
- Evidence: the current responsive path can produce roughly 1,230 Room writes per minute, while saver and balanced can still produce roughly 61 and 306. The approved product is pressure trend/coverage, not raw waveform archival. Per-callback crash durability would multiply writes and processor wakeups without a demonstrated product benefit.
- Decision: Pressure’s durable observation is a short bounded microsegment/window containing count, endpoints, min/max, mean/M2, slope, fit quality, accuracy and actual/expected elapsed-time coverage. Use wake-up delivery when supported or the longest FIFO/report latency compatible with the direct demand floor. A crash before a window closes produces an explicit bounded gap; it does not justify per-sample Room writes.
- Consequences: session `RECORDING` advances after the first committed qualified microsegment, while an in-memory preview may appear earlier and must be labeled provisional. Window length and sampling rate are device-calibrated against trend accuracy and battery/write budgets. Raw archival or elevation inference requires a separate product decision.

## TI-D066 — Typed delivery identity is the receipt; generic accounting remains out of scope

- Status: `ACCEPTED_AFTER_ARCHITECTURE_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: restore generic writer activation/contribution/correction tables; use destination-specific stable identities and recomputation; permit unversioned additive aggregates
- Evidence: the removed generic Phase 3 repositories had no production callers. Existing source destinations can use stable `(sourceEventId, itemIndex or interval identity)` uniqueness and recompute bounded summaries. The actual risk is an unfenced legacy destination, not the absence of a generic accounting framework.
- Decision: each typed delivery’s stable `(sourceEventId,itemIdentity,projectorVersion)` identity is its idempotence receipt. Projection and cursor advancement occur in the same writer transaction. TI-D071 separately defines logical fact/range identity and explicit correction mutations. Deletion fences precede removal so replay/import/delayed callbacks cannot resurrect data. A narrow contribution receipt may be added only inside the same transaction as a demonstrated non-recomputable additive destination.
- Consequences: writer cutover is source/destination-specific and Location remains protected. Do not build a generic activation, arbitrary attribution, receipt, counting-head, dirty-day, or join platform before a shipping consumer proves the need.

## TI-D067 — Radio facts are independently useful, privacy-keyed snapshots with optional query-time context

- Status: `ACCEPTED_AFTER_SOURCE_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: store only changed child rows; embed mutable Location coordinates in Wi-Fi/Cell rows; persist self-contained minimized snapshot headers and resolve optional Location context at query time
- Evidence: fresh empty, unchanged, stale, throttled and no-delivery radio states are currently conflated. Existing identifiers use deterministic unkeyed hashes, and radio rows embed optional coordinates. This prevents reliable coverage semantics, key rotation and clean Location deletion while risking one query per child during enrichment.
- Decision: every qualified Wi-Fi/Cell delivery has a source-event header with provider/freshness/capability state, including empty and unchanged deliveries. Minimized children use per-install, purpose/epoch-bound keyed HMAC identity; an unchanged header may hold one pinned reference to the prior payload. Optional Location context is a versioned page/day linear merge over already-product-eligible Location facts and never mutates the radio fact or starts Location.
- Consequences: retention cannot prune a referenced payload until its one-hop pin is gone; consent reset/deletion rotates the applicable key and invalidates derived context. Existing radio-map usefulness requires privacy-safe shadow parity. Default UI/export exposes counts, band/technology/quality mix and coverage, not raw SSID/BSSID/tower/subscription identity.

## TI-D068 — v28 is the direct target, gated by exact populated-v27 evidence

- Status: `ACCEPTED_WITH_DEVICE_GATE`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: preserve every local v28 experiment and add v29; rewrite v27 history into speculative new facts; shape one minimal v27→v28 migration and validate it with a populated fixture
- Evidence: the user confirmed v28 was never released. The generated v28 schema remains untracked and no device migration has run. Static schema comparison finds 51 released v27 tables and 61 current v28 tables: the only ten additions are the policy authority/policy/consent, demand/registration/eligibility, manifest/source, lifecycle-intent and desired-action tables. v27 contains user facts and active projection/outbox consumers that must not be reinterpreted or dropped, plus operational/event rows whose historical eligibility may be unprovable.
- Decision: continue reshaping `MIGRATION_27_28` and schema 28 directly around the minimum pipeline. Preserve all 51 released v27 tables in the first slice; no physical drop is authorized. Reshape the ten unshipped additions freely, change released tables only additively, and retire released compatibility/projection tables later only after production callers and pending effects are drained through an explicit migration decision. Preserve owned v27 typed user facts and stable identities. Where legacy operational/WAL provenance cannot prove source, purpose, epoch or integrity, keep it inert for diagnostics or explicitly discard only after repository evidence proves it never represented released user history; never fabricate product facts from it.
- Consequences: no v29 shell and no destructive migration are authorized. Clean and realistically populated v27 open → migrate → reopen → query/export/delete tests must execute on Android before any v28 schema freeze or source activation. The fixture must include sessions, source facts, radio data, imports, deletions, counter reset and interrupted automatic state.

## TI-D069 — One production history repository composes existing surfaces

- Status: `ACCEPTED_AFTER_ARCHITECTURE_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: retain separate session/trip/day repositories per screen; build a persisted universal `DayOverview` platform first; expose one typed history facade that composes source facts for current consumers
- Evidence: current Timeline/DailySummary paths are trip/segment dominated and can hide Wi-Fi-only, Cell-only or ambient-only evidence. A persisted generic DayOverview and full Days-first redesign have no current production owner, yet source completion still needs one shared query contract.
- Decision: introduce one `TrackingHistoryRepository` facade that returns stable historical-day/session/outside-session state, typed source metrics, optional context provenance and completeness to Today, Timeline, Calendar, selected-day/detail, map, export and deletion. It may compose/recompute from typed tables and existing compatible summaries; persistence/caching is added only after profiling.
- Consequences: one source vertical can ship without a speculative UI rewrite, but cannot claim `QUERYABLE` until every named existing consumer handles its sole-source and ambient states truthfully. The future Days-first UI may consume the same facade without changing acquisition or fact identity.

## TI-D070 — Durable units are authorization-homogeneous at observed time

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: stamp one authorization revision on every provider delivery; classify the complete delivery using receipt-time policy; partition/split source-native deliveries at immutable observed-time authorization boundaries
- Evidence: a Location/Activity batch or Pressure window can cross session stop or consent revocation before delayed delivery. One stamp either admits post-revocation evidence or loses valid pre-boundary evidence. Step counter deltas spanning an unobserved boundary cannot be split honestly.
- Decision: the durable admission transaction resolves immutable authorization intervals for every item/subrange by observed time. Split Location and Activity batches into authorization-homogeneous subranges while retaining their shared delivery identity. Close Pressure windows at known authorization boundaries. Wi-Fi/Cell snapshots use their provider observation instant/interval. A Step delta crossing an unknowable boundary establishes a new baseline and an explicit gap; it is never allocated across the boundary by assumption.
- Consequences: one provider callback may create multiple WAL subranges in one bulk transaction. Revocation fences product use immediately without discarding earlier eligible evidence. Tests delay every source delivery across enable/revoke, session start/stop, consent epoch and reboot boundaries.

## TI-D071 — Delivery idempotence and logical correction identity are distinct

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: use `(sourceEventId,itemIndex)` for both delivery receipt and product identity; restore generic contribution algebra; define destination-specific logical interval/range identities and explicit fact mutations
- Evidence: a provider correction or projector upgrade can replace one prior item with zero or two; a new event identity cannot remove the old fact. Correcting a Step boundary can affect both adjacent intervals. Stable event receipt alone therefore prevents duplicate delivery but cannot define cardinality-changing correction.
- Decision: each typed projector defines (1) immutable delivery identity including projector version, (2) stable logical fact/range identity independent of the correcting delivery, and (3) explicit `UPSERT`, `DELETE`, or bounded `REPLACE_RANGE`/supersession mutations. `TrackingWriter` retracts obsolete facts and memberships, applies replacements and advances that source cursor atomically.
- Consequences: TI-D066 remains correct that no generic accounting platform is required, but its phrase “typed fact identity is the receipt” is narrowed to delivery idempotence. Source contracts must document correction ranges and ordering. Correction, replay and projector-version tests must cover one→zero, one→two, out-of-order and Step boundary-chain changes.

## TI-D072 — Scoped deletion, import, and destination ownership are narrow persisted fences

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: rely on one global data epoch and `INSERT OR IGNORE`; restore a generic activation/tombstone framework; add narrow source-purpose-time deletion and source-destination owner generations enforced by the single writer
- Evidence: current merge import independently inserts both facts and derived summaries with `INSERT OR IGNORE`, bypassing future membership/deletion reconstruction. A pre-deletion backup can resurrect scoped data. Current force-canonical rollout cannot stop a paused legacy mutation from committing after candidate activation.
- Decision: add `deletion_fence(source, purpose, consentEpoch, observedBoundary/scope, fenceGeneration)` and `source_destination_owner(source, destination, ownerGeneration, owner)` to v28. All live admission, projection, deletion, portable merge import and destination mutations execute through `TrackingWriter` and recheck relevant fences in the mutation transaction. Merge import stages provenance-bearing base facts only, treats identical identity+checksum as duplicate, rejects/quarantines identity conflicts or unverifiable rows according to an explicit import policy, and recomputes derived products. Whole-database restore remains a separately labeled empty-target protocol.
- Consequences: legacy and candidate writers must use the same owner fence before cutover; Location cannot cut until they do. Portable export is sourced from typed product facts/membership/completeness, includes all released sources, excludes control-only operational evidence and raw radio identifiers. Deletion→old-import→replay and paused-legacy-writer races become release-blocking tests.

## TI-D073 — Verified zero Steps is covered product evidence, not `RECORDING` onset

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: discard every zero delta; let a baseline alone prove zero; persist qualified unchanged counter boundaries as coverage while retaining positive delta as the `RECORDING` threshold
- Evidence: the runtime can form valid zero-delta same-generation windows, but the current projection drops them and the DAO collapses no rows and zero total. A positive delta is intentionally the source-qualified `RECORDING` event, yet users still need to distinguish “observed and stationary” from “not observed.”
- Decision: one baseline is `NO_OBSERVATION`/`PARTIAL`, not zero. Two or more qualified same-generation boundaries with unchanged count create covered Step evidence and may truthfully show zero for the covered interval; a positive post-baseline delta advances lifecycle `RECORDING`. Queries always return value together with coverage/completeness.
- Consequences: zero and recording-onset remain distinct. Goals/streaks cannot treat a partial verified-zero interval as a complete zero day. Tests cover baseline-only, verified-zero, positive delta, restart/reset and replay.

## TI-D074 — Product state is orthogonal availability, evidence, and materialization

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: use one expanding status enum; let every screen infer state from nullable values; return an exhaustive product state with orthogonal axes and named causes
- Evidence: current design artifacts and repositories use overlapping vocabularies while History exposes only Loading/Content and daily values are nullable. Wi-Fi can simultaneously be enabled, OS-throttled, have prior partial evidence and be materializing; one flat value loses important truth.
- Decision: `TrackingHistoryRepository` exposes one shared contract: policy/capability availability (`DISABLED`, `UNSUPPORTED`, `PERMISSION_REQUIRED`, `OS_LIMITED`, `AVAILABLE`), acquisition evidence (`NONE`, `STARTING`, `ACTIVE`, `RECORDED`), and product state (`MATERIALIZING`, `PARTIAL`, `READY`, `DEGRADED`, `FAILED`) with source-specific coverage and named repair cause. Consumers map it exhaustively; numeric zero is present only with covered evidence.
- Consequences: internal lifecycle labels may remain, but UI/export/query semantics use this one mapping. Renderer, TalkBack, large-text and no-invented-zero tests cover all six sources and meaningful cross-axis combinations without multiplying bespoke screen enums.

## TI-D075 — Optional context requires typed purpose and session compatibility

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: let callers pass a free-form allowed-purpose set; reuse any nearby product Location; require a typed requester and deterministic compatibility rule
- Evidence: a capture-only Location point just before session end could otherwise enrich an ambient Wi-Fi fact just after session end even when ambient Location consent is disabled. “Already paid” does not authorize cross-purpose reuse.
- Decision: `LocationContextResolver` receives the primary fact membership, logical session, purpose, consent epoch and observed interval. Same-session capture may use capture-eligible Location from that session; ambient primary facts may use only independently ambient-eligible Location under the compatible consent epoch; control-only, deleted, cross-session capture and incompatible-purpose facts are excluded.
- Consequences: optional context remains query-time and cannot start hardware, but it also cannot borrow consent. Revocation/deletion removes context without changing the primary radio fact. Same-session success and cross-session/purpose failure tests are mandatory.

## TI-D076 — Central owners are thin executors, not new god objects

- Status: `ACCEPTED_AFTER_FRESH_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: place source logic in one writer/supervisor/history class; create an interface/repository per table; keep three concrete thin owners with source-specific pure collaborators and separate command services
- Evidence: one mutation owner and one read facade simplify SQLite correctness, but the existing coordinator already exceeds 2,000 lines. Without enforceable boundaries, decoding, correction, import, deletion, presentation and provider logic can accumulate centrally and recreate global coupling.
- Decision: `SourceSupervisor` only reconciles direct demands to physical registrations; source adapters own Android semantics. Source projectors are pure/read-only and produce typed commands. `TrackingWriter` only serializes commands, rechecks fences/cursors and applies bounded transactions. `TrackingHistoryRepository` is read-only. Export, deletion/import orchestration and context resolution are separate services over stable identities but submit mutations to the writer.
- Consequences: no repository interface per fact table and no package-move campaign. Architecture dependency tests reject provider/query logic in the writer, mutation APIs in history, hardware activation from context, and direct tracking DAO/transaction access outside the writer. Slow import/deletion stress must preserve bounded live admission and unrelated-source progress.

## TI-D077 — Every source has an explicit acquisition ladder and source-local handoff

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: model passive/active only for Location; use one generic mode enum for all sources; define source-specific ordered modes under one shared switching protocol
- Evidence: the user requires passive/active Location switching and equivalent efficient options for all sources. Current plans already expose Location passive/low-power/balanced/high-accuracy/probe, Activity transitions/sampling, Wi-Fi cached/broadcast/active attempts, Cell callbacks/sparse refresh, and tunable Steps/Pressure latency. However, current resolution can ignore demand floors, restart providers on authorization-only changes, or disable Pressure, and “one runtime” could be misread as one OS handle even when Activity or Steps legitimately uses complementary APIs.
- Decision: each source owns the mode ladder in `ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md` §7.2. One app-level runtime owns the minimal compatible Android handle set for that source. Same-source direct demands merge to a plan satisfying every floor within policy ceilings. Physical changes are source-local, durable-before-side-effect, provider-specific, gap-aware, and deduplicated across bounded overlap; authorization-only changes issue no provider calls.
- Consequences: active Location downgrades to passive when only ambient demand remains; Activity may retain Transitions while removing session Sampling; Steps may retain one system continuity adapter while removing the live counter; Pressure changes FIFO/rate/window without disappearing; Wi-Fi/Cell return from direct probes/refresh to opportunistic callbacks. Enrichment never raises a mode. Transition/property/device tests cover every adjacent and direct mode change, failure, revoke, process death, and unrelated-source mutation.

## TI-D078 — Acquisition modes name real Android mechanisms, not assumed battery tiers

- Status: `ACCEPTED_AFTER_PLATFORM_RESEARCH`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: retain saver/balanced/responsive labels as if each guarantees a different energy cost; reduce every source to `OFF`/`ON`; model only materially different provider work and measure profile separation
- Evidence: Google documents Activity Transitions as preferred and lower power than sampled classifications, but the realized saving is device/workload dependent. Android documents that positive sensor report latency reduces application-processor interrupts only with a real hardware FIFO; a zero-capacity FIFO is equivalent to immediate delivery. The current Pressure profiles request approximately 1 Hz/60 s report latency, 5 Hz/10 s, and 20 Hz/1 s, but still checkpoint the accumulator per sample; the qualitative battery estimator is an uncalibrated generic prior. `OFF` remains cheaper than every registered continuous sensor.
- Decision: Activity uses `TRANSITION_EVENTS` and `SAMPLED_CLASSIFICATIONS`. Pressure separates rate profile (`LOW_RATE`, `STANDARD_RATE`, bounded `HIGH_RATE_WINDOW`) from actual delivery (`FIFO_BATCHED` or `IMMEDIATE`). These are internal acquisition plans, not promised user-visible battery percentages. A rung ships only when it changes an Android request or measured callback/wakeup/CPU/energy behavior while preserving its quality floor; otherwise it collapses into the adjacent rung.
- Consequences: Transitions remain the default for automation/coarse bands because they are semantically sufficient and platform-recommended, even if shared Play services work makes the device-level delta small. Pressure batching may reduce wakeups but does not turn the sensor off. Device tests record effective sample rate, FIFO capacity, batch size, callbacks, wakeups, CPU and energy; UI reports the applied mechanism and degradation rather than an unverified saving.

## TI-D079 — Cache reads, passive notifications, and active requests are distinct

- Status: `ACCEPTED_AFTER_PLATFORM_RESEARCH`
- Owner/date: lead orchestrator, 2026-08-22
- Alternatives: call cache access a callback/mode; treat every read as a new observation; distinguish stored state, provider notification and app-triggered work
- Evidence: Android's current [Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan) documents `getScanResults()` as the most recently updated cache, `SCAN_RESULTS_AVAILABLE_ACTION` as scan-completion notification, and `startScan()` as the active request; on Android 10+ the broadcast can report scans performed by the platform or other apps. Current `CACHED_ONLY` and `BROADCAST_DRIVEN` both register that same receiver, read the same startup cache and issue no scan, so their physical behavior is identical. The current [TelephonyManager reference](https://developer.android.com/reference/android/telephony/TelephonyManager) says target-Q+ `getAllCellInfo()` returns cached state without refreshing it, while `requestCellInfoUpdate()` asks for updated state but is rate-limited and not guaranteed; change listeners are independently registered callbacks. Current Battery Saver and Balanced Cell plans use the same listener behavior.
- Decision: Wi-Fi modes are `OFF`, `PASSIVE_SCAN_RESULTS`, and bounded `ACTIVE_SCAN_BUDGET`; Cell modes are `OFF`, `CHANGE_CALLBACKS`, and bounded `REFRESH_BUDGET`. Cache reads are bootstrap/payload retrieval operations, not physical acquisition modes and never by themselves satisfy `RECORDING`. Attempts, timeouts, throttles and failed completions are bounded operational outcomes; only timestamp-qualified provider results may become product facts.
- Consequences: ambient Wi-Fi/Cell can opportunistically benefit from work already performed by the system without Tracker promising cadence. Direct source-only sessions may spend a declared bounded budget to seek first/useful evidence. Pre-Q Wi-Fi passive behavior, process lifetime, permissions, Doze, throttling and multi-SIM coverage are explicit capability/completeness dimensions.

## TI-D080 — Freshness and relevance gate identity-bearing storage

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: persist every cache/result and filter only at query time; use the newest child or receipt time for a whole batch; gate each item before durable payload admission and retain only compact rejection diagnostics
- Evidence: current Wi-Fi code admits all access points using the freshest timestamp and substitutes receipt time when no provider timestamp exists. Current Cell code age-gates only cached outcomes, uses the newest cell timestamp for the group, and can persist timestamp-unknown or stale callback content. Both can preserve stale identity/signal payload and later misstate freshness. Android's current [`ScanResult.timestamp` reference](https://developer.android.com/reference/android/net/wifi/ScanResult#timestamp) defines per-result microseconds since boot when the AP was last seen; the current [`CellInfo.getTimestampMillis()` reference](https://developer.android.com/reference/android/telephony/CellInfo#getTimestampMillis()) defines a non-negative `SystemClock.elapsedRealtime()` timestamp for modem information. These are the item-level recency clocks used by the containment gate.
- Decision: a source adapter must classify input before identity-bearing WAL admission as `PRODUCT_FACT`, `COVERAGE_ONLY`, `OPERATIONAL_OUTCOME`, or `REJECTED`. Registration/policy/consent/boot/effective-time fences precede source-specific per-item age and semantic validation. Stale, pre-effective, clock-unverifiable, malformed, or irrelevant payload is not stored or used; only a bounded non-identifying reason counter may remain. Fresh empty/unchanged deliveries may persist a compact coverage header because they prove observation coverage without duplicating child payload.
- Consequences: “provider runs, therefore it persists” means every **qualified** authorized delivery receives a durable quality-preserving representation; it never licenses stale cache retention. Receipt time cannot fabricate observation time, one fresh radio child cannot pull stale siblings into storage, and cached state alone cannot advance `RECORDING`. Source-specific admission, mixed-age batch, boundary, future-time, missing-time, duplicate, empty and unchanged tests block Wi-Fi/Cell materialization and production history wiring.

## TI-D081 — Replayed cache state has zero product and coverage value

- Status: `ACCEPTED`
- Owner/date: user/product owner and lead orchestrator, 2026-08-22
- Alternatives: persist every cache read as coverage; periodically store duplicate snapshots for liveness; discard exact cache replays and retain only genuinely new provider observations
- Evidence: `getScanResults()` and target-Q+ `getAllCellInfo()` expose provider caches. Reading an identical content/provider-time vector again shows only that the app reread memory; it does not show that Wi-Fi or the modem observed the environment again. Storing it would inflate observation counts, coverage, database writes and retention without adding information.
- Decision: an exact cache replay—same normalized qualified content and same per-item provider-time vector—creates no WAL row, fact, coverage header, lifecycle transition or query/export result. It may increment only an in-memory or bounded non-identifying diagnostic counter. A successful new provider callback whose content is semantically unchanged but whose qualified provider times advance is not a cache replay: it may create one compact `COVERAGE_ONLY` header and reuse the prior qualified content without copying children.
- Consequences: stale cache has no product value and is never retained merely to prove liveness. Observation count means provider observations, not app reads. Dedup state is bounded/reconstructable and carries no new source history; retention/deletion cannot leave dangling reused-content references. Tests distinguish identical cache replay, new callback/same content, fresh empty completion, mixed-age result and actual content change.

## TI-D082 — Radio containment withholds identity and gates Wi-Fi by the APIs actually used

- Status: `ACCEPTED_FOR_CONTAINMENT`; durable keyed identity and device proof remain `BLOCKED`
- Owner/date: lead orchestrator after fresh Android/power/privacy adversary, 2026-08-22
- Alternatives: retain deterministic SHA-256 radio tokens; build a keystore/HMAC/key-rotation subsystem before any other work; temporarily retain only identity-free coverage/quality fields and add keyed identity when its deletion lifecycle is ready
- Evidence: the current runtime had no per-install key owner or consent-reset rotation transaction, so deterministic BSSID/cell hashes remained correlatable and the Cell payload also retained Android subscription IDs. Counts, band/technology mix, signal quality, item time, and optional already-authorized Location context do not require stable radio identity. Android's current [Wi-Fi permission guidance](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) and [scan overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan) list `ACCESS_FINE_LOCATION`, Wi-Fi state/change permissions, and enabled Location Services for the `startScan()`/`getScanResults()` path used here; `NEARBY_WIFI_DEVICES` applies to other Wi-Fi operations not called by Tracker.
- Decision: new radio runtime payloads withhold provider and subscription identifiers until TI-322 supplies a per-install, purpose/consent-epoch-scoped HMAC plus rotation/deletion tests. Wi-Fi payload version 2 preserves each accepted AP's provider elapsed time while decoding version-1 rows unchanged. A provider-confirmed fresh empty callback is durable zero coverage; failed completion, startup cache, and nonempty all-stale delivery are not. Onboarding, settings, capability checks, and the manifest request only the permissions required by the scan APIs in use.
- Consequences: the first Wi-Fi/Cell product vertical can expose useful privacy-safe aggregate history without prematurely creating a key subsystem. Stable-network/place correlation is unavailable until the keyed vertical justifies it. Existing released legacy radio rows are not rewritten by this containment slice. API/OEM permission and provider behavior remain device gates, and any future Wi-Fi operation must declare and test its own permission contract rather than broadening scan permission preemptively.

## TI-D083 — Paid radio acquisition is finite, direct-only, and ends after qualified evidence

- Status: `ACCEPTED_FOR_CONTAINMENT`; values are not calibrated product tiers
- Owner/date: lead orchestrator after fresh Android/power/privacy adversarial review, 2026-08-22
- Alternatives: leave active scan/refresh attempts running for the registration lifetime; forbid all direct acquisition; use a small finite first-evidence budget while retaining passive callbacks
- Evidence: passive Wi-Fi broadcasts and Cell change callbacks can provide opportunistic evidence but cannot guarantee a short sole-source session result. Conversely, an in-process loop that repeatedly calls `startScan()` or `requestCellInfoUpdate()` spends battery, is platform-throttled/rate-limited, and is not a wake-reliable schedule. Optional context/enrichment has no authority to spend that work.
- Decision: the containment runtime grants active attempts only to direct `SESSION_CAPTURE` demand, currently at most one Wi-Fi scan request or one Cell refresh group per physical registration. A qualified durable or duplicate provider result closes the paid budget; the passive callback registration remains active while authorized. `CONTROL`, `AMBIENT_PRODUCT`, and enrichment receive no paid radio attempts in this slice. Repeated attempts require a later explicit measured mode.
- Consequences: this removes perpetual active work and follows the design's conservative one-probe floor without claiming it is the optimal measured limit. TI-212 must move any repeated-attempt budget into explicit measured policy, test reconfiguration/process death, and calibrate quality/energy on relevant devices. Absence after budget exhaustion is truthful no-observation/OS-limited evidence, never fabricated zero.

## TI-D084 — R1 narrows implementation to one real source lane at a time

- Status: `ACCEPTED`; materializer/history wiring remains `BLOCKED`
- Owner/date: lead orchestrator after three fresh R1 adversarial reviews, 2026-08-22
- Alternatives: instantiate six projectors/cursors and a universal writer before any product fact; make each source depend on completion of the prior rollout source; implement one Steps-local lane and generalize only proven mechanics
- Evidence: the prior executable plan made TI-410 depend on TI-322 while TI-322 depended on the first typed vertical, and encoded Steps → Pressure → Location → Activity → Wi-Fi → Cell as a hard dependency chain. Room already serializes transactions; the required extra authority is a thin data-plane mutation owner, not a universal owner for policy and lifecycle control transactions.
- Decision: Steps owns the first concrete WAL cursor/writer/correction/deletion/export/query slice. `TrackingWriter` owns only data-plane WAL/fact/membership/cursor/deletion/import/destination mutations; policy and lifecycle retain their own atomic control-plane transactions. Additional source lanes are created with real verticals. The default source order is scheduling preference only, so one source's device or product blocker cannot block another otherwise-ready source.
- Consequences: do not recreate the removed generic contribution/accounting/join/day-cache platform. A plan-DAG test and a Pressure-independent-of-Steps plan case become required evidence. TI-322 generalizes deletion/export only after Steps proves the minimum contract.

## TI-D085 — Observed-time authority and released pending WAL precede v28 schema freeze

- Status: `ACCEPTED`; implementation `BLOCKED`
- Owner/date: lead orchestrator after fresh R1 data/migration review, 2026-08-22
- Alternatives: authorize at receipt time; bind authority to the current physical registration row; resolve immutable authorization intervals at each item's observed time and preserve qualified legacy work separately
- Evidence: current admission can reject an authorized pre-revocation observation delivered after revocation, while a policy-only change rotates physical generations and loses Step/Pressure continuity. Migration currently labels pending v27 WAL `LEGACY_UNKNOWN`, after which integrity filtering can quarantine checksum-valid released evidence and advance projections past it.
- Decision: v28 stores physical configuration generations separately from immutable boot/elapsed authorization intervals. Admission splits or closes source deliveries at authorization boundaries and resolves every retained item by observed time. A v27 row whose existing payload checksum verifies is classified `LEGACY_CHECKSUM_VERIFIED` and may drain once only through its released legacy projection; it cannot acquire new capture/ambient provenance. Nonterminal legacy sessions/runs are finalized interrupted before they can be reported active.
- Consequences: TI-180/TI-181/TI-184 and a realistically populated Android fixture block schema freeze. Current live radio replay containment is not durable exactly-once proof. No candidate materializer may consume legacy-unqualified rows or activate before the destination owner fence exists.

## TI-D086 — Sole-source start and completeness must reach the app's existing consumers

- Status: `ACCEPTED`; implementation `IN_PROGRESS`
- Owner/date: lead orchestrator after fresh R1 product/scope review, 2026-08-22
- Alternatives: keep a global Location permission gate; fix only History; derive prerequisites from selected sources and carry completeness through every consumer that can interpret a daily metric
- Evidence: the Dashboard currently checks `hasAnyCaptureSource()` and then unconditionally requires Location permission before starting, blocking Pressure-only, Steps-only, and Activity-only sessions. Existing History, goal/game, widget, notification, achievement, streak, and Dashboard paths commonly consume nullable/numeric step totals and can turn missing or partial coverage into zero or a complete-day achievement.
- Decision: the start action requests only prerequisites required by the selected source set; a platform Location permission needed for Wi-Fi/Cell is labeled as that source's prerequisite and never enables Location capture. The first history facade exposes only the existing Today/day/session/fact needs—no paged Days API—and completeness accompanies each metric through all consumers that can award or summarize it.
- Consequences: six parameterized only-X start tests and baseline-only/covered-zero/partial/complete Steps consumer tests block source rollout. A future Days journal remains separately approved product work, not a safety prerequisite.

## TI-D087 — Radio identity-free aggregates and identity products are separate rollout tiers

- Status: `ACCEPTED`
- Owner/date: lead orchestrator after fresh R1 data/product reviews, 2026-08-22
- Alternatives: retain raw or deterministic identifiers; silently show identity-dependent legacy screens with partial v2 facts; ship identity-free aggregates first and gate correlation products separately
- Evidence: withholding provider/subscription identity still supports per-callback counts, Wi-Fi band mix, Cell technology/quality distribution and coverage. It cannot support unique/new network counts, AP/cell continuity, radio heatmap clustering, or reliable multi-SIM completion. Once raw identity is discarded, these products cannot be reconstructed retroactively.
- Decision: v28 containment rows are not product-backfillable into identity-dependent screens. The first radio tier may expose explicitly identity-free aggregate history. Unique-network/distinct-cell/map products remain `UNAVAILABLE` for those rows until admission creates per-install purpose/epoch-keyed HMAC tokens with version/rotation/deletion guarantees; Cell additionally needs a non-identifying registration-local SIM grouping contract.
- Consequences: legacy radio ownership remains separate until shadow parity and an explicit cutover decision. Product tests must distinguish aggregate availability from identity-product availability instead of presenting a silently partial screen.

## TI-D088 — Released-v27 recovery is a startup fence plus frozen compatibility drain

- Status: `ACCEPTED_FOR_CONTAINMENT`; frozen runtime drain and host startup fence implemented;
  connected migrate-to-runtime proof remains `BLOCKED`
- Owner/date: lead orchestrator after three independent v27 recovery adversaries, 2026-08-22
- Alternatives: let the ordinary live coordinator reinterpret pending v27 WAL; gate only the normal
  `Application` initialization path; build a generic migration/receipt/restore platform; record one
  narrow durable recovery obligation and require one process-wide startup authority
- Evidence: released v27 had exactly four v1 projections. Current Activity v1 semantics have changed,
  effect dispatch previously matched only free-form effect kind, and a v27 crash may leave WAL,
  checkpoint, and undelivered outbox at different transaction boundaries. Android can cold-start
  receivers/services outside the normal application initializer, while eager Room/DataStore
  consumers can begin policy/provider work before `LegacyDatabaseUpgradeCoordinator` completes.
  A fully projected pending effect also proved capable of losing its originating WAL under routine
  maintenance. Full restore has no safe hot-singleton replacement seam, and partial Tracker database
  merge can bypass full-database recognition.
- Decision: the unreleased v28 migration captures an immutable admission/outbox high-watermark/data epoch,
  snapshots or seeds only the four exact released v1 targets, deactivates all v1 registrations, and
  starts current v2 projections at `cutoff + 1`. Live outbox dispatch is keyed by projection ID and
  version. Pending legacy effects and blocked targets retain the required WAL until a durable bridge
  or terminal disposition; bounded maintenance may then remove terminal payloads. The next runtime
  slice must expose one process-single-flight `TrackingStartupFence` used by every provider,
  receiver, service, policy bootstrap and module initializer. Deletion wins before drain; checksum-
  valid rows are decoded only by frozen v1 code; old Activity/control and unsafe session-bound effects
  are suppressed with auditable disposition; no v27 row acquires v28 purpose/consent/manifest state.
- Consequences: current containment raises v28 from 61 to 63 entities without restoring the removed
  generic Phase 3 platform. Location recovery is shadow-only and cannot become a second canonical
  writer. Source materializers and production UI wiring remain prohibited until startup ordering,
  crash/retry, epoch/deletion races and every target disposition pass. Production backup-wrapper
  proof, cold empty-target restore, and partial-database merge rejection are separate narrow slices;
  no general hot restore or provenance-free import framework is authorized.

## TI-D089 — Frozen-v27 destinations are narrow, semantic, and truthfully partial

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator after released-code inspection and fresh data/lifecycle/product attacks, 2026-08-22
- Alternatives: run current v2 projectors over v27 rows; suppress every legacy effect; copy all four v1 projectors into a permanent compatibility framework; recover only the two existing typed facts through one startup-only adapter
- Evidence: released v27 Activity effects can restart automation from stale motion; joined-frame effects had no production consumer; Location v1 wrote noncanonical shadow state and cannot be replayed without creating a second writer. Event-frame v1 alone carried durable Steps/Pressure facts with stable `source-event:<eventId>` destination identity. Released pruning could remove raw WAL after outbox creation but before typed commit, and released SignalAdapter audit stamps legitimately differ from WAL-enriched stamps. SQLite AUTOINCREMENT/ignored inserts make ordinal holes normal rather than proof of loss. A fresh implementation adversary also demonstrated that an outbox-only ordinal may exceed a missing/reset WAL sequence, that an outbox may lie below its writer activation, and that identity-matched WAL/outbox payloads may still disagree semantically.
- Decision: the one-time drain suppresses Activity v1 as `SUPPRESSED_STALE_CONTROL`, suppresses joined frames as `SUPPRESSED_UNWIRED_OUTPUT`, preserves Location shadow byte-for-byte as `LOCATION_SHADOW_RETAINED` or truthfully `LOCATION_SHADOW_PARTIAL`, and bridges event-frame v1 only into existing `StepInterval`/`PressureSample` destinations. The immutable cutoff is the maximum of the WAL sequence, retained WAL, and retained outbox ordinals. Raw-WAL bridge transactions atomically verify/classify payload, insert-or-semantically-verify the typed fact, record terminal poison/collision evidence, and advance the fenced cursor. Pending event-frame outboxes without raw WAL use one frozen effect fallback and become `BRIDGED_TYPED_FACTS_PARTIAL` with `LEGACY_UNKNOWN` clock provenance; no current-boot metadata is invented. Nonpositive or pre-activation outboxes are terminally quarantined, and an outbox paired to raw WAL must equal the exact frozen v1 semantic cycle before it can be acknowledged as already bridged. Sparse ordinals advance without a false partial result, unknown target/outbox generations block startup, and target terminalization requires its immutable cutoff.
- Consequences: this adds no live materializer, generic receipt platform, Activity callback,
  joined-frame consumer, or Location canonical writer. Exact semantic duplicates are accepted
  despite known released audit-stamp variants; changed metric/window fields never overwrite and
  become auditable collisions. Event-frame poison evidence is retained until normal retention/full
  deletion. The process-wide host fence is implemented by TI-D091; connected
  migrate→startup→drain proof and every source/product gate remain required.

## TI-D090 — Fresh R1 remains blocked after the boot/epoch corrections

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; pre-materializer gate `BLOCKED`
- Owner/date: lead orchestrator after fresh data/migration, Android/power/privacy, and product/scope reviews, 2026-08-24
- Alternatives: begin materializers or production UI after the two authority fixes; build a new generic orchestration platform; keep rollout defaults off and close the remaining source-local reachability, quality, durability, lifecycle, export, and civil-day boundaries first
- Evidence: three independent reviewers returned `BLOCK`. R1-DM01 found incompatible boot-domain identities between policy and registration authority; R1-DM02 showed a delayed Activity observation could be stamped with a post-suppression automation epoch. The shared canonical `BootClockDomainProvider` and the Activity epoch's boot/elapsed effective boundary mitigate those two findings, and the focused 2026-08-24 policy/registration/Activity ingress/outbox/action/finalizer shard is `BUILD SUCCESSFUL`. The same round still found default event acquisition without a reachable product writer, sole-source Activity/Wi-Fi QoS plans below their declared capture floors, non-crash-auditable or unbounded pre-WAL lanes, provider-retirement orphan risk, conflated `ACTIVE`/`RECORDING`, missing immutable ambient civil-day identity, process/generation-local Cell replay identity, incomplete/minimization-unsafe portable export, and an automatic Transition re-arm release gap.
- Decision: keep all candidate materializers, ambient exposure, and production history/UI wiring off. A source acquisition path may become rollout-eligible only with a reachable typed product lane; expensive work remains optional and source-local; provider handoff must preserve or durably declare gaps; source-qualified evidence alone advances `RECORDING`; ambient facts require immutable civil-day allocation before admission to product history. R1-DM05 remains an explicit disagreement: migration-time terminalization of a v27 active runtime may conflict with later retained facts, so neither the current containment nor the review objection is treated as final proof until a populated recovery timeline resolves it. Generic purpose-blind Location enrichment remains out of scope; optional context is query-time, purpose-compatible, fresh, and creates no demand.
- Consequences: R1-DM01 and R1-DM02 are `MITIGATED_LOCALLY`, not a phase pass. The next bounded slices are the Activity/Wi-Fi sole-source QoS correction, crash-auditable Location/Wi-Fi/Cell ingress and provider retirement, automatic re-arm recovery, portable export minimization, immutable ambient day identity, and truthful lifecycle evidence. Ambient rollout remains default-off and begins only with a complete source vertical, currently planned as Steps. A fresh R1 rerun is required before materializers or product UI wiring.

## TI-D091 — Unreleased-v28 startup and source rollout fail closed at a reachable-lane boundary

- Status: `ACCEPTED_FOR_PRE_R1_CONTAINMENT`; fresh post-commit R1 `PENDING`
- Owner/date: lead orchestrator, 2026-08-24
- Alternatives: keep the old global event-acquisition default; relabel every source as legacy-owned
  even though the removed legacy provider entry points are not reachable; add an explicit contained
  owner and require a named reachable lane before source acquisition
- Evidence: before `f14a4a2b1`, `TrackingRolloutState.eventIngress()` selected `EVENT` for all six
  sources while production DI exposed no typed source product lane and the prior legacy provider
  producers had already been removed. That made the rollout state claim acquisition reachability
  which the repository did not have. The same integration review found Android service/FGS launch
  could race ahead of durable lifecycle intent and the frozen-v27 startup drain. The isolated-index
  app/API/engine checkpoint for the correction is `BUILD SUCCESSFUL` in `5m 39s` (`613` tasks).
- Decision: rollout schema v3 adds `SourceOwner.CONTAINED`. Missing state, v27 state, and the old
  global-v2 event marker migrate to a contained revision: no provider acquisition is authorized,
  retained legacy facts remain readable, and no writer is cut over. `EVENT` is valid only for an
  explicitly named source whose stage is `EVENT_SHADOW` or `EVENT_CANONICAL`; there is no
  all-source convenience default. A process-single-flight `TrackingStartupGate` makes deletion and
  the frozen-v27 terminal drain precede provider, service, policy, import/export, retention, and
  derived-data consumers. Manual/automatic starts persist prepared intent and accepted source/FGS
  candidates before external runtime; failed, stale, stopped, permission-revoked, and previous-exit
  paths converge without reviving terminal sessions.
- Consequences: this deliberately contains tracking in an unreleased development build until each
  source has a real typed lane. It is not a product rollout or a `QUERYABLE` claim. Retained v27
  facts remain available through their established readers, and no schema downgrade is introduced.
  The current v28 schema has 65 entities (51 released-v27 plus 14 narrowly owned additions). Three
  fresh R1 adversaries must attack the committed boundary before Steps materialization or any
  production history/UI wiring. Connected process/reboot/FGS and migrate-to-runtime evidence remain
  mandatory.
