# Assembly and proof gap closure

2026-09-26. Planning within **IMPLEMENTATION_ONLY**. This closes the assembly design; it does not freeze inputs, validate code, activate providers, integrate, or authorize new product decisions. Read COMPLETE_IMPLEMENTATION_PLAN.md first. Current input hashes are in PLAN_CLOSURE_INPUTS.json. Earlier inventories remain historical receipts.

## 1. What is selected, and what is preserved

MAIN remains clean local `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`. Planning ROOT was `04487c78be925b2c3bdaafe200b627b1f259ec4a` when the receipt was captured. No checkout contains the full proposed implementation.

| Input | Planned use | Exclusions and reconciliation |
| --- | --- | --- |
| ROOT: protected native radio references, resolver, ambient DI | Reuse the exact native/imported distinction and existing shared machinery | Replace the unapproved blanket 100m context cutoff with the final accepted-point/uncertainty policy; finish query/render/invalidation rather than treating a resolver as a feature |
| LOCATION `1c17c27e…`, ten draft files | Reuse sole-writer manual/automatic lane and first-sequence-zero correction; adapt accepted provenance atomically | Replace draft proof gaps with common authenticated sample receipt and purpose associations; include passive system delivery and recovery |
| RADIO: twenty-two drafts over MAIN | Reuse generation2/owner4 manual+automatic contract; retain immutable generation1/owner2 readers | Close fact/manifest/generation equality and historical per-item purpose qualification in admission, projectors, maintenance, history and transfer; preserve one immutable minimized radio delivery. Preferred transition activation remains excluded |
| UI `487a2fc9…`, ten drafts | Reuse source-aware nonLocation composition and always-available Stop | Replace separate ambient card/polling and distance-first mixed-history assumptions with unified stored-day/organization readers |
| AUDIT `4facc94f…`, six committed corrections | Reconcile release evidence, retirement barrier, sole Location owner guard, Battery Saver behavior, Activity elapsed age and truthful restrictions | Release-evidence fixes do not imply release or resolve CONTROL retention. Source evidence takes precedence over stale audit wording |
| AUDIT: eight further dirty UI files | Review against UI candidate and preserve useful source-aware recent-entry state/tests | `RecentTripsUiState`, Dashboard, progress cards, Route/ViewModel, strings and tests overlap UI ownership; choose one source-aware implementation and one set of qualification rules, never apply both sequentially without reconciliation |
| LIFECYCLE `f7865631…`, twenty-six commits | Extract the latest coherent exact lifecycle proof families described below, with their relevant tests | Do not cherry-pick twenty-six evolving patches wholesale. Do not copy the undefined Wi-Fi receive-time identifier, old radio generation assumptions or per-point query fanout |
| Older audit branch, broker/accounting refs | Keep historical alternatives; retain already-integrated owners/accounting | No speculative replacement or reimplementation from old registry status |
| Protected root files, frozen importer/awards drafts, unrelated work | Preserve original refs and files | No wholesale application, deletion, cleanup or staging |

### Lifecycle extraction is a defined implementation task

The twenty-six immutable commit IDs/titles are recorded in the JSON receipt. Select the **final state of each family**, trace it against current callers, and author one coherent reconciled slice per family:

1. `SourceLifecycleProof` contract/catalog/coordinator/runtime adapter and exact source-WAL carrier authentication, including first event sequence0 and all six sources.
2. Pressure correction history, retained identities, selected authority, collision rejection and bounded truncation. Keep direct hPa and correction/retraction truth; no invented elevation.
3. Event/radio retention proof and scope authority, updated for both immutable radio contracts and current source-native references. Retained old facts do not require today's writer ownership; new writes do.
4. Location receipt integrity, protected canonical handoff, accepted sample/provenance and retention. Merge with LOCATION and the new passive-purpose contract under one writer.
5. Erase/replay/restore authority, deletion fences, drained ownership and recovery; update every dependent table, clear path and diagnostic receipt.
6. Bounded history/query adapters, Room/migration/owner triggers/invalidation and DI/workers. Lifecycle verification must use batch reads for lists/maps, never a query per observation.

