# Independent native radio export candidate review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Static source inspection only. No production changes,
Git operations, compilation, Room query execution, tests, provider/device work or integration.

## Reviewed selection and exact pins

Reviewed `RADIO_EXPORT_CANDIDATE_FILES.json` and its source boundary: only the two source DAO
candidate query/method hunks, the exporter source-kind import/page-call hunks, two new test
sources, and the three named discovery additions in each existing source fixture. Unrelated
history/test drafts and whole unfinished radio exporters remain excluded.

The following whole-file hashes match the manifest at inspection; these pins do not enlarge
the documented mixed-path hunk selection:

| Path suffix | SHA-256 |
| --- | --- |
| `dao/WifiCapturedFactDao.kt` | `006d36bb57490b97c59f2532fe5f1a9b7ceb5ee389ebfc5f06670e8772eea799` |
| `dao/CellCapturedFactDao.kt` | `0fb8ca93a7a9cef8708d1220e1bcecde40c7edf059b2393df1e45553af785a4b` |
| `dao/RadioExportCandidateRoomTest.kt` | `f44e26ab2e2d41e6148c7f4daabdf92e8f3faad8984f89b9142472ce7b8e60e5` |
| `exporter/RadioExportCandidateIntegrationRoomTest.kt` | `bd3aaeb7ba1bf3b9b4ac9971d8ed22b485fff4c6205418a120f265df44df8611` |
| `repository/WifiHistoryRepositoryRoomTest.kt` | `def7beab55e06f96f07f766751a7f5bc2e39c8b10c6fa0b16bb57b96715f05c4` |
| `database/CellCapturedFactMaintenanceTest.kt` | `e6e30a33b3ffb2184108a46d18553d34cba2ee7dde7a13cca8c190103d245f82` |

Exact UTF-8 query/method excerpts, original line endings, from the preceding `@Query(` through
the end of `completedExportCandidateIdsPage`'s `): List<String>`, excluding the following newline:

- Wi-Fi, 3,860 bytes: `68f560ff110392b726f0ddb7efac96ba8e08d9e3a6c8c6f8abb407180458c765`.
- Cell, 3,840 bytes: `36bed5345c956ef6bfe9e51527221c0f9931c5d7dd2525d1184470101de8c6cf`.

The held exporter whole-file hashes have changed during separate work and differ from the
manifest snapshots: Wi-Fi `e05bd80a7b7d103cf25738c6ebe445dc14b521246b015127f28ec2df377ce354`,
Cell `605a2ab678b4218ffee5c9d226419f9b22ee65f4a529f94eb2338b20340353d2`.
The inspected calls at Wi-Fi `:113-116` and Cell `:108-111` pass only the exact source constant,
selected date bounds, ascending cursor and limit 100 to the respective source DAO. These are
dependency observations, not full-draft approval.

## Concrete remaining finding

**P2 — invalid negative derived coverage time can be silently excluded.**
The clock `CASE` in `WifiCapturedFactDao.kt:109-126` and
`CellCapturedFactDao.kt:88-105` guards the individual fields and overflowing latest endpoint,
but does not recognize an earliest wall time below zero after subtracting elapsed coverage.
For an exact owned fact with `observed_wall_time_ms = 1`, uncertainty zero and a 10,000,000 ns
coverage span, earliest becomes -9 and latest is 1. With an outside-date owner segment and
requested dates `[1000,2000)`, the query drops the row because latest is before the dates.

This is invalid timing, not a trustworthy outside-date interval: the canonical Wi-Fi and Cell
lower-bound functions reject a negative result (`CanonicalRetentionBounds.kt:149-153,171-175`),
and the portable source reader consequently cannot qualify it. The declared conservative
discovery contract requires invalid timing to remain a candidate for that honest refusal.

Minimal repair: also treat rounded coverage span greater than
`observed_wall_time_ms - wall_time_uncertainty_ms` as an invalid-clock candidate, after the
existing individual-field guards, for both sources. Author actual Room assertions with owner
segments outside the dates so the owner-overlap branch cannot hide this missing condition.
Do not add a schema, authority bypass or silently clamp the bad clock into a verified time.

## Static strengths

- Parent sessions must be completed, and the ascending cursor remains outside the grouped
  interest/date `OR` clauses. An overlapping fact cannot bypass source interest, session state
  or cursor advancement. The result is key-only and limited to 100 distinct logical IDs.
