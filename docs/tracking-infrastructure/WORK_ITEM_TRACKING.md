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
Individual source-owner receipts are authoritative only for their owned artifact; generic
entire-effort statements cannot override parent global state or adversarial blockers.

## Frozen session scope

Scope expansion is frozen. Finish only artifacts demonstrably started before the boundary,
their directly necessary corrections/test source and focused static review. Preserve dirty
work. Later owner receipts prove Activity range, Ambient Steps files and shared history
consumers were already running before their queued freeze arrived; those exact artifacts are
preserved without allowing further expansion. Assembly is not frozen
and validation remains deferred. No agent push or provider/writer activation is authorized.

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
| `active_correction` | 1 |
| `active_documentation` | 1 |
| `active_governance` | 1 |
| `approval_required` | 1 |
| `committed_held` | 8 |
| `completed_accepted_slice` | 15 |
| `completed_foundation` | 1 |
| `decision_required` | 8 |
| `gate_deferred` | 4 |
| `partial_implemented` | 18 |
| `planned` | 14 |

Review states: `active` 7, `blocked_findings` 3, `closed_except_decision` 1, `closed_static` 17, `continuous` 2, `historical_accepted` 1, `mixed` 14, `not_applicable` 9, `not_started` 18.

Integration states: `held_branch` 15, `local_dev` 11, `local_only` 1, `mixed_source_refs` 14, `not_applicable` 9, `not_started` 18, `source_branch` 4.

Validation states: `deferred_implementation_only` 63, `not_applicable` 9.


## Work-item index

