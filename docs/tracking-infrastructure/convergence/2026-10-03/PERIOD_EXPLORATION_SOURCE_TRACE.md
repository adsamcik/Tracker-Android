# Period exploration metrics: independent source trace

Date: 2026-10-03. Reviewer: `/root/fresh_bundle_context_review_0930`.
**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Read-only source trace; no production
edits, Git, delegation, compile, tests, lint, schema, device/provider, CI or integration work.
Full inherited user requirements and the period answers in `DECISION_STATUS.md` apply.

## Actual producer and active writer

The premise that exploration has no production writer is incorrect for this candidate:

1. `tracker/engine/.../pipeline/ProcessorPipelineModule.kt:78–79` supplies an
   `ExplorationProcessor()` through the processor multibinding. The default
   `CellDiscoveryEngine` receives no persisted known tokens.
2. `TrackingOrchestrator.kt:405–427` starts the processor pipeline for a session and
   persists its domain events through `DomainEventRepository.persist`. `ProcessorPipeline.kt`
   checkpoints durable signal admission before analytical fan-out (238–304), then persists
   processor flush events (328–342, 560–572). Exploration requires ACTIVE tier.
3. `ExplorationProcessor.kt:31–54` reads accepted signal Location, runs S2 discovery and
   emits `CellDiscovered` only for `discovery.isNew`. It does not emit ordinary revisits.
   `onStop` discards the returned finalized quality result (63–65).
4. `DefaultDomainEventRepository.kt:27–29,95–102` saves dated discovery events.
   `DomainEventDao` deduplicates exact type/processor/timestamp/payload and delivers by
   insertion ID, so late events are not lost solely behind a timestamp cursor.
5. `app/.../di/StartupModule.kt:40–42` binds `GameModuleInitializer`; Application invokes
   module initialization after a ready startup result. `GameModuleInitializer.kt:47–55`
   drains discovery work at startup and on event-table observation.
6. `feature/game/.../event/ExplorationDomainEventConsumer.kt:74–108` applies bounded event
   batches and acknowledges the consumer cursor in the same Room transaction, inside a
   startup-generation lease. It inserts cells at 130–147; an existing cell received at
   a different timestamp takes `updateVisit` at 160–166. Dirty-table handoff follows commit.
7. `ExplorationStreakTracker.kt:15–55` updates the level-14 DAILY_DISCOVERY aggregate from
   genuinely new cells. It converts event UTC milliseconds to the phone's local date at
   processing time. It has no WEEKLY_EXPLORER write branch.

The pipeline's newness is the engine's in-memory discovered set, not database lifetime
newness: `CellDiscoveryEngine.kt:70–72,121–124`. A repeat cell is suppressed during that
processor's lifetime. A fresh processor can emit it again, and the consumer then updates
the aggregate's last visit/count/mask. That occasional rediscovery is not complete revisit
capture. The provided engine's cell-change path also does not emit the previous cell's
quality upgrade (115–135), despite its broader comment; same-cell updates return null.
No separate background Location-to-exploration fan-out appeared in the inspected source
coordinator/canonical writer/processor bindings. Source-only background Location therefore
cannot be considered covered by this session/tier producer merely because points are saved.

## What durable dated evidence exists

| Storage | Evidence available | What cannot be recovered |
| --- | --- | --- |
| `exploration_cell` | Cell token/level, first-discovery time, last-visit time, visit count, lifetime ORed season mask, center | Every middle visit date; which year a mask bit belongs to; per-source-point membership |
| `exploration_streak` | One current/best count and last increment day per type | Historical day/week membership or the portion of a lifetime best inside a selected range |
| `domain_event` | Individual dated emitted `CellDiscovered` payloads | Suppressed same-process revisits, complete background/import coverage, precise source/sample/session/epoch ownership |
| Retained Location samples and accepted receipts | Actual saved observation timestamps/coordinates; retained purpose authority for new native points | Visits never recorded or already erased; missing historical proof cannot be invented |

Domain events are operationally retention-aged: `RetentionPipelineWorker.kt:759–774`.
They therefore cannot be the durable historical seasonal ledger. `DomainEvent.CellDiscovered`
and its serialized entity lack the source-event/sample identity needed to retract a specific
discovery after source correction or deletion.

Cell retention deletes a whole aggregate by its first-discovery timestamp:
`ExplorationCellDao.kt:106–107` and `RetentionPipelineWorker.kt:739–751`. A cell first seen
before the floor but visited again after it can lose its aggregate. Streak retention deliberately
preserves nonzero lifetime bests (`ExplorationStreakDao.kt:45–52`). Full clear explicitly clears
cells, streaks and domain events (`AppDatabase.kt:1312–1323`). The consumer's generation lease
protects full clear, but does not establish source-specific correction/retraction membership.
Source deletion and canonical Location correction paths inspected do not rebuild these cell
or streak rows. Imported Location storage does not emit discovery events either.