The old full diff has not been claimed reviewed line by line. Reviewing each selected final source/test family is normal implementation work, not an unresolved architectural choice. If that review uncovers behavior contradicting the approved plan, record the specific conflict and obtain a decision before changing the design.

## 2. Close every global assembly prerequisite

The frozen historical WORK_ITEMS registry is a scope reference, not a live implementation verdict. Its `WI-ASSEMBLY-GATE-001` has twenty-five prerequisites. All remain required; the four-source emphasis does not remove existing Activity, Pressure or cross-source obligations.

| Stable prerequisite | Required implementation and test-source outcome | Responsible slice |
| --- | --- | --- |
| WI-STEPS-MANUAL-001 | Direct Steps solo/mixed sessions, zero baseline, count-domain/reboot continuity, corrections, Stop and qualified reads | Providers + data |
| WI-STEPS-AUTOMATIC-001 | Same Steps path under independent supported automatic trigger, trigger-only data excluded from capture | Runtime + CONTROL |
| WI-STEPS-AMBIENT-001 | Consented system recording continuity, recorded-day/count-domain de-duplication and recovery independent of sessions | Providers + data |
| WI-PRESSURE-VERTICAL-001 | Direct hPa solo/mixed manual/automatic capture, correction-safe day/range/detail/repair state, actions and portable data | Providers + product |
| WI-LOCATION-VERTICAL-001 | Manual/automatic sole writer, passive sessionless baseline, accepted proof, map/query/export and optional approved active baseline | Providers + data |
| WI-ACTIVITY-VERTICAL-001 | Captured Activity solo/mixed manual/automatic, separate CONTROL evidence, direct source history and no fabricated motion duration | Providers + CONTROL + product |
| WI-WIFI-VERTICAL-001 | Callback/native records, immutable contract readers, bounded optional live attempt, ambient consent and qualified empty/stale outcomes | Providers + data |
| WI-CELL-VERTICAL-001 | Callback/native records, SIM/radio capability limits, bounded optional live refresh, immutable readers and ambient consent | Providers + data |
| WI-SCHEMA-CONVERGENCE-001 | One entity/DAO/migration/trigger/invalidation/erase schema union, preserved v27, final marker and development containment | Serialized schema |
| WI-AUTHORITY-PURPOSE-001 | Explicit capture/background/CONTROL/feature purpose and effective epoch; no inferred consent or cross-purpose entitlement | Runtime + data |
| WI-PURPOSE-PUBLICATION-001 | Atomic desired-policy publication, exact effective plan/receipt and durable admission; revocation fences late work | Runtime + data |
| WI-BROKER-PROVIDER-OWNERSHIP-001 | One physical owner, one Location writer, joined purpose demand, acknowledged retirement and no duplicate handoff | Runtime |
| WI-SOURCE-CALLER-GUARD-001 | No UI/worker/legacy bypass, hidden helper acquisition or source coupling; architecture tests enforce call authority | Runtime + host test source |
| WI-QOS-BATTERY-001 | One versioned five-level policy, stable useful floors, explicit source-specific mode/cost rules and no competing legacy acquisition actuators or cost optimizer | Adaptive policy |
| WI-LIFECYCLE-RUNTIME-001 | Scoped session/host controls, late permission-result fences, user-stop rearm/admission gate, start/stop/cancel/race/kill/reboot/service legality and truthful recovery | Runtime |
| WI-LIFECYCLE-PRODUCT-PROOF-001 | Exact observation-to-product proof through deletion/retention/correction, with bounded readers | Data |
| WI-STEPS-NUMERIC-001 | No cumulative double counts, fabricated fractions or boot/reset overflow; exact qualified totals and interval limits | Data |
| WI-CROSS-SOURCE-RECOVERY-001 | Durable replay/import/restore idempotency; surviving source independence and no resurrection after erase | Data + runtime |
| WI-CROSS-SOURCE-PRIVACY-001 | Local-only CONTROL lifetime, explicit ambient consent, payload-free diagnostics and exact data/export erasure scope | Data + product |
| WI-SHARED-FILE-ASSEMBLY-001 | One implementer at a time owns schema, catalog/DI, coordinator/service, shared readers and strings | Coordinator |
| WI-SOURCE-ACTIONS-EXPLANATION-001 | Every visible action has a supported dispatcher/target; actionable limited states and precise Stop/export/delete semantics | Product |
| WI-SHARED-HISTORY-DAY-RANGE-001 | Complete indexed range discovery, one global paging frontier and explicit invalidation dependencies, stored-zone days, mixed/solo details and override-before-range annotation reads | Data + product |
| WI-INTEGRATION-SCENARIOS-001 | Test source for solo/subset/join-leave/policy/lifecycle/privacy scenarios, with eventual evidence categories below | All slices |
| WI-UI-SURFACES-001 | Dashboard, Tracker, timeline/calendar/detail, maps, stats, settings, files, notifications/widget/tile, locales/accessibility | Product |
| WI-NONSTEPS-EFFECTS-001 | Qualified eligible facts only, exact-once effects, correction/delete retraction, existing earned-receipt compatibility | Data + product |

