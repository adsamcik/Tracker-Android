# Historical Wi-Fi capture and W1 repair selection — October 4

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. This is a focused source selection, not the assembly freeze or integration readiness.**

Exact parent: `c315f7dc1c649a54c575485bd8de18e16a5fa162`.

## Preserved inputs and selection

- The rejected first attempt, `../WIFI_HISTORICAL_CAPTURE_SELECTED`, remains unchanged. `ORIGINAL_PACKET_PINS.json` pins its source selection, authorship, dependency, inventory and review records.
- `BASE/` and `HEAD_BASE.json` are root-exported bytes from this exact parent. `SELECTED/`, `FILES.json` and `SELECTION.patch` select the complete historical Wi-Fi slice against that parent: 13 changed paths and one unchanged fact-contract input.
- `BEFORE/` and `W1_BEFORE.json` preserve the two working inputs before W1 authoring. Their normalized contents equal the original packet's selected sources. `W1_AUTHORING.patch` contains only this repair's production and test delta.
- `AFTER/` pins all 14 actual working sources at freeze. Their normalized contents equal this selected composition. Earlier excluded draft inputs remain preserved in the original packet's before/after snapshots.
- The selected lifecycle adapter preserves the already committed R11 origin predicate from the parent. Its parent-to-selection diff selects only historical Wi-Fi changes. Existing Cell helpers, schema and separate ambient delivery implementation remain intact.
- `DEPENDENCY_INPUTS/` preserves the original 64 context inputs and revised schema companion pins. `W1_DEPENDENCY_INPUTS/` and `W1_DEPENDENCIES.json` pin five additional current read-context sources: run-retirement entity, session entities/DAO, accepted Cell counterpart and its test fixture. Dependency context is not an instruction to stage another source or held draft.

## W1 repair

An ACTIVE physical Wi-Fi registration no longer makes a selected session's positive completeness sufficient on its own. For versioned per-reading capture, the export snapshot reads that session/run/source/instance/generation's existing raw run-retirement row, counts the bounded read against the existing 96-query authority budget, validates the row and requires its acknowledged lease, drained tail, callback barrier, coverage and COMPLETE status to agree with the selected completeness. Failed admission, unresolved callbacks, wrong retirement outcomes, stale lease or an unfinished drain reject the snapshot.

The selected capture demands must already have exact immutable closing memberships. The final selected demand's closing elapsed and wall times must additionally equal the acknowledged stop cutoff. Earlier policy demands may close before that cutoff. Another ongoing session or independently enabled background source can continue using the physical registration. Empty selected sessions require the same acknowledgement and cutoff correlation as sessions with saved callbacks. Existing physical-retirement and strict legacy-v5 paths retain their prior meaning.

This reuses the existing source/run retirement receipt and private transfer helper. It adds no table, stored identity, format, public API, collection request or provider activation. All reads and emitted data remain inside the existing bounded Room snapshot and epoch/erase/retention barriers.

## Authored test source

`TEST_SOURCE_INVENTORY.json` lists the original 15 historical-capture cases and 13 additional W1 cases, all **NOT_RUN**. The old empty-session positive now creates a real acknowledged selected retirement and leaves the sibling session ACTIVE. The new actual Room scenarios cover:

- Independent ACTIVE background and session siblings, empty and nonempty selected capture.
- Missing or only-requested acknowledgement, wrong lease, mismatched final cutoff and missing final closing.
- Mismatched completeness tail, failed admission, unresolved callbacks, incomplete callback drain and an entry barrier behind the saved tail.
- Valid earlier policy windows ending before the final acknowledged cutoff.

These are source fixtures, not runtime or test-pass evidence. Compilation, Hilt/Room, schema/migrations, unit/quality gates, provider/device/battery evidence and final integrated checks remain deferred.

## Remaining boundaries

The original historical adapter/classifier/writer/lane, exact child authentication, retention/replay, erase, history and existing-format closure remain selected. W1 addresses the independent source-lifetime stop boundary; it is not new export-format work. New radio file-format design and unrelated backup work are outside this packet.

The full 25 assembly chains remain open until their complete prerequisite evidence and final convergence batch are recorded. No source completeness, integration readiness, `dev/v10` merge or provider activation is claimed here.
