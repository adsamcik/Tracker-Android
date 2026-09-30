# Independent R25 source lifetime coverage review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Independent static source review only. No test,
compilation, Hilt, schema, device/provider, Git or integration operation was performed.

## Reviewed selection

Reviewed the three new methods and two existing-method extensions declared by
`R25_SOURCE_LIFETIME_COVERAGE_FILES.json`, their actual fixtures and production signatures,
against the full inherited user requirements/approvals. All five current source hashes match
the manifest. Earlier test/production drafts are outside the selected additions. This is a
test-source slice and adds no production behavior or design decision.

## One concrete test repair

`SourceBrokerTest.kt`, new `every capture subset joins and leaves...` method: the assertion
after `markSessionDemandsRetiring` calls `currentDemands(...).all { STATUS_RETIRING }` without
requiring any rows. If marking incorrectly retires/deletes the last six capture demands
immediately, that assertion passes vacuously. The following sibling-authority checks and final
retired-history checks would also still pass. Assert the exact six last-vector demand IDs (or
exact six rows plus identity parity) before asserting their retiring status. This is necessary
to test the intended separate retiring lifetime; it needs no production change.

## Meaningful source trace and evidence limits

- The broker matrix uses actual Room policy/demand/registration/authorization tables and
  production `buildSessionDemands`, transactional session replacement, authorization rotation,
  retiring and retirement. All 64 capture vectors are present. Demand IDs, authorization
  fingerprints, unchanged sibling rows, excluded Location, unchanged registration generation
  and complete retired history make replacement/source-isolation errors observable. Sibling
  demands and accepted registrations are explicit ledger fixtures; this is not their real
  admission, physical exclusivity or provider-to-product proof. The separate retiring assertion
  requires the repair above.
- The lazy registry matrix uses the production keyed directory with all 64 selections and
  existing counting/cached fake providers. Selecting a source resolves its correct owner;
  siblings remain uncreated, availability is unread, and fake lifecycle calls throw. Repeated
  identity equality comes from the fixture's cached provider. The source slice correctly
  declines generated Hilt/native-singleton proof; the six actual Hilt map bindings were traced,
  not compiled.
- Simultaneous map/game temporary leases exercise the production shared controller against
  a mocked physical runtime. The requested intervals, surviving game cadence, idempotent stale
  map release, final close and absence of durable refresh/collection demands are observable.
  The approved 10-second/5-second ages are carried on requests, but callback freshness filtering,
  native availability/precision and actual saved-output exclusion are not asserted by this case.
- The Turn-off extension iterates the eleven actual canonical supported source/purpose pairs
  (six capture, four ambient, one Activity control). The real authority reader checks the closed
  gate before touching strict policy/lifecycle/retention mocks; the existing explicit zero-policy
  assertion is meaningful. This is acquisition-authority fencing, not app/widget/shortcut routes
  or physical teardown.
- The Activity extension uses the production arbiter, real Room and an entered callback barrier,
  with a mocked GMS backend. Existing End narrowing waits for the entered callback and preserves
  the control generation. The new Off tail removes the remaining owner, closes admission,
  verifies deny-all authorization plus retired registration, preserves generation identity and
  observes exactly one backend removal across repeated Off. Durable consumer retirement is
  arranged in the fixture, so it is the leaf owner lifecycle rather than the user command or
  complete automatic start/capture/product chain.

The added calls match the actual source signatures and reuse existing fixtures. No additional
concrete test-source defect was found at the four other seams. Three new methods/two extensions
are authored, not executed. The source slice honestly leaves S01-S25 full-chain reconciliation,
convergence/Hilt/Room execution and real-device lifecycle/power evidence pending. It must not be
used to declare those complete.

## Static repair recheck — 2026-09-30

The coordinator repaired the sole test finding. Before marking retirement, the test now reads
the final demand vector and requires six rows. After marking, it requires six current rows,
exact demand-ID parity with that vector, and only then every row's `STATUS_RETIRING`. A
premature full retirement or row loss can no longer pass vacuously. The refreshed SourceBroker
input hash is `a64b7f5de8dfe811aa47df3835e45dbdbaef57d84b840f16ddb9e20a142149ba` and matches
the inspected source.

The repair has static closure, and no concrete remaining defect was found in the five scoped
test additions/extensions. Their limited ledger/fake-provider/controller/arbiter coverage stays
as described above. No scenario was executed; full-chain, Hilt, device and assembly claims
remain pending.
