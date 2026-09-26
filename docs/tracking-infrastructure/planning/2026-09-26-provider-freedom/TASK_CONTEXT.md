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
