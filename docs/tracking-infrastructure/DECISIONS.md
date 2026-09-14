# Tracking Infrastructure Decisions

Last updated: 2026-09-12

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

- Status: `SUPERSEDED_BY_TI-D179`
- Owner/date: user/product owner, resolved 2026-09-12 by TI-D179
- Alternatives: remove corroboration; retain it as a visible, consented `CONTROL` demand/setting
- Evidence: `BackgroundTrackingApi` owns a hidden `StepActivityCorroborator` whenever confidence-based automatic detection is active, even when Steps capture is disabled
- Decision: remove corroboration; see TI-D179 for current code evidence and consequences.
- Consequences: automatic control remains Activity-owned and no Steps control setting or demand is added.

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

- Status: `ACCEPTED_FOR_CONTAINMENT`; Steps membership is resolved by TI-D179, while Activity control-data retention remains open
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: let the legacy automatic-mode preference register Activity and Steps; infer control permission from a captured-source consent; require an independently eligible `CONTROL` epoch for each physical control registration
- Evidence: the legacy background API registered Activity whenever automatic mode was enabled and could register Steps for corroboration without an authoritative source/purpose decision. The v28 bootstrap deliberately imports only verified capture intent and denies new control/ambient purposes. Reusing capture consent would violate purpose separation and make an upgrade silently grant new processing authority.
- Decision: automatic Activity registration requires an eligible Activity `CONTROL` decision and epoch. TI-D179 removes optional Step corroboration, so no Steps control decision or epoch is created for enrichment. Missing, corrupt, unavailable, revoked, or superseded Activity control policy fails registration closed and stops automatic control use; it does not stop an independently started manual session.
- Consequences: automatic tracking remains unavailable after migration until explicit Activity control is granted. No retention duration, export behavior, or final product copy is inferred. The minimized Activity control-evidence retention/export/deletion contract still requires user/product/privacy direction before TI-200 can pass.

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

- Status: `SUPERSEDED_BY_TI-D062_AND_TI-D180`
- Owner/date: lead orchestrator, 2026-08-21
- Alternatives: accept an unstamped service intent; infer freshness from current motion state; fail closed until the trigger envelope is durable and consumed transactionally
- Historical evidence: at this decision's checkpoint the coordinator validated a partial envelope,
  while `TrackerServiceApi` and `TrackerServiceSourceSession` did not carry it and Android service
  launch happened first. TI-D062 later specified the legal trigger and ordering contract; TI-D180
  records the current implementation.
- Decision: do not weaken the coordinator validator. Production automatic v2 starts remain
  unavailable until trigger identity, control registration generation, policy/consent purpose,
  current automation epoch, consumption state, and Android action identity are durably connected
  before service launch.
- Consequences: the original containment condition has been implemented but remains unvalidated.
  Automatic product completion still depends on the source-specific manifest, provider, history,
  recovery, Android legality, and device gates; see TI-D180.

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

- Status: `ACCEPTED_FOR_PRE_R1_CONTAINMENT`; fresh post-commit R1 `PASSED_FOR_TI_410_ONLY` by TI-D099
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
  At this decision checkpoint, v28 had 66 entities (51 released-v27 plus 15 narrowly owned additions). Three
  fresh R1 adversaries passed the shared boundary for TI-410 only under TI-D099. Connected
  process/reboot/FGS and migrate-to-runtime evidence remain mandatory before source rollout.

## TI-D092 — Source rollout is partial by capture purpose, not all-enabled-or-nothing

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; implementation `IN_REVIEW` at `09f32d22e`
- Owner/date: lead orchestrator after corrected-boundary Android and product adversaries, 2026-08-24
- Alternatives: reject a session when any configured source is contained; silently drop contained sources; start every configured provider; start the reachable capture subset and report every rejected member by name
- Evidence: `TrackingSessionOwnership.resolve()` required every enabled setting to be `EVENT` owned. With Location and Steps configured but only Steps promoted, contained Location rejected the entire session, so the independently viable Steps source could not start. Automatic Activity control also required Activity capture/product reachability, making control-only use impossible without exposing an Activity capture lane.
- Decision: partition configured capture sources into a rollout-reachable subset and named contained subset. Capability and foreground-service acceptance operate only on the reachable subset; zero accepted capture sources fail closed, while a nonempty subset continues with contained/unavailable siblings reported as degradation. Add `CONTROL` ownership for an explicitly declared provider dependency: it may satisfy control demands but can never satisfy session/ambient capture or authorize a product projection. Expensive optional context remains enhancement-only and creates no provider demand.
- Consequences: each source can roll forward or back independently. `CONTROL` is not a shortcut to persistence, materialization, export, or UI. Parameterized only-X and mixed-configured/contained tests must prove exact provider and FGS sets before a source gate passes.

## TI-D093 — A rollout row cannot activate acquisition without an installed source lane and cursor

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; implementation `IN_REVIEW` at `09f32d22e`
- Owner/date: lead orchestrator after fresh data/migration adversary, 2026-08-24
- Alternatives: trust `EVENT_SHADOW` metadata; pin all WAL forever; activate a generic global projector; transactionally bind the named source to one concrete lane, activation floor, initialized cursor, and retention obligation
- Evidence: production DI has only the Activity automation projection. A persisted `eventShadow(STEPS)` could authorize the Steps provider even though no Steps consumer existed; the global Activity projection could skip that row and advance its own checkpoint, after which retention could prune the only durable Steps evidence. This violates durable-before-attribution and makes rollout metadata stronger than repository reality.
- Decision: source capture reachability requires one durable active binding for the exact source, product stage, projection ID/version, activation ordinal, and initialized source cursor. Installing that binding and advancing the rollout revision is one Room transaction; conflicting active identities fail. Retention considers the source cursor independently of unrelated projector progress. Control-only ownership does not require or imply a product lane.
- Consequences: current production rollout remains contained until the first concrete Steps lane is installed. Tests may construct rollout values for pure logic, but provider-facing persisted state cannot promote a source with metadata alone. This is the minimum one-writer/retention boundary, not a reusable materializer platform.

## TI-D094 — Live projection failure is source-local; only frozen-v27 recovery is startup-global

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; implementation `IN_REVIEW` at `eb5dc1885`
- Owner/date: lead orchestrator after fresh data/migration adversary, 2026-08-24
- Alternatives: keep every live projection behind one startup drain; ignore all recovery failure; globally gate frozen released recovery but drain each live source through its concrete lane
- Evidence: an Activity outbox identity collision stops the global `TrackingCoordinator`; `DefaultTrackingStartupGate` then reports `LIVE_V2` retry and prevents an unrelated manual Steps source from admitting facts. Poison isolation therefore existed inside a dispatcher but not at the process startup boundary.
- Decision: deletion generation, lifecycle/epoch authority, and the exact frozen-v27 obligation remain global gates. Live Activity projection/effects use the Activity lane and failures remain pending/failed for Activity only. Future sources join startup only through their own installed lane and never through the retained global dispatcher.
- Consequences: one broken source cannot disable a viable only-X sibling. Source-level completeness/failure must be exposed before product rollout; a globally `Ready` process is not proof that every source is `MATERIALIZED` or `QUERYABLE`.

## TI-D095 — Permanent optional-control states are terminal recovery outcomes

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; implementation `IN_REVIEW` at `eb5dc1885`
- Owner/date: lead orchestrator after fresh Android/power adversary, 2026-08-24
- Alternatives: map every failed Boolean to WorkManager retry; silently report success; distinguish accepted, terminal disabled/contained, and plausibly retryable outcomes
- Evidence: boot and post-deletion workers translated contained, revoked, disabled, or permanently unsupported Activity control into exponential WorkManager retries beginning after 30 seconds. No retry could change rollout, permission, consent, or hardware, so the chain spent battery without improving quality.
- Decision: automatic-control restoration returns `ACCEPTED`, `TERMINAL_DISABLED_OR_CONTAINED`, or `RETRYABLE`. Workers finish successfully for deliberate containment, disabled/revoked policy, and missing permission after bounded stale-demand cleanup. A provider result explicitly marked retryable remains retry work; the current Play Services availability signal cannot safely distinguish permanent absence from a transient or user-resolvable outage.
- Consequences: optional control remains optional and recovery is power-honest. A later user/policy/rollout change schedules ordinary reconciliation; WorkManager is not used as a substitute for that state change.

## TI-D096 — Manual, automatic, and ambient Steps are three independent gates

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`
- Owner/date: lead orchestrator after fresh product/scope adversary, 2026-08-24
- Alternatives: complete all Steps modes as one batch; omit ambient; prove the simplest manual vertical and independently gate automatic control and ambient product behavior
- Evidence: the prior ST-02/TI-410 row bundled session deltas, automatic Activity control, app-scoped ambient continuity, civil-day allocation, export/deletion, and product query. A failure or unresolved privacy choice in either advanced mode could delay the first useful only-Steps session proof and encourage a generic framework before a concrete lane existed.
- Decision: TI-410 proves manual/session Steps first. TI-410B adds automatic Steps only after legal fresh Activity control, bounded purpose-limited retention, and no history/export leakage are proven. TI-410C adds default-off ambient Steps only with explicit consent, immutable day identity, retention, export/deletion, completeness, and sessionless product visibility.
- Consequences: shared mechanics are extracted only from demonstrated use. Ambient persistence remains wanted where useful, but it is neither silently enabled nor a prerequisite for manual Steps quality. This supersedes TI-D090's earlier consequence that immutable ambient civil-day identity precedes every first materializer; that requirement applies to TI-410C and other ambient product gates, not TI-410 manual/session Steps.

## TI-D097 — A v27 coordinator lease row is transient ownership, not retained history

- Status: `ACCEPTED`; exact-boundary connected migration rerun pending
- Owner/date: lead orchestrator, 2026-08-25
- Alternatives: retain the v27 wall-clock owner/expiry as if it remained authoritative; invent elapsed-time/boot values for the old owner; clear only the lease rows and let v28 recovery reacquire under a real boot/process generation
- Evidence: `source_coordinator_lease` stores an owner token and wall-clock acquisition/expiry for temporary projection/lifecycle exclusion. It is not referenced by a source fact, session, history query, export, or user preference, and its owner cannot survive process death or reboot. Released factual/runtime evidence remains in the 51 v27 fact, WAL, checkpoint, outbox, session, and run tables. The populated migration suite proves those rows survive while unprovable runtime is fenced, and the v28 table adds boot identity, generation, and monotonic elapsed times.
- Decision: `MIGRATION_27_28` deletes only the in-flight rows from `source_coordinator_lease`, preserves the table, and lets recovery acquire a fresh boot-aware monotonic lease. It never translates a stale wall-clock expiry into current authority. This supersedes TI-D052's wording that the v27 lease's diagnostic wall times are retained; durable run/session diagnostics remain retained, but ephemeral lock ownership does not.
- Consequences: this is not authorization to delete or reinterpret any observation, attribution, source fact, session, run, preference, exportable value, or pending recovery obligation. An exact populated device migration still must pass before schema freeze, and recovery must remain fail-closed if a fresh lease cannot be acquired.

## TI-D098 — Capture closure requires executable admission and a drained callback revision

- Status: `ACCEPTED_FOR_FRESH_R1`; TI-410 implementation authorized by TI-D099, source rollout still `BLOCKED`
- Owner/date: lead orchestrator after three corrected-boundary pre-audits, 2026-08-25
- Alternatives: trust rollout metadata alone; append authorization from a pre-transaction snapshot; retire capture immediately and hope in-flight callbacks finish; keep permits forever when callback work times out
- Evidence: a structurally plausible but non-executable lane could admit capture WAL; duplicate/malformed active lane rows could reopen admission; Activity capture-to-control/retire changes could publish stale durable demands after a concurrent containment change; timed-out/cancelled callback work could leak a process permit forever. These timelines violated one authority, immutable observed-time eligibility, durable-before-attribution, and deletion/retirement convergence.
- Decision: capture admission requires exactly one structurally valid active lane and exact ownership by the current binary's `ExecutableSourceLaneCatalog`, checked in the WAL transaction. Every Activity authorization mutation re-reads durable demands and recomputes policy, consent, rollout, and lane eligibility in the same Room transaction as append. A capture-closing mutation fences new process-local callback entries, waits for earlier entries to unwind, repeats that transactional derivation, appends the revision, and durably acknowledges the exact maximum capture authorization revision. Permits always release when bounded callback work unwinds; only a durable admission publishes downstream work.
- Consequences: no callback can borrow a stale capture revision and deletion need not wait forever on a timed-out receiver. A storage outage may still produce an explicit source completeness gap; crash-auditable pre-WAL handoff remains a source activation gate for the five non-Activity adapters and is not replaced by a leaked permit. This adds no materializer, ambient default, generic contribution platform, or production UI authorization.

## TI-D099 — Corrected R1 authorizes one concrete manual Steps vertical, not another platform

- Status: `ACCEPTED_AFTER_FRESH_R1_ADVERSARIAL_REVIEW`; TI-410 implementation `IN_PROGRESS`
- Owner/date: lead orchestrator after three fresh data/migration, Android/power/privacy, and product/scope reviews, 2026-08-26
- Alternatives: continue broad shared-spine work; start all source materializers and history UI; prove one complete source using existing source-local foundations and generalize only after a second concrete need
- Evidence: the committed policy/lifecycle/broker/ledger boundary at `d51723280` passes complete tracker-engine, app, Dashboard, and schema host verification. All three fresh reviewers found no remaining shared `BLOCKER` or `HIGH` that requires another horizontal framework before manual Steps. They did find source-completion gates: one executable Steps binding and lane activation; one atomic logical-fact/receipt/cursor/deletion transaction; truthful baseline/zero/positive/partial/query states; source-aware existing tracking UI; typed manual-start results at every existing entry point; and portable typed Steps export/import. They also found two bounded shared issues, fixed in `bfa0c9da1` and `648f894a4` with 310/310 Activity and 652/652 app tests.
- Decision: begin only TI-410 manual/session Steps. Reuse `StepSourceRuntime`, the shared hardware counter, released `StepInterval` only as frozen legacy query evidence, `SourceProductProjectionLaneEntity`, collected-data deletion epoch, and existing Today/session/tracking/export surfaces. Add only the Steps-specific identity, receipt/correction, cursor transaction, query truth, and adapters required to make that path exactly-once in effect and product-visible. Keep all rollout ownership `CONTAINED` until the complete path is installed and verified together. Do not introduce a universal mutation language, six unused writer schemas, a generic dirty-day engine, or a new Days destination.
- Consequences: automatic Steps (TI-410B), ambient Steps (TI-410C), Health Connect/Recording continuity, other source materializers, and broad DayOverview/UI work remain blocked. The first positive post-baseline delta may advance Steps `RECORDING`; baseline-only, covered zero, partial, materializing, and unavailable remain distinct. TI-410 is not `DONE` until crash/replay/correction/deletion/import/export/no-resurrection, production query, existing UI, and source-only registration assertions pass. Device/provider proof is still required before rollout.

## TI-D100 — Permanent startup blocks wait for state change, not recurring work

- Status: `ACCEPTED_AND_IMPLEMENTED` at `648f894a4`
- Owner/date: lead orchestrator after corrected-R1 Android/power/privacy review, 2026-08-26
- Alternatives: keep retrying a permanent startup block; return success and require process death; retain one process-local epoch-fenced obligation and consume an authoritative Ready transition
- Evidence: a post-deletion worker that observed `TrackingStartupResult.Blocked` previously completed while writer and Activity latches could remain paused if the user repaired storage in the same process. Polling the permanent condition with WorkManager would spend battery without new evidence. Focused recovery/startup and architecture tests plus the full 652-test app suite pass.
- Decision: a blocked worker records one process-local obligation for the exact collected-data epoch and startup generation, then completes. `Application.reconcileTrackingStartup()` rearms the existing unique worker only when authoritative reconciliation becomes `Ready`. A newer deletion epoch invalidates the obligation; a Ready-before-marker race receives at most one bounded retry; a failed enqueue retains the obligation for another explicit Ready signal. Process death clears it because the protected latches are also process-local and reconstruct on cold start.
- Consequences: no infinite retry/poll loop is introduced. This reopens no provider and grants no policy, consent, capture, or product authority; the rearmed worker rechecks all durable fences normally.

## TI-D101 — The first Steps writer is source-local, hint-driven, and dormant until product truth exists

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_DORMANT_CANDIDATE` at `8bb6d606f`; production activation `BLOCKED`
- Owner/date: lead orchestrator after source-local data and scope adversaries, 2026-08-26
- Alternatives: build a generic six-source materializer; poll the WAL; activate metadata before a reader exists; add one concrete Steps lane that wakes only from durable admission/startup hints and remains unreachable in production until its full source gate passes
- Evidence: corrected R1 authorized only the smallest manual/session Steps vertical. The existing source-product lane already carries exact source, writer version, binding generation, capture mask, stage, rollout revision, activation floor, cutoff, retention obligation, and cursor. A generic command language or scheduler would add unused machinery and battery work without making Steps queryable.
- Decision: `steps-session-facts` is the sole candidate contract for new Steps facts. One Room transaction verifies the complete lane authority and expected cursor, writes or verifies a self-contained fact receipt, commits source evidence/failure state, and advances the cursor last. A conflated in-process hint follows only durable admission or startup recovery; there is no polling or wake-reliable claim. `EVENT_SHADOW` validates and advances only, never writes facts or product evidence. Production keeps the executable catalog empty until the legacy/candidate writer fence, logical-session bridge, typed query state, export/import, and existing product consumers pass together.
- Consequences: this advances the core architecture without creating another platform. Host tests can exercise an explicit fixture lane, but no runtime source becomes `MATERIALIZED` or `QUERYABLE` from this decision. Pressure may reuse only mechanics proven to be genuinely shared after its own source design.

## TI-D102 — Partial retention removes Steps payloads at the monotonic privacy floor and preserves redacted tombstones

- Status: `ACCEPTED_AND_IMPLEMENTED` at `af846143c`
- Owner/date: lead orchestrator after two fresh retention adversaries, 2026-08-26
- Alternatives: prune WAL/legacy intervals but keep self-contained facts; delete every revision including deletion receipts; retain payloads until a future generic deletion framework; remove expired UPSERT payloads transactionally while retaining already-redacted RETRACT identities
- Evidence: the candidate UPSERT deliberately outlives its legacy `StepInterval` and WAL, so the existing retention workers could otherwise leave interval, count, session, consent, and timing payloads queryable indefinitely. The retained-from boundary is monotonic; a later wider retention preference cannot weaken a previously established privacy floor. Room serializes a concurrent lane write with retention: a pre-boundary commit is deleted, while a post-boundary drain observes the synchronized floor and skips the stale event.
- Decision: both raw-retention transactions delete Steps UPSERT revisions whose complete interval ends strictly before the authoritative `CollectedDataLifecycleSnapshot.retainedFromMs`. Existing redacted local-delete RETRACT rows survive so replay/import cannot undo deletion. The lane is then drained under the exact startup-generation operation lease, after which WAL pruning recomputes the actual source-local retaining cursor.
- Consequences: interval-end equality remains retained, overlapping facts remain intact, expired product payload is not left behind, and retention cannot race startup/deletion or prune the poison row before stale-failure reconciliation. This is Steps-specific evidence, not a generic retention abstraction or approval of final retention durations.

## TI-D103 — Destination ownership is an admission fence, not a historical selector or cutover coordinator

- Status: `ACCEPTED_AFTER_STEPS_R1_ADVERSARIAL_REVIEW`; implemented foundation at `ef1da62d2`,
  cutover and product query `BLOCKED`
- Owner/date: lead orchestrator after data/migration, Android/power/privacy, and product/scope
  adversaries, 2026-08-26
- Alternatives: select every historical result from the current global owner; activate the
  candidate after a metadata CAS; keep product wiring and label inconsistent states unavailable;
  treat the owner row as a narrow admission fence and add effective provenance plus a real cutover
  protocol before readers or activation
- Evidence: the attempted Steps reader selected the current global owner for every retained
  `SessionSegment`, while candidate facts were service-run-scoped and completeness was
  logical-session-scoped. A legacy→candidate transition could therefore hide old legacy history;
  a second run could change the first run's state; rollback generation 3 was rejected by writers
  hard-coded to generation 1; full deletion removed the candidate lane but retained candidate
  ownership; and the legacy write check could roll back unrelated destinations in a mixed batch.
- Decision: `source_destination_owner` is a permanent exact writer-admission fence only. Every
  product slice must resolve immutable/effective writer provenance for that segment or pass a
  verified retained-history backfill boundary. Completeness, lag, facts, and deletion receipts must
  use the same service-run/product scope. Owner transition requires a coordinator that fences queued
  generations, drains or suppresses the retired destination without failing unrelated writes,
  binds the replacement writer/read contract to the new generation, and establishes a coherent
  post-deletion generation. The low-level CAS has no production caller. Production Steps query/UI
  wiring remains absent until these assertions and observable materialization refresh pass.
- Consequences: legacy generation 1 remains the sole active Steps destination; the candidate lane
  stays dormant. The durable ID bridge and exact write fence remain because they add no acquisition,
  polling, or battery cost and are required to solve the identified timelines. Query-only DAO
  helpers and the premature Trip Detail integration were removed rather than hidden behind a flag.

## TI-D104 — Every attempted provider action remains exact retirement authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `ca5b1ffad` and `47fb0d842`; materializer and rollout
  authority unchanged
- Owner/date: lead orchestrator after fresh R1 correction adversaries, 2026-08-26
- Alternatives: stop every process-local runtime for a source; retire only acknowledged start
  actions; treat a newer automatic request as proof the predecessor is stale; retain exact attempted
  and accepted run claims until strict release evidence exists
- Evidence: the coordinator persists an action as `APPLYING` and increments its attempt before the
  runtime call. A runtime can bind that claim and publish provider work before cancellation leaves
  the action unsettled. Retirement previously reconstructed only `START_ACCEPTED` and
  `CLEANUP_REQUIRED`, so it could synthesize completion while that provider remained live. A
  duplicate automatic request could likewise directly finalize a fully `START_ACCEPTED` provider.
  Service destruction could choose restart suspension after cancellation and receive
  `NoActiveSession` for a durable `STARTING` or `RECONFIGURING` run. Two fresh reviewers independently
  falsified those timelines; composed tests and the complete 1,640-test engine suite now pass.
- Decision: every exact source action with `attemptCount > 0` and latest ownership status
  `APPLYING`, `START_ACCEPTED`, or `CLEANUP_REQUIRED` remains retirement authority for its logical
  session and service run. Claims are tried newest first. A latest exact `STOP_ACCEPTED`, which
  requires complete provider removal, flush, app drain, and matching run membership, suppresses
  older claims for that source. Successful whole-run retirement terminalizes historical unresolved
  attempts. Cancellation after service-session ownership attaches forces full stop; restart
  suspension is reserved for a durably clean active run. The runtime-stop dispatcher holds commands
  during teardown and releases inactive fallback only after cleanup succeeds. It never performs a
  global source-family shutdown.
- Consequences: unrelated control, ambient, or successor registrations remain untouched; a newer
  automatic request cannot erase a potentially live predecessor; failed removal remains durable
  `CLEANUP_REQUIRED`; and a destroyed service retries with capped exponential delay instead of
  falsely completing or spinning. The added work occurs only during failed teardown and prevents a
  live provider from wasting more battery. Because v28 is unreleased, the exact run/action fields
  are added directly to v28. This decision changes no acquisition cadence, persistence eligibility,
  canonical writer, ambient consent, product query, or UI gate.

## TI-D105 — A queued Steps command freezes its effective writer generation before acknowledgement

- Status: `ACCEPTED_AND_IMPLEMENTED` at `30051416d`; candidate activation/cutover `BLOCKED`
- Owner/date: lead orchestrator after three focused writer/provenance/privacy reviews, 2026-08-26
- Alternatives: resolve every retained command from the latest global owner; add a second
  service-run binding table; silently acknowledge a mismatched command; fail the entire mixed-source
  batch; stamp the existing pending command from its exact immutable manifest and disposition only
  its Steps destination
- Evidence: resolving writer ownership at flush time could reinterpret an older command after a
  manifest revision or ABA owner transition. Treating a missing permanent owner row as an ordinary
  mismatch could acknowledge valid Steps without any writer. Rolling back the mixed batch on an
  unverified Steps stamp prevented unrelated Location facts from progressing. Policy-tier
  reactivation also rebuilt `ProcessorContext` with session ID zero, so valid manifest/session
  provenance could be rejected after an otherwise legal transition. Quarantine copies the complete
  serialized signal and therefore must expire from provider acquisition time rather than its newer
  quarantine time.
- Decision: source-event-backed Steps admission reads the event's exact manifest revision and stamps
  its writer owner/generation into `pending_signal`. The already-durable row wins an ambiguous retry.
  The legacy writer reloads `source_destination_owner` inside the same Room transaction and writes
  only an exact owner/generation match; missing authority throws a retryable invariant failure.
  Candidate and stale legacy stamps suppress only `StepInterval`; a null/null unverified stamp is
  moved atomically to source-local quarantine. Kotlin entity validation excludes half-pairs,
  nonpositive generations, and unknown owners before recovery. Quarantine stores `acquired_at_ms`
  and both raw-retention workers prune by that clock. Released-v27 pending rows are stamped legacy
  generation 1; v27 quarantine has no trustworthy acquisition clock and migrates to fail-closed
  zero. Processor tier activation copies the original session/resume context.
- Consequences: no new provider registration, wakeup, polling loop, per-run binding table, generic
  materializer, or product surface is introduced. Legacy generation 1 remains canonical and the
  candidate remains dormant. TI-VS28 still requires an atomic coordinator, historical effective
  selection, rollback/deletion reconstruction, process-death tests, and production visibility.
  Logical deletion is proven; forensic byte erasure from raw SQLite backups remains an explicit
  separate privacy verification boundary.

## TI-D106 — Historical Steps selection follows immutable service-run writer intent

- Status: `ACCEPTED_AND_IMPLEMENTED_DORMANT` at `b20e1efaa`; cutover and production query `BLOCKED`
- Owner/date: lead orchestrator after three focused design audits and fresh data adversaries,
  2026-08-27
- Alternatives: select all history from the current global owner; add another writer-binding table;
  backfill every legacy segment before any read; use immutable service-run manifests and exact lane
  evidence with typed incompleteness while keeping the selector internal
- Evidence: a global-owner selector would hide legacy history immediately after candidate cutover,
  while logical-session completeness could mix separate physical runs. Retention could expose an
  older corrected attribution, a redacted tombstone could inherit stale epoch authority, and a
  retired or malformed lane could remain `MATERIALIZING` forever. `SessionSegment` already carries
  the exact logical/session-run bridge, every effective manifest carries writer provenance, and the
  source-local lane and completeness tables already carry the exact evidence needed; another schema
  or generic history platform would not resolve these timelines.
- Decision: one Steps-specific selector verifies service-run membership and manifest checksums,
  rejects mixed writers within a run, resolves the exact active or retired writer lane, and selects
  fact state by the latest UPSERT's effective run scope plus the fact-global latest revision.
  Availability, evidence, materialization, and coverage remain independent. Retention removes all
  older UPSERT revisions when the latest effective UPSERT expires, preserving redacted tombstones so
  an older scope cannot resurrect. Candidate values require exact acquisition completeness, lane
  progress, retention, and collected-data-epoch evidence; legacy positive values remain explicitly
  degraded and legacy zero never becomes verified zero.