A prerequisite is satisfied by current implementation plus relevant authored checks and later actual proof, not by a completed-looking registry label. No additional automation strategies, ambient Activity/Pressure collection, pressure-derived elevation, AP/tower identity products or unrelated feature work is introduced.

## 3. Serial assembly order and file ownership

1. Record approval of the current plan and any explicit alternative. Lock the source/purpose/mode matrix, organization semantics, profile version and transfer behavior. Existing five levels/source freedom/session meaning need no renewed decision.
2. Assemble authority/lifecycle/data foundations from selected inputs; repair sequence0, radio generation equality, per-item historical purpose qualification, useful-data floors and retained proof before new readers depend on them. Author meaningful focused tests now, without running them.
3. Serialize schema/DAO/migration source changes for authenticated receipts, purpose associations, one annotation table and versioned policy metadata. Align every erase/retention/invalidation/restore path at the same time. Do not generate Room schema yet.
4. Assemble central adaptive intent/resolver and provider transport/host lifetimes; retain explicit consent and legal recovery limits. Include optional explicitly enabled sessionless passive-radio hosting alongside active Location using the same demand-owned service; this never grants active scan attempts. Legacy paths may translate settings or observe state but cannot remain independent acquisition actuators. Preserve their historical processing/UI responsibilities; derive live state from existing acquisition and distinguish it from host residency.
5. Implement shared bounded history/organization queries, source summaries and optional map enrichment; finish Activity/Pressure/effects obligations using the same qualification rules.
6. Implement current product surfaces and file pipeline from the concrete Product/Data contracts, including typed source bundles using existing job/entry receipts, original-job epoch/retention fences, import failure/cancellation/deletion UX and precise End/Turn off tracking scopes. Keep underlying observation authority independent of organization metadata.
7. Complete source/test authorship for all twenty-five prerequisites. Static review all selected source families and the final cross-module call graph. Commit each coherent step with `IMPLEMENTED_UNVALIDATED`.
8. Only after all planned production and test-source slices exist, freeze one exact convergence input set and transition to the separately recorded validation sequence.

Parallelism is by disjoint source files; coordinator serializes AppDatabase/schema/migrations, SourceProductFactories/DI, TrackerService/coordinator and shared history/strings. No recursive delegation. Current main and candidate drafts remain protected until their content has been accounted for.

## 4. Schema and rollback decision

Preserve released v27 schema, facts and files. The repository's existing path uses development v28 and `MIGRATION_27_28`; common open paths require the final owner/schema marker and preserve unknown development files. Consolidate into that unshipped target only while its unshipped premise is supported by the project's release evidence. A Git tag/branch alone cannot prove no sideloaded v28 exists.

If evidence shows a distributed v28 database that must be supported, do not reshape it or reset it: change the target to the next version and write an explicit migration from the actual shipped schema(s). This is the selected preservation rule; exact deployed schema evidence, if any, is an external input, not a license to guess. An unrecognized database stays preserved with a recovery action.

