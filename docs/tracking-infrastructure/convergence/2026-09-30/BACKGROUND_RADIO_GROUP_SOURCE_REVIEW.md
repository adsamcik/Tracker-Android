# Background radio groups — source receipt

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. This is a bounded implementation slice,
not an assembly freeze, activation, validation or integration receipt.

The user approved saving every independently valid background Wi-Fi/Cell group
from one delivery, retaining original reading identity/time and one export choice
per source (`call_dyTLyUBooYp7RWyWE3vQurTk`, item 0).

## Selected source and test paths

- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/RadioPurposeItemAuthorityReader.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/CellPurposeItemQualification.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/RadioObservationChildMigration.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/DevelopmentV28DatabaseContainment.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/AmbientWifiFacts.kt`
- `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/AmbientCellFacts.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiPurposeItemAuthorityReader.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiPurposeChildren.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/wifi/AmbientWifiFactProjector.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/cell/AmbientCellFactProjector.kt`
- `core/base/src/test/java/com/adsamcik/tracker/shared/base/database/data/AmbientRadioGroupIdentityTest.kt`
- `core/base/src/test/java/com/adsamcik/tracker/shared/base/database/RadioObservationChildMigrationTest.kt`
- `core/base/src/test/java/com/adsamcik/tracker/shared/base/database/DevelopmentV28DatabaseContainmentTest.kt`
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ingress/WifiDurableSourceIngressTest.kt`
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ingress/CellDurableSourceIngressTest.kt`

Existing radio-child migration and ingress fixture drafts in these paths are
preserved. Their remaining companion drafts must still be selected before freeze.
The already committed pure qualifier and ingress are reused; no provider is started.

## Implemented contract

The bounded shared reader authenticates the exact callback revision and the
historical immutable demand timeline. V6 qualification selects each reading for
each independently authorized purpose with that demand's own freshness limit.
Each background demand group gets its own fact, clock, children, cursor and replay
identity inside one Room transaction. A write exception rolls the transaction back.
A typed rejection of one group does not revoke another independently valid group.

The nullable `purpose_demand_id` keeps v1 identities/checksums and whole-callback
summary semantics for older facts. New v6 facts bind the demand to their identity
and checksum. V6 detail-bearing callbacks are not compacted merely because their
signal summaries match. Replay checks exact children and reports missing/changed
children as a conflict. Gap timestamps use each qualified group's observation time.

The development 28→29 migration adds the two nullable columns; v29 preflight checks
them before Room opens. Checked-in schema generation remains deferred.

## Test source authored

- Two sequential background demand groups from one delivery, including exact details.
- Historical readings arriving after the callback purpose has been denied.
- Stable distinct group IDs and byte-compatible older IDs.
- Retained v5 whole summaries without invented detail children; mixed v5 admission
  still rejects a newly submitted boundary-spanning callback.
- Exact replay and missing-child conflict with an independently valid sibling.
- A second-group SQL write failure rolls back the first group and its children.
- Mixed physical-lifetime readings retain the valid sibling after retirement.
- Group-specific Cell gap times and idempotent replay.
- Nullable migration columns and missing-column v29 containment.

These tests have not been compiled or run.

## Independent review and remaining work

`/root/completion_wiring_0930` independently inspected this slice, its dependencies
and day/range readers. It confirmed the historical membership, per-reading consent,
legacy identity/meaning, transaction, replay and deletion fences by static trace.
It found raw-maximum physical registration filtering and cross-midnight grouping.
The physical filter now authenticates actual in-lifetime readings before purpose
qualification. The reviewer rechecked that delta and the retirement/SQL rollback
fixtures and found no further definite blocker within this bounded slice.

Cross-midnight readings still need the pending day-group decision and end-to-end
day/range/export test source. Background delivery/recovery checkpoint wiring,
historical session capture groups, native detail-reader completeness and the rest
of the assembly remain open. This receipt does not close their prerequisites.
