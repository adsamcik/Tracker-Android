# Dashboard route preview bounds

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

The requested source-aware product polish includes truthful displays of saved Location.
The existing Dashboard route preview initialized maximum latitude and longitude with
`Double.MIN_VALUE`, a positive number. Routes entirely south or west of zero consequently
included an artificial zero extent and appeared compressed. Identical negative points could
also appear to have a route extent.

The same coordinate-fit calculation now uses the first actual point to initialize all four
bounds. One internal function supplies Canvas's existing screen points and permits direct
geometry assertions. Padding, orientation, animation, markers, copy and callers are preserved.
No new user behavior, data model, dependency, Location request or permission is introduced.

`FILES.json` pins the two selected production/test paths at parent `640a27da4f`.
The saved `.before`/`.after` snapshots preserve their input/output bytes; the existing three
Compose scenarios are untouched. Five newly authored geometry scenarios cover matching
positive/negative extents, crossing zero, constant negative latitude/longitude and identical
negative points. They have not been compiled or executed.

Static source review is pending. This does not complete Dashboard qualification, route-gap
presentation, the assembly gate, device evidence or integration readiness.
