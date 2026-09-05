# Tracking infrastructure: cross-machine handover

Last updated: 2026-09-05

Publication update: [PUBLICATION_HANDOVER.md](PUBLICATION_HANDOVER.md) records the user's subsequent
one-time push authorization and GitHub clone instructions. The local-only statements below
describe the exact earlier bundle checkpoint; implementation limits and remaining work are unchanged.

## Read this first

This is a **local implementation checkpoint, not completion or activation of the six-source
program**. The accepted code boundary is `b5bc3674cd6fafbcd50fddd98a78581428756b6a`; this document
is delivered in a documentation-only successor. The transfer receipt beside the Git bundle names
the exact final `dev/v10` HEAD and bundle SHA-256. Repository commits and documents are authoritative;
do not recover instructions from the corrupted historical chat.

The user-supplied [Vision and Scope](VISION_AND_SCOPE.md) is committed at `e7c9d63fb` and is the
product north star. It matches the supplied September 5 text. The detailed architecture remains
authoritative for implementation. Safety containment is useful progress, but withholding numbers
is not a substitute for a usable source-only vertical or measured quality per battery.

Production implementation ends at `21437e97c`; `b5bc3674c` is the verification-only correction that
teaches the existing architecture test to recognize the stronger generation-operation gate while
still rejecting missing/reordered fences and eager database resolution.

## Transfer accepted work without touching another checkout

The handover folder contains a standalone `tracker-android-dev-v10-2026-09-05.bundle` and a transfer
receipt. Copy that folder to the other machine. The bundle contains committed `dev/v10` history,
including these documents. It does **not** contain Android user data, Gradle/SDK caches, machine
credentials, the six protected root edits, or the two frozen unaccepted drafts.

