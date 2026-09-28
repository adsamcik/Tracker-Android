# Tracking Infrastructure Work-Item Tracking

Last updated: 2026-09-27

## Current provider completion checklist

These marks describe source implementation and static review only. The assembly gate remains
open; validation and integration are deferred. The historical registry below is preserved.

- [x] Close settled design decisions: CONTROL retention is 48 hours; optional passive Wi-Fi/Cell
  notification hosting is approved; export produces only the selected format. Backup restore
  target restrictions remain a separate unresolved decision in `planning/2026-09-26-provider-freedom/DECISION_CLOSURE.md`.
- [x] Reconcile implementation inputs: candidate `dedc7df8f1` records final selected versions,
  all overlaps, original refs and exact preserved live drafts. See
  `reconciliation/2026-09-27/README.md` and `SELECTION_MANIFEST.json`.
- [ ] Complete source lifecycle proof: authenticate observation identity, registration,
  authorization, sequence and product lineage consistently across all six sources. In progress
  on `codex/source-native-map-context-root`; includes supported sessionless ambient purposes.
- [ ] Fix shared Wi-Fi/Cell admission: qualify each item by its destination's historical authority
  and age, preserve valid background subsets, and prohibit backward consent. Versioned contract
  confirmation and end-to-end wiring remain open; see `shared-radio/2026-09-27/CONTRACT_DECISION.md`.
- [x] Complete five-level configuration across sources: production and focused test sources
  authored and independently source-reviewed, **IMPLEMENTED_UNVALIDATED**. See `five-level/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Implement source-specific power behavior: independent Steps reporting/request-ownership
  fixes and focused test sources authored; durable intent budget, carried cooldown and profile
  freshness remain open pending the recovery contract. See `source-power/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Finish shared provider and host ownership: bounded claimant, retirement, capture/host type and
  control-state fixes authored; optional radio hosting and feature Location leases remain pending
  contract confirmation. See `provider-host/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Complete passive background Location: source trace and concrete system-residency/receiver/
  recovery contract prepared; confirmation and dependent implementation remain open. See
  `passive-location/2026-09-27/CONTRACT_AND_TRACE.md`. Process-bound passive callbacks do not
  establish system-rearmable delivery.
- [ ] Complete background Steps execution: supported provider import has no production scheduler;
  execution permission, finite recovery and predecessor drain wiring remain open. A retention
  high-water correction and focused regression sources are authored, **IMPLEMENTED_UNVALIDATED**.
  See `background-steps/2026-09-27/IMPLEMENTATION_TRACE.md` and `SCHEDULING_CONTRACT.md`.
- [ ] Complete automatic tracking integration: shared capture/trigger authority traced; saved-intent,
  late-permission and denied-Location sibling corrections authored, **IMPLEMENTED_UNVALIDATED**.
  CONTROL owner/48h enforcement, capability presentation and remaining automatic source bindings
  stay open. See `automatic/2026-09-27/IMPLEMENTATION_TRACE.md` and `CONTRACT_DECISIONS.md`.
- [ ] Finish collection controls across every entry point: source settings permission-race and
  explicit-Start/user-exit deadlock corrections authored, **IMPLEMENTED_UNVALIDATED**. Shared
  capture-scoped End, Turn off tracking, exact external action identity and pending manual-repair
  continuation remain open. See `collection-controls/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Unify temporary Location consumers: valid map coordinates, unknown accuracy and actual game
  observation time corrections authored, **IMPLEMENTED_UNVALIDATED**. Independent shared-owner
  feature leases, real observed freshness and map/sunrise lifetime confirmation remain open.
  See `temporary-location/2026-09-27/IMPLEMENTATION_TRACE.md` and `CONTRACT_DECISIONS.md`.
- [ ] Complete retained data authority: carried capture-consent/retiring radio grant checks and
  Cell/Wi-Fi/Activity shared-heartbeat and native/imported Steps carried-consent corrections authored,
  **IMPLEMENTED_UNVALIDATED**. Purpose settlement,
  historical Location semantics, CONTROL expiry and full restore remain open. See
  `retained-authority/2026-09-27/IMPLEMENTATION_TRACE.md` and `CONTRACT_DECISIONS.md`.
