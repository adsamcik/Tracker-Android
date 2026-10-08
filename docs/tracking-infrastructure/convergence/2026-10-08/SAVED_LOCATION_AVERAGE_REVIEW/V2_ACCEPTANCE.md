# Shared saved moving average: V2 bounded source acceptance

Independent review only. IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED; eight new authored
methods NOT_RUN. Accept the exact V2 bounded source selection recorded in V2_INPUTS.json.
No concrete remaining blocker found for this slice. This is not execution, full-feature or
integration readiness evidence; all 25 full-chain prerequisites and T25 remain open.

## Exact input and preserved review

V2 FILES SHA: 2657feba33bc43133775fc02e04658ff382139825b7ccc32da943f8a12b40290.
V2 patch SHA: 34761d3a3c4b048c66b6a15a5b369ff74b27110c5e64886392b1d4586c46bd32.
Nine dependency pins SHA: 271d7aa6c487ba51d5328db073187ce4e7b9ca4e2bf7efff65be404f8bbe7e37.
All thirteen BASE/raw WORKING_BEFORE operations and eleven selected LF sources match their
manifest; all nine committed current dependency pins and all thirteen base paths match inspected
current 8187f45ced4e7507ff398b0f217a2b6edacc3ae0. V1 remains separately preserved with
V1_FINDINGS.md. Only the finite reader and its existing Room test differ from V1.

## V1 failure closed

SavedLocationSummaryReader.result now reads measured distance once, filters it for finiteness,
and supplies the average only when that same finite distance exists. The existing failure path
can therefore return point count/latest fix with null distance/average, Partial and the original
INTEGRITY_UNAVAILABLE problem without violating the new SavedLocationSummary invariant.
It skips the onState callback only when accumulated measured distance is non-finite, avoiding
construction of an invalid SavedTripDistanceState. Empty and valid measured prefixes keep their
existing continuation behavior. The unchanged live seed already returns Next(read, null) when
the read has a problem or no supplied state, preserving the partial snapshot and reseeding on a
later update. No invalid prefix is reused and no new state/observer contract is introduced.

## New handler source case

The new JUnit4 runBlocking method explicitly returns Unit. It seeds the existing real Room
session, original authority/grants and two consecutive saved points, then mocks the actual
five-argument Location.Companion.distance call used by the accumulator to return NaN and
positive infinity. Both session-in-transaction and ordinary date-window reads must retain both
points/latest ID and return null numerical fields with Partial/INTEGRITY_UNAVAILABLE; the
session callback must not supply resumable state. Finally unmockkObject releases the fault and
the saved rows are still present. The companion method, overload types, imports and existing
MockK test dependency convention match statically. This is an artificial numerical fault at the
existing calculation seam, not a claimed ordinary-coordinate fault or provider reproduction.
MockK instrumentation, Room generation and test/runtime success remain unexecuted.

## Unchanged accepted scope

The earlier full V1 trace still applies: shared constant-memory distance/moving sums and resume
state, existing native elapsed/legacy wall continuity and gap boundaries, same finite/live
qualification and invalidation, nullable appended API compatibility, existing Tracker collector
and localized value/Partial/Waiting/Unavailable display. The unused feature-local path calculator
and its own test have no production callers. No duplicate observer, provider activation, store,
math engine or whole-session rescan is added. Existing tests were preserved; seven prior methods
plus the new handler are all NOT_RUN.

Source-local clock/window ordering, effective corrections and broader numerical consumers remain
explicitly open. The pure reversed-wall accumulator case does not prove reversed-clock Room
ordering. The selected Compose case injects a saved snapshot; seeded Room authority does not
prove physical Android providers or end-to-end controller UI timing. No compile/test/lint/schema,
diff-check, device/provider, CI, Git mutation, merge or push was performed.
