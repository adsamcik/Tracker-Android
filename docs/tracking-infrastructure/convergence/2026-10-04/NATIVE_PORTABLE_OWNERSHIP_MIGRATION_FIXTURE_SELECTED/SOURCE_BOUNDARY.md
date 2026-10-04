# Existing ownership migration fixture companion

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

This separate one-test-path selection preserves the original lookup freeze unchanged.
`BEFORE/` holds the exact fixture bytes before this edit; `SELECTED/` and `SELECTION.patch`
add only original source metadata tables/rows and actual migration-backfill assertions.
The source tables are minimal fixture parents, not a new production schema or API.

The existing migration scenario now contains one original physical service run plus one
Activity and one Pressure window. After actual `MIGRATION_28_29`, all seven existing source
keys must be present, including the exact original Activity/Pressure window keys. Its prior
legacy radio/Location provenance assertions remain unchanged. No test was run.

The companion depends on the frozen native ownership lookup packet. Future nonunique radio
retention event-index changes are excluded and will require a separate source selection.
No compilation, schema generation/check, test, provider/device execution or Git mutation occurred.