- [ ] Converge the schema and dependency wiring: typed Pressure trigger sentinel, exact selected
  index admission and count-domain/retention-proof invalidations corrected; actual Room observer
  and database-preservation regression sources authored, **IMPLEMENTED_UNVALIDATED**. Selected
  entity/DAO/migration/DI wiring traced. Pending contracts, final input freeze, generated schema,
  migration execution and compiled DI proof remain open. See
  `schema-wiring/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [x] Implement local session organization: retrospective overlapping groups, optional names/activity
  labels, editable closed intervals, live metadata-only edits, paged History/editor/calendar,
  grouping-only removal and epoch/revision/retention/full-clear fences authored and independently
  source-reviewed, **IMPLEMENTED_UNVALIDATED**. Whole structural contents and portable annotation
  import/export remain separate assembly work. See `session-organization/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Implement unified history: global range/key/merge contract, bounded native/imported candidate
  discovery and exact/sessionless source-reader seams authored, **IMPLEMENTED_UNVALIDATED**.
  Production facade/paging/calendar/timeline/detail wiring and complete retained Location proof remain
  open. Historical Location visibility and exact local ownership indexing await tool-question answers.
  See `unified-history/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Complete metrics, actions and effects: unknown/malformed metric rejection and original native
  Activity/validated closed Pressure header repairs authored and independently statically reviewed,
  **IMPLEMENTED_UNVALIDATED**. Exact Steps numeric/goal retraction machinery traced and preserved.
  Qualified six-source summaries/consumers, period/open-interval contracts, native portable bounds,
  source action selectors/dispatch and non-Steps effects/retraction remain open. See
  `metrics-actions-effects/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Finish optional map enrichment: approved temporal/scope/uncertainty matcher and native
  fact/receipt/authority observer coverage authored and independently source-reviewed,
  **IMPLEMENTED_UNVALIDATED**. Shared authenticated paired reader, native rendering, both-input
  layer/cache routing and explicit gap/limit presentation remain open. Display choice awaits
  tool-answer confirmation; see `optional-map-enrichment/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Finish settings and product surfaces: widget/app locale and existing-action accessibility
  repairs with focused test sources authored, **IMPLEMENTED_UNVALIDATED**. Unified source controls,
  onboarding Pressure/selection, guarded recoveries and qualified live presentation await concrete
  confirmation. Tracker/statistics history, source-aware notifications/widgets and retained readers
  remain unfinished. See `product-surfaces/2026-09-27/IMPLEMENTATION_TRACE.md`.
- [ ] Finish the remaining production and test-source slices in the approved implementation plan.
- [ ] Freeze the complete convergence input set and run the deferred validation batch.
- [ ] Obtain authorized provider/device evidence and fresh final independent review.
- [ ] Integrate the proven final result into local `dev/v10`.

No checked item above establishes runtime success, provider activation or whole-plan completion.

### Lifecycle proof implementation slices

- [x] Consistent zero-based WAL sequence through Activity admission/facts, Steps carrier/count-domain
  maintenance and Pressure carrier. Authored positive-zero and negative-sequence regression cases.
- [x] Shared historical provider-registration authentication for ambient Location, including boot,
  residency, wall/elapsed lifetime and epoch boundaries; bounded authorization lookup. Authored
  inconsistent acceptance-wall and prior-boot open-registration cases.
- [x] Independent requested-source proof observation, including empty and solo configurations;
  exact stage identity/epoch continuity, with authored tests for all 64 source subsets.
- [x] Bounded ambient radio WAL reads, exact sequence identity, deleted-admission fencing and
  reconstructed historical authorization at the callback observation; real authorization fixtures.
- [x] Shared callback registration lifetime proof for ambient Wi-Fi/Cell: exact process residency,
  boot, wall/elapsed boundaries and epoch; authored acceptance-wall and prior-boot rejection cases.
- [ ] Authenticate ambient radio physical carriers and independently qualify each provider item.
- [ ] Purpose-aware RECORDING/MATERIALIZED/QUERYABLE adapters for supported ambient sources.
- [ ] Native aggregate Steps identity/registration/revision/count-domain proof without fabricated WAL identity.
- [ ] Trace immutable automatic writer generations through admission, receipts and production queries.
- [ ] Final fresh static review of the complete lifecycle slice and evidence receipt.

The sequence/Location slice is committed as `9ad1a05b1a` after independent static review, including
a corrected Activity tail fixture. The radio slice is committed as `0b177a0d21`.
Radio/coordinator review is recorded in
`lifecycle-proof/2026-09-27/SOURCE_TRACE.md`. Test sources have not been compiled or executed.
All remaining lifecycle slices keep
`WI-LIFECYCLE-PRODUCT-PROOF-001` and `TODO-LIFE-003/004/005` open.

### Shared radio admission slices

- [ ] Confirm the explicit shared-item contract marker while preserving legacy payload 5 semantics.
- [x] Extract and independently review the common pure per-item authority/age qualifier and focused
  Wi-Fi/Cell regression sources. Committed as `b9fdbae62c`, **IMPLEMENTED_UNVALIDATED**;
  see `shared-radio/2026-09-27/REVIEW_RECEIPT.md`.
- [ ] Wire bounded historical timeline reconstruction and lawful item union into physical
  normalization and durable admission for the confirmed contract.
- [ ] Derive destination-specific counts, intervals, wall times and completeness in projection,
  retained proof/maintenance, history and exact replay using the same qualification rule.
- [ ] Complete independent static review of the entire shared-admission change and record evidence.

The pure qualifier alone does not fix production admission. It has no current runtime/ingress/
projector callers. No checked source slice implies tests passed or activation is authorized.

### Source-specific power slices

- [x] Preserve requested Steps hardware reporting within the actual demand limit; no sensor/backend
  switching or checkpoint-driven registration restart. Production/test source slice authored.
- [x] Fence queued Wi-Fi/Cell attempts by immutable owner/plan/deadline and live prerequisites;
  preserve passive observations. Production/test source slice authored.
- [x] Exact Cell request callback/SIM/timeout ownership; no inferred Wi-Fi request attribution.
- [ ] Confirm the versioned durable radio checkpoint and transactional reservation contract.
- [ ] Preserve spent intent allowance/cooldown across profile, authorization, physical, SIM and
  recovery changes; operational cleanup cannot refund it.
- [ ] Separate adopted-profile 60s desired freshness from useful provider item age.
- [ ] Author durable contract/Room cases and independently review the complete power slice.

The checked slices above are IMPLEMENTED_UNVALIDATED source work. The whole task remains open;
its initial/final bounded static review is recorded in `source-power/2026-09-27/REVIEW_RECEIPT.md`.
No checkpoint proposal is implicitly approved. Existing per-registration budget reset paths remain
until its dependent slice is completed.

### Shared provider and host ownership slices

- [x] Static trace of all six singleton source bindings and direct/system Steps count-domain separation.
- [x] Exact Location/Wi-Fi/Cell/Steps claimed capture joins, rejected-join rollback, stale disabled-claim
  fencing and acknowledged claimed close; preserve independent baseline joins. Source/test authorship.
- [x] Radio unsettled actor/retirement receipt retention; finite cancelled-actor join and replacement
  fencing. Source/test authorship, including noncooperative admission cases.
- [x] Activity changed-request predecessor retirement before successor registration; pending cleanup
  blocks replacement. Authored source cases replace prior make-before-break expectations.
- [x] Authenticate capture FGS mask separately from the full acknowledged host union; no source consent
  borrowing. Authored source-only/API and stale-union cases.
- [x] Derived live capture versus physical collection host predicates in session UI, widgets, shortcuts,
  automatic decisions and deletion presentation cleanup; preserve writer's physical host-loss fence.
- [x] Existing Location host ticket promotion uses the shared sub-five-second deadline; exact service
  attachment and accepted-ticket abandonment; authored delayed-authority/concurrent-ticket cases.
- [ ] Confirm final per-radio saved notification-host contract and feature Location/sunrise subdesign.
- [ ] Implement multi-source host, source-specific options/notifications and final-host release.
- [ ] Implement shared nonpersisting Map/game lease adapter, observed-time capability/freshness routing
  and eliminate permanent sunrise provider. Live-feed baseline regression is fixed; existing generic
  live capture still does not prove Location quality/capability until that adapter is complete.
- [ ] Complete independent static review of the entire ownership slice after dependent work exists.

Checked entries describe bounded IMPLEMENTED_UNVALIDATED source work only; the whole item is open.
See `provider-host/2026-09-27/IMPLEMENTATION_TRACE.md` and `REVIEW_RECEIPT.md` for exact limits.

## 2026-09-20 authority and Steps P5 integration ledger

Local `dev/v10` reached exact source-integration head
`75fd24b934bec9aedd71e694ae0a6d2a976b4f02`. The following bounded integrations are
**IMPLEMENTED_UNVALIDATED**:

- Retention authority: reviewed tip
  `ad234dd27c5dddd19b3fc1e5bd7a1ad66646e38c` remains preserved at
  `refs/heads/handover/reviewed/ti-retention-authority-20260919`; its local composed counterpart
  is `352dc074bc7a45f536acd4bf7aa5ef87895bbdc0`, merged by
  `9b81db5b00ec12c3a9c5bb507696fa0ab2d88db8`.
- Caller authority: final accepted reviewed tip
  `484839083510d3ce8a828e171dc6575b02d92855` remains preserved at
  `refs/heads/handover/reviewed/ti-caller-authority-20260919`, merged by
  `79fa624417118fcaa67ad21915aca497451e05d4`.
- Steps P5: source-reviewed tip `4ad78ef8086cade4aedd6963e0af7432fba0fe36` remains at
  `refs/heads/handover/reviewed/ti-steps-p5-source-20260919`; its composed source counterpart is
  `1b33697104`. The accepted assembled tip
  `bc43dd972ac8f1a63a37827cf7c2eaad90db5600` remains at
  `refs/heads/handover/reviewed/ti-steps-p5-assembled-20260919` and was merged by
  `75fd24b934bec9aedd71e694ae0a6d2a976b4f02`.

This closes the exact trusted-retention producer/recovery slice,
`WI-SOURCE-CALLER-GUARD-001`, `WI-STEPS-AMBIENT-P5-RECEIPT-001`, and the P5 dependency that kept
`WI-STEPS-NUMERIC-001` partial. It does not close broad purpose publication, cross-source privacy,
the Ambient Steps vertical, provider ownership, lifecycle product proof, or the six-source
assembly mission.

The next ready implementation wave is:

1. `impl-broker-provider-bindings`: dormant per-source physical owner bindings using the integrated
   caller and retention authority; no provider activation.
2. Portable Steps P5 adoption: separately authenticate portable count domains and compose truthful
   session, between-session, and total Steps states without borrowing native-provider authority.

No Gradle, compilation, test, lint, Detekt, generated-schema, Room-drift, diff-check, device,
battery, CI, release, or rollout validation ran for these integrations. Generated v28 JSON remains
deferred. Provider/writer activation remains unauthorized. `WORK_ITEMS.json` remains the frozen
pre-integration registry snapshot; this section and the adjusted rows below are authoritative only
for this exact post-`75fd24b9` delta until a separately authorized registry regeneration.

## Finish-started scope closure

The finish-started session scope is closed. All artifacts that were demonstrably running at the
freeze boundary and later accepted by focused static review are now locally integrated as
**IMPLEMENTED_UNVALIDATED**. The final source integration head before this documentation commit is
`2956f962e41ba3c85bd9eb1e2c9afeb83bb3e74a`.

Exact reviewed integration chain:

- Purpose: `cef95415e2` -> `8943f299f8` -> local `17b089212e`, reviewed ref
  `refs/remotes/handover/reviewed/ti-purpose-settings-20260915`.
- Portable: `2697903ae6` -> `aa9447e070` -> local `cb8159d68c`, reviewed ref
  `refs/remotes/handover/reviewed/ti-portable-file-assembly-20260915`.
- Final source/schema: `7142543ba9` -> `a4ae2dd57b` -> local `527c022e97`, reviewed ref
  `refs/remotes/handover/reviewed/ti-final-source-schema-20260916`.
- Five-source history: `1031df9f35` -> `98f2f567ce` -> local `14d31f9aa5`, reviewed ref
  `refs/remotes/handover/reviewed/ti-five-source-history-20260916`.
- Ambient files: `d5a565b418` -> `bf260d7b1f` -> local `f67ca6b16e`, reviewed ref
  `refs/remotes/handover/reviewed/ti-ambient-file-integration-20260916`.
- Consumers: `10ed3b99da` -> `5788cf846a` -> local `7e7f50bdaa`, reviewed ref
  `refs/remotes/handover/reviewed/ti-source-history-consumers-20260916`.
- Review `43a` recreation correction: `b3f490c76d` -> `335df7ea57` -> local `2956f962e4`,
  reviewed ref `refs/remotes/handover/reviewed/ti-consumer-recreation-fix-20260916`.
  Targeted rereview closed configuration disposal, same navigation-scoped ViewModel/selection
  retention within the fixed TTL, and explicit back/up, `onCleared`, TTL and stale-authority
  release. The final started-work static review has no remaining source defects.

This closes the bounded session scope, not the six-source assembly gate. Location product,
purpose publication/atomic radio guard, remaining maintenance/files/day/UI/effects/rearm work,
schema JSON/development-v28 handling, scenarios and the frozen validation/device/rollout gates
remain dependency-ordered backlog. The user published only `3dd1ff004a`; final main remains local
unless the user later publishes it. No agent push or provider/writer activation is authorized.

## Sources of truth

- `IMPLEMENTATION_TODO.md` is the exhaustive stable requirement-ID checklist.
- `WORK_ITEMS.json` is the exhaustive frozen execution registry; the 2026-09-20 ledger above
  supersedes only its stale retention/caller/P5 classifications.
- This document is an overview/index and update protocol. It owns no independent status except the
  explicit post-registry delta recorded above.
- `IMPLEMENTATION_STATUS.md`, `DECISIONS.md`, `VERIFICATION_MATRIX.md` and the dated handover
  provide narrative and chronological evidence.

Broad unchecked TODOs often contain accepted sub-slices. Their unchecked state means the broad
requirement is not complete, not that no implementation exists.
Individual source-owner receipts are authoritative only for their owned artifact; generic
entire-effort statements cannot override parent global state or adversarial blockers.

## Frozen session scope

Scope expansion remains frozen. The previously identified Activity range, Ambient Steps files and
shared history consumer late-start artifacts are now closed and locally integrated. No successor
was started. Assembly is not frozen and validation remains deferred. No agent push or
provider/writer activation is authorized.

The user independently published `3dd1ff004a34beb339539c4961704919d781371c`.
Local documentation continued from `6744ced52037d531e56751777b519165167e6b19`.

## Structural audit correction

Independent registry audit `8e` at `c77b1202321b2a2c8b6cc581dc6da2c9c0c7a237`
reported nine structural/actionability findings. This revision:

1. Adds reciprocal requirement links to concrete child artifacts.
2. Separates the closed Ambient/Location schema slice from planned all-source schema convergence.
3. Removes the purpose/publication/broker/lifecycle phase cycle and adds product-state proof.
4. Gives shared day/range explicit source-producer dependencies.
5. Adds the exact Ambient Steps execution-authority remedy.
6. Moves historical Steps NUM-006 to the planned P5 count-domain receipt.
7. Marks existing QoS/UI/actions/scenarios/non-Steps effects as partial rather than absent.
8. Keeps approved passive Location independent from optional expanded-ambient decisions.
9. Makes the assembly gate depend explicitly on every source and shared completion boundary.

Final audit follow-up at `aec85eeeb429b91ce9137030a54c6466c12126d5` adds the P5 day
dependency, fixes Ambient execution/publication ownership, adds direct assembly prerequisites,
and reclassifies the late-start Activity range artifact without authorizing new work.

## Coverage

- Work items: **72**
- Stable TODO requirements: **293**
- Explicitly mapped: **293**
- Unmapped: **0**
- TODO checked/unchecked: **71 / 222**

State counts:

| Implementation state | Items |
| --- | ---: |
| `active_governance` | 1 |
| `approval_required` | 1 |
| `completed_accepted_slice` | 29 |
| `completed_foundation` | 1 |
| `decision_required` | 8 |
| `gate_deferred` | 4 |
| `partial_implemented` | 17 |
| `planned` | 11 |

Review states: `closed_except_decision` 1, `closed_static` 29, `continuous` 1,
`historical_accepted` 1, `mixed` 16, `not_applicable` 9, `not_started` 15.

Integration states: `local_dev` 31, `local_only` 1, `mixed_source_refs` 16,
`not_applicable` 9, `not_started` 15.

Validation states: `deferred_implementation_only` 63, `not_applicable` 9.


## Work-item index

| ID | State | Review | Integration | Title | Next bounded action |
| --- | --- | --- | --- | --- | --- |
| `WI-TRACKING-REGISTRY-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Maintain the authoritative work-item registry | Finish-started documentation scope is closed at the final local integration receipt. Remain idle until a new explicit bounded assignment. |
| `WI-HANDOVER-PRESERVATION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve accepted transport and receiving seams | Preserve as historical evidence; do not reopen or expand without a bounded request. |
| `WI-REPOSITORY-SAFETY-001` | `active_governance` | `continuous` | `local_only` | Preserve repository, worktree and publication boundaries | Apply these boundaries to every owner receipt and local integration decision. |
| `WI-FOUNDATION-BASE-001` | `completed_foundation` | `historical_accepted` | `local_dev` | Preserve the accepted tracking safety foundation | Use as immutable architectural constraints for remaining items. |
| `WI-SCHEMA-MIGRATION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close the bounded shared v28 schema assembly | Preserve this bounded schema slice inside the locally integrated final source/schema assembly at 527c022e97; do not claim migration or generated-schema proof. |
| `WI-SCHEMA-CONVERGENCE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Complete all-source v28 schema convergence | Preserve reviewed original 7142543ba9, rebased a4ae2dd57b and local integration 527c022e97. Defer generated schema and development-v28 repair strategy to the frozen final batch. |
| `WI-AUTHORITY-PURPOSE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete source and purpose authority semantics | Preserve reviewed retention `ad234dd2` through local merge `9b81db5b`; actual purpose publication and broader authority convergence remain open. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete one physical owner per source | Next ready wave: bind dormant per-source physical owners through the integrated caller/retention authority. Keep providers off. |
| `WI-LIFECYCLE-RUNTIME-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Close lifecycle, action and settlement semantics | Preserve locally integrated runtime/lifecycle settlement and exact owner/pending DDL. Executable multi-generation rearm and source propagation remain ordered backlog. |
| `WI-SOURCE-CALLER-GUARD-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Guard every source runtime caller | Preserve final reviewed `48483908` at `handover/reviewed/ti-caller-authority-20260919` through merge `79fa6244`; broker/provider binding and activation remain separate. |
| `WI-LIFECYCLE-PRODUCT-PROOF-001` | `planned` | `not_started` | `not_started` | Prove RECORDING, MATERIALIZED and QUERYABLE transitions | Handover backlog. Implement only after provider/source product dependencies close; do not infer proof from current branches. |
| `WI-DATA-MAINTENANCE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete durable admission, projection and maintenance | Preserve the locally integrated started artifacts. Resume remaining data-plane work only through the dependency-ordered backlog. |
| `WI-QOS-BATTERY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete honest acquisition and battery semantics | Preserve existing plan/callback primitives. Hand over remaining implementation hooks; actual measurement stays solely in WI-VALIDATION-MEASUREMENT-001. |
| `WI-STEPS-PORTABLE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed imported Steps and portable round trip | Preserve; no new work in this scope. |
| `WI-STEPS-MANUAL-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed manual Steps vertical | Preserve until final convergence validation. |
| `WI-STEPS-NUMERIC-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve qualified Steps consumer infrastructure | Preserve accepted consumers plus the integrated P5 count-domain proof at `75fd24b9`; portable-domain adoption remains a separate next-wave composition. |
| `WI-STEPS-AUTOMATIC-001` | `partial_implemented` | `closed_except_decision` | `local_dev` | Complete automatic Steps with bounded control evidence | Keep control behavior contained and inactive; do not invent a duration. |
| `WI-STEPS-AMBIENT-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Ambient Steps vertical | Preserve product/file inputs and integrated P5 count-domain authority. Hand over execution, portable-domain adoption, settings, export union and shared day/UI work. |
| `WI-STEPS-AMBIENT-PRODUCT-CORRECTION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Ambient Steps base and product review findings | Preserve locally integrated input 0c4728c2. Native P5 authority is now integrated; portable/product adoption, execution, settings and shared day/UI remain separate backlog. |
| `WI-STEPS-AMBIENT-FILES-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Review the committed Ambient Steps file artifact | Preserve reviewed 220a2721 through Ambient file integration f67ca6b16e. Do not infer export-union or execution-lane completion. |
| `WI-STEPS-AMBIENT-EXECUTION-001` | `planned` | `not_started` | `not_started` | Implement Ambient Steps execution authority | Handover backlog. Implement under a new bounded assignment; never fake AMBIENT support on the session lane. |
| `WI-STEPS-AMBIENT-P5-RECEIPT-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Implement the P5 Ambient/session count-domain receipt | Preserve source-reviewed `4ad78ef8`, accepted assembled `bc43dd97`, and local merge `75fd24b9`; no portable or provider authority is implied. |
| `WI-PRESSURE-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Pressure source-to-product vertical | Preserve locally integrated Pressure maintenance, recency/qualification bridges and file scope. Hand over day/range product and executable rearm propagation. |
| `WI-PRESSURE-MAINTENANCE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Pressure maintenance privacy corrections | Preserve the definitive Pressure review and locally integrated 7ce26646/57d23d30 successors inside 527c022e97. Do not claim execution proof. |
| `WI-PRESSURE-FILES-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Pressure portable file review | Preserve reviewed source 8ba72248 and exact helper 76739a35. No new Pressure file feature; final consumers are locally integrated separately. |
| `WI-PRESSURE-PRODUCT-001` | `planned` | `not_started` | `not_started` | Build the remaining Pressure day and range product | Handover backlog; do not start. |
| `WI-LOCATION-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete protected Location without a second writer | Preserve the definitive protected Location handoff review inside 527c022e97; implement the Location product/catalog/query chain only under a future bounded assignment. |
| `WI-LOCATION-HANDOFF-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close protected Location WAL-to-writer handoff | Preserve definitive lifecycle successor ffdef045b9 and Location successor ea04901eec in local integration 527c022e97. Do not activate or infer provider proof. |
| `WI-LOCATION-AMBIENT-PRODUCT-001` | `planned` | `not_started` | `not_started` | Implement approved passive ambient Location baseline | Handover backlog; do not start. |
| `WI-ACTIVITY-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete captured Activity and control separation | Preserve locally integrated Activity actions plus 6dfa4841/b6a0b093 range/action successors. Hand over wider shared UI/runtime work. |
| `WI-ACTIVITY-ACTIONS-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve closed Activity source actions and file semantics | Preserve locally integrated Activity source actions, raw-EOF correction and separately closed range/action successors. Do not infer wider shared UI completion. |
| `WI-ACTIVITY-RANGE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Implement Activity structural range product | Preserve the closed late-start range/action artifact through successors 6dfa4841 and b6a0b093 in local 527c022e97. Do not infer wider UI completion. |
| `WI-WIFI-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Wi-Fi source-to-product vertical | Preserve locally integrated Wi-Fi product correction 99c56006. Hand over retention, source erase, files/deleted range and shared product surfaces. |
| `WI-WIFI-PRODUCT-CORRECTION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Wi-Fi product, range and selected-delete corrections | Preserve the closed Wi-Fi successor 99c56006 in local 527c022e97. Do not infer retention, erase, files or UI completion. |
| `WI-WIFI-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Wi-Fi retention, erase and file actions | Handover backlog; do not start. |
| `WI-CELL-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Cell source-to-product vertical | Preserve locally integrated Cell product correction 1ff5ebf7. Hand over retention, source erase, files/deleted range and shared product surfaces. |
| `WI-CELL-PRODUCT-CORRECTION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Cell product, range and selected-delete corrections | Preserve the closed Cell successor 1ff5ebf7 in local 527c022e97. Do not infer retention, erase, files or UI completion. |
| `WI-CELL-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Cell deleted ranges, retention, erase and files | Handover backlog; do not start. |
| `WI-AMBIENT-RADIO-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close passive ambient Wi-Fi and Cell artifact | Preserve closed passive-radio successor 1b2467e3 and its locally integrated registry/schema scope at 527c022e97. Do not advertise or activate radio support. |
| `WI-PURPOSE-SETTINGS-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close purpose settings containment retry | Preserve locally integrated Purpose source. Do not begin authority-issuer, Hilt/runtime reporting or feature-collector successors. |
| `WI-PURPOSE-PUBLICATION-001` | `planned` | `not_started` | `not_started` | Wire actual purpose authority publication | Handover backlog; do not start. |
| `WI-SHARED-FILE-ASSEMBLY-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close shared portable file assembly review | Preserve locally integrated portable input. Ambient Steps files are integrated by their separate reviewed receipt; radio formats and runtime activation remain backlog. |
| `WI-SHARED-HISTORY-UNION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close five-source recent, live and selected history union | Preserve reviewed 1031df9f/production f597877b through local five-source history integration 14d31f9aa5. Do not infer Location or structural day/range completion. |
| `WI-SHARED-HISTORY-BRIDGES-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close the imported-history producer bridge contract | Preserve the closed four-producer contract and real nonempty chain in local five-source history integration 14d31f9aa5; no structural range or extra UI work is implied. |
| `WI-HISTORY-BRIDGE-ACTIVITY-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Implement the narrow imported Activity history bridge | Preserve the closed Activity bridge in local five-source history integration 14d31f9aa5; keep wider Activity UI/runtime work separate. |
| `WI-HISTORY-BRIDGE-PRESSURE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close the narrow imported Pressure history bridge | Preserve the closed Pressure bridge in local five-source history integration 14d31f9aa5; do not infer Pressure day/range completion. |
| `WI-HISTORY-BRIDGE-WIFI-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close the narrow imported Wi-Fi history bridge | Preserve the closed Wi-Fi bridge in local five-source history integration 14d31f9aa5; keep retention/files/UI separate. |
| `WI-HISTORY-BRIDGE-CELL-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Implement the narrow imported Cell history bridge | Preserve the closed Cell bridge in local five-source history integration 14d31f9aa5; keep retention/files/UI separate. |
| `WI-SHARED-HISTORY-CONSUMERS-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Correct the started shared history feature consumers | Preserve consumer base 10ed3b99/5788cf846a/7e7f50bdaa plus review-43a recreation fix b3f490c7/335df7ea/2956f962. The started-work static review is closed with no remaining source defects. |
| `WI-SHARED-HISTORY-DAY-RANGE-001` | `planned` | `not_started` | `not_started` | Complete all-six structural day, range and Today history | Handover backlog; do not start. |
| `WI-UI-SURFACES-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Wire truthful shared product surfaces | Preserve the final source-history consumer and recreation correction at 2956f962. Hand over structural day/Today/Timeline/Calendar/Trips/maps and remaining truthful variants. |
| `WI-SOURCE-ACTIONS-EXPLANATION-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete start, settings, source actions and recording explanation | Preserve existing manual start and source-action primitives. Hand over shared dispatcher, explanation and remaining settings/UI. |
| `WI-CROSS-SOURCE-PRIVACY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete cross-source retention, transfer, deletion and no-resurrection | Preserve the trusted retention-authority slice merged at `9b81db5b`; remaining per-source transfer, erase, no-resurrection and policy-duration work stays open. |
| `WI-NONSTEPS-EFFECTS-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Qualify non-Steps widgets, achievements and effects | Preserve current effect collectors and Steps identity/retraction patterns. Hand over remaining non-Steps qualification conversions. |
| `WI-RUNTIME-SETTLEMENT-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close active shared runtime settlement correction | Preserve definitive runtime review e71c739e, lifecycle final ffdef045 and exact pending/owner DDL in local 527c022e97. Do not advertise executable source support. |
| `WI-RUNTIME-PRODUCER-PROPAGATION-001` | `planned` | `not_started` | `not_started` | Propagate runtime generation and lifecycle contracts | Handover backlog. Implement executable multi-generation rearm and source propagation only under a new bounded assignment; providers remain off. |
| `WI-CROSS-SOURCE-RECOVERY-001` | `planned` | `not_started` | `not_started` | Complete cross-source correction, recovery and provenance | Handover backlog; do not start. |
| `WI-INTEGRATION-SCENARIOS-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Author complete real integration scenario sources | Preserve existing source-specific chain tests and authored regressions. Hand over the missing complete scenario composition. |
| `WI-DEC-CONTROL-EVIDENCE-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve exact automatic CONTROL evidence lifetime and shape | User/product owner decision; implementation must not invent a duration. |
| `WI-DEC-RETENTION-PRIVACY-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve source/purpose retention and privacy copy | User/product/privacy approval. |
| `WI-DEC-AMBIENT-LOCATION-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve or reject ambient Location beyond passive points | User/product approval. |
| `WI-DEC-RADIO-MODES-IDENTITY-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve optional active radio modes and identity products | User/product/privacy decision after measurement. |
| `WI-DEC-PRESSURE-ELEVATION-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve or reject calibrated Pressure elevation | User/product decision; do not infer elevation. |
| `WI-DEC-CROSS-MIDNIGHT-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve cross-midnight counting and presentation | User/product decision before shared day implementation. |
| `WI-DEC-ROLLOUT-WRITER-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve rollout evidence channel and writer retirement | User approval after final evidence. |
| `WI-DEC-V28-PREMISE-001` | `decision_required` | `not_applicable` | `not_applicable` | Confirm the unshipped v28 premise and development database handling | Release owner confirms premise before validation handling is selected. |
| `WI-ASSEMBLY-GATE-001` | `planned` | `not_started` | `not_started` | Complete and freeze the implementation assembly | Handover backlog. Parent may integrate only closed bounded scopes; no assembly freeze yet. |
| `WI-VALIDATION-HOST-SCHEMA-001` | `gate_deferred` | `not_started` | `not_started` | Run the final host, compile, migration and schema batch | Do not execute until the phase stop is explicitly lifted. |
| `WI-VALIDATION-DEVICE-001` | `gate_deferred` | `not_started` | `not_started` | Run representative Android source-to-product evidence | Do not execute until explicitly authorized after host/schema convergence. |
| `WI-VALIDATION-MEASUREMENT-001` | `gate_deferred` | `not_started` | `not_started` | Measure quality, battery and cutover behavior | Do not execute until explicitly authorized. |
| `WI-FINAL-INTEGRATION-001` | `gate_deferred` | `not_started` | `not_started` | Perform final review and local integration | Final validated convergence remains deferred; current local assembly is IMPLEMENTED_UNVALIDATED and does not satisfy this gate. |
| `WI-PUBLICATION-ACTIVATION-001` | `approval_required` | `not_applicable` | `not_applicable` | Obtain separate publication and activation approval | Wait for explicit user approval after local completion. |