- Consequences: historical reads no longer depend on present write authority and incur no provider,
  wakeup, polling, or schema cost. The selector stayed internal through transition and deletion-rearm
  proof; TI-D108 now exposes it through a read-only facade, but it remains product-inert until typed
  export/import and completeness-safe consumers pass. This decision does not authorize automatic or
  ambient Steps, another source materializer, `DayOverview`, UI wiring, or rollout.

## TI-D107 — Steps writer transitions are source-specific, atomic, and contained

- Status: `ACCEPTED_CONTAINED`; transition implementation `IN_REVIEW` at
  `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a`, deletion seam verified at `3b2365547`;
  production activation/consumer `BLOCKED`
- Owner/date: lead orchestrator after three independent reconciliation adversaries, 2026-08-27
- Alternatives: promote Steps by changing rollout metadata; create a generic all-source cutover
  framework; expose a source-specific transition that atomically moves exact lane, owner, rollout,
  cursor, and lifecycle authority while keeping first activation an explicit release action
- Evidence: database review verified one-transaction candidate promotion and owner CAS, rollback
  containment/cutoff/drain/retirement/legacy restore, ABA-safe deletion re-arm, run-boundary gates,
  and candidate receipt/cursor atomicity with no remaining `BLOCKER`, `HIGH`, or `MEDIUM`.
  Lifecycle review found a `HIGH` timeline where canonical rollout/admission survived destination
  owner drift; rollout save/load/repair/authorization and transaction-local WAL admission now require
  the exact candidate owner. App review found a `MEDIUM` residual `step_interval` deletion timeline;
  re-arm now refuses residual rows and a focused test covers it. The exact merge passes serialized
  `ciCheck --continue` in 21m 46s with 1,991 actionable tasks, 224 executed and 1,767 up-to-date.
- Decision: `StepsSessionFactWriterTransitionCoordinator` is the only Steps destination transition
  boundary. It may promote a verified caught-up shadow lane only while lifecycle/run/action/command/
  callback and legacy-writer boundaries are quiescent; rollback first contains capture and latches a
  cutoff, then retires the drained candidate and restores legacy ownership. Full deletion may re-arm
  an empty monotonic generation only for a writer that was already canonical; it cannot perform the
  first cutover. Rollout authorization and durable capture admission recheck exact destination-owner
  authority. There is no ordinary production first-activation or rollback caller.
- Consequences: legacy generation 1 remains the sole active production Steps owner. The transition
  adds no provider registration, acquisition cadence, polling, ambient default, product query, or UI.
  TI-B160 closes the prior `MEDIUM` same-process
  `DefaultCollectedDataDeletionService -> AppDatabase -> transition re-arm` gap while deletion/
  startup barriers remain closed. Production consumer truth, typed export/import, continuous
  deletion/replay visibility, device/provider/process/energy evidence, automatic/ambient Steps, and
  rollout remain blocked. Reconciliation topology and next commands are in
  `CONTINUATION_HANDOVER.md`.

## TI-D108 — Historical Steps availability requires affirmative retained evidence

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_READ_ONLY_FACADE` at `c5118e186`; production consumer and
  `QUERYABLE` gate `BLOCKED`
- Owner/date: lead orchestrator after three focused history-contract adversaries, 2026-08-30
- Alternatives: infer `DISABLED` whenever an immutable manifest lacks a Steps capture binding;
  collapse every unresolved condition into one product failure; add another historical capability
  snapshot schema before exposing any read; expose one narrow selected-session query that reports
  only states justified by retained policy, manifest, writer, fact, and completeness evidence
- Evidence: a manifest can lack Steps because policy explicitly disabled it, because enabled policy
  and capture intent disagree, or because required historical policy/capability evidence is missing.
  The retained v28 records do not yet prove historical `UNSUPPORTED`, `PERMISSION_REQUIRED`, or
  `OS_LIMITED` states. Fresh review also showed that legacy unknown coverage is not a lower bound,
  candidate output behind its lane is partial, baseline-only evidence is not `ACTIVE`, and a covered
  zero cannot claim the positive-delta `RECORDING` transition.
- Decision: `TrackingHistoryRepository.observeSession(segmentId)` is a read-only facade for one
  selected local segment. `DISABLED` is emitted only when every immutable policy revision referenced
  by the run contains an explicit Steps policy with `enabled = false`; a valid captured binding proves
  `AVAILABLE`; unresolved or inconsistent retained evidence becomes `UNAVAILABLE`. The public
  contract keeps availability, acquisition evidence, product state, and coverage independent.
  Positive values require `RECORDED`; a covered zero requires `ACTIVE`; missing or baseline-only
  evidence remains null; every non-ready product state has a stable cause. The facade observes the
  exact Room tables read by the selector, performs the selected snapshot in one transaction, and
  neither starts a provider nor mutates a writer or projection.
- Consequences: the API is Hilt-bound but has no production consumer, so this is not a `QUERYABLE`,
  UI, export, activation, or rollout claim. The current observer invalidates on eleven relevant tables
  and is proportionate for one selected session; profile and introduce a shared/batched composition
  only before list/day fan-out. Do not create one observer per history row or infer unsupported,
  permission, or OS-limited history until durable evidence exists.

## TI-D109 — Deletion authority lands dormant before any permanent-trip product wiring

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_DORMANT_AUTHORITY` at `03bbda2f1`; production session
  deletion, import, retention, and UI semantics remain `BLOCKED`
- Owner/date: lead orchestrator after three fresh R1 adversaries, 2026-08-30
- Alternatives: keep direct `session_segment` deletion and accept retained facts; wire a
  Steps-only retraction transaction through `DefaultTripRepository`; introduce a universal
  deletion platform; first persist the smallest source/purpose/run fence and make only the dormant
  candidate Steps writer and selected-session reader honor it
- Evidence: the attempted production wiring was falsified independently. The active legacy Steps
  writer has no exact run attribution or fence check; service teardown can rewrite a segment after
  a terminal source-run row appears; active/`STOPPING` deletion has no typed UI outcome; persisted
  `daily_summary` rows are not invalidated; trip retention bypasses the proposed transaction; and
  raw merge import, typed portable import, future writers, and other captured sources do not yet
  share one deletion authority. The UI currently promises permanent deletion, so a
  presentation-only implementation would be materially misleading.
- Decision: v28 gains one payload-free `source_deletion_fence` keyed by source, purpose, scope kind,
  and a domain-separated digest of immutable logical/run identity. It retains a positive fence
  generation, collected-data epoch, deletion time, and integrity checksum. The dormant candidate
  Steps WAL lane checks the exact fence before validating or writing a fact and treats a matching
  previously terminal failure as lifecycle-rejected on its next drain. The immutable
  selected-session selector returns a named deleted state and its Room observer invalidates on fence writes.
  UPSERT facts must carry deletion generation zero. Full collected-data deletion removes fences.
  No production path creates a scope fence yet; `DefaultTripRepository`, active legacy generation
  1, retention, import/export, day summaries, and UI are deliberately unchanged.
- Consequences: this commit is a battery-neutral schema and dormant data-plane/read-path primitive,
  not selected-trip deletion or no-resurrection completion. A real deletion command must live at a
  narrow data-plane mutation owner, return typed product outcomes, and use durable presentation
  acknowledgement before deleting source-neutral session/Ski rows. Source fences stay inside
  legacy, candidate, and portable-import Steps writes. Selected deletion, cutoff retention,
  portable import, and read-only export remain distinct services that may share one narrow
  Steps-specific fence/retraction evaluator. The unreleased v28 schema now has 69 entities: 51
  released-v27 entities plus 18 narrowly owned v28 additions. No v29 shell is created.

## TI-D110 — Empty-session reclamation requires presentation quiescence, not terminal SQL

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_CONTAINMENT` at `88309387d`, with remaining zero-sample read
  correction at `24b9aeffb`; lifecycle-owned orphan reclamation remains `BLOCKED`
- Owner/date: lead orchestrator after lifecycle, Room, WorkManager, and final-diff review,
  2026-08-30
- Alternatives: keep the six-hour global `sample_count = 0` delete; add an exact terminal-run SQL
  predicate; retire the mutation indefinitely; retire it now and later reclaim only from a durable
  exact ownership plus post-presentation-quiescence boundary
- Evidence: `sourceSession.stop()` persists terminal logical/run state before the service drains
  cycles and calls `orchestrator.shutdown()`. `SessionTrackerComponent.onDisable()` and the Ski
  writer may therefore still perform final writes after source terminality. No predicate over the
  current lifecycle tables can distinguish that interval from an abandoned row. Process death can
  also strand a preinserted zero-sample row because the durable start descriptor does not yet
  receive its generated segment ID. Retention is optional, so those rows can persist. Before this
  decision, overlapping/live-stat and achievement count queries could treat such a placeholder as
  a trip even though normal trip queries already required `sample_count > 0`.
- Decision: remove `SessionSegmentDao.deleteEmpty()`, stop scheduling the periodic maintenance
  mutation, never restore it after collected-data deletion, and keep the historical worker class as
  an inert WorkManager compatibility shell. UI maintenance startup requests asynchronous
  cancellation; collected-data deletion awaits cancellation and never restores the work. A
  persisted request may still wake until cancellation completes. `88309387d` requires positive
  samples for daily/live plus source/all-time activity reads; `24b9aeffb` extends that rule to
  app-age/hour/night/dawn and ActivityRecognition reads. Every zero-sample row, including matching
  legacy/imported rows, is excluded from those named queries; other DAO reads are unchanged. Future
  graceful cleanup must persist and validate exact `(logicalTrackingId, serviceRunId,
  sessionSegmentId)` ownership and run only after successful presentation shutdown. Previous-exit
  orphan cleanup is a separate later slice and requires proof that the session is non-recoverable.
  Terminal SQL may be defense in depth, but call-site quiescence is the proof.
- Consequences: the active-row data-loss race and database mutation are contained immediately. UI
  startup only requests cancellation, so a persisted inert wake remains possible until the
  asynchronous operation completes; deletion provides the awaited path. Zero-sample rows no longer
  change the named product or ActivityRecognition reads, while unchanged DAO paths retain their
  prior semantics. A physical orphan may remain indefinitely after a crash; that bounded storage
  debt is explicit and is not product deletion, forensic erasure, durable writer acknowledgement,
  or process-death cleanup evidence. Do not restore periodic reclamation or wire permanent-trip
  deletion until the exact identity and presentation-quiescence contract passes.

## TI-D111 — Manual Steps stays product-first and source-local; no generic mutation platform

- Status: `ACCEPTED_AFTER_ADVERSARIAL_SCOPE_CORRECTION`; implementation remains `BLOCKED`
- Owner/date: lead orchestrator after independent next-wave scope and document-falsifier reviews,
  2026-08-30
- Alternatives: require both presentation acknowledgement and universal session/Ski tombstones;
  route selected delete, retention, import, and export through one command; add a permanent Room
  fence observer; finish every numeric consumer before exercising the only-Steps product; keep each
  operation source-local and prove the smallest product path early
- Evidence: the first handoff draft put a Steps source/purpose fence into source-neutral session/Ski
  writers while also requiring quiescence, expanded a row-oriented delete into batch retention and
  read-only export, omitted `LegacyUnverifiable`/`UnsupportedScope` UI outcomes, covered Trip Detail
  but not the Location-shaped live surface, excluded only Activity/Location in the sole-source test,
  assumed explicit-zone day recomputation that the current aggregator cannot perform, and proposed a
  permanent Room observer even though the Steps lane already has conflated `requestDrain()` plus
  startup recovery.
- Decision: define and test contained source-adaptive historical and live Steps states first; missing
  Steps is never zero, and Steps-only hides Location-specific product controls. Choose durable
  post-presentation acknowledgement for source-neutral presentation writers. Fence only legacy,
  candidate, and portable-import Steps writes. Expose distinct `DeleteSelectedSession`,
  `RetainStepsBefore`, `ImportPortableSteps`, and `ExportPortableSteps` contracts in an API boundary,
  backed by a narrow data-plane Steps evaluator without reversing module dependencies. Deletion
  returns `Deleted`, accepted `NotFound`, `BlockedActive`, `LegacyUnverifiable`/`UnsupportedScope`,
  or retryable failure; only the first two dismiss the row. Define the existing summary's zone
  authority before day repair. Use the lane's post-commit drain hint and startup recovery, not a
  worker, polling loop, or long-lived observer.
- Consequences: immediately after contained history/live consumer wiring, the manual smoke asserts
  the exact capture/registration set `{Steps}`, no other capture/control/ambient demand, listener
  removal after stop, no scheduled recovery, and truthful `RECORDING -> MATERIALIZED -> QUERYABLE`.
  Production activation still waits for deletion, separate retention and portable round trip,
  numeric-consumer completeness, host/device/process gates, and fresh review. Previous-exit orphan
  cleanup, automatic Steps, and ambient Steps remain separate later slices.

## TI-D112 — Selected Trip Detail may consume durable Steps only as a contained read-only slice

- Status: `ACCEPTED_AND_IMPLEMENTED_CONTAINED` at `f9b1c3c45`; source-only discovery, safe deletion,
  live/day consumers, and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after three independent product/Android/validation adversaries and
  one fresh corrected-diff reviewer, 2026-08-30
- Alternatives: keep the facade unused until every deletion/list/day contract exists; wire the
  existing screen and retain its presentation-only delete; infer Location presence from
  `SessionSegment.sampleCount`; add a generic day/UI platform; expose one read-only selected-session
  Steps metric while withdrawing unsafe mutation and unsupported source-adaptive assumptions
- Evidence: the current runtime increments `SessionTrackerComponent.collections` per processing
  cycle and persists it as `sampleCount`, so a Steps-only or Pressure-only run can have a positive
  count with no Location. `TripPresentationRepository.deleteTrip` removes only the presentation
  segment while append-only candidate Steps facts have no foreign key or scoped retraction. Fresh
  review also proved that a terminal Room-flow exception needed an explicit recovery action, an
  internal supplemental-data waiter could keep history subscribed off-screen, and a slow initial
  history result must not hide an otherwise valid trip.
- Decision: Trip Detail consumes exactly one `observeSession(segmentId)` stream. It renders the trip
  immediately with Steps `Materializing`, then maps durable availability/evidence/product/coverage
  to explicit typed UI states; corrections may update the value, history failure is isolated to
  Steps, and the rest of the trip stays visible with a user-triggered selected-history reread. That
  retry does not activate materialization. The state stream is `WhileSubscribed`, Compose
  collection is lifecycle-aware, and optional Location/Ski reads begin only from the composed
  screen with one cancellable job. The screen does not infer capture or Location from `sampleCount`
  and does not expose direct deletion until a typed data-plane command can retract/fence all scoped
  facts. No provider demand, writer owner, cadence, schema, or rollout state changes.
- Review disposition: `ACCEPTED_AS_QUERYABLE_BLOCKER` for ordinary source-only discovery. Existing
  `TripDao`/`SessionSegmentDao` list paths require positive `sample_count` and can therefore hide a
  source-only session even though a known segment ID renders truthfully. This is not cleared by the
  corrected-diff review and must be resolved before any `QUERYABLE` claim.
- Consequences: this is useful production UI truth for a known selected segment, but not ordinary
  sole-source discoverability or `QUERYABLE`. Existing list queries still use positive
  `sample_count`, other statistics delete paths remain unsafe, and no exact historical capture-set
  or qualifying-Location signal exists to hide map/export/Location facts truthfully. Add that
  contract before source-adaptive layout or live Dashboard wiring; add shared/batched composition
  before list/day use. New copy currently falls back to English in localized builds. Activation,
  automatic/Ambient Steps, device proof, and other sources remain outside this decision.

## TI-D113 — Presentation settlement belongs to the exact physical service run

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_SETTLEMENT_AUTHORITY` at `9aeb8853a`; physical cleanup,
  source materialization, logical-entry product grouping, and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after independent schema/lifecycle/segment-ownership adversaries,
  2026-08-30
- Alternatives: resume one presentation row across replacement service runs; treat the DataStore
  descriptor as ownership authority; infer writer quiescence from terminal lifecycle SQL; add a
  generic tombstone/evidence-disposition platform; bind one presentation segment to each physical
  run in Room and acknowledge only completed writer shutdown
- Evidence: final plan §4.3 permits one logical entry to own multiple service runs and segments.
  Source lifecycle becomes terminal before `SessionTrackerComponent`, the processing pipeline,
  Ski writer, and summary attempt complete. Wi-Fi- or Cell-only evidence is not represented by the
  source-neutral component's counters, so an empty segment cannot be classified from that writer
  alone. v28 has never shipped, allowing the direct 27→28 migration and schema to gain this exact
  ownership without a v29 shell.
- Decision: `source_service_run` is the authority for a nullable, uniquely reverse-indexed
  `session_segment_id`. A new physical run clears the recovery mirror and creates its own segment
  under the existing logical ID; same-run recovery resumes only the exact Room binding. New runs
  start `PENDING`; migrated v27 rows are `LEGACY_UNVERIFIABLE`; completed `FINALIZED` or `FAILED`
  runs may become `QUIESCED` only after the orchestrator returns an exact receipt after all
  presentation writers have stopped. DataStore mirrors this tuple through an atomic exact CAS but
  never authorizes it. `NOT_TERMINAL` and operational database failures retry; missing or mismatched
  ownership is logged without payload, remains unacknowledged, and does not permanently wedge
  already-stopped Android/provider resources.
- Consequences: `QUIESCED` means only that the existing session/Ski presentation writers cannot
  mutate the row again. It does not mean empty, retained, materialized, complete, queryable, or
  deletable. This slice deliberately adds no `NEEDS_SOURCE_EVIDENCE` state because no all-source
  evaluator or deletion consumer exists; the enum, comments, tests, and absence of cleanup enforce
  the fail-closed boundary. A future typed evaluator must inspect qualified facts for all six source
  families before reclamation. Recovery may now expose multiple physical Trip rows for one logical
  entry until the production history facade groups them. The Steps selector must next require the
  exact reverse binding for new-v28 rows; legacy-unverifiable rows remain typed and blocked.

## TI-D114 — Selected Steps history requires the exact service-run presentation reverse binding

- Status: `ACCEPTED_AND_IMPLEMENTED_CONTAINED` at `f2c7a3225`; logical-entry grouping and
  `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after focused reverse-binding implementation and independent
  read-only acceptance reviews, 2026-08-31
- Alternatives: trust only the segment's forward `serviceRunId`; infer ownership from logical ID or
  wall-time overlap; reject migrated rows as generic membership failures; require the authoritative
  Room reverse binding while preserving a distinct migrated-unverifiable result
- Evidence: TI-D113 made `source_service_run.session_segment_id` the unique Room authority and
  stamps migrated v27 rows `LEGACY_UNVERIFIABLE`. At `d6a2e31f0`, the Steps selector validated only
  forward logical/run membership, and its fixtures represented selectable new-v28 runs without the
  reverse binding.
- Decision: after logical/run membership validation, migrated `LEGACY_UNVERIFIABLE` ownership
  returns typed `SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE`. Every other v28 run must satisfy
  `serviceRun.sessionSegmentId == segment.id` before deletion-fence, manifest, lane, completeness,
  or fact reads. A mismatch returns `SERVICE_RUN_SEGMENT_BINDING_MISMATCH`. These map to the existing
  stable public `LEGACY_UNVERIFIED` and `HISTORY_MEMBERSHIP_UNAVAILABLE` causes. No schema, provider,
  writer, or rollout state changes.
- Consequences: exact-bound `PENDING` and `QUIESCED` rows retain the existing legacy/candidate read
  semantics; null or foreign reverse links cannot borrow another run's evidence. Migrated rows
  remain named and blocked rather than guessed. This does not make `QUIESCED` deletion authority,
  group replacement runs, provide ordinary discovery, or establish `QUERYABLE`.

## TI-D115 — Qualified Steps history combines exact intent with source-local product evidence

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_FOUNDATIONAL_READ_COMPOSITION` at `c1d6a4a62`; production
  logical-entry composition, ordinary discovery, and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after focused implementation, complete affected host verification,
  and three independent corrected-diff reviews, 2026-08-31
- Alternatives: infer captured sources from `SessionSegment.sampleCount`; use the current policy or
  manifest rather than historical revisions; accept control-only evidence as captured history;
  issue one Room query per row; stop after one rejected physical batch; combine exact historical
  intent with bounded source-local evidence and keyset through rejected candidates
- Evidence: `sampleCount` is a source-neutral processing-cycle count and can be positive without
  Location or Steps. Immutable manifests already persist revisioned capture and control membership,
  while the Steps product lane, deletion fences, source epochs, completeness state, and latest
  semantic revisions provide source-local evidence. More than one physical run may belong to one
  logical entry, and invalid newer rows must not starve a valid older result at a batch boundary.
- Decision: persisted manifest purpose values are interpreted through the stable
  `SESSION_CAPTURE`/`CONTROL` vocabulary. A checksum-valid manifest contributes an exact revisioned
  captured-source set; control membership stays separate, and unknown purpose or source values fail
  closed. Steps is qualified only when exact captured intent intersects current, non-deleted,
  epoch-valid covered or recorded Steps evidence for that run. Retractions, fences, stale epochs,
  terminal source-local failure, and unavailable prefixes remain typed. `sampleCount` compatibility
  is restricted to null/null migrated rows or durable `LEGACY_UNVERIFIABLE` runs and never qualifies
  any source. Candidate rows are traversed by `(startTimeMs, id)` keyset in bounded batches, with
  fixed-count batch reads for referenced run/manifest/source-policy/evidence state inside one Room
  transaction. Latest semantic correction ownership is resolved globally, then attributed to its
  exact physical run. No schema or generic history platform is added.
- Consequences: a zero-sample Steps run with qualified source-local evidence can now be selected,
  and a positive source-neutral sample count cannot fabricate Steps, Location, or capture intent.
  The new batch selector has no production list caller yet. It therefore proves a foundational
  discovery primitive, not ordinary product navigation or `QUERYABLE`. The next product read must
  group replacement-run physical segments under one logical entry before applying the consumer
  limit while retaining exact run/segment ownership internally; safe deletion and other source
  verticals remain separate later work.

## TI-D116 — Logical Steps history composes explicit physical members before consumer limits

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_INTERNAL_PRODUCT_READ_COMPOSITION` at `923bf2025`; public
  list/live wiring, ordinary navigation, and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after complete affected host verification and three independent
  final corrected-diff reviews, 2026-08-31
- Alternatives: limit physical rows and group afterward; infer membership from wall-time overlap;
  collapse or sum per-run Steps into one untyped number; group null/null legacy rows; sort only by
  the member that supplied discovery evidence; materialize every sibling in one unbounded Room
  result; compose explicit membership in bounded pages before applying the consumer limit
- Evidence: one logical tracking entry may own replacement-run physical segments. An older member
  may provide qualified Steps evidence while a newer exact replacement is baseline-only or still
  materializing, so product recency must follow the newest eligible physical member rather than the
  discovery seed. Migrated runs can retain matching explicit forward logical/run identity while
  their reverse presentation binding remains typed unverifiable.
- Decision: a coarse exact-source or positive legacy-compatibility member discovers an entry
  identity. For a nonblank logical id, composition then includes only segments whose service run
  has the same explicit logical/run identity and either the exact reverse segment binding or durable
  `LEGACY_UNVERIFIABLE` acknowledgement. Null/null legacy rows remain independent physical entries;
  partial or mismatched identity is excluded. Entry order uses the newest eligible
  `(startTimeMs, segmentId)` tuple, while physical members remain oldest-first. Candidate identities
  and sibling rows are keyset-paged in 64-row batches inside one Room transaction before the final
  `1..100` consumer limit. Every member keeps its own typed capture, fence, completeness, lane, and
  count state; only qualified-source membership is unioned. No cross-run numeric total is created.
- Consequences: replacement runs no longer consume separate internal product entries or starve an
  older logical entry at the result limit. Attributed migrated members share their known product
  identity but stay blocked and qualify no source; logical identity grants no deletion, export, or
  fact authority. The reader is internal and has no public list/UI caller, so this commit does not
  establish ordinary navigation, safe deletion, live/day state, or `QUERYABLE`.

## TI-D117 — Public Steps-only history exposes opaque nonnumeric rows, not global suppression aliases

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_PUBLIC_READ_CONTRACT` at `90f7e758e`; concrete list/live UI,
  ordinary navigation, and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after focused implementation, complete affected host verification,
  and three independent final corrected-diff reviews, 2026-08-31
- Alternatives: expose only selected-session Steps; expose a logical cross-run total; return raw
  logical or segment IDs to presentation code; infer Steps-only from qualification or `sampleCount`;
  publish suppression IDs from an independently limited logical list; expose exact revisioned
  authority plus a bounded opaque nonnumeric logical list and defer physical suppression until a
  concrete consumer owns both candidate windows
- Evidence: TI-D115/TI-D116 provide exact per-run capture/product evidence and bounded logical
  grouping, but neither had a public list caller. Every retained revision matters: a later mixed
  capture revision or one unverifiable replacement member must block the whole Steps-only claim.
  Separately, a logical top-N and a paged physical Trip top-N do not share a coverage boundary, so
  aliases from the former cannot safely suppress the latter.
- Decision: `SessionHistory` exposes exact immutable capture/control revisions or typed
  `Unverifiable`, plus qualified sources constrained to the union of exact captured sources.
  `capturesOnlySteps` requires every retained captured-source set to equal `{Steps}`; control remains
  separate. `observeRecentStepsOnlyEntries(limit)` applies the exact Steps-only predicate before the
  accepted-result limit and returns only an opaque equality key, the member time envelope, and a
  nonnumeric `AVAILABLE`/`MATERIALIZING`/`PARTIAL` state. It returns no cross-run count, physical
  segment identity, or suppression aliases. Existing Trip-row suppression must be composed in a
  bounded read against the concrete consumer's actual physical candidates.
- Consequences: production code now has a source-qualified, replacement-aware read seam suitable
  for a non-clickable Steps list row without fabricating zero or granting mutation/detail authority.
  The seam alone does not make source-only entries navigable and cannot safely filter independent
  Trip paging. The next UI slice must own that concrete composition, preserve the contained Trip
  Detail state model, preserve truthful selected-session missing and unavailable states, and add
  them to the live surface. No provider,
  writer, schema, deletion, export, activation, or rollout behavior changes.

## TI-D118 — Action-bearing history composition is finite and candidate-owned

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_BOUNDED_READ_CONTRACT` at `2ac4e399d`; production list
  wiring and `QUERYABLE` remain `BLOCKED`
- Owner/date: lead orchestrator after two independent final read-only reviews, 2026-08-31
- Alternatives: publish a global suppression-ID stream; independently limit physical and logical
  queries and merge them in UI; filter an unbounded paging stream row by row; expose replacement
  segment IDs for detail or deletion; compose against the concrete consumer's finite candidate set
- Decision: a consumer that owns a complete finite physical candidate window may request one
  Steps-aware page of at most 100 candidates and at most 100 final rows. One Room transaction loads
  the supplied candidates and their complete authoritative logical groups. Exact Steps-only intent
  suppresses the whole physical group even when its current evidence is baseline-only, fenced, or
  unavailable; a logical replacement appears only when the group is currently qualified. Physical
  results echo only caller-supplied existing IDs, while logical rows expose an opaque nonnumeric,
  non-selectable identity. The newest physical member determines recency and the final limit is
  applied only after the merge.
- Consequences: a finite Dashboard list can avoid duplicate or misleading Trip rows without
  acquiring cross-screen identity or deletion authority. The contract intentionally does not solve
  arbitrary paging, day aggregation, Statistics lists, export, selected deletion, or numeric
  cross-run totals. A caller that supplies an incomplete candidate window may receive a truthful
  underfilled page; it may not infer that omitted history is absent.

## TI-D119 — Live Dashboard authority is exact, segment-bound, and nonnumeric by default

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_LIVE_PRESENTATION_SLICE` at `4803d8af3`; recent-list
  discovery, `QUERYABLE`, numeric consumers, and device proof remain `BLOCKED`
