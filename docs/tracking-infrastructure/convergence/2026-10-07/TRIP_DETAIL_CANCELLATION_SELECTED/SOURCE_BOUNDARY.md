# Trip detail repository cancellation

Date: 2026-10-07. Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

## Selected change

Base input: `eebb97b0a82894f6f378ec9c25fff08f335d3af4`.

The existing `DefaultTripRepository.getTripDetail` suspends in `TripDao.getById`.
Its general `Exception` handler previously converted cancellation into a
`StatsError.DatabaseError`. The real `TripDetailPresenter` calls this operation
inside the current LoadTrip `flatMapLatest` flow; its later live-history collection
already preserves cancellation. This slice makes the initial repository read do
the same by rethrowing `CancellationException` before the existing error handler.

Success mapping, source origin, NotFound and ordinary database error behavior stay
unchanged. No new UI, public API, database, schema, provider, writer, worker,
dependency or collection/retention policy is introduced. This is routine completion
of existing structured cancellation and History reliability requirements.

## Authored scenario

One new test uses the actual production repository and a strict TripDao mock. The
DAO suspends after signalling that the read has entered; the child request is then
cancelled. An independent deferred value captures the repository's own Result,
which must fail with CancellationException instead of returning a database-error
Left. Exact one-call verification prevents another DAO lookup from hiding the
failure. Four existing tests retain successful mapping, NotFound, ordinary storage
failure and imported origin behavior.

This is not a real Room/database or Android lifecycle test. It does not establish
provider execution, all-source History, full clear, Hilt runtime composition or
any complete prerequisite. All five methods, including the new scenario, remain
**NOT_RUN**.

## Exact input and preservation

Both working files were Git-clean at inspection but used CRLF while their committed
baseline used LF. Exact working-before bytes are preserved in WORKING_BEFORE;
normalized content was identical before editing. BASE and SELECTED retain exact
committed LF conventions; working-after files keep their prior CRLF convention.
FILES.json records the different raw hashes explicitly. Select only the two
source/test paths from SELECTED, not their working-tree EOL bytes or other drafts.

The first exact-byte preflight rejected this EOL difference before writing anything;
it did not discard or overwrite a semantic draft. The subsequent preflight confirmed
EOL-only equivalence and checked the exact working-before bytes again before edits.
Other mixed drafts remain untouched. Eleven committed dependency pins cover the
actual DAO/API/caller/Hilt/module/test-convention sources.

## Review and evidence boundary

Independent source review is pending. No Gradle, compilation, tests, lint, Room
schema generation/drift, diff --check, device/provider activation, CI or Git mutation
ran. This selected packet is test-source authorship and production implementation,
not a validation result or permission to integrate into dev/v10.
