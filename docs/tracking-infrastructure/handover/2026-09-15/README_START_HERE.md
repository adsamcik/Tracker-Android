# Start here — Tracker tracking-infrastructure handover

Read HANDOVER_PROMPT.md completely. It is standalone and intended for an agent with zero chat
knowledge. The receiving agent must continue **implementation only** and author tests without
executing/compiling them or running any quality/device/CI gates until the complete final assembly.

This is a local `dev/v10` handover checkpoint authorized by the user on 2026-09-15, not a proven
six-source implementation or activation/release. No push occurred. Old remote `dev/v10` was
`0460f12a54a3550244f8e8ea5c9fb1f5e2ef27d2`; the ZIP receipt identifies the new exact local HEAD.

## Package contents

- HANDOVER_PROMPT.md: copy/paste continuation instructions, goals, scope and non-negotiable rules.
- CHECKPOINT_RECEIPT.json: exact clean local dev/v10 HEAD, phase, publication boundary, hashes,
  source file inventory, reference count, explicit unvalidated status and exclusions.
- source/: complete committed source tree, all modules/build logic/resources/manifests/test
  sources, full designs, vision, TODO/decision/status/evidence/rollout ledgers and this handover.
- tracker-tracking-history.bundle: complete Git objects reachable from dev/v10 and original
  local tracking source refs. This is the transport for unpublished commits and original ancestry.
- branch-and-path-inventory.json: original branch tips, commit/path maps, checkpoint ancestry and
  `git cherry` disposition. `-` is patch-equivalent, `+` means not patch-equivalent; neither alone
  proves that an older alternate implementation should be reapplied. Consult ASSEMBLY_MANIFEST.md.
- local-assembly-history.json: first-parent local assembly/checkpoint commits.
- QUARANTINE_DO_NOT_APPLY/: exact tracked patches and dirty/untracked source copies from the
  six-path protected root and two frozen old Steps drafts. These are unaccepted, not implementation.
- quarantine-inventory.json: original heads, statuses and per-file SHA256, distinguishing untracked.
- USER_VISION_ORIGINAL.txt: supplied vision attachment, if accessible; durable VISION_AND_SCOPE.md
  is also present. Private local config, caches, SDK/signing material, logs and unrelated worktrees
  are deliberately not copied.

The outer ZIP has a separate ZIP_RECEIPT.json beside it. The package-generation script is committed
with the handover; the ZIP is a generated transfer artifact, not a second source-of-truth checkout.
The source snapshot boundary before these final handover documents is
`c63443ca7eb16692aad4094c5e3450d5faf260f6`. Use CHECKPOINT_RECEIPT.json for the later final docs HEAD.

## Original-machine checkpoint

The clean committed dev/v10 checkout is `.worktrees/tracking-infra-integration` under the source
workspace. The root checkout remains detached and protected; do not switch/stage it. Source refs
and worktrees are deliberately retained for handover archaeology, overriding generic cleanup for
this dated transport. Thirty-eight original source worktrees and the coordinator assembly ref are
accounted for; only the two frozen old drafts are dirty outside the protected root. The receiving
machine need not recreate these worktrees: its clean transferred dev/v10 is the new baseline.

Generate another transfer artifact only into a NEW output directory inside the explicitly named
workspace. The committed New-HandoverPackage.ps1 refuses overwrite and never deletes drafts:

```powershell
& <clean-dev-v10-path>/docs/tracking-infrastructure/handover/2026-09-15/New-HandoverPackage.ps1 `
  -Repository <clean-dev-v10-path> -ProtectedRoot <original-workspace-path> `
  -OutputDirectory <new-directory-inside-original-workspace> `
  -OriginalVisionFile <original-attachment-path>
```

This generates transport artifacts only. It does not run Android/build/test/schema/CI gates.

## Safe receiving procedure

1. Unzip into a new transfer directory, not over a dirty checkout. Read receipt/prompt/manifest.
   Artifact existence/hash inspection is transport integrity, not Android behavior validation.
2. Inspect local branch/head/status/worktrees. Do not assume a remote clone has the unpublished work.
3. If the receiving dev/v10 is already at the receipt HEAD, use its clean worktree directly.
4. If it is only the old base, fetch from the local bundle into NEW handover refs; do not force
   update an existing local dev/v10 or overwrite its working tree:

```powershell
git fetch <absolute-bundle-path> dev/v10:refs/remotes/handover/dev-v10
git log -15 --oneline --decorate refs/remotes/handover/dev-v10
git merge-base --is-ancestor dev/v10 refs/remotes/handover/dev-v10
```

If local dev/v10 is clean and an ancestor, switch its clean checkout to dev/v10 and fast-forward
locally with `git merge --ff-only refs/remotes/handover/dev-v10`. If divergent, stop and inspect
the actual commits; never reset/force/overwrite. A new clean isolated worktree at the transferred
HEAD is also safe for inspection while the user decides how to preserve divergence.

To preserve all original source refs without overwriting any receiving branch, fetch their bundle
namespace to fresh handover remote refs:

```powershell
git fetch <absolute-bundle-path> 'refs/heads/codex/ti-*:refs/remotes/handover/codex/ti-*'
```

Do not push fetched refs. Do not apply quarantine patches to assembled source. A full `git clone`
of the bundle into a NEW directory is another offline option; inspect its refs and explicitly select
dev/v10. The source/ tree is useful for reading but is not a Git checkout and lacks archaeology.

## What to do next

Read the required full documents in the prompt, reconcile current static assembly seams, initialize
the goal and stable-ID TODO ledger, then continue source-local outstanding implementation from
CURRENT_STATE_AND_REMAINING_WORK.md. Ask only unresolved product/privacy decisions, especially
AUTO-005's control evidence lifetime/shape. Do not rebuild completed manual Steps/import/numeric
waves or enable dormant writers to pretend the final device gate passed.
