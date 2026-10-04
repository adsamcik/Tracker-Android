# Historical Wi-Fi capture: W1 second repair selection

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. No execution, assembly completion or integration readiness.**

Exact selected parent remains `c315f7dc1c649a54c575485bd8de18e16a5fa162`; the later all-day/History work is outside these paths.

## Exact scope

This packet preserves the full 14-path historical Wi-Fi selection from the first repair, including the committed R11 origin predicate and unchanged Cell helpers. Only `WifiCapturedPortableTransfer.kt` and `WifiWalQualificationAdapterTest.kt` have a new authoring delta. `BASE/` is the root-exported exact parent; `SELECTED/`, `FILES.json` and `SELECTION.patch` provide the complete selected composition. `BEFORE/`, `W1_V2_BEFORE.json` and `W1_V2_AUTHORING.patch` isolate this repair from the first repair's selected bytes.

The original rejected packet and the first repaired packet remain unchanged. `ORIGINAL_PACKET_PINS.json` and `PREVIOUS_REPAIR_PINS.json` pin them. Their original test and review evidence is preserved. Dependency snapshots retain their original pins and read-context-only meaning; they do not authorize staging other drafts.

## Two remaining relations repaired

An ACTIVE shared provider's accepted wall and elapsed times must each be at or before the selected run's acknowledged stop cutoff. This uses the same existing receipt relation as the accepted Cell counterpart. Two additional actual Room empty-session fixtures independently move wall or elapsed acceptance past that cutoff, also moving the corresponding START acknowledgement so the existing acceptance-before-START relation remains valid. Each must reject the export through the new stop-cutoff relation.

Exhausting the existing 96-query authority budget now returns the existing `DEPENDENCY_OVERFLOW` outcome. Both the initial count and a new per-run retirement read take this typed outcome, instead of being mislabeled as unverifiable writer authority. No new test seam, query-budget framework or public API is introduced. Existing source cases cover stable bounded audit query count as WAL rows grow and real completeness-row exhaustion; there is no executed or new test-only exact-97th-query proof.

The first repair's exact acknowledged retirement, drained tail, callback barrier, lease, failures/gaps and final closing-cutoff checks remain. Earlier valid policy windows and independent active session/background siblings retain their existing behavior. Empty and nonempty capture share the same required acknowledged stop boundary.

## Evidence boundary

`TEST_SOURCE_INVENTORY.json` lists 30 authored cases: 15 original historical-capture cases, 13 first-repair cases and two new acceptance-cutoff cases. All are **NOT_RUN**. Source byte and composition inspection is the only verification performed here. No Gradle, compiler, tests, schema checks, device/provider activation or Git operation occurred.

All-day retained-status wiring is the next separate approved implementation slice. It remains untouched by this packet. The full 25 assembly chains and final convergence batch remain pending.