Migration backup, regular raw database export, collected-data deletion and full restore have different scopes. Maintain existing deletion-pending sidecars/backup fences and empty-target restore restrictions. An archive is not permission to restore runtime consent, ownership or CONTROL as current local authority.

Technical rollback retires/drains an affected registration and fences writes, retains admitted observations and compatible readers, and restores a proven sole owner where possible. It never downgrades a v28 database to v27 or erases user data to make startup succeed.

## 5. Exact evidence boundary and final proof

No commands below run during this planning or implementation-only phase. Written tests and static traces are **IMPLEMENTED_UNVALIDATED**.

After complete assembly, the freeze receipt records exact final commits/draft dispositions, source/purpose/contract/generation matrix, schema/migration/test inventories, capability limits, rollback and checks. Then:

1. Run the coordinated production/test/Hilt/Room compilation and schema convergence batch; review generated final schema, preserve v27 JSON and run focused contract, migration/reopen/recovery tests. `./gradlew.bat ciUnitTest` is the repository-owned JVM/host aggregate; `./gradlew.bat ciCheck --continue` is the full host quality gate. Fix actual failures and rerun the complete required gates on the final corrected input.
2. Run relevant real Room/connected tests in existing modules, including populated migration, source recovery, protected admission, history/organization/export and deletion. Use existing Gradle tasks, one representative emulator/API configuration and recorded fixture data. Host gates do not establish Android transactions or provider behavior.
3. Before changing preferred provider/writer bindings, record the separately required bounded local candidate/device activation authority. The earlier automatic review rejection of V1-to-V2 activation remains binding. Name the exact sources/purposes/generations, dedicated test installation and rollback. Existing `debug`/`dev` application-ID suffixes can isolate fixtures; never assume an installed real-data app is disposable.
4. Physical proof: each solo L/S/W/C path, existing Activity/Pressure verticals, representative combinations, supported automatic triggers, sessionless baseline and join/leave transitions. Trace actual callback -> durable event -> canonical fact -> production query -> visible product/export. Record API/OEM/GMS/sensor/SIM/permission capability and expected unsupported states. Contract tests cover all fifteen nonempty four-source subsets plus empty and supported ambient subsets; device runs exercise distinct mechanisms rather than repeating every combinatorial UI path.
5. Exercise screen-off, saver, charging, permission/source revocation, no-GMS capability where available, process loss/reboot, stalled registration/retirement, expired hint, empty/stale callback, storage/write failure and stop/race recovery. Include system user-requested stop followed by scheduled/passive entry, feature-only actions, Steps history windows crossing suppression, late permission grant after Off, unavailable-source Off controls and baseline-only widget/notification actions. Unsupported device scenarios remain explicitly unproven rather than simulated as physical success.
6. Assess energy/detail on a repeatable bounded workload, recording enabled modes, request/delivery/wake/write counts and available device energy measurements. Compare modes and useful admitted evidence, not guessed percentages. No permanent telemetry or new measurement subsystem is needed.
7. A **fresh independent review-only agent** receives every original user message/follow-up, approved decisions, exact assembled code/diff, authored tests and actual results. It traces full requirement compliance, regressions, unnecessary complexity and remaining proof gaps. This planning review cannot substitute for that gate.
8. Address concrete findings, rerun affected and required complete gates, rebase onto current clean local `dev/v10`, verify the final input and locally integrate only once ready. Preserve unrelated changes and source receipts; no push, CI trigger, deployment or publication is authorized here.

Source references for these existing gates and boundaries: `AGENTS.md`; `handover/2026-09-15/{README_START_HERE,HANDOVER_PROMPT}.md`; `WORK_ITEMS.json` assembly dependencies; `build-logic/convention/.../RootVerificationConventionPlugin.kt` (`ciUnitTest`, `ciCheck`, schema drift and release SQLite); `app/build.gradle.kts` debug/dev variants; `AppDatabase.kt`, `DevelopmentV28DatabaseContainment.kt`; actual Android tests in core/base and app. DELIVERY_PLAN.md retains the detailed one-based inspected-code anchors. The input JSON records the current refs rather than transferring older test results.