## Scope-receipt classification

All three concrete late-start receipts are closed and locally integrated:

- `WI-ACTIVITY-RANGE-001` through local source/schema integration `527c022e97`.
- `WI-STEPS-AMBIENT-FILES-001` through local Ambient file integration `f67ca6b16e`.
- `WI-SHARED-HISTORY-CONSUMERS-001` through final review-43a recreation integration
  `2956f962e4`.

Their late-start classification remains historical scope evidence; it does not authorize successors.

## Update protocol

1. Keep existing `TODO-*` and `WI-*` IDs stable. Add a child `WI-*` only for a coherent
   independently ownable artifact, decision or gate.
2. Update `WORK_ITEMS.json` first. Do not put a competing status in this Markdown file.
3. Change state only from concrete evidence: exact pinned commit/ref, owner receipt, focused
   review, local integration or executed validation on the exact commit. Do not let a moving
   owner branch/worktree silently replace the recorded review input.
4. A branch or authored test never implies review, integration or validation completion.
5. Record exact modules/path scopes, public interfaces, dependencies, blockers, authored tests
   and deferred commands. Preserve unexpected dirty work.
6. Rebuild this overview from the JSON state counts/index and update the narrative ledgers when
   a decision, accepted slice, integration or proof boundary changes.
7. During IMPLEMENTATION_ONLY, never run the deferred commands. Metadata-only JSON parsing and
   requirement coverage checks are permitted.

