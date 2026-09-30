# Pressure export original header

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. This is a bounded source repair,
not a full-chain acceptance or convergence freeze.

## Selected paths

- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/PortablePressureRoomReader.kt`
- `stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/PressureHistorySelectorTest.kt`

Native logical entries now use the source reader's original closed session bounds
for their portable header. Physical run and Pressure window times remain exactly
as saved. An unresolved original header returns the existing unavailable result;
the exporter cannot invent it from the surviving physical runs.

The existing Room selector scenarios now assert the portable header differs from
the unchanged physical member bounds when the original session was wider. Every
unresolved header fixture also asserts that portable comparison yields no invented
entry. Ordinary valid measurements remain available to history.

`/root/adversary_product_0930` independently accepted the two-file source diff and
test authorship against the approved original-header rule. No compilation, tests,
schema checks, device evidence, activation or integration were performed.
