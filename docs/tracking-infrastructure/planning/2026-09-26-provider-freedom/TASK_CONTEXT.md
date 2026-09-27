# Full task context for four Astra planning agents

Status: PLANNING_ONLY. Existing implementation and authored tests remain IMPLEMENTED_UNVALIDATED.
This packet is context, not approval of recommended choices and not an assembly freeze.

## Corrections established by the coordinated current-code review

The historical hypotheses under “Known findings to VERIFY” are inputs to challenge, not current conclusions. Location AMBIENT_PRODUCT has a supported catalog identity but is contained, rather than Unsupported. `WORK_ITEMS.json:2541` and `WORK_ITEM_TRACKING.md:104–119` retain the already-approved default-off passive/opportunistic ambient Location baseline; only expansion beyond passive points needs a separate product decision. The final implementation plan and reconciled role reports supersede the hypotheses below.

## Original task messages, in order

### 1. Initial request

I want you to identify what is still incomplete from our implementation work of the providers. The MVP requires location/steps/wifi/cell tracking to work independently of any other active tracking but also have the capability to be enriched by whatever makes sense, eg. location enriches wifi + cell for map displays and so on. Any questions?

### 2. Scope clarification

Assistant question: For this MVP, should independent tracking cover manual sessions only, or also automatic sessions and ambient tracking? I’ll assess all three against the existing plan unless you narrow it.
User answer: All three where supported

### 3. Completion and coordination request

Please trace every part through code and ensure all pieces are unconditionally implemented and work reliably and can be trusted and used, so that we may begin practical on-device testing. Do not stop until it is fully completed and you are able to prove by tracing the code with a fresh subagent that everything is fully implemented. I want to rework manual/ambient/automatic sessions so that the tracking happens more naturally and organically and avoid split flows. The tracking should leverage a single highly polished and highly reliable flow and clean performant activations of parts of the "brain" that ensure only relevant bits are activated. We need some coordination between the layers if we want to enrich data, crosscheck and gather feedback on relevant bits from astra and ensure it is fully equipped to answer them with full context.

### 4. Ambient clarification

Assistant question: For the unified flow, should ambient observations remain sessionless and separately consented while sharing the same provider activation and fact pipeline as sessions? That matches the current privacy model.
User answer: Yes, sessionless ambient

### 5. Earlier AGENTS instructions

Commit work after each completed step. Merge worktrees back to the main worktree once the entire effort is completed. Never deploy anything to chatgpt sites, unless instructed.

The project-doc section was the tracking repository guidance in the root AGENTS.md. It is repeated in the current project guidance file and must be read fully. The later replacement below supersedes this earlier general prefix.

### 6. Continuation

Continue

### 7. Replacement general AGENTS instructions, verbatim

These AGENTS.md instructions replace all previously provided AGENTS.md instructions.

## Scope and simplicity
Build for a personal project unless told otherwise. Use the simplest clear, maintainable solution that delivers the requested behavior with high polish and reliability. Follow existing conventions. Treat unrequested functionality as out of scope: avoid speculative features, future-proofing, unnecessary abstractions, infrastructure, and unrelated refactoring. Suggest worthwhile extras, but implement them only after approval.

## Questions and approval
Read all task-related user messages and relevant code before acting. Ask focused questions about unresolved ambiguity, conflicting requirements, or major choices; do not guess. Confirm every new or changed design decision before implementation, including UX/UI, behavior, architecture, dependencies, and data handling. Batch related decisions into a brief proposal with your recommendation and meaningful trade-offs, then wait for approval. Do not reask settled questions. Routine execution of explicitly approved decisions needs no further approval; undisclosed choices are not implicitly approved.

## Execution and verification
Work in small, coherent steps. Keep investigation, planning, and testing proportional to the task. Simplicity must not sacrifice correctness, security, necessary error handling, or polish. Run relevant checks and commit after each completed, verified logical step, including only task-related changes. Avoid redundant checks and endless refinement. Report verification gaps honestly.

## Independent review
For complex or risky work, use an independent, review-only subagent before final integration. Give it the original text of all user messages for this task, including follow-ups and corrections, plus approved decisions, relevant code, the diff, and verification results—not just your summary or the latest message. Have it check full requirement compliance, correctness, regressions, unapproved decisions, scope creep, and unnecessary complexity. Use one reviewer by default; add others only for distinct needs. No recursive delegation. Address concrete findings and recheck affected areas. If subagents are unavailable, disclose this and perform a separate self-review.

## Git and deployment
Once the entire effort is complete, reviewed, and verified, merge work from task worktrees into the intended branch in the main worktree and verify the integrated result. Preserve unrelated changes. Ask when the integration target or conflict resolution is unclear; never discard others’ work.

Never deploy anything to ChatGPT sites unless I explicitly instruct you to.

The user included the same complete project-doc section from root AGENTS.md after this prefix. Read that whole file; the implementation-only phase has precedence over generic verification instructions until final assembly is explicitly frozen.

### 8. Product clarification

What I want is essentially to give users maximum freedom in what they track and the tracker will just work, some functionality may be limited ofc like the map, if you dont track location there wont be anything, but you get the point right? Consult astra too

### 9. Latest planning request

Yep that sounds good, please let Astra design the full implementation plan, make sure you spawn 4 focused Astra subagents to coordinate together to form the plan and ensure 100% context is gathered so that the plan covers every edge case and is accurate to current state

### 10. Whole-product polish follow-up

I want you to run one more pass that also considers highly polished, UX where every element and piece of text must earn its place. The goal is to make sure things don't make sense just at the tracker level but also at how we store and present the data to the user and export. All pieces of the tracker need to be considered during this work not just my request in isolation of everything else.

This follow-up authorizes another planning and static source-review pass. It does not select the pending design choices in section 9 of the implementation proposal or start the deferred validation phase. Review the complete existing product and its data/export meaning; revise the proposal with concrete, evidence-backed omissions and element/copy rules, without implementing an unapproved redesign.

### 11. Questions and visual overview request

Give me the questions you still have as well as visual tldr of what we want to build.

### 12. Image request

I can't see the flowchart can you generate visualisation images?

### 13. Unified adaptive collection request

Would it be possible to merge the ambient and session sources together and instead design single adaptive system that can independently scale the collections and adapt to any configuration, simply, cleanly, durably, reliable.