Metadata consistency check:

```powershell
@'
import json, pathlib, re
root = pathlib.Path(r'docs\tracking-infrastructure')
data = json.loads((root / 'WORK_ITEMS.json').read_text(encoding='utf-8'))
todo = (root / 'IMPLEMENTATION_TODO.md').read_text(encoding='utf-8')
ids = set(re.findall(r'^- \[[ xX]\] ([A-Z][A-Z0-9-]+)', todo, re.M))
mapped = {r['id'] for r in data['requirement_index']}
assert ids == mapped
assert not data['coverage']['unmapped_requirement_ids']
print(len(data['work_items']), len(ids))
'@ | python -
```

## Evidence caveat

One Location reviewer accidentally invoked `git diff --check`; it produced no output and
changed no files. It is recorded as a process deviation, not validation evidence. No compile,
test, Gradle, lint, schema, device or CI gate has started for the current assembly.

### Unified history foundation continuation (2026-09-27)

Whole unified-history requirement remains unchecked. Completed source slices after independent
static acceptance are recorded in `unified-history/2026-09-27/REVIEW_RECEIPT.md`; test sources are
unexecuted. No broad WI/source-day/consumer completion, runtime claim, freeze, provider activation
or new local dev/v10 integration is inferred from these preparatory source APIs.

