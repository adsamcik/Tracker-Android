# Data, writers, reads, and optional enrichment plan

Status: **PLANNING_ONLY within IMPLEMENTATION_ONLY**. This report proposes work; it approves no design, activation, migration execution, or integration. Existing commits and authored tests are **IMPLEMENTED_UNVALIDATED**. No Gradle, compilation, tests, lint, schema generation/drift, diff-check, device, CI, activation, push, merge, or production/test edit was performed for this planning pass.

## 1. Scope and evidence boundary

Read the complete `TASK_CONTEXT.md`, `CURRENT_CODE_INVENTORY.json`, root `AGENTS.md`, dated README and standalone `HANDOVER_PROMPT.md`. They carry the original user requests and replacement approval rules. The settled objective is independently useful Location/Steps/Wi-Fi/Cell, manual and automatic where supported, separately consented sessionless ambient, and optional enrichment using compatible retained evidence without generating demand. Precise new storage, query, matching, and UI decisions remain subject to approval.

All paths below are repository-relative unless a checkout label is given. Line numbers refer to the inspected version, not a hypothetical merged result.

| Label | Inspected input | Meaning |
| --- | --- | --- |
| MAIN | `dev/v10`, `13f1a99871b59d29b3d059f374fc415490da6943` | Integrated implementation baseline; not runtime proof |
| ROOT | `codex/source-native-map-context-root`, `de63ffded04464f48a3aee5c075d9831915918a9` | Three candidate commits: resolver, native opaque event references, ambient reader bindings |
| LOCATION | `.worktrees/location-product-query-20260925`, `1c17c27e15442b678e5e55139a55dd1ddc4b6b25` plus 7 tracked/3 untracked drafts | Protected manual/automatic lane plus draft accepted provenance and zero-sequence repairs |
| RADIO | `C:/Users/adam-/.codex/worktrees/radio-auto-capture/Tracker-Android`, base MAIN plus 22 tracked drafts | New immutable radio generation and adaptation of writers/maintenance/transfer; not activated |
| UI | `.worktrees/unified-tracking-surface`, `487a2fc98f562e5ca418e55a0d34b68d9596a206` plus 7 tracked/3 untracked drafts | Product planner owns full UI; data inspection includes ambient summary reader |
| LIFECYCLE | `.worktrees/impl-lifecycle-product-proof`, `f786563167` | Discovered later: 26 unpublished commits, 147 changed paths. Exact proof/retention authentication candidates; not assembled or wholesale accepted |

Read inventory: module graph `settings.gradle.kts`; `core/base` and `stats/data` build files; `build-logic/convention/.../AndroidConventionPlugins.kt`; core manifest; `AppDatabase` version/builders; source-specific writer/maintenance/transfer diffs; history API, common repository and union, batch candidate DAO, radio composers and source-local range APIs; ROOT resolver and its complete authored tests; LOCATION entity/DAO/migration and handoff diffs, migration test; UI ambient summary; MAIN ambient Wi-Fi repository and imported Wi-Fi mapping; LIFECYCLE exact lookup contract/reader, Location receipt fields/authentication/retention classifier and sample reader; relevant zero-sequence sites. The initial attempted `domain/tracking` module read and guessed old build-logic path were nonexistent; the authoritative graph corrected those assumptions. Stats-data has no main manifest file.

Follow-up reconciliation read: `WORK_ITEMS.json:2541–2607` and `WORK_ITEM_TRACKING.md:104–119,185,217` distinguish the **already-approved passive/opportunistic ambient Location baseline** from separately decision-gated expansion beyond passive points. The old next-action text “handover backlog; do not start” is chronological scope accounting; the current broad completion task requires planning the baseline. Catalog containment is an implementation gap, not permission to silently exclude it.

