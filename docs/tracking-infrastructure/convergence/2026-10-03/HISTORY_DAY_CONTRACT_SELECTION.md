# Displayed-day History contract selection

Date: 2026-10-03. **IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.**

This coherent contract slice implements the approved display-day selection for
saved background Wi-Fi/Cell readings without rewriting facts, revisions, IDs or
source calendar metadata. Each radio section can select its exact half-open UTC
window. Missing older details produce an unknown reading count. Location History
can retain its saved point ID for sibling-purpose presentation.

Selected whole diffs only:

| Path | SHA-256 of reviewed working source |
|---|---|
| `stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/UnifiedHistory.kt` | `b3b19bf1d7c2e0866b90802ecc586acea74a2888449a728d07cc32cf386f4dc6` |
| `stats/api/src/commonTest/kotlin/com/adsamcik/tracker/stats/api/repository/UnifiedHistoryDayWindowTest.kt` | `60631b5228c58052d0e6b0c1f8d0d0ff1ad10b79b54f77dc2b8563d502e6ea19` |

The independent displayed-day review inspected both exact pins in
`../2026-09-30/HISTORY_DISPLAY_DAY_FRESH_REVIEW_1003.md`. The coordinator re-read
the complete two-file diff and confirmed both pins before exact-path staging.
Three contract scenarios are authored: Wi-Fi half-open days with unchanged saved
metadata, Cell selection retaining signal/owner, and unknown older summary counts.
None has run.

The full held R11 readers, DI, source-day storage partition, paging/calendar/detail
UI, Location erase/read-error repair and composed Room cases are excluded. This
does not complete History or any full-chain assembly prerequisite. Compilation,
schema, tests, UI/device, gates and integration remain deferred.
