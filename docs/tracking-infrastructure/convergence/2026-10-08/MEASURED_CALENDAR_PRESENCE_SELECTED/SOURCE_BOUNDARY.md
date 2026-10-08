# Calendar saved-data presence — source selection

IMPLEMENTED_UNVALIDATED. Every authored test is NOT_RUN. No Gradle, compilation, tests, lint, schema, device/provider activation, CI or Git mutation was performed.

Human approval: all saved-source days have one simple data dot; replace distance shading/legend. The actual monthly History grid has distance shading but no legend in current committed code. Its shading is removed; the separate overview heatmap component is untouched.

## Selected scope

51 paths: five production, 40 existing localized measured-History resources, six affected/new test sources. CalendarDayData carries saved-data presence rather than distance intensity/trip count. The actual History Presenter removes the obsolete daily-distance dependency and uses the same measured History facade as Timeline and selected-day browsing. CalendarContent and HistoryRoute connect loading/source Retry and accessible saved-data dots. Sessions remain reachable from the selected day; an annotation without any saved source data does not invent a data dot.

## Actual caller chain

HistoryRoute -> HistoryPresenterViewModel.observeCalendarMonth -> existing TrackingHistoryRepository.observeRange(month, limit=1, OBSERVATIONS) -> DefaultTrackingHistoryRepository -> UnifiedHistoryRangeReader with the same existing source-owned qualifiers and table invalidation set.

For each real displayed local day, the same readRange facade qualifies first one raw key. If rejected and a continuation exists, later reads use at most 100 keys. One finite round reads at most one page for each of at most 31 days. Original raw continuations are retained between rounds; rejected pages are pending, never false empty. No rejection cap, new loader/timer, whole-month measurement list, calendar database store, duplicate qualification or invented daily split is added. Only day presence, bounded frontiers and typed problems are held. A real saved timed gap is existing data; no date is manufactured for untimed problems.

A qualified row is enough for a dot. Complete genuine source windows keep their original bounds/count and are selected by the existing source-owned day overlap rules, including DST and overnight windows. Source problems coexist with healthy dots; Retry reconnects the same month observer. Each new invalidation resets stale day presence before requalification, including destructive changes/epochs. Snapshot mismatch withholds mixed results; collectLatest and request/month/zone guards cancel stale work. Selecting another day in the same month does not rescan the month. Existing ON_RESUME zone refresh now drives both the day and month readers.

DefaultTrackingHistoryRepository preserves every OBSERVATIONS invalidation emission even if its first Page is identical; an older changed day must not be hidden by distinctUntilChanged on that first Page. RECORDINGS keeps its old distinct behavior. The committed shared live/finite Location signal, fields and facade overrides remain untouched.

## Test-source boundaries

Seven authored ViewModel method names replace the old distance case and add six actual Presenter/Flow request scenarios: all-source independent dots, rejected-frontier progression/loading, healthy sibling+Retry, deletion/epoch reset, DST/zone change, no selected-day rescan and mismatch. The History facade responses are explicit fixtures, not provider/storage proof. Three new Compose methods exercise the real grid/description/dot/loading/Retry; the old intensity-rendering case is adapted. Three existing CalendarDayData contract cases are adapted to the approved presence model. One new actual Room/DefaultRepository/retained-Location case preserves an equal first Page invalidation after a deeper saved-point insert; unused capture collaborators are fixtures, with a 10-second bounded body. All NOT_RUN.

The existing RecordingHistoryPages fixture was incomplete against inherited abstract facade functions; unused methods now delegate to the existing MockK fixture while actual reached readRange/observeRange/pagingSource behavior is supplied explicitly. This was static source inspection, not compiler feedback.

## Performance and assembly limits

Day probes are cheap first-leaf reads on populated days. Rejected keys progress through finite rounds with yield/cancellation and visible loading; there is no growing retained raw-history list. The existing broad Room invalidation set may requalify a month on normal appends; correctness takes precedence over retaining a possibly erased old dot when dirty days are unknown. Device/query timing and battery behavior are unproven and deferred to final convergence. Month dots are a presentation read and start no sensor/provider.

BASE is exact committed 3a3476e63e5c3bc40e2697f5e503a94cb18592ed; WORKING_BEFORE preserves raw drafts; SELECTED is UTF-8 LF. CURRENT_COMPOSITION records selected-path compatibility with 8187f45ced4e7507ff398b0f217a2b6edacc3ae0 and DEPENDENCIES freezes 17 exact committed inputs. Names decoration is a separate held metadata overlay; the first-page invalidation behavior remains necessary when it is composed. No live source or accepted 124-path History foundation was overwritten. No whole-assembly/T14/T17 completion or execution readiness is claimed.