- Owner/date: lead orchestrator after complete Dashboard host verification and corrected-diff
  review, 2026-08-31
- Alternatives: reuse `TrackerSessionSnapshot.steps`; select source layout from qualification or
  `sampleCount`; keep the Location UI until a Steps value appears; show missing values as zero;
  retain a non-ticking duration; globally remove existing milestones; bind the durable selected-run
  history to the exact active physical segment and fail closed while authority is absent
- Decision: the live Dashboard observes durable selected-session history only while the tracker
  publishes a positive active segment. Exact every-revision `{Steps}` captured intent selects the
  Steps-only surface; control sources do not make it mixed. `NotFound`, observer failure, or segment
  mismatch is unavailable, and replacement/stop cancels the old stream. The UI exposes complete
  positive, covered zero, partial lower bound, materializing, missing, and unavailable states;
  only complete covered zero is numeric zero. Steps-only UI contains no Location-derived or action
  affordance and keeps Stop available without Location permission. Runtime milestones retain their
  previous behavior only for an exact current-segment `Standard` presentation and are disabled for
  every other authority state.
- Consequences: an active Steps-only session can be represented without activating Location or
  trusting the runtime accumulator. Host tests establish the state/UI contract, not sensor,
  listener, FGS, process, reboot, battery, visual-device, or accessibility behavior. The live slice
  neither makes stopped Steps-only history ordinarily discoverable nor grants logical detail,
  export, or deletion authority; the finite list consumer remains the next gate.

## TI-D120 — Dashboard history authority is one bounded composed generation

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_BOUNDED_DASHBOARD_PRODUCT_SLICE` at `123c6399e` and
  `ab760d234`; exact manual provider/device proof and broader consumers remain `BLOCKED`
- Owner/date: lead orchestrator after focused host/static verification, one storage review, and one
  corrected product/lifecycle review, 2026-08-31
- Alternatives: keep raw recent trips and independently overlay Steps; filter or look up each row
  in presentation; expose logical/physical suppression aliases; fall back to raw trips when Stats
  fails; allow a cached physical row to regain actions during lifecycle restart; compose one finite
  physical generation through the existing bounded Stats contract and fail closed
- Evidence: the Dashboard's action-bearing list needs stable ordering and one common coverage
  boundary. Independently limited reads can duplicate a Steps-only replacement group, and an
  indefinitely retained `StateFlow` page can briefly restore a stale physical top row after the
  screen restarts. `TrackingHistoryEntryKey.toString()` is deliberately non-identifying, so Compose
  identity must use the typed opaque key itself.
- Decision: both recent physical queries order by `(startTimeMs DESC, id DESC)`. While tracking is
  stopped, the Dashboard snapshots at most 20 physical candidates and uses `flatMapLatest` to pass
  that immutable ID generation to one `observeRecentStepsAwarePage(..., limit = 5)` observation.
  Only Stats ordering is presented; physical results must resolve from that same snapshot and any
  failure becomes typed `Unavailable`, never raw fallback or backfill. A top composed physical row
  alone may authorize Last Session/map/detail, with controller data accepted only for the same ID.
  An opaque Steps-only row grants no physical action, Steps total, or distance. Lifecycle replay expires when the
  five-second stop timeout elapses, resetting first presentation to `Loading` before refreshed
  content.
- Consequences: qualified stopped Steps-only entries are ordinarily discoverable and
  `QUERYABLE` in the bounded Dashboard list without `sampleCount`, fabricated zero, or physical
  identity leakage. Physical rows preserve existing behavior. An intentionally underfilled page is
  truthful and does not authorize an unbounded search. This does not solve Statistics paging,
  day/Calendar composition, cross-run totals, deletion, export/import, manual provider proof,
  automatic/ambient Steps, or any other source.

## TI-D121 — Steps RECORDING is a qualified positive WAL boundary, not a persisted session state

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_SYNTHETIC_HOST_BOUNDARY_PROOF` at `dff1b85fd`; exact
  Steps-only admission/provider/device-chain proof remains `BLOCKED`
- Owner/date: lead orchestrator after focused ingress/projection verification and adversarial
  corrected-diff review, 2026-08-31
- Alternatives: add a durable `RECORDING` lifecycle state; treat the first baseline or unchanged
  covered window as recording; wait for projection before recognizing capture; force a projection
  drain or delay into production; define the evidence boundary at the first positive, contiguous,
  capture-attributed post-baseline WAL window and keep materialization independently observable
- Evidence: the Steps provider must first establish a noncontributing baseline. A later covered-zero
  window proves coverage but no positive step effect. The durable WAL can commit before the
  asynchronous candidate writer receipt/cursor, and production decoding can return a valid prefix
  before a corrupt row. The legacy bridge addresses its destination as `source-event:<eventId>`.
- Decision: the manual gate may report `RECORDING` only from an integrity-valid, production-admitted
  Steps v3 `COVERED` window with positive delta, exact session/run capture attribution, current
  generation/authorization/lifecycle stamps, and a compatible preceding baseline chain. Baseline,
  covered zero, reset, legacy-ambiguous, corrupt, scan-exhausted, or attribution-invalid evidence is
  non-recording or typed unverifiable. `MATERIALIZED` remains the separate exact candidate writer
  receipt plus committed lane cursor. No production lifecycle row, forced drain, or timing delay is
  added.
- Consequences: `dff1b85fd` proves the WAL-before-receipt ordering and canonical materialization only
  from synthetic capture-attributed host rows decoded by production code. It intentionally does not
  prove broker admission, observed-time freshness, provider callbacks, normal recovery scheduling,
  UI, or listener removal. The disposable device harness must supply those facts and use normal
  recovery; real `SensorService` evidence remains required for listener removal.

## TI-D122 — The manual Steps device gate is a disposable production-observing harness

- Status: `ACCEPTED_AND_IMPLEMENTED_AS_COMPILED_DISPOSABLE_HARNESS` at `1bb9749af`; connected
  execution and the exact manual device gate remain `BLOCKED`
- Owner/date: lead orchestrator after instrumentation compile/static verification and two
  adversarial staged-diff passes, 2026-08-31
- Alternatives: add an ordinary production caller that activates the candidate writer; inject
  provider/WAL/fact rows; force a projection drain; clear broker owners directly; accept a partial
  teardown audit; infer rendered UI or physical listener state from repository rows; use a cleared,
  disposable debug install to observe the existing production start/provider/admission/writer/query/
  stop chain and require external platform/UI evidence for boundaries the process cannot prove
- Evidence: candidate activation is intentionally unreleased and the default settings can create an
  automatic Activity control owner. The Steps runtime also permits a synchronous callback while
  `SensorManager.registerListener` is executing, before durable provider acceptance, so acceptance
  time is not a valid lower bound for the first baseline. Room can prove app-owned retirement but
  cannot prove Android removed the physical listener, and repository DTOs cannot prove Compose
  rendering or accessibility.
- Decision: the operator-run instrumentation class first writes exact Steps-only settings, invokes
  production automatic-control reconciliation, and requires a disposable empty data plane. It may
  install the inert Steps shadow lane and invoke the existing source-specific candidate transition
  only inside that test. Thereafter it uses production manual start/stop, provider callback
  admission, normal asynchronous writer wakeup, and `TrackingHistoryRepository`; it inserts no
  tracking rows and calls no drain/recovery shortcut. Durable before/after audits require exactly
  one new Steps session demand and registration, no other source/control/ambient effect, one fresh
  baseline followed by a contiguous positive covered window with no later zero-delta window, exact
  run/manifest/purpose/consent/epoch/lease attribution, one canonical writer, queryable truthful
  Steps state, and complete retirement. Cleanup is armed before enqueue and is non-cancellable until
  terminal/no-live-state proof. Settings and rollout mutations persist, so app data is cleared after
  every outcome.
- Consequences: compile, Detekt, lint, and review can validate the harness contract without claiming
  its Android assertions ran. One identified physical step-counter device must still supply the real
  callback and connected provider-to-query evidence. Before/during/after `dumpsys sensorservice` is
  mandatory for physical listener removal, and separate rendered UI/accessibility inspection is
  mandatory for UI truth. The test-only transition is not ordinary activation, external rollout,
  or, by itself, permission to begin later Steps-local mutation work before this gate settles.

## TI-D123 — Parallel worktrees may advance independent seams without weakening source gates

- Status: `ACCEPTED_AND_IN_PROGRESS`; foundation converged locally at `4b25d39e2`, first parallel
  wave branches are isolated and not activated
- Owner/date: user-directed program change, implemented by the integration owner, 2026-09-01
- Alternatives: retain strict Steps-device-first development serialization; let every source branch
  edit shared schema/history and resolve conflicts later; build all six source stacks in one branch;
  develop only disjoint source-local or Steps-local seams in clean worktrees and converge them
  through one gated local `dev/v10`
- Evidence: the manual Steps device class compiles but cannot run without attached hardware. That
  missing evidence blocks provider/listener/UI/activation claims, but it does not technically
  prevent schema-free exact Steps deletion, Pressure request normalization, or Cell atomic admission.
  Those slices have disjoint declared files and independently useful correctness outcomes. The
  33-commit foundation passed exact `ciUnitTest` and `ciCheck` before local fast-forward.
- Decision: independent host-testable seams may develop concurrently in separate clean branches and
  worktrees. Shared schema, migration, history, portable export/import, cross-source deletion, and
  documentation retain a single owner. Each branch stages exact paths, passes proportional checks,
  rebases onto the latest local `dev/v10`, reruns its affected gate, and merges locally one at a time.
  Parallel development neither activates a candidate writer/provider nor changes rollout state.
- Consequences: TI-D122's development-order restriction is superseded only for disjoint contained
  implementation. Its exact manual Steps provider/listener/rendered-UI gate remains mandatory before
  ordinary Steps activation or any device-quality claim. Location remains legacy-canonical until an
  evidence-backed shadow/cutover decision. No worktree may infer proof from another source, create a
  second writer, or claim publication; all commits remain local unless separately authorized.

## TI-D124 — Pressure acquisition tiers are normalized to real sensor capabilities

- Status: `ACCEPTED_AND_IMPLEMENTED` at `afcb009e1`; device and battery realization remain
  `UNVERIFIED`
- Owner/date: Pressure source owner with fresh integration review, 2026-09-01
- Alternatives: pass policy values directly to `SensorManager`; name a batching tier even when the
  sensor exposes no FIFO; clamp requests while continuing to report `APPLIED`; normalize once and
  reuse that exact immutable physical tuple throughout the runtime
- Evidence: Android exposes minimum/maximum delay and FIFO capability per sensor. The corrected
  runtime's focused 53-test suite covers delay bounds, malformed capability bounds, FIFO/no-FIFO
  latency, compatible refresh, replacement, capacity resume, window accumulation, and acquisition
  floors; fresh review found no remaining blocker, high, or medium issue.
- Decision: Pressure normalizes requested sample period and report latency against the selected
  sensor before registration. The normalized tuple is the provider fingerprint and the exact tuple
  used for every registration/resume path. A requested quality floor the provider cannot meet is
  truthfully `DEGRADED`; absent FIFO forces zero report latency and cannot be described as batching.
- Consequences: equivalent effective requests do not churn the listener, distinct physical tuples
  use the existing fenced replacement, and policy names cannot manufacture a battery tier. Host
  tests do not establish realized cadence, FIFO delivery, wake behavior, or energy savings; those
  claims remain device-gated. This decision does not activate Pressure or authorize another writer.

## TI-D125 — Cell durable identity and WAL ordering belong to atomic ingress

- Status: `ACCEPTED_AND_IMPLEMENTED` at `862e839eb`; device radio behavior remains `UNVERIFIED`
- Owner/date: Cell source owner with fresh independent review, 2026-09-01
- Alternatives: allocate a source sequence before Room admission; persist every callback or refresh
  outcome; include raw radio/subscription identity; deduplicate only inside one runtime generation;
  admit one minimized delivery atomically and let Room allocate its sequence
- Evidence: the corrected runtime and real in-memory Room suites pass `38/38`; an independent forced
  rerun passed the exact older-duplicate stop-barrier case and cross-generation Room replay. Detekt
  and affected lint pass with no new issue.
- Decision: only a fresh, nonempty, timestamp-qualified callback can form a Cell product delivery.
  Its stable identity uses the boot clock domain and minimized provider-time product facts; raw cell
  and subscription identifiers remain absent. Room atomically resolves replay and allocates a
  sequence only for a new delivery. Callback resolution and the physical-run WAL ordinal high-water
  advance independently so replay of an older row cannot weaken the stop completeness boundary.
- Consequences: stale, cached, operational, empty, generation-invalid, prerequisite-invalid, and
  post-cutoff observations remain non-durable; retries are finite and recheck authority. This is
  JVM/Robolectric/in-memory-Room evidence, not real Telephony callback, process-death, radio timing,
  energy, or OEM proof. Cell still needs its typed fact lane, production query/UI, deletion,
  retention, and portable export/import, and this decision activates no provider or writer.

## TI-D126 — Pressure checkpoint repair requires the exact retained delivery authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `3a3bcbadb`; physical sensor and product behavior remain
  `UNVERIFIED`
- Owner/date: Pressure source owner after fresh NO_GO correction and independent re-review,
  2026-09-01
- Alternatives: allocate a source sequence before ingress; identify a window with process-local
  callback ordinals; let any duplicate repair a Pressure runtime checkpoint; trust the caller's
  wrapper interval; couple checkpoint repair to the exact retained WAL authority
- Evidence: the first review rejected provider-envelope-free duplicate repair, pre-retention
  mutation, callback-local delivery identity, and wrapper intervals that could hide intrinsic
  endpoints. The corrected DAO projection, atomic ingress, runtime, and focused Room tests passed
  `153/153` after rebase; the reviewer independently reran the exact DAO and Pressure ingress cases
  (`34/34`) and returned `GO`.
- Decision: Room allocates the Pressure source sequence and WAL row atomically. A duplicate may
  advance `SensorAdmissionCheckpoint` only when the retained row, incoming evidence, and current
  checkpoint agree on source instance, registration generation, physical configuration, and one
  non-denied observed-time authorization revision covering both true Pressure-window endpoints.
  The retention floor is checked before replay mutation. Pressure identity excludes callback-local
  sequences, while payload checksum comparison still turns sequence-only changes into an identity
  collision. Pressure and Steps wrappers cannot narrow their intrinsic sensor interval.
- Consequences: exact same-generation retry is idempotent; another generation cannot adopt an old
  window into its checkpoint; pre-floor, boundary-crossing, narrowed, colliding, and failed
  transactions leave checkpoint, sequence, and WAL state unchanged. Ordinary non-checkpointed
  duplicate delivery remains envelope-agnostic, and radio spans are unaffected. This adds no
  candidate fact lane, product query/UI, activation, second writer, ambient Pressure, or rollout.
  Device FIFO/flush/cadence, process death, battery, deletion, retention, and portable export/import
  remain separate Pressure gates.

## TI-D127 — Location provider acceptance establishes an immutable callback observation floor

- Status: `ACCEPTED_AND_IMPLEMENTED` at `a4caa9f12`; physical provider behavior and canonical
  cutover remain `UNVERIFIED`
- Owner/date: protected Location source owner after correction and fresh independent review,
  2026-09-01
- Alternatives: accept every timestamp-fresh provider fix after callback registration; use receipt
  time as the floor; replace the floor during compatible authorization refresh; let a duplicate's
  older WAL ordinal replace a newer stop boundary; capture one exact acceptance boundary and only
  raise it while the same physical provider registration remains active
- Evidence: the focused Location gate passed `58/58` for runtime, delivery, normalization,
  prerequisites, backend, and framework-provider selection. Fresh review independently reran the
  same six suites (`BUILD SUCCESSFUL in 1m 22s`, 234 tasks) and found no scoped high issue. Detekt
  and affected lint also pass with no new issue.
- Decision: a newly reserved Location generation captures one elapsed boundary before provider
  start, rejects synchronous callbacks until durable provider acceptance, and installs the exact
  accepted callback context with the stricter provider/authorization floor. A compatible
  authorization refresh keeps the physical registration and raises the floor to the maximum of its
  prior value, the refresh boundary, and durable authorization authority. Every retry repartitions
  immutable raw fixes against that floor plus the current cutoff, future-time, and freshness rules.
  Durable and duplicate admissions update a physical-run ordinal high-water; only a new physical
  registration resets it.
- Consequences: cached pre-start fixes cannot become qualified Location evidence or create a false
  gap, and replay of an older retained row cannot weaken stop settlement. No second canonical
  writer, shadow comparison, destination-owner transition, provider activation, or rollout is
  introduced. These host/Robolectric tests do not prove real fused/framework callbacks, passive or
  active cadence, process death, reboot, FGS legality, battery, OEM behavior, product query/UI, or a
  Location cutover decision.

## TI-D128 — Activity publication follows the durable selected subset

- Status: `ACCEPTED_AND_IMPLEMENTED` at `3c7b28545`; physical Activity Recognition behavior remains
  `UNVERIFIED`
- Owner/date: Activity source owner after one focused review correction and fresh independent
  corrected-diff review, 2026-09-01
- Alternatives: coerce invalid provider time to elapsed zero; reject an entire callback when one
  sibling is stale; let raw recognition presence suppress transition-derived Activity; derive every
  publication from the sparse original-index set that atomic Room admission actually made durable
- Evidence: the corrected focused gate passed `112/112` across the receiver, retry worker, delivery
  factory, Activity Room adapter, and generic durable ingress. Detekt and Activity lint passed with
  no new issue. Fresh read-only review found no remaining scoped blocker, high, or medium finding.
- Decision: negative and overflowed recognition timestamps and negative transition timestamps are
  omitted. Provider-window qualification is accepted-inclusive, retired-exclusive, and
  future-exclusive while retaining every valid sibling's original index. The receiver publishes
  only indexes returned by durable admission. A selected recognition owns Activity state; a selected
  transition supplies the contained Activity fallback only when the durable recognition-index set
  is empty, while the transition stream itself remains independently published.
- Consequences: an invalid, stale, or future recognition cannot suppress a valid selected transition,
  and a valid selected recognition cannot be overwritten by its transition sibling. Callback retry
  still settles the complete atomic delivery and low-confidence capture remains preserved without
  an invented threshold. This is host/Robolectric evidence, not real PendingIntent delivery,
  process/reboot, automation-start, battery/OEM, product materialization/query/UI, deletion/export,
  activation, or rollout proof.

## TI-D129 — Activity automatic triggers retain exact historical provider authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `0a6a8f545`; physical automatic-start behavior remains
  `UNVERIFIED`
- Owner/date: Activity trigger-envelope owner after focused host gates and fresh independent review,
  2026-09-01
- Alternatives: trust the generation and authorization stamps already encoded in an automation
  effect; require the provider generation to remain currently active; reload the exact historical
  Activity generation and require the observation to fall inside its accepted-inclusive,
  retired-exclusive physical lifetime at every pre-start boundary
- Evidence: the final six-suite selection passed `108/108` with zero failures, errors, or skips
  (`BUILD SUCCESSFUL in 5m 31s`, 230 tasks). Root Detekt passed in `35s`, and
  `:tracker:engine:lintDebug` passed in `7m 31s` with 377 tasks and no new issue. Fresh read-only
  review found no scoped blocker, high, or medium finding across the exact nine-path snapshot.
- Decision: automation projection emits an effect only for Activity evidence with
  `CONTROL_AUTOSTART`, a positive physical generation, nonblank physical configuration and
  authorization fingerprints, and positive authorization and automation revisions. Outbox
  validation and the action repository reload the exact Activity generation and require matching
  boot/data epoch, durable provider acceptance, a nonfailed eligible status, and
  `accepted <= observed < retired` when a retirement cutoff exists. Reserve, Android-request
  authorization, delayed service validation, and no-intent cold recovery all reuse that authority.
  A lifecycle intent already committed under those checks remains an acknowledged durable effect;
  it is not retroactively reclassified because the historical provider later retires.
- Consequences: missing, failed, unaccepted, wrong-epoch, pre-acceptance, and at/after-cutoff
  provider evidence terminalizes only its automation action. Retired evidence observed before the
  cutoff remains valid, so the implementation does not invent a current-generation requirement.
  Malformed control members are omitted without poisoning a valid sibling. This introduces no
  Activity capture fact, captured-history path, writer activation, provider start, rollout, or
  device claim; PendingIntent delivery, process death/reboot, FGS legality, battery/OEM behavior,
  product materialization/query/UI, deletion, retention, and portable export/import remain open.

## TI-D130 — Steps selected deletion requires exact authority and complete bounded day repair

- Status: `ACCEPTED_AND_IMPLEMENTED` at `b5698e635`; rendered/device deletion UI remains
  `UNVERIFIED`; retention and portability remain `BLOCKED`
- Owner/date: manual/session Steps deletion owner after terminal-dependency correction and fresh
  independent review, 2026-09-01
- Alternatives: delete only the presentation row; accept `QUIESCED` as proof that no writer can
  mutate; infer ownership from wall-time overlap or `sampleCount`; materialize every terminal
  projection failure into an unbounded list; require exact immutable run/manifest/fact authority and
  fail closed when bounded repair dependencies are incomplete
- Evidence: the focused DAO/deletion/history suite passed `117/117`; the post-rebase selected suite
  passed `161/161` plus Android-test compilation, app Hilt compilation, and Room schema drift. Root
  Detekt and all three affected lints pass. Fresh review rejected the original unbounded
  terminal-failure query, then returned `GO` after deterministic cap-plus-one SQL and adversarial
  no-false-READY/no-mutation regressions; no final blocker, high, or medium finding remains.
- Decision: only one exactly reverse-bound, candidate-owned v28 Steps-only presentation scope may be
  selected for this deletion path. Lifecycle, manifest integrity, capture purpose, writer binding,
  policy/consent/data epoch, fact attribution, retained source evidence, and every affected calendar
  zone are preflight authority. The transaction installs a monotonic source-local fence and redacted
  retractions before removing the exact presentation and repairing affected summaries from surviving
  qualified facts. Repair uses the stored summary/manifest zone authority and never the device's
  current zone. Terminal-failure dependencies are ordered by admission ordinal and writer identity,
  read with a 2,048-row cap plus one, and overflow makes affected history unavailable and deletion
  repair unsupported before mutation.
- Consequences: active, legacy, mixed-source, mismatched, stale, materializing, retention-incomplete,
  overflowed, or otherwise unverifiable scope remains intact under a typed outcome. Exact candidate
  replay cannot cross the installed fence, while idempotent absence remains `NotFound`; portable and
  database-import no-resurrection remain separate unimplemented gates.
  `QUIESCED`, wall overlap, and `sampleCount` prove neither ownership nor source qualification. This
  does not activate the candidate writer, generalize deletion to another source, complete portable
  export/import or retention, or prove a rendered/device deletion flow.

## TI-D131 — Automatic-capable Steps attribution is a new immutable binding generation

- Status: `ACCEPTED_AND_IMPLEMENTED` at `b7d4900cf`; automatic product execution remains `BLOCKED`
- Owner/date: contained Steps binding owner after exact-diff review, 2026-09-02
- Alternatives: widen generation 1 in place; infer automatic capability from the current rollout;
  accept any executable Steps lane during re-arm/deletion; add generation 2 with an exact immutable
  capture-mode mask while retaining generation 1 for historical authority
- Evidence: the reviewed 14-path snapshot passed `188/188` focused tests, app KSP/compile, root
  Detekt, and affected core/tracker lints. A forced post-rebase rerun executed all 230 tasks and
  passed the same `188/188`; fresh review found no blocker, high, or medium finding.
- Decision: Steps session-fact binding generation 1 remains exactly manual-session-only. Generation
  2 is exactly manual plus automatic session capture and is preferred only when no durable installed
  Steps lane supplies its own exact generation. Manifest writer stamps, rollout capture masks,
  projector authority, selected deletion, rollback, and re-arm must all resolve the same installed
  generation/mask. Activity in an automatic Steps manifest remains `CONTROL`, never captured.
- Consequences: retained V1 facts are not retroactively broadened, and rollback/re-arm cannot adopt
  an ambiguous or unknown Steps binding. Executability is not activation: no policy, demand,
  provider, destination owner, rollout state, or writer was enabled. Trigger legality, control
  retention/no-export, provider-to-query execution, process/reboot/device evidence, and full
  automatic Steps remain separate gates.

## TI-D132 — Wi-Fi delayed admission reauthenticates exact historical and current authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `8000f4b16`; Wi-Fi product materialization and physical
  radio behavior remain `BLOCKED`
- Owner/date: Wi-Fi durable-admission owner after corrected-diff review and a forced post-rebase
  compatibility gate, 2026-09-02
- Alternatives: trust a callback's process-local generation; accept the historical registration
  without rechecking current policy; infer retirement from a session state or wall-time overlap;
  admit only when one exact historical registration, complete immutable manifests, current policy,
  session/run/lease/cutoff authority, and the source-local suspension intent still agree
- Evidence: the forced post-rebase selection passed `311/311` tests with zero failures, errors, or
  skips across Wi-Fi runtime/admission, generic Room ingress/sink, SourceBroker, and Activity, Cell,
  Location, and Pressure compatibility. All 248 test-command tasks executed. Root Detekt plus
  `:core:base:lintDebug` and `:tracker:engine:lintDebug` then passed with 381 tasks executed and no
  new issue. The final exact hardening review found no blocker, high, or medium finding.
- Decision: a fresh, nonempty, privacy-minimized Wi-Fi result receives a stable delivery identity
  independent of process-local runtime generations, and Room alone allocates its source sequence.
  Delayed historical admission requires the claimed physical registration, authorization,
  fingerprint, demand set, session/run/lease, and accepted-inclusive/retired-exclusive cutoff to
  match, plus checksum-valid historical and current manifests and unchanged current policy. A
  suspended provider may recover only through the exact current lifecycle intent checksum and its
  deterministically derived source action. Empty, stale, generation-invalid, purpose-incompatible,
  or identity-colliding results do not mutate durable evidence.
- Consequences: exact replay remains zero-effect, callbacks cannot borrow another source or expired
  purpose authority, and optional Location context neither starts nor retains Location. Passive
  Wi-Fi callbacks remain compatible with direct demand, while active attempts stay finite and
  direct-demand-only. This adds no Wi-Fi product fact, query/UI, deletion, retention, portable
  export/import, ordinary writer activation, rollout, or physical scan/callback/battery/OEM proof.

## TI-D133 — Numeric Steps decisions require complete source-qualified day coverage

- Status: `ACCEPTED_AND_IMPLEMENTED` at `18e4f7acb`; broad numeric consumers and device execution
  remain `BLOCKED`