The coordinator recommended one source list and adaptive per-source plans, preserving consent and durable collection evidence. It asked whether sessions should request greater detail or only group observations, and whether active collection may increase between sessions. The latter remains unanswered.

### 14. Session semantics clarification

Sessions will essentially ideally just be times of higher fidelity and help users organize data and perhaps designate sessions as activities trips and so on. Sessions can also be added manually afterwards.

This settles live sessions as fidelity requests plus organization and requires retrospective creation. ADAPTIVE_COLLECTION_AND_SESSIONS.md records the proposed concrete design and supersedes the earlier two-source-list product target. It does not approve active background escalation, exact schema/actions/export design, or unrelated earlier pending choices.

### 15. Tool questions and five-level adaptive policy

Can you use your tools to ask questions?

The adaptive system should be able to leverage hints from multiple sources that tell it whether the user is moving, stationary, what is the settings what balance between quality and power it should use. This includes whether session is active and more, fill in all the types. The system should then based on this adapt the tracking and setups so that its working reliably and trustworthy, especially trying to balance the value. The goal is that everything should work through a central systém and it should adapt to balance what the user wants if they start a session they want to prefer quality over power otherwise based on settings other preferences may be there. Use a 5 step configuration for balance of power and performance and figure out the best system that can support it.

This settles a central adaptive policy, five user-selectable power/quality levels, multiple qualified inputs, and live-session quality preference. FIVE_LEVEL_ADAPTIVE_POLICY.md develops the concrete recommendation. Five preference levels are distinct from source enablement and from motion state. Retrospective organization has no current collection effect. Exact profile behavior, live-session level mapping, device-power handling and expanded background authorization remain proposals until selected; the request does not activate providers or begin deferred validation.

### 16. Acquisition-mode energy cost refinement

We should also take into account during scaling how much a resource uses battery and how much a mode uses battery, for example steps tracking is basically free, while wifi scans are quite expensive but passive wifi scans are rather cheap for the app.

This adds a required source-and-mode cost distinction to adaptive scaling: compare additional acquisition, delivery, processing and switching work; preserve useful cheap continuity/passive observations when reducing expensive optional requests. "Basically free" is the user's relative-cost example, not approval to claim zero measured energy. FIVE_LEVEL_ADAPTIVE_POLICY section4.1 incorporates the concrete proposal and supersedes blanket per-source battery downshifts. The earlier two approval questions remain unanswered; this refinement does not approve all preceding defaults or active-background expansion.

### 17. Complete plan and alternatives request

Make sure to fill in all the gaps in the plan with consideration for all options available to us

This requests closure of the complete implementation proposal, including concrete pre-edit contracts, realistic platform alternatives, whole-product behavior, migration and verification dependencies. Inspect unresolved source-backed questions now and supply a recommended choice with tradeoffs. Keep genuine user approval and external/device evidence distinct from missing planning work. This is not blanket approval of the previous defaults, new background scope or unresolved D/P choices, and does not open deferred validation.

### 18. Adversarial simplicity and missing-work review

Anything left and remaining that we missed? Please also run adversary review to make sure we did not overcomplicate the design, feel free to rewrite any part of architecture if it means it will be much cleaner, more maintainable and simpler.

This authorizes an adversarial review and rewriting the proposed architecture for simplicity and maintainability while preserving the task's requirements. Challenge earlier planning conclusions against actual code and concrete failure cases. It does not approve the still-pending package wholesale or open deferred execution gates; new production behavior remains subject to the Questions and approval rule. Reduce duplicate mechanisms and plan ambiguity rather than adding another competing plan layer.

## Confirmed product direction

The assistant's immediately preceding response accepted by message 9:

Yes. I consulted Astra, and it agrees with this model:

- Users choose what to record. Every supported source combination works independently.
- Features follow the available data. Steps-only gives steps; Wi-Fi/Cell-only gives radio history; no Location data means no geographic display.
- Enrichment is optional. Compatible data already collected can improve another source’s presentation without enabling extra tracking.
- One shared engine serves manual, automatic, and separately consented sessionless ambient tracking. Automatic trigger requirements remain explicit and separate from recorded sources.

The existing shared provider architecture fits this. The remaining gaps are mainly reader wiring, truthful history for sessions without Location, and optional map enrichment—not a need for another “brain.”

That is the acceptance standard I’ll use. The precise data-handling choices in the pending proposals still require approval under your replacement AGENTS.md.

## Earlier pending proposals: recommendations, not settled approvals

Before the product clarification, the coordinator asked these questions. No explicit selections were received. Message 9 confirms the high-level direction above and authorizes full planning; do not treat it as selection of arbitrary TTLs or technical/UX options.

1. May I complete this data design: keep one purpose-aware provider flow; add immutable Wi-Fi/Cell generation 2 contracts for manual and automatic sessions; fix zero-based first-event handling; retain exact accepted Location provenance with its sample, deleting it with the sample; derive radio map context only within the same session/run/segment/boot, within 30 seconds and with Location accuracy <=100 m; show aggregate observed coverage without inferred AP/tower positions?
2. May I complete these UI choices: show separately consented, sessionless ambient Steps/Wi-Fi/Cell in a Between sessions card; refresh stored-data summary every 30 seconds while the dashboard is visible; show partial/unavailable evidence honestly; include source-only sessions in calendar/timeline using their existing source detail screen, with distance/maps shown only where supported?
3. How long should Activity evidence used only to start or maintain automatic sessions remain on-device? Recommendation: fixed 24-hour maximum, followed by deletion; captured Activity history keeps separate existing retention. Alternatives offered: until session ends, 7 days.
4. Further tracing found that historical sessions combining Steps/Wi-Fi/Cell without Location still render as distance-based trip rows. May I include them as source-session summaries and a shared detail view, and add a bounded date-range read so calendar/timeline can find older source-only sessions reliably?

For the plan, reduce new decisions by following already approved durable designs and established constants. A recommendation in this packet is not source evidence.

## Authoritative locations and exact current state

The paths/HEADs below describe the initial planning snapshot. For this latest closure pass, use PLAN_CLOSURE_INPUTS.json for the refreshed seven-checkout inventory, source-draft hashes and full lifecycle commit list. COMPLETE_IMPLEMENTATION_PLAN.md is now the current proposal entry point; new design recommendations remain pending approval.

