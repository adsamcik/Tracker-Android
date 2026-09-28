# Settings and product surfaces continuation

Date: 2026-09-27. Whole request remains incomplete. Candidate:
`codex/source-native-map-context-root`, based on c129fdacb8. Main dev/v10 remains
13f1a99871. Production and test sources only: **IMPLEMENTED_UNVALIDATED**.

## Phase boundary

IMPLEMENTATION_ONLY remains mandatory. No Gradle, compilation, tests, lint, Detekt,
Room schema generation/drift, diff-check, emulator/device, UI evaluator, battery, CI,
release or rollout validation has run. No input freeze, provider/writer activation,
new dev/v10 integration or push. Preserve protected paths, original refs and drafts.
Authored tests are not passing evidence. Final assembly convergence is still separate.

## Current source trace

| Surface | Current implementation | Remaining work |
| --- | --- | --- |
| Settings | TrackingSettingsViewModel separates intent, permission observation, source reconciliation, desired/applied policy and operational availability; screen preserves legacy controls and five-level controls. | Confirm the proposed one-list source Details editor; remove redundant live/background control presentation without losing independent purpose consent, legacy/custom values or unsupported capability truth. |
| Onboarding | SetupUiState/WhatToCollectStep have Location, Activity, Steps, Wi-Fi and Cell. Unoffered Pressure is explicitly off. Raw automatic preference is preserved while execution is gated. | Confirm explicit default-off Pressure choice and preserving selection when permission/hardware is absent. Permission callbacks currently disable selected sources; Activity denial also disables Steps. Complete guarded save/error/retry flow after confirmation. |
| Dashboard | DashboardViewModel observes qualified live session snapshot and coordinated source-aware recent page; SourceSetTrackingContent renders source-local products. Exact Steps values do not come from raw session.steps. | Finish source-aware daily/weekly summaries, provider recovery and saved-evidence status. Current projections do not prove unfinished ambient authority/readers are trustworthy. |
| Tracker | TrackerRoute uses central start readiness; Stop remains reachable independently. | TrackerRouteViewModel recentTrips is Trip-only and turns load errors into empty content. Live cards still use legacy CollectionData/session values. Finish qualified source projection and source-aware history. |
| Statistics | Selected source detail adapters exist; session annotation UI is separate. | StatsPresenter and HistoryPresenter still depend on Trip/DailySummary flows. Global bounded history facade/paging/calendar/timeline and complete retained Location reader remain unfinished. Calendar descriptions still lack complete date/data semantics; existing selected-day state is now exposed to accessibility. |
| Notifications | Central start/stop and retirement fences already exist. NotificationComponent formats legacy cycle/session data and preserves customization catalog. | Wire qualified source-product refresh, Steps/Pressure components, source-aware geography, permission/capability/partial states; complete accessible reorder and recoverable customization load/save after confirmation. |
| Widgets | Central WidgetActions and qualified exact-segment Steps presentation exist. | Complete six-source values/status and recovery. Active/Today fallback failures must not be mistaken for idle/empty success. Current geographic/summary adapters remain legacy. |

All source participation is independent. Optional Location enrichment must not create Location
demand. Sessionless ambient keeps independent consent and shares collection infrastructure;
live session quality applies only to participating sources. Desired configuration, provider
request acknowledgement and qualified saved evidence are different facts. Full data-reader,
authority, source lifecycle and control work stays tracked in its existing continuation folders.

## Authored independent repairs

- Widget duration and goal percentage no longer force US formatting. Optional locale arguments
  preserve existing callers; production widgets pass the configured app locale, including
  qualified Long Steps and collection/session counts. Percent truncation and clamping remain.
- Legacy Location filter labels use existing localized meter/second resources and app digits.
  Numeric settings and legacy/custom limits are unchanged.
- Calendar month/year, weekday labels and day digits use the configured app locale. Existing
  selected/unselected state is exposed to accessibility without changing date selection or data
  meaning. Full-date and qualified recorded-data descriptions remain incomplete.
- Notification Title/Content/Edit descriptions use existing translated resources. No new copy,
  recovery UI, reorder action, notification catalog or preference behavior has been introduced.
- Session card description uses existing localized labels. The overriding no-op accessibility
  Copy click is removed; actual detail click remains and real copy long-click gets its existing
  localized action label. Clipboard label uses the existing Session resource.
- The remembered copy/share lambda now refreshes with displayed duration, distance and relative
  start/context changes, preventing stale captured display values. Summary body still contains
  legacy English/geographic content and requires the full source-aware presentation work.

## Focused unexecuted test sources

- WidgetFormattersTest: explicit stable US cases, Arabic hour/negative-zero digits, German percent
  spacing/truncation/clamping, exact Long count beyond Int and locale grouping.
- ActiveSessionWidgetPresentationTest: configured Arabic app locale controls duration, collections
  and exact qualified Long Steps; raw carrier Steps behavior remains unchanged.
- TrackingSettingsScreenTest: legacy filter values honor configured Arabic app digits/resources.
- CalendarContentComposeTest: configured Arabic month/year/day digits, existing selected state
  and unchanged real date click callback.
- TrackerDashboardSessionCardsTest: actual detail callback remains reachable and copy belongs to
  the real long-click action; existing semantic assertions use the localized card shape.
- NotificationManagementAccessibilityTest: configured Czech resource labels and actual Edit action.

No tests have been compiled or executed. Existing tests are source compatibility inputs only.

## Next work

1. Resolve the concrete pending tool proposals in CONTRACT_DECISIONS.md; do not infer approval.
2. Implement settings/onboarding and guarded recoveries with focused failure/race sources.
3. Finish retained authority, unified range/history and metric readers under their approved contracts.
4. Wire source-qualified live/history/notification/widget projections without starting providers
   from read surfaces or replacing unavailable evidence with zero or empty success.
5. Complete localization/accessibility and full-scope independent source review; keep checklist open.
6. Only after all planned slices exist freeze the complete input set and execute final convergence.