- Owner/date: Steps history/storage owner after corrected aggregate, forced post-rebase focused
  gates, repository quality gates, and local fast-forward, 2026-09-02
- Alternatives: reuse `daily_summary.steps` or `sampleCount`; treat absent facts as zero; compose
  each row independently; expose partial numbers with a warning; retain one permanent Room observer;
  perform one coherent bounded source-qualified read and return a typed nonnumeric state whenever
  completeness cannot be proven
- Evidence: the forced post-rebase product/storage selection passed `103/103`, the DAO selection
  passed `44/44`, stats API JVM tests passed, root Detekt passed, and app compile, affected lint, and
  Room drift passed. The authoritative `ciUnitTest` aggregate then passed in `18m 38s` with 994
  tasks. The earlier aggregate's five branch-local deletion/repair failures and three incomplete
  Activity fixtures were corrected before this accepted run.
- Decision: a numeric Steps result is `Ready` only when exact immutable session/run/manifest,
  `SESSION_CAPTURE`, consent, writer, data-epoch, deletion-fence, calendar-zone, and complete covered
  fact authority tile every requested captured slice. Replacement physical runs compose under their
  logical tracking identity while retaining exact run ownership internally. Active or settling
  exact work is `Materializing`; absence, partial capture, missing authority, ambiguity, retention
  loss, contradiction, and storage failure remain typed nonnumeric results. `sampleCount`,
  presentation steps, wall-time overlap, and `QUIESCED` never establish source ownership or a zero.
- Consequences: the read is one coherent bounded Room generation, starts no provider, writes no
  repair, and installs no permanent observer. Goals, streaks, achievements, widgets, and
  notifications must use only `Ready`, but are not wired yet. Before a broad 370-day consumer is
  enabled, settled facts must be streamed through a precomputed day-window accumulator to avoid
  retaining and rescanning the whole fact set; a new service-run-first index requires measured need
  and the later schema/retention owner. Connected provider-to-query, listener removal, rendered UI,
  process/reboot/FGS, battery/OEM, retention, portable transfer, activation, and rollout remain
  separate gates.

## TI-D134 — Pressure windows carry qualified source evidence without inventing altitude

- Status: `ACCEPTED_AND_IMPLEMENTED` at `d63075f2d`; Pressure product materialization remains
  `BLOCKED`
- Owner/date: Pressure source-runtime owner after corrected durable-format review, forced focused
  gates, and local fast-forward, 2026-09-02
- Alternatives: retain the legacy mean/min/max window; derive altitude from a fixed standard
  atmosphere; rename identical request tiers; compact qualified windows and recompute an apparent
  fit; freeze exact source-owned endpoints, fit, accuracy, realized coverage, normalized request,
  and closure evidence while leaving product interpretation to a later Pressure fact lane
- Evidence: the corrected pre-commit and post-rebase selections each passed `95/95`; root Detekt and
  tracker-engine lint passed with no new issue. Fresh review found and then verified corrections for
  sequence/statistical consistency and a missing frozen wire vector; no blocker, high, or medium
  issue remains.
- Decision: payload v4 appends exact qualified Pressure evidence after the frozen v1-v3 shape.
  Stable enum codes, strict full-byte decoding, sequence cardinality, one-sample invariants, and an
  independent golden vector protect the durable WAL contract. `TARGET_ELAPSED` is complete only
  with expected count, sufficient observed span, and no gap reaching two requested cadences;
  `SOURCE_BOUNDARY` remains durable partial evidence. Per-event accuracy is conservatively reduced
  to the worst window value. Qualified windows cannot use the legacy compactor because merged
  regression quality cannot be reconstructed truthfully.
- Consequences: acquisition tiers now leave measurable request and realized-window evidence without
  claiming realized device cadence, wake reliability, altitude, or product stability. Delivery
  identity remains replay-stable while changed qualified content reaches checksum-collision
  protection. No schema, materializer, query/UI, deletion, retention, portable transfer, ambient
  Pressure, destination-owner switch, activation, rollout, push, or release is authorized.

## TI-D135 — Portable Steps freezes exact source truth before product file plumbing

- Status: `ACCEPTED_AND_IMPLEMENTED` at `fd558265a`; Room export, authoritative import, retention,
  and end-to-end no-resurrection remain `BLOCKED`
- Owner/date: Steps portability contract owner after focused host/static gates, export-design audit,
  clarification, and local fast-forward, 2026-09-02
- Alternatives: export legacy presentation rows or the complete database; serialize clear local
  identities; derive imported identities or deletion scopes again; let storage failures surface
  after file emission begins; freeze a source-local privacy-minimized schema and typed transfer
  boundary before adding Room or UI plumbing
- Evidence: the exact API/codec selection passed `24/24` initially, before commit, and after rebase.
  Release compilation, root Detekt, and import/export lint passed before and after rebase; root
  Detekt passed again after the final documentation-only contract clarification. The export audit
  separated current codec/schema guarantees from later storage and lifecycle obligations.
- Decision: portable Steps v1 contains only opaque kind-namespaced identities, the original exact
  v28 run-deletion-scope digest, immutable Steps session-capture attribution, explicit settlement,
  and latest effective typed facts under canonical semantic checksums. Replacement physical runs
  stay grouped by logical entry. A producer must validate and bound its complete point-in-time
  snapshot before the first sink emission; an importer must atomically recompute integrity and
  durably preserve portable identities and deletion scopes verbatim. Non-covered evidence never
  carries a fabricated zero.
- Consequences: the codec can reject malformed, oversized, noncanonical, identity-colliding, or
  checksum-invalid files without broad database or provider authority. The 2,048 fact cap applies
  to latest portable states; a later exporter must separately bound historical correction
  revisions and complete snapshot memory. Portable qualification is not selected-deletion
  eligibility, particularly for a Steps slice of a mixed-source run. No Room exporter/importer,
  durable origin mapping, registry/UI, retention execution, database-import bridge,
  no-resurrection proof, activation, rollout, push, or release is authorized.

## TI-D136 — Broad Steps numeric reads stream facts into a bounded day accumulator

- Status: `ACCEPTED_AND_IMPLEMENTED` at `b5f43a2e8`; numeric consumer migration remains `BLOCKED`
- Owner/date: Steps numeric storage owner after fail-closed review, repeated focused gates, static
  gate, clean rebase, forced post-rebase reruns, and local fast-forward, 2026-09-02
- Alternatives: retain all latest facts and rescan them for every requested day; add a speculative
  aggregate schema or service-run-first index; weaken correction or materializing validation; page
  settled latest state once and accumulate into the already bounded structural-day window
- Evidence: the exact four-class selection passed `80/80` repeatedly, including 95,090 facts over
  370 days and cross-page/correction/materializing edge cases. Detekt, tracker lint, and Room drift
  passed. The final forced post-rebase selection passed `80/80`; Gradle's anomalous printed duration
  is recorded in the implementation and verification evidence rather than used as a benchmark.
- Decision: settled latest Steps fact state is read in total-key keyset pages and consumed once by an
  at-most-370-cell accumulator. Only one physical run's coverage cursors are retained at a time.
  Immutable authority, exact completeness bounds, deletion and retention fences, stored calendar
  authority, and replacement membership are unchanged. A lane-behind terminal run may lack rows,
  but every present row is validated and no row beyond its completeness target is accepted.
  Per-fact manifest successor and capture-slice lookup is constant time.
- Consequences: the demonstrated broad-read memory and repeated-scan blocker is removed without a
  new schema, index, observer, provider demand, or generic materializer. This does not authorize a
  consumer to treat `Materializing` or `Unverifiable` as zero. Goals, streaks, achievements,
  widgets, and notifications must be migrated and tested separately; connected provider, UI,
  retention/portable, automatic/ambient, activation, rollout, push, and release gates remain.

## TI-D137 — Numeric product surfaces consume only source-qualified Steps

- Status: `ACCEPTED_AND_IMPLEMENTED` at `3e727f255`; award and achievement mutation remain
  `BLOCKED`
- Owner/date: Steps product-consumer owner after focused tests, corrected adversarial review,
  affected static gates, required latest-base check, and local fast-forward, 2026-09-02
- Alternatives: continue using `daily_summary.steps`; overlay raw live GoalTracker values; map
  missing or partial evidence to zero; require a legacy summary before showing Steps; keep an
  unbounded worker wait; expose the existing typed repository through bounded subscription-scoped
  reads and render only independently qualified metrics
- Evidence: initial consumers passed `99/99` focused cases. Review found and the correction removed
  raw-summary presence authority and unbounded widget/worker waits; corrected cases passed `54/54`,
  root Detekt and four affected lints passed, and a fresh re-review found no blocker, high, or medium
  issue.
- Decision: only `QualifiedStepCount.Ready` may provide a Steps number or ratio. Verified zero is a
  present value; every unavailable reason is nonnumeric. Daily and weekly qualification remain
  independent. Existing raw flows and Room summaries are invalidation/non-Step inputs only. Product
  composition strips raw Steps, does not require a legacy summary, omits absent sibling metrics,
  retries materialization finitely, and bounds replay-sentinel waits while propagating caller
  cancellation.
- Consequences: Dashboard, legacy Tracker, Game, Today widget, and goal notifications no longer
  fabricate or hide Steps through raw aggregate presence. This does not qualify GoalTracker award
  writes, streaks, achievements, lifetime/best-day metrics, or automatic/ambient Steps. No provider,
  observer network, schema, writer activation, rollout, push, or release is added.

## TI-D138 — Pressure session facts require exact immutable authority and transactional projection

- Status: `ACCEPTED_AND_IMPLEMENTED` at `db2a460a3`; product query and writer activation remain
  `BLOCKED`
- Owner/date: Pressure source-fact owner after focused tests, corrective review, full affected
  static gates, required rebase, and local fast-forward, 2026-09-02
- Alternatives: keep compatibility `pressure_sample` as product authority; infer session ownership
  from wall-time overlap; backfill legacy samples without provenance; share the Steps projector;
  skip sparse ordinals; advance a cursor independently; activate the new writer immediately; add a
  Pressure-specific append-only fact lane behind exact existing authority while leaving it dormant
- Evidence: the rebased nine-suite selection passed `182/182`; Android-test compilation and the
  595-task Detekt/app/lint/schema gate passed; cancellation, trigger rollback, sparse ordinal,
  terminal poison, writer/binding conflict, deletion epoch, and exact legacy-owner cases are covered;
  a fresh reviewer found no blocker, high, or medium issue.
- Decision: `pressure_fact_revision` is append-only and source-local. Every fact retains its exact
  service run, physical segment, manifest/policy/consent attribution, source signal, writer and
  deletion generations, qualified window payload, and correction lineage. Projection operates over
  one frozen finite WAL/deletion snapshot, accepts legitimate sparse global ordinals, and moves its
  cursor only atomically with fact/failure/evidence mutation. Event-local semantic poison is
  terminal for that ordinal; structural authority conflicts and cancellation roll back. No legacy
  `pressure_sample` row is promoted to a qualified fact, and the lane remains dormant until its own
  product and cutover gates pass.
- Consequences: Pressure has durable qualified candidate facts without creating a second canonical
  writer or claiming product readiness. `RECORDING`, production query/UI, typed deletion,
  correction/day repair, retention, portable import/export, device behavior, and activation remain
  independent gates. Standard-atmosphere altitude is not approved as calibrated elevation, and
  continuous ambient Pressure remains off.

## TI-D139 — Portable Steps export validates a complete Room snapshot before emission

- Status: `ACCEPTED_AND_IMPLEMENTED` at `6202d16a8`; portable import and no-resurrection remain
  `BLOCKED`
- Owner/date: portable Steps export owner after corrected adversarial review, forced focused tests,
  post-rebase static/schema gates, and local fast-forward, 2026-09-03
- Alternatives: export only presentation rows; trust selected-run-local correction history; infer
  ownership from timestamps; stream while validation is incomplete; preserve a partial live-fact
  checksum; validate the complete bounded Room snapshot and exact immutable authority before the
  first byte is emitted
- Evidence: the rebased DAO/integrity/writer/export selection passed `67/67`. Detekt, stats-data
  lint, app Hilt compilation, and Room drift passed. The initial review found and the correction
  closed one lifecycle/presentation envelope defect and one integrity-coverage gap; the final
  read-only review returned `GO` with no blocker, high, or medium finding.
- Decision: one portable entry is assembled only from reciprocally bound candidate-owned physical
  runs with complete immutable capture and writer authority. Replacement runs retain exact internal
  ownership while exporting under one logical entry. Fact lineage is global by writer/version/fact
  identity and every historical UPSERT consumes the selected run's correction budget; off-scope
  lineage fails closed. Canonical LIVE_WAL facts use a versioned digest over all retained fields,
  which is revalidated before portable signing. The export envelope covers both lifecycle and
  presentation time without using time as ownership.
- Consequences: a delayed presentation segment and a correction attributed to a later physical run
  cannot silently disappear from a valid export, and corrupt retained live facts cannot be
  re-signed as portable data. Earlier dormant-development rows produced with the narrower digest
  may now fail closed; v28 and the candidate lane have not shipped or activated, so no semantic
  revision bump is justified merely to preserve those non-production rows. This does not authorize
  import, registry/UI exposure, retention/no-resurrection, provider/device behavior, activation,
  rollout, push, or release.

## TI-D140 — Pressure selected deletion requires exact source-local authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `9592c42d8`; Pressure product query, retention, portable
  transfer, writer activation, and device proof remain `BLOCKED`
- Owner/date: Pressure deletion owner after correction-chain review, bounded-query correction,
  focused and static/schema gates, clean rebase, and local fast-forward, 2026-09-03
- Alternatives: delete compatibility samples by time overlap; treat presentation quiescence as
  deletion authority; reject every semantic correction; load an unbounded fact set; query consent
  once per manifest; reuse the Steps deletion result without Pressure attribution; validate and
  delete one exact candidate-owned Pressure logical/run scope under source-local fences
- Evidence: the forced post-rebase DAO/integrity/policy/deletion/day-repair selection passed `94/94`
  in `3m 47s` with 244 tasks executed. Post-rebase Detekt, Android-test compilation, core and
  tracker lint, app Hilt compilation, and Room drift passed in `4m 11s` (615 tasks: 100 executed,
  515 up-to-date). A fresh review found one bounded per-manifest consent-query fan-out; the
  correction uses one exact capped batch per scope validation, and the re-review returned `GO`
  with no blocker, high, or medium finding.
- Decision: deletion is allowed only for an inactive, reciprocally segment-bound, new-v28
  Pressure-only session whose complete manifest chain, policy, consent, capture QoS `1..3`, writer
  generation, collected-data epoch, and append-only fact integrity agree. Contiguous same-scope
  semantic corrections are valid only from revision one with strictly increasing revisions and
  admission ordinals under immutable lineage. Cross-scope corrections fail closed. The command
  installs the exact source fence, deletes every revision in deterministic 256-row keyset batches,
  repairs only bounded stored-zone days, and commits all mutations in one Room transaction.
- Consequences: cancellation, SQLite failure, mismatched delete counts, active scope, legacy or
  mixed capture, missing authority, invalid QoS, and unverifiable repair all leave facts,
  presentation, summaries, and fences unchanged. Retry is idempotent and delayed replay cannot
  resurrect the deleted scope. `QUIESCED`, sample count, and wall-time overlap are not deletion or
  source proof. This is host/static evidence for a dormant candidate lane, not Pressure history/UI,
  retention/export/import, physical sensor cadence/FIFO, process death/reboot/FGS, battery/OEM,
  activation, rollout, push, or release proof.

## TI-D141 — Empty deletion windows validate source truth without becoming numeric products

- Status: `ACCEPTED_AND_IMPLEMENTED` at `33e0eb71d`; representative-device deletion and manual
  Steps-only proof remain `BLOCKED`
- Owner/date: regression repair owner after full tracker-engine reproduction, focused correction,
  static/schema gates, fresh review, no-op rebase, and local fast-forward, 2026-09-03
- Alternatives: classify every empty-summary scope as unverifiable; move `Materializing` ahead of
  fact validation; allow the ordinary numeric accumulator to accept an empty day request; create a
  deletion-only zero-window accumulator that validates all surviving groups and facts but emits no
  numeric result
- Evidence: the first complete tracker-engine run exposed two empty-summary deletion failures in
  addition to 34 stale canonical Pressure fixtures. The corrected seven-class cohort passed
  `175/175` before commit and after rebase, the complete tracker-engine suite passed `2015/2015`,
  and Detekt, tracker lint, and Room schema drift passed. Two fresh reviews found no final defect.
- Decision: an explicit selected-session deletion may validate a scope even when no persisted daily
  summary establishes a product-day window. This exception is available only when an excluded
  segment identity is present. It must still stream and validate every surviving logical group and
  fact before returning `Materializing`; invalid authority wins over settlement state. A valid
  settled empty window yields `Ready(emptyList())`, never `Ready(0)`.
- Consequences: the ordinary product factory continues to reject an empty day request, so missing
  or unavailable Steps cannot become numeric zero. Deletion retry semantics remain truthful without
  weakening source authority, checksum, correction, ownership, or settlement validation. This is
  host/Robolectric evidence only, not device, provider, listener, process/reboot, FGS, battery/OEM,
  activation, rollout, push, or release proof.

## TI-D142 — Qualified numeric settlement is observed only while a product subscribes

- Status: `ACCEPTED_AND_IMPLEMENTED` at `57cfb4b10`; Statistics integration and device UI proof
  remain `BLOCKED`
- Owner/date: Steps numeric-product owner after focused Room/Game tests, static checks, fresh
  read-only review, clean rebase check, and local fast-forward, 2026-09-03
- Alternatives: refresh only when `daily_summary` changes; poll indefinitely; install a permanent
  observer network; expose internal history flows with incomplete dependencies; observe every
  durable table while one product caller is subscribed
- Evidence: the focused Room/Game selection passed in `17m 28s` with 368 tasks and proves a
  presentation-only `Materializing -> Ready(5)` transition without a summary write. After one
  brace-style precursor failure, root Detekt plus tracker-engine and stats-API lint passed in
  `5m 57s` with 378 tasks. Fresh review found no actionable defect.
- Decision: the public repository exposes a cold Flow backed by Room invalidation of the 12 direct
  coherent-read dependencies. Invalidations are conflated before each existing bounded read;
  cancellation is rethrown, storage failure remains typed, and the operation neither repairs data
  nor acquires any provider demand.
- Consequences: terminal run, completeness, lane, fact, deletion, retention, or calendar changes
  can refresh qualified numeric state without fabricating a daily-summary event. Because table
  invalidation is scope-broad and `source_evidence_state` is high-frequency, consumers must keep
  collection subscription-scoped and must not promote it into a permanent app-wide observer. This
  is host/Robolectric/static evidence, not rendered UI, provider/listener, physical device,
  process/reboot/FGS, battery/OEM, activation, rollout, push, or release proof.

## TI-D143 — Retained Steps facts are authenticated before product composition

- Status: `ACCEPTED_AND_IMPLEMENTED` at `b84f52d7b`; physical manual Steps-only proof and the
  remaining retained-metrics/import work remain `BLOCKED`
- Owner/date: Steps history-integrity owner after exact code reconciliation, storage and product
  adversarial review, forced focused gates, broad-suite correction, repository aggregates, and
  local integration preparation, 2026-09-04
- Alternatives: trust latest Room rows because they were locally written; infer source ownership
  from segment samples or wall overlap; let one complete replacement run mask another incomplete
  run; treat a counter reset as a new zero baseline; add a generic materializer/tombstone platform;
  share one bounded source-local validator/candidate reader across demonstrated Steps consumers
- Evidence: the forced 12-class selection passed `374/374`; review found and regression coverage
  corrected legitimate source sequence zero; the final six-class cross-source selection passed
  `171/171`; root Detekt and tracker lint passed; committed `ciUnitTest` and `ciCheck --continue`
  both passed. The initial broad suite's two stale test expectations and the invalid module-local
  Detekt selector are retained in the status/evidence ledger rather than hidden.
- Decision: a retained Steps row becomes product evidence only after its canonical checksum and
  LIVE_WAL shape, source-local correction/deletion lineage, reciprocal run/segment identity,
  immutable manifest/policy/consent/purpose/writer binding, completeness generation, lane, and
  retention/deletion epoch agree. Full-run consumers require the exact manifest revision union;
  civil-day consumers may read a retained contiguous slice without inventing omitted prefix
  authority. Numeric completeness is evaluated per physical run before logical replacement runs
  compose. Reset gaps span the prior and new counter domains, contribute zero, and remain partial.
- Consequences: malformed, missing, retained-prefix-incompatible, correction-conflicting, or
  materializing data stays typed and nonnumeric across history, portable export, deletion repair,
  and numeric consumers. `sampleCount`, `QUIESCED`, and wall-time overlap cannot authenticate
  ownership, and one valid run cannot conceal another invalid run. This adds no schema, provider
  demand, permanent observer, generic materializer, writer activation, rollout, push, or release.
  Device/provider/listener, process/reboot/FGS, battery/OEM, portable import/no-resurrection,
  retained awards/streaks/achievements, automatic control separation, and Ambient Steps remain
  separate gates.

## TI-D144 — Retention loss remains discoverable but never becomes source or numeric proof

- Status: `ACCEPTED_AND_IMPLEMENTED` at `7cc3149fb`; portable-import retention and physical-device
  Steps proof remain `BLOCKED`
- Owner/date: Steps retention/history owner after the old exact-head rejection, corrected focused
  and repository gates, and independent exact-commit review, 2026-09-04
- Alternatives: trust indexed operation/time/run fields before checksum validation; delete a whole
  affected run; let pruned entries disappear; infer Steps qualification from an exact manifest or
  stored segment count; create a generic tombstone/materializer framework; use one purpose-separated
  Steps run marker plus exact fact authentication and existing product composition
- Evidence: old exact HEAD `86a956431` was rejected for unauthenticated retention selection,
  pre-floor false-zero repair, deletion of post-floor suffix authority, marker-only history
  invisibility, and an unenforced wall/acquisition invariant. Focused regressions, complete affected
  module suites, root Detekt, changed-module lints, Room drift, `ciUnitTest`, and `ciCheck --continue`
  pass on corrected exact code commit `7cc3149fb`. Independent final review returned `GO` with no
  P1/P2 finding.
- Decision: within the retention transaction, authenticate the complete current-epoch Steps fact
  table before using any row field for indexed discovery. Install one immutable payload-free
  logical-run marker before pruning, delete only exact authenticated expired UPSERT identities,
  and retain post-floor suffix facts for bounded temporal discovery. Canonical Steps ingress and
  projection require `wallTimeMs == acquiredAtMs`; terminal failures caused by missing, negative, or
  mismatched source time cannot be released using those disputed times. A current-epoch marker can
  keep an exactly bound Steps-only entry visible as unavailable/partial, but never enters
  `qualifiedSources`, supplies a count, or authorizes selected deletion/export. Pre-floor day repair
  checks the retained floor before any no-overlap fast path.
- Consequences: retention cannot silently leave unauthenticated private payload or certify a pruned
  day as zero; replacement suffixes remain temporally visible while known loss stays explicit.
  Future portable-import facts fail retention closed until that vertical supplies its own intrinsic
  verifier. The whole-store audit runs twice in the current mark/prune pipeline; this bounded cost
  is accepted for the personal app and may be optimized only after measured need. No schema,
  provider demand, permanent observer, generic tombstone platform, activation, rollout, push, or
  release is added.

## TI-D145 — Unverifiable achievement progress is withheld without erasing history

- Status: `ACCEPTED_AND_IMPLEMENTED` through `2650857e4`, locally integrated, 2026-09-05.
- Decision: absent metrics are skipped, not defaulted to zero. Raw Steps-derived metrics and
  legacy XP metrics that cannot separate old raw-Steps contributions are unavailable for evaluation.
  Their stored achievement progress and derived meta rows are hidden from product presentation but
  retained. Meta evaluation counts only catalog-backed, directly trusted progress.
- Scope: this closes false awards/presentation, not qualified positive Steps achievements. It adds
  no retention schema, migration, provider, permanent observer, or activation. Revisit withholding
  when exact retained source authority supports a bounded positive consumer, not by trusting raw
  `daily_summary` or old ledger/profile rows.
- Evidence: focused stats-data tests, the live aggregator regression, Detekt and stats lint passed
  before this resume. Repository integration verification is recorded separately in TI-B204 onward;
  an earlier full stats-data run hit the 60-second portable-export batching test timeout and is not
  claimed as a green aggregate.

## TI-D146 — Preserve useful qualified Steps surfaces and contain raw-number bypasses

- Status: `ACCEPTED_AND_IMPLEMENTED` through `884b50550`, locally fast-forwarded after the required
  no-op rebase and post-rebase test gate, 2026-09-05.
- Decision: reuse existing Dashboard/history/detail and numeric repositories. Selected Calendar
  observation cancels when its tab is hidden; exact active-session widget Steps require qualified
  source evidence and complete coverage in one bounded read per refresh. Covered zero remains a
  value; missing, partial, materializing, and unverifiable states cannot become zero. Live milestone
  effects require qualified values and a segment-local high-water boundary. Stale session insight
  responses cannot cross a selected-session change.
- Containment: remove raw Steps badges/counters/share tokens and unqualified insight/achievement
  claims where no complete source-qualified consumer exists. Preserve independent distance/duration
  and the useful qualified live/Calendar/detail/widget surfaces. This does not complete positive
  awards, all-history Steps sharing/badges, or ordinary source-native activation.
- Evidence: fresh exact-HEAD review found no P1/P2; post-rebase host gate passed in `4m 11s`,
  610 tasks, XML 446 Statistics + 697 app + 31 selected import/export tests, no failures/errors/skips.
  Prior root Detekt and affected lint passed. No device/render, provider/listener, process/reboot,
  battery/OEM, push, release, or activation evidence is implied.

## TI-D147 — Raw Steps cannot award; accepted effects cannot cross deletion generations

- Status: `ACCEPTED_AND_IMPLEMENTED` through `501ad5bb1`, 2026-09-05; positive qualified Steps
  completion awards remain unfinished.
- Decision: remove raw Steps from fallback points/session XP and withhold raw goal completion
  writes, goal-reached notifications, and Steps Ghost comparisons. Remove wall-overlap slope awards.
  Preserve independently useful exact-segment distance/duration and mini-game behavior.
- Reuse the existing startup/deletion gate around admitted run identity, score/points/XP/profile
  effects, retry reconciliation, event load/apply/ACK, and exploration dirty handoff. New work must
  capture the generation before loading and carry it through mutation; reopened state cannot lend
  authority to an old run or loaded batch. Keep one accepted operation rather than nested gate
  acquisition. A zero-points row is an idempotency sentinel, not fabricated Steps coverage.
- `collect` replaces cancellation-on-invalidation for existing durable event drains. Cell/streak
  writes and acknowledgement share a Room transaction; dirty delivery is after commit and within
  the accepted generation. Cancellation remains exceptional and failures leave retry authority.
- Session XP uses exact persisted segment-end time and the corresponding half-open civil-day cap
  in the current device zone. It does not claim historical stored-zone authority for Steps goals
  or choose the final cross-midnight product rule.
- Evidence: corrected 128-test game/points cohort, 11 startup/runtime tests and root Detekt passed;
  deletion/load/reopen, cancellation, ACK rollback, delayed XP and inclusive/exclusive day bounds
  have focused regressions. Final rebased repository gates are recorded in TI-B206 onward. No
  migration, provider demand, activation, generic deletion platform, or device proof is added.

