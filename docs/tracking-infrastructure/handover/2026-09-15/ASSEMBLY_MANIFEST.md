# Tracking assembly manifest — 2026-09-15

## Meaning and authority

This manifest records source disposition, not six-source completion. Every newly assembled source
family is **IMPLEMENTED_UNVALIDATED**. No tests, compilation/builds, Gradle, lint, Detekt, Room schema
generation/drift, `git diff --check`, emulator/device/UI, battery, CI, release, or rollout validation
was executed during this closure. Static inspection and independent static acceptance do not prove
that authored tests compile/pass or that Android providers, process boundaries, UI, or power behave
correctly. Earlier passing evidence belongs only to its recorded old exact commits.

The user requested local `dev/v10` assembly for transfer on 2026-09-15. That is a narrow handover
exception to the older no-unvalidated-integration rule, **not** an implementation-complete gate,
validation readiness, writer/provider activation, publication, or release approval. The original
local/remote checkpoint was `0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2`. No push is authorized or
claimed. The generated `CHECKPOINT_RECEIPT.json` identifies the final transferred local HEAD;
do not treat this manifest's intermediate inspection HEAD as the final receipt.

This inventory was reconciled after source assembly commit
`c63443ca7eb16692aad4094c5e3450d5faf260f6`, with no source merge in progress. Final handover
documentation follows that source checkpoint; the ZIP receipt supplies its exact final HEAD.
Original tracking branch tips are kept
in the Git bundle and machine-readable branch/path inventory; do not delete their archaeology or
reapply older branches merely because their names remain visible.

## Reviewed family tips and disposition

All fifteen source worktrees below were clean when inspected with read-only Git status. The full
38-source-worktree inventory found only the two frozen drafts dirty; every other source was clean.
Accepted
means the source slice's recorded static-review disposition, not execution or product acceptance.
All fifteen listed source tips are ancestors of the completed source assembly HEAD.

| Family / branch | Exact clean tip | Disposition at inspection |
| --- | --- | --- |
| Session/import/numeric Steps — `codex/ti-steps-import-actions` | `43757982088e292b9bb97f6218706d6665c5e3b6` | Merged ancestor; preserve manual Steps/import/action/numeric work, do not restart it |
| Ambient Steps read/export — `codex/ti-ambient-transfer` | `8ff5ec428cb191c1ab585d6355712d5ead85a8af` | Merged ancestor; default-off sessionless primitives, not complete ambient product |
| Ambient Steps maintenance — `codex/ti-ambient-maintenance` | `d998dbb4cfbc9a54a16b9ba979e6a2cf53526f7b` | Merged ancestor; retention/deletion/continuity boundaries retained |
| Pressure origins/maintenance — `codex/ti-pressure-import` | `94199906088b9d40b623fce1413c2fd44c6b470d` | Merged ancestor; source-specific import/read/reexport/imported deletion |
| Pressure purpose/writer boundary — `codex/ti-pressure-writer-activation` | `a410dcb86b04b1943035c1505a61592e044c7139` | Merged ancestor; branch name does not authorize activation |
| Pressure contained detail — `codex/ti-pressure-detail` | `c27cc1c182a7a32688e0500fcb7777567386550d` | Merged ancestor; exact source truth composed with imported Steps |
| Activity transfer/imported retention/callers — `codex/ti-activity-transfer` | `b13765db7dca0be8e09f230ec3bb2989cca9d12c` | Merged ancestor; latest one-lineage retention and real worker invocation retained |
| Activity applied-plan/WAL projection — `codex/ti-activity-projection-adapter` | `f269b562abcf513931545578bfb87f67dd3b440a` | Merged ancestor; captured and CONTROL lanes remain separate |
| Activity local maintenance/deletion — `codex/ti-activity-maintenance` | `dd19b4d332c2a343798350ccb74e75c6ae018880` | Merged ancestor; exact selected-session ownership/fencing retained |
| Shared Activity/Pressure product UI — `codex/ti-activity-pressure-product-ui` | `100d3e9d27af66ac9ca9d2a889852d1bd0bec7a5` | Merged ancestor; authoritative union replaces older alternate Activity-only UI |
| Protected Location provenance — `codex/ti-location-wal-provenance` | `6e9d84f38fbb2132fb2d068ec4967e43d551ed23` | Merged ancestor; no canonical Location replacement/cutover |
| Cell maintenance/export/import — `codex/ti-cell-maintenance` | `f8dd5d6d115c63b8cc771313901978098e2a5608` | Merged ancestor; includes importer uncertainty/quality closure |
| Cell captured history — `codex/ti-cell-product-read` | `79056b454b634ae65ed1ba85e2fea32cebba5cb3` | Merged ancestor in `2c395724d`; read/authority union remains unvalidated |
| Wi-Fi maintenance/export/import — `codex/ti-wifi-maintenance` | `315ac6a8347b8e38ff6b64876734d9c265c7463c` | Merged ancestor in `df4ad74b0`; includes reviewed imported-namespace closure fixes |
| Wi-Fi direct manual attempt — `codex/ti-wifi-manual-action` | `750bd6c423a62267e14bcb58e010b4b6d268a545` | Merged ancestor in `c63443ca7`; bounded direct-demand-only action and exact paired permission repair, not ambient cadence |

