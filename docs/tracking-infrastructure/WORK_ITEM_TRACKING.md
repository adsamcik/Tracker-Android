# Tracking Infrastructure Work-Item Tracking

Last updated: 2026-09-15

## Sources of truth

- `IMPLEMENTATION_TODO.md` is the exhaustive stable requirement-ID checklist.
- `WORK_ITEMS.json` is the authoritative execution registry for current state, dependencies,
  ownership, evidence, blockers and next actions.
- This document is an overview/index and update protocol. It does not own independent status.
- `IMPLEMENTATION_STATUS.md`, `DECISIONS.md`, `VERIFICATION_MATRIX.md` and the dated handover
  provide narrative and chronological evidence.

Broad unchecked TODOs often contain accepted sub-slices. Their unchecked state means the broad
requirement is not complete, not that no implementation exists.

## Frozen session scope

Scope expansion is frozen. Finish only artifacts demonstrably started before the boundary,
their directly necessary corrections/test source and focused static review. Preserve dirty
work. Parent freeze inspection confirmed the three named successor assignments were unstarted,
and owners were told not to create them. They are planned backlog. Assembly is not frozen
and validation
remains deferred. No agent push or provider/writer activation is authorized.

The user independently published `3dd1ff004a34beb339539c4961704919d781371c`.
Local documentation continued from `6744ced52037d531e56751777b519165167e6b19`.

## Coverage

- Work items: **62**
- Stable TODO requirements: **293**
- Explicitly mapped: **293**
- Unmapped: **0**
- TODO checked/unchecked: **71 / 222**

State counts:

| Implementation state | Items |
| --- | ---: |
| `active_correction` | 10 |
| `active_documentation` | 1 |
| `active_governance` | 1 |
| `allowed_dependency` | 1 |
| `approval_required` | 1 |
| `committed_held` | 3 |
| `completed_accepted_slice` | 5 |
| `completed_foundation` | 1 |
| `decision_required` | 8 |
| `gate_deferred` | 4 |
| `partial_implemented` | 11 |
| `planned` | 16 |

Review states: `active` 6, `awaiting_focused_closure` 1, `blocked_findings` 9, `closed_except_decision` 1, `closed_static` 5, `continuous` 2, `historical_accepted` 1, `mixed` 7, `not_applicable` 9, `not_started` 21.

Integration states: `held_branch` 2, `held_dependency` 1, `local_dev` 7, `local_only` 1, `mixed_source_refs` 7, `not_applicable` 9, `not_started` 21, `source_branch` 14.

Validation states: `deferred_implementation_only` 53, `not_applicable` 9.


## Work-item index

