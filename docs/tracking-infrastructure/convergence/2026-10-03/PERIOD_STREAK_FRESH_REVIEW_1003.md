# Independent windowed discovery streak review

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Review by
`/root/transfer_adversary_1003` on 2026-10-03. Static source inspection only; no
compilation, test execution, lint, schema/diff checks, device/provider activity,
Git mutation, integration or recursive delegation.

## Exact reviewed selection

Reviewed `PERIOD_STREAK_FILES.json`, `PERIOD_STREAK_SOURCE_BOUNDARY.md`, the four
tracked deltas in `PERIOD_STREAK_TRACKED.patch`, and all seven selected source
versions under `period-streak-selection/after`, against their preserved before
versions where present. All seven live and selected hashes and all four before
hashes match the manifest. Three new files have no before version. The patch
SHA-256 matches
`3c9a2ea7c6ab35616056cc6c252188085ad500203e4922a076e34aefad5584c6`.
The packet records base `9d48e176b050bdc88b8c2ff68fcd6c11b950f49f`; no Git
ancestry or integration check was executed by this reviewer.

The exact selection is two production files in stats/data, the ExplorationCellDao
query, and four test files. Authored annotations independently count to 27 new
cases: 3 DAO, 10 provider, 10 pure calendar, 4 composed Room/provider. The existing
interval-to-lifetime test is separately adjusted to test Cumulative; it is not
counted as a new case.

## Findings

**No concrete defect found in this bounded backend slice.** The approved period
portion is computed from first discoveries whose UTC timestamps satisfy the exact
closed interval, before grouping into local days or locale weeks. Discoveries
outside the selected period cannot extend its run. Equal timestamps, multiple
discoveries in a period, late arrivals, gaps, DST and week/year boundaries are
handled without assuming a fixed local-day duration.

The zone and locale week rules are captured before suspension. Existing all-time
`bestCount` reads remain unchanged. The existing `(level, first_discovered_at)`
index supports the new metadata query; no schema, durable calendar/visit lookup,
dependency, provider activation or screen is introduced. CPU conversion runs on
the injected default dispatcher, the DAO is suspend, cancellation is propagated,
and storage failures remain typed errors. One lookahead timestamp rejects an
over-budget result instead of returning a truncated streak.

## Evidence limits and remaining work

The 65,536-distinct-timestamp ceiling bounds returned data and conversion work; it
does not establish a SQL latency or total scanned-row bound. Query performance,
Room/Hilt generation and all authored test outcomes remain unvalidated.

The Room cases exercise constructed exploration rows through the actual DAO and
provider, not native/imported source production or correction lineage. This
selection does not resolve discovery fanout, replay/correction/deletion rebuild,
retention semantics, seasonal visits, logical trip distance or UI period wiring.
Production search found the WindowedMetricsProvider binding but no product
consumer; the existing streak producer writes daily discovery only, with no
production weekly writer found. Those existing full-chain gaps remain open and
must not be represented as completed by this receipt. All-time preservation is
not proof of all-time producer completeness.

The exact frozen source is suitable for the coordinator's scoped implementation
checkpoint. This receipt does not declare assembly complete, gates passed or
`dev/v10` integration ready.
