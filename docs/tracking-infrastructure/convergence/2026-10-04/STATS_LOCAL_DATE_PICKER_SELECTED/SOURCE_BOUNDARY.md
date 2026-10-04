# Statistics local calendar picker repair — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** No compilation, tests, lint,
Room/schema/diff checks, CI, device/provider activation or integration has occurred.

The user approved UTC measurement timestamps with local calendar-day selection.
The independent `PERIOD_METRIC_PRODUCT_TRACE_1004.md` found that Statistics gave
local instants to Material's UTC-date picker and confirmed UTC dates plus a fixed
24 hours. This shifts dates and breaks daylight-saving boundaries.

The two selected files are exact committed BASE plus this repair. Initial and
preset values now encode the intended LocalDate as UTC midnight for Material.
Captions decode the picker date in UTC. Confirmation resolves that date in the
captured display zone, ending one millisecond before the following local day.
No new screen, setting, API, dependency, database or metric is added.

The active preset is derived from the actual picker selection. Selecting All time
clears the picker's dates; subsequent calendar selection can produce a new range
instead of retaining a stale All time flag. Existing clear and dismiss callbacks
and labels remain. The caller's existing inclusive end convention is preserved.

Seven newly authored Compose scenarios exercise the real dialog/state/callback:
custom-date round trips east and west of UTC, Prague's 23-hour and 25-hour days,
Today east of UTC, Last month west of UTC and calendar selection after All time.
The first four also check the displayed date caption. Existing scenarios remain.
These scenarios are unexecuted, not passing evidence. Preset expectations reuse
the separately covered preset calculator; the exercised picker bridge is real.

`FILES.json`, `HEAD_BASE.json`, `SELECTION.patch` and BASE/SELECTED snapshots pin the
source input. Initial working bytes are preserved. All 25 complete prerequisite
chains, product period-metric wiring, full History and convergence remain open.
