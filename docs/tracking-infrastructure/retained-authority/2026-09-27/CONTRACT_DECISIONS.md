# Retained authority: unresolved contracts

2026-09-27. User AGENTS.md requires confirmation of new/changed behavior, architecture and data
decisions. No approval is inferred from elapsed time, an unanswered question or broad completion
intent. The settled 48h CONTROL lifetime and selected-format-only export remain unchanged.

## Current question: background Location Off versus retained history

The plan (`DATA_GAP_CLOSURE.md` section 4) says future capture Off/permission denial are not
retrospective erasure; background Off stops future collection and explicit erase is separate.
Current Location code deletes ambient associations when background eligibility or its envelope
changes, and reads require current acquisition consent. Disabling or re-enabling the source can
therefore delete/hide independently retained history.

Question-tool recommendation: Off, reconfiguration and re-enable preserve historically authorized
data. Historical reads continue to enforce source/purpose proof and current deletion/retention
fences. Explicit erase removes only the selected purpose, preserving independently authorized
siblings. Alternative: background Off also erases its history, with truthful UX and exact sibling
fencing. No answer received; dependent changes are unapplied.

This confirmation must reconcile future acquisition consent, historical access and explicitly
revoked retention approval as distinct existing authorities. It must not turn a revoked historical
purpose into current collection authority or erase another purpose through shared sample storage.

## Previously pending full backup restore contract

User intent is settled: a clean backup of all durable data, not a raw database archive alone.
`planning/2026-09-26-provider-freedom/DECISION_CLOSURE.md` D4 records the unresolved restore target:
recommended recoverable replacement of existing Tracker data after confirmation, versus empty
installation only. That question remains unanswered; do not reask or infer a target choice here.

The documented concrete snapshot/restore/schedule/file behavior also remains proposed. Before
dependent edits, inventory registered Room and independent databases, DataStore/SharedPreferences,
durable owned files (including custom maps), deletion sidecars and local diagnostic stores; settle
what is operational versus historical. Current database-by-database copying is insufficient.

Restoring historical data must not restore current provider leases/generations, pending CONTROL
starts, active-session recovery commands, permissions or external URI grants. Current deletion/
retention and recoverable replacement need one consistent authority boundary. The documented
stopped-state/explicit-resume behavior and schedule destination/re-enable rules belong to that
concrete approval, not generic import permission. Protected DatabaseImport/drafts remain untouched.

## Settled corrections and remaining execution seams

The bounded carried-consent and source-clock fixes implement existing purpose independence;
they introduce no new erase scope, consent semantics, schema, dependency or UX.

Activity/ambient Steps still need exact callback settlement while a sibling retains the physical
owner. Preserve quiescence guards until the actual issued-purpose retirement acknowledgment is
implemented and reviewed. Use existing source owners/barriers; do not add a generic authority
framework or silently count a policy toggle as provider settlement.

CONTROL 48h is approved, but its exact production owner/expiry/compact capture-proof composition
remains the separately pending automatic/runtime contract. Do not manufacture automatic support,
renew TTL during replay/reconfiguration or destroy independently captured Activity evidence.
