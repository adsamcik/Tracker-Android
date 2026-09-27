# Shared radio admission contract decision

Status: **proposal awaiting confirmation; IMPLEMENTATION_ONLY**.

## Original request

> **Fix shared Wi-Fi/Cell admission.** Qualify individual items for each purpose so stricter session requirements cannot discard valid background observations or lend consent backward.

The approved behavior is `RUNTIME_GAP_CLOSURE.md` section 5.2a: keep one immutable minimized
delivery snapshot/unit, retain the lawful union, independently qualify every destination using
historical observation-time authority and the same surviving immutable demand, preserve original
times during replay, and preserve generation 1 semantics. All original user messages and accepted
corrections are in the inherited review context and `planning/2026-09-26-provider-freedom/TASK_CONTEXT.md`.

## Confirmed current gap

The selected candidate and the preserved generation 2 drafts have no explicit shared-item
admission-contract marker. Both generations use radio payload version 5. Capture writer
generation 2 identifies a session product writer, not an ambient-only physical registration;
using it as a global switch would change behavior when a session joins or leaves.

The current controllers choose the strictest age; Wi-Fi runtime filters against the newest
global authorization start; ingress compares interval-start and interval-end authorization
revisions; captured carrier predicates require session binding; ambient projection aggregates
the entire snapshot. Changing only the shared age ceiling cannot satisfy the request.

## Recommended contract

1. Add one explicit **version 2 shared radio admission contract**. Carry it in the immutable
   physical request/registration identity and each originating demand contract, and stamp the
   durable callback as a new canonical radio WAL payload version. Old version 5 rows and old
   requests retain their existing interpretation. Do not use capture writer generation as the switch.
2. Keep one bounded, identity-free physical snapshot, one unit and content-derived identity.
   Physical normalization retains only items lawful for at least one supported destination.
3. Each destination accepts an item only if its immutable demand is authorized at the item's
   original provider time and survives in the callback's exact authorization vector. Demand ID,
   policy/consent, retention approval, session identity, boot and physical generation must match.
   A new/replacement demand cannot grant consent to earlier items. A revoked demand contributes none.
4. Age is evaluated per destination using the original received time. Each destination derives
   its own accepted interval, count, wall time and completeness. No accepted items means no fact,
   never an invented zero. A stricter session does not change continuing background semantics.
5. Admission, projection, maintenance/history and exact replay share the predicate. Read-only
   paths authenticate stored original evidence and never repair, activate a provider or create demand.
6. Keep preferred provider/writer bindings unchanged. The new contract remains a dormant candidate
   until the authorized final assembly/validation/activation transition.

Trade-off: explicit versioning requires transport through existing immutable contracts/readers,
but prevents old stored evidence from silently acquiring new meaning. An unversioned correction
would be smaller but would violate the approved legacy-semantics requirement; it is not recommended.

## Current authorized preparatory work

Extract the common pure item qualifier and author its focused regression sources. This work
implements the already-approved qualification rule without changing any caller or stored contract.
Wiring the new versioned contract depends on the decision above. This document is not approval.

Repository AGENTS.md requires confirmation of new architecture/data-contract decisions. Independent
review-only agent `/root/shared_radio_review` confirmed the missing marker in both selected and
preserved inputs. No checks ran, no provider/writer activation, no `dev/v10` integration or push.