Document coverage: inspected role-relevant data/history/Location/Wi-Fi/Cell/privacy chapters of `IMPLEMENTATION_TODO.md` (including 1045+, 1234+, 1461+, 1531+), source/data decisions TI-D186 through TI-D251 and TI-D275, current status authority/P5/development-v28/receiving-seam sections, dated current-state source/data remainder, and dated assembly manifest. Earlier history decisions TI-D109–TI-D145 and other source chapters were indexed and used as follow-up pointers, not exhaustively re-audited line by line. Full long-document coverage must be completed across the four planners; this report does not claim that a headings scan or truncated tool result constituted a full read. The late-discovered lifecycle branch was inspected at the pertinent interfaces and overlapping source, not every one of its ~31k added lines. A final code review cannot be replaced by this plan.

## 2. Existing machinery to preserve

1. Durable ingress uses zero-based **source sequence**, distinct from positive global admission ordinal. Runtime planner traced `SourceRegistrationRepository.kt:315,330`, `SourceRegistrationStateDao.kt:48–64`, and `DurableSourceIngress.kt:927–946`. Keep those domains separate.
2. Wi-Fi/Cell facts are immutable identity-free revisions with exact aggregate owner/dependent closure and cursor CAS. The RADIO writers re-read manifest binding, require the current destination owner to equal that binding, map the binding into the fact, and compare the existing row's exact binding/owner on replay. This is the correct direction; do not weaken it to merely accepting any supported generation.
3. Native and imported ownership are deliberately different. `ImportedWifiHistoryMapper.kt:27–145` preserves explicit imported origin, retained/deleted partial state, and authenticated selection. It does not grant local manifest, provider, WAL, consent, or runtime authority. Preserve the corresponding Cell and Steps boundaries.
4. `DefaultTrackingHistoryRepository.kt:387–511` already composes one bounded transactional source-aware page with exact membership collision checks and one final sort/limit. `TrackingHistoryReadDao.kt:25–40` expressly rules out one query cascade per display row. Extend this seam.
5. Source-local arbitrary range primitives already exist: `WifiHistoryRepository.kt:20–23`, `DefaultWifiHistoryRepository.kt:390–581`; `CellHistoryRepository.kt:23–25`, `DefaultCellHistoryRepository` range implementation. They handle native/imported provenance and stored structural days. The missing feature is the **shared** range/mixed-session composition, not a second Wi-Fi/Cell range framework.
6. `RoomAmbientWifiRepository.kt:39–190` already uses fixed-query bounded snapshot reads, local/imported origin selection, correction lineage checks, explicit overflow, cancellation, and `readDay`. Cell has the analogous repository. ROOT `de63ffded0` adds Hilt reader bindings only; it does not activate ambient recording.
7. Room version 27 is released; 28 is explicitly development/unshipped (`AppDatabase.kt:279–296`). Current final-schema preflight/builders and exact deletion/retention recovery are existing machinery. Do not invent a destructive migration, reset, or parallel database.

## 3. Verified blockers and current traces

### D1 — Zero is valid ingress but rejected downstream

MAIN `ActivityCapturedFact.kt:104` requires `sourceSequence > 0`; `ActivityCapturedWalAdmissionAdapter.kt:143,626` accepts `1..MAX_DURABLE_SOURCE_SEQUENCE`. `StepsCountDomainStore.kt:1845` rejects `wal.sourceSequence <= 0` in ownerless prune. LIFECYCLE still has the same adapter restrictions at 160/657 and Steps check at 1853. These are not closed by that branch.

LOCATION changes `LocationQualifiedObservation`/`LocationWalQualificationAdapter` to allow zero and changes no-prior continuity to `-1` in `ProtectedLocationCanonicalHandoff`; continuity-anchor decode also allows zero. Reconcile these fixes into any chosen Location proof implementation. Do not change positive admission ordinal, generation, revision, or callback-local completeness requirements indiscriminately.

### D2 — Generation 2 capture is not readable through current radio history

RADIO adds binding generation 2 with exact canonical owner generation 4; generation 1 retains manual-only semantics and owner 2. `SourceDestinationOwnerEntity.kt:90+` supplies source-specific supported-pair helpers. Writers and projection lanes carry and fence exact manifest ownership.