- Source interest is exact manifest source/purpose/eligibility, exact source completeness, or
  a source-table fact joined through matching logical/run/segment ownership. No sibling source
  supplies payload authority.
- Run/segment time discovery retains missing, wrong-owner, reversed and non-integer timing.
  Interval overlap includes a zero-length event at the inclusive start and excludes the
  exclusive end. Unknown-run cohorts remain candidates.
- Valid measured coverage and uncertainty can keep a candidate even outside run headers.
  Partial milliseconds round outward. Existing type/sign/order guards make elapsed subtraction
  nonnegative and at most `Long.MAX_VALUE`; its rounded milliseconds remain small enough for
  safe integer arithmetic. Guarded wall-plus-uncertainty stays within `Long.MAX_VALUE`.
  The missing negative earliest validation above is separate from numeric overflow.
- Qualified whole-entry smart selection, original IDs, session expansion switch and outside-date
  counts remain unchanged. Candidates confer no retained data authority and start no tracking.

## Authored evidence and publication boundary

All 14 authored scenarios were inspected: four shared Room DAO cases; three additions in the
actual Wi-Fi fixture; three in the actual Cell fixture; and four exporter-to-Room routing
cases. They cover source/date/state exclusion, gap/zero-length boundaries, unknown ownership,
205-key paging, corrupted unrelated facts, uncertainty beyond headers, elapsed coverage,
negative/reversed clocks, relevant refusal and propagated cancellation. They currently lack
the negative-derived-wall-time case above. No test was executed.

Routing tests use real Room discovery but explicit typed qualification doubles; they are not
positive whole-file, live source or Hilt evidence. Their exporter/entry-point definitions are
held outside this narrow accepted selection. Therefore DAO/core/stats fixture companions can
form a separate coherent source commit; the feature routing source and page-call hunks must
remain held until the corresponding full radio exporter definitions are independently selected.

Imported candidate parity and Wi-Fi imported cursor continuation, query cost, exact archive
membership, transport/spool approval, shared transfer drain/epoch integration, product surfaces,
convergence and device evidence remain open. This receipt does not approve full radio exporters
or assembly/integration readiness.

## Dated static recheck — 2026-09-30

The negative-derived-time finding is closed in both source queries. Their invalid-clock
`CASE` now also retains a rounded coverage span greater than observed wall time minus
uncertainty (Wi-Fi `:119-121`, Cell `:98-100`). This is candidate discovery only: it does not
clamp or authenticate the malformed clock. Existing bounded arithmetic and all other clauses
remain intact.

Two additional actual Room sources (`WifiHistoryRepositoryRoomTest.kt:164` and
`CellCapturedFactMaintenanceTest.kt:2961`) use outside-date owner segments, show that an exact
zero earliest bound stays excluded, and that one extra nanosecond/fractional rounding or a
whole negative bound remains candidate. The Cell fixture additionally requires its owning
exporter to refuse the malformed source without reaching its sink. These source assertions
directly cover the missed branch rather than relying on overlapping headers. There are now
16 authored scenarios, still unexecuted.

All eight current whole-file snapshots match the refreshed manifest. The two earlier stale
exporter snapshot notes are historical; those full definitions remain held. Updated mixed
whole-file pins:

- Wi-Fi DAO: `bae9582fce08814fdfc8fc7d953e6f267ac466fb57b73a6c590778e31aa95890`.
- Cell DAO: `c5f78bdfa1b4f07043a0b454bb63146e18217ed4629db9676ac4c935bf6e2f90`.
- Wi-Fi fixture: `982f3c69a0fdd279ef591bc2dc3b9c0a29efd75fd6e03e5b32de739d6dd41784`.
- Cell fixture: `df9ab6360bf3094eafb820d580a07294b79f1ac26fabc32071fa725d02ff4a1c`.

Using the same exact excerpt hashing boundary defined above, the repaired query/method pins are:

- Wi-Fi, 4,155 bytes: `a9de97534a7370ff5aabdb6568b82f88e75c86931dba14741b8321e1527cc114`.
- Cell, 4,125 bytes: `69502c21d120480c1dfa8acb44a54fb8f05b4d9c862e366c831b14f2455e446b`.

No additional concrete defect was found in this narrow repaired candidate selection. The
held exporter/routing-source publication boundary, imported parity and all validation and
integration limitations above remain unchanged. This is static closure only.