## TI-D148 — Queued achievement notifications share existing qualification authority

- Status: `ACCEPTED_AND_IMPLEMENTED` at `21437e97c`, 2026-09-05.
- Evidence: review found that quarantining persisted achievement presentation did not filter old
  `AchievementUnlocked` events. The queued event consumer could still announce legacy raw Steps,
  XP, or meta achievements. A five-case Robolectric regression uses the real qualified repository
  and catalog; the combined consumer/repository cohort is 14 tests, all passing with root Detekt.
- Decision: read qualified snapshot ID/tier membership once for each batch containing unlocks,
  within the existing accepted generation. Notify only matching ID/tier, acknowledge suppressed
  unknown or quarantined events, and retry without acknowledgement on query failure. Do not
  require `isUnlocked`: legitimate events can precede background progress persistence. Generation
  replacement after qualification still rejects both notification and acknowledgement.
- Consequences: notifications and achievement presentation agree without a duplicated metric
  blacklist, new schema, public abstraction, observer, or per-row database query. This closes a
  concrete consumer interaction; it does not qualify old numeric reward history or complete new
  positive Steps awards. The checkpoint now returns to the source vertical and device gates.

## TI-D149 — Qualified goal progress follows durable source changes while subscribed

- Status: `ACCEPTED`, 2026-09-09; code `b6301c80bd2691ddeadb93f4522755eb1e193724`,
  focused/module/full-gate acceptance evidence in TI-B210.
- Evidence: `SourceQualifiedStepsSummary` read once per legacy invalidation and retried only for
  1.75 seconds. Late source-only presentation settlement, correction or deletion could therefore
  leave a subscribed Game/GoalProgress consumer stale, despite the existing qualified Room
  repository already offering a cold dependency-complete observation.
- Decision: use that existing observation for independently qualified daily and weekly progress,
  scoped to actual subscribers. Keep calendar replacement and cancellation explicit, goal settings
  as presentation inputs, and missing/partial/materializing/storage states nonnumeric. Do not add
  timer polling, provider demand, background observer ownership, or a second qualification path.
- Boundary: this improves useful goal progress; it does not authorize positive durable awards from
  raw totals, choose new reward/storage semantics, solve silent clock changes without a product
  invalidation, activate Steps, or replace the physical only-source/UI/listener gate.

## TI-D150 — Portable Steps retain foreign evidence without fabricated live authority

- Status: `ACCEPTED_DORMANT_STORAGE`, 2026-09-09; source
  `099f9e1b9fc33c624cf1152550ec4c98857b938d`, full acceptance evidence TI-B211.
- Evidence: portable v1 deliberately omits live WAL/admission, boot, elapsed-clock and cumulative
  counter fields. The prior `PORTABLE_IMPORT` fact shape nevertheless required those fields and
  numeric zero for non-covered evidence. The wire contract already contains exact entry/run/
  manifest identities, original deletion-scope digests, stored zones and five completeness fields.
- Decision: retain that Steps-specific hierarchy in three dormant metadata tables and reuse the
  existing append-only fact table with origin-specific shape validation. Portable omitted fields
  must be null; only covered counts are numeric, including verified zero. Local collected-data
  epoch and writer binding remain distinct from foreign policy/consent provenance. No live runtime,
  local consent grant, provider demand, or compatibility session is synthesized.
- Integrity: retain the existing LIVE_WAL namespace and exact checksum field order, with a pinned
  pre-change digest regression. Portable intrinsic checks use a separate domain and preserve
  opaque identity verbatim. The retained fields can reconstruct the existing portable entry
  checksum; intrinsic integrity alone never admits an entry or establishes contextual ownership.
- Storage boundary: ABORT conflicts, exact parent foreign keys and unique original run-scope
  digests prevent silent identity replacement. Full collected-data clear removes facts and cascades
  imported metadata. Only three tables are added to unshipped v28; every existing v28 entity
  definition and all 51 released-v27 tables are preserved.
- Scope: this is the first necessary import slice, not a new origin platform or enabled importer.
  Production writes require a subsequent coordinated hierarchy/checksum/owner/fence/retention
  transaction, qualified history and numeric consumers, re-export, selected deletion, repair and
  no-resurrection proof. Existing readers continue rejecting non-live authority. Physical manual
  Steps, positive durable awards, automatic/ambient Steps and all other source gates remain open.
- Next-slice truthfulness constraint: the current portable exporter retains only the Steps capture
  binding even when the original run also captured other sources or used controls. Imported Steps
  therefore prove retained Steps membership, not the original full capture/control set. Keep
  `HistoryCapture.Exact` and live reverse-binding checks unchanged; use a bounded typed imported
  product branch rather than claiming `capturesOnlySteps` or inventing a local service run.
  Imported selected deletion may authorize only the retained Steps subset under its original
  deletion digest, never omitted source/control facts. These are continuation requirements, not
  implemented imported history or deletion support.

## TI-D151 — Publish only the accepted checkpoint; preserve unfinished parallel work

- Status: `PUBLICATION_SCOPE_CONFIRMED`, 2026-09-09.
- The user's clarification, "Push reviewed implementation and handover only", authorizes one
  normal `dev/v10` push containing accepted source `099f9e1b9`, evidence checkpoint `b925e89bc`
  and the current documentation successor. It does not authorize unfinished draft publication,
  feature/importer activation, a release, force push or subsequent publication.
- The six protected root paths, two older frozen drafts and three new uncompiled imported
  admission/product/actions worktrees remain local, unstaged and excluded. Their state and
  unresolved coordinated gates are recorded in `CONTINUATION_HANDOVER.md`.
- No source implementation or acceptance boundary changes. TI-D150/TI-B211 remain the accepted
  dormant storage boundary; proposed full-clear fences, imported history and source-local actions
  are not accepted fixes. The complete six-source vision and physical Steps gate remain open.

## TI-D152 — Authenticate retained imported members without inventing live authority

- Status: `VERIFIED_LOCAL_DEPENDENCY`, 2026-09-09; source `4db55146e`, TI-B213. Not integrated,
  published or activated. Imported-retention worker hooks must accompany integration because
  generic physical-segment retention now excludes the explicitly imported source.
- Retain the original whole-entry checksum and add an exact reverse physical-member binding and
  per-member retained checksum to the unshipped-v28 imported representation. Null legacy receipts
  remain unverifiable. A retained sibling need not reconstruct a deleted original whole entry;
  it must still authenticate its own exact hierarchy, facts, stored zone and original scope.
- Keep historical writer-owner generation distinct from origin-specific fact binding generation.
  Portable binding is a fixed representation contract, not a claim to the local manual or automatic
  live writer lane. Preserve LIVE_WAL checksums and source-specific ownership.
- Reuse the portable v1 canonicalizer through core/model and the existing stats API facade. Raw
  Room shape checks precede Boolean/Int narrowing; malformed zones produce typed integrity failure.
  Bounded adaptive batch splitting isolates malformed entries without permanent observers or
  ordinary per-row query fan-out.
- Full collected-data clear retains payload-free original-scope fences for admitted imports and
  exactly attributable live Steps capture. A malformed original scope digest causes atomic rollback;
  this is a documented corruption-recovery limit, not arbitrary-corruption deletion proof.
- This dependency does not implement authoritative admission, imported numeric/day repair,
  selected deletion or full round-trip/no-resurrection. Those source-local operations must converge
  before importer exposure. The full six-source product and physical Steps gate remain incomplete.

## TI-D153 — Imported Steps history exposes retained evidence, not original capture selection

- Status: `VERIFIED_LOCAL_PRODUCT_DEPENDENCY`, 2026-09-09; source `ecbdf6161`, TI-B214.
- Add a bounded imported branch to the existing history/read/export composition and cold Room
  invalidations. `HistoryCapture.ImportedSteps` preserves foreign Steps manifests without claiming
  the original full capture/control set. `RETAINED_IMPORTED` is not local provider availability;
  neither `capturesOnlySteps` nor Location qualification is inferred from compatibility samples.
- Group retained physical runs under their logical imported entry, while preserving individually
  selectable segment IDs and per-member values. Covered zero is numeric; overlap, overflow,
  missing coverage and retention loss stay typed partial/unavailable. No latest-run value or sum
  is presented as an invented logical total.
- Re-export only when the original whole-entry identity/checksum can still be reconstructed.
  Missing/deleted/retention-truncated members are not silently reminted. This conservative v1
  boundary does not complete portable round-trip support after retention loss.
- Both origins have independent 16,384-dependency export budgets, so the combined read is bounded
  by their sum, not by a claimed single 16,384 cap. Imported budget enforcement is incremental:
  overflow stops before requesting the next batch. Combined wire identity/scope uniqueness and
  entry limits are still enforced before output I/O.
- No new UI or importer activation is included in this commit. Dashboard/Detail integration,
  broader History/Calendar discovery, numeric/day repair, exact deletion and authoritative import
  must converge with source-local retention before the product gate is complete.

## TI-D154 — Keep Steps numeric authority in source-qualified reads

- Status: `VERIFIED_LOCAL_API_CLEANUP`, 2026-09-09; source `3f62d5c644b44277088097800a85637a955cfdb0`,
  TI-B215. Remove the unused `stats.api.repository.DailySummary.totalSteps` property and make
  windowed Steps metrics return the existing typed validation error before any DAO read.
  Non-Steps metrics keep their existing behavior. No production windowed collector was found.
- Do not add a nullable daily cache or broaden the schema for a nonexistent consumer. Existing
  source-fact composition remains the numeric authority; other compatibility summary types still
  exist and their product callers must retain their source-qualified Steps overrides.
- Import-only partial materialization may preserve an old non-authoritative compatibility integer
  while repairing independent metrics and the stored zone. This is not a deletion policy: full
  database exports copy retained SQL aggregates, so keeping a deleted contribution would leak it.
  Keep selected deletion's partial/unrepresentable repair guard until atomic Steps-only aggregate
  redaction is implemented and verified. Redaction zero must never become qualified observed zero.
- This API cleanup neither accepts the pending engine repair draft nor completes positive awards,
  authoritative import, deletion, physical provider proof or the six-source program.

## TI-D155 — Retain imported Steps under their exact original ownership

- Status: `VERIFIED_LOCAL_RETENTION`, 2026-09-09; TI-B216. Extend the existing source-local
  retention functions and two app workers; no new scheduler, provider, migration or tombstone platform.
- Authenticate intrinsic raw revisions plus complete retained hierarchy before age selection. Use
  exact entry/run/segment membership to delete, never wall overlap, QUIESCED or sample counts.
  Preserve the original opaque deletion digest and distinguish truncation markers from capture
  deletion fences. Imported and live run identities cannot share ownership.
- Raw retention removes only expired exact UPSERT revisions; cutoff boundaries and straddling
  intervals survive as partial evidence. Update the retained-member receipt atomically, keeping
  the original whole-entry checksum unchanged. Empty retained coverage is not numeric zero.
- Trip retention removes only selected physical imported members and their payload, with original
  scope fences and source evidence revision in the worker's existing generation-guarded transaction.
  Retained siblings survive. Authenticated malformed scope/receipt state aborts rather than silently
  authorizing deletion. This does not implement arbitrary-corruption recovery or selected-session deletion.
- Full-entry authentication occurs once per retention pass, not once per raw fact page. Bounded
  adaptive entry batches preserve legitimate multi-entry cases without ordinary per-row fan-out.
- This supplies the worker hooks required by TI-D152, but integration still requires the frozen
  converged gate. Authoritative import, numeric/day repair, selected deletion and complete
  round-trip/no-resurrection remain separate dependencies before importer exposure.

## TI-D156 — Show imported Steps without borrowing other source products

- Status: `VERIFIED_LOCAL_PRESENTATION`, 2026-09-09; TI-B217. Use the existing Dashboard recent
  history card and contained Trip Detail, not a new UI platform or all-source schema.
- A logical imported entry exposes each retained physical recording by exact segment ID. Multiple
  recordings expand on demand; neither the latest member nor their sum becomes an invented
  logical total. One member is directly selectable. Recording labels use Android plural resources.
- Retained covered zero remains zero, partial coverage an explicit lower bound, and missing or
  materializing evidence distinct from both. Imported evidence does not establish current live
  Steps registration or the original complete capture/control set.
- Imported Detail presents retained Steps and timestamps only. It suppresses distance, samples,
  route, elapsed duration and activity claims; map/GPX actions and supplemental local route/Ski
  reads are excluded. Wall overlap cannot enrich imported ownership by accident.
- The broader History/Calendar composition, authoritative import and source-local actions still
  have their own gates. Host Compose evidence is not rendered device UI or physical Steps-only
  proof. No source, importer, writer or feature is activated by this contained read surface.

## TI-D157 — Defer all execution-based validation until implementation convergence

- Status: `ACTIVE_USER_DIRECTIVE`, 2026-09-10; TI-B218. Author production logic and focused unit/
  contract tests together, but run no build, compilation, test, lint, Detekt, Room drift, emulator,
  device, evaluator, battery, CI, or release task during the implementation-only phase.
- Source review and exact-path Git review continue because they protect ownership and unrelated
  work; they are not evidence that code compiles or behaves correctly. Every new implementation
  commit is explicitly `IMPLEMENTED_UNVALIDATED` and carries its expected validation commands and
  known debt forward.
- Do not integrate unvalidated work into local `dev/v10`. After every required source/product/action
  piece exists, compose a dedicated local convergence branch, freeze inputs, execute the complete
  dependency-ordered validation plan, fix failures, and only then use the repository's normal
  reviewed rebase/merge process.
- Prior evidence remains scoped to the exact earlier commits. Deferral does not weaken final
  acceptance, privacy, lifecycle, battery, device, migration, or six-source product requirements.

## TI-D158 — Imported Steps share numeric composition without gaining local lifecycle authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; TI-B219.
- Authenticated portable entries are consumed in bounded entry batches into the existing Steps
  day accumulator. Imported run, entry, segment, manifest, fact, deletion-scope, retention, zone,
  and checksum identity remain their own authority; no local service run, consent, policy,
  provider generation, elapsed clock, or tracked duration is fabricated.
- Fact-time discovery supplements the presentation envelope after wall-clock movement. Imported
  and local partitions must not reuse a run, segment, or logical identity, and exact physical
  ownership remains internal when replacement members compose into a logical result.
- Qualified numeric product state keeps Long counts. The legacy daily-summary integer is only a
  compatibility cache: partial or Int-overflow materialization may preserve an existing value while
  repairing independent totals, but it must fail closed when preservation would otherwise invent
  zero. Selected deletion remains stricter and refuses any nonexact compatibility recomposition.
- The production code and focused tests exist at f93b373ef and continuation cherry 293ff43e5.
  No validation command ran; compilation, test behavior, static checks, and the previously reported
  imported test-class initialization failure remain deferred to convergence.

## TI-D159 — Admit portable Steps through one inert source-local transaction

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `8abd7c6e3`, TI-B220.
- Bind one source-local `ImportPortableSteps` command, but keep it unreachable from file discovery
  until stored-zone day repair and registry work are complete. Import never starts or retains a
  provider and never creates a local logical session, service run, policy, consent, elapsed clock,
  provider generation, or control observation.
- Deep-snapshot the caller graph and recompute its canonical checksum before preflight and again in
  the owning Room transaction. Use the existing bounded production exporter as the one global
  native/imported identity view; reject cross-entry entry, run, fact, or original deletion-scope
  reuse instead of introducing another identity index or generic import framework.
- Preserve portable entry, physical run, manifest, original policy/consent numbers, purpose, zone,
  fact, checksum, and deletion-scope identity. Store the current canonical destination-owner
  generation only as the local admission receipt; imported facts keep the fixed portable binding
  and cannot acquire live writer authority from that receipt.
- Check the monotonic lifecycle floor plus capture-deletion and retention-truncation fences before
  mutation, then insert the full hierarchy and advance source evidence in one transaction. Exact
  replay returns `Duplicate` without changing Room or observers. Cancellation propagates and rolls
  back; transient storage and state races stay retryable.
- Post-commit table invalidation is present, but exact affected-day repair and the product file
  bridge remain explicit blockers. This commit is not compilation, test, Room-schema, device,
  no-resurrection round-trip, activation, integration, publication, or release evidence.

## TI-D160 — Repair imported Steps days before exposing an admitted hierarchy

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `3dfa4eaad`, TI-B221.
- Acquire the bounded conservative epoch-day lock envelope before the owning Room transaction.
  Inside it, preserve an existing calendar-zone authority whose physical window intersects the
  imported evidence; otherwise accept exactly one physical window derived from the original stored
  run zones. Conflicting, missing, overlapping, excessive, or uncovered authority fails closed.
- Insert the exact imported hierarchy and recompose every resolved day from authenticated local and
  imported facts in the same transaction. Imported facts contribute neither invented local duration
  nor provider authority. A partial or Int-unrepresentable result may retain an existing legacy
  compatibility integer, but cannot create a new zero-valued compatibility row.
- Recheck lifecycle state after repair. Cancellation, storage failure, lifecycle drift, typed
  materialization, or unverifiable repair rolls back payload, summaries, and source evidence
  together. Room/source observers and metric dirty state advance only after commit.
- The importer remains unreachable from file discovery. No validation command ran; compilation,
  test behavior, malformed/reopen/correction coverage, file round trip, no-resurrection, activation,
  integration, publication, device, or release evidence is claimed.

## TI-D161 — Route portable Steps files without stealing source-local transaction authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `c58fcc85f`, TI-B222.
- Register one import-only, versioned `.trackersteps` descriptor and decode it only through the
  existing strict bounded portable v1 codec. Resolve the already-bound source-local command from
  the application graph; the adapter creates no provider, session, run, capture, or control demand.
- Honor the existing `FileImport.transactionMode` contract. Legacy importers retain the worker's
  enclosing receipt transaction. Portable Steps executes outside it so bounded civil-day locks can
  precede the importer's own atomic Room transaction; a successful replay receipt is recorded in a
  separate transaction only after source-local completion.
- Map applied entries, identical replay, deletion/retention fences, permanent conflict or
  unverifiable state, and transient retryable failure distinctly. Cancellation continues to escape.
  Direct-file content addressing is bounded by the format's maximum byte count; archive bounds and
  per-entry replay receipts remain owned by the existing archive path.
- Focused adapter, registry, routing, transaction-boundary, failure-receipt, malformed-input, and
  cancellation tests are authored but were not run. Malformed retained-state, reopen,
  correction-specific Room coverage, complete file round trip, no-resurrection, device/UI,
  integration, activation, publication, and release evidence remain open.

## TI-D162 — Retained corruption and extra lineage can never become an import replay

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; tests `03db9e565`, TI-B223.
- An existing imported entry is an identical replay only when its complete retained hierarchy still
  authenticates. Raw Boolean corruption must return typed unverifiable state without payload,
  evidence-revision, summary, or observer mutation.
- Any additional fact correction or redaction lineage prevents the portable v1 latest-state row
  from being treated as the complete original hierarchy. The importer fails closed instead of
  silently collapsing correction history into a duplicate.
- A committed import and its source evidence, presentation binding, day summary, and replay identity
  must survive a close and fresh production Room open; the next identical import is a side-effect-free
  duplicate. These contracts are authored only. No test, compilation, schema, device, integration,
  publication, activation, or release evidence is claimed.

## TI-D163 — Imported selected deletion owns the complete logical entry and two run fences

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `89454b8c1`, tests `3ff5c086c`,
  TI-B224.
- A portable replacement member is a product selection handle, not an independently deletable
  ownership scope. Resolve it through the exact imported run-to-segment binding, authenticate the
  complete retained original entry, and delete all of its physical replacement members together.
  Never reinterpret the portable entry as a locally captured service run and never consult, start,
  retain, or drain the live Steps provider.
- Plan from every run envelope and every fact wall interval. Lock the bounded all-zone plausible
  day set before the Room transaction, preserve one nonoverlapping persisted calendar authority,
  and re-read the complete scope after locking. Any authority drift is retryable; incomplete,
  retained-away, corrupted, or ambiguous original evidence fails closed before mutation.
- For every physical run, install the original portable deletion-scope fence and the independently
  derived current-local logical/run fence before removing payload. Retain one payload-free latest
  retraction per fact, then compare-and-delete exact imported receipts, manifests, segments, and
  the now-empty entry. This rejects both portable re-import and delayed local projection/restore.
- Recompose all affected days from surviving authenticated source facts in the same transaction.
  If the surviving Steps state is partial or cannot fit the compatibility integer, write zero only
  as an explicit legacy redaction sentinel; typed product qualification remains partial or
  unavailable and can never treat the sentinel as measured zero.
- The focused source contracts cover complete replacement deletion, both fences, redacted
  retractions, re-import refusal, partial compatibility cleanup, malformed and corrected hierarchy
  refusal, retention-loss refusal, cancellation rollback, and backup/fresh-Room reopen. They have
  not compiled or run. No activation, integration, publication, device, or release claim follows.

## TI-D164 — Portable Steps is a bidirectional source-owned product format

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `19b8789b2`, tests `2d7af2b70`,
  TI-B225.
- Register `.trackersteps` for export as well as import. The export adapter resolves the existing
  read-only `ExportPortableSteps` singleton and streams it through the strict v1 codec; it creates
  no provider, demand, session, writer, policy, consent, repair, or mutation authority.
- A source-owned exporter declares that it does not depend on Location rows. Date-range selection
  therefore passes an empty Location sequence to the adapter instead of applying the legacy
  Location-count preflight. Whole-history export remains a bounded half-open request.
- Use the portable contract's canonical extension and MIME type. The privacy-minimized artifact is
  not labelled as containing precise Location; database, GPX, KML, and legacy JSON exports retain
  their existing sensitive-location confirmation.
- No-entry, durable unverifiable state, and retryable storage/state failure remain distinct user
  errors. A local export can be imported into a fresh database, queried as retained imported
  history, and canonically re-exported without creating a local service run, source event,
  admission ordinal, or original full-capture claim.
- The production and focused test source is authored only. No compiler, Gradle, test, lint, Detekt,
  Room drift, device, UI, integration, publication, activation, or release command ran.

## TI-D165 — Manual Steps is one exact source-owned path with no implicit control

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source audit plus focused contract, TI-B226.
- Every ordinary manual entry point delegates to the same source-aware start API. Readiness and
  service preparation resolve the current immutable policy and rollout; a capable reachable Steps
  source is sufficient and Location is not a prerequisite.
- A manual Steps-only plan contains exactly Steps. Manual origin adds no control dependency, so it
  cannot create or retain an Activity, Location, Pressure, Wi-Fi, Cell, CONTROL, or AMBIENT demand
  merely to enrich Steps. The exact manifest, service run, demand, authorization, WAL evidence and
  candidate fact retain their source, purpose, policy, consent, epoch and generation bindings.
- The app-scoped step-counter owner establishes a post-effective baseline, admits only fresh
  generation-valid boundaries through the atomic durable ingress, treats reset/gaps/unchanged/
  covered-zero/positive/partial/capability/storage states distinctly, and durably retires the exact
  listener before releasing provider ownership.
- The source-only list, Today/Calendar, Detail and live read surfaces consume qualified history and
  never infer source presence from legacy `sampleCount` or fabricate zero. The dormant writer
  transition stays contained and default-off; this decision does not activate or roll out the lane.
- Production and test source was inspected and one missing exact host-plan assertion was authored.
  No compiler, Gradle, test, lint, Detekt, Room drift, Android provider, listener, UI, battery,
  integration, publication, activation, or release command ran.

## TI-D166 — Daily and weekly qualified Steps share one bounded read snapshot

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-10; source `7eaa891b3`, TI-B227.
- The only demonstrated multi-window consumer is current-day plus week-to-date Steps presentation.
  Its repository contract therefore accepts one or two complete range requests, not arbitrary
  source/day fan-out, and preserves request order in one immutable result.
- Room resolves every requested window inside one reader transaction. Calendar authority and
  local/imported source qualification are still evaluated independently per range, but daily and
  weekly results can no longer come from different committed database generations.
- Existing single-window reads and observations delegate to the same batch implementation. The
  Game summary owns one cold subscription per calendar authority; cancellation retires that one
  observer, and settings changes continue to remap qualified values without restarting storage.
- This is a read-composition boundary only. It does not make the source-evidence revision an effect
  CAS, persist a goal/effect identity, re-enable points or XP, create a streak, mutate achievement
  progress, or define correction/deletion retraction. Those remain required before any positive
  Steps effect is allowed.
- Focused source and tests were authored but no compiler, Gradle, test, lint, Detekt, Room drift,
  device, UI, integration, publication, activation, or release command ran.

## TI-D167 — Qualified goal decisions carry exact replaceable source authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; sources `ead24608c`, `759c369b4`,
  `343f13577`; TI-B228.
- A Steps goal effect is keyed by structural period and kind and records source-evidence revision,
  source-result digest, exact per-day zone authority, target, qualified value, decision state, and
  its own monotonic effect revision. The effect row is the decision authority, not
  `daily_summary`, `SessionSegment.steps`, or a live tracker snapshot.
- Effect creation rechecks the source snapshot inside the owning Room transaction. Identical replay
  is unchanged; a newer source decision replaces the exact effect; an older observation cannot
  overwrite it. Materializing and unverifiable inputs remain typed and never become zero.
- The v28 schema shape and migration contracts are authored. The generated Room identity hash is
  intentionally deferred to convergence and must not be guessed manually.

## TI-D168 — Qualified goal rewards are source-local reversible projections

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; sources `8bbe2d0d5`, `26442893a`,
  `1f4041d00`; TI-B229.
- Points and XP use independent source-local ledger identities and monotonic effect revisions.
  One effect can create, replace, or retract its own delta without reopening unrelated award
  ownership or relying on wall-time overlap.
- A replay of the same effect revision is zero-effect. A correction or deletion projects the exact
  replacement delta, including zero, while stale revisions and generation-invalid transactions are
  rejected. Point and XP component failure remains independently retryable.
- This does not authorize legacy XP cleanup, player-level requalification, coordinator activation,
  or any other source's awards.

## TI-D169 — Goal notification delivery is claimed durably and orchestration stays dormant

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; sources `2745384f8`, `dc23917e5`;
  TI-B230.
- A notification claim belongs to the exact current eligible effect revision and is committed
  before platform delivery. Disabled notification policy consumes the claim without delivery;
  ambiguous platform failure does not reopen it and risk duplicates.
- One app-process coordinator is authored to observe qualified current periods, drain historical
  repairs, project both reward components, and dispatch claims. It does not create provider demand.
- The coordinator is deliberately not started by `GameModuleInitializer`. Runtime activation
  remains forbidden until all implementation pieces converge and the deferred validation gate
  succeeds.

## TI-D170 — Historical Steps effects repair under stored zone authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; source `9520af007`; TI-B231.
- Normal fact projection, day materialization, portable import, selected deletion, and retention
  enqueue exact affected structural days while advancing source evidence. The queue collapses
  revisions and removes only the exact request it settled.
- Historical reconciliation uses persisted per-day zone authority. Pending repair marks dependent
  qualified achievements materializing; terminal unverifiable evidence stays nonnumeric; ready
  history replaces day/week effects and correction-sensitive streak/perfect-week progress.