MAIN `WifiHistoryComposer.kt:608–609,1012–1025` and `CellHistoryComposer.kt:414–415,729–749` still require fixed generation 1/owner 2. LIFECYCLE changes these composers substantially but still pins generation 1 (`WifiHistoryComposer.kt:649,968`, `CellHistoryComposer.kt:543,817`). Simply applying RADIO then LIFECYCLE would not close the gap and risks overwriting exact event-reference changes from ROOT.

Readers must accept only recognized immutable generation/mode/owner tuples and require **fact == manifest == historical lane**, including exact activation/cutoff/cursor settlement. Historical reads should not require the current destination owner to remain the old writer after a valid later cutover. New writes must require the current owner. Reuse the existing generation contract; no open-ended future-generation acceptance.

### D3 — Wi-Fi maintenance draft broadens a manifest check without closing fact equality

RADIO `WifiCapturedFactMaintenance.kt:1724` calls `wifiBinding.isExactWifiWriter(consentEpoch)`; the helper at 2771+ now accepts either supported pair. Unlike the writer's existing-row validation, this proof path does not visibly compare both manifest writer fields with the selected fact revision. Trace and add that exact equality at the fact-authentication boundary before accepting generation-2 maintenance/export/deletion. Cell's modified `isExactCapturedCellBinding(revision)` explicitly compares revision binding and owner, providing a narrow model. Merely proving both objects belong to the set {v1,v2} is insufficient.

Test mixed retained v1+v2 histories, wrong cross-paired owner, same payload with swapped manifest generation, and valid v1 history after v2 becomes current. Retention/export/deletion need these tests as well as capture.

### D4 — Accepted Location provenance is promising but not a complete query authority

LOCATION `LocationAcceptedProvenanceEntity.kt:15–80` is sample-linked with cascading deletion and a stable segment/time/event index. `LocationAcceptedProvenanceDao.kt:25–70` uses paired keyset cursors, max 257, accepted decision, sample signal/event/time/elapsed/boot/clock equality, current collected epoch, and retained wall time. `ProtectedLocationCanonicalHandoff.kt:1029+` re-reads canonical receipt and writes provenance with cursor advancement in a transaction, enforcing exact replay equality. Canonical sample commit occurs earlier in the protected pipeline: do not describe sample/provenance/cursor as one single transaction without tracing that distinction and crash repair.

Remaining authority gaps in this draft:

- Its row does not retain wall-time uncertainty, exact writer generation, scope deletion generation, or a complete immutable content receipt. Its DAO checks `observed_wall_time_ms >= retained_from_ms`, not the conservative uncertainty lower bound.
- The DAO does not itself authenticate complete reciprocal logical/run/segment membership, historical manifest/consent reference, current source/run deletion fences/high-water, or accepted receipt bytes. Boolean `eligibleForContext` in the resolver is supplied by callers and is not authentication.
- Deleting a sample cascades the carrier; source-scoped erase, retention, decision/receipt deletion, restore, and interrupted handoff must all retire or fail closed on its authority. Full collected-data clear must include the carrier while preserving fences.
- The authored migration test checks columns and FK metadata only; it does not insert a populated v27 row set, prove actual cascade/reopen/rollback, or prove query qualification.

LIFECYCLE supplies useful missing semantics: `LocationObservationDecision.kt:42–54` exact writer provenance; `ProtectedLocationCanonicalReceiptIntegrity.kt:118+` receipt authentication; `LocationRetentionMaintenance.kt:115–217` exact canonical observation/decision/sample/receipt and uncertainty-bound classification. However that classifier and `DefaultSourceFactQueryRepository.kt:190–286` require WAL. This conflicts with the accepted-provenance draft's intent to support retained canonical samples after WAL pruning. Choose one coherent durable authority representation and retention dependency, not two uncoordinated proof systems.