Family merges retain predecessor source refs and their actual ancestry; an assembly commit's name
does not mean every outstanding product/runtime/action contract in that family is implemented.
Use `CURRENT_STATE_AND_REMAINING_WORK.md`, the stable-ID `IMPLEMENTATION_TODO.md`, and current code
for the remaining boundary.

## Equivalent and superseded refs — do not replay wholesale

Read-only `git merge-base --is-ancestor` and `git cherry <assembly-head> <source-ref>` were used to
separate original ancestry from patch equivalence. For an ancestor, empty `git cherry` output means
there are no source-only commits. For a nonancestor, `-` means patch-equivalent; `+` means that exact
patch is not equivalent. Neither sign proves a product gate or authorizes applying an alternate
implementation. Shared merge corrections may intentionally supersede a non-equivalent patch.

The following nonancestor refs were entirely patch-equivalent at the inspection HEAD:

| Original retained ref | Exact tip | Equivalent source-only commits |
| --- | --- | --- |
| `codex/ti-activity-product-read` | `d0bf3bf591f44185668ad2256e885eb9028e468c` | 6; included through the reviewed Activity transfer prerequisites |
| `codex/ti-pressure-product-read` | `97941e8b19f186a89f8e5cd188360dde1aa13769` | 2; equivalent read work already included |
| `codex/ti-steps-import-admission` | `4db55146eccad9e39adc7725076cb2783d92e330` | 1; equivalent accepted Steps admission already included |
| `codex/ti-steps-import-product` | `f93b373ef6392fdca8727776bb052e292fd3f9ce` | 3; equivalent imported Steps product work already included |
| `codex/ti-steps-summary-authority` | `3f62d5c644b44277088097800a85637a955cfdb0` | 1; equivalent accepted summary authority already included |
| `codex/ti-wifi-fact-model` | `26347367c44e93639c59d38fd43a845000335d0f` | 1 source-only patch; its earlier fact lineage is already ancestral through Wi-Fi maintenance |

`codex/ti-activity-ui` at `48d4d3e678bd9200d0e0d434fa5fd0cc3d6e43eb` has six equivalent read
prerequisites and two non-equivalent UI commits (`f364cbc42427f19679b9e8a0a62aa4323f68c414` and
`48d4d3e678bd9200d0e0d434fa5fd0cc3d6e43eb`). Its alternate presentation is deliberately superseded
by the reviewed shared Activity/Pressure product union at `100d3e9d`; do not layer it over that union.

Earlier fact, acquisition, adapter, maintenance, retention-product, and read/export refs whose tips
are ancestors remain retained source archaeology, not extra work to merge. In particular Pressure
maintenance and deletion-repair refs share `ed408932367f0083c38ed2752dd441565b304bed`. The generated
inventory records every original tracking ref rather than silently removing duplicate names.

The completed Wi-Fi maintenance merge includes `codex/ti-wifi-product-read` at
`adde063f39273831440a065304364728c098dee2` as an ancestor. Its older overlapping read branch was
not reapplied. The original 29/3/18 pre-merge source-only counts were intermediate, not omissions.
After assembly, the only nonancestor refs are the six equivalent refs above and deliberately
superseded Activity-only UI. The generated final branch inventory records exact dispositions.

## Local source assembly commits

All are coherent local commits under adsamcik, with no hooks/quality gates executed:

```text
2158c8a57 Steps coordinator checkpoint
47436a667 Ambient Steps product/export
9accd612e Ambient Steps maintenance
bccd718ea Pressure origins/maintenance
4f95c6ee3 Pressure purpose/writer boundaries
919a5f831 Pressure contained detail + imported Steps/shared UI preservation
549a171cf Activity transfer/import/retention/callers
61af9c535 Activity adapter closure
bf8324da8 Selected Activity deletion + imported Steps day-repair union
af4041d3e Shared Activity/Pressure recent/live/UI + imported Steps union
104465b68 Protected Location provenance, no cutover
2a6f2cbd0 Cell origins/maintenance/import correction
2c395724d Cell captured history + retained Activity worker fixture
df4ad74b0 Wi-Fi facts/history/maintenance/import + five-lane recovery union
c63443ca7 Exact Wi-Fi manual-start/platform/paired permission prerequisites
```

Exact commands used include `git -c core.fsmonitor=false merge --no-ff --no-commit <source-ref>`,
exact-path `git add -- <reviewed-paths>`, static staged source/stat inspection and
`git -c core.fsmonitor=false -c core.hooksPath=NUL commit -m <message>`. The final local transport
uses a clean integration worktree switch to dev/v10 and `git merge --ff-only` of this assembly.
No `git diff --check` or cached check, build/test/CI or remote mutation was executed.

## Frozen dirty drafts and protected root — quarantine only

Both frozen draft refs point at committed base
`a347df9a25e2902b3d9951639f2303e9806f7645`:

