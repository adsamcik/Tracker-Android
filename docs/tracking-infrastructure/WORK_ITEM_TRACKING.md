# Tracking Infrastructure Work-Item Tracking

Last updated: 2026-09-16

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
- `WORK_ITEMS.json` is the authoritative execution registry for current state, dependencies,
  ownership, evidence, blockers and next actions.
- This document is an overview/index and update protocol. It does not own independent status.
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
| `completed_accepted_slice` | 26 |
| `completed_foundation` | 1 |
| `decision_required` | 8 |
| `gate_deferred` | 4 |
| `partial_implemented` | 18 |
| `planned` | 13 |

Review states: `closed_except_decision` 1, `closed_static` 27, `continuous` 1,
`historical_accepted` 1, `mixed` 16, `not_applicable` 9, `not_started` 17.

Integration states: `local_dev` 29, `local_only` 1, `mixed_source_refs` 16,
`not_applicable` 9, `not_started` 17.

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
| `WI-AUTHORITY-PURPOSE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete source and purpose authority semantics | Do not start a new authority wave; consume only closed current-purpose artifacts when a bounded integration is assigned. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete one physical owner per source | Remain planned outside already-started runtime and purpose corrections. |
| `WI-LIFECYCLE-RUNTIME-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Close lifecycle, action and settlement semantics | Preserve locally integrated runtime/lifecycle settlement and exact owner/pending DDL. Executable multi-generation rearm and source propagation remain ordered backlog. |
| `WI-SOURCE-CALLER-GUARD-001` | `planned` | `not_started` | `not_started` | Guard every source runtime caller | Handover backlog. Implement only after broker/publication/producer propagation close; providers remain off. |
| `WI-LIFECYCLE-PRODUCT-PROOF-001` | `planned` | `not_started` | `not_started` | Prove RECORDING, MATERIALIZED and QUERYABLE transitions | Handover backlog. Implement only after provider/source product dependencies close; do not infer proof from current branches. |
| `WI-DATA-MAINTENANCE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete durable admission, projection and maintenance | Preserve the locally integrated started artifacts. Resume remaining data-plane work only through the dependency-ordered backlog. |
| `WI-QOS-BATTERY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete honest acquisition and battery semantics | Preserve existing plan/callback primitives. Hand over remaining implementation hooks; actual measurement stays solely in WI-VALIDATION-MEASUREMENT-001. |
| `WI-STEPS-PORTABLE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed imported Steps and portable round trip | Preserve; no new work in this scope. |
| `WI-STEPS-MANUAL-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed manual Steps vertical | Preserve until final convergence validation. |
| `WI-STEPS-NUMERIC-001` | `partial_implemented` | `closed_static` | `local_dev` | Preserve qualified Steps consumer infrastructure | Preserve the accepted numeric infrastructure. Do not duplicate engine numeric implementation; complete only the separate P5 producer receipt when assigned. |
| `WI-STEPS-AUTOMATIC-001` | `partial_implemented` | `closed_except_decision` | `local_dev` | Complete automatic Steps with bounded control evidence | Keep control behavior contained and inactive; do not invent a duration. |
| `WI-STEPS-AMBIENT-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Ambient Steps vertical | Preserve locally integrated product 0c and file 220 inputs. Hand over execution authority, P5 count-domain receipt, settings, export union and shared day/UI work. |
| `WI-STEPS-AMBIENT-PRODUCT-CORRECTION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Ambient Steps base and product review findings | Preserve locally integrated input 0c4728c2 with typed Unproven P5 behavior. P5, execution authority, settings and shared day/UI remain separate backlog. |
| `WI-STEPS-AMBIENT-FILES-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Review the committed Ambient Steps file artifact | Preserve reviewed 220a2721 through Ambient file integration f67ca6b16e. Do not infer export-union or execution-lane completion. |
| `WI-STEPS-AMBIENT-EXECUTION-001` | `planned` | `not_started` | `not_started` | Implement Ambient Steps execution authority | Handover backlog. Implement under a new bounded assignment; never fake AMBIENT support on the session lane. |
| `WI-STEPS-AMBIENT-P5-RECEIPT-001` | `planned` | `not_started` | `not_started` | Implement the P5 Ambient/session count-domain receipt | Handover backlog. Implement the receipt only under a new bounded assignment and reuse existing numeric consumers. |
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
| `WI-CROSS-SOURCE-PRIVACY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete cross-source retention, transfer, deletion and no-resurrection | Preserve closed started artifacts and hand over remaining source retention, transfer, erase and no-resurrection work in dependency order. |
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