- Qualified achievement rows preserve an explicit notification high-water and exact unlock time.
  Bootstrap creates a baseline without retroactive celebration, and a downward correction followed
  by restoration cannot resurrect a prior notification.
- Dashboard and Game product composition accept only READY qualified rows. Missing,
  materializing, or unverifiable progress is absent rather than displayed as fabricated zero.

## TI-D171 — Raw Steps presentation cannot feed Game goals

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; sources `e9f344f46`, `90f603c36`;
  TI-B232.
- Remove the legacy goal object graph and every Game-goal dependency on
  `TrackerSessionSnapshot`, raw `DailySummaryUpdated.totalSteps`, and `SessionSegment.steps`.
  `GoalTracker` now carries only a payload-free structural calendar invalidation.
- Daily and weekly target settings remain direct flows. Numeric presentation reads only
  `StepsNumericSummaryRepository`, whose durable dependency observation handles source
  settlement, correction, deletion, retention, and import changes.
- Keep `DailySummaryUpdated` as an acknowledged compatibility event with no goal, XP, or
  achievement-scheduling effect. Retain only the notification UI helper needed by the qualified
  effect dispatcher and remove the unused `:tracker:api` module dependency.
- A read-only post-commit review found no P0/P1 defect. Its localization-inventory and explicit
  no-scheduler test follow-ups are included in `90f603c36`. No validation command ran.

## TI-D172 — Retained Steps achievements derive only from exact source facts

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; sources `77a48b2b1`, `04aaf16e8`, and
  `be1f0cd7b`; TI-B233 and TI-B234.
- Lifetime total and best daily Steps are typed retained-source decisions, not reads of
  `daily_summary`, `SessionSegment.steps`, raw intervals, or presentation rows. Their production
  repository composes exact authenticated local and imported facts, correction lineage, stored-zone
  day authority, retention loss, completeness, source-evidence revision, and a canonical digest.
- A retained metric snapshot may be READY, MATERIALIZING, UNVERIFIABLE, or storage-unavailable.
  Missing, partial, retained-away, malformed, conflicting-zone, or Int-unrepresentable evidence
  never becomes fabricated zero. Active pre-floor work receives a bounded post-floor probe so it
  remains pending or unverifiable rather than falsely absent.
- `STEPS_TOTAL` and `BEST_DAILY_STEPS` use the same qualified achievement authority as goal streak
  and perfect weeks: exact `QUALIFIED_STEPS_V1` revision/digest, READY state, and a persisted claimed
  high-water. First qualification bootstraps without retroactive notification; downward correction
  repairs the visible tier/value without lowering the claim; restoration at or below that claim does
  not notify again; a later tier above it emits each newly crossed tier.
- Achievement row replacement and nonempty unlock-event outbox insertion share one Room transaction.
  Notification authorization requires exact revision and digest equality while holding one startup-
  generation lease through platform delivery. A deletion or generation change after that lease
  cannot retroactively redefine a delivery that was valid under the held authority, while stale
  acknowledgement remains fenced.
- `StepsRetainedAchievementReconciler` is deliberately absent from `GameModuleInitializer`, as is
  `StepsGoalCoordinator`. This decision authors dormant logic and focused tests only; it does not
  activate an achievement writer, provider, reward, notification, rollout, or release path. No
  compiler, Gradle, test, lint, Detekt, Room drift, device, UI, battery, CI, integration, or
  publication command ran.

## TI-D173 — Widget and legacy notification Steps remain typed through presentation

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; source `8e3cb68e5`; TI-B235.
- Widgets must not use `DailySummary.totalSteps` or `TrackerSessionSnapshot.steps` as numeric
  authority. The app maps qualified day results and exact selected-segment history into ready,
  partial, materializing, not-captured, disabled, unavailable, and storage-unavailable presentation
  states. Only qualified complete evidence may display a number; covered zero remains numeric.
- The active widget waits for bounded settlement and accepts only its selected segment. The Today
  widget keeps every nonnumeric source state visible with an explicit localized label. A lower
  bound is labeled with `≥`; an unknown partial is labeled `Partial` rather than zero.
- The legacy periodic goal-notification worker retries transient materialization/storage state and
  terminates without claim mutation for nonnumeric terminal state. It persists a threshold claim
  only after platform notification handoff; denied notification permission is not a delivery.
- Current session history carries a disabled availability. Daily qualified totals do not yet carry
  current policy authority, so a missing or not-captured day must not be guessed as disabled. That
  bounded propagation remains open before `TODO-STEPS-NUM-005` can close.

## TI-D174 — Generic trip and JSON products do not carry unqualified Steps

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; source `7a36129db`; TI-B236.
- `TripSummary` carries only independently truthful non-Steps fields. Selected-session Steps belong
  to `TrackingHistoryRepository`, which can verify exact segment/run/writer/source completeness;
  the nullable legacy trip column cannot.
- Newly written schema-3 JSON omits its already-optional session `steps` member. The importer keeps
  accepting historical files that contain it, so compatibility does not require continuing to
  emit an unqualified number.
- `.trackersteps` remains the portable Steps product because it carries exact source evidence,
  correction lineage, covered zero, partial state, and ownership. Generic JSON location/radio
  export does not borrow that authority or infer it from a trip row.

## TI-D175 — Generic daily and session aggregates do not carry Steps authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; source `294b76c16`; TI-B237.
- The app-level `DailySummary` carries only distance, duration, and session count. Qualified daily
  Steps remain a separate `QualifiedStepCount`, including covered zero and typed unavailability.
- Aggregate `SessionStatsSnapshot` and its Room `SessionSegmentStats` projection carry only
  non-Steps metrics. The legacy physical segment Steps column is compatibility storage and cannot
  be reintroduced as an aggregate product number through this query.
- Removing a public projection field does not authorize removing legacy Room columns, migration
  inputs, correction/day-repair state, or source-local portable data. No destructive schema change
  is part of this decision.

## TI-D176 — Historical trajectory V1 excludes unqualified legacy Steps

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-11; source `967b7ebc9`; TI-B238.
- Clock-domain and monotonic overlap with `StepInterval` do not prove manifest membership, service
  run, writer, capture purpose, consent, completeness, or correction/deletion authority. Historical
  reconstruction therefore cannot use such a row as stationary evidence or source lineage.
- The pure reconstruction API retains optional `stepDelta`, and the nullable Room lineage column
  remains compatible, but V1 supplies neither until an exact source-qualified reader is justified.
- Input composition is part of derivative identity. The runner appends
  `location_activity_v2` to the pure configuration version and persists that exact value, ensuring
  old step-influenced `default_v1` runs remain eligible for replacement.
- Raw legacy interval reads remain permitted only for source-local persistence, migration,
  recovery, deletion/rearm, and inactive local research/debug evidence. They cannot authorize a
  product number or effect without a new explicit decision and source-qualified contract.

## TI-D177 — Daily disabled Steps requires active source-policy authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; sources `d95d8bc56`, `987cb550b`;
  TI-B239.
- A daily absence can be labeled `DISABLED` only when the existing `SourcePolicyRepository`
  supplies an active, validated immutable six-source snapshot whose Steps session capture and
  ambient-product persistence are both off. Control-only authority cannot make captured history
  available. Product code does not infer disablement from a missing fact, legacy preference,
  sample count, or raw policy row.
- Current policy refines only daily `NOT_CAPTURED`. It cannot replace a qualified ready value,
  partial/materializing result, calendar/storage failure, or a weekly period that may contain
  historical captured days. Uninitialized or invalid policy authority is unavailable, never an
  all-disabled user choice.
- Widget presentation maps the typed reason to `Disabled`. The legacy goal-notification worker
  treats it as terminal nonnumeric state before threshold or claim access. Neither consumer may
  fall back to raw daily/session Steps.
- The new policy flow shares the existing while-subscribed product lifecycle. It does not register
  provider demand, activate either dormant Steps effect reconciler, or create a permanent observer.

## TI-D178 — Numeric consumer tests stay split at their owning authority seams

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source this documentation checkpoint; TI-B240.
- Do not introduce a synthetic end-to-end numeric fixture that bypasses production ownership merely
  to put every scenario in one class. The exact Room readers own positive, covered-zero, partial,
  correction, deletion-fence, retention, and imported-origin assertions. Presentation consumers own
  typed mapping and observation lifecycle; durable effect consumers own replay, replace, retract,
  high-water, atomicity, and stale-generation assertions.
- Imported origin is authenticated by the storage reader. Downstream product and effect consumers
  intentionally receive the same qualified decision as a local source fact and must not branch on
  provenance or award it again.
- Static inspection found focused authored coverage for every audited production consumer and each
  required scenario category. No test or validation command was run; all evidence remains authored
  and unvalidated until final convergence.

## TI-D179 — Automatic start does not retain Steps for corroboration

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source `b779afd1e`; TI-B241.
- The former Step corroboration path could only widen sampled Activity starts from confidence 50 to
  75 after an Activity callback. It could not legally cold-start tracking by itself, yet retaining
  its `CONTROL_AUTOSTART` join kept the physical counter alive solely for cross-source enrichment.
- Activity recognition is the sole automatic-start control. Sampled recognition requires the full
  configured confidence threshold; Activity Transition remains the legal transition trigger.
  Automatic Steps sessions still capture Steps exactly and declare Activity separately as control.
- No Steps automatic-control setting, consent, demand, recent-evidence cache, or product history is
  created. Builds retire the old app-scoped Steps control demand and reconcile the shared physical
  listener back to session capture only. Cleanup failure is retryable but cannot make Steps a hidden
  prerequisite for a usable Activity registration or prevent Activity removal on disable.
- This closes only the corroboration product decision and dead control path. TI-D180 subsequently
  reconciles the already-present durable Activity gateway; the immutable automatic manifest,
  purpose-limited control retention, complete stale-trigger proof, recovery, trigger-to-query
  composition, and default-off Ambient Steps remain later gates.

## TI-D180 — Automatic Steps reuses the existing durable Activity start gateway

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; current-source reconciliation at `9e59e8601`;
  historical gateway source includes `0a6a8f545`; TI-B242.
- Activity automation persists a source outbox envelope with exact provider registration,
  authorization, boot, observed/received clocks, automation epoch, and collected-data epoch. Only
  the exact still-live Activity Transition callback ordinal receives a foreground-start permit;
  sampled recognition and cold replay cannot create a legal cold start.
- The consumer reserves and revalidates one durable automatic-start action, commits
  `START_REQUESTED`, and then asks `TrackerServiceApi` to prepare the immutable lifecycle intent.
  Room PREPARE and exact startup/lifecycle authority checks precede
  `ContextCompat.startForegroundService`; expiry is checked before PREPARE and again immediately
  before enqueue. Failed or stale preparation is compensated or terminalized without Android
  delivery, and cold replay never reissues the platform call.
- The trigger carries its immutable identity, origin, boot and elapsed clocks, expiry, automation
  and policy revisions, requested and intended capture masks, intended FGS type mask, and collected
  data epoch. Provider generation, control consent, current policy, lifecycle action, and manifest
  correspondence are revalidated transactionally at their owning boundaries rather than inferred
  from current motion or wall time.
- Automatic Steps therefore uses this shared Activity-owned gateway; no Steps-local gateway,
  provider, observer, action table, or service entry is introduced. This closes AUTO-002 only.
  TI-D181 subsequently closes the exact manifest-attribution contract. Provider demand, control
  nonleakage, full stale-action coverage, recovery, production query/UI, and eventual Android/device
  legality remain later gates.

## TI-D181 — Automatic Steps manifests contain exactly Steps capture and Activity control

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/test contract `3e1fb6189`; TI-B243.
- The existing coordinator derives session capture bindings only from enabled plans and derives
  automatic control dependencies only as Activity. For generation 2 Automatic Steps, the immutable
  manifest therefore contains exactly two purpose-qualified members: persistence-eligible Steps
  `SESSION_CAPTURE` and nonpersistent Activity `CONTROL`.
- The Steps member carries the exact capture consent and QoS plus the current candidate destination,
  owner generation, projection identity/version, and automatic-capable binding generation. The
  Activity member carries its distinct control consent and QoS, is not persistence eligible, and
  has no output destination or writer provenance.
- The manifest itself is checksum-protected and bound to the exact logical entry, physical service
  run, automatic mode/origin, policy, plan, rollout, boot and elapsed/wall effective clocks, stored
  zone, and automation epoch. No source is inferred from sample count, wall overlap, or control
  evidence.
- The focused Room contract now asserts all of this in one Automatic Steps preparation scenario.
  Existing generation-1 containment remains manual-only. This closes AUTO-003 only; TI-D182
  subsequently records provider-demand activation and exact control/capture runtime reconciliation.

## TI-D182 — Automatic Steps activates only Steps capture and Activity control demand

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; focused contract `27ec42e74`; TI-B244.
- Room PREPARE stages the exact manifest-derived Steps `SESSION_CAPTURE` and Activity
  `CONTROL_CONTINUATION` demand pair as blocked. Neither is registration/callback authority before
  the exact Android start is claimed and foreground acceptance is durably acknowledged.
- Foreground acceptance activates only that exact run/manifest/lease demand vector. The Activity
  member remains nonpersistent control, while lifecycle source actions and runtime application are
  derived only from the enabled capture plan and therefore contain/start Steps alone.
- The focused Automatic Steps scenario requires exact trigger preservation, accepted sources
  `{Steps}`, active purpose-qualified demand membership, one Steps runtime start, no Location
  runtime start, no captured Activity action, and no Location, Pressure, Wi-Fi, or Cell demand.
- This reuses the existing broker and coordinator; it adds no second provider owner or source-local
  orchestration path. It closes AUTO-004's authored logic boundary, not physical Activity/Steps
  registration, callback, listener, device, FGS, or query proof. Control minimization, retention,
  deletion, and export nonleakage remain AUTO-005.

## TI-D183 — Automatic service acceptance requires the exact immutable trigger envelope

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; focused contract `b37862fde`; TI-B245.
- The existing Activity gateway already checks provider observed/received clocks, callback expiry,
  boot identity, automation epoch and effective boundary, current policy/control consent,
  registration generation and lifetime, authorization identity, collected-data epoch, and one
  durable action slot before a service start can be accepted.
- The focused Room contract now mutates each service-carried trigger field independently and
  requires exact action-envelope rejection. A stale collected-data epoch and a missing action keep
  their distinct terminal reasons, while the original untouched trigger remains valid.
- Durable outbox settlement remains a compare-and-set operation: delivered/terminal effects are
  not selected again, cold replay cannot regain callback permission, and a reused singleton action
  slot cannot be mutated by an older trigger. This closes AUTO-006's authored logic boundary.
- No new provider, start path, lifecycle authority, or generic action framework is introduced.
  AUTO-005 remains open solely for the unresolved fixed control-evidence lifetime, and Android,
  process/reboot, FGS, provider, and end-to-end query evidence remains deferred.

## TI-D184 — Automatic sessions never receive restart authority after process interruption

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; focused contract `0bb36227b`; TI-B246.
- Existing automatic mode reconciliation stops an automatic session when the mode is disabled,
  preserves a manual session, grants incompatible Activity a bounded stop grace, and cancels that
  grace for compatible or user-initiated sessions. Provider removal remains bounded and retriable.
- Previous-exit recovery preserves only an ACTIVE manual session whose exact logical entry, current
  run, same-boot restart token, and Room authority still agree. Old-boot, stopping, mismatched,
  automatic, and otherwise stale sessions have their demands retired and incomplete runs/actions
  terminalized; force-stop uses the same no-recovery direction.
- The focused Room contract now gives an automatic session the strongest misleading descriptor—a
  matching same-boot logical/run identity and restart token—and requires final session/run/action
  settlement, demand retirement, and automation-epoch rotation. Existing coordinator fences keep a
  finalized logical identity from being recreated, including by an accepted automatic action.
- This closes AUTO-007's authored logic boundary without creating an automatic restart path or
  broadening manual fallback. Process death, reboot, Android service delivery, provider teardown,
  and device behavior remain deferred validation, not current evidence.

## TI-D185 — Automatic Steps end-to-end authorship is a linked production-seam cohort

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; focused contracts `a097575b3`; TI-B247.
- The coordinator's exact generation-2 automatic scenario now uses real `RoomDurableSourceIngress`
  after trigger validation and foreground acceptance. Its owned Steps runtime admits a baseline and
  fresh positive post-baseline delta under the exact physical registration and observed-time
  authorization; the canonical Steps lane materializes both and binds the positive fact to the
  exact logical entry and physical service run.
- The application-level production-facade contract uses the same immutable generation-2 automatic
  attribution and requires `TrackingHistoryRepository` to report captured and qualified Steps only,
  with Activity visible solely in the separate control-source field. Portable export emits only
  Steps `SESSION_CAPTURE`; import into a fresh database remains explicitly unable to fabricate the
  original capture set.
- This is intentionally a linked cohort at existing module owners, not a new cross-module
  materializer harness or runtime architecture. The real-ingress writer contract and production
  query/export contract share the exact stored schema and generation semantics while remaining
  independently useful.
- This closes AUTO-008's authored boundary only. No contract in the cohort has been executed on the
  current branch, and no physical sensor, listener removal, Android FGS, process/reboot, UI-device,
  battery, OEM, integration, rollout, or release evidence follows.

## TI-D186 — Ambient Steps starts opportunistically behind independent default-off consent

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `a8e876909`; TI-B248.
- The first Ambient Steps product promises only opportunistic continuity. Provider gaps, process
  absence, reboot discontinuity, unavailable capability, and revoked permission remain explicit
  incomplete coverage; Tracker does not present them as a complete zero-step interval.
- Tracker will not retain the direct Step Counter or add an always-on hidden service solely to make
  ambient history look continuous. A future full-day guarantee would be a distinct, explicitly
  enabled visible-foreground product with its own battery and lifecycle evidence.
- Ambient consent is a separate default-off persisted preference. It maps only to persistent Steps
  `AMBIENT_PRODUCT` policy, has its own monotonic consent epochs, and can exist while session Steps
  is disabled. Revocation retires and denies only ambient demand at its exact monotonic boundary;
  session capture policy and epoch are unchanged.
- The preference alone authorizes no provider side effect and creates no demand. Capability,
  permission, one selected continuity adapter, retention/explanation UI, and provider evidence are
  subsequent AMBIENT-002/003 work, not implied by this checkpoint.

## TI-D187 — Ambient Steps selects one system continuity provider without a direct-sensor fallback

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `75f389f4f` and `754835f7c`;
  TI-B249.
- Health Connect mobile Steps has precedence whenever its platform, extension, SDK, and feature
  capability are available. Missing `READ_STEPS` is a typed user action and cannot silently switch
  Tracker to Local Recording. Optional background-read permission changes import opportunity only;
  it does not change provider identity or promise a cadence.
- Local Recording is selected only when Health Connect mobile Steps is genuinely unavailable and
  its exact Play services and Activity Recognition requirements can be represented. A Health
  Connect probe failure fails closed instead of authorizing a different provider from an uncertain
  snapshot.
- The Android resolver reads capability and grants but performs no registration and creates no
  demand. The generic Steps QoS-to-floor path rejects `AMBIENT_PRODUCT`; direct Step Counter is not
  an ambient provider under a renamed acquisition tier.
- This decides provider precedence and capability semantics only. Durable provider acceptance,
  import cursors, overlap ownership, product composition, UI, and device evidence remain open.

## TI-D188 — Incompatible Steps providers own exact purpose-scoped authorization

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `aa6995b98` and correction
  `1023ab6b5`; TI-B250/TI-B251.
- The live direct Step Counter now reserves and refreshes only an exact `SESSION_CAPTURE` owner
  scope. A future system continuity adapter will use a separate `AMBIENT_PRODUCT` scope, durable
  pointer, sequence space, and observed-time authorization vector.
- Source authorization rotation and reserved-provider acceptance filter demands through the
  physical registration's durable owner scope. Revoking ambient authority therefore cannot revoke
  a valid session listener, and adding ambient authority cannot retain or authorize that listener.
- Existing shared broker owners and unrelated legacy provider owners retain their established
  all-purpose behavior. A malformed or wrong-source broker scope fails closed. No Room schema or
  generic provider platform was added.
- Sensor checkpoint and durable-ingress sequence state follows the exact authenticated physical
  registration owner. No path may reconstruct a shared owner from source kind after physical
  authorization has selected a purpose-scoped registration.
- This is structural isolation, not ambient registration. The system-rearmable provider lifecycle,
  demand reconciler, external subscription acceptance, record import, and exact overlap partition
  remain subsequent AMBIENT-002 through AMBIENT-006 work.

## TI-D189 — Ambient Steps demand names the selected provider without inventing cadence

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `7b33b2271`, `c16a65e8d`, and
  `039153f67`; TI-B252/TI-B253.
- Health Connect mobile Steps and Local Recording have distinct canonical acquisition floors.
  Both promise only opportunistic coverage and declare provider-native cursor authority for record
  freshness; neither advertises a requested delivery latency or an adaptive/direct-counter tier.
- A ready capability becomes one sessionless persistent `AMBIENT_PRODUCT` demand only when the
  current Room policy has exact ambient consent and the Steps product lane admits `AMBIENT`.
  Session Steps may remain disabled. Permission-required, unavailable, revoked, and contained
  outcomes retire the app ambient consumer and remain typed.
- Unchanged reconciliation reuses the existing demand. A provider change retires the prior demand
  before inserting the replacement, and provider identity participates in demand identity. The
  exact direct Step Counter cannot satisfy either ambient floor.
- This is durable demand authority only. No production caller, system-rearmable provider
  registration, Local Recording subscription, Health Connect read, import cursor, fact composition,
  permission UI, or retention UI is claimed by this decision.

## TI-D190 — Ineligible Ambient Steps state is resolved before capability probing

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `187800e03`; TI-B254.
- Ambient reconciliation reads authoritative source policy and rollout before asking Android or a
  provider which continuity mechanism is available. Default-off or revoked policy returns typed
  `REQUEST_DISABLED`, and inactive authority or contained rollout also remains typed without a
  capability probe.
- Any stale app-owned ambient demand is retired at that boundary. The broker still performs its
  existing transactional policy, consent, rollout, and generation revalidation before accepting an
  eligible demand; preflight is side-effect minimization, not a replacement for broker authority.
- This prevents a disabled local-first product from touching Health Connect or Play services merely
  to rediscover that it is disabled. It does not add a provider, importer, cadence, or UI claim.

## TI-D191 — Ambient Steps windows require explicit stored-zone structural authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests `33874e0fe`; TI-B255.
- Provider read intervals are split at exact second-aligned local-day boundaries only under an
  explicit `ZoneId`. Each planned window carries epoch day, zone, day start/end, and exact covered
  start/end; 23-hour and 25-hour days are structural truth rather than normalized durations.
- One pass is bounded to ten windows by default and never more than 31. An explicit deferred
  boundary identifies remaining work rather than hiding it in an unbounded provider read.
- A zone observed after process absence or reboot is not proof of the unobserved interval's zone.
  Cursor/gap authority must preserve that uncertainty before this planner can drive production
  import.

## TI-D192 — Ambient provider aggregates use a distinct sessionless revision store

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-12; source/tests/schema `88c14a52e`; TI-B256.
- Ambient provider aggregates are not session `StepFactRevisionEntity` rows. Their v28 table owns
  opaque logical/mutation identity, exact writer and owner generation, provider class, registration
  and authorization identity, structural day/zone/bounds, read window, `Long` count, ambient
  policy/consent/collected-data epochs, deletion generation, and an effect checksum.
- Covered zero is valid only for an exact covered provider window. Local delete uses a redacted
  revision shape, full collected-data deletion clears ambient facts, and a separate
  `AMBIENT_STEPS` destination-owner fence prevents this path from borrowing the session writer.
- Provider account/origin identifiers are deliberately not persisted. No session, elapsed clock,
  route, sample count, or source qualification is fabricated.
- This accepts the source-specific storage boundary, not the final importer identity. Before first
  production write, the logical fact identifier must remain stable across an extending read-through
  window so corrections revise one segment rather than create overlapping sum-able facts.

## TI-D193 — Ambient continuity rotates authority inside one physical registration

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/tests/schema `e965fb015`, `766d61fdb`,
  `fab70f295`, `5dfbc148b`, and `1b274bf2c`; TI-B257.
- One cursor is bound to an exact continuity authority: provider, opaque source instance,
  registration generation, authorization fingerprint/revision, policy/consent/collected-data
  epochs, boot identity, zone, structural day, and privacy floor. Physical registration reuse does
  not permit attribution reuse.
- A legal authorization change within one physical registration closes the old segment and opens a
  new continuity generation at the same rounded effective boundary without inventing a gap. The
  immutable transition is self-verifying over both complete authority tuples and its boundary.
- Cursor advance is compare-and-set and monotonic in high-water, observation, update time, and
  continuity generation. Same high-water requires a newer observation; total no-op, stale writer,
  regression, wrong authority, or wrong transition fails without mutation.
- Provider aggregate logical identity is stable across an extending read-through end and includes
  the opaque source-instance namespace. UPSERT logical IDs and every mutation ID are recomputed by
  entity validation so a caller cannot merge or duplicate facts with signed arbitrary IDs.

## TI-D194 — Ambient gaps are immutable declarations with correction-safe effective intervals

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/tests/schema `09de7819e`, `a47807103`,
  and `4461a95b4`; TI-B257.
- Process, reboot, provider, zone, retention, or no-evidence discontinuities remain immutable exact
  declarations. Effective gaps are the maximal remaining intervals after subtracting
  latest-effective exact-origin provider fact windows; no generic tombstone/effect platform is
  introduced.
- A later fact UPSERT may partially or fully cover a declared gap; its exact RETRACT restores the
  corresponding effective interval. Segment transition succeeds only across one maximal effective
  interval and rejects nonmaximal, zero-length, fully covered, wrong-origin, or stale authority.
- This is cursor/gap storage and query authority only. The importer must still bind its fact and
  cursor mutations in one transaction, and product composition must preserve partial coverage.

## TI-D195 — Captured Activity uses exact-window facts and transition-first compatible refinement

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `950b2b603`, `e1fb12403`,
  `02a173ffc`, `50d053691`, `d000b569b`, and `7228cd6e9`; TI-B258.
- Activity capture admission is independent from CONTROL and carries exact logical/run/source,
  physical registration, authorization, policy/consent/manifest/lease/deletion/boot, historical
  acquisition configuration, provider/auth/session half-open temporal authority, and provider/wall
  clocks. Control-only input is typed-rejected.
- Terminal movement bands use a stable window/revision/supersession identity, wall-time anchors and
  uncertainty. Dynamic fragments do not become durable logical identity, so late EXIT or refined
  evidence can revise one window rather than strand an overlapping fact.
- Transition evidence is primary. High-confidence directly requested samples may refine coarse
  `ON_FOOT` or `UNKNOWN` state without contradicting definitive transitions. EXIT barriers clip
  compatible older or equal-order samples; equal-time order follows source sequence, and
  UNKNOWN EXIT negates only UNKNOWN.
- Bounded sorted sweeps produce typed active/inactive/unknown/unobserved coverage and explicit gaps.
  This decision adds no schema, writer, provider, history, UI, or captured ambient Activity product.

## TI-D196 — Ambient provider reads commit one fact and cursor under revalidated authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/tests `39cff3c5a`, `1ca770a5f`, and
  `29801e17f`; TI-B259.