- [x] Author and statically review range/filter/global consumed-key contract and bounded merge.
- [x] Author and statically review native/legacy and five-import candidate range discovery.
- [x] Author and statically review selected logical/imported Steps and sessionless background seams.
- [x] Author focused merge/Room/selected-origin regression sources (unexecuted).
- [ ] Resolve historical Location visibility and exact local ownership-index decisions.
- [ ] Implement complete qualified range facade, invalidation and production paging/DI.
- [ ] Wire calendar, timeline and exact detail consumers; author full-source/UI regression sources.

Checked foundation lines describe source/static-review evidence only, IMPLEMENTED_UNVALIDATED.

### Metrics, actions and effects continuation (2026-09-27)

Whole work item remains unchecked. These narrow checked slices describe production/test-source
and independent static review only, **IMPLEMENTED_UNVALIDATED**:

- [x] Reject unknown metric IDs without fabricated zero and malformed windows without DAO reads.
- [x] Map source-qualified native Activity display to original logical start/cutoff; author active,
  STOPPING, invalid-bound, selected/recent and unchanged-evidence regression sources.
- [x] Carry validated original closed Pressure headers in the bounded transactional reader; retain
  independent windows/qualification/recency and author Room/unresolved-header regression sources.
- [x] Trace all six summary/action/effect consumers and independently review these narrow repairs.
- [ ] Resolve unsupported-period and uncertain/open original interval API/UI decisions.
- [ ] Complete qualified six-source summary facade, actual scope/coverage/origin and UI/file consumers.
- [ ] Complete native portable original bounds with exact format/collision compatibility handling.
- [ ] Implement exact native Steps/Pressure logical/mixed selectors and supported source actions.
- [ ] Complete prospective logical source-qualified effects and correction/deletion retraction,
  preserving previous confirmed receipts and organization no-effect.