| ID | State | Review | Integration | Title | Next bounded action |
| --- | --- | --- | --- | --- | --- |
| `WI-TRACKING-REGISTRY-001` | `active_documentation` | `continuous` | `local_dev` | Maintain the authoritative work-item registry | Continue authorized tracking-only updates as owner receipts, correction closures and integration dispositions arrive; do not start production work. |
| `WI-HANDOVER-PRESERVATION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve accepted transport and receiving seams | Preserve as historical evidence; do not reopen or expand without a bounded request. |
| `WI-REPOSITORY-SAFETY-001` | `active_governance` | `continuous` | `local_only` | Preserve repository, worktree and publication boundaries | Apply these boundaries to every owner receipt and local integration decision. |
| `WI-FOUNDATION-BASE-001` | `completed_foundation` | `historical_accepted` | `local_dev` | Preserve the accepted tracking safety foundation | Use as immutable architectural constraints for remaining items. |
| `WI-SCHEMA-MIGRATION-001` | `committed_held` | `active` | `held_branch` | Close the bounded shared v28 schema assembly | Close the frozen cba0b116 review and apply only directly reported corrections to this existing schema scope. |
| `WI-AUTHORITY-PURPOSE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete source and purpose authority semantics | Do not start a new authority wave; consume only closed current-purpose artifacts when a bounded integration is assigned. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete one physical owner per source | Remain planned outside already-started runtime and purpose corrections. |
| `WI-LIFECYCLE-RUNTIME-001` | `active_correction` | `blocked_findings` | `source_branch` | Close lifecycle, action and settlement semantics | Finish only the already-started runtime correction and test-source closure. |
| `WI-DATA-MAINTENANCE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete durable admission, projection and maintenance | No new cross-source data-plane wave; finish only the active source and shared artifacts listed in this registry. |
| `WI-QOS-BATTERY-001` | `planned` | `not_started` | `not_started` | Complete honest acquisition and battery semantics | Handover backlog; do not start during the finish-only scope. |
| `WI-STEPS-PORTABLE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed imported Steps and portable round trip | Preserve; no new work in this scope. |
| `WI-STEPS-MANUAL-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed manual Steps vertical | Preserve until final convergence validation. |
| `WI-STEPS-NUMERIC-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed qualified Steps consumers | Preserve; final execution remains deferred. |
| `WI-STEPS-AUTOMATIC-001` | `partial_implemented` | `closed_except_decision` | `local_dev` | Complete automatic Steps with bounded control evidence | Keep control behavior contained and inactive; do not invent a duration. |
| `WI-STEPS-AMBIENT-001` | `partial_implemented` | `blocked_findings` | `source_branch` | Complete the Ambient Steps vertical | During this scope, close only the active c8/4d5 product correction. Hand over all other Ambient Steps work. |
| `WI-STEPS-AMBIENT-PRODUCT-CORRECTION-001` | `active_correction` | `blocked_findings` | `source_branch` | Close Ambient Steps base and product review findings | Owner applies only these nine corrections and returns both existing reviewers to the exact corrected input. |
| `WI-STEPS-AMBIENT-FILES-001` | `planned` | `not_started` | `not_started` | Implement Ambient Steps file actions | Backlog only; do not start unless a later bounded assignment includes an owner receipt with actual authored-code evidence. |
| `WI-PRESSURE-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Pressure source-to-product vertical | Finish only the active maintenance and file reviews; hand over the rest. |
| `WI-PRESSURE-MAINTENANCE-001` | `active_correction` | `active` | `source_branch` | Close Pressure maintenance privacy corrections | The existing reviewer checks 7dd72ab9 and 39198f5 against the six corrections; the owner fixes only resulting in-scope findings. |
| `WI-PRESSURE-FILES-001` | `committed_held` | `active` | `source_branch` | Close Pressure portable file review | Close the existing f8ca404 review; do not add another format or UI action. |
| `WI-PRESSURE-PRODUCT-001` | `planned` | `not_started` | `not_started` | Build the remaining Pressure day and range product | Handover backlog; do not start. |
| `WI-LOCATION-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete protected Location without a second writer | Finish only the active Location handoff correction; hand over product and ambient successors. |
| `WI-LOCATION-HANDOFF-001` | `active_correction` | `blocked_findings` | `source_branch` | Close protected Location WAL-to-writer handoff | Owner fixes only these blockers and returns the same focused reviewer to the corrected real-chain input. |
| `WI-LOCATION-AMBIENT-PRODUCT-001` | `planned` | `not_started` | `not_started` | Decide and implement passive ambient Location product | Handover backlog; do not start. |
| `WI-ACTIVITY-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete captured Activity and control separation | No new Activity lane; integrate only the closed 483 source when shared review permits. |
| `WI-ACTIVITY-ACTIONS-001` | `completed_accepted_slice` | `closed_static` | `held_dependency` | Preserve closed Activity source actions and file semantics | No source edit. Parent consumes 483 only through the frozen shared-file integration after its review closes. |
| `WI-ACTIVITY-RANGE-001` | `planned` | `not_started` | `not_started` | Implement Activity structural range product | Backlog only; do not start unless a later bounded assignment includes an owner receipt with actual authored-code evidence. |
| `WI-WIFI-VERTICAL-001` | `partial_implemented` | `blocked_findings` | `source_branch` | Complete the Wi-Fi source-to-product vertical | Finish only the existing product/range/selected-delete correction; hand over retention/erase/files/UI. |
| `WI-WIFI-PRODUCT-CORRECTION-001` | `active_correction` | `blocked_findings` | `source_branch` | Close Wi-Fi product, range and selected-delete corrections | Owner closes these findings and the already-present range/selected-delete edits; no retention/source-erase/file successor. |
| `WI-WIFI-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Wi-Fi retention, erase and file actions | Handover backlog; do not start. |
| `WI-CELL-VERTICAL-001` | `partial_implemented` | `blocked_findings` | `source_branch` | Complete the Cell source-to-product vertical | Finish only the current correction/review; hand over retention, erase and files. |
| `WI-CELL-PRODUCT-CORRECTION-001` | `active_correction` | `blocked_findings` | `source_branch` | Close Cell product, range and selected-delete corrections | Owner fixes only these findings and returns the frozen artifact to focused review. |
| `WI-CELL-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Cell deleted ranges, retention, erase and files | Handover backlog; do not start. |
| `WI-AMBIENT-RADIO-001` | `active_correction` | `active` | `source_branch` | Close passive ambient Wi-Fi and Cell artifact | Close only the current review/corrections; hand over all parent assembly. |
| `WI-PURPOSE-SETTINGS-001` | `active_correction` | `awaiting_focused_closure` | `source_branch` | Close purpose settings containment retry | Close the existing cancellation/key-bound backoff and orchestration-test correction; no Hilt/runtime collector successor. |
| `WI-PURPOSE-PUBLICATION-001` | `planned` | `not_started` | `not_started` | Wire actual purpose authority publication | Handover backlog; do not start. |
| `WI-SHARED-FILE-ASSEMBLY-001` | `committed_held` | `active` | `held_branch` | Close shared portable file assembly review | Close only the existing frozen e3f2ecd review and directly reported corrections. |
| `WI-SHARED-HISTORY-UNION-001` | `active_correction` | `active` | `source_branch` | Close five-source recent, live and selected history union | Add only the two narrow producer bridges needed by this already-started union, then close its current focused review. |
| `WI-SHARED-HISTORY-BRIDGES-001` | `allowed_dependency` | `not_started` | `not_started` | Add the two producer bridges required by the active history union | Parent assigns the two narrow bridges only if the active union reviewer confirms they are required. |
| `WI-SHARED-HISTORY-CONSUMERS-001` | `planned` | `not_started` | `not_started` | Implement shared history feature consumers | Backlog only; do not start unless a later bounded assignment includes an owner receipt with actual authored-code evidence. |
| `WI-SHARED-HISTORY-DAY-RANGE-001` | `planned` | `not_started` | `not_started` | Complete all-six structural day, range and Today history | Handover backlog; do not start. |
| `WI-UI-SURFACES-001` | `planned` | `not_started` | `not_started` | Wire truthful shared product surfaces | Handover backlog; do not start. |
| `WI-SOURCE-ACTIONS-EXPLANATION-001` | `planned` | `not_started` | `not_started` | Complete start, settings, source actions and recording explanation | Handover backlog; do not start. |
| `WI-CROSS-SOURCE-PRIVACY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete cross-source retention, transfer, deletion and no-resurrection | No new cross-source wave; finish current source artifacts and hand over the rest. |
| `WI-NONSTEPS-EFFECTS-001` | `planned` | `not_started` | `not_started` | Qualify non-Steps widgets, achievements and effects | Handover backlog; do not start. |
| `WI-RUNTIME-SETTLEMENT-001` | `active_correction` | `blocked_findings` | `source_branch` | Close active shared runtime settlement correction | Close the current six-plus-P0 correction and focused tests only; no source activation. |
| `WI-CROSS-SOURCE-RECOVERY-001` | `planned` | `not_started` | `not_started` | Complete cross-source correction, recovery and provenance | Handover backlog; do not start. |
| `WI-INTEGRATION-SCENARIOS-001` | `planned` | `not_started` | `not_started` | Author complete real integration scenario sources | Handover backlog; do not start. |
| `WI-DEC-CONTROL-EVIDENCE-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve exact automatic CONTROL evidence lifetime and shape | User/product owner decision; implementation must not invent a duration. |
| `WI-DEC-RETENTION-PRIVACY-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve source/purpose retention and privacy copy | User/product/privacy approval. |
| `WI-DEC-AMBIENT-LOCATION-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve or reject expanded ambient Location | User/product approval. |
| `WI-DEC-RADIO-MODES-IDENTITY-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve optional active radio modes and identity products | User/product/privacy decision after measurement. |
| `WI-DEC-PRESSURE-ELEVATION-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve or reject calibrated Pressure elevation | User/product decision; do not infer elevation. |
| `WI-DEC-CROSS-MIDNIGHT-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve cross-midnight counting and presentation | User/product decision before shared day implementation. |
| `WI-DEC-ROLLOUT-WRITER-001` | `decision_required` | `not_applicable` | `not_applicable` | Approve rollout evidence channel and writer retirement | User approval after final evidence. |
| `WI-DEC-V28-PREMISE-001` | `decision_required` | `not_applicable` | `not_applicable` | Confirm the unshipped v28 premise and development database handling | Release owner confirms premise before validation handling is selected. |
| `WI-ASSEMBLY-GATE-001` | `planned` | `not_started` | `not_started` | Complete and freeze the implementation assembly | Handover backlog. Parent may integrate only closed bounded scopes; no assembly freeze yet. |
| `WI-VALIDATION-HOST-SCHEMA-001` | `gate_deferred` | `not_started` | `not_started` | Run the final host, compile, migration and schema batch | Do not execute until the phase stop is explicitly lifted. |
| `WI-VALIDATION-DEVICE-001` | `gate_deferred` | `not_started` | `not_started` | Run representative Android source-to-product evidence | Do not execute until explicitly authorized after host/schema convergence. |
| `WI-VALIDATION-MEASUREMENT-001` | `gate_deferred` | `not_started` | `not_started` | Measure quality, battery and cutover behavior | Do not execute until explicitly authorized. |
| `WI-FINAL-INTEGRATION-001` | `gate_deferred` | `not_started` | `not_started` | Perform final review and local integration | Handover backlog; no final integration during implementation-only. |
| `WI-PUBLICATION-ACTIVATION-001` | `approval_required` | `not_applicable` | `not_applicable` | Obtain separate publication and activation approval | Wait for explicit user approval after local completion. |

## Explicitly unstarted successor backlog

Parent freeze inspection confirmed these named successors were unstarted and owners were told
not to create them. They remain planned unless a later bounded assignment includes an owner
receipt with actual authored-code evidence:

- `WI-ACTIVITY-RANGE-001` — Implement Activity structural range product.
- `WI-STEPS-AMBIENT-FILES-001` — Implement Ambient Steps file actions.
- `WI-SHARED-HISTORY-CONSUMERS-001` — Implement shared history feature consumers.

## Update protocol

1. Keep existing `TODO-*` and `WI-*` IDs stable. Add a child `WI-*` only for a coherent
   independently ownable artifact, decision or gate.
2. Update `WORK_ITEMS.json` first. Do not put a competing status in this Markdown file.
3. Change state only from concrete evidence: exact commit/ref, owner receipt, focused review,
   local integration or executed validation on the exact commit.
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