Use a new, nonexistent destination directory. Do not clone over, reset, clean, or overwrite an
existing checkout. In PowerShell, replace the two example paths with actual local paths:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'D:\Transfer\tracker-android-dev-v10-2026-09-05.bundle'
```

Compare the hash with the transfer receipt **before cloning**; stop on any mismatch. Then:

```powershell
git clone --branch dev/v10 'D:\Transfer\tracker-android-dev-v10-2026-09-05.bundle' 'D:\Github\Tracker-Android-checkpoint'
Set-Location 'D:\Github\Tracker-Android-checkpoint'
git bundle verify 'D:\Transfer\tracker-android-dev-v10-2026-09-05.bundle'
git status --short --branch
git rev-parse HEAD
git log -10 --oneline --decorate
git diff --check
```

Match the SHA-256 and HEAD to the transfer receipt before continuing. A bundle clone's `origin`
points to the local bundle, not GitHub. Do not interpret that as publication or run `pull`/`push`
against an assumed remote. If using an existing repository instead, first inventory its HEAD,
branches, dirty paths, and ancestry; stop on divergence. Never force the checkpoint over local work.

No push, release, tag, deployment, external rollout, feature/source activation, destructive migration,
or remote configuration is authorized. The source machine's unchanged `origin/dev/v10` tracking ref
is `ffd5d372fafafceb7d9d595b95e47b89b949de83`; it was not refreshed and is not a live remote claim.

## Exact implementation boundary

- Achievement evaluation skips unavailable metrics. Unverifiable old Steps/XP/meta achievement
  progress is hidden without deleting rows. Queued unlock notifications now use the same qualified
  repository membership and matching tier; a legitimate first unlock does not wait for background
  progress persistence. One snapshot read serves a batch, and a failed read remains retryable.
- Qualified Dashboard/live, selected-day Calendar, Trip Detail, and active-widget Steps remain useful.
  Calendar observation cancels when hidden. Widget Steps require exact physical identity, qualified
  source evidence, and complete coverage. Covered zero is valid; missing/partial/materializing/
  unverifiable data is not zero. Raw badges, legacy counters, shared Steps, and unsafe insights are
  withheld where a qualified positive replacement remains absent.
- Raw Steps cannot complete goals or award points/XP. Independently useful exact-segment
  distance/duration and mini-game behavior remain. Points, XP/profile, scores, event acknowledgement,
  and exploration replay use the existing deletion-generation gate. A game begun in an old
  generation cannot persist a result after deletion/reopen. Sequential durable event draining
  prevents an invalidation from cancelling the commit-to-dirty handoff.
- Session XP uses persisted segment-end time and a half-open civil-day cap in the current device
  zone. This is not historical stored-zone authority for future qualified Steps goals. A foreground
  mini-game may still need explicit stop after deletion; stale finalization is rejected. No new
  physical listener-retirement claim is made.
- Existing Steps exact attribution, authenticated retained facts, logical replacement-run history,
  selected deletion, correction/retention containment, and portable exporter remain protected.
  Pressure has dormant facts and exact selected deletion; radio/Location/Activity admission work
  is foundation evidence, not completed product verticals.

`TrackingRolloutState` schema v4 defaults all sources to `CONTAINED`, retained legacy product
stages, and zero capture masks. Only Steps V1/V2 and Pressure have installed executable candidate
contracts; none was activated. Legacy generation 1 destination ownership must not be described as
proof of ordinary Steps-only acquisition. There is no ordinary first-activation caller.

## Vision alignment and remaining work

| Vision requirement | Checkpoint contribution | Still not proven or complete |
| --- | --- | --- |
| Every source useful alone | Exact Steps-only history/read foundation; no new provider dependency | Ordinary/manual physical source-only gate; other five thin verticals |
| Fresh durable facts, once in effect | Existing source-local WAL, attribution, replay/correction and ownership retained | Provider-to-product and crash/reboot evidence; imported-origin no-resurrection |
| Honest useful product | Qualified live/day/detail/widget values; no fabricated zero or raw awards | Positive qualified goals/streaks/achievements and remaining badges/share consumers |
| Better quality per battery | No new provider demand; finite widget read; hidden Calendar observer cancelled | Representative quality/latency/battery measurement; meaningful tier separation |
| Explicit automatic and ambient | No accidental capture or ambient activation | Exact control-separated automatic Steps, then default-off Ambient Steps |
| Reuse and privacy | Existing repositories, Room transactions and generation gate; no new platform/schema/egress | Portable import/round trip and remaining source-specific retention/export/delete gates |

The source-by-source matrix is in [Implementation Status](IMPLEMENTATION_STATUS.md). Do not resume
another framework or another broad reward audit before advancing the positive Steps path.

1. Inventory the machine and read all applicable `AGENTS.md`. Read `VISION_AND_SCOPE.md`, then the
   complete technical design, adaptive design, `CONTINUATION_HANDOVER.md`, `EXECUTION_PLAN.md`,
   `DECISIONS.md`, `IMPLEMENTATION_STATUS.md`, `VERIFICATION_MATRIX.md`, and `ROLLOUT_RUNBOOK.md`.
   Reconcile documentation claims against code. Older inventory sections are explicitly historical.
2. Configure the repository's JDK 21, SDK 37, and dependency access locally. Do not transfer secrets
   or silently change dependency versions. `settings.gradle.kts`, module builds, and `build-logic`
   are authoritative. Bundles do not carry Git author configuration: verify `git config user.name`
   and `git config user.email`. In the new clone, set the required repository-local identity if
   missing with `git config --local user.name adsamcik` and
   `git config --local user.email adsamcik@users.noreply.github.com`. Investigate a conflicting
   configured identity rather than silently using it. Inspect current environment capability
   instead of copying old absolute SDK paths.
3. Attach one representative physical `TYPE_STEP_COUNTER` device. Execute the exact manual
   capture-set `{Steps}` scenario with no Location/Activity/Pressure/Wi-Fi/Cell/control/ambient
   demand, a fresh positive post-baseline delta, `RECORDING`, one canonical `MATERIALIZED` writer,
   and production `QUERYABLE` evidence. Inspect rendered UI separately and retain platform
   `sensorservice` evidence before/during/after stop. The harness alone proves neither physical
   listener removal nor rendered Compose state. Its disposable-install activation/cleanup is
   test-only; never run it against personal data or treat it as rollout authorization.
4. Finish source-qualified positive Steps goals/streaks/achievements without raw-summary shortcuts;
   reconcile portable import in bounded source-owned slices; prove correction-complete round trips,
   retained origin mapping, retention and no-resurrection. Preserve typed incomplete states.
5. Complete automatic Steps with explicit Activity `CONTROL` separation and exact generation-2
   authority, then default-off Ambient Steps. Keep known generation-2 fixture/authority gaps visible.
6. Continue Pressure, protected Location, Activity, Wi-Fi, and Cell as thin independent verticals.
   Each needs source-appropriate query/UI and correction/retention/export/deletion/device proof.
   Keep radio active attempts bounded/direct-demand-only and cadence claims opportunistic.

Final retention durations, optional Step control corroboration, Ambient Steps continuity/provider
choice, extra ambient Location product, measured active-radio tiers, calibrated Pressure elevation,
and cross-midnight presentation remain product choices. This checkpoint does not choose them.

## Verification and integration rules

Exact commands, outcomes, corrected failures, and evidence boundaries are in TI-B204 onward in
[Verification Matrix](VERIFICATION_MATRIX.md). The source machine had no attached Android device.
At code HEAD `b5bc3674c`, `ciUnitTest checkRoomSchemaDrift` passed in `19m 46s` and the full
`ciCheck --continue` passed in `9m 46s`. The host XML inventory contains 9,392 tests, zero failures
or errors, and three unchanged Android-dependent skips; valid cached results are included.
Release lint passed with existing warnings/baseline findings, not a warning-free claim.
Host/Robolectric/static/build checks do not prove physical sensors, rendered UI, process death,
reboot, FGS, battery, OEM behavior, or rollout. Rerun affected tests on the other machine; a copied
green result is evidence of the source snapshot, not of the destination toolchain.

Use one Gradle invocation globally. Every invocation for this checkpoint uses:

```text
--no-daemon --no-parallel --max-workers=1 -Pksp.incremental=false --console=plain --no-configuration-cache
```

`ciUnitTest` is the repository host aggregate; `ciCheck --continue` includes host tests, release lint,
Detekt, Room drift, architecture, SQLite linkage, release-evidence fixtures and metadata checks.
Do not weaken gates for SDK/auth/sandbox failures. New worktrees start from clean local `dev/v10`;
assign one primary owner per overlapping file, commit coherent exact-path chunks, rebase onto the
latest local `dev/v10`, verify, then merge locally from the clean main checkout. Remove only clean
fully merged worktrees/branches. Do not push.

## Work intentionally left on the source machine

Do not claim these drafts are accepted or included in the bundle:

| Source worktree | Exact HEAD | Preserved draft |
| --- | --- | --- |
| `G:\Github\Tracker-Android\.worktrees\ti-steps-portable-import` | `a347df9a25e2902b3d9951639f2303e9806f7645` | 37 tracked modifications, 16 untracked files, `+2669/-164`, nothing staged |
| `G:\Github\Tracker-Android\.worktrees\ti-steps-qualified-awards` | same HEAD | 66 tracked modifications, 14 untracked files, `+6008/-662`, nothing staged |

The detached source root remains at `ffd5d372fafafceb7d9d595b95e47b89b949de83` with exactly six
protected paths and the hashes listed in `CONTINUATION_HANDOVER.md`. Do not alter, stage, commit,
reset, clean, or push them. Do not rebase or copy either dirty draft wholesale into accepted work.
If a draft is needed on the destination machine, arrange a separately reviewed preservation
transfer; absence of these drafts is not evidence that their work was integrated or discarded.

## Copy-paste continuation prompt

> Resume Tracker Android from the verified local bundle checkpoint on `dev/v10`. First compare
> HEAD and bundle hash with the transfer receipt, inspect all worktrees/dirty paths, and read
> AGENTS plus the ordered documents above. Treat VISION_AND_SCOPE.md as the product north star,
> commits and code as current evidence, and older chat as non-authoritative. The September 5 code
> checkpoint improves qualified numeric presentation and deletion-safe side effects; it does not
> complete or activate six-source tracking. Follow the remaining-work sequence above, prioritize
> useful positive Steps behavior and its representative physical gate, preserve existing Location
> and exact source authority, and use bounded independent worktrees only where work truly separates.
> Never fabricate zero, infer source ownership from sampleCount/wall overlap, use QUIESCED for
> deletion, activate two canonical writers, start providers for enrichment, or claim battery/radio
> reliability without measurement. Commit and merge accepted work locally; no push, release,
> rollout, destructive migration, or protected-draft mutation is authorized. End at a verified,
> documented checkpoint with exact remaining gates.