Legacy migration copies existing aggregate rows (`LegacyV26Importer.kt:421–427`); it does not
recover unavailable dated visits from their lifetime mask/count.

## Concrete remaining metric gaps

- `DefaultWindowedMetricsProvider.kt:116–120` returns cumulative values for Longest trip,
  seasons, daily streak and weekly streak under interval/rolling requests. This violates
  the approved period rules. Its existing test at lines 182–196 explicitly expects the
  wrong cumulative daily streak and must be replaced with real period scenarios.
- The existing `first_discovered_at` index and level/date index can supply discovery dates
  inside a requested period. Daily/week streaks can be calculated from those actual dates;
  neither lifetime best nor latest increment is enough. The producer currently writes only
  the daily aggregate, so weekly zero is not proof of no weekly discovery.
- Seasonal period data cannot be computed by filtering cells on first-discovery date and
  ORing lifetime masks. Filtering on last visit is also insufficient: it drops middle visits
  and can lend season/year bits from outside the selected range.
- `SessionSegmentDao.kt:377–378` currently computes the all-time maximum distance.
  The approved existing trip-unit calculation needs the whole saved distance where its
  start is inside the selected range; do not impose end-within-range or prorate distance.
  The current presentation entity is a physical segment and carries optional logical/run
  IDs (`SessionTrackerComponent.kt:201–215`); this trace does not silently redefine the
  metric as a sum of a logical session or a TripCompleted event.
- The windowed API is bound in Hilt but no production caller was found. The real Summary
  route invokes `StatsPresenterViewModel.loadSummaryStats()` (`SummaryRoute.kt:57–65`),
  rather than the windowed provider. Backend authorship alone does not prove month/year
  product integration; trace the actual period consumer during its implementation review.

## Smallest implementation choices and approval boundary

Routine execution of approved rules can add a start-date-scoped maximum-distance DAO query,
bounded distinct discovery-date reads on the existing indexed cell table, and a small pure
consecutive-period calculation in stats/data. Preserve the existing closed `TimeWindow`
contract, filter dates before streak grouping, clamp streak membership to the chosen range,
and author boundary/cross-month/cross-year/late-date/empty/date-zone source scenarios. Reuse
the established product calendar convention; an absent weekly convention should not be
silently invented. Do not substitute a lifetime best or a generic zero after a read failure.
These changes still need the correction/retention limitations above addressed before a
full-chain completion claim.

There is existing source-owned Location qualification to reuse:
`RetainedLocationContextReader.qualifyInTransaction` accepts bounded receipt candidates,
checks canonical sample consistency, saved purpose grants, epoch/high-water/floor, deletion
fences and retention authority, without starting tracking. Existing history uses it together
with purpose-owned Location reads (`UnifiedHistoryNativeReader.kt:217–266`). Native and
background sibling purposes must remain independent; count one observation once. Legacy
or user-imported points need their existing honest origin/coverage treatment, not invented
native authority. Backup `savedFileChunk` semantics are intentionally broader and must not
silently become proof of qualified native metrics.

However, `stats/api/.../WindowedMetricsProvider.kt:15–16` explicitly requires pre-aggregated
tables and forbids scanning raw Location samples. Two maintainable options remain:

1. Change that specific contract to permit bounded read-only calculation from current saved
   Location observations using the existing source-owned qualifier/visibility seams. This
   avoids a new visit table and naturally follows current sample deletion/correction, but
   must be bounded, cancellable, snapshot-consistent and honest when evidence is missing.
   It requires approval of the contract/read-policy change if not already approved elsewhere.
2. Keep the aggregate-only contract and approve one small dated-visit projection tied to
   existing saved observation identity. Wire it from qualified session/background/import
   evidence and reconcile its correction, retention and deletion using those existing
   owners. This adds durable storage/migration and write/recovery work, so is a new storage
   decision requiring approval. Reusing operational discovery events without ownership
   would not solve the problem. No generic event/effects framework is needed.

First/last/mask alone cannot distinguish these histories: the same cell has spring first
visit, summer middle revisit and winter last visit, versus no summer revisit. Their aggregates
can have indistinguishable first/last/count/mask allocation across years while the chosen
summer-year metric differs. Missing dated evidence must stay unavailable/partial through
the existing result handling, never become an invented complete period number.

## Inspected source pins

