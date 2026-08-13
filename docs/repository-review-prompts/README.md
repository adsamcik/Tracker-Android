# Tracker Android Repository Review Prompt Pack

This pack contains standalone prompts for independent agents reviewing the
[`dev/v10` branch of `adsamcik/Tracker-Android`](https://github.com/adsamcik/Tracker-Android/tree/dev/v10)
through GitHub. The agents do not need a local clone, shell, Android SDK,
emulator, or write access. They may use official web documentation when the
specialist prompt calls for current external standards.

## Prompts

1. [Architecture review](01-architecture-review.md)
2. [Code quality and Android code-practices review](02-code-quality-review.md)
3. [Software-engineering practices review](03-swe-practices-review.md)
4. [CI and pipeline review](04-ci-pipelines-review.md)
5. [Unit and automated-test quality review](05-test-quality-review.md)
6. [Android API, platform, and Play compliance review](06-android-api-compliance-review.md)
7. [Agentic instructions review](07-agentic-instructions-review.md)
8. [Cross-review synthesis](08-synthesis.md)

## How to use the pack

Give each specialist agent exactly one prompt. The repository and branch are
already fixed to `https://github.com/adsamcik/Tracker-Android` and `dev/v10`.
Replace `{{AS_OF_DATE}}` with the review date, or instruct the agent to fill it
with the date on which the review begins.

Each specialist must resolve `refs/heads/dev/v10` from GitHub at the start of
its review and report the resulting immutable commit SHA. Dispatch the agents
close together and confirm that they resolved the same SHA before synthesis. If
the branch advances between reviews, rerun the out-of-date reviews or explicitly
pin a coordinated run to one SHA; do not combine findings from different trees.

Run the seven specialist reviews independently. Then give their complete reports
to the synthesis agent with the synthesis prompt. Keeping the reviews independent
reduces shared assumptions; the synthesis prompt is responsible for reconciling
conflicts and removing duplicates.

## Important GitHub baseline note

The prompt pack was tailored after inspecting the local repository on
2026-08-13. A direct GitHub remote check on that date resolved
`refs/heads/dev/v10` to
`f0469c384670bb125fdb24d3b8cb00def1cf4c1c`. That SHA is a verification record,
not a permanent pin: reviewers must resolve the branch again when they start.
A GitHub-only agent cannot review local commits or working-tree changes that
have not been pushed to `dev/v10`, including this prompt pack until it is
published. Never let an agent silently fall back to the default branch, another
ref, a pull-request head, or a cached/local checkout.

## Repository context supplied for orientation

The prompts deliberately require agents to verify this context at the selected
commit rather than trust it:

- Multi-module Kotlin/Android application with Compose, Hilt, Room, Coroutines,
  Flow, WorkManager, MapLibre, and convention plugins in `build-logic`.
- The locally inspected build used compile/target SDK 37, min SDK 26, AGP 9.3.1,
  Kotlin 2.4.10, Gradle 9.6.1, Java bytecode 17, and JDK 21 in CI.
- The major boundaries are `:app`, `:core:*`, `:tracker:api`,
  `:tracker:control`, `:tracker:engine`, `:stats:*`, `:domain:*`,
  `:sensor:*`, and feature implementation/API modules.
- The app is privacy-sensitive and location-heavy. It has a long-running
  foreground tracking service, Room schema/versioning concerns, native MapLibre
  and SQLite binaries, optional controlled network egress, and recovery paths
  for process/service failure.
- The tree contains hundreds of JVM tests, a smaller instrumented-test suite,
  source-scanning architecture fitness tests, lint baselines, GitHub Actions for
  unit tests/build/lint/CodeQL/Codacy, and repository-specific QC agent skills.

## Evidence standard shared by all prompts

An acceptable finding identifies a concrete risk or control, explains impact,
and cites an immutable GitHub blob URL with a line range. Search-result pages,
directory listings, mutable branch URLs, document claims without source-code
confirmation, and an agent's memory are not sufficient evidence.

The prompts use this severity scale:

- `P0`: demonstrated release blocker, data-loss path, severe security/privacy
  exposure, or fundamental correctness failure.
- `P1`: likely major reliability, policy, security, privacy, or architectural
  failure requiring near-term action.
- `P2`: material engineering weakness or debt with a plausible impact.
- `P3`: useful improvement with limited current impact.

Do not report style nits. Do report verified strengths, because the synthesis
needs to distinguish absent controls from controls that are already effective.
