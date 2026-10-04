# Preserve the spent radio request's original cooldown

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Exact input: `HEAD_BASE.json`;
two selected paths: `FILES.json`, `SELECTED`, `SELECTION.patch`.

This implements the already approved saved request limit and next-allowed time.
It keeps `a2a4cddc91`'s existing local preference ledger, one request per actual
session, independent source keys and existing runtime reservation/callback checks.
There is no second ledger, Room migration, observation proof or new collection.
The proposed storage move in the optional October 4 question is not selected.

Save the original minimum interval in the same synchronous reservation commit.
A later request uses the longer of that saved interval and its current minimum,
so changing quality, restarting the actor or rebooting cannot shorten the
original cooldown. The existing monotonic clock and conservative reboot/wall
rollback handling remain, including saturating deadline arithmetic.

A completely absent source reservation can receive its first request. Partial,
blank or wrongly typed saved state cannot become that fresh state. An exact
already-spent session remains spent even when another field is missing. Earlier
unshipped candidate state without the interval grants no recovered active
request; passive callbacks continue. This preference ledger was never released
on `main`, and released settings/data require no conversion.

Eight additional authored tests cover a shorter later profile across recreation,
reboot, a stricter current profile and wall rollback, incomplete state and source
independence, a missing interval, invalid values, overflow/elapsed rollback and
reported commit failure after cache update. The latter uses a real preference
delegate with an injected false result; it verifies Tracker's response and does
not simulate physical storage failure. All eleven ledger test methods are
**NOT_RUN**. No provider, runtime actor, build, test, schema, CI, device or battery
gate ran; complete source-power and full-chain assembly remain open.

Runtime call sites are unchanged. They reserve under their existing provider
operation mutex, then recheck exact registration/plan before Android mutation.
Their post-reservation race/physical-provider evidence remains a full-chain
convergence obligation, not a passing claim from these ledger test sources.