Read CURRENT_CODE_INVENTORY.json captured immediately before this planning pass.
Main checkout is C:/Users/adam-/GitHub/Tracker-Android, branch dev/v10, clean HEAD 13f1a99871b59d29b3d059f374fc415490da6943.
Primary read/docs output checkout is C:/Users/adam-/GitHub/Tracker-Android/.worktrees/source-native-map-context-root, branch codex/source-native-map-context-root, HEAD de63ffded04464f48a3aee5c075d9831915918a9.
Location worktree: C:/Users/adam-/GitHub/Tracker-Android/.worktrees/location-product-query-20260925, HEAD 1c17c27e15442b678e5e55139a55dd1ddc4b6b25, 7 tracked + 3 untracked draft files.
Radio worktree: C:/Users/adam-/.codex/worktrees/radio-auto-capture/Tracker-Android, HEAD 13f1a99871b59d29b3d059f374fc415490da6943, 22 tracked draft files.
UI worktree: C:/Users/adam-/GitHub/Tracker-Android/.worktrees/unified-tracking-surface, HEAD 487a2fc98f562e5ca418e55a0d34b68d9596a206, 7 tracked + 3 untracked draft files.

No branch is assembled with all these changes. Inspect committed changes relative to main AND working-tree diffs/untracked source. Never present another branch's draft as integrated code.
An unused managed root checkout also exists but is not part of this plan's source input.

Root commits: 037cd12955 scope-safe radio Location context resolver; 58afb52545 authenticated opaque native localSourceEventId history references with imported references forbidden; de63ffded0 ambient radio repository Hilt binds.
Location commits: 6b47edd05b dormant protected manual Location lane; 1c17c27e15 immutable automatic-capable protected Location lane.
UI commits: 441c23b063 active Stop remains available without Location permission; 487a2fc98f exact nonLocation mixed live source presentation.
All these commits are IMPLEMENTED_UNVALIDATED.

## Known findings to VERIFY, not blindly repeat

- Existing SourceRuntimeRegistry/SourcePipelineModule compose six sources and shared controllers. Wi-Fi/Cell/Steps session+ambient demands share physical ownership and durable ingress, session stop should preserve ambient demand.
- Manual/automatic start through TrackerServiceSourceSession/authoritative coordinator. Ambient remains sessionless separate consent.
- All candidate rollout state is initially contained; dormant catalog declarations alone do not make a usable production start.
- Automatic Activity CONTROL is contained pending AUTO-005's control-only evidence lifetime/shape. TTL has NOT been chosen. No control evidence may enter ordinary capture history/export/goals.
- Ambient Location is explicitly unsupported in current catalog. Confirm whether to retain as MVP supported-limit versus later proposal, given original design describes passive/opportunistic Location.
- The ingress allocates sourceSequence zero first. ActivityCapturedFact reference/admission and ActivityCapturedWalAdmissionAdapter reject zero; StepsCountDomainStore ownerless prune rejects zero. Location uncommitted draft corrects zero and -1 no-prior continuity.
- Radio draft adds immutable manual+automatic generation 2, canonical owner generation4, preserving generation1 manual-only owner2. WifiHistoryComposer and CellHistoryComposer currently pin generation1/owner2. Wi-Fi maintenance draft authenticates either manifest but needs exact fact-to-manifest writer generation comparison.
- Prior automatic approval review rejected changing preferred radio candidate transition bindings V1->V2 as provider/writer activation forbidden in implementation-only phase. Those edits were not applied. Do not bypass the rejection; plan the explicit later assembly/validation/activation boundary.
- Location durable accepted provenance draft entity/DAO is sample-linked, inserts atomically in protected canonical handoff with cursor, exact replay equality, accepted decision + sample signal/event/wall/elapsed/boot/clock/current epoch/retention qualified join, bounded stable paired keyset. Check source scope/source deletion/recovery limitations.
- RadioLocationContextResolver draft has same logical/session/run/segment/boot/clock/epoch matching, max30s and100m accuracy; it is not query/renderer wiring. TrackingJoinSpecs established limits differ: Location30s, Wi-Fi bracket30s, Cell60s. New thresholds remain proposals.
- Native radio facts are identity-free. Legacy radio map paths require BSSID/tower identity + stored coords. Do not fabricate identities/site/AP estimates for new aggregates.
- Current MapDataChangeObserver does not observe source-native fact/cursor/provenance/deletion/retention tables. Context-dependent radio layers need invalidation from either source.
- History recent union supports source-only entries but leaves mixed nonLocation sets as Physical trip presentation. Calendar/timeline only Trip. Arbitrary date-range discovery is absent from TrackingHistoryRepository; filtering recent pages is incomplete.
- Detail selections support Activity/Wi-Fi/Cell; Steps/Pressure missing selectors explicitly. Steps Today is qualified. Statistics radio summaries still use legacy identity-bearing repositories.
- Ambient UI draft has proposed Between sessions card, per-source consent + policy revision, but coverage/gap-only/unavailable and refresh gaps remain.
- No Gradle/compile/tests/lint/schema/diff-check/physical/device/UI/battery/CI validation has run for these slices. Written tests are not passing evidence.

## Planning operating rules

Exactly four focused Astra planning identities are requested. Runtime, Data, and Product first gather independently and directly exchange focused findings; Delivery joins after a slot is free and synthesizes the final plan with peer challenges. This host supports three concurrent children plus the coordinator, so four agents run in coordinated waves. No recursive delegation.

Agents may read all relevant code/docs/diffs and write ONLY their assigned planning report in this folder. Do not edit production/test sources, existing drafts, phase/rollout state, main AGENTS, or private config; no commits by agents. Root commits reviewed planning artifacts only.
Do not execute deferred validation. Do not push/activate/deploy/merge implementation.
Use exact current paths/one-based lines and distinguish main baseline, committed candidate, uncommitted draft, recommendation, and unknown.
Consult repository instructions + handover prompt, settings/module graph/build logic/manifests/tests. Partition large durable design/decision/TODO/status/verification/runbook docs across four roles, with explicit coverage inventory. Follow links relevant to your role and track disagreements with stale documents.
Each report must include: coverage/read inventory; current provider-to-product traces; exact blockers; preserved good machinery; smallest proposed changes; dependencies/ownership; focused test-source cases + eventual validation evidence; approval decisions/alternatives; cross-role questions and answers.
Do not assert literal universal edge-case proof from static inspection. Produce an exhaustive in-scope checklist and explicit unresolved/context limits. Final independent code review after implementation remains a separate fresh-agent gate.

