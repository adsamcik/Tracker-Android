# Actual all-day Unsupported facade coverage

Date: 2026-10-07. Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

## Selected boundary

One new `stats/data` test file instantiates the actual `RoomAllDaySourceStatusQuery` from committed input `2f03d3ebe7486790328afd2e889ad6b5918cd8af`. Activity and Pressure already have explicit Unsupported sessionless requests. This completes focused test-source coverage of that existing behavior; it adds no product capability or design decision.

Strict, unconfigured MockK dependencies are the database and the existing Steps/Wi-Fi/Cell repositories. Both actual Unsupported reads must return the exact Unsupported result without any dependency call, including a database-open check, transaction, DAO or constructor-time access. The real facade still constructs its native Steps and retained Location readers; their committed constructors only retain the database reference and cannot bypass the strict database assertion.

Cancellation scenarios enter the actual read from an undispatched child and suspend at its queued IO handoff. An independent CompletableDeferred captures the read's own Result. Cancelling the child must leave that Result failed with CancellationException, rather than merely observing that the outer child is cancelled. Neither source can read dependencies on cancellation.

## Reuse and preservation

Uses existing JUnit Jupiter, MockK, kotlin-test and coroutines-test dependencies supplied by the module convention. No production source, DI, module dependency, manifest, schema, provider, worker, public API or new testing infrastructure changes. Existing mixed drafts remain untouched. No new Room instance or sensor is activated.

The settings/module graph, Android test conventions, actual facade/constructor dependencies, current API-only coverage, History caller and Hilt binding were statically inspected. This test does not prove Hilt composition, retained supported-source qualification, actual Room execution, provider behavior, Android lifecycle, UI or complete-source assembly.

## Authored cases and review

Three new test methods cover four Activity/Pressure request scenarios. Every scenario is **NOT_RUN**. Exact committed dependencies and source bytes are pinned in DEPENDENCIES.json and FILES.json; the SELECTED copy and SELECTION.patch preserve the proposed source. Independent review is pending. No compilation, tests, schema generation/drift, lint, Gradle, diff --check, device, CI, activation or Git mutation ran.