Recommendation for approval: reuse LIFECYCLE's canonical receipt validation and writer attribution, retain the smallest sample-linked query carrier needed after WAL pruning, and batch-authenticate it with current source/run fences and conservative retention bounds. If a compact post-WAL carrier cannot prove the same receipt, keep the necessary WAL/receipt while the sample is retained and report that storage cost explicitly; do not silently accept unverifiable samples. Exact storage shape is a design decision still pending.

### D5 — Lifecycle proof branch is useful, not a completed product or a list-query API

`SourceLifecycleProof.kt` models exact evidence → fact → current head, selected correction revision, writer/cursor, coverage and completeness. `DefaultSourceFactQueryRepository.kt:73–78` delegates through the production history repository, which is a useful anti-bypass boundary. `RoomSourceFactLookupReader` rejects CONTROL and explicitly returns `AMBIENT_PROOF_NOT_IMPLEMENTED` for ambient at 119–130. This branch cannot by itself prove sessionless ambient usability.

`DefaultLocationSampleRepository.kt:95–147` loops through samples and calls `classifyLocationSampleAuthority`, which performs several DB reads per sample. `getOrderedChunkBetween:49–82` can keep scanning omitted rows until it fills a page, with no overall candidate budget visible. Reuse its truth states and authentication semantics, but replace that fanout with bounded batched reads for map/history. Do not call one lifecycle lookup per map point or list row.

Static integration hazard independently confirmed: LIFECYCLE `WifiSourceRuntime.kt:1253–1282` uses `receivedWallTimeMs` inside `wifiDelivery`, whose arguments name `receivedNanos` and `wallTimeMs`; the identifier search found only unrelated method-local parameters. This is a likely unresolved-reference defect, not a compiler result. Fix during source reconciliation and later compile at the final gate.

### D6 — Shared range and mixed-session selector remain missing

MAIN `TrackingHistoryRepository.kt:12–74` has selected/live and recent APIs, not arbitrary range. Its page contract says mixed entries remain Physical. `DefaultTrackingHistoryRepository.kt:387–511` suppresses exact single-source groups only. `TrackingHistoryReadDao.kt:41–174` has exact Steps coarse seeds and legacy compatibility; source `sample_count` use is confined to legacy compatibility and must not be generalized into native proof.

Steps-only `actionTarget` at `TrackingHistoryRepository.kt:514–517` is explicitly nonactionable; Pressure at 528–531 is also nonactionable. Activity/Wi-Fi/Cell have producer-issued selectors. Mixed Steps+Wi-Fi, Steps+Cell, Wi-Fi+Cell, and Steps+Wi-Fi+Cell need a truthful native source-session row and selected detail without distance or Location-shaped authority. A manifest capture set can change across revisions: classify the entire logical replacement union, not just the latest manifest.

Recommendation: add bounded shared range request/result and a native logical-session selector to the existing facade. Use existing source-local ranges for portable origins and authenticated native candidate/group readers. Batch complete physical membership and source fact dependencies in one snapshot; merge by stable sort once. Add source-local Steps/Pressure selectors by following existing source-owned selection/revalidation contracts, not by decoding opaque display keys. Separate selection (navigation) from mutation authority (re-read current epoch/revision/fences on action).

Suggested range contract: half-open wall window `[from,to)` plus explicit stored-structural-day mode where required by Calendar, limit bounded by the existing shared 64-row ceiling, opaque continuation tied to request and source-evidence generation, deterministic tie-breaker including origin and stable logical identity, typed invalidated/overflow/unavailable result. Do not silently return an empty or complete page when membership budget is exceeded. Cross-midnight display convention remains a product decision; use existing stored day/zone evidence and preserve partial/ambiguous legacy allocations.

### D7 — Resolver exists, geographic product wiring does not

ROOT `RadioLocationContextResolver.kt:5–118` has same logical/run/segment/boot/clock/epoch matching, sessionless exclusion, nearest fix, max 30 seconds, accuracy <=100 m, no provider dependency. Its inputs are not populated by an authoritative map query, and it produces a coordinate per radio observation, not AP/tower positions.

