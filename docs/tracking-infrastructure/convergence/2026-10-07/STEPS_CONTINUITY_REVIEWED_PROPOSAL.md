# Steps continuity: reviewed proposal for T03

Date: 2026-10-07. **DESIGN ONLY; NOT APPROVED OR IMPLEMENTED.**
Production input: `5b19ca833596b602643e99d291c4ceae0d3b5cc6`; runtime/database
inputs in the existing 25-file design snapshot are unchanged from its d2b/b0ab pins.
The plan commit changes documentation only. No execution or activation occurred.

This narrows [Astra's proposal](../2026-10-04/STEPS_CONTINUITY_REPAIR_DESIGN.md)
following independent review by `/root/remaining_runtime_inventory_1007`.
Its earlier generic caller-currentness changes must not be implemented unchanged.

## User-visible behavior

Changing quality, or changing another source while Steps stays selected, must not
lose the intervening real session steps. Keep one continuing counter registration
and recording identity. Request five-second batching for adaptive direct Steps
throughout its live recording; other sources continue adapting normally.

End, Off, deselection, consent/permission loss, counter reset, process/boot change,
changed real owner/writer/data epoch and failed acceptance remain real boundaries.
System-provider all-day counting and hourly/enable/open copies remain unchanged.
Legacy/custom physical requests do not silently adopt the new contract.

More frequent requested delivery than the current fifteen-second live setting is
a power trade-off; Android delivery and battery impact remain unmeasured.

## Smallest connected implementation

1. Add one nullable Steps recording-origin manifest revision to existing source
   membership, with additive migration/checksum/raw-query compatibility. Null keeps
   legacy semantics; no inferred continuity is backfilled into older records.
2. Keep generic fresh caller acceptance, current-source checks and replay tied to
   the latest global manifest and chosen source set. Do not teach them to accept
   stale policy/manifests or infer currentness from old Steps identities.
3. After authenticating the latest requested demand vector, let the broker retain
   only the exact continuing Steps demand through the verified recording-origin
   mapping. Replace other demands normally in the existing transaction.
4. Retain the exact origin receipt only while a verified live Steps demand needs
   it. Superseded-reference cleanup must distinguish actual retirement from still
   being required by Steps, including an expected predecessor and older pinned
   references. Unrelated historical references remain ambiguous/errors. The
   source-session coordinator must not falsely acknowledge a receipt as retired.
5. Preserve the original recording-plan attribution, counter authorization,
   accumulator and physical listener. Acknowledge the latest desired plan through
   existing applied-plan state; do not relabel old recording events with it.
6. Validate continued source/consent/run/lease/boot/process/epoch/writer/owner
   identity during demand mutation, activation and transactional ingress. Missing,
   retired, denied or incompatible authority ends continuity. Never reconstruct
   a retired receipt or search backward for a compatible earlier lifetime.
7. Have retained Steps qualification use the next actual Steps recording boundary
   for explicitly marked memberships. Reuse one source-local lifetime predicate
   across existing history, maintenance/carrier and portable readers. Legacy null
   memberships retain the strict original manifest boundaries.

No new owner, ledger, counter payload, fact version, runtime-claim interface or
general authority framework is proposed. Generic guards remain strict.

## Compatibility and footprint

Update existing manifest membership/integrity/migration/raw DAO shapes;
authoritative manifest reconciliation; dispatcher/broker/referenced-receipt
retirement and source-session cleanup; activation's effective demand mapping;
adaptive factory/shared Steps runtime/registration attribution; durable ingress;
retained history/count-domain/carrier/maintenance/portable lifetime readers.

Existing portable manifests permit ordered nonconsecutive revisions. Authenticate
the full native timeline, then project original Steps recording manifests and
their unchanged facts. No new wire version is indicated by static inspection;
composed existing-format compatibility must still be authored and later tested.
This is source-contract compatibility work, not a new backup feature.

## Required source cases

- Counter 1000, two quality changes, counter 1009: exactly nine steps once with
  the same listener, demand, receipt, authorization and recording origin.
- Another source changes while latest caller/source-set checks stay strict.
- Exact origin receipts survive repeated reconciliations; unrelated references
  remain rejected, and End/Off finally retire the continuing demand and receipt.
- No carry across permission loss, consent/epoch/writer changes, failed acceptance,
  physical restart, process/boot change or counter reset.
- Durable retries/duplicates do not advance a baseline early or count twice.
- Current plan acknowledgment and original event configuration both stay truthful.
- Legacy migration/checksums, pruning/retained reading, missing middle membership,
  and existing-version portable round trip preserve or reject data honestly.

All source tests are **NOT_RUN** until the complete assembly convergence gate.
Approval is required before implementing the recording lifetime, fixed request,
storage field and reader semantics.
