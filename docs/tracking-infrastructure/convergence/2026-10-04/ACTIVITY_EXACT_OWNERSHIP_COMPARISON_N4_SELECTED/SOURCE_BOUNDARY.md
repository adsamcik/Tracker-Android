# Activity exact comparison — N4 correction

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

This cumulative four-path selected packet follows the revised native ownership lookup
and the separately selected raw caller projection (root reports committed f3b026e4d1).
Its original N2 and revised N3 packets remain unchanged; both contain the recorded
review rejection. BASE is unchanged from the N2/N3 source selection. SELECTION.patch
is cumulative. N4_REPAIR.patch contains only this correction relative to the frozen N3
selection. BEFORE_N4 preserves exact actual working bytes for both affected paths.

N4 removes the duplicate incomplete value-free ownership reader. Owners omitted from
portable comparison because no values are visible now use loadLocalProtectedOwnership,
which validates their exact physical membership and includes entry, run, deletion scope
and every surviving captured window identity. A retired/retained owner cannot lend its
window ID to a foreign imported run. Only the already-qualified local owners are read;
there is no global census or additional storage. Genuine bounded revision-reader overflow
maps to the existing READ_BUDGET_EXCEEDED result in ordinary recent history.

The additional actual Room case advances the real retention floor while the native window
revision still survives. It verifies that native visible values are withheld and an unrelated
future import remains readable, then admits a structurally valid foreign entry/run/scope
reusing only the native window ID. Ordinary recent history marks that imported record with
ORIGIN_IDENTITY_CONFLICT and no values; the shared imported bridge reports its existing
SOURCE_INTEGRITY_FAILURE. The other four exact comparison/mixed factless cases remain.
No complete-window deletion is misrepresented as leaving a window identity behind.

Held R11 range/visibility foundation remains excluded from SELECTED. The routine matching
budget catch in its current range caller is pinned only in N4_OWNED_WORKING_DELTA.patch.
Strict exact export completeness, ordinary date export and unrelated Pressure/radio work
are unchanged. No compiler, tests, lint, schema/gate, device/provider execution or Git
mutation occurred. Five tests are authored and unexecuted; full-chain readiness is not claimed.
