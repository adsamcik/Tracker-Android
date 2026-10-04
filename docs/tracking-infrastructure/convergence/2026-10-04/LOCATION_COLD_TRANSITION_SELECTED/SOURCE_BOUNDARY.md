# Location cold system-request transition test sources

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**. Seven added cases are **NOT_RUN**. No production change, compilation, tests, schema checks, device/provider activation or Git operation is part of this packet.

## Selected scope

One existing test path, `LocationSourceRuntimeTest.kt`, extends its existing runtime fixture and adds seven composed cases for already approved shared Location behavior:

1. A surviving cold passive system request reuses its exact identity and generation without allocating an active replacement.
2. An active request waits for exact system removal and acknowledged retirement before reservation or provider start.
3. Failed system removal keeps the original retirement token and retries that exact identity.
4. Successful removal without durable acknowledgement keeps replacement fenced until acknowledgement succeeds.
5. Passive → active → passive transitions retire the old physical owner and prepare the next exact system identity.
6. The actual shared controller cannot let a temporary UI lease bypass a surviving cold system request when retirement authority is unavailable.
7. The actual shared controller and physical runtime compose map/game temporary leases, ignore callbacks from a replaced UI request, release leases independently, and make no durable reservation or sink delivery.

The fixture models the existing registration row, pending retirement and passive backend handle separately. A cold process begins without an in-memory handle; passive start publishes one, and exact removal clears only the matching handle. Production backend removal and database acknowledgements remain mocked seams with explicit gates and outcomes. The test sources do not establish real Room recovery, Android PendingIntent behavior, permission behavior or physical-device evidence.

## Preservation

`WORKING_BEFORE` preserves both originally assigned test paths and all mixed drafts. `SELECTED` derives only this narrow test delta from root-exported BASE `03fe9a70fb5e483b036887c745bd570adcc66769`; unrelated working tests are excluded. `SharedLocationSourceControllerTest.kt` is unchanged and unselected because the composed assertions fit the existing physical runtime fixture.

The six-path radio freshness repair and its frozen packets remain untouched. No new Location ownership, recovery, data-handling or UI design is implemented. No full assembly, convergence, integration or `dev/v10` readiness claim is made.