The thresholds/direction are not approved: existing `TrackingJoinSpecs.kt:19–24,65–96` uses Wi-Fi bracket 30 seconds and Cell prior-or-equal 60 seconds. Do not claim the new uniform nearest30s/100m is the existing policy. Recommendation is to reuse established source-specific temporal limits/directions unless the user selects the new policy; clarify that nearest-point display and Wi-Fi interpolation are different algorithms. No identity-based inference or persistent derived coordinates is needed for this MVP.

ROOT native history `localSourceEventId` references can identify already-authenticated native facts; imported entries explicitly reject local event references (`CellHistoryRepository.kt:320`, Wi-Fi corresponding invariant). Preserve this when composing LIFECYCLE changes. A foreign imported payload must never acquire a local geo join by matching an event string or wall time.

Map data shape recommendation: bounded source-specific native observation points containing radio source/type, opaque native fact/event reference, exact source scope, observed time, aggregate metric/coverage state, optional accepted Location context with time separation/accuracy and omission reason. No BSSID, tower ID, subscriber identity, AP coordinate, or unique-network count. Radio observations without context remain queryable radio history; map returns contextual and total eligible counts plus explicit omitted/partial reasons. Selection of time window and map bounds should bound scanning before rendering, while complete owner/fact authentication remains mandatory.

MAIN `MapDataChangeObserver.kt:46–80` watches only legacy location sample/observation, Cell sample, Wi-Fi observation. Add committed invalidation for native fact/cursor changes, accepted provenance/decision, source evidence floor/epoch, source/run deletion and relevant retained authority. A change to Location must invalidate dependent radio display as well as the Location layer. Subscribe only while an existing map consumer is active; no permanent observer network.

### D8 — Ambient readers exist; summary must preserve coverage and consent truth

UI `DashboardAmbientSummary.kt:68–133` uses 24 hours, Steps latest day, and latest radio fact from up to 512 facts. It can label gap-only data `NoRecentEvidence`, discards availability and coverage-only distinctions, and the Steps day does not necessarily match the radio interval. The 24-hour window and refresh cadence are proposals, not approval.

Reuse radio `readDay` plus Steps stored structural-day APIs if the approved product is Today/Between sessions. Keep exact/partial/unavailable/gap-only/confirmed-empty distinct; do not sum radio set counts as additive daily metrics, and do not add overlapping ambient and session Steps. Separate current acquisition availability from historical retained data. Preserve cancellation and source-independent failures. Runtime planner reports actual empty Wi-Fi/Cell callback handling returns null (`WifiSourceRuntime.kt:1194–1207`, `CellSourceRuntime.kt:1370–1387`): empty fact schemas alone are not proof of fresh-empty acquisition. Coordinate a narrow authoritative empty-delivery identity contract or retain an explicit platform limitation; never stamp receipt time onto a stale cache to fabricate fresh zero.

### D9 — Passive ambient Location is a required unfinished baseline

`WI-LOCATION-AMBIENT-PRODUCT-001` requires separate ambient/background Location consent, the protected sole writer, truthful passive point coverage, retained/exportable/deletable/explained facts, and no continuous-route promise. Its `public_interfaces` list is empty and it remains planned/not-started; there is no precise completed sessionless query contract to claim or reuse wholesale. Complete this baseline unless the user explicitly approves its named deferral.

The current LOCATION carrier and ROOT `SessionContextScope` require a positive segment plus logical/run IDs; those are session authority and cannot represent ambient by inventing a session. Minimal proposed data design: extend the source-specific canonical Location attribution to distinguish exact session scope from sessionless ambient scope, retain accepted provider/authorization identity and independent ambient consent/retention/deletion authority, and couple either scope to the same protected sample/receipt transaction and recovery path. Use existing source-purpose consent and deletion machinery; do not add a second Location writer or generic ambient fact platform. Add bounded passive-point reads and source-local export/delete/retention with an explicit ambient origin and point coverage, not routes or distance.

