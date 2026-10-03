# Retained Location erase and read-failure core selection

Date: 2026-10-03. **IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.**

Independent review statically closed H1, H2 and H3 in
`../2026-09-30/HISTORY_DISPLAY_DAY_FRESH_REVIEW_1003.md`. This selection includes
the tracked `RetainedLocationContextReader.kt` diff and only the seven direct
H2/H3 Room methods plus their bounded run fixture in its existing test file.

The reader authenticates explicit native erase against the existing exact
receipt, saved sample, writer and dataset before loading optional map context.
Optional limits/errors keep that erase result. Actual optional SQLite/shape
failures carry existing Storage/Integrity reasons; ordinary absent older proof
does not become a database error. Cancellation still propagates. No new stored
proof, schema, map contract, provider request or import restriction is added.

Exact selective inputs:

| Input | SHA-256 |
|---|---|
| Reviewed production reader | `7b26cecd95d2ca3ee9de099f7cc6b6904d5527f49d0c38cc797ffc8b7278d3a0` |
| Original HEAD test snapshot at `1e58017f39` | `818ebd6917263d2712fdfb22eca52b7621e74b9b5e6e6f99298d82408c20e8fb` |
| Desired direct-test snapshot | `7c352e70431fb37d85277dea28940db8c0e82aa672be740450889c99d5c203b3` |
| `RETAINED_LOCATION_CORE_SELECTION.patch` | `5243d3585cfc7d68a731dab56c41debde71db4f54ba4f85f89f8915d8899c2d7` |

The review compared baseline, desired snapshot and patch. Original six scenarios
and fixtures remain unchanged except final whitespace. Four new actual Room
cases exercise authenticated erase and unverified proof under the 101-run cap;
three exercise optional SQLite failure, invalid purpose shape and ordinary
missing proof. All seven are authored and unexecuted.

The working test also contains composed day scenarios, which the selective patch
excludes. Full R11 range/native readers, their new problem propagation and
composed scenarios remain held with the broader History assembly. Their frozen
pins and prior H1/H2/H3 inputs are preserved as review evidence, not whole-file
acceptance. All 25 full-chain prerequisites and convergence remain open. No gate,
device work, integration or activation ran.