| Source | SHA-256 |
| --- | --- |
| DefaultWindowedMetricsProvider | `958300b6262f5b765407d2ab75d88e279b33c5e679dd4ff0a45531ea93ae7819` |
| ExplorationProcessor | `592170ecd120854193f95db43968877c5b26838962b9e7934dbbfdaa123ded6c` |
| CellDiscoveryEngine | `e396708d6c97324ebf26f8a8f774d3a9b7f3fdbe52082a5ebdc51741a510d2d0` |
| ExplorationDomainEventConsumer | `06e70bb70bbe729f4d161c3f634781fd44b1ecc2548e699b7d3152a8c4eb25d0` |
| ExplorationStreakTracker | `3956369c9545bccc7f981edc2ab6cf9164c6b05886d60bdb99eccadb3ebc409a` |
| GameModuleInitializer | `422d0dbe6fdc4afc7a8b146e49c1a1043a9a6fe5ccb46b602b1c83a0df772d44` |
| ExplorationCellDao | `ca7c73cf4b96f011a48e2d400a3ca874c25fa38e783c9d216f1269b61c585220` |
| ExplorationStreakDao | `5e55100db5541beee25f56b2161a2b6fdc0b22bbd28289d9369948c3d0fc1ed0` |
| RetainedLocationContextReader | `4faba770bd149ba50b5570d5d13c90d8037366a79666a9f35252c8b8ec8b1f07` |

This records static findings and implementation inputs, not a passing test, complete
prerequisite, assembly freeze or integration-ready result.

## Concrete minimal storage proposal and logical-trip clarification

The coordinator clarified that the approved whole measured trip is the complete logical
trip where logical membership exists. Therefore a start-filtered physical-segment maximum
alone would be insufficient. Native logical trips need their original logical start and the
complete measured distance across every reciprocal physical run/member, including runs
outside the requested period. Select by logical start, then compare that whole total.
Do not filter individual members by the month/year before adding them, and do not count
one logical trip several times after process recovery. Legacy/imported records without
logical membership keep their independent existing trip units; never coalesce unrelated
records by timestamp.

Reuse existing bounded exact member discovery rather than create another session index.
`LogicalTrackingHistoryReader.kt:237–259,269–311` loads member cohorts with cancellation,
finite budgets and complete run/member reciprocity. Its current selected-member loader
uses the Steps selector and proves membership, not measured Location distance. No complete
source-qualified logical distance numeric reader was found in this trace. A routine narrow
stats/data implementation must therefore qualify the relevant saved Location contribution
and completeness before treating segment distance as measured, reject missing/partial
membership honestly, retain a stable whole-trip identity, and propagate limits/storage
failure rather than convert them to a zero. Existing presentation distance fields alone
are not proof of complete Location coverage.

If the aggregate-only period contract is retained, recommend one derived observation table:

- One row keyed by the existing canonical `location_sample.id`, with a foreign key to that
  sample and cascade on sample deletion. Save its actual UTC time, S2 cell token/level and
  the applicable season bit. This is observation membership, not a claimed visit duration
  or a count of separate trips; consecutive points in one place do not invent more visits.
- Reuse existing sample/receipt/import-origin identity and qualification. Add no signature,
  copied consent proof, new lifecycle framework or independent sensor demand. Native capture
  and background copies of one sample share that row; either surviving valid purpose keeps
  its observation available. Imported observations remain user data with honest origin.
- Project accepted saved points in their existing owner transaction, including background
  materialization and importer-managed sample writes. A bounded existing-data catch-up can
  fill rows for retained samples. Upsert by sample ID makes replay harmless; a changed sample
  replaces its derived cell/time row, rather than appending another visit.
- Period season masks query the dated rows directly, so an actual revisit contributes even
  when the cell was first discovered earlier. Discovery dates and affected existing cell/
  streak aggregates are reconciled from surviving rows after correction, deletion or floor
  changes; they are not decremented blindly. Existing lifetime accomplishment preservation
  must follow its explicit retention policy and cannot imply erased observations remain.
- Add only the indexes needed for level/time range and cell/time lookup, the migration,
  source-owned insertion/reconciliation hooks, and focused Room/contract sources for
  corrections, source siblings, erase, floor, replay and imported timestamps. Avoid another
  generic event/effects layer.

This proposal needs user approval because it adds durable storage and write/recovery policy.
The lower-storage alternative is the explicit contract change allowing bounded read-only
derivation from existing current Location samples; both satisfy the same user-facing period
rule only when genuine dated evidence exists. Neither may fabricate missing historical
revisits from a lifetime mask, visit count or guessed interval.
