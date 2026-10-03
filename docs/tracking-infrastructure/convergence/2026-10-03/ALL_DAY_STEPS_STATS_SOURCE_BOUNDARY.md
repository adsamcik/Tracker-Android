# All-day Steps Statistics consumer: frozen source boundary

Status: **IMPLEMENTED_UNVALIDATED**. Phase: **IMPLEMENTATION_ONLY**.

This slice implements the already approved use of saved Steps throughout the day independently
of sessions, through the existing Statistics summary and History/Summary actions. It does not
declare the all-day source chain, assembly, provider behavior, or integration validated.

## Exact selection

`ALL_DAY_STEPS_STATS_FILES.json` pins four source paths. All four were clean at task start;
read-only exact-path status was explicitly authorized by the coordinator. Before/after bytes are
preserved in `all-day-steps-stats-selection/{before,after}`. Source remains frozen for independent
review. No source Git mutation was performed by this author.

The Czech resource selection changes only `stats_no_tracker_sessions`, correcting its meaning
to the existing English “No tracking sessions yet”; a session query does not establish that all
recorded observations are absent.

## Production connection

- `StatsRoute` now collects its existing retained qualified Steps flow while session paging is
  empty, loading, or failed. Dense successful session lists preserve the existing sparse-card
  visibility rule. Collection remains lifecycle-bound and adds no provider demand.
- `StatsScreen` reuses its existing HeaderActions and summary card for these states. Existing
  session loading/error content and Retry remain available alongside saved Steps. Empty or
  unsettled session paging never inserts the test-only trip placeholders.
- Without a successful nonempty session list, the card omits session-specific explanatory copy
  and Location distance. Steps preserve exact `Long` counts, including a genuinely covered zero.
  Updating, incomplete, and unavailable summaries use the existing History state strings;
  incomplete evidence never becomes Steps or active-days zero.
- Qualified Steps day rows no longer wait for the independently loaded structural session bars.
  Missing presentation rows come only from actual `Ready.days`, using existing `EEE` local-day
  labels and retaining structural labels where available. Numeric authority stays in the original
  epoch-day-to-`Long` map; a presentation row grants no Steps qualification. Later structural rows
  replace the same day, so active days are not counted twice.
- The existing Start prompt is retained only for empty sessions, `NOT_CAPTURED` Steps, and no
  available non-Step weekly bars or heatmap activity. This consumer has no all-six-source retained
  presence API; it does not prove the absence of other or older source history. History remains
  accessible in every session paging state.

## Existing all-day foundation traced, unchanged

- `data/preferences/.../tracking/TrackingParamsState.kt` and
  `RoomSourcePolicyRepository.kt`: one Steps choice and ambient purpose participation independent
  of sessions; the legacy ambient setting is a compatibility mirror.
- `tracker/engine/.../source/runtime/AmbientStepsPurposeOwner.kt`: supported provider ownership,
  immediate copy on enable/rearm, independent retirement, and foreground-only degradation.
- `tracker/engine/.../worker/AmbientStepsImportWorker.kt`: unique hourly background copies with
  `KEEP`, immediate unique work on enable/open, and cancellation of both schedules on retirement.
- `tracker/engine/.../module/TrackerModuleInitializer.kt`: app-open foreground scheduling remains
  connected even when another source needs repair.
- `tracker/engine/.../source/ambient/steps/AmbientStepsImportRunner.kt`: each attempt is bounded by
  30 seconds, observes startup/permission/capability, and preserves progress without a session test.
- `AndroidAmbientStepsCapabilityResolver.kt`, `AndroidAmbientStepsProviderBackends.kt`, and
  `AndroidAmbientStepsProviderReaders.kt`: supported on-device Health Connect/Local Recording,
  truthful unsupported/foreground-only outcomes, real provider windows, and no missing-as-zero read.
- `tracker/engine/.../source/summary/RoomStepsNumericSummaryRepository.kt` and
  `stats/data/.../repository/DefaultAmbientStepsHistoryRepository.kt`: retained native/imported
  source evidence, structural local-day authority, explicit gaps/errors, and no current collection
  permission requirement for saved history.
- `StatsPresenterViewModel.weeklyStepsSummary`: existing bounded seven-local-day retained query,
  `WhileSubscribed(0, replayExpiration = 0)`, and current local-day rollover. No backend or settings
  change was required by this consumer repair.

## Test source inventory and evidence limit

Ten new authored, **unexecuted** Compose/Robolectric cases cover:

1. Saved all-day counts with zero sessions, exact active days, and working Summary/History actions.
2. Genuine covered-zero Steps and active days without Location distance or a Start prompt.
3. Updating-to-Ready-to-partial/evidence/calendar/storage invalidation, withholding stale numbers.
4. Not-captured empty data with existing Start and History actions.
5. Known non-Step weekly activity without a misleading empty collection prompt.
6. No test-only placeholder sessions when Empty has no supplied paging object.
7. Czech no-session copy without a claim that all records are absent.
8. Saved Steps while session paging loads.
9. Saved Steps while session paging fails, preserving the existing Retry action.
10. Steps arriving before structural daily bars, then replacement without duplicate active days.

One existing visibility-policy test is adjusted. Existing source-qualified zero, partial,
materializing, sparse-session, and lifecycle subscription cases remain in the same test source.
The new UI scenarios use empty PagingData and the actual production collector/visibility helper;
they assert rendered numeric/state authority and real callbacks rather than just mirroring a boolean.

No tests, compilation, Gradle, lint, Detekt, Room schema checks, diff checks, devices, providers,
CI, or integration gates were executed. These authored cases are not passing evidence. This packet
does not resolve pending bundle duplicate-file membership, exact session selection, or whole-assembly
coverage and must remain out of `dev/v10` until the applicable final convergence gate permits it.
