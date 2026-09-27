# Independent provider/host source review — 2026-09-27

Reviewer: fresh review-only provider_host_review; no edits or recursive delegation.
Scope: original full user conversation/TASK_CONTEXT, supplied AGENTS.md, approved provider-freedom
plans and selected reconciliation family, parent b23b496e2f, entire bounded final source/test diff.
Evidence: static source/diff inspection only. IMPLEMENTED_UNVALIDATED.

## Initial findings addressed

- Capture-only foreground mask was compared with the full hosted union, rejecting otherwise lawful
  source-only capture while Location baseline was hosted. Separate exact mask/membership checks.
- Wi-Fi cancellation join unbounded; Cell ignored cancelled-actor completion. Retained exact actor,
  registration, claim and receipt block replacement until actual settlement.
- Wi-Fi/Cell/Steps demand selection borrowed unrelated capture identities; Steps rejected joins
  discarded its predecessor. Exact session/run/lease matching, staged candidate rollback and
  foreign-disable fences preserve current ownership.
- Activity installed successor before predecessor removal. Changed physical requests now use
  acknowledged predecessor-first retirement and honest retryable gaps.
- Shared claimed close discarded logical ownership before acknowledgements. Existing cutoff/receipt
  paths retain incomplete joins and release only after typed acknowledgement.
- Source inspection caught missing extension imports, undefined close helpers, outdated close/mask/
  successor/Activity fixtures; corrected without executing tests or changing from executed results.

## Follow-up regression/race findings addressed

- Deletion UI cleanup waited for preserved background host shutdown. It now waits only for live
  membership after the same durable STOP/writer acknowledgement.
- Background-only host publication incorrectly selected empty capture Location feed for games.
  Derived host-plus-live feed routing fixes this regression. Existing capture-wide capability and
  freshness limits remain explicitly pending the shared feature adapter.
- Capture writer guards lost physical host-loss fence while teardown metadata remained. Require
  both physical host and live membership, preserving that previous containment.
- Location host delivery lacked the shared pre-foreground deadline. Ticket deadline now includes
  authority reads and main dispatch, with repeated exact owner/ticket/generation checks.
- Cancelled concurrent same-identity request could retire a sibling's accepted host. Private
  abandonment release atomically checks accepted ticket and does not revoke sibling requests.
- Delayed service release callback could stop an acknowledged successor. It rechecks current host
  before stopSelf. Recorded as exact static service trace; no focused harness for that interleaving.

## Final reviewer result

“Bounded static review complete. No remaining material findings in the applied provider/host diff
  after the final fixes.” Rechecked claimant rollback, acknowledged retirement, Activity replacement
  ordering, capture/host state, FGS union authentication, deletion cleanup, feed routing and ticket
  retirement. Authored test sources agree with the changes.

This receipt is not whole-task completion. Optional radio-host representation and feature Location/
sunrise contracts await confirmation and implementation. Global Location exclusivity, source-aware
feature/game freshness and complete six-source assembly remain open. Test sources are uncompiled
and unexecuted. No Gradle, compilation/tests, lint/Detekt/schema/diff-check, emulator/device/UI/
battery/CI/release/rollout gates, provider/writer activation, push or integration occurred.
Main dev/v10 remains clean at 13f1a99871. Protected paths and preserved drafts remain untouched.