- `codex/ti-steps-portable-import`, `.worktrees/ti-steps-portable-import`: 53 dirty/untracked paths
  at this inspection.
- `codex/ti-steps-qualified-awards`, `.worktrees/ti-steps-qualified-awards`: 80 dirty/untracked paths
  at this inspection.

Their committed base is already an assembly ancestor. That fact **does not include or accept their
dirty drafts**. The old importer/awards drafts are unreviewed, unfinished, potentially duplicate or
superseded work. Preserve their binary-capable patch, original file bytes, status and hashes only
in `QUARANTINE_DO_NOT_APPLY`; never copy/apply/stage/merge them wholesale into assembled source.

The protected detached root remains at `ffd5d372fafafceb7d9d595b95e47b89b949de83`, with exactly
these six excluded paths (three tracked modifications and three untracked files):

```text
feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImport.kt
feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportCollisionTest.kt
feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportTest.kt
docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx
docs/TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md
feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardManualStartDecisionTest.kt
```

No protected-root or frozen-draft path was altered/staged/committed during manifest creation. Their
quarantine receipt, not an accepted source table, records exact bytes and provenance. Unrelated
architecture/game/design/Tracebox worktrees, private configuration, generated caches, signing
material and device logs are outside the tracking transfer; omission is not deletion.

## Bounded assembly seams and remaining implementation

Preserve these concrete source seams during the remaining union and resume:

- Additive Room entities, accessors, SQL/FKs/indices, full collected-data clear and source-specific
  DAO queries must include every accepted source without replacing sibling additions. v28 JSON is
  intentionally stale/deferred; released v27 JSON remains immutable. Whether any v28 shipped and
  safe handling of old development-v28 databases require explicit migration authority.
- Imported Activity retention authenticates one complete lineage at a time and runs before pending
  signal deferral. Cell and Wi-Fi captured maintenance each run once, before source-event WAL
  pruning; pending signals defer legacy radio cleanup only. Preserve both sources' outcome,
  cancellation, pending/WAL-order tests and all worker constructor callers.
- Ingress/recovery hints are source-local, finite and nonactivating. Retain Steps, Pressure,
  captured Activity, Cell and Wi-Fi hints, every constructor slot and both parents' tests. Do not
  make a poisoned sibling block another source or CONTROL effects.
- Incompatible Steps providers retain exact purpose-specific owner scopes and sequence spaces;
  do not overwrite or retire Ambient ownership while closing only SESSION_CAPTURE. Durable native
  radio sequence **0 is valid**; callback-local completeness counters are a separate domain.
- Wi-Fi import reserves cursor, immutable-fact and orphan aggregate-reference identities with one
  bounded reciprocal UNION. All source/purpose fence digests reserve opaque namespaces without
  widening deletion mutation; exact capture deletion retains its typed precedence.
- Cell import reauthenticates incoming and stored lineage against the conservative checked lower
  uncertainty bound and exact upper bound, and exact known/weak quality-bucket formulas. Keep
  global owner/scope fences, correction/receipt/marker authority, full-clear and no-resurrection.
- Shared live/recent/detail composition preserves imported Steps origin and nullable/unavailable/
  partial/materializing truth. No source-only discovery or Location UI relies on `sample_count`;
  no missing Steps becomes fabricated complete zero. Exact physical replacement ownership remains
  internal even when logical product entries are grouped.

The bounded static worker review reported duplicate imports and a retained Activity worker test
factory missing the new Cell service argument; the coordinator applied concrete source fixes.
The completed Wi-Fi union retains all 13 previous worker tests plus five complete Wi-Fi test
blocks with inert per-source defaults, five independent fact-lane recovery hints, both native
callback barriers, and exact sequence zero. A bounded independent source review accepted paired
fine/coarse repair on Android 12+, fine-required success and authoritative readiness recheck;
newer Dashboard qualified Steps composition and Wi-Fi retirement barriers remain intact.
Unknown compilation/Hilt/Room/test/provider defects remain deferred, not disproven.

Still **not implemented completely**: Wi-Fi/Cell imported product composition and authenticated
reexport/roundtrip, imported selected deletion/retention/source erase and product/file actions;
Cell exact live selected-session deletion; Activity source-wide erase/action convergence;
Pressure imported retention/source erase/action/day/Calendar/runtime convergence; Ambient Steps
portable import, settings/remediation and complete shared/numeric product wiring; protected
Location's full canonical-only vertical and measured shadow/cutover decision; all six exact
manual/automatic/approved-ambient runtime/product/lifecycle/consumer gates. AUTO-005 control-only
evidence lifetime/shape remains an unresolved product/privacy choice. These are source-specific
remaining slices, not permission to introduce a generic importer/tombstone/materializer framework.

Read `CURRENT_STATE_AND_REMAINING_WORK.md` and the exhaustive stable-ID TODO before selecting the
next bounded worktree. Continue production and test-source authorship **implementation only**.
The final validation/fix batch starts only after every planned piece exists, decisions are resolved
or contained, exact convergence inputs are frozen, and the phase switch is explicitly authorized.
Local integration, publication and provider/writer activation remain distinct decisions.