- Provider reads occur outside Room. The final Room transaction re-reads the accepted provider
  registration, current authorization, policy, consent, direct ambient demand, lifecycle, source
  owner, cursor, and deletion authority before it inserts at most one fact revision and advances the
  cursor by compare-and-set.
- The importer cannot read before its rounded privacy floor or assign a newly observed zone to an
  earlier interval. Zone, retention, and undrained-authorization discontinuities are explicit gaps;
  a fully completed structural day with no provider evidence becomes a typed no-evidence gap so it
  cannot block all later days or become fabricated covered zero.
- Progressive reads revise one stable fact identity. Exact same-high-water and same-observation
  replay is a no-op; a newer observation may advance only observation authority. A completed-day
  no-evidence gap is terminal to this monotonic path, so later backfill requires a separately bounded
  repair contract rather than implicit cursor reversal.

## TI-D197 — Pressure history is discovered from qualified facts and complete replacement groups

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `8768767d4`, `097277c74`,
  `7d3392a7d`, `30b99549`, and `8b076e6f7`; TI-B260.
- Pressure-only history discovery joins Pressure facts to reciprocal service-run and segment binding;
  neither `sample_count` nor Location is source proof. Every selected logical entry expands the full
  explicit replacement-run set before composition, and malformed membership, manifest union,
  reverse binding, ownership, or correction lineage fails the whole logical group closed.
- Fact paging and logical membership are bounded with typed overflow and cancellation checkpoints.
  Immutable correction attribution includes manifest, policy, consent, clock, and stored-zone
  authority. A provider-unavailable sentinel cannot hide retained or escaped facts.
- The read model retains real count, range, mean, variance, trend fit, accuracy, cadence, latency,
  expected and actual sample coverage, maximum gap, closure, flags, confidence, and stored zone. This
  decision does not yet expose the model through shared product history or UI.

## TI-D198 — Pressure acquisition exposes only real continuous or batched provider behavior

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `3b8abe350`; TI-B261.
- Pressure no longer models a movement-gated burst that no provider implemented. The retained v1
  serialized byte must be `false`; legacy `true` is a typed unsupported mode rather than silently
  acquiring continuously under a false battery promise.
- Low, standard, and responsive plans retain distinct 1, 5, and 20 Hz sampling, delivery latency,
  FIFO/batching, and aggregation windows that reach the existing SensorManager request and
  accumulator. Thermal fallback is an explicit continuous batched plan bounded by direct demand.
- Activity or stationary state cannot change, wake, start, or retain Pressure acquisition. This does
  not activate a provider, change rollout defaults, or claim device battery evidence.

## TI-D199 — Protected Location qualification binds immutable delivery and clock evidence

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `0025d8f26` and
  `762186a24`; TI-B262.
- The dormant Location qualifier requires exact capture-only source membership and binds its command
  identity to the immutable event, admission, WAL integrity, source-native delivery unit, captured
  authorization, provider clock, wall-time anchor and uncertainty, payload, quality, and mock
  provenance. Current deletion authority is separate and cannot re-stamp an old delivery.
- Raw provider evidence is immutable under one delivery identity. A later derived qualifier revision
  may change only derived semantics under the same complete durable evidence; changed coordinates,
  provider clocks, quality, or mock state are collisions rather than corrections.
- Retention uses the complete overflow-safe wall-time uncertainty interval, and optional altitude,
  vertical accuracy, speed, bearing, provider, coordinate, and accuracy fields fail closed when
  malformed. Location-only capture is valid without `sample_count` or another source.
- This decision does not wire the qualifier into the protected canonical writer, add a second writer,
  change provider registration, or claim history/UI behavior. A later adapter must load and verify
  the actual WAL row matching the supplied integrity identity.

## TI-D200 — Captured Activity persistence authenticates source semantics and historical plans

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `c2e1ee707` through
  `a8c1752cb`; TI-B263.
- Activity-specific v28 window-revision, fragment, evidence, cursor, and immutable
  registration-plan rows preserve exact run, segment, manifest, policy, consent, provider,
  acquisition, clock, zone, deletion, and source-owner authority without using control-only input.
- Every referenced WAL payload is canonical-decoded and matched to the captured transition or sample
  semantics before an append-only fact revision commits. Historical freshness, confidence, and
  coverage derive only from the exact serialized Activity plan and immutable registration-plan
  binding; a later current applied-plan pointer cannot invalidate a legitimate delayed correction.
- Retention uses the earliest possible wall time across both bounds and their uncertainty for the
  current fragment and the full prior lineage. Exact replay, semantic no-op, cursor CAS, transaction
  rollback, and control rejection remain source-local. The generated v28 Room schema JSON is still
  stale and must converge during the deferred generation and validation phase.

## TI-D201 — Ambient provider handoff drains once and partitions the cutover exactly

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/tests `4624859d1` and `016a434df`;
  TI-B264.
- An already accepted successor may coordinate exactly one bounded predecessor read under the
  predecessor's historical pre-cutover demand, authorization, policy, and consent. The read ends no
  later than the retirement boundary and cannot retain or start a provider for enrichment.
- One transaction commits at most one predecessor fact revision, records only the remaining
  undrained interval as a typed gap, retires the predecessor cursor, initializes the successor at the
  rounded nonoverlapping privacy floor, and records the exact provider-change marker. No positive
  interval can be both retained fact coverage and an effective handoff gap.
- Completed replay performs no provider read only after it authenticates both cursor authorities,
  the complete gap and authorization-transition sibling sequences, and the exact marker linking the
  predecessor and successor. Corrupt or dangling replay state fails closed.

## TI-D202 — Pressure product reads expose source truth without fabricated physical claims

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `264cc4fe9` and
  `97941e8b1`; TI-B265.
- The source-specific public read facade discovers recent Pressure-only logical entries from retained
  qualified facts, expands complete replacement membership, and preserves physical ownership
  internally. A batched logical-recency cursor includes newer factless replacement members and fills
  past rejected candidates before applying the caller limit.
- Public state separates availability, evidence, product state, coverage, and causes and exposes only
  retained pressure count, range, mean, slope, fit, cadence, latency, maximum gap, and stored-zone
  evidence. Missing or unqualified windows remain null or typed; they never become zero, altitude,
  elevation, ascent, or another Location-derived claim.
- This decision adds a source-specific API and repository path only. Shared Dashboard, History,
  Calendar, detail rendering, provider activation, and automatic control remain open.

## TI-D203 — Pressure demand is session-capture-only at construction and restoration

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/tests `5db56ebcc`, `b21135cf5`, and
  `a410dcb86`; TI-B266.
- Pressure demand construction accepts only exact `SESSION_CAPTURE`. Registration reconciliation
  independently rejects empty or non-session durable Pressure vectors before reservation, provider
  acceptance, active authorization refresh, or authorization-row insertion, so restored, legacy, or
  corrupt control or ambient rows cannot retain the sensor.
- RECORDING evidence remains a qualified durable WAL row, not a request, registration, baseline,
  timer, or metadata event. MATERIALIZED source evidence advances only after the existing canonical
  Pressure writer atomically commits the fact and evidence revision before its cursor.
- The existing bounded SensorManager window actor, callback fencing, retirement, rollback, and
  deletion machinery is reused. No catalog, default, provider activation, or shared product change
  is made.

## TI-D204 — Cell qualification begins from one authenticated retained WAL delivery

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `114137e58`, `eeae6d24f`,
  `891d5e2f2`, and `2102d3212`; TI-B267.
- The dormant Cell qualifier accepts only a real retained one-unit Cell WAL row with positive unique
  source sequence, canonical payload bytes, recomputed WAL, payload, and delivery identities, and
  exact captured-registration attribution. It derives the next boundary from the same registration's
  authorization timeline rather than adjacency in a global revision sequence.
- Qualification revalidates the immutable desired plan and fingerprint, registration and
  authorization members, policy and consent, run and complete manifest timeline, reciprocal
  run-to-segment binding, provider clocks and wall uncertainty, stored structural zone, collected
  epoch, retention floor, and current global deletion authority in one Room snapshot.
- Cell products remain privacy-minimized and identity-free. Retained v1 evidence cannot prove
  subscription grouping, so completeness stays `UNKNOWN`; confirmed empty also remains
  unverifiable. No Cell fact table, source-local deletion generation, writer, provider activation,
  product history, or UI was added, and scope deletion generation must be exact before persistence.

## TI-D205 — Pressure retention removes complete uncertain lineages under bounded authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `f36b80cb5` and
  `85ac20157`; TI-B268.
- Retention candidate discovery never proves ownership from wall time. One source-specific Room
  transaction revalidates the current exact retention floor and epoch, reciprocal run/segment
  binding, complete manifest timeline/checksum and stored zone, exact Pressure capture policy,
  consent and writer ownership, correction chains, and absence of cross-scope or deleted-scope facts.
- When any revision's earliest possible wall bound crosses the floor and the aggregate cannot be
  split truthfully, every revision of that logical lineage is removed and a self-verifying,
  payload-free run marker is written atomically. The Pressure writer rejects later resurrection
  across the same floor; `QUIESCED` and wall-time overlap are never ownership predicates.
- Fixed query pages are reinforced by explicit total and per-run run/row/revision/lineage budgets.
  Overflow, malformed authority, or cancellation rolls back all markers and deletions. A worker does
  not yet invoke this path and product reads do not yet consume its marker.

## TI-D206 — Wi-Fi facts authenticate the exact identity-free provider snapshot

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `e5082b670` through
  `96e930d80`; TI-B269.
- Pure Wi-Fi qualification accepts only captured-registration evidence. A passive callback may omit
  the redundant runtime configuration hint, but the exact serialized desired plan and applied
  registration remain mandatory; a nonnull mismatch fails closed.
- Input bytes are capped before decoding, v2 payloads must canonical re-encode with no trailing data,
  and only the production one-unit callback shape qualifies. Nonempty observations must be sorted,
  privacy-minimized, timestamped per child, carry the latest provider millisecond in the platform
  timestamp, and omit result age.
- The stored source-delivery identity is recomputed with the production Wi-Fi provider canonicalizer
  before any fact identity is derived. Aggregate count and owner semantics remain construction-safe;
  no BSSID, SSID, or stable radio identity is retained. Durable WAL adaptation, persistence,
  provider ownership, active attempts, product reads, and UI remain open.

## TI-D207 — Protected Location shadow input fails typed when v1 provenance is absent

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `077e5b4db`, `e11dcb3ba`,
  `249342867`, and `8a90104ce`; TI-B270.
- The dormant adapter selects one actual retained Location WAL delivery by positive bounded source
  sequence and exact declared sibling cardinality, canonical-decodes its unit, recomputes delivery
  identity, and verifies immutable plan, registration, same-registration authorization, manifest,
  reciprocal run/segment, stored zone, epoch, deletion, and full retention-uncertainty authority.
- Independent Room reads compare every persisted scalar and integrity field plus payload content;
  Kotlin array reference equality is never used as evidence. Sparse global authorization revisions
  do not shorten or extend the same registration's effective interval.
- Canonical v1 Location WAL did not retain mock provenance. The adapter therefore returns typed
  `MOCK_PROVENANCE_UNVERIFIABLE` and cannot emit a qualified command, rather than fabricating
  `isMock=false`. The existing canonical writer remains the only writer; no runtime, schema, product,
  comparison, cutover, or UI change is made.

## TI-D208 — Pressure retention loss remains discoverable without qualifying a missing fact

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source convergence `69b4d62b2`, `cbfdb602d`, and
  `ed4089323` atop TI-D197/TI-D202/TI-D205; TI-B271.
- Both existing retention workers invoke the bounded authenticated Pressure mark-and-prune
  transaction before physical Pressure sample or segment deletion. Audit failure is retryable and
  cannot be recorded as a successful retention transaction or proceed to the physical delete.
- Recent discovery may use exact Pressure `SESSION_CAPTURE` manifest membership only as a bounded
  coarse candidate. A logical group becomes ordinarily discoverable solely from retained qualified
  Pressure facts or a recomputed current-epoch source-specific retention-loss marker after complete
  replacement membership is authenticated.
- A marker-only group is `PARTIAL` with retention loss and has no qualified Pressure source, window,
  summary, or numeric value. Stale or corrupt markers fail closed; retained siblings remain visible.
  The fixed coarse candidate budget may return a short page under extreme manifest-only noise, but
  it cannot publish an unqualified group.

## TI-D209 — Activity product reads bind complete lifecycle and materialization truth

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/API/tests `eb4f426b6` through
  `d0bf3bf59`; TI-B272.
- Activity-only discovery is fact-backed, bounded, page-aware, and expands complete logical
  replacement membership inside one Room transaction. It authenticates immutable desired-plan and
  registration-plan fingerprints, policy/consent, lane execution/terminal settlement, cursor,
  deletion/retention, manifest-window coverage, reciprocal segment binding, and stored zone.
- The current logical pointer must name the sole nonterminal replacement run and bind its current
  manifest, lease, boot, and exact closed lifecycle vocabulary. Completion fields and session/run
  phases must form a canonical pair; STOPPING carries its paired immutable cutoff and unknown,
  idle, crossed, or multiply active shapes fail closed.
- A newly effective capture manifest that has not produced its first fact contributes no invented
  unbounded duration. Earlier qualified facts remain visible and the group stays typed partial and
  materialization-behind. Physical run and event identities remain internal; no control-only input,
  `sample_count`, or Location inference is used.

## TI-D210 — Wi-Fi WAL qualification replays exact applied capture authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `8c2587909` and
  `d7c5e5d4b` atop TI-D206; TI-B273.
- One dormant Room adapter selects exactly one retained positive-sequence Wi-Fi delivery and
  authenticates its bounded one-unit WAL shape, canonical identity-free payload, recomputed delivery
  identity, complete manifest/run/segment binding, clock/zone, retention, deletion, and exact
  captured-registration authority.
- Historical plan evidence comes from the retained accepted source action plus immutable desired
  plan bytes and checksum. Every bounded authorization member and demand contract is recomputed;
  cache-only, malformed, excessive-age, non-broadcast-floor, or fabricated delivery-deadline plans
  fail closed rather than borrowing current intent.
- Active and terminal lifecycle pairs are authenticated separately. A terminal session must have no
  current run, and its final admission ordinal must cover the selected WAL unit. All plan, manifest,
  authorization-member, WAL-unit, and sibling reads have explicit limits.
- This decision adds no fact table, writer/cursor, callback owner, active attempt, product read,
  deletion/retention mutation, UI, or activation. Those remain separate Wi-Fi-local work.

## TI-D211 — Ambient Steps days compose only through exact stored authority phases

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `5ac0e573b` through
  `55024d9f6` atop the accepted Ambient importer and handoff; TI-B274.
- One source-specific Room snapshot reads bounded structural-day candidates, facts, gaps, cursor and
  authorization history, policy/consent, local session Steps, and portable-import evidence. It
  composes authoritative day total, contained-session total, and between-session value without
  fabricating a session or adding overlapping representations twice.
- Every fact and gap must fit wholly inside one authenticated same-registration authorization phase.
  Direct successors must preserve both elapsed and wall-clock order before their clamped privacy
  boundary is accepted; a rounded or clamped wall regression fails typed rather than silently moving
  evidence across an authority transition.
- Exact, partial, and unavailable values remain distinct. Gaps, unsupported or denied runtime,
  historical authority failure, materialization lag, overflow, imported/local incompatibility, and
  structural-zone identity remain explicit; missing evidence never becomes numeric zero.
- This is a bounded source-specific day read, not shared Today/Timeline/Calendar/detail wiring.
  Retention, deletion, consent reset, portable transfer, end-user settings/remediation, and
  activation remain open.

## TI-D212 — Pressure portable transfer starts with a bounded privacy-safe export

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated API/source/tests `63b9667b3` atop the
  accepted Pressure selector and retention-marker path; TI-B275.
- A source-specific export API and self-checksummed v1 format expose only opaque kind-scoped logical,
  run, and window identities plus qualified Pressure statistics, timing/coverage, uncertainty,
  accuracy, cadence, latency, maximum gap, and stored window zone. They contain no coordinates,
  elevation/ascent, control evidence, provider identifiers, or local database IDs.
- One Room transaction pages bounded range candidates, expands complete replacement membership, and
  reuses the accepted manifest/policy/consent/writer/correction/deletion/retention authority before
  producing an in-memory snapshot. Sink I/O starts only after the transaction; corrupt,
  materializing, or configured-overflow input emits nothing.
- Marker-only and retained-plus-marker entries remain explicitly partial. A marker never fabricates
  a numeric window or qualified retained observation.
- Pressure import is not added. It remains blocked on an authoritative source-local import writer
  and exact portable provenance, deletion, collision, retention, and no-resurrection mapping; export
  cannot be used as permission to bypass those controls.

## TI-D213 — Cell persistence retains bounded immutable aggregate ownership

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/schema/tests `31bf7c49d` through
  `0dae1d8f5` atop TI-D204; TI-B276.
- Qualified identity-free Cell deliveries enter one dormant source-local writer with exact delivery,
  owner, manifest/run/segment, policy/consent, retention, global and Cell deletion-epoch, correction,
  and cursor-CAS authority. Writes remain transactional and cancellation or corruption fails closed.
- Changed or corrected content is self-contained. A compact coverage-only fact may reference prior
  aggregate content only when the owner had finite immutable temporal authority at creation. The
  referenced exact historical revision remains valid only inside one bounded complete aggregate
  lineage whose authenticated current tip matches the cursor.
- Later legitimate owner settlement or correction therefore cannot strand a dependent at an
  obsolete current revision. Replay may materialize a new self-contained aggregate when historical
  reuse is no longer safe; finite exact replay and retained-prior fallback remain bounded.
- Generated v28 schema JSON, retention-worker handling of referenced historical revisions, product
  history/UI, portable transfer, provider/runtime activation, and device behavior remain open.

## TI-D214 — Activity WAL admission is byte-bounded and lifecycle-settled

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `8b10e2281`, `8138d7a45`,
  and `689a6e994` atop TI-D199/TI-D209; TI-B277.
- The dormant adapter preflights selected and sibling payload lengths/cardinality in SQL before Room
  loads any BLOB, then retains the canonical 21-byte Activity unit cap during full reads. Oversized,
  incomplete, noncanonical, or identity-mismatched deliveries fail typed without allocation-driven
  decode work.
- Admission reauthenticates exact captured owner, immutable registration-plan binding, authorization
  members and demands, policy/consent, manifest/run/segment, provider clocks, deletion/retention,
  stored zone, and writer destination without accepting CONTROL evidence.
- Reachable live session/run pairs use the exact durable-ingress vocabulary, including
  RECONFIGURING and STOPPING. A finite window remains unavailable until both session and physical
  run are terminal, the current pointer is null, and cutoff/final-admission ordinal cover the
  selected delivery; legitimate older replacement runs remain admissible after settlement.
- At this boundary runtime plan-binding insertion and exact delivery attribution were separate work;
  TI-D215 accepts their implementation. A bounded terminal projection trigger, destination
  activation, product UI, maintenance, and transfer remain open.

## TI-D215 — Activity registrations persist the exact applied capture plan

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/schema/tests `4cd3246cf` atop
  TI-D214; TI-B278.
- The Activity runtime supplies canonical serialized capture-plan bytes. Provider acceptance stores a
  defensive-copy payload, checksum, true plan revision, and physical fingerprint under the exact
  ACTIVE_SESSION registration in the same Room transaction that advances the accepted pointer.
- The durable desired plan must match exactly. A byte, version, checksum, revision, or fingerprint
  mismatch rolls back database acceptance and removes the already requested provider registration;
  historical replacement bindings remain append-only and attributable.
- CONTROL cannot supply captured-plan authority. A missing session binding leaves callback input
  receive-time-only, while authorized captured delivery stamps the true plan revision and fingerprint
  independently of registration generation. The WAL adapter and writer compare exact stored plan
  bytes as well as derived identity.
- This closes the plan-attribution reachability gap only. A bounded terminal projection trigger,
  destination/writer activation, maintenance, transfer, shared UI, generated v28 schema convergence,
  and provider/device evidence remain open.

## TI-D216 — Pressure-only recent and live UI uses one bounded truthful product snapshot

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated product/API/UI/tests `06ec05883` through
  `8214b92ab` atop the accepted Pressure read and retention-marker path; TI-B279.
- One Pressure-aware recent-page composition merges physical, Steps-only, and exact Pressure-only
  candidates under explicit candidate/member limits, cancellation, and bounded backfill. Pressure
  logical recency is one member-owned `(startTime,id)` tuple, never independent maxima.
- Exact Pressure-only intent replaces the Location-shaped physical fallback before the first fact and
  while unavailable or materializing. Mixed-source and legacy-unverifiable entries retain their
  existing physical presentation; no `sample_count` or Location inference is introduced.
- Dashboard exposes an opaque non-clickable Pressure-only row and one live snapshot assembled from
  session plus Pressure classification inside one Room transaction. It shows only retained direct
  hPa latest/range/change and typed coverage/state, never route, distance, speed, elevation, ascent,
  coordinates, or fabricated zero.
- `Recording` is evidence-driven: ready or partial/materializing state with retained qualified
  metrics may use it; materializing without metrics, unavailable, and failed states use their typed
  non-recording status. Calendar, selected shared detail, full Today/Timeline integration,
  localization/device review, and automatic control remain open.

## TI-D217 — Protected Location v2 retains mock provenance without adding a writer

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `1013c72dc` and
  `6e9d84f38` atop the accepted retained-WAL adapter; TI-B280.
- Frozen pre-v2 Location payload bytes and raw-observation repair retain their established
  compatibility behavior. Because they never recorded authoritative mock provenance, the new
  qualified path continues to return typed `MOCK_PROVENANCE_UNVERIFIABLE` rather than inventing it.
- Canonical v2 payloads require the platform mock bit, include it in normalized delivery ordering
  and identity, and preserve both true and false through WAL decode, qualification, and
  raw-observation crash repair. Missing, trailing, noncanonical, or corrupt v2 provenance fails
  closed.
- The adapter still returns a dormant command only. This adds no schema, catalog activation,
  rollout stage, product history, cutover, fact write, or second canonical Location writer.

## TI-D218 — Pressure-only selected detail fails closed without Location-shaped fallback

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated product/UI/tests `00d831728` and
  `c27cc1c18` atop TI-D216; TI-B281.
- Only an exact all-revision `{Pressure}` capture set selects the Pressure presentation. Mixed,
  Location, and legacy-unverifiable entries preserve their existing standard detail behavior.
- Pressure detail reads retained direct hPa latest/range/change and coverage from one transactional
  live-history snapshot. Partial, materializing, unavailable, failed, and absent values remain
  typed; missing values never become numeric zero.
- Resolving, Pressure-only, and failed states suppress map, route, navigation, GPX—including the
  programmatic export path—Location/Ski enrichment, distance, speed, elevation, and sample-shaped
  content. Observer failure atomically clears stale source classification and shows retryable
  unavailable history instead of spinning forever.
- This is contained selected-detail presentation only. Calendar, full Today/Timeline composition,
  maintenance/import, localization/device/accessibility evidence, and activation remain open.

## TI-D219 — Cell history discovery starts from exact durable cursor ownership

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated product/API/source/tests `1edd65264`
  through `79056b454` atop TI-D213; TI-B282.
- Source-only Cell entries are discovered from exact writer/version cursor carriers joined to
  reciprocal run/segment ownership, never from `sample_count`, Location, or self-declared fact
  scope. Missing or corrupt current revisions therefore remain visible as typed integrity failure.
- One Room transaction expands complete bounded replacement membership, cursor-carried correction
  lineages, and finite direct aggregate owners. It authenticates manifest, plan, provider,
  lifecycle, policy/consent, lane, deletion/high-water, uncertainty-safe retention, and stored-zone
  authority before composition.
- A compact coverage fact may expose referenced metrics only when both it and the referenced exact
  owner pass current source epoch, deletion, high-water, cursor, lineage, and retention authority.
  v1 subscription grouping remains typed `UNKNOWN`; no stable radio identity is introduced.
- This adds no provider/callback owner, runtime projection, maintenance, transfer, shared UI,
  automatic/ambient activation, or Location enrichment.

## TI-D220 — Activity maintenance deletes capture facts only behind exact run fences

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `adcf47289` and
  `d3b889991` atop the dormant Activity projection branch; TI-B283.
- Retention authenticates each complete captured-Activity correction lineage and removes whole
  uncertainty-crossing windows only behind the current source-evidence retention floor. Partial,
  orphaned, malformed, foreign, or configured-overflow state fails closed before mutation.
- Capture-source deletion requires current revoked `SESSION_CAPTURE` consent, no active/retiring
  capture demand, and no nonterminal compatible Activity registration. Shared registrations are
  treated conservatively; CONTROL is not interpreted as captured product data.
- The transaction reconciles every expected Activity revision, cursor, fragment, evidence, and
  registration-plan row, authenticates live/replacement/terminal effect ends exactly as the writer
  stored them, installs exact retained run deletion fences, and only then clears capture state.
  WAL and CONTROL remain intact; stale projection replay cannot resurrect deleted facts.
- This is source-local maintenance, not a generic tombstone platform. Transfer, shared UI,
  destination/catalog activation, provider/device behavior, and AUTO-005 remain separate.

## TI-D221 — Activity terminal projection preserves each physical window's failure origin

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/tests `96aa051cf` through
  `f269b562a` atop the accepted WAL/plan-attribution boundary; TI-B284.
- One bounded terminal logical-session drain preflights each exact Activity event before loading its
  payload, partitions by physical acquisition window, and invokes the one dormant canonical writer.
  Replacement physical runs never share a coalescing accumulator.
- Each window retains its own first WAL admission ordinal. Coalescer rejection or writer poison is
  attributed to that exact group, so a later invalid replacement cannot poison an earlier valid
  prefix. The 4,096 plus one overflow row remains the exact terminal failure origin.
- Fact/evidence/lane-cursor mutation remains one Room transaction; cancellation and poison roll back.
  CONTROL, deleted, retained-out, or generation-invalid evidence cannot write captured history.
- The production projection catalog remains inert. This does not activate a destination, provider,
  automatic mode, shared UI, transfer, or product rollout.

## TI-D222 — Wi-Fi terminal history requires a complete current replacement bundle

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/schema/tests `3a8fd1968`
  through `26347367c` atop the accepted Wi-Fi classifier/WAL adapter; TI-B285.
- The dormant Wi-Fi writer appends identity-free fact revisions, bounded correction and finite
  aggregate-owner references, and one exact source cursor in a single Room transaction behind the
  Wi-Fi deletion epoch. Replay, cancellation, stale generation, and invalid authority fail closed.
- An older terminal physical run is attributable only when the different current run has a complete
  bounded manifest timeline/checksum/source set, reciprocal run/segment, accepted start action,
  canonical plan, boot/lease/source-instance, current evidence epoch, and exact process-bound
  provider registration.
- Registration reservation, acceptance, action acknowledgement, and retirement must preserve both
  wall and elapsed chronology. ACTIVE and RETIRING shapes are validated separately; a retiring
  provider cannot precede its accepted start acknowledgement.
- No SSID, BSSID, raw identifier, provider activation, active scan, product history, maintenance,
  transfer, UI, automatic/ambient enablement, or Location inference context is added.

## TI-D223 — Pressure maintenance scope distinguishes existing deletion from missing source-wide authority

