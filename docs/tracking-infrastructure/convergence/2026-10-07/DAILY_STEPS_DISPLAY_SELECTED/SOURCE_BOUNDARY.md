# Daily Steps display source selection

Revision: V2; V1 exact packet/input/source bytes are preserved in REVIEW_V1.

Status: IMPLEMENTED_UNVALIDATED. Phase: IMPLEMENTATION_ONLY. All authored tests are NOT_RUN.
No Gradle, compilation, Room/schema, tests, lint, diff-check, emulator/device, provider activation,
CI, integration or push was performed. This packet is not the final assembly freeze.

## Approved behavior and precise contract

The human approved "Use one per-day display reader" for the existing statistics reader: each day
independently shows its verified total, recorded-so-far count or missing-data state, with strict
games/achievements unchanged. The coordinator explicitly confirmed that the small source-owned
per-day calendar-conflict discovery companion, existing query reuse and needed invalidations are
within this approved behavior. This creates no database, provider, preference, background worker,
new dependency or numeric reward authority.

- Existing StepsNumericSummaryRepository gains readDisplay/observeDisplay(request). The new
  StepsDailyDisplay snapshot contains the source-evidence revision and exactly one value for each
  requested epoch day: Complete, Recorded(count, actual causes), Materializing or Unavailable.
- AmbientStepsNumericRangeReader gains one display-only structural discovery method. Its concrete
  existing stats-data reader executes the existing bounded native/imported/fence day-key queries
  once, identifies conflicting/malformed days and exposes the valid neighbours. Strict discovery
  still rejects a requested window containing any conflict. Existing alternative test readers use
  the conservative default strict adapter, not invented calendar authority.
- Room uses one reader/deferred transaction for all stored calendar keys, all-day source totals and
  existing session-source composition. It reads the source revision before/after, stays on the
  injected IO dispatcher, propagates caller cancellation and reports storage failure without
  retaining a prior number. A source/calendar failure never becomes zero.
- A qualified all-day value replaces session counts rather than adding them. Partial saved all-day
  counts retain actual gap/unfinished/retained coverage causes. Overlap, identity, authority and
  overflow causes never expose a count. Missing, deleted, stale-epoch or unverifiable evidence is
  not reconstructed or claimed as complete.
- Only involved overlapping/conflicted calendar days lose their number. Per-day windows remain
  their actual stored zones; current zone is only the already existing no-stored-authority fallback.
  No step window is split or rebound to a guessed current calendar.
- The existing native composer gains a display-only sparse-day path, sharing all source/lane/
  consent/deletion/fact checks and bulk queries. Its unfinished coverage is scoped to affected epoch
  days using actual authenticated physical run intervals and retained fact walls/uncertainty. An
  open run covers the remaining requested window, not earlier days; retained wall-regressed facts
  can independently extend its affected days. Point boundaries use their actual measured day.
  Unknown time/uncertainty or overflow is conservative; corrupt authority remains nonnumeric.
  This is one bounded run fact traversal with indexed day-window lookup, never per-day queries.
  A closed Monday day remains Complete when only Tuesday is unfinished. A qualified closed subset
  on an affected day is Recorded(MATERIALIZING); strict composition still returns Materializing
  and its adapter refuses the unsettled subset. Unsafe overlap remains nonnumeric. Independently
  qualified all-day counts survive unavailable native session composition.
- Statistics and selected History day subscribe to this one display stream. No racing second
  Steps repository is merged into it. Seven-day count/chips display saved per-day values and
  "%1$s recorded" for incomplete subtotals. Whole-week total overflow withholds only that total.
  In the Steps card, unavailable day labels remain explicit even when retained Steps history is
  shown after switching Steps off. In the generic Location-only sparse summary (Steps off and no
  saved Steps coverage), chips retain actual distance/duration and the existing nonnumeric Steps
  metric. Chosen-but-missing Steps never borrows a Location number. Complete verified zero remains
  numeric. Short copy is
  included in all 40 existing radio-detail locale resource sets with no global strings overwrite.
- The shared invalidation set now includes existing provider registration, native retention/replay
  and count-domain dependencies, so changed ownership/corrections cannot leave stale daily data.
- Production Hilt continues to bind the same Room numeric repository and the same existing
  stats-data source reader. Games, rewards, strict read/observe/decision methods and goal inputs
  are unchanged. Test-only game fakes reject accidental display API use.

## Authored evidence

CASE_INVENTORY.json records 27 new test-source methods (22 V1 methods preserved plus 5 V2 cases): real Room native/provider and imported
source-only chains, an earlier exact day beside today's subtotal, corrections/retractions, gaps,
overlap, calendar conflict isolation, stale data epoch, retained suffix, verified zero, saved DST
zone/no legacy cache counts, active native replacement subset, storage/cancellation, actual Room
invalidation, strict adapter refusal, independent Statistics/History consumers and Compose rows.
V2 adds actual two-day and 25-hour DST native Room cases, production Compose Location-only sparse
fallback and selected-missing Steps cases, plus ViewModel distance/duration preservation. Existing
metric assertions are scoped to their actual total node and per-day state checks remain distinct.
Existing presentation, subscription/cancellation and strict game fixtures are adapted; these authored
sources are not claimed as passing evidence. No physical provider or on-device UX proof exists.

## Selection and preserved work

The selected source base is 3246f1093d0956644fab28723368f60657dae003. At V2 freeze root HEAD was
0cdd3575fa5893d96986caa680d24ab08f3b7ad8; all 61 selected production/test/resource base paths matched
current committed bytes. 32 dependency snapshots are pinned to that actual root HEAD, including
the retained Steps helper and selector selected at 0cdd3575fa. Their prepared lifetime boundaries
preserve legacy cutoffs and all existing carrier/consent/lane/epoch checks; physical runtime/lease
continuity is not assumed implemented. SourceSessionDao includes root Start changes, but the saved
reader contracts used here are unchanged. No dependency bytes are inferred from a preserved draft.

BASE, SELECTED and WORKING_BEFORE preserve exact source bytes. No shared production/test source
or Git index was edited. Global mixed strings, importer/award drafts, all six protected paths and
unrelated working edits are excluded. FILES.json and SELECTION.patch define only this proposed
selection. Root owns fresh review, exact staging and coherent IMPLEMENTED_UNVALIDATED commits.

Remaining limitations belong to the full assembly: full unified History/day timeline/calendar,
source-only navigation/actions, all remaining lifecycle/runtime continuity work, schema assembly,
all 25 prerequisite closure, convergence gates, device evidence and final local integration.
This slice does not declare any of them complete.

## Root selection

V2 has fresh independent static acceptance in SOURCE_REVIEW.md. Exact reviewed
source and current dependency pins selected; mixed drafts remain preserved.
COMMIT_SELECTION is the separately reviewed LF-only realization; original raw
SELECTED, FILES and patch remain unchanged. All 27 authored methods remain NOT_RUN.
This is not final assembly freeze.