| ID | State | Review | Integration | Title | Next bounded action |
| --- | --- | --- | --- | --- | --- |
| `WI-TRACKING-REGISTRY-001` | `active_documentation` | `continuous` | `local_dev` | Maintain the authoritative work-item registry | Continue authorized tracking-only updates as owner receipts, correction closures and integration dispositions arrive; do not start production work. |
| `WI-HANDOVER-PRESERVATION-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve accepted transport and receiving seams | Preserve as historical evidence; do not reopen or expand without a bounded request. |
| `WI-REPOSITORY-SAFETY-001` | `active_governance` | `continuous` | `local_only` | Preserve repository, worktree and publication boundaries | Apply these boundaries to every owner receipt and local integration decision. |
| `WI-FOUNDATION-BASE-001` | `completed_foundation` | `historical_accepted` | `local_dev` | Preserve the accepted tracking safety foundation | Use as immutable architectural constraints for remaining items. |
| `WI-SCHEMA-MIGRATION-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close the bounded shared v28 schema assembly | Keep exact input fcc2162d held. Integrate only after its bundled source prerequisites close; do not start Pressure, Cell or radio schema assembly. |
| `WI-SCHEMA-CONVERGENCE-001` | `planned` | `not_started` | `not_started` | Complete all-source v28 schema convergence | Handover backlog. Assemble only after every contributing source contract is reviewed and the parent assigns the bounded final schema convergence. |
| `WI-AUTHORITY-PURPOSE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete source and purpose authority semantics | Do not start a new authority wave; consume only closed current-purpose artifacts when a bounded integration is assigned. |
| `WI-BROKER-PROVIDER-OWNERSHIP-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete one physical owner per source | Remain planned outside already-started runtime and purpose corrections. |
| `WI-LIFECYCLE-RUNTIME-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Close lifecycle, action and settlement semantics | Preserve the closed shared runtime contracts and do not begin producer propagation. |
| `WI-SOURCE-CALLER-GUARD-001` | `planned` | `not_started` | `not_started` | Guard every source runtime caller | Handover backlog. Implement only after broker/publication/producer propagation close; providers remain off. |
| `WI-LIFECYCLE-PRODUCT-PROOF-001` | `planned` | `not_started` | `not_started` | Prove RECORDING, MATERIALIZED and QUERYABLE transitions | Handover backlog. Implement only after provider/source product dependencies close; do not infer proof from current branches. |
| `WI-DATA-MAINTENANCE-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete durable admission, projection and maintenance | No new cross-source data-plane wave; finish only the active source and shared artifacts listed in this registry. |
| `WI-QOS-BATTERY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete honest acquisition and battery semantics | Preserve existing plan/callback primitives. Hand over remaining implementation hooks; actual measurement stays solely in WI-VALIDATION-MEASUREMENT-001. |
| `WI-STEPS-PORTABLE-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed imported Steps and portable round trip | Preserve; no new work in this scope. |
| `WI-STEPS-MANUAL-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve completed manual Steps vertical | Preserve until final convergence validation. |
| `WI-STEPS-NUMERIC-001` | `partial_implemented` | `closed_static` | `local_dev` | Preserve qualified Steps consumer infrastructure | Preserve the accepted numeric infrastructure. Do not duplicate engine numeric implementation; complete only the separate P5 producer receipt when assigned. |
| `WI-STEPS-AUTOMATIC-001` | `partial_implemented` | `closed_except_decision` | `local_dev` | Complete automatic Steps with bounded control evidence | Keep control behavior contained and inactive; do not invent a duration. |
| `WI-STEPS-AMBIENT-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Ambient Steps vertical | Preserve source-locally closed product 0c and file 220 inputs. Hand over execution authority, P5, settings, schema and shared day/UI work. |
| `WI-STEPS-AMBIENT-PRODUCT-CORRECTION-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close Ambient Steps base and product review findings | Preserve source-locally closed input 0c4728c2 with typed Unproven P5 behavior. Do not implement P5, execution authority, settings or shared day/UI in this artifact. |
| `WI-STEPS-AMBIENT-FILES-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Review the committed Ambient Steps file artifact | Preserve source-closed held input 220a2721. Do not add union export, continuation, registry/picker wiring or another feature. |
| `WI-STEPS-AMBIENT-EXECUTION-001` | `planned` | `not_started` | `not_started` | Implement Ambient Steps execution authority | Handover backlog. Implement under a new bounded assignment; never fake AMBIENT support on the session lane. |
| `WI-STEPS-AMBIENT-P5-RECEIPT-001` | `planned` | `not_started` | `not_started` | Implement the P5 Ambient/session count-domain receipt | Handover backlog. Implement the receipt only under a new bounded assignment and reuse existing numeric consumers. |
| `WI-PRESSURE-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete the Pressure source-to-product vertical | Preserve source-locally closed maintenance/bridge 045 and files 8ba. Hand over runtime/schema integration and the full day/range product. |
| `WI-PRESSURE-MAINTENANCE-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close Pressure maintenance privacy corrections | Preserve source-locally closed input 045cd7cf. Runtime barrier and all-source schema assembly remain separate dependencies. |
| `WI-PRESSURE-FILES-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close Pressure portable file review | Preserve reviewed source 8ba72248 and exact helper 76739a35. No new Pressure file feature; shared consumers integrate through their held artifacts. |
| `WI-PRESSURE-PRODUCT-001` | `planned` | `not_started` | `not_started` | Build the remaining Pressure day and range product | Handover backlog; do not start. |
| `WI-LOCATION-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete protected Location without a second writer | Finish only the active Location handoff correction; hand over product and ambient successors. |
| `WI-LOCATION-HANDOFF-001` | `committed_held` | `active` | `held_branch` | Close protected Location WAL-to-writer handoff | Hold exact input 921324f2 for the final focused review. Do not add new PersistenceProcessor lease, ingress, schema or provider features. |
| `WI-LOCATION-AMBIENT-PRODUCT-001` | `planned` | `not_started` | `not_started` | Implement approved passive ambient Location baseline | Handover backlog; do not start. |
| `WI-ACTIVITY-VERTICAL-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete captured Activity and control separation | Preserve locally integrated Activity actions/EOF source and finish only the held 5dc range/bridge review. Hand over wider UI/runtime work. |
| `WI-ACTIVITY-ACTIONS-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Preserve closed Activity source actions and file semantics | Preserve locally integrated Activity source actions and raw-EOF correction. Do not widen into the held Activity range/bridge artifact. |
| `WI-ACTIVITY-RANGE-001` | `committed_held` | `active` | `held_branch` | Implement Activity structural range product | Close only the existing Activity range plus eligible-bridge review at 5dc1d069. Do not start wider UI, file or runtime work. |
| `WI-WIFI-VERTICAL-001` | `partial_implemented` | `blocked_findings` | `source_branch` | Complete the Wi-Fi source-to-product vertical | Finish only the existing product/range/selected-delete correction; hand over retention/erase/files/UI. |
| `WI-WIFI-PRODUCT-CORRECTION-001` | `committed_held` | `active` | `held_branch` | Close Wi-Fi product, range and selected-delete corrections | Close only the current focused five-case review and preserve the range/selected-delete scope; no retention/source-erase/file successor. |
| `WI-WIFI-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Wi-Fi retention, erase and file actions | Handover backlog; do not start. |
| `WI-CELL-VERTICAL-001` | `partial_implemented` | `blocked_findings` | `source_branch` | Complete the Cell source-to-product vertical | Finish only the current correction/review; hand over retention, erase and files. |
| `WI-CELL-PRODUCT-CORRECTION-001` | `committed_held` | `active` | `held_branch` | Close Cell product, range and selected-delete corrections | Close only the current focused three-case review. Do not start retention, source erase, files or shared UI. |
| `WI-CELL-REMAINING-MAINTENANCE-001` | `planned` | `not_started` | `not_started` | Finish remaining Cell deleted ranges, retention, erase and files | Handover backlog; do not start. |
| `WI-AMBIENT-RADIO-001` | `active_correction` | `blocked_findings` | `source_branch` | Close passive ambient Wi-Fi and Cell artifact | Correct only the three remaining compensation/test findings at 5589026c and return to the same reviewer; hand over all guard/schema/runtime/DI/UI integration. |
| `WI-PURPOSE-SETTINGS-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close purpose settings containment retry | Preserve locally integrated Purpose source. Do not begin authority-issuer, Hilt/runtime reporting or feature-collector successors. |
| `WI-PURPOSE-PUBLICATION-001` | `planned` | `not_started` | `not_started` | Wire actual purpose authority publication | Handover backlog; do not start. |
| `WI-SHARED-FILE-ASSEMBLY-001` | `completed_accepted_slice` | `closed_static` | `local_dev` | Close shared portable file assembly review | Preserve locally integrated portable input. Do not add Ambient/radio formats, unreviewed range/bridge work or runtime activation. |
| `WI-SHARED-HISTORY-UNION-001` | `committed_held` | `closed_static` | `held_branch` | Close five-source recent, live and selected history union | Preserve owned-closed input c6aad1d9 and wire only statically closed producer bridges into a real nonempty chain. Do not start structural day/range or UI work. |
| `WI-SHARED-HISTORY-BRIDGES-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close the imported-history producer bridge contract | Preserve static-closed contract c6aad1d9 and assemble only the four producer adapters into shared history; no structural range or feature consumer work. |
| `WI-HISTORY-BRIDGE-ACTIVITY-001` | `committed_held` | `active` | `held_branch` | Implement the narrow imported Activity history bridge | Close only the current bridge portion in the 5dc review; keep wider UI/file/runtime work out of scope. |
| `WI-HISTORY-BRIDGE-PRESSURE-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close the narrow imported Pressure history bridge | Preserve source-locally closed input 045cd7cf and wire only this producer into shared history; do not start WI-PRESSURE-PRODUCT-001. |
| `WI-HISTORY-BRIDGE-WIFI-001` | `committed_held` | `active` | `source_branch` | Close the narrow imported Wi-Fi history bridge | Close only the existing Wi-Fi bridge review and supply the adapter to shared history. |
| `WI-HISTORY-BRIDGE-CELL-001` | `committed_held` | `active` | `held_branch` | Implement the narrow imported Cell history bridge | Close only the current Cell bridge review, then supply it to shared history. |
| `WI-SHARED-HISTORY-CONSUMERS-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Correct the started shared history feature consumers | Preserve source-locally closed consumer input ab6fdd92. Do not start Location/day/range or additional UI. |
| `WI-SHARED-HISTORY-DAY-RANGE-001` | `planned` | `not_started` | `not_started` | Complete all-six structural day, range and Today history | Handover backlog; do not start. |
| `WI-UI-SURFACES-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Wire truthful shared product surfaces | Preserve accepted detail/source-only shells and the active 707 consumer correction. Hand over remaining surface conversion. |
| `WI-SOURCE-ACTIONS-EXPLANATION-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete start, settings, source actions and recording explanation | Preserve existing manual start and source-action primitives. Hand over shared dispatcher, explanation and remaining settings/UI. |
| `WI-CROSS-SOURCE-PRIVACY-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Complete cross-source retention, transfer, deletion and no-resurrection | No new cross-source wave; finish current source artifacts and hand over the rest. |
| `WI-NONSTEPS-EFFECTS-001` | `partial_implemented` | `mixed` | `mixed_source_refs` | Qualify non-Steps widgets, achievements and effects | Preserve current effect collectors and Steps identity/retraction patterns. Hand over remaining non-Steps qualification conversions. |
| `WI-RUNTIME-SETTLEMENT-001` | `completed_accepted_slice` | `closed_static` | `held_branch` | Close active shared runtime settlement correction | Preserve source-locally closed input e71c739e as held. Do not begin producer propagation, schema wiring or source activation. |
| `WI-RUNTIME-PRODUCER-PROPAGATION-001` | `planned` | `not_started` | `not_started` | Propagate runtime generation and lifecycle contracts | Handover backlog. Do not start until Pressure/Location prerequisites close and a new bounded producer-propagation assignment is issued. |
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
| `WI-FINAL-INTEGRATION-001` | `gate_deferred` | `not_started` | `not_started` | Perform final review and local integration | Handover backlog; no final integration during implementation-only. |
| `WI-PUBLICATION-ACTIVATION-001` | `approval_required` | `not_applicable` | `not_applicable` | Obtain separate publication and activation approval | Wait for explicit user approval after local completion. |

## Scope-receipt classification

Point-in-time directory absence is superseded only by a concrete owner-authored-code receipt.
Current classification:

- `WI-ACTIVITY-RANGE-001` — Implement Activity structural range product.
- `WI-STEPS-AMBIENT-FILES-001` — Review the committed Ambient Steps file artifact.
- `WI-SHARED-HISTORY-CONSUMERS-001` — Correct the started shared history feature consumers.

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
