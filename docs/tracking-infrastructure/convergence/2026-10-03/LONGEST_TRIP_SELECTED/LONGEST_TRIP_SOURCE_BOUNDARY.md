# Saved Location longest trip: source boundary

Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.
Base and exact source hashes: `LONGEST_TRIP_FILES.json`. Selection:
`LONGEST_TRIP_SELECTION.patch`; the four mixed DAO/scope paths have HEAD-based
before/after snapshots. Other preserved drafts remain excluded.

## Implemented source path

`WindowedMetricsProvider.collect(LONGEST_TRIP_KM)` calls
`SavedTripDistanceReader.longestDistanceMeters` for All time, Interval and Rolling.
The period selects the original start of a completed logical trip, then reads all
reciprocal physical members, including members outside that period. Legacy/imported
unattributed records retain their independent physical-trip compatibility interval.
An existing presentation distance is never the numeric fallback.

All dependent reads share one Room transaction. Candidate pages have 64 rows,
Location pages 256, and a logical trip has the existing 256-member bound plus a
true overflow probe. There is no total point/history ceiling or silent truncation.
The point queries use exact native purpose/run/segment membership or the existing
legacy saved-point interval semantics. Current native epoch/high-water, scope erase,
retention floor and exact imported archive epoch are respected. Saved unknown and
imported coordinates need no invented native provenance or recording authority.

Distance sums only measured sections. Native sequence, registration, clock and
elapsed gaps break continuity; legacy/imported reversed timestamps and gaps over
two minutes break continuity. Invalid coordinates reset the anchor. A stationary
measured pair can be zero; absence of a measured pair is `NotFound`. Incomplete
membership, clipped retention and member-budget exhaustion are `ValidationError`;
storage failure is `DatabaseError`, and cancellation is rethrown. Each request
reads current rows without a cache, so coordinate correction/deletion changes its
result. No tracking is started.

Bounded `SEASONS_EXPLORED` now returns unavailable because dated visit storage was
declined; it does not reuse the lifetime season mask. All-time seasons are unchanged.

## Authored scenarios

Fourteen actual Room scenarios cover cross-month replacement runs, original-start
selection, identical-start candidate paging, Location-free/active siblings, honest
legacy/imported points, correction/deletion, point-page anchor continuity, missing
native fixes, reciprocal ownership, retention/epoch, erase and dependency/storage
failure. Four pure route cases cover coordinate/time/clock/sequence boundaries and
stationary versus unavailable. Five provider cases cover period/All-time routing,
unavailable, cancellation and seasonal lifetime-fallback rejection.

Independent static review found two source defects and one authored coverage gap.
The selected legacy candidate now discovers actual saved points even with zero
summary collection cycles; an actual Room case covers that distinction. The
existing discovery-streak Room fixture receives the new saved-point reader
constructor dependency. A real archive/member/identity/current-epoch case withholds
an old import until an exact current archive copy exists.

The same saved-point DAO now preserves descriptive background/session origin for
exact native membership and supplies exact-ID saved file rows. This lets the
approved session export reuse saved-file origin/retention semantics without
loading an unrelated date range or pretending a native duplicate is unknown.
The additional Room case checks dual-purpose labels, exact selection and retention;
these flags never authorize providers, enrichment or a new numeric contribution.

## Remaining limits

No compilation, test execution, schema/diff check, device, performance or provider
activation was performed. Mature All-time snapshot duration and database writer
contention remain unmeasured. The metric records measured sections, not estimated
movement across gaps. Legacy interval ownership cannot establish native capture.
Pending Location Replace representation must eventually feed the same effective
saved-point seam before time filtering/paging; this slice adds no representation.

No production consumer of `WindowedMetricsProvider` exists in the inspected tree.
Statistics `SummaryRoute` calls `StatsPresenterViewModel.loadSummaryStats`, which
uses `SessionStatsRepository.getAllTime/getBetween`; it does not display this metric.
Backend authorship does not close the period Statistics product integration or the
complete metrics prerequisite.