## 19. Visualization request

> Please generate me visualisation so that I can more easily understand the plan

Two explanatory images were generated; these were proposal previews, not evidence of implementation.

## 20. Location correction

> Here is one example that I would like to tackle if we have balanced and location why would location be off. I was also thinking to expand the location to be handled much better so that we can collect easily location over time but based on settings we balance sources and collection rates. At balanced level I would expect to have decent fidelity collections optimized through activity and so on.

The image used session-only Location. Code review also established actual proposal gaps: passive-by-default background, Balanced preference conflated with Android provider priority, stationary level3 cap ineffective, and displacement filtering starving stopping evidence.

## 21. Tool questions and exact answers

- What should Balanced Location aim to preserve during ordinary all-day tracking?
  Answer: Daily routes (Recommended). Recognizable walks and journeys with adaptive precision/sampling and possible gaps.
- May adaptive Location use separately permitted motion/activity hints when Activity history tracking is off?
  Exact answer: "allow hints but make them optional we cannot force anyone to anything."
- When the user explicitly enables background Location, should adaptive collection with an ongoing notification be the normal mode?
  Answer: Adaptive by default (Recommended). One shared service can reduce/increase effort without needing a session.

## 22. Implementation approval

> Implement the proposed plan.

This approves the immediately preceding All-day adaptive Location plan, reproduced in ADAPTIVE_LOCATION_IMPLEMENTATION.md. It does not lift the repository IMPLEMENTATION_ONLY phase, authorize candidate activation/push, or implicitly approve unrelated pending choices in the broader package.

## 23. Close unresolved design decisions — 2026-09-27

User requests following the implementation checkpoint:

> What is remaining from our mplementation work, did you make sure all is commited into dev/v10?

> Can you write me step by step work items that we need to complete as a markdown list?

> **Close unresolved design decisions.** Resolve the documented CONTROL retention lifetime, optional Wi-Fi/Cell notification hosting, and remaining file/archive behavior.

The user then answered the four tool questions:

> 1. 48 hours retention
> 2. Yes
> 3. I don't get what this is about
> 4. The point of database export was a clean way to backup all data

The questions concerned (1) CONTROL-only lifetime, (2) optional default-off “Keep running with notification” for Wi-Fi/Cell, (3) selected portable files/geographic interchange with truthful time/scope, and (4) retaining raw database archives as advanced exports without complete restore. Therefore 48h and the host option are approved; file-format confusion is not approval; the archive-only recommendation is superseded by the all-data-backup requirement.

Follow-up question tool (selected-export question superseded by section24; restore question still pending):

> For selected-data exports, I recommend a Tracker file that preserves the selected data and session labels, alongside GPX/KML for other apps. Exports would show when a whole day or measurement must be included, and imports would never invent missing timestamps. Is that the behavior you want, separately from full backup?

Options: Yes—Tracker files plus GPX/KML (Recommended); Keep selected exports to the existing formats for now.

> For full backup, I recommend saving all durable Tracker data and settings, excluding temporary caches and files bundled with the app. Restore would replace existing Tracker data after confirmation, recover safely if interrupted, and leave tracking stopped until the user starts it. Should restore support replacing existing data, or only work in an empty app?

Options: Allow replacement after confirmation (Recommended); Restore only into an empty app.

DECISION_CLOSURE contains the concrete proposal and current source evidence. No complete backup/restore implementation, recurring schedule migration, validation or integration is authorized merely by recording this decision packet. Phase remains IMPLEMENTATION_ONLY.

## 24. Export format correction — 2026-09-27

> No if user selects a given format they should get export in just that format.

This settles output strictly in the user's selected format. No automatic companion Tracker file, session bundle, backup, different enclosing archive or format fallback. A proposed new bundle/codec remains a separate explicit format choice if approved; this correction is not approval to add those formats or all detailed import proposals. The earlier selected-export follow-up is superseded, not pending and not to be reasked. The full-backup restore question remains pending. Phase remains IMPLEMENTATION_ONLY; this slice records the design correction only.

## 25. Reconcile implementation inputs

> Reconcile implementation inputs. Inventory committed candidates and preserved drafts, select their final versions, and resolve overlapping changes without losing existing work.

Work continues on the existing candidate from63ef296026, preserving original refs and dirty draft files. Current inventory and final selection/reconciliation receipts live under `reconciliation/2026-09-27`. The user authorizes input reconciliation, not new product/data-handling decisions, provider/writer activation, deferred validation, integration into dev/v10 or push. The independent reviewer receives the full original user packet, approved decisions, exact inputs/diff and static-only verification boundary. No recursive delegation.

## 26. Reconciliation result — 2026-09-27

Current final input selection is `../../reconciliation/2026-09-27/SELECTION_MANIFEST.json`. Continue ROOT with latest compatible V1 lifecycle/proof families, committed source-aware UI and six committed audit corrections. Preserve whole LOCATION/RADIO V2 inputs until exact-generation proof/history/retention transport is assembled. Exclude old Location reader/pruner that rejects ROOT ambient receipts; preserve originals for bounded common-purpose adaptation. All56 live draft bytes are archived locally and original worktrees/refs remain untouched. This supersedes older planned source-selection wording only; it changes no approved product contract. Input reconciliation is IMPLEMENTED_UNVALIDATED; no global freeze, validation, activation, dev/v10 integration or push.

## 27. Source lifecycle proof continuation — 2026-09-27

> Please update the checklist as things are completed.
> **Complete source lifecycle proof.** Authenticate observation identity, registration, authorization, sequence and product lineage consistently across all six sources.

The live completion checklist is `../../WORK_ITEM_TRACKING.md`. The current six-source trace,
bounded corrections and independent static review receipt are in
`../../lifecycle-proof/2026-09-27/SOURCE_TRACE.md`. Whole lifecycle proof remains incomplete;
written test sources have not been compiled or executed. The candidate remains isolated and
the implementation-only phase remains binding.

Pending question sent through the question tool:

