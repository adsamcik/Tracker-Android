# Independent radio background export budget review — 2026-09-30

Status: `IMPLEMENTED_UNVALIDATED`. Static source inspection only. No production changes,
Git operations, compilation, tests, schema checks, device/provider work or integration.

## Exact scope and pins

The selectable slice is only the new helper/import hunks in
`CapturedRadioDateSelection.kt` and the whole new `RadioBackgroundExportBudgetRoomTest.kt`,
as described by `RADIO_BACKGROUND_EXPORT_BUDGET_FILES.json`. Existing date-selection behavior
is not changed by these hunks. Both selectable whole-file hashes match the input manifest:

- `CapturedRadioDateSelection.kt`:
  `2059d0b15d7065120eefd5667b6a804c6c23543acfef8b7245c16d9b39659f75`.
- `RadioBackgroundExportBudgetRoomTest.kt`:
  `f0d56ad109c73f82a9aad07752837a97686eabddfcf18bd586ca88aa7c2a6a05`.

The held Wi-Fi/Cell drafts were inspected only at their `backgroundChildren` collector call
sites as dependencies. Their whole-file hashes have changed during separate date/page work
and no longer equal this manifest's earlier draft pins. Observed current whole-file hashes:

- Wi-Fi: `e05bd80a7b7d103cf25738c6ebe445dc14b521246b015127f28ec2df377ce354`.
- Cell: `605a2ab678b4218ffee5c9d226419f9b22ee65f4a529f94eb2338b20340353d2`.

To identify the reviewed dependency bytes without accepting either whole draft, SHA-256 of
each exact UTF-8 excerpt from its `val rows = readBoundedBackgroundRadioChildren(` through
the end of `importedRows`, before the following `require(rows.size...)`, preserving original
line endings:

- Wi-Fi, `:301-315`, 635 bytes:
  `25dbf5d0cb50308d70012102f3aa29b60b01c88f3bd0fa163178335b41a1ea34`.
- Cell, `:285-297`, 625 bytes:
  `cb78432accb0fd6559e67000128a60f9bcc6ad00cbcf4362627def362b316208`.

The stale whole-draft pins require refreshing when those drafts are independently frozen;
they do not prevent selecting the matching helper/test slice alone.

## Static findings

No concrete defect was found in the selectable helper/test logic.

`readBoundedBackgroundRadioChildren` (`CapturedRadioDateSelection.kt:12-27`) validates the
existing raw background ceiling, derives the remaining allowance, requests exactly that
allowance plus one overflow sentinel, then checks before appending. On overflow it returns
no clipped owner or partial successful result and does not visit later owners. Its arithmetic
is bounded by the existing 262,144-child cap, so `remaining + 1` cannot overflow. A full budget
still permits summary-only owners through a one-row existence query and rejects any later
detail. Coroutine cancellation is checked both before and after every suspending DAO call.

Both dependency call sites use the native result's size to reduce the imported allowance;
the combined result therefore retains the existing shared raw-row ceiling, rather than
allocating that ceiling separately to every fact/revision or origin. The exact-revision DAO
queries preserve correction selection; older revisions cannot crowd out the chosen revision.
Checksums, qualification, child completeness, final writer ceilings and defensive imported
deduplication remain separate, unchanged responsibilities. No recording demand, coordinates,
retention scope, source coupling, schema or new failure framework is added.

The prior cross-archive duplicate-parent concern is not a conforming-storage defect:
`AmbientWifiFacts.kt:315-319` and `AmbientCellFacts.kt:336-340` make fact/revision globally
unique, and `ImportedRadioChildren.kt:67-74,94-101` binds children to the archive/fact/revision
parent by a cascading foreign key. The existing imported post-read defensive deduplication
remains in both held draft paths. This review does not approve their remaining archive
membership behavior.

## Authored evidence and limits

All six sources use actual Room parent/child DAOs:

1. Wi-Fi overflow verifies decreasing query limits and no later-owner query.
2. Cell verifies the corresponding overflow boundary.
3. A second storage group receives only the first group's remaining allowance.
4. Full budget permits an owner without details and rejects a later detailed owner.
5. Selected Wi-Fi revision excludes a larger older revision.
6. Cancellation immediately after a Cell query stops subsequent queries and preserves data.

These are raw collector fixtures, with placeholder checksums and directly seeded imported
parents. The two-group case exercises the shared allowance through imported DAOs twice;
it does not claim native-plus-imported exporter or authorization evidence. Native DAO
signatures and actual held native/imported call-site composition were inspected separately.
Tests have not been compiled or executed.

The selectable utility/test slice is coherent for an implementation-only source commit.
Full Wi-Fi/Cell exporter publication, date-prefilter changes, exact archive membership,
whole-file transport snapshot/spool approval, shared transfer cancellation/draining, product
surfaces, convergence and integration remain outside this closure.
