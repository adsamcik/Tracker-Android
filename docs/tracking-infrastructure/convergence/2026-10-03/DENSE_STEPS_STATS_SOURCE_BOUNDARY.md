# Existing Steps summary with a populated session list

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** Exact three-path continuation over the
coordinator-authorized clean prior Steps Statistics selection (`e833d2e773`). Sources are frozen.

## Approved behavior and scope

The user approved the existing seven-day Steps summary near the top, independently of session count whenever
Steps is selected or those days contain saved Steps data. The existing card, session list, History
action, qualified Long counts, and missing-data text are reused. No resource, setting, calendar,
backend, provider, durable storage, dependency, or new UI block is added.

`StatsRoute` observes the existing `TrackingParamsRepository.data.stepsEnabled` through the same
route Hilt EntryPoint pattern used by TrackerRoute. False initial presentation does not assert a
chosen source before settings arrive. The existing read-only weekly Steps stream is collected with
lifecycle ownership at every session count. Its presenter still requests exactly seven local
calendar days and uses WhileSubscribed(0, replayExpiration=0); reads never activate tracking.

`StatsScreen` reuses its existing summary directly after header actions when Steps is selected,
or when Ready / PARTIAL_CAPTURE proves retained Steps. Generic storage/calendar/evidence failure
or initial Materializing alone does not establish saved Steps when the source is off. Selected
Steps always retains the existing waiting/incomplete/unavailable text. Dense reuse suppresses the
sparse-session explanation and unqualified distance; Ready counts, including real zero, keep
their original Long authority. Existing sparse summary, day chips, empty/loading/error content,
and History/Summary/session actions remain in the same flow. Selected but not-yet-captured Steps
does not fall back to an invitation to start a session on the empty list.

## Authored scenarios and boundaries

Six new **unexecuted** Compose cases exercise the production collector and Screen with actual
LazyPagingItems and source-selection Flow fixtures:

1. Three loaded sessions with Steps off retain a seven-day Ready summary, daily covered zero and
   a count beyond Int.MAX_VALUE; session click and History still work, with no dense distance or
   sparse-session subtitle.
2. Three sessions with Steps selected retain Not captured, Updating, Incomplete, and each
   unavailable reason without inventing numeric zero.
3. Switching Steps off preserves an incomplete retained summary.
4. Steps off with no captured evidence does not add the new summary, even for generic read
   failures; the loaded session list and History remain available.
5. Paging from two to three sessions cannot unsubscribe the Steps read or prevent later counts.
6. Fifty saved sessions do not push the Steps count below the first viewport; History remains
   visible without scrolling. The existing three-session case also checks initial summary
   visibility before scrolling to a session action.

One obsolete predicate-only test expecting the read to stop at three sessions is removed. Existing
visibility/lifecycle cancellation and sessionless scenarios remain, with their fixture now using
the same unconditional screen collection as the route. Test methods: 27 before, 32 after.

The coordinator's placement review found that the original dense patch still put the card after
all paged sessions. The final delta places chosen/retained Steps before the calendar and sessions,
without a duplicate footer. The existing non-Steps sparse fallback retains its original placement.

These are authored fixture scenarios, not passing evidence or proof of Hilt generation, native
providers, device timing, rendering, battery, or complete retained-data discovery. An unavailable
read does not independently report whether saved Steps exists; no presence is invented from it.
All execution/gates/Git/provider activation/integration remain deferred.

## Exact review input and exclusions

`DENSE_STEPS_STATS_FILES.json` pins three source deltas. `DENSE_STEPS_STATS_SELECTION.patch` is a
static source patch generated without Git from the preserved before bytes and frozen sources;
no further whole-file after copies were made. Review only this exact delta and relevant unchanged
dependencies. Earlier UI Location, radio storage/export, permission continuation, session export,
Location Replace, period exploration lookup, and map-notice work are excluded. This slice does
not declare assembly complete or `dev/v10` ready.