> Ambient Steps comes from system aggregate windows, so it has no WAL event or admission number. I recommend letting lifecycle proof carry its real provider-window/mutation identity, registration and revision, while WAL-backed sources retain their exact event/sequence proof. This avoids inventing identity and preserves one shared proof API. Approve this contract adjustment? Repository AGENTS.md requires confirmation of new architecture/data-contract decisions.

Options: "Approve truthful aggregate identity (recommended)"; "Keep aggregate Steps proof separate".
No answer has been received or inferred. Dependent shared proof-contract changes remain unapplied.

## 28. Shared Wi-Fi/Cell admission — 2026-09-27

> **Fix shared Wi-Fi/Cell admission.** Qualify individual items for each purpose so stricter session requirements cannot discard valid background observations or lend consent backward.

This is the approved corrective scope of `RUNTIME_GAP_CLOSURE.md` section 5.2a. Current input
trace and a concrete marker proposal are in `../../shared-radio/2026-09-27/CONTRACT_DECISION.md`.
Independent reviewer confirmed that selected and preserved RADIO V2 inputs both use payload 5
and lack a durable shared-item contract marker; capture writer generation cannot act as an
ambient-only physical semantics switch.

Question tool request:

> The approved plan requires preserving old radio behavior, but neither the selected code nor preserved V2 drafts has a version marker for the new per-item rule. I recommend adding an explicit versioned shared-radio contract carried by immutable requests/demands and WAL snapshots, with old records unchanged and provider activation still deferred. Approve this contract addition? AGENTS.md requires confirmation of new architecture/data contracts; the concrete proposal is docs/tracking-infrastructure/shared-radio/2026-09-27/CONTRACT_DECISION.md.

Options: "Approve explicit versioned contract (Recommended)"; "Apply correction to existing contracts too".
No answer has been received or inferred. Common pure qualifier/test-source extraction proceeds as
authorized preparatory implementation; no caller, stored contract or activation changes are applied.
The full checklist item remains open. Implementation-only restrictions and protected paths apply.


## 29. Five-level configuration completion — 2026-09-27

> **Complete five-level configuration across sources.** Separate participation from quality, preserve legacy/custom settings, and apply live-session quality preference only to participating sources.

This implements the already-approved five-level profile, additive metadata, baseline/override and
actual accepted live participant decisions in FIVE_LEVEL_ADAPTIVE_POLICY.md and the newer Location
mapping in ADAPTIVE_LOCATION_IMPLEMENTATION.md. It adds no consent, background mode or recurring
radio attempt capability. The quality slice uses current source selection, native ambient Steps
and existing automatic operational permission behavior. Prior pending shared-radio marker and
ambient aggregate Steps proof questions remain unanswered; their dependent changes are unapplied.

The candidate is `codex/source-native-map-context-root`, based on `1d77cce2d1`. The main `dev/v10`
checkpoint remains unchanged. Production and focused test sources are authored; static independent
review is mandatory before a local IMPLEMENTED_UNVALIDATED commit. No Gradle, compilation, tests,
lint, Detekt, schema generation/drift, diff-check, device, UI, battery, CI, release, rollout,
provider/writer activation, push or integration is permitted in this implementation-only phase.
See `../../five-level/2026-09-27/IMPLEMENTATION_TRACE.md` for the trace and evidence boundary.


## 30. Source-specific power implementation request (2026-09-27)

Original user text:

> **Implement source-specific power behavior.** Preserve cheap Steps continuity and passive radio callbacks; enforce finite active radio budgets, cooldowns and request ownership without replenishing budgets through reconfiguration.

User-provided AGENTS.md remains binding: implementation-only until assembly freeze; test source
required but no execution/gates; preserve protected root paths, source refs and drafts. Independent
review-only agent required; no recursive delegation, activation, push or new dev/v10 integration.

The approved finite-attempt policy remains unchanged. A new versioned radio power checkpoint
contract in existing runtime-state storage was proposed explicitly through a question tool;
confirmation is pending. Its dependent persistence work is unapplied. Independent Steps quality
and radio request-fencing source fixes may proceed; entire source-power item remains open.


## 31. Shared provider and host ownership request (2026-09-27)

Original user text:

> **Finish shared provider and host ownership.** Ensure one physical owner per source, acknowledged retirement, independent purpose lifetimes and the correct foreground-service requirements.

The full original user packet above and user-provided AGENTS.md apply. Work remains implementation-only:
production/test-source authorship and static inspection/review only; no Gradle, compilation, test,
lint, Detekt, Room schema generation/drift, diff-check, emulator/device, UI/battery, CI/release/
rollout gates, activation, push or new dev/v10 integration. Preserve protected paths and drafts.
The selected candidate is codex/source-native-map-context-root based on b23b496e2f; main dev/v10
remains 13f1a99871. Independent review-only provider_host_review has full original context and diff.

Two concrete remaining contracts were asked via question tools under AGENTS.md. Radio hosting:
extend existing host with separately keyed leases and default-off per-radio saved choices. Feature
Location: shared nonpersisting adapter, approved Map/game observed-age proposals, and newly proposed
cache-only sunrise refresh from already-authorized collection. No answer has been received or
inferred; dependent work is unapplied. See ../../provider-host/2026-09-27/CONTRACT_DECISIONS.md.
Earlier shared-item WAL, aggregate Steps identity, radio recovery and backup restore decisions
remain pending. Whole ownership completion and convergence gate remain open.

## 32. Passive background Location request (2026-09-27)

Original user text:

> **Complete passive background Location.** Add system-rearmable delivery, authenticated receiver admission, recovery and safe transitions to the existing active Location owner.

IMPLEMENTATION_ONLY continues: source authorship and static review only, no Gradle, compilation,
test/lint/Detekt/schema/diff-check/device/UI/battery/CI/release/rollout gates, activation, push or
new dev/v10 integration. Preserve protected paths and drafts. Candidate starts at f0a36cef2c;
main dev/v10 remains 13f1a99871. Independent review-only passive_location_review inherits the full
original user packet, approved requirements and implementation-only boundary; no recursive work.

A concrete FLP plus framework passive PendingIntent contract through the existing owner, existing
SYSTEM_REARMABLE storage, a non-exported 5s/64-fix receiver, exact retire-before-replace and safe
current-authority recovery was asked via the question tool, as required by user AGENTS.md.
No answer was received or inferred. Dependent production/test-source implementation is unapplied.
See ../../passive-location/2026-09-27/CONTRACT_AND_TRACE.md. Earlier pending decisions stay pending.
Current source already includes a durable observed-user-stop gate distinct from the positive
force-stop process guard; the new passive receiver/recovery must reuse both. Whole passive Location
and the assembly gate remain open.