- Status: **RECONCILED_FROM_REPOSITORY_EVIDENCE**, 2026-09-13; static audit of accepted ancestry and
  current code; TI-B286.
- Pressure selected-session deletion and stored-zone correction-safe repair are already implemented
  and accepted in `61608800e` and `9592c42d8`. Source-specific retention and retained-loss discovery
  are present through `ed4089323`, and privacy-safe portable export exists separately at
  `63b9667b3`.
- The global all-data transaction atomically advances the collected-data epoch/high-water before
  clearing WAL, lifecycle state, and `pressure_fact_revision`, so a second "all-data" Pressure path
  would duplicate existing authority.
- What remains is narrower and materially different: portable Pressure import and, only if the
  product requires it, a separately invocable Pressure-wide erase. The latter cannot borrow selected
  session ownership or `QUIESCED`; it requires explicit source-generation, run-fence enumeration,
  provider/demand quiescence, undrained-WAL handling, and no-resurrection semantics.
- Historical checkpoint text that predates the accepted deletion/read/retention work remains useful
  as history but is not the current implementation boundary. Do not start a duplicate deletion/day
  repair lane from those older snapshots.

## TI-D224 — Activity-only Dashboard truth is accepted source-locally and requires union convergence

- Status: **IMPLEMENTED_UNVALIDATED_WITH_CONVERGENCE_BLOCKER**, 2026-09-13; isolated source/API/UI/
  tests `f364cbc42` and `48d4d3e67`; independent static review; TI-B287.
- Exact all-revision Activity-only intent is resolved before facts and across complete logical
  replacement membership. One Room transaction reads live Activity and session authority, and one
  bounded recent-page merge preserves source-only discovery without `sample_count` or Location.
- Dashboard exposes retained movement bands, active time, coverage, and gaps in non-clickable
  Activity-only live/recent content. Missing, materializing, partial, unavailable, or failed evidence
  remains nullable/typed; route, distance, speed, elevation, coordinates, maps, and fabricated zero
  are absent.
- This is not yet a shared-product integration. The accepted Pressure UI branch independently owns
  the same history API, Dashboard repository/ViewModel/live-state, Compose, strings, and tests.
  Convergence must create one combined Activity-plus-Pressure transactional live snapshot, one
  bounded recent-page composition, and one presentation vocabulary preserving both exact-intent
  paths. Neither branch may overwrite or replace the other.

## TI-D225 — Activity and Pressure UI converge through one demonstrated source-aware product seam

- Status: **ACCEPTED_DESIGN_NOT_IMPLEMENTED**, 2026-09-13; independent overlap and algorithm audit;
  TI-B288.
- Convergence starts from accepted Pressure UI `8214b92ab`, layers accepted Activity dependencies
  through `d0bf3bf59`, then applies `f364cbc42` and `48d4d3e67` without taking either conflict side
  wholesale. The two resulting commits separate shared stats coordination from Dashboard
  presentation.
- `TrackingHistoryRepository` retains one generic live observer. Its one Room transaction resolves
  the segment once and returns the common session plus Activity and Pressure states. The ViewModel
  observes only that snapshot; exact source-only classification must agree with common capture
  authority, and a contradictory dual-only state fails closed.
- One bounded source-aware recent-page transaction authenticates and suppresses the union of complete
  Activity-only and Pressure-only replacement members, obtains bounded top candidates from each
  source, merges them with existing Physical/Steps entries, rejects logical-identity collision, then
  sorts once by member-owned recency and takes the requested limit. Overflow from either source is a
  typed unavailable result; no per-row observer or query fan-out is introduced.
- The presentation vocabulary is closed to `Physical`, `StepsOnly`, `ActivityOnly`, and
  `PressureOnly` plus existing typed unavailable states. Only Physical owns navigation/detail;
  mixed/legacy remain Physical, source-only numeric absence remains null, and no generic source
  payload, materializer language, or new UI platform is added.

## TI-D226 — Wi-Fi history authenticates one capture owner within a compatible shared registration

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated API/data/Room tests `d52e85b7a` through
  `adde063f3`; corrected independent re-review; TI-B289.
- One bounded Room snapshot discovers Wi-Fi-only logical entries from exact cursor, retained WAL, and
  completeness carriers, expands complete replacement membership, and authenticates fact lineage,
  receipt freshness, provider/authorization/session windows, plan, lifecycle, lane/cursor, deletion,
  retention, and stored-zone authority. Missing or corrupt current heads remain typed failures.
- Provider reservation may precede a later compatible manifest on the same physical registration;
  reservation must still precede acceptance. ACTIVE and RETIRING lane shapes require positive rollout
  and valid install/update/terminal chronology; RETIRED requires exact cursor-equals-cutoff.
- A complete authorization revision may contain multiple compatible broker members. Exactly one
  persistence-eligible `SESSION_CAPTURE` demand must match the fact's logical/run/manifest owner;
  every other demand must satisfy the same Wi-Fi broadcast physical contract but receives no fact
  ownership. Incompatible membership fails closed in both retained-WAL and fact paths.
- Policy, consent, demand, fence, and lane reads use SQL limits plus overflow accounting. Public
  observations remain identity-free, and discovery uses neither `sample_count` nor Location.

## TI-D227 — Ambient Steps deletion retains a terminal source-local retraction against replay

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/Room tests `23a26a356`,
  `2f08fdae8`, and `d998dbb4c`; corrected independent review; TI-B290.
- Retention and consent/source deletion audit complete bounded Ambient Steps fact lineages, cursor,
  gap, authorization-transition, and import state. Active or retiring Ambient demand and active,
  reserved, or retiring compatible provider registration block deletion; `QUIESCED` is not used as
  an ownership predicate.
- Source deletion installs a checksum-authenticated payload-free terminal RETRACT before removing
  exact UPSERT payloads and all import authority in one Room transaction. The retraction survives
  retry and authorizes cleanup of an older or corrected UPSERT replayed after deletion; success is
  reported only after no payload/import state remains.
- Retention removes whole uncertainty-crossing and replayed terminal lineages behind the durable
  floor. Malformed, foreign, orphaned, partial, or configured-overflow state and cancellation fail
  closed without partial mutation.
- This is Ambient source-local maintenance, not portable-export acceptance, shared UI, provider
  activation, generic tombstones, device/process proof, or product rollout.

## TI-D228 — Manual Wi-Fi uses the platform scan matrix and paired precise-location repair

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated app/core/tracker/UI tests
  `ca433596b`, `78498d12f`, and `750bd6c42`; corrected independent review; TI-B291.
- Tracker's scan-only path calls `startScan`/`getScanResults`; Nearby Devices is retained as a raw
  capability for unrelated Wi-Fi APIs but is not a prerequisite for this path.
- One shared prerequisite model now drives capability, foreground admission, service preparation,
  connectivity runtime, Wi-Fi runtime, and reporting. API 26–27 accepts coarse or fine Location (or
  the manifest-declared change-Wi-Fi permission) without Location Services; API 28 accepts coarse or
  fine plus enabled services; API 29+ for this target requires fine plus enabled services.
- On Android 12+, Dashboard and Tracker repair precise Location by requesting fine and coarse
  together through the multi-permission contract. A fine grant is required for the API 29+ scan;
  denial remains typed, and the route re-reads exact source readiness before starting. Earlier APIs
  and unrelated single-permission prerequisites retain their existing path.
- All audited manual entry points already preserve the exact requested capture set. This correction
  neither enables Wi-Fi nor registers another source, provider, scan, control, or enrichment demand.

## TI-D229 — Ambient Steps export blocks every cursor or authorization successor that can change a day

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; isolated source/Room tests `4dd91180a`,
  `0f67e93be`, and `8ff5ec428`; corrected independent review; TI-B292.
- One bounded Room snapshot authenticates the selected structural day, stored zone, complete fact
  lineage, effective gaps, authorization transitions, cursor, policy/consent, retention, and source
  deletion state before constructing portable content.
- An authenticated active cursor makes a day materializing whenever its segment begins before the
  day end and its imported-through boundary is below day end, even when backlog has not reached day
  start. The cursor revision must also equal the latest immutable authorization revision of any kind;
  a later deny-all cannot be hidden by purpose-filtered lookup.
- The checksummed format uses opaque kind-scoped identity and released product evidence only. It
  contains no provider, registration, session, control, Location, local database identity, or
  fabricated zero. Terminal retraction plus delayed replay returns no data; cancellation escapes and
  sink I/O occurs only after the read transaction.
- This does not implement portable import, round-trip/no-resurrection mapping, shared UI, provider
  activation, or device/process evidence.

## TI-D230 — Portable Pressure requires distinct imported provenance, never invented live authority

- Status: **REPOSITORY_BOUNDARY_CONFIRMED**, 2026-09-13; clean audit at export HEAD `63b9667b3`;
  no implementation change; TI-B293.
- `PressureFactRevisionEntity` requires a real nonblank source event, positive admission ordinal,
  provider sequence, local service run/manifest/policy/consent, and the Pressure projection owner.
  The only writer derives those values from admitted provider WAL and the production history
  selector reauthenticates them before exposing a fact.
- Portable Pressure v1 intentionally exports only opaque product identity, pressure/quality/
  coverage/uncertainty, structural zone, corrections, and retention-loss truth. It excludes provider
  and local lifecycle/database identity; no decoder, Pressure import authority table/DAO, or
  source-owned import command exists.
- Direct DAO insertion or invented event/run/manifest values would bypass ownership and still fail
  truthful history qualification. The next bounded import architecture is Pressure-specific:
  imported-origin provenance and storage, one authenticated writer/admission transaction, explicit
  deletion/retention/no-resurrection authority, and read/maintenance composition. It is not a
  universal import framework and does not weaken the live-WAL contract.

## TI-D231 — Activity and Pressure converge through one bounded truthful product composition

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/API/UI/tests `49081ecfb`, `873fcae26`,
  `606153694`, and `100d3e9d2`; two corrected independent review rounds; TI-B294.
- The shared history facade performs one transactional live read for session, Activity, and Pressure,
  and one bounded recent composition that expands and suppresses complete source-only replacement
  membership before a single final recency sort.
- Activity capture intent reuses canonical historical authority: known nonpersistent capture rows do
  not change the set, unknown source or purpose fails closed, and common exact intent must agree
  bidirectionally with the source-local flag. Pressure retains equivalent exact-capture agreement.
- Logical Activity recency copies both start and ID from one newest physical member. Any source
  overflow, identity collision, contradictory dual-only claim, or live agreement mismatch becomes
  typed unavailable instead of a standard Location-shaped entry.
- The closed presentation vocabulary is `Physical | StepsOnly | ActivityOnly | PressureOnly`. Only
  physical entries navigate; source-only cards expose retained nullable source evidence without
  Location metrics or fabricated zero. This does not complete shared Today/Timeline/Calendar/detail,
  automatic/ambient presentation, localization/accessibility, device evidence, or validation.

## TI-D232 — Wi-Fi maintenance preserves complete aggregate ownership behind source-local fences

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-13; source/DAO/Room tests `6369e4ed6` and
  `cc2e83938`; corrected independent review; TI-B295.
- One source-local transaction bounds and authenticates the complete Wi-Fi fact-revision, cursor,
  deletion-generation, and retained-WAL scope together with exact payload, plan, provider,
  authorization, policy/consent, run/segment/manifest, clock/zone, and destination-owner authority.
- Retention uses the earliest covered wall bound including uncertainty. If any coverage dependent or
  aggregate owner crosses the floor, ownership expands bidirectionally to the complete fixed-point
  component; dependents are deleted before owners and exact floor equality is retained.
- A coverage-only WAL event is authentic only when its reconstructed identity-free aggregate equals
  every owner metric, not just observation count. Revoked-consent deletion requires compatible
  demand and provider quiescence, installs exact global run fences and the next Wi-Fi deletion
  generation before facts/cursors are removed, and retains WAL, CONTROL, and unrelated sources.
- This does not activate projection/provider paths, invoke maintenance from workers or actions,
  implement transfer, shared UI, automatic/ambient capture, or provide executed evidence.

## TI-D233 — Portable Pressure facts retain imported provenance instead of invented live authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; source/DAO/migration/test commits `04ba749d0`
  and `ddb9ed162`; corrected independent review; TI-B296.
- Portable Pressure uses a source-specific immutable entry-revision → physical-run → window
  hierarchy. It retains every v1 value plus copied receipt provenance and local collected-data and
  deletion-generation authority, but it never invents live WAL, provider, manifest, policy,
  consent, service-run, or projection ownership.
- Reads are capped at 64 runs and 2,048 windows with separate counts so a future writer can prove
  completeness. Exact receipt rollback cascades the selected hierarchy, while a standalone
  self-checksummed run tombstone survives and may advance only within the same epoch by an
  overflow-safe exact `+1` compare-and-set.
- The raw DAO does not grant admission. A later Pressure-specific writer must enforce initial
  generation 1, reject every retained tombstone resurrection, authenticate receipt/idempotence and
  the complete portable hierarchy, and commit atomically before imported facts become readable.
- The v28 Room schema JSON is intentionally deferred but mandatory before convergence integration.
  This slice does not implement import admission, history/maintenance composition, file/UI wiring,
  portable round trip, validation, activation, or rollout.

## TI-D234 — Cell maintenance replays the canonical provider identity before trusting aggregates

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; source/runtime/Room/test commits `86c994a15`
  through `f8d7a5f50`; corrected independent review; TI-B297.
- Maintenance decodes canonical v1 Cell payload once, rejects identity-bearing or null/nonpositive-
  time children, recomputes the exact byte-for-byte provider-delivery identity shared with the live
  runtime, and rederives the complete identity-free aggregate before trusting a fact or cursor.
- Coverage-owner reuse requires the referenced revision to be the current cursor head and to match
  every aggregate and provider/configuration/plan/authorization/policy/manifest/lease/clock/
  temporal authority field. Count equality alone never authorizes retargeting.
- Retention uses the complete covered interval including uncertainty and a bounded bidirectional
  owner/dependent fixed point, deleting dependents before owners. Revoked-consent deletion requires
  compatible demand/provider quiescence and the exact-zero process callback barrier, then installs
  run and Cell-generation fences before removing payload while preserving WAL and CONTROL.
- This does not invoke the service from workers/actions, activate the projection/provider path,
  implement transfer/shared UI/automatic/ambient behavior, or provide executed evidence.

## TI-D235 — Activity export authenticates production delivery shape without exporting CONTROL

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; source/DAO/Room/test commits `c7e087d9c`
  through `8f0842354`; corrected independent review; TI-B298.
- The export snapshot expands every retained Activity sibling by source plus canonical bare 64-hex
  delivery identity before loading payloads. Original unit indices may be sparse and allocated
  source sequences may start at zero or contain gaps; duplicate indices, sequence regression, or
  disagreement in immutable provider-delivery fields fails closed.
- Each retained unit is authenticated at provider observation time against its exact authorization
  revision. The original full authorization fingerprint stays bound, then production per-demand
  maximum age is replayed with overflow-safe time conversion to recompute the qualified purpose
  mask. Only a qualified exact capture member may contribute an export target; CONTROL siblings are
  authenticated but never represented as captured history.
- Activity closing is half-open at observation time. A qualified observation before closing may be
  received later only when exact terminal session/run settlement proves the durable drain; an
  observation at or after closing is rejected.
- This is read-only captured Activity export. It does not implement portable import, selected
  deletion, file/UI action wiring, catalog activation, validation, device proof, or rollout.

## TI-D236 — Pressure import claims every receipt and authenticates the whole stored lineage

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; API/Room/migration/test commits `2a680f5b9`,
  `f7e11b4cf`, and `6c77f25ef`; corrected independent review; TI-B299.
- One Pressure-local immutable receipt row authoritatively binds every accepted original or
  alternate `(job, entry)` receipt to exact entry revision, content checksum, collected-data epoch,
  source label, and receive time. Alternate identical content claims only a receipt row; later
  identity/checksum/provenance reuse conflicts and never depends on call order.
- Before replay, receipt claim, or correction extension, one bounded set of queries loads the exact
  revision headers, receipts, runs, and windows. In memory the writer proves contiguous `1..N`
  revisions, exact predecessor links, one epoch and v1 format/schema, complete receipt bindings,
  checksums/order/intervals, per-revision limits, and one stable kind/owner for every opaque identity
  across the entire lineage.
- Current local epoch and retained run tombstones are checked before every duplicate path. The
  transaction writes only imported entry/run/window/receipt rows at live generation zero;
  cancellation or failure rolls all of them back and no live provider/session/WAL/fact authority is
  fabricated.
- The v28 Room schema JSON lacks all five imported-Pressure tables and remains a mandatory
  convergence blocker. This slice does not implement imported history/maintenance, file/UI wiring,
  re-export round trip, validation, activation, or rollout.

## TI-D237 — Cell projection settlement requires authenticated full-row evidence

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; source/DAO/recovery/test commits `2e114194a`
  and `20da10845`; corrected independent review; TI-B300.
- The dormant source-local lane drains through a finite durable target and uses the one existing Cell
  qualifier and writer. Ingress and startup recovery may only provide a wake hint; they do not start
  the provider, register demand, enable rollout, or create another destination owner.
- A payload-free candidate is only an index hint. Before skipping CONTROL or AMBIENT, or interpreting
  terminal lifecycle, retention, deletion, epoch, time, logical, run, or segment fields, the lane
  reloads the exact `(eventId, ordinal, source)` WAL row and requires its complete qualified
  integrity. Missing, wrong-source, or corrupt evidence remains terminal.
- Independently authenticated deleted-source high-water is the sole row-independent settlement
  authority. The finite drain target retains the first terminal ordinal even after WAL removal, so
  failure cannot disappear from recovery merely because the payload row is absent.
- This does not invoke maintenance from a worker/action, implement transfer or shared UI, enable
  automatic/ambient Cell, provide executed evidence, activate the provider/writer, or authorize
  rollout.

## TI-D238 — Captured-Cell retention must run before its WAL authentication can be pruned

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; app service/worker/test commits `c34fa21d9`
  and `7823ddc09`; corrected independent review; TI-B301.
- The existing `wifiCellRetentionDays` setting is the only policy input. Zero remains keep-forever;
  a nonzero value produces one overflow-safe cutoff and one bounded captured-Cell retention call per
  worker run. No new duration, policy copy, scheduler, or retry loop is introduced.
- The service snapshots collected-data epoch and deleted-source high-water. The source-local
  transaction atomically rechecks both plus the exact cutoff before any fact mutation, and typed
  blocked/no-change/pruned results remain truthful nonretry outcomes. Cancellation propagates.
- Captured-Cell retention must execute before generic source-event WAL pruning because that WAL is
  required to authenticate retained facts. Pending signals may still defer legacy Wi-Fi/Cell row
  deletion, but cannot skip captured retention and allow its evidence to be destroyed first.
- This does not invoke consent-reset deletion, alter legacy radio retention semantics, start a
  provider/demand/writer, enable rollout, implement transfer/UI/automatic/ambient behavior, or
  provide executed evidence.

## TI-D239 — Activity selected deletion owns the complete replacement group, not scoped control

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; API/DAO/service/test commits `09a2f1c96`
  and `dd19b4d33`; corrected independent reviews; TI-B302.
- Deletion resolves a bounded complete bidirectional logical-entry → physical-run → segment group,
  proves every reverse binding and manifest, and requires the exact all-revision capture set to be
  `{Activity}`. Wall-time overlap, `sample_count`, `QUIESCED`, or one-sided ownership never selects
  rows for deletion.
- The transaction reauthenticates terminal lifecycle, policy/consent, source writer, exact selected
  capture demand, current provider authorization, and source evidence. It installs a monotonic fence
  for every physical run before deleting selected fact/revision/cursor/fragment/evidence and
  presentation rows, then repairs the affected structural-zone days from survivors.
- Only bounded Activity `SESSION_CAPTURE` demand is a deletion blocker. A live session-scoped
  `CONTROL_CONTINUATION` registration remains valid control authority and is preserved; it cannot
  become captured history or prevent deletion of already terminal captured Activity.
- WAL, CONTROL, plans, registrations, authorization, unrelated Activity, other sources, and
  nonselected days remain. This slice does not wire user actions/consent reset, implement portable
  import, enable automatic capture, provide executed evidence, activate rollout, or release.

## TI-D240 — Divergent Pressure origins cannot fabricate a correction winner during round trip

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; DAO/API/read/export/test commits `040b38f8c`
  and `b9e31666d`; corrected independent review; TI-B303.
- One bounded Room snapshot authenticates the complete imported Pressure entry-revision, receipt,
  run, and window lineage plus current epoch, tombstones, checksums, stable owners, correction order,
  retention, structural zone, coverage, and limits before any product or export result.
- Imported-only entries remain discoverable without Location, `sample_count`, or invented local
  lifecycle/WAL authority. Retention-only is typed partial with nullable/no pressure evidence;
  deleted, materializing, and unverifiable states remain distinct.
- Live and imported entries with different identities remain distinct. Exact full-v1 duplicate
  content under one opaque identity emits once. Divergent authenticated origins sharing that
  identity return typed `CONFLICTING_ORIGIN_IDENTITY` before sink I/O; output ordering cannot invent
  a cross-origin correction lineage or choose a latest winner on re-import.
- Only fully authenticated non-tombstoned latest imported v1 content is re-exported. This does not
  add provider/session/manifest/WAL authority, implement imported maintenance or file/UI actions,
  generate the deferred schema, validate execution, activate rollout, or release.

## TI-D241 — Wi-Fi projection settles only authenticated retained WAL through the sole writer

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; DAO/lane/recovery/ingress/test commit
  `dfaf6bb8e`; independent review; TI-B304.
- One dormant source-local lane captures a finite durable high-water and pages at most 64 Wi-Fi
  candidates per pass. The target includes retained WAL, deleted-source high-water, and the first
  terminal failure ordinal so WAL removal cannot hide poison from recovery.
- A payload-free candidate is only an index. CONTROL/AMBIENT skip and terminal lifecycle, fence,
  epoch, scope, time, or retention settlement require an exact full-row reload and qualified
  integrity. Missing or corrupt evidence remains terminal unless independently settled by
  deleted-source high-water.
- Captured and compact coverage-owner paths still pass through the existing Wi-Fi qualifier and sole
  writer, authenticate provider-interval retention and exact destination ownership, and commit
  fact/cursor/evidence/failure/lane state in one transaction with cancellation propagation.
- Ingress and startup recovery provide conflated wake hints only. This does not install or activate
  a provider, register demand, change rollout, add a second writer, invoke maintenance, implement
  transfer/UI/automatic/ambient behavior, provide executed evidence, or release.

## TI-D242 — Imported Pressure deletion fences the complete correction lineage and identity namespace

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; API/Room/migration/history/import/export/test
  commits `582f51570`, `3a6a118f2`, `11a01b75b`, and `941999060`; corrected independent reviews;
  TI-B305.
- One selected imported entry is deletable only at the exact current collected-data epoch and import
  revision after complete bounded receipt/revision/run/window authentication. Before any mutation,
  every selected opaque identity must have one compatible global live owner and no conflicting entry
  or run marker identity.
- Deletion writes a source-specific logical-entry marker and generation-one marker for every run
  across every retained correction revision before cascading the imported hierarchy in the same
  transaction. It never substitutes wall overlap, `sample_count`, `QUIESCED`, or live Pressure
  provider/session/manifest/WAL authority for ownership.
- History and export treat a marker found only on a superseded run as unverifiable and nonnumeric;
  a marker on an identity reused by the latest correction retains the typed deleted representation.
  Import checks every retained-lineage run marker before exact replay, alternate receipt claim, or a
  successor correction, preventing an older physical run from resurrecting through a newer file.
- Unrelated imported entries, local Pressure, other sources, and live authority remain untouched;
  cancellation and storage failure roll back. The generated v28 schema, file/UI action, imported
  retention/source-wide erase, execution validation, activation, and release remain deferred.

## TI-D243 — Portable Activity import owns immutable captured history without live authority

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; storage/import/test commits `5401ef0dc`,
  `a99987807`, and `fde0c9f63`; corrected independent reviews; TI-B306.
- Eight Activity-local tables retain immutable entry revisions and receipts, exact physical
  replacement runs, zone epochs, windows/fragments, and distinct entry/run deletion markers. The
  importer accepts captured v1 format only and preserves `NOT_CAPTURED` replacement members as
  structural evidence rather than converting CONTROL or missing capture into product history.
- One bounded transaction requires the current collected-data epoch and retention floor, exact or
  contiguous correction lineage, compatible receipts, and globally compatible entry/run/window
  ownership across live owners and both imported deletion-marker namespaces before mutation.
- Every run's payload-free Activity `SESSION_CAPTURE` deletion-scope digest is checked against the
  current source-local fence before receipt replay or hierarchy mutation. A current exact fence is
  typed `DELETED_SCOPE`; stale exact fence evidence is unverifiable; unrelated digest, source, or
  purpose remains nonblocking, preventing same-database export/delete/reimport resurrection.
- Cancellation, SQLite failure, and concurrent conflict roll back. Import does not create provider,
  demand, session, manifest, policy, consent, WAL, writer, or rollout authority. Imported product
  read/round trip, maintenance, file/UI action, generated schema, validation, activation, and
  release remain deferred.

## TI-D244 — Wi-Fi captured retention runs once before its WAL evidence can be pruned

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; worker/service/maintenance/test commit
  `16acedc538`; independent static review; TI-B307.
- `wifiCellRetentionDays == 0` remains keep-forever. A nonzero value computes one saturating cutoff
  and invokes the narrow captured-Wi-Fi retention service exactly once under the retention worker's
  current startup-generation lease.
- The invocation occurs before source-event WAL pruning and is not skipped when pending signals
  defer legacy Wi-Fi/Cell cleanup. The service snapshots collected-data epoch and deleted-source
  high-water; the existing source-local transaction rechecks those values and the exact floor while
  retaining its WAL authentication evidence.
- Typed no-change, blocked, and pruned outcomes continue the worker once. Storage failure maps to
  WorkManager retry and cancellation propagates; no provider, demand, writer, retry loop, rollout,
  transfer, UI, automatic, or ambient behavior is introduced.

## TI-D245 — Cell capture deletion drains callbacks without revoking independent CONTROL

- Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-14; command/runtime/repository/maintenance/test
  commits `0ad2ec4d6`, `71efc0707`, `917fe45df`, `128e3622c`, and `27b796bd8`; corrected independent
  reviews; TI-B308.
- The command preflights the exact current source-evidence epoch/high-water and revoked Cell
  `SESSION_CAPTURE` policy/consent, closes and drains the current-process callback FIFO, publishes an
  authenticated durable barrier, and lets the low-level transaction recheck every authority before
  mutation. Only exact direct capture demand is retired and run/source fences precede payload
  removal.
- Independently authorized CONTROL may remain on the physical registration. Every barrier abort,
  timeout, publication exception, storage error, and cancellation attempts exact compatible CONTROL
  resumption without reopening stale, replaced, or capture-active state. No provider start/stop or
  hidden demand is introduced.
- Pure CONTROL-only WAL remains exact and does not advance capture-deletion staleness even when it is
  newer than the request; only current-epoch, above-high-water, fully authenticated capture-bearing
  WAL contributes. Timeout and exceptional callback-lane failure remain separately typed.
- This adds no UI/action caller, retry loop, rollout, transfer, shared UI, automatic/ambient
  behavior, validation, activation, or release.
