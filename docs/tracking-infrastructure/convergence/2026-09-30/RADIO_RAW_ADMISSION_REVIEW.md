# Radio raw-admission checkpoint

Date: 2026-09-30. Candidate: `codex/source-native-map-context-root`.
Parent: `4bfc7192cd`. Status: **IMPLEMENTED_UNVALIDATED**.

## Bounded implementation

- New version-six Wi-Fi and Cell callbacks retain available network/tower details.
  Their stable delivery identity binds those details. SIM/subscriber identifiers
  are not persisted and diagnostic string formatting does not expose tracked data.
- Source ingress authenticates the physical generation and the exact callback
  authorization. A bounded historical timeline selects individual readings for
  their original recording purpose, activation/retirement interval and freshness.
  The callback header and payload are not narrowed to one destination's requirements.
- Cached readings outside the physical lifetime do not invalidate an independently
  valid reading. A broken or closed capture session does not invalidate independently
  qualified background readings. A denied callback revision can retain readings
  that were authorized when observed; it does not authorize observations after denial.
- The shared Wi-Fi request retains readings within the maximum age useful to any
  participating purpose. Each destination still enforces its own limits.
- Version-five wire layouts/digests and summary fixtures retain their saved version.
  Narrow compatibility changes in the classifier, adapter, maintenance and history
  reader preserve those existing records while the remaining version-six product
  implementation is assembled.

## Independent source review

Review-only subagent `/root/radio_admission_review` received the original task and
follow-up messages, approved decisions, candidate source/diff and the implementation-only
boundary. It did not edit source, execute validation or delegate further agents.

It identified constructor handling for authenticated denied callbacks, physical
endpoint handling, capture-failure isolation, malformed-child classification,
saved-version identity and an invalid test-fixture property. Those findings were
corrected in source. Its bounded raw-ingress rereview found no further blocker.
The staged-boundary review additionally required explicit version-five encoding in
the reconstructed Wi-Fi fixture; that correction was restaged before commitment.

## Authored evidence and limits

Regression sources cover mixed authorization revisions, delayed callbacks, raw
header preservation, replay, stale-session/fresh-background observations, closed
or corrupted capture sessions, denied callbacks, physical boundaries, damaged
authorization history, malformed child items, retained details and legacy versions.
These are authored tests, not passing-test evidence.

Background/session projectors, native history, child propagation, maintenance,
privacy retraction, UI and file round trips remain pending. This checkpoint does
not establish a complete source-to-product chain or integration readiness.

No Gradle, compilation, tests, lint, Detekt, Room schema/drift, diff-check,
device/emulator, UI evaluator, battery, CI, release or provider-activation commands
were run. No merge, push or draft/worktree cleanup occurred. Local `dev/v10`
remains `13f1a99871b59d29b3d059f374fc415490da6943`.