## 33. Background Steps execution request (2026-09-27)

Original user text:

> **Complete background Steps execution.** Finish supported system-provider import scheduling, count-domain deduplication, bounded recovery and honest coverage gaps.

All original user messages above and user-provided AGENTS.md apply. IMPLEMENTATION_ONLY continues:
bounded production/test-source authorship, static inspection/review and coherent local checkpoint
only. No Gradle, compilation, tests/lint/Detekt/schema/diff-check/device/UI/battery/CI/release/
rollout validation, activation, push or new dev/v10 integration. Protected paths/drafts preserved.
Candidate starts at fee73e09c4; main dev/v10 remains 13f1a99871.

Independent review-only background_steps_review inherits all original messages and requirements;
no recursive delegation. It reviewed the exact bounded retention diff, including authored test sources.
The new scheduling cadence/bounds were explicitly asked via a question tool under AGENTS.md;
confirmation is pending. No reply is inferred. Dependent scheduler/worker/budget contracts are
unapplied. The existing native aggregate importer has real semantic revisions, not fabricated WAL
ordinals; the separately pending generic native-product proof question remains open.
See ../../background-steps/2026-09-27/IMPLEMENTATION_TRACE.md and SCHEDULING_CONTRACT.md.

## 34. Automatic tracking integration request (2026-09-27)

Original user text:

> **Complete automatic tracking integration.** Keep trigger authority separate from capture, use the same collection flow, and expose unsupported automatic capabilities accurately.

Full original user messages and supplied AGENTS.md apply. IMPLEMENTATION_ONLY continues: bounded
production/test-source authorship and static review only. No Gradle, compilation, tests/lint/Detekt/
schema/diff-check/device/UI/battery/CI/release/rollout validation, activation, push or new dev/v10
integration. Preserve protected paths/drafts. Candidate starts at 047423c6a3; main remains 13f1a99871.

Independent review-only automatic_integration_review inherits all user messages/requirements,
inspects approved product/runtime decisions, relevant current code and exact diff/test source;
no recursive delegation. Approved desired-intent and sibling-isolation corrections can proceed.
New detailed capability presentation and concrete CONTROL owner/expiry composition were asked
through the question tool as required by AGENTS.md; no answer received or inferred. 48h lifetime
itself stays settled. Dependent contracts remain unapplied; no blanket automatic support claimed.
See ../../automatic/2026-09-27/IMPLEMENTATION_TRACE.md and CONTRACT_DECISIONS.md.

## 35. Collection controls request (2026-09-27)

Original user text:

> **Finish collection controls across every entry point.** Connect Start, End and Turn off tracking consistently through UI, notifications, widgets and shortcuts; finish permission-race and user-stop fencing.

All original messages and supplied AGENTS.md apply. IMPLEMENTATION_ONLY continues: bounded
production and required test-source authorship, static inspection/review and local checkpoint only.
No Gradle, compilation, tests/lint/Detekt/schema/diff-check/device/UI/battery/CI/release/rollout
validation, activation, push or new dev/v10 integration. Protected paths/drafts unchanged.
Candidate starts at b3dc968a1b; main remains 13f1a99871.

Review-only collection_controls_review inherits original messages, approved decisions, current
code/diff and authored tests; no recursive delegation. Review found the explicit-Start-after-exit
mutex deadlock, grants restoring Off, unfenced manual permission repair, stale widget/notification
actions, End retiring background-only host, missing Turn off, incomplete background re-enable and
feature-scoped suppression. Bounded settings intent/capability and exact-current resume fixes
implement approved requirements. New shared control placement/wiring and permission continuation
contract were asked via question tool under AGENTS.md; no answer received or inferred. Dependent
contracts remain unapplied. Whole collection controls and assembly stay open. See
../../collection-controls/2026-09-27/IMPLEMENTATION_TRACE.md and CONTRACT_DECISIONS.md.

## 36. Temporary Location consumer request (2026-09-27)

Original user text:

> **Unify temporary Location consumers.** Route My location and games through independent shared-owner leases with real freshness requirements and no unintended tracking persistence.

All original messages above, follow-ups and supplied AGENTS.md apply. IMPLEMENTATION_ONLY
continues: bounded production and required test-source authorship, static inspection/review and
local checkpoint only. No Gradle, compilation, tests/lint/Detekt/schema/diff-check/device/UI/battery/
CI/release/rollout validation, activation, push or new dev/v10 integration. Protected paths/drafts
preserved. Candidate starts at ecf8c73de9; main remains 13f1a99871.

Review-only temporary_location_review inherits original messages, approved decisions, relevant
current code, exact diff and authored test sources; no recursive delegation. Whole-flow trace found
generic live-session game routing, separate UI providers, process-background closure, synthetic
game observation time, map coordinate/accuracy defects and an unmanaged persistent sunrise provider.
Approved honest-data requirements support bounded coordinate/accuracy/timestamp corrections.

The concrete shared lease/host/freshness pre-edit contract and map/sunrise lifetime changes were
asked through the question tool as required by AGENTS.md; no answer received or inferred.
Dependent architecture/UX/data contracts are unapplied. Shared durable registration cannot serve
feature-only demand via a dummy purpose; it needs a genuinely ephemeral delivery path. Existing
general startup/storage readiness differs from collection suppression; games need no new readiness
framework and must not acknowledge tracker stop. Full temporary Location unification and the
assembly gate remain open. See ../../temporary-location/2026-09-27/IMPLEMENTATION_TRACE.md,
CONTRACT_DECISIONS.md and REVIEW_RECEIPT.md.

## 37. Retained data authority request (2026-09-27)

Original user text:

> **Complete retained data authority.** Reconcile consent, correction, retention, erase, replay and restore behavior while preserving independently authorized sibling purposes.

All original messages above, follow-ups and supplied AGENTS.md apply. IMPLEMENTATION_ONLY
continues: bounded production and required test-source authorship, static inspection/review and
local checkpoint only. No Gradle, compilation, tests/lint/Detekt/schema/diff-check/device/UI/battery/
CI/release/rollout validation, activation, push or new dev/v10 integration. Protected paths/drafts
preserved. Candidate starts at 15f639b68d; main remains 13f1a99871.

