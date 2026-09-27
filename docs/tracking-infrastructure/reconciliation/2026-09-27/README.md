# Implementation input reconciliation — 2026-09-27

Task: "Reconcile implementation inputs. Inventory committed candidates and preserved drafts, select their final versions, and resolve overlapping changes without losing existing work."

Phase: **IMPLEMENTATION_ONLY**. Static inventory, source selection/reconciliation and authored test source only. No Gradle, compilation, executed tests, lint, Detekt, generated schema/drift, diff-check, emulator/device/UI, battery, CI, release, provider/writer activation, integration into dev/v10 or push. This is not the global convergence freeze.

## Inventory checkpoint

- Main: clean local `dev/v10`, `13f1a99871b59d29b3d059f374fc415490da6943`.
- Continuing candidate: `codex/source-native-map-context-root`, input `63ef296026e440e4a5667235fe0769a03004eb47`; retain its adaptive Location implementation and later decision corrections.
- `INPUT_INVENTORY.json`: 51 registered worktrees, 54 local heads, 295 distinct commits not ancestral to main, and exact status/byte hashes for 56 draft paths in five dirty worktrees. One stale/prunable worktree is recorded, not pruned.
- `REFERENCE_AND_PRESERVATION_SCOPE.json`: all 137 current references (54 heads, 70 remote-tracking references, nine tags), ancestry and five unrelated `.external-wip` files preserved without selection or mutation. No fetch/push/ref deletion occurred.
- `PATH_OVERLAPS.json`: 385 distinct paths among the current primary candidates/drafts; 47 have multiple input versions. These counts describe the snapshot, not implementation completion.

Ancestry proves that a commit is already reachable. Patch equivalence is a narrower source comparison, not a behavior proof; `git cherry` omits merge-only residuals. Non-equivalent old commits require explicit disposition, not replay by branch name/date. Preserve all original source refs and dirty files.

## Protected and historical material

The six protected root paths in `handover/2026-09-15/QUARANTINE.md` and the frozen importer/awards drafts remain excluded from application/staging. The current machine has no dirty live counterpart of those old root/frozen worktrees. Their historical receipt is available; the bounded repository-doc search found no quarantine payload/archive/bundle. Do not claim that a receipt contains the missing original draft bytes or regenerate them as originals. This reconciliation changes none of the protected paths and deletes nothing.

Old audit `a8fd308df9` has unique source/test work. The six-commit successor `4facc94f40` is not proof that all 21 old fixes were integrated. Retain the old ref and separately trace its unreconciled behaviors, especially old outbox/start-result/deferred-materialization, lifecycle/Location-health and correlated-health/test work; never reintroduce stale APIs wholesale.

## Current selection work

Selection and overlap resolution are in progress. The final record will identify exact source versions and any intentionally excluded activation, superseded draft, unrelated work or behavior requiring later adaptation. No claim that selection is complete or that candidate source runs is made at this inventory checkpoint.

Settled user decisions remain in `planning/2026-09-26-provider-freedom/DECISION_CLOSURE.md`: 48h CONTROL-only retention; optional default-off Wi-Fi/Cell notification host; each export produces only the selected format; clean all-data backup required, with restore choice still pending. This reconciliation introduces no new format/restore design or provider activation.
