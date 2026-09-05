# September 5 checkpoint publication and restart

The user authorized committing and pushing the accepted checkpoint and handover on 2026-09-05.
This supersedes the earlier local-only/no-push instruction **for this one normal `dev/v10` push**.
It does not authorize release, deployment, tags, force push, source activation, destructive
migration, or future publication. Previously protected root edits and unaccepted draft work remain
excluded pending explicit scope clarification; their inventory is in `MACHINE_HANDOVER.md`.

## Accepted scope and verification

- Checkpoint before this documentation-only update: `7499558bdde0a9ab28ae9223b5e00cc9f385e53c`.
- Tested code: `b5bc3674cd6fafbcd50fddd98a78581428756b6a`; production implementation ends at
  `21437e97cd041dd8e07c5177c52a404be0e15aab`.
- A fresh `git fetch --no-tags origin refs/heads/dev/v10` and `git ls-remote --heads origin
  refs/heads/dev/v10` both resolved `ffd5d372fafafceb7d9d595b95e47b89b949de83` before publication.
  `git rev-list --left-right --count FETCH_HEAD...dev/v10` returned `0 161`.
- The accepted source checkpoint passed `ciUnitTest checkRoomSchemaDrift` and full
  `ciCheck --continue`; exact commands, corrected failures, timings, cached results and three
  existing skips are recorded in TI-B204 through TI-B209 in `VERIFICATION_MATRIX.md`.
- This update changes only Markdown. It requires diff/link checks, not another unchanged-code
  Gradle run. A push may trigger the repository's existing CI; local green results do not assert
  remote CI success. No physical Steps, rendered UI, listener, battery or OEM proof is added.

This document records publication scope and preparation, not a self-referential claim about its
own remote commit hash. The publishing task must verify the final remote HEAD after the push.

## Continue on another machine

Use a new, nonexistent destination directory; never overwrite an existing checkout:

```powershell
git clone --branch dev/v10 https://github.com/adsamcik/Tracker-Android.git 'D:\Github\Tracker-Android-checkpoint'
Set-Location 'D:\Github\Tracker-Android-checkpoint'
git status --short --branch
git rev-parse HEAD
git ls-remote --heads origin refs/heads/dev/v10
git log -10 --oneline --decorate
git diff --check
git merge-base --is-ancestor 7499558bdde0a9ab28ae9223b5e00cc9f385e53c HEAD
```

Match HEAD to the publishing task's confirmed commit. If the branch advanced, inspect the new
commits and reconcile the current boundary; do not reset or force old state over later work.
Read [MACHINE_HANDOVER.md](MACHINE_HANDOVER.md), its copy-paste continuation prompt, and the ordered
design/evidence documents. Its implementation limits, Steps-first priorities, protected drafts,
toolchain/identity setup, and source/device gates remain current. Its local-only statements describe
the pre-publication snapshot and do not undo this one-time authorization.

The committed [Vision and Scope](VISION_AND_SCOPE.md) matches the supplied September 5 text.
Continue toward useful independent sources and measured quality per battery; withholding unsafe
numbers is necessary containment, not completion of the product.

## Optional offline backup

The previously verified ZIP/bundle remains a local generated artifact, not a tracked repository
file or hosted release. It represents the earlier exact `7499558bd` checkpoint; the only missing
changes are this publication documentation. Its receipt and checksum remain valid:

- Bundle: `tracker-android-dev-v10-2026-09-05.bundle`, 105982608 bytes.
- Bundle SHA-256: `75F17C3FFF6654547AA9532FFE33853F452BAC0649A974EDCFCF73CDFF1DDA65`.
- ZIP: `tracker-android-checkpoint-2026-09-05-7499558bd.zip`, 104132294 bytes.
- ZIP SHA-256: `686019854A22827255C9003CAD4A3A83B41CAC468561015C16E74E6E1CC40889`.

Do not commit generated Git bundles, archives, caches, secrets or Android user data. The bundle's
standalone clone/connectivity checks prove portable Git history, not destination build or device
behavior. Copy it out of the source integration checkout's `build` directory before cleaning.