Review-only retained_authority_review inherits all original user messages, approved decisions,
current selected code/diff and authored tests; no recursive delegation. Code trace found carried
revoked capture consent blocked by current-policy equality, Cell cleanup blocked by generic sibling
heartbeat, retiring radio grant equality, missing exact Activity/Steps sibling-purpose settlement,
Location Off deleting history/current consent gating reads, missing Location capture-association
actions, CONTROL 48h owner/expiry and full restore gaps. The real Pressure persistence barrier is
DI-bound; the unavailable fallback concern was rejected by code trace.

Approved carried-consent/source independence supports bounded source-local cleanup corrections
and retiring radio grant temporal checks. Static review required preserving the newer shared Cell
heartbeat after successful erase/retention; that correction and regression sources are authored.
The same bounded monotonic publication correction is authored for existing Wi-Fi and Activity
erase/retention, with actual mutation and correction/replay regression sources. Final root source
inspection identified the Activity case after the earlier accepted diff; affected independent static
review accepted its production/test sources and accurate documentation with no blocking finding.
New historical Location Off/read behavior was asked via question tool under AGENTS.md because
current code conflicts with the plan; no answer received or inferred. The earlier full restore
target and automatic owner contracts remain unanswered and dependent changes are unapplied.
Full retained authority and the assembly gate stay open. See ../../retained-authority/2026-09-27/
IMPLEMENTATION_TRACE.md, CONTRACT_DECISIONS.md and REVIEW_RECEIPT.md.

Followup from committed cf80908b56: approved carried-consent semantics also correct the six native
ambient Steps day/public fact/timeline and current availability/materialization equality checks.
Two reader sources and one existing Room fixture/test source are authored; exact source/purpose/
epoch/persistence, historical wall boundaries, retention/count-domain/correction and cursor checks
remain. Actual public/day corrected-lineage, future/late grant and no-fact materialization cases are
accepted by independent static review without blocking findings. Imported source-fence regrant
checks then received the same settled carried-consent correction in actual import/history paths;
the existing erase/regrant test now exercises before/after quality changes, future grant rejection
and deleted-day replay exclusion. Independent static review accepted the combined four production/
two test sources plus documentation without blocking findings; full historical monotonic demand
composition remains open. No tests/compilation/validation or new design is inferred.

## 38. Schema and dependency wiring request (2026-09-27)

Original user text:

> **Converge the schema and dependency wiring.** Assemble the final entities, DAOs, indexes, migrations, triggers, invalidation and DI; preserve released and unknown databases.

All original messages above, follow-ups and supplied AGENTS.md apply. IMPLEMENTATION_ONLY
continues: bounded production/test-source authorship, static inspection/review and local checkpoint
only. No Gradle, compilation, test/lint/Detekt/schema/diff-check/device/UI/battery/CI/release/rollout
validation, activation, new dev/v10 integration or push. Candidate base a0b39fd96c; main 13f1a99871.
Protected root paths and frozen/source drafts remain preserved; selected V1 families remain.

Independent review-only schema_wiring_review inherits original messages/decisions/current code and
exact scoped diff/authored tests; no recursive delegation. It confirmed typed Pressure sentinel
mismatch, weaker index shape admission and missing actual read dependencies. Existing approved
preservation/trusted-read contracts authorize bounded corrections with existing verifier/list
patterns. No new entity, schema version, dependency or production ownership architecture introduced.

Actual Room observer and file-preservation regression sources are authored, not run. Production
DI uses the guarded singleton database; no missing selected binding found by static trace. Whole
assembly is not frozen: pending purpose/control/temporary Location/restore contracts, generated
v28, migration execution and compiled Hilt/Room proof remain open. Selected preflight is not full
owner-trigger-body authentication; configurable fileBuilder is test-used and not guarded by default.
See ../../schema-wiring/2026-09-27/IMPLEMENTATION_TRACE.md and REVIEW_RECEIPT.md.

## 39. Session organization request (2026-09-27)

Original user text:

> **Implement session organization.** Add retrospective sessions, names/activity labels and editable closed intervals using the planned annotation model.

All original messages, follow-ups and supplied AGENTS.md apply. IMPLEMENTATION_ONLY continues:
production/test-source authorship, static inspection/review and local IMPLEMENTED_UNVALIDATED
checkpoint only. No Gradle, compilation, tests/lint/Detekt/schema/diff-check/device/UI/battery/CI/
release/rollout validation, activation, new dev/v10 integration or push. Candidate base5b57140041;
main13f1a99871. Protected root paths/source drafts remain preserved and selected V1 families remain.

Implement the settled DATA_GAP_CLOSURE one-annotation local organization behavior and
PRODUCT_GAP_CLOSURE History create/edit/removal flow. Retrospective overlaps and empty periods
are legal; live name/label only; saved IANA zone/explicit DST resolution; metadata never acquires
source facts/consent/erase/export/reward authority. Retention reuses approved trip cutoff; full-clear
reuses epoch/lease. Existing recording browsing/actions remain reachable. No new dependency,
second lifecycle, output format or restore decision is introduced.

Independent review-only session_organization_review inherits original messages/decisions and exact
scoped source/test diff; no recursive delegation. Concrete foundation/UI/paging/privacy findings
were corrected and regression sources authored. Reviewer reports no remaining blocker for scoped
local/native/legacy annotation source. Whole structural source contents, portable organization
selectors/import/export/file assembly, generated schema and executed evidence remain separate.
See ../../session-organization/2026-09-27/IMPLEMENTATION_TRACE.md and REVIEW_RECEIPT.md.

## 40. Unified history request (2026-09-27)

Original user text:

> **Implement unified history.** Finish bounded day/range queries, paging, calendar, timeline and detail views across native, background and imported observations.

All earlier original user messages/follow-ups/decisions and supplied AGENTS apply. IMPLEMENTATION_ONLY:
production/test-source authorship, static review and local IMPLEMENTED_UNVALIDATED checkpoints only;
no Gradle/compilation/tests/lint/Detekt/schema/diff-check/emulator/device/UI evaluator/battery/CI/release/
rollout validation, activation, final input freeze, new dev/v10 integration or push. Candidate
codex/source-native-map-context-root base835302e86c; main13f1a99871 unchanged. Protected paths preserved.

