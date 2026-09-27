# Shared radio preparatory source review

Status: **IMPLEMENTED_UNVALIDATED; whole shared admission task incomplete**.

Candidate: `codex/source-native-map-context-root`. Input: `29a653a9e4`.
Reviewed preparatory source commit: `b9fdbae62c`.
Main local `dev/v10` remains `13f1a99871`.

## Completed bounded slice

- `core/base/.../database/data/RadioPurposeItemQualifier.kt`: source-specific item shape plus
  one common historical qualification rule. Reconstruct each entire authorization vector from
  immutable demands. Qualify per demand using original provider/received times, activation,
  retirement, provider lifetime, destination age and uninterrupted membership through every
  supplied revision. Return original item indexes per destination and their physical union;
  an empty union is not a product fact.
- `WifiWalCarrierAuthenticator.kt` and `CellWalCarrierAuthenticator.kt`: extract existing shape
  predicates unchanged. Existing capture binding, carrier, payload version and legacy destination
  qualification semantics remain intact.
- `RadioPurposeItemQualifierTest.kt`: authored both-source cases for background 10-minute/session
  1-minute ages, mixed subsets, session join/leave, replacement demands with unchanged purpose/
  consent, revoke/rejoin, missing/prior consent, immutable constraint substitution, boot/generation,
  lifetime/age/retirement boundaries, equal effective timestamps, invalid items, bounded dependency
  rejection and original received-time reconstruction.

No runtime, ingress, projector, maintenance or history caller uses this new qualifier yet. Its
historical input must come from a reader that authenticates the complete stored timeline and the
exact callback revision. The pure function cannot prove that a reader omitted no transitions;
registration, policy/consent, deletion and physical carrier remain separate required predicates.

## Independent review

Review-only agent `/root/shared_radio_review` received the full original user conversation and
follow-ups, including the exact latest request, approved plan/decisions, candidate code/diff,
authored tests and implementation-only/protected-path boundary. It read the selected and preserved
radio contract inputs independently. No recursive delegation, edits or execution.

It confirmed the missing shared-item contract marker and the multiple current rejection paths.
It found one concrete performance issue in the initial kernel's nested membership search. This
was corrected before commitment: precompute per-revision demand-ID sets and each demand's earliest
uninterrupted suffix once, then use a direct item-time check. Work is bounded by timeline rows plus
callback demands multiplied by timeline revisions and items, instead of scanning every member
for every item and revision. Final static rereview found no remaining concrete finding in this
bounded slice; extracted legacy predicates are equivalent and fixture contracts are consistent.

## Remaining work and pending decision

`CONTRACT_DECISION.md` is the concrete versioned physical-radio contract proposal. The question
tool request is recorded verbatim in planning `TASK_CONTEXT.md` section 28. No answer has been
received or inferred. AGENTS.md requires confirmation before changing the stored contract or
wiring new semantics. Provider/writer activation remains deferred regardless of that answer.

After confirmation: transport the marker through immutable requests/demands/registration and WAL;
load bounded complete historical timelines; wire lawful physical union and destination-specific
membership into admission; derive each destination's own counts/intervals/wall/completeness;
apply the same rule to retained projection/history/maintenance and original-time replay; author
focused source tests and obtain independent review of that complete diff.

No Gradle, compilation, tests, lint, Detekt, Room schema/drift, diff-check, device/emulator, UI,
battery, CI, release or rollout checks ran. No provider/writer activation, new `dev/v10`
integration, push or cleanup. Original refs/drafts and the six protected paths are preserved.
