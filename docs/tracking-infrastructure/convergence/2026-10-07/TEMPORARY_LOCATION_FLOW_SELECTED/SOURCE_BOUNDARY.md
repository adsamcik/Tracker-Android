# Composed temporary Location flow boundary

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED / NOT_RUN.**

This slice authors two test methods in the existing LocationSourceRuntimeTest.
There are no production, dependency, schema, resource or UX changes. No build,
compilation, test, lint, schema-drift, device/provider, CI, Git mutation or
integration was executed.

The existing user decisions approve temporary My location/map/game requests on
one shared Location owner, independent request lifetimes, a 10-second map freshness
limit and a 5-second or stricter game limit. Feature-only points must not create
tracking persistence. A foreground-service game collector can outlive application
backgrounding while the map releases and later reacquires its own lease.

The test calls actual SharedUiLocationProvider, SharedLocationSourceController,
LocationSourceRuntime and LifecycleRegistry. It supplies real Location objects
through the existing captured Android-backend callback, not through a mocked
shared owner or a duplicated freshness policy. Both requests use the same physical
cadence so one request joining/leaving does not require replacing an otherwise
identical provider plan.

The lifecycle case leaves a read-only raw-event observer active after both feature
flows cancel. Its event count must remain unchanged when the retired physical
callback is invoked. This tests the real runtime callback fence rather than only
the absence of already-cancelled feature collectors.

The existing broker and Android backend/registration boundaries remain fixtures.
The broker reports no durable Location demand. The recording sink has no deliveries,
and durable process/system registration constructors are asserted untouched.
This is a composed temporary-consumer source scenario, not Room storage, process
recovery, physical-provider, battery, accessibility or complete lifecycle proof.

Independent review and root exact selection/commit remain required. The complete
Location chain and all 25 assembly prerequisites stay open.