Global browse contract/consumed-frontier merge, bounded native/imported key discovery and selected/
sessionless source seams are authored. Independent review-only unified_history_review inherits full
original context and inspected code/diff/test sources. Concrete contract/membership/collision/time
findings corrected; no executed evidence. Whole facade/UI/retained source integration remains open.

Two questions were presented through request_user_input_async: historical Location visibility after
collection-off/permission loss, and approve exact local opaque ownership index for Activity/Pressure
imports instead of whole-history scans. Both recommendations await explicit answers; elapsed time
is not approval. Supplied AGENTS requires confirmation for changed storage/behavior decisions.

Resume from ../../unified-history/2026-09-27/IMPLEMENTATION_TRACE.md and REVIEW_RECEIPT.md. Never treat
API defaults, coarse seeds, authored tests or static foundation acceptance as implemented full history,
source/action authority, device readiness, or approval to execute the final assembly gate.

## 41. Metrics, actions and effects request (2026-09-27)

Original user text:

> **Complete metrics, actions and effects.** Provide truthful summaries for all six sources, exact Steps totals, original Activity/Pressure bounds, supported actions and correction/deletion retraction.

All earlier original user messages, supplied AGENTS and approved decisions remain applicable.
IMPLEMENTATION_ONLY: source/test authorship, static review and local IMPLEMENTED_UNVALIDATED
checkpoints only. No Gradle/compile/tests/lint/Detekt/schema/diff-check/emulator/device/UI evaluator/
battery/CI/release/rollout validation, activation, input freeze, new dev/v10 integration or push.
Candidate codex/source-native-map-context-root based2c307247e6; main13f1a99871 unchanged. Protected
paths, source refs and excluded drafts preserved.

Unknown/malformed metric rejection and original native Activity/validated closed Pressure header
mapping authored. Independent review-only metrics_actions_effects_review inherits original full
context and inspected code, exact diff and unexecuted test sources; no blocking regression found
for narrow repairs after requested active/STOPPING/negative-start cases were authored. Exact Steps
numeric/goal correction machinery remains preserved, not newly executed/proven.

Complete qualified summaries/consumers, portable native original bounds, exact source actions and
non-Steps prospective effects/retraction remain incomplete. Two new tool questions await explicit
answers: reject unsupported periods vs expose actual period in expanded result API; separately
qualify missing/open original recording bounds while retaining independent observations vs omit
those records from summaries. Prior historical Location and exact ownership-index questions remain
pending. Elapsed time is not approval. Do not claim the full request is complete or fully blocked by
these questions; continue authorized independent implementation while awaiting required choices.

Resume from ../../metrics-actions-effects/2026-09-27/IMPLEMENTATION_TRACE.md and REVIEW_RECEIPT.md.
Never infer final convergence, on-device readiness, provider activation or integration approval from
static acceptance, authored tests or a local checkpoint.

## 42. Optional map enrichment request (2026-09-27)

Original user text:

> **Finish optional map enrichment.** Connect qualified Location context to Wi-Fi/Cell displays, preserve uncertainty and gaps, and invalidate results when either source changes.

All earlier original requests, supplied AGENTS and approved decisions remain applicable. Phase is
IMPLEMENTATION_ONLY: source/test authorship, static review and candidate IMPLEMENTED_UNVALIDATED
checkpoints only. No Gradle/compile/executed tests/lint/Detekt/schema/diff-check/device/UI/battery/CI/
release/rollout validation, input freeze, provider/writer activation, new dev/v10 integration or push.
Main13f1a99871 unchanged; candidate starts616619e32b. Protected paths, refs and drafts preserved.

Question: May I use Wi-Fi's exact-or-bracketed Location fixes within30 seconds, Cell's preceding fix
within60 seconds, and retain coarse fixes with their accuracy shown instead of adding a100m cutoff?
Both sources independently qualify for the same purpose and compatible ownership/clock; missing
context leaves radio history intact. Exact answer: Approve the proposed join rules (Recommended).

Question: May I use one read-only enrichment API in stats/api implemented in stats/data, authenticating
Location receipts/purpose grants and native radio facts in a bounded paired Room snapshot with
explicit gaps/limits, reusable by history/maps and never starting tracking? Exact answer:
Use a shared source-owned reader (Recommended). These specific approvals supersede the map timing/
accuracy recommendation's prior proposal status, not unrelated outstanding decisions.

The earlier native observed-point display-placement question remains unanswered. Prior historical
Location collection-off/permission visibility, ownership-index, period/open-interval and restore
questions remain unanswered; elapsed time is not approval. Continue authorized independent work.

Review-only map_enrichment_review inherits original messages, code/diff, test-source inventory and
approved decisions. Matcher/observer/actual Room test sources received narrow static acceptance;
reader and native rendering/cache routing remain incomplete. No complete retained Location facade
exists; visibility-only or checksum-only conversion cannot qualify joins. Resume from
../../optional-map-enrichment/2026-09-27/IMPLEMENTATION_TRACE.md and REVIEW_RECEIPT.md.

Continuation from d685b73664: approved shared native enrichment API and canonical-content helper/test
sources authored. Both canonical writers reuse the identical sample checksum; no full authority
proof, reader implementation, DI, renderer, schema change or provider activation is inferred.
Root and independent review confirmed an exact registration/sequence/purpose binding gap after WAL
pruning: canonical content/compact retention scope proof cannot reconstruct the complete original
authority fingerprint. New question asks approval for versioned full accepted-receipt and each
purpose-grant integrity proof, written atomically by the qualified writer, preserving older records
as unavailable unless original evidence survives; alternative is original-event-only enrichment
with older gaps. No answer received, no dependent storage/migration/proof-writer implemented.
Existing shared API approval is not approval to invent missing durable proof authority.

Ambient local facade authority/owner/lineage gaps and portable export's post-snapshot lineage race
are confirmed. Existing maintenance accepts revoked retention authority for cleanup; it is not a
display qualification helper. No native ambient DELETED revision exists; erase purges families and
uses deletion markers/replay footprints. Preserve independent surviving purposes. Whole task and
assembly gate remain open; all tests unexecuted under IMPLEMENTATION_ONLY.
