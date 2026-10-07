# Shared History list proposal

Static review: /root/tracking_slice_review_1007. Original full task messages and
approved date/session/source rules supplied. No implementation or validation.

Question presented through the questions tool: History still mainly lists sessions.
Use one shared scrollable list for Timeline and Calendar's selected day, all six
supported sources/origins, measured dates, optional session labels, inline expanded
readings, older data paging, genuine aggregate windows and sibling-preserving Retry.
Extend the existing saved-data readers and load-state handoff. Awaiting answer.
This proposal does not reask the already approved measured-date/grouping rule.

## Current committed trace

HistoryPresenterViewModel.loadTimeline queries a fixed 30-day physical-trip range
and groups by trip start. Selected Calendar day uses physical trips and legacy
daily distance; daily Steps reader work is separate. Existing Sessions editor,
annotation list and paging are already independent and can remain in their tab.
HistoryRoute and CalendarContent have no unified six-source observation list.

DefaultTrackingHistoryRepository already delegates readRange/observeRange and
pagingSource to the shared bounded reader. Both repository interfaces bind to the
same singleton. The feature already has stats API, Paging/Compose/Hilt/navigation;
no extra dependency or new database is needed for this proposal.

HistoryRange is a finite half-open UTC-millisecond range with a valid displayed
zone; HistoryRangeRequest pages contain 1..100 rows and exact-range continuation.
The reader uses key-only lookahead and bounded payload/dependency reads, including
4096 facts/points per day. It exposes typed source/overflow/storage/time problems.
Background capability is Location/Steps/Wi-Fi/Cell. Activity/Pressure appear only
through supported captured/imported data; the proposal does not add new providers.

## Contracts that must be completed before the UI is correct

1. Native/imported capture rows currently sort by recording/entry start. Merely
flattening those pages into day headers would misdate overnight readings and
break cross-page observation order. Add a narrow read-only observation mode to
the same existing reader: each instantaneous value uses its actual measured time
and stable saved source/child identity; genuine aggregates retain real bounds
and uncertainty. Preserve existing organization/recording range behavior and
source-owned qualification, consent, retained epoch and identity. No parallel
collection engine, store or shadow copy.
2. UnifiedHistoryPagingSource currently discards Page.problems and snapshot. Carry
source-local failure and read status alongside good rows, so a failed source-key
probe cannot look like an empty or complete history. Do not invent dated sensor
rows for errors or fail all valid sibling data.
3. Existing source detail selection supports actionable Activity/Wi-Fi/Cell only.
Use actual source-issued navigation targets where available, otherwise inline
read-only expansion. A browse key/date/session label is never a delete/export
selector. Native/imported Steps/Pressure, background and sessionless Location
action/detail contracts remain explicit later work.

The full Calendar grid still uses trip/distance intensity plus annotation dots;
its complete-source composition is a separate follow-on. This bounded next slice
will not close T14 or the complete History prerequisite by itself.

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. Tests NOT_RUN.