- [ ] Complete whole-scope source review and deferred final convergence evidence after assembly.

See `metrics-actions-effects/2026-09-27/IMPLEMENTATION_TRACE.md` and `REVIEW_RECEIPT.md`.
No executed checks, provider activation, assembly freeze, new dev/v10 integration or push.

### Optional map enrichment continuation (2026-09-27)

Whole enrichment requirement remains unchecked. Narrow checked slices mean authored source and
independent static review only, **IMPLEMENTED_UNVALIDATED**.

- [x] Confirm temporal/accuracy join rules and shared source-owned paired-reader architecture.
- [x] Author conservative Wi-Fi30s/Cell60s matching, exact purpose/scope boundaries and uncertainty.
- [x] Extend committed Room invalidation to native fact/cursor/receipt and retained-authority inputs.
- [x] Author matcher, observer classification and actual Room registration/commit regression sources.
- [x] Author and independently statically review the approved shared API and reused canonical
  checksum source; local IMPLEMENTED_UNVALIDATED checkpoint, no reader/DI/renderer activation.
- [ ] Resolve durable full Location receipt/purpose-proof storage versus original-event-only gaps.
- [ ] Implement complete retained Location and native radio authentication in one bounded snapshot.
- [ ] Confirm native observed-point display placement; preserve legacy identity/estimate semantics.
- [ ] Wire native displays and both-input map layer/cache dependencies, gaps and limit outcomes.
- [ ] Author authenticated paired-reader, privacy/correction and MapStore/display regression sources.
- [ ] Finish whole-scope independent static review and deferred final convergence evidence.