This storage/API adaptation is a new detailed design requiring approval even though the passive product baseline is already approved. Runtime owns how genuinely passive fixes enter the sole writer and retire on consent changes; data owns attribution and no-resurrection. Optional ambient radio context is a separate unfinished join: ROOT deliberately excludes it. Either propose a narrowly qualified ambient-to-ambient scope (same boot/clock/data epoch, each source's own valid consent and retained authority, no session borrowing) for explicit approval, or explicitly defer that enrichment while completing passive Location itself. Never compare two different sources' consent-epoch numbers as if equality proved compatible authority.

## 4. Minimum coherent implementation sequence (after approval)

| Slice | Production scope and dependency | Required authored regression source |
| --- | --- | --- |
| DATA-0 Reconcile inputs | Decide exact reusable LIFECYCLE commits/paths versus current ROOT/LOCATION/RADIO drafts; one owner for AppDatabase/schema/composers/handoff/retention workers. Preserve every source ref and draft. | Contract matrix old/new event fields and immutable generations; inspect full selected diffs before edits |
| DATA-1 Sequence boundaries | Runtime + data exact zero fixes, preserving positive ordinal domains and `-1` no-prior semantics. | First sequence0, replay0, next1, gap, negative, overflow, ownerless prune and Activity first capture |
| DATA-2 Radio gen2 full vertical | Existing immutable binding contract, writers, composer/history/transfer/maintenance/deletion equality; remain dormant. | Manual v1 preserved; manual/automatic v2; v1↔v2 cross-attribution rejected; retained old facts after owner cutover; owner/dependent correction closure |
| DATA-3 Protected Location read authority | Reconcile receipt/writer provenance, bounded sample-linked carrier and conservative retention, recovery and deletion. Dependency: decided post-WAL strategy. | Commit interruptions before/after canonical sample, before carrier/cursor; exact replay/collision; sample substitution; wrong scope/boot/clock/epoch/consent/fence; WAL prune; retention uncertainty; actual cascade/fullclear |
| DATA-4 Shared range/selectors | Shared native+portable range and mixed source-set page; Steps/Pressure source selectors; reuse existing range readers. Product owns routes/cards. | Old nonLocation entries outside recent page, all nonempty4-source combinations, mixed manifest revisions, replacement sibling outside date window, zero-fact waiting, duplicated memberships, ties, pagination invalidation, imported collision, stored-zone/DST/cross-midnight |
| DATA-5 Action/recovery closure | Wire selectors through existing source-local export/delete/retention/import/restore contracts; complete only actual missing methods. Product owns action UI. | Stale selection, concurrent correction/delete, scope drift, restart resume, owner/dependent closure, no resurrection on reimport/restore, no CONTROL export, replay identical/changed payload |
| DATA-6 Optional map and native stats | Batched authoritative retained reads + approved resolver policy + native aggregate rendering + invalidation. Product owns renderer/copy. | No Location means radio available/geography unavailable; delete only Location removes context; delete only radio removes radio; no provider methods; imported event spoofing; matched/unmatched counts; scope boundaries; equal-time fixes; cancellation/budgets |
| DATA-7 Ambient summary | Approved structural-day/window read of independent consented sources, explicit coverage/gaps/availability; local/imported origin kept explicit. | Consent revoke during read, policy update same retained epoch, gap-only, confirmed0 versus absent, overflow, simultaneous session+ambient overlap, partial day, DST and changed device zone |
| DATA-7L Passive ambient Location | Complete already-approved source product through protected sole writer with explicit sessionless attribution, separate ambient/background consent, bounded point reads and source-local lifecycle actions; detailed schema/API adaptation requires approval. | No fabricated session/run; passive-only acquisition; independent session/ambient consent and stop; accepted/rejected point truth; captured scope replay; background denial; export/delete/retention/restore; no route promise; optional context remains separately qualified |
| DATA-8 Schema convergence inputs | Serialize additive entity/DAO/SQL/indices/fullclear/development-v28 marker union; keep released v27 schema immutable. Author tests now. | Populated v27→28 preservation, final fresh/migrated reopen, stale-development-v28 containment, actual FK cascades and rollback, startup/deletion/retention recovery |

These are source-local changes around established contracts. A new generic materializer, all-source retention platform, inference engine, or derived-map fact writer is out of scope. Duplicated proof readers should be reduced at concrete shared authentication seams, not replaced with another universal abstraction.

## 5. Data lifecycle checklist for every selected source/purpose

- Admission: exact policy, purpose, provider/authorization identity and observed-time interval; current deletion high-water/epoch; new provider evidence; sequence0; no CONTROL leakage; unsupported remains typed.
- Persistence: one canonical destination owner, immutable binding, idempotent event/fact identity, atomic revision/cursor and failure settlement, bounded corrections and complete aggregate dependents.
- Read: exact fact-to-manifest/writer relationship, complete replacement membership, current privacy/deletion/retention authority, retained historical consent identity (TI-D275 says do not equate epoch creation revision with current policy revision), typed gaps/partial/unavailable, no inferred Location from counters.
- Maintenance: retention proof before necessary WAL pruning; conservative uncertainty lower bound; complete correction and aggregate closure; deletion marker/fence before payload removal; preserve unrelated capture/ambient/CONTROL scopes; cancellation/partial batch restart and full-clear ordering.
- Transfer: source-owned portable identity/origin, exact replay/collision, no local provider authority for imports, no control or radio identifiers, authenticated retained reexport, deletion receipt/namespace persistence preventing resurrection.
- Queries/UI: bound candidate/fact counts and memory, batched source joins, IO dispatcher, subscription-scoped invalidation, stable tie order and explicit continuation, no stale selection mutation, no fabricated numeric completeness.

## 6. Decisions requiring confirmation, not silent implementation

1. Approve the complete radio generation2 reader/writer/maintenance contract and zero fix as one bounded data change; preserve generation1 history and dormant transitions. The prior automatic review rejection of preferred V1→V2 transition edits must not be bypassed. Selection of a preferred candidate and actual rollout are separate later authority.
2. Choose retained Location authority after WAL pruning: compact authenticated sample-linked carrier (recommended for query efficiency, requires exact schema/proof design) versus retaining necessary WAL/receipt while samples survive (fewer proof representations, more storage and retention coupling). Reconcile LIFECYCLE first.
3. Approve shared source-session row/selectors/range semantics and stored-zone/cross-midnight presentation. Recommendation: reuse source-local range and complete replacement-group contract; partial/incomplete remains explicit.
4. Approve optional native observed-radio map semantics and temporal/accuracy policy. Recommendation: observed aggregates at retained device context, no AP/tower positions; reuse established temporal direction/limits or explicitly select the draft new policy. Scope currently excludes sessionless-to-session and foreign imported joins.
5. Decide ambient display window/refresh and how to show coverage-only/gap-only evidence. Existing `readDay` supports structural-day reuse without new retention duration.
6. AUTO-005 Activity control lifetime/shape remains unresolved and runtime-owned. Audit-reconciliation F22 concerns **release control evidence** (manifest/CI), not Activity control-data retention; it does not settle AUTO-005.
7. Ambient Location is currently **contained** in code but its passive/opportunistic baseline is **already approved and unfinished**, as confirmed by `WORK_ITEMS.json:2541–2582`. Complete the baseline and approve only new detailed storage/runtime/UX choices. Expansion beyond passive points remains a separate optional decision; a baseline deferral requires explicit scope approval.

## 7. Cross-role coordination

Runtime confirmed zero allocation, contained rollout, Activity-control containment, ambient Location distinction, and real callback empty-result gaps. Data sent exact generation/retention/query integration blockers. Runtime owns demand/physical ownership and activation boundaries; data queries must accept no broker/provider dependency.

Product confirmed actual consumers: `HistoryPresenterViewModel.kt:197,231,288` uses Trip date readers; Timeline and Calendar model Trip only. Product agrees a shared range/mixed-session selector is required, with stored-zone decisions explicit. Data clarified existing Wi-Fi/Cell range/readDay primitives to avoid rebuilding them. Product reports `SourceHistoryDetailRoute.kt:70–87` uses unavailable actions even for existing Activity/Wi-Fi/Cell detail; action dispatch remains necessary. Product owns that UI and native statistics/Location-containing live state; data owns source truth and selector contracts.

Delivery must reconcile all exact input refs before freezing; LIFECYCLE reuse is a proposal, not blanket integration approval. Its final proof API still lacks ambient, gen2 radio qualification, and bounded map batching. Final integration requires a new independent review of the actual assembled source/tests, not four planners agreeing about a plan.

## 8. Deferred evidence and unresolved inspection limits

After every approved production/test-source slice exists, freeze exact commits/draft disposition and explicitly authorize the final convergence phase. Then execute compilation/Hilt/Room/schema, focused unit/contract/Room suites, populated v27 migration and reopen, `ciUnitTest`, and `ciCheck --continue` on the exact integrated candidate, fixing and rerunning required gates. None ran here.

One representative device should subsequently prove manual each-only-source and representative mixed sets, automatic capture/control separation, supported sessionless ambient, simultaneous session+ambient stop survival, permission changes, process/reboot/FGS handling, fresh/unchanged/empty radio evidence where genuinely supported, retention/deletion/map invalidation, and listener retirement. Source traces and host tests cannot prove physical Android delivery or battery behavior.

Specific remaining inspection debt before implementation: full chosen LIFECYCLE diff/dependency closure and tests; complete legacy/location import/restore compatibility impact of stronger query authority; source-wide Location deletion carrier behavior; shared range source failure isolation behavior; published-v28 premise if deployment history contradicts current source; imported gen2 portable format compatibility across older readers; exact source-local selectors for Steps/Pressure mutations. These are explicit review tasks, not evidence of defects or authorization to add speculative frameworks.

Memory was used only to locate prior independence/implementation-only guidance (`MEMORY.md:43–74`, prior rollout `01a0da83-c797-7dd2-939a-aee7dfbd5f3f`); current source above supersedes its older missing-feature lists.

## 9. Consolidated-plan data peer review

Reviewed `IMPLEMENTATION_PLAN.md` after the Delivery ambient-context correction. Its S7, invariant 4, D4, D7 and post-decision text now propose compatible **ambient-to-ambient** context after S5, with each source's own consent authenticated; session-to-ambient borrowing and foreign imported authority remain excluded. This supersedes this report's earlier unselected alternatives about deferring ambient context. The proposed context still creates no demand and still requires explicit approval of its precise matching/display policy. Zero-sequence semantics, immutable radio generations, current write versus historical read authority, compact retained Location proof, bounded batching, native/imported distinctions and complete global freeze accounting are consistent with the data findings.

Sent Delivery two concrete acceptance clarifications before finalization:

1. S5 must reconcile the current carrier's unique `sample_id`, source-event primary key and mandatory single-session scope with a physical Location delivery eligible for both session and ambient. Preserve independent purpose authority without duplicate canonical samples or double counting. Revoking/deleting/retaining one purpose must not erase another still-authorized purpose's evidence or leave unauthorized visibility. The exact source-specific representation remains the approval-gated S5 subdesign; author dual-purpose replay, consent, deletion and retention tests.
2. D1/S3 CONTROL TTL cleanup must not delete shared Activity WAL or receipts still required by captured Activity. Separate control-exclusive retained authority/payload from capture dependencies under existing purpose-scoped retention. Author a dual-purpose event crossing the control TTL while captured history remains retained, plus a control-only event that expires.

Also requested an explicit D4 boundary: an exact-equal accepted Wi-Fi Location fix should qualify without requiring two distinct bracketing observations; test exact equality and stable endpoint tie selection. These are planning review findings, not executed failures.
