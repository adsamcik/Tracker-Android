# Bounded T13 trace at 7da374b5f4c8a1706525991aafc2c884ebda5226

Static implementation audit; no gates or validation executed.

Connected: metadata annotation creation/edit/removal, names/local activity labels, paging, Hilt bindings, original UTC instants/local display zone, active-recording restrictions, optimistic revision/data epoch fences and half-open calendar overlap markers. These do not authorize a sensor or erase source records.

Concrete remaining production gaps:

1. HistoryRoute:158 opens the metadata editor, not a session's contents. SessionOrganizationEditor:102 opens one originalSegmentId; OrganizationAnnotationDao:40/56 derives it from MIN session segment. DefaultTrackingHistoryRepository's existing range/observation/paging foundation has no feature/app caller. Source-only and retrospective session contents need the already held History presentation direction; do not build another organization store.
2. UnifiedHistoryRangeReader:518-544 correctly selects background radio readings with a measured displayWindow, but UnifiedHistoryOrganizationSelection:74-84 then requires an entire fact interval inside a retrospective session. A Tuesday session can lose its valid Tuesday child when the original batch also includes Monday. Captured Wi-Fi/Cell whole-observation checks at56-72 have the related limitation. Hold changes with the pending narrow measured-observation reader/presentation proposal; never alter original times or invent aggregate splits.
3. UnifiedHistoryRangeReader:642-645 explicitly returns INTEGRITY_UNAVAILABLE for BackgroundRadioArchive selectors because overlapping portable imports do not yet retain exact archive membership. This is the existing T20/T21 prerequisite; an archive receipt or coincident time range is not exact membership.
4. Descriptive imported-label display/removal was broken (null local ID hid its displayed name; name-only preservation made No label unable to clear it). The private repair in this packet addresses only this fourth gap.

Existing bounded annotation composition marks contents beyond its first finite page as DEPENDENCY_OVERFLOW. Native/imported capture rows still sort by recording start. The held shared measured-observation proposal owns the next presentation/continuation work. Activity/Pressure all-day collection remains unsupported. Whole measured Steps windows remain intact; session grouping does not reassign daily totals or prorate counts.