See `optional-map-enrichment/2026-09-27/IMPLEMENTATION_TRACE.md` and `REVIEW_RECEIPT.md`.
No validation, input freeze, provider activation, new dev/v10 integration or push.

### Source file transfers continuation (2026-09-28)

- [x] Resolve source-focused export UI: one Wi-Fi choice and one Cell choice, each preserving
  session and separately consented background data within its own single file.
- [x] Resolve the date switch: default on for captured Wi-Fi/Cell, include only whole entries
  with selected-source observation/gap evidence touching the dates; off excludes crossing entries.
- [x] Resolve exact Location backup scope: include all saved points in selected dates, label
  unknown origin honestly, accept structurally valid edited files and deduplicate by point ID.
- [x] Author the ambient radio one-snapshot export correction and focused test source; independent
  static review found no remaining narrow-slice issue. **IMPLEMENTED_UNVALIDATED**.
- [x] Author captured Cell gap-only portable coverage, zero-observation re-import/deletion handling,
  and radio WAL retention while saved captured facts depend on it. Focused test sources are authored;
  this slice remains **IMPLEMENTED_UNVALIDATED**.
- [ ] Finish combined Wi-Fi/Cell file adapters, source-owned range selection and
  `.trackerlocation` export/import with overlap deduplication.
- [ ] Complete whole-scope review and deferred convergence/device verification.

