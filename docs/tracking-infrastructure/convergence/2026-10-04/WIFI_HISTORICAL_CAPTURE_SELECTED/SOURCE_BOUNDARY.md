# Historical Wi-Fi session capture selection — October 4

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. No assembly completion or integration readiness.**

Exact parent: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`.

## Source selection

- `before/` preserves all 14 working inputs exactly before Wi-Fi authoring. `BEFORE.json` pins those bytes.
- `BASE/` and `HEAD_BASE.json` are root-exported bytes from the exact parent.
- `AFTER/` preserves actual authored working bytes, including separately owned shared-file context.
- `SELECTED/` and `SELECTION.patch` select only this Wi-Fi slice against the exact parent.
- `AUTHORING_AFTER/` and `AUTHORING.patch` isolate the Wi-Fi authoring delta from the preserved before inputs. Root Cell R7 helper changes and product R11 action-origin changes are excluded from this delta.
- `FILES.json` pins 13 changed paths and one unchanged fact-contract input.
- `DEPENDENCIES.json` pins 64 read-context inputs. These snapshots are dependency context, not permission to stage held working drafts. The revised schema companion preserves historical 27→28 UNIQUE DDL, converts the secondary index in 28→29, creates fresh Room29 nonunique, and requires old28Unique/final29Nonunique in preflight.
- `TEST_SOURCE_INVENTORY.json` lists 15 newly authored source cases. None has been executed.

## Implemented chain

The v6 adapter independently qualifies the original indexes for every historical capture demand, then reuses existing logical fact IDs, original raw delivery identity, policy/consent/manifest/run bounds, child storage and the source lane. The callback's current or denied session header never becomes historical session authority. Last matching membership preserves one unchanged demand through unrelated authorization rotation; strict v5 attribution remains intact.

Classification retains the original callback envelope separately from the selected purpose. Group writes and cursor advancement share the existing Room transaction, so cancellation rolls back siblings. A legitimately erased or retained-out group settles independently. Other per-group integrity failures stay visible; denied or deleted callback headers cannot silently retire a v6 terminal failure for historical siblings.

Lifecycle and exact query reads select the exact purpose before bounded revision queries. Both load required child rows and requalify historical indexes. Coverage-only reads also authenticate the reused aggregate's own original delivery and children. History includes original callback envelopes referenced by selected facts and verifies each fact's own authority and owner.

Maintenance preserves multiple independently authorized groups for one raw event in an in-memory list; it selects exact logical/run/manifest/policy/consent ownership when auditing stored facts. Erase keys include policy/consent while the separate original WAL hash/ordinal collision guard remains strict. Existing retained receipts and the revised nonunique secondary event index are reused.

Portable export preserves selected measured bounds through retention, exports one selected session without depending on unrelated capture sessions, and accepts acknowledged retirement of an empty selected purpose while another purpose keeps the physical registration active. The selected purpose still requires its own immutable closing membership and exact acknowledgement. No new table, durable identity, public API, driver or physical daily grouping is introduced.

## Authored evidence, not execution

The new source cases cover actual durable ingress after End, denied and new-session callback headers, two historical sessions, rejected malformed sibling items, cancellation rollback, unchanged demand rotation, same-run policy/consent siblings, one-session deletion, terminal-failure fencing, independently retired empty capture, per-group retention/replay/export, missing required owner children, missing owner delivery, and individual network clocks across local midnight. A separate retained-receipt fixture exercises cleanup after the original WAL is removed.

Original test cases remain in the selected sources. Compilation, Hilt/Room, migration/schema, unit/quality gates, provider/device/battery evidence and integrated dev/v10 validation remain deferred. A source test is not a passing test.

## Excluded and open assembly work

- Cell helpers/tests, schema authorship, ambient delivery packet and product R11 are separately owned and preserved.
- Future-dated-item whole-batch rejection is excluded. Root's upstream trace found real provider filtering removes future observations; this packet does not assert an unreachable callback is a production blocker.
- Shared active/passive maximum-age/freshness admission is under separate upstream review.
- This is a focused source slice. The full 25 assembly chains and final convergence gates remain open until their complete prerequisite evidence is recorded.
