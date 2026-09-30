# Wi-Fi downstream qualification source receipt

Status: **IMPLEMENTED_UNVALIDATED**, 2026-09-30. This is a bounded R04 source slice,
not an implementation freeze or a validation receipt.

## Exact source and test manifest

- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactMaintenance.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiWalQualificationAdapter.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryComposer.kt`
- `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/wifi/WifiWalQualificationAdapterTest.kt`
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryRepositoryRoomTest.kt`

Selected preserved companions required by that chain:

- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactClassifier.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/WifiCapturedFactWriter.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultWifiHistoryRepository.kt`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/ImportedWifiHistoryDetails.kt`
- `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/wifi/RoomDeleteSelectedWifiHistory.kt`

Existing working-tree child-record handling in the maintenance/composer/adapter files is
preserved. The existing shared `RadioPurposeItemQualifier`, database purpose-item reader,
and loaded history/portable authority snapshots are reused. No dependency, entity, DAO,
migration, manifest, provider activation or transfer-format change is introduced by this slice.

## Static chain

V6 native qualification authenticates the actual provider readings within its registration,
the exact authorization effective at receipt, and each selected reading's historical capture
demand. V5 retains its whole-callback authorization and original temporal authority shape.

The selected V6 indexes and elapsed bounds now reach maintenance's deterministic
reclassification, lifecycle action bounds, policy/consent checks, manifest bounds and portable
audit. Portable qualification uses the same pure item rule on its existing bounded snapshot.
Sessionless V6 rows in maintenance authenticate receipt/timeline rather than requiring the
entire batch to fit the final callback authorization period.

History resolves the same historical membership from its existing Room snapshot, checks
selected child indexes against that membership, and uses qualified observation bounds for
provider, action and consent checks. The raw callback payload, receipt, delivery identity and
provenance remain unchanged. V6 retention bounds retain sub-millisecond uncertainty through
the existing canonical helper.

## Test source authored

Native/maintenance/portable scenarios:

- Rotation between two readings preserves both authorized readings through native writing,
  maintenance audit and portable export.
- A capture authorization beginning between readings claims only the later reading.
- A stale callback revision cannot stand in for the revision effective at receipt.
- A cached reading before provider/session start does not discard a valid sibling reading.

History scenarios:

- Both readings survive a callback authorization rotation.
- Only the reading authorized for capture appears, including its detail child.
- Validly checksummed full facts and children cannot claim a reading preceding capture authority.

Existing V5 authorization-rotation and half-open-boundary test source remains in place.
No authored test has been executed or compiled in this implementation-only phase.

## Remaining boundary

This slice covers the single capture tuple already bound to the callback WAL. It does **not**
close historical capture groups belonging to another ended session, or a delayed callback
after capture has left. Native ingress currently selects capture metadata from callback members
and a WAL row can carry only one capture tuple. A neutral carrier and separate historical
capture-group product authority require an explicitly approved contract change before editing
that path. Existing capture-mask constructor and carrier invariants are retained.

`/root/adversary_product_0930` independently traced this bounded slice through native
writing, exact children, maintenance, retention, portable export and history. It
reported no new definite blocker within the current callback capture tuple and
confirmed the v5 branch and uncertainty bounds by static inspection.

The same independent reviewer accepted the bounded child loading, exact imported
detail ownership/checksums, and child-before-parent deletion in the three selected
stats companions. Imported legacy summaries may legitimately have no children;
new imported-file completeness and historical capture groups remain separate work.

Compilation/Hilt/Room, schema/migration gates, tests, device behavior, battery evidence
and integration remain pending. Background radio checkpoint wiring is outside this
slice and must be tracked separately.