All tracked data remains in the app database until a person explicitly chooses Export. A chosen
format produces one file. No validation, input freeze, provider activation, new dev/v10 integration
or push.


### Settings and product surfaces continuation (2026-09-27)

Whole surface requirement remains unchecked. Checked source slices are authored only,
**IMPLEMENTED_UNVALIDATED**; final assembly and runtime proof remain separate.

- [x] Trace source setup/settings, Dashboard, Tracker, statistics, notifications and widgets.
- [x] Author app-locale widget counts/duration/percent and legacy settings-unit corrections.
- [x] Author localized existing notification/session action and calendar-selection semantics, plus focused regression sources.
- [x] Finish independent static review of the narrow source diff and record the receipt.
- [x] Resolve concrete source Details/Pressure/selection, recovery and live presentation proposals;
  user approved all four plain-language choices on 2026-09-28. Implementation remains below.
- [ ] Implement source-aware onboarding/settings, guarded completion and recoverable serialized saves.
- [ ] Complete qualified history/metrics readers and wire Dashboard/Tracker/statistics presentation.
- [ ] Complete qualified notifications/widgets, source capability/recovery states and accessible reorder.
- [ ] Complete concise copy/localization/calendar/action accessibility across remaining surfaces.
- [ ] Finish full-scope source review and deferred complete convergence/runtime/device evidence.

See `product-surfaces/2026-09-27/IMPLEMENTATION_TRACE.md` and `CONTRACT_DECISIONS.md`.
No validation, input freeze, provider activation, new dev/v10 integration or push.
