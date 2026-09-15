# Unaccepted historical drafts — do not apply

The user previously explicitly excluded these from publication and review scope. The 2026-09-15
handover preserves their evidence so nothing is lost, but does not accept or integrate them.
They may contain stale duplicate APIs, speculative unfinished importer/awards work, old schema
snapshots and assumptions superseded by coherent reviewed commits. Never copy/apply/stage them
wholesale over the new dev/v10. Review an individual idea from scratch if a concrete remaining
product need requires it; original file ownership/protection still applies on the source machine.

Protected root original HEAD: `ffd5d372fafafceb7d9d595b95e47b89b949de83`, detached.
Exactly six dirty paths must remain untouched:

```text
feature/import-export/src/main/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImport.kt
feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportCollisionTest.kt
feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/DatabaseImportTest.kt
docs/TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.docx
docs/TRACKING_INFRASTRUCTURE_IMPLEMENTATION_ORCHESTRATOR_PROMPT.md
feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/DashboardManualStartDecisionTest.kt
```

Frozen draft worktrees both start from `a347df9a25e2902b3d9951639f2303e9806f7645`:

```text
.worktrees/ti-steps-portable-import — codex/ti-steps-portable-import
.worktrees/ti-steps-qualified-awards — codex/ti-steps-qualified-awards
```

The package inventories every tracked dirty and untracked source path in these three scoped
checkouts, stores a binary-capable tracked diff against original HEAD, and copies exact existing
file bytes with SHA256. Deleted paths are represented in the diff/status, not recreated as empty
files. Original unchanged base files are reconstructible from the Git history bundle. Both frozen
source refs are also retained. No root/frozen staging, cleaning, reset, rebase or modification occurs.

Other unrelated architecture/design/game/Tracebox/etc worktrees are out of this tracking handover.
Their omission is deliberate scope protection, not evidence that they were deleted or integrated.
Private/generated configuration is not a transferable implementation artifact.
