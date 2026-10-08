# Tracking completion plan

Updated: 2026-10-08. Initial inspected candidate: `5b19ca833596b602643e99d291c4ceae0d3b5cc6`.

## Outcome and current boundary

People choose what to record. Supported sources work independently, adapt to the
five quality/power positions and share physical providers. Sessions organize the
same data and prefer higher quality for participating sources. Optional enrichment
uses already authorized saved data and does not turn another source on.

Complete tracking and its everyday data surfaces first. Keep the remaining file
and data-backup work in this plan, but defer it until the tracking core is assembled.

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Author production and test source,
perform static independent reviews and commit coherent selected changes. Tests are
**NOT_RUN**. Do not run compilation, Gradle, tests, lint, schema generation/drift,
`git diff --check`, device/provider activation or CI before T25 genuinely closes.
Do not merge into `dev/v10` or push during this phase.

This is the current execution order. The older [293-requirement ledger](../../IMPLEMENTATION_TODO.md)
and [work-item registry](../../WORK_ITEMS.json) retain their stable scope and history;
unchecked historical rows are not evidence that selected implementation is absent.
The [25-prerequisite trace](../../assembly-coverage/2026-09-28/PREREQUISITE_TRACE.md)
remains the complete-chain acceptance boundary. None of those chains is closed.

## Existing work to preserve

The selected candidate already contains shared providers, five-level plans,
thermal refresh, independent radio hosting, finite radio request/cooldown state,
per-reading radio qualification/details, rearmable passive Location, temporary
Location leases, automatic Activity CONTROL publication, hourly/enable/open Steps
copies, bounded History readers and source-owned all-day status. These need final
composition and evidence, rather than replacement implementations.

Recent selected slices include Steps permission repair (`5b19ca8335`), source
participation planning coverage (`5000ed377e`), cold automatic callback handling
(`b0ab706287`), Location transition test source (`e37c0844f9`) and radio live
freshness (`da73b3d622`). They have static review and authored tests, not runtime proof.

Activity/Pressure all-day recording is currently unsupported; their existing
writer generation supports manual capture. Activity CONTROL is separate from
recorded Activity. Keep unsupported states accurate rather than enabling a new
product or changing activation gates implicitly.

## A. Complete the tracking core

- [ ] **T01 — Reconcile the working input.** Refresh the commit/ref/draft inventory.
  Assign each candidate and draft a selected, superseded, preserved or excluded
  disposition. Preserve mixed edits, original refs, protected paths and unrelated
  work. Select exact coherent source changes rather than entire dirty files.

- [ ] **T02 — Finish Start, End and Turn off tracking.** Repair both confirmed
  Start lock-order failures: automatic callback reservation and service/database
  acceptance. The presented coordination repair is authorized by the October 7 execution instruction. Finish exact one-use
  permission continuation for app/widget/shortcut starts, preserving the original
  source choices and rejecting superseding Start/End/Off or stale process requests.
  Keep Off saved until that exact Start is accepted. Check every external action
  and cleanup acknowledgment; End releases the session, Off stops collection.

- [ ] **T03 — Preserve Steps through quality changes.** Independently review and
  approve the smallest connected continuity design, then implement it through
  caller authority, physical ownership, storage and retained readers. A fixed
  latency alone and an unchecked carried counter baseline are incomplete repairs.
  Cover other-source changes, End/Off, real consent boundaries, reset/reboot,
  retries, exact counts and existing file compatibility. The independently narrowed recording-reference proposal is authorized; any
  newly discovered authority-rule change still requires explicit review/approval.

- [ ] **T04 — Close source identity and purpose publication.** Trace current policy
  through demand, caller authority, registration, sequence, durable admission,
  projection and product query for each supported source/purpose. Finish the caller
  inventory across UI, workers, legacy routes and temporary features. Prove stale
  callbacks and late revocation cannot borrow newer permission or revive erased data.

- [ ] **T05 — Close shared provider and foreground-host lifetimes.** Compose each
  existing physical owner with independent session/background/control/feature
  leases. Verify acknowledged retirement before replacement, source-local denial
  and correct Android service types. Ending one purpose must preserve authorized
  siblings. Optional radio hosting adds no active-scan allowance.

- [ ] **T06 — Finish adaptive quality and source-specific power behavior.** Preserve
  participation, legacy/custom choices and the chosen-source live quality preference.
  Compose current motion, battery/charging, saver, Doze, thermal, permissions and
  availability into actual owner plans. Keep useful Balanced Location, cheap Steps
  and passive radio callbacks; verify finite request ownership, original cooldowns
  and no budget replenishment during quality changes or recovery. Do not add another
  optimizer or timer. Author the missing admitted-motion-to-live-replan composition.

- [ ] **T07 — Close background Location delivery and recovery.** Compose the existing
  Google/system fallback, authenticated receiver, cold rearm, active takeover and
  historical admission through the one Location owner. Decide the pending durable
  gap-record scope before adding storage. Cover permission/provider loss, process
  death, reboot, deletion and unavailable delivery without invented coordinates.

- [ ] **T08 — Complete all-day Steps data and daily presentation.** Preserve the
  selected hourly/enable/open provider copies, 30-second attempts, real provider
  windows and bounded cursor/revision recovery. Daily totals must not add session
  counters to overlapping provider counts. Following the approved display direction, stop a future
  midnight cursor from hiding trustworthy saved subtotals; keep independently valid
  days visible and preserve strict game/achievement completeness. Optional first-enable
  permission-prompt synchronization is authorized; the clicked repair already works.

- [ ] **T09 — Finish automatic tracking composition.** Connect real trigger receipt
  to accepted Start, chosen capture owners, saved products and End/Off. Preserve
  transition-detection choices and repair states. Trigger-only Activity is not
  recorded Activity. Keep the approved 48-hour CONTROL lifetime and accurate
  source-specific automatic capability limits; fixture enqueue is not acceptance.

- [ ] **T10 — Finish temporary Location consumers.** Trace My location, Follow and
  games through existing independent shared leases and freshness requirements.
  Explicit user demand may request higher quality than ordinary tracking settings.
  Verify no map-open acquisition, no accidental persistence, independent release,
  stop/permission races and stale-fix handling. Preserve removal of unused sunrise code.

- [ ] **T11 — Close Wi-Fi/Cell data composition.** Trace real available network/tower
  details through per-reading admission, child storage, retained history and cleanup.
  Preserve every independently valid historical group, measured times, old identities
  and versioned compatibility. Stricter capture must not discard valid background
  data or lend permission backward. Include recoverable older details; exclude
  SIM/subscriber IDs. Prove joined-purpose End/erase and shared-parent integrity.

- [ ] **T12 — Complete retained-data authority and effects of changes.** Compose
  correction, retention, purpose-scoped erase, replay and full clear on surviving
  native/imported siblings. Turning collection off retains authorized history.
  Finish invalidation and count-domain/derived-product retraction, pruning dependencies
  and CONTROL expiry. Preserve unknown older origin honestly. Core privacy remains
  here even while backup/transfer-specific cleanup is deferred.
  The run-only Location proof is an internal acquisition diagnostic. Saved products
  already use retained qualification independently; the specific concern is reconciled
  in `RETAINED_LOCATION_LIFECYCLE_SELECTED/CALLER_TRACE.md`. Preserve both scopes
  without reconstructing expired acquisition authority or adding a proof store.

## B. Complete everyday organization and product surfaces

- [ ] **T13 — Finish session organization.** Preserve existing annotation creation,
  editing, removal, names/activity labels and paging. Finish exact six-source contents,
  retrospective membership and relation to unified History. Sessions group observations
  without moving their measured dates or owning daily Steps. Check edits/deletions
  and detail navigation; do not rebuild the existing editor.
  Current cards open metadata editing and one original segment, not the existing
  six-source contents reader. Retrospective radio membership still requires whole
  batches to fit; imported background archive membership is unavailable. Connect
  contents under the held History design and complete exact import membership under
  T20/T21. See `IMPORTED_SESSION_LABEL_SELECTED/T13_AUDIT.md`.


- [ ] **T14 — Finish unified History.** Connect the selected bounded reader foundation
  to paging, displayed local days, calendar, timeline and source details. Include
  native/background/imported data and optional session organization. Preserve
  each measured timestamp across midnight, genuine aggregate windows, gaps,
  duplicate/conflict outcomes, corrections and invalidation. Select reviewed held
  caller/UI slices only after checking their exact prerequisites.
  Replace the remaining trip-only fixed 30-day Timeline load and trip-only Calendar
  path. Finish native/imported Steps and Pressure detail routes and exact action
  selectors; a displayed date range must never become deletion authority.

- [ ] **T15 — Finish metrics, actions and effects.** Wire qualified source summaries,
  exact Steps and supported source-local export/delete actions. Present selected-period
  streaks and saved-point longest trips with the approved scope; do not substitute
  All time for a month/year. The declined dated exploration lookup remains excluded
  and its dependent period statistics unavailable. Cover correction/delete retraction
  and non-Steps awards without granting legacy rewards twice.
  Replace legacy-only radio statistics/dialog reads with qualified current source
  data. The points consumer/worker still uses session-end/legacy trip calculations;
  connect actual correction/deletion retraction before calling effects complete.

- [ ] **T16 — Finish optional map enrichment.** Compose existing retained source-owned
  reads with Wi-Fi/Cell map layers and invalidation from either source. Apply the
  approved matching rules and accuracy/uncertainty. Show available device observations
  without an extra missing-location notice; radio history remains usable without
  Location. Opening this retained view must never request a sensor.

- [ ] **T17 — Finish settings and visible tracking states.** Complete source-aware
  setup, Dashboard, Tracker, Statistics, notifications and widgets. Reuse chosen-source
  trustworthy saved products; the presented shared saved Location-summary interface
  is authorized. Finish recovery, current-choice permission repair, concise localized copy,
  accessibility and external actions. Preserve ordered notification saves and approved
  Retry/Leave without saving behavior. Distinguish waiting, gaps and unsupported
  capability from measured zero; never show unqualified old distance.
  Replace the mixed-session-without-Location placeholder with qualified existing
  source overviews. Review held radio detail rows/copy/localization before selecting
  them; do not silently adopt a new visible cap or stored-zone date rule.

- [ ] **T18 — Converge storage and dependencies.** Assemble final entities, DAOs,
  indexes, triggers, invalidation and Hilt wiring from selected contracts. Preserve
  released `main` schema-12 data, recoverable older details and unknown development
  databases. Candidate 27/28/29 were never released. Author populated migration/reopen
  and full-clear sources now; generate/check the final schema only in T26.
  Released-data review found active recovery still omits original session-label
  relationships, distance breakdowns, first-seen radio/coordinate metadata,
  rejected sessions, count-only Wi-Fi rows and operator names. They survive in
  the preserved original database. Concrete storage reuse remains proposed in
  `RELEASED_DATA_RECOVERY_AUDIT/PRESERVATION_PROPOSAL.md`; extra preservation fields/older-record tables approved on October 8. Migration should reuse the existing import/export workflow; its smallest implementation seam is under review.


- [ ] **T19 — Complete integration-scenario source.** Author missing provider-to-admission-
  fact-to-query-to-product/file-to-retirement cases for solo sources, supported purposes
  and representative combinations. Include join/leave, permission/Start races, source-local
  failures, process/reboot/FGS recovery, privacy/erase, migration and temporary-feature
  nonpersistence. Existing 64-subset planning/proof tests do not replace these cases.

## C. Deferred file and backup completion

Keep these obligations and existing approvals; begin after the tracking core.

- [ ] **T20 — Complete source file transfers.** Finish radio exporters/registry and
  exact Location transfer/conflict UI. One Wi-Fi or Cell choice includes its available
  session/background data. Implement approved individual readings, selected-date
  scope, optional complete sessions, old-file compatibility, overlap merging and
  exact membership. Location Keep/Replace uses one import-wide choice and the
  approved current correction everywhere, retaining the original phone reading.

- [ ] **T21 — Complete session bundles.** Connect the existing exact typed adapters
  and bundle writer to the approved shared preview/write entry point and session detail.
  Initially select available sources, allow exclusions and preserve annotations.
  Finish original-job epochs, retries, partial failure, duplicate Steps/radio membership,
  exact content rechecks and safe final handoff through existing import receipts.

- [ ] **T22 — Complete geographic exports and schedules.** Finish source-aware JSON
  and truthful GPX/KML scope without invented coordinates/timestamps. Respect selected
  data, complete-session choice, indivisible windows and disclosed expansion. Carry
  exact LastSession/incremental identity through worker, reader, serializer and handoff.
  A chosen format produces only that format. Any incompatible wire change needs approval.

- [ ] **T23 — Complete data-only logical backup and restore.** Include recorded/imported
  data, sessions, corrections/deletions and required app-owned data files; exclude settings
  and schedules. Reimport into existing databases after confirmed replacement, with
  interrupted-operation recovery and tracking stopped. No database file swap or restart.

- [ ] **T24 — Complete transfer/archive privacy cleanup.** Finish reviewed raw-archive
  scope, dedicated private export storage, identifiable old-file cleanup and uncertain
  leftovers, persisted unfinished external-file recovery and cancellation/draining during
  full clear. Coordinate same-filename recovery only after its pending decision. Preserve
  database-open export admission and user-controlled external copies.

## D. Freeze, verify and deliver

- [ ] **T25 — Freeze the complete implementation assembly.** Resolve required decisions,
  finish production/test source and disposition every held draft. Have an independent
  reviewer trace all 25 prerequisites. Record exact commits, schema inputs, capability
  limits, test inventory and required commands. Declare complete only when required
  implementation is actually assembled. Deferred file work remains part of this full
  gate; tracking priority alone does not authorize an earlier validation batch.

- [ ] **T26 — Run and repair convergence gates.** Only after T25: compilation/Hilt/Room,
  final schema generation/drift and released-data migrations/reopen, focused required
  checks, `ciUnitTest` and `ciCheck --continue`. Repair concrete failures, rerun required
  complete gates and record exact results. An authored test is not a passing test.

- [ ] **T27 — Collect real Android evidence.** Obtain bounded activation authority.
  Use one recorded representative configuration for real source-to-storage-to-UI/file-
  Stop, shared lifetimes, permission/process/reboot/FGS recovery and battery/quality
  behavior. Check accessibility and honest gaps/capability limits. Separate host,
  emulator and physical-provider evidence; never promise recovery after force-stop.

- [ ] **T28 — Perform fresh final review.** Give a review-only agent original task
  messages, approved decisions, final diff and verification/device results. Fix concrete
  correctness, data-loss, usability and unnecessary-complexity findings; recheck affected
  areas. Do not treat an earlier slice review as the final full-effort review.

- [ ] **T29 — Integrate into local dev/v10.** Preserve a clean main worktree, rebase
  selected work onto latest local `dev/v10`, run required checks, merge locally and
  verify the integrated result. Record the final commit and update status. Clean up only
  safely merged worktrees/branches after preserving unrelated work. Do not push or deploy.

## Pending decisions, separate from approved execution

AGENTS.md requires approval before changing these designs. Ask in plain language
when the concrete proposal is ready; do not reask settled decisions.

| Decision | Affected tasks | Current state |
| --- | --- | --- |
| Existing cancellation/lock-order repair for both Start failures | T02, T09 | Authorized by the October 7 instruction to execute the full presented plan. |
| Smallest Steps recording continuity design and persistent meaning | T03, T18 | Narrowed presented proposal authorized. Bounded same-live-counter continuation explicitly authorized on October 8; fresh caller authority remains strict. |
| Recorded-so-far daily Steps with honest missing coverage | T08, T15, T17 | Presented behavior authorized. Per-day display-reader contract explicitly approved and V2 independently accepted; games remain strict. |
| Shared saved Location summary for Tracker/notification/widget values | T17 | Presented reader reuse authorized; October 8 also approves valid coordinates without accuracy for ordinary summaries, while radio joins still require accuracy. No new collection. |
| Optional two-second first-enable provider-status wait | T08, T17 | Presented bounded wait authorized; existing repair button remains usable. |
| Saved network/tower rows in existing radio detail screens | T11, T14, T17 | Presented scrolling-list direction authorized; complete lazy-row implementation accepted; simpler authority-copy removal explicitly approved and independently accepted. |
| Shared measured-observation list for Timeline and Calendar day | T14, T17 | Concrete screen and existing-reader extension explicitly approved on October 8; implementation in progress. See `UNIFIED_HISTORY_PRESENTATION_PROPOSAL/SOURCE_TRACE_AND_PROPOSAL.md`. |
| Recover original released data in current rows and older-record tables | T13, T18 | Exact V2 storage reuse proposal explicitly approved on October 8, with migration reuse of the existing import/export workflow required; implementation seam under review. See `RELEASED_DATA_RECOVERY_AUDIT`. |
| Durable background Location gap records | T07, T12 | Unanswered storage choice. |
| New Activity/Pressure divergent-import conflict display | T14, T20 | Keep existing outcome until a concrete UI proposal is approved. |
| Exact repeated Steps file membership for bundles | T21 | Review existing receipt semantics before proposing changes. |
| Incompatible JSON wire representation, if necessary | T22 | Six-source/all-day JSON goal already approved; only incompatible representation unresolved. |
| Export-settings load error/Retry | T24 | Unanswered, deferred. |
| Shared coordination while reusing an unfinished external filename | T24 | Unanswered, deferred. |

## Complete-chain coverage mapping

All rows remain **OPEN**. Mapping does not claim the corresponding code is absent.

| Existing prerequisite | Plan tasks |
| --- | --- |
| WI-STEPS-MANUAL-001 | T02–T06, T08, T13–T15, T19–T21 |
| WI-STEPS-AUTOMATIC-001 | T02–T06, T08–T09, T19 |
| WI-STEPS-AMBIENT-001 | T04–T06, T08, T12, T14–T15, T19–T20 |
| WI-PRESSURE-VERTICAL-001 | T04–T06, T12–T15, T17–T20 |
| WI-LOCATION-VERTICAL-001 | T04–T07, T10, T12–T20 |
| WI-ACTIVITY-VERTICAL-001 | T04–T06, T09, T12–T15, T17–T20 |
| WI-WIFI-VERTICAL-001 | T04–T06, T11–T12, T14–T20 |
| WI-CELL-VERTICAL-001 | T04–T06, T11–T12, T14–T20 |
| WI-SCHEMA-CONVERGENCE-001 | T01, T03, T11–T12, T18, T25–T26 |
| WI-AUTHORITY-PURPOSE-001 | T04–T05, T07–T12, T18–T19 |
| WI-PURPOSE-PUBLICATION-001 | T02–T05, T08–T09, T18–T19 |
| WI-BROKER-PROVIDER-OWNERSHIP-001 | T03–T05, T07–T11, T19 |
| WI-SOURCE-CALLER-GUARD-001 | T02–T05, T09–T10, T19 |
| WI-QOS-BATTERY-001 | T03, T05–T08, T11, T19, T27 |
| WI-LIFECYCLE-RUNTIME-001 | T02, T04–T05, T07–T10, T19 |
| WI-LIFECYCLE-PRODUCT-PROOF-001 | T04, T08, T11–T12, T14, T17, T19 |
| WI-STEPS-NUMERIC-001 | T03, T08, T12, T14–T15, T17, T19–T21 |
| WI-CROSS-SOURCE-RECOVERY-001 | T02, T04–T12, T18–T19, T23–T24, T27 |
| WI-CROSS-SOURCE-PRIVACY-001 | T04, T07–T12, T16, T18–T24 |
| WI-SHARED-FILE-ASSEMBLY-001 | T11–T12, T18–T24 |
| WI-SOURCE-ACTIONS-EXPLANATION-001 | T02, T08, T13–T17, T20–T22 |
| WI-SHARED-HISTORY-DAY-RANGE-001 | T08, T11–T14, T16–T19 |
| WI-INTEGRATION-SCENARIOS-001 | T19, with T02–T24 source prerequisites |
| WI-UI-SURFACES-001 | T02, T08, T10, T13–T17, T20–T22, T27 |
| WI-NONSTEPS-EFFECTS-001 | T12, T15, T17, T19 |

## Immediate execution

1. Commit this reconciled plan and link it from the current ledger/progress index.
2. Present the pending core decisions together using the questions tool; defer file questions.
3. Start approved source work while those answers are pending: close actual all-day
   facade capability-boundary coverage and select the next bounded existing product/data slice.
4. Repair Start, Steps continuity and daily presentation as their specific design choices
   are approved. Use independent review and exact local commits for each coherent step.
5. Update these checkboxes with actual completion evidence; do not mark broad chains done
   from an isolated implementation or unexecuted fixture.

## Execution progress

- [x] Reconcile, independently review and commit this plan: `2f03d3ebe7`.
- [x] Capture the refreshed T01 preservation inventory at that commit; see
  `CURRENT_INPUT_STATUS.md`. Final draft dispositions and assembly remain open.
- [x] Select the first actual all-day facade capability/cancellation test slice:
  three methods, four scenarios and independent source acceptance in
  `ALL_DAY_UNSUPPORTED_FACADE_SELECTED/SOURCE_REVIEW.md`. All remain NOT_RUN.
- [x] Select composed temporary Location flow source: two methods through the
  actual provider/controller/runtime, independent freshness and lifecycle release/rejoin.
  Static review: `TEMPORARY_LOCATION_FLOW_SELECTED/SOURCE_REVIEW.md`; NOT_RUN.
- [x] Select existing legacy cleanup drain validation-order repair and four actual
  Room planner cases: `LEGACY_CLEANUP_DRAIN_ORDER_SELECTED/SOURCE_REVIEW.md`.
  Mixed drafts preserved; cases NOT_RUN, full End/ownership chains remain open.
- [x] Select History detail cancellation repair and one suspended repository-read
  case: `TRIP_DETAIL_CANCELLATION_SELECTED/SOURCE_REVIEW.md`. Existing
  result mappings preserved; cases NOT_RUN, full History composition remains open.
- [x] Select daily Steps saved-count composition repair and seven actual Room
  scenarios: `DAILY_STEPS_COMPOSITION_SELECTED/SOURCE_REVIEW.md`. Strict
  numeric rules preserved; NOT_RUN, display-reader integration still pending.
- [x] Select reviewed saved Wi-Fi/Cell detail lists: lazy rows, no twenty-row cap,
  current local measured dates, omitted missing fields and forty localized heading
  resources. `RADIO_DETAILS_LAZY_SELECTED/SOURCE_REVIEW.md`; four new cases NOT_RUN.
- [x] Select reviewed Steps recording-origin, raw integrity, unshipped migration,
  lifetime predicates and stable adaptive-request foundation: fourteen paths and
  nine new methods in `STEPS_CONTINUITY_SELECTED/SOURCE_REVIEW.md`; NOT_RUN.
  Physical continuity, current authority and retained-reader integration remain open.
- [x] Select reviewed two-second first-enable Steps availability synchronization:
  ten ViewModel methods and unchanged actual screen permission guards in
  `STEPS_ENABLE_WAIT_SELECTED/SOURCE_REVIEW.md`; NOT_RUN. Advanced Off and
  ViewModel cancellation fence the old opportunity; no save queue blocking.
- [x] Trace existing admitted motion-to-replan wiring and select seven composition
  methods through the real controller/planner/storage/registry. No new loop:
  `MOTION_REPLAN_COMPOSITION_SELECTED/SOURCE_REVIEW.md`; NOT_RUN. Actual
  service scheduling, admission, provider continuity and battery remain separate.
- [x] Reconcile original Location diagnostics with existing retained saved readers
  and select nine actual Room lifecycle methods: `RETAINED_LOCATION_LIFECYCLE_SELECTED/SOURCE_REVIEW.md`;
  NOT_RUN. No new proof storage; End/Off commands and hardware remain unexecuted.
- [x] Select repaired Start coordination: both lock cycles, Off-until-acceptance,
  original callback/Stop fencing, process-scoped continuation and atomic exact
  shell cleanup. Thirty-six paths and fifty-seven authored/replaced methods have
  final static acceptance in `START_COORDINATION_REPAIR_SELECTED/SOURCE_REVIEW.md`;
  NOT_RUN. Separate APPLY physical cleanup stays with existing durable Stop.
- [x] Select the existing goal receipt/retraction consistency repair: nine paths and
  nineteen actual Room/projector methods in `POINTS_RETRACTION_SELECTED/SOURCE_REVIEW.md`;
  NOT_RUN. Legacy SESSION rewards and dormant goal activation remain open.
- [x] Select approved simpler radio details: remove technical cards/footer, concise
  Imported labels in forty existing locales, updated production callers and six
  new/updated Compose methods in `RADIO_DETAILS_POLISH_SELECTED/SOURCE_REVIEW.md`;
  NOT_RUN. Saved readings and coverage remain intact; full T17 stays open.
- [x] Connect the approved Steps recording lifetime to native retained session
  history, preserving legacy boundaries and preparing bounds once per run. Four
  paths and eight authored methods in `STEPS_RETAINED_LIFETIME_SELECTED/SOURCE_REVIEW.md`
  have independent V2 static acceptance; NOT_RUN. Runtime/portable composition open.
- [x] Select approved per-day Steps display and one Statistics/History stream:
  sixty-one paths, forty localized resource sets and twenty-seven authored methods
  have fresh V2 acceptance in `DAILY_STEPS_DISPLAY_SELECTED/SOURCE_REVIEW.md`;
  NOT_RUN. Incomplete days are isolated, useful source-aware fallbacks preserved,
  strict game/reward APIs remain strict. Full source/device chains stay open.
- [x] Select composed Location Off and independent temporary-use coverage through
  real command persistence, Room authorization/caller retirement and shared owners:
  one new method in `LOCATION_OFF_UI_COMPOSITION_SELECTED/SOURCE_REVIEW.md`,
  NOT_RUN. Backend/sink fixtures do not prove WAL, hosting or Android behavior.
- [x] Select real automatic transition-to-Steps capture composition: three authored
  Room/runtime methods through current Stop fencing, preparation/claim/acceptance,
  ingress/native fact projection and End/Off in
  `AUTOMATIC_CAPTURE_CHAIN_SELECTED/SOURCE_REVIEW.md`; V2 bounded-await repair
  independently accepted, NOT_RUN. Platform/Ready boundaries remain fixtures.
- [x] Repair background radio child-before-parent cleanup using existing exact
  DAO methods and select eight actual retained Room cleanup/history methods:
  `RADIO_CHILD_MAINTENANCE_SELECTED/SOURCE_REVIEW.md` and
  `RADIO_RETAINED_CLEANUP_COMPOSITION_SELECTED/SOURCE_REVIEW.md`. V2 source accepted,
  NOT_RUN. Source-only helper UX wiring, captured/imported siblings and full T11 open.
- [x] Repair notification Back during an unfinished drag through the existing
  ordered save queue. Two paths and three new ViewModel methods have independent
  source acceptance in `NOTIFICATION_DRAG_EXIT_SELECTED/SOURCE_REVIEW.md`; NOT_RUN.
  Existing recovery/accessibility cases preserved; full T17/device coverage open.
- [x] Remove the legacy user-label fallback from detected Activity import; two
  Room/import/TripDao cases and one corrected existing NULL expectation in
  `LEGACY_ACTIVITY_DOMAIN_SELECTED/SOURCE_REVIEW.md` have exact V2 static acceptance,
  NOT_RUN. Genuine detected Activity and original data remain intact; full T18 open.
- [x] Repair imported session-label display/removal through the existing editor,
  ViewModel and Room edit: seven paths and seven authored methods in
  `IMPORTED_SESSION_LABEL_SELECTED/SOURCE_REVIEW.md` independently accepted, NOT_RUN.
  Name-only edits preserve labels; No label clears them without changing source data.
- [x] Trace released schema-12 recovery and independently review the lean V2
  preservation proposal in `RELEASED_DATA_RECOVERY_AUDIT/SOURCE_REVIEW.md`.
  Eight released and forty-three historical candidate pins retained; known later
  fixes recorded. The October 8 answer approved the concrete V2 preservation;
  its selected implementation is recorded below, with validation still deferred.
- [ ] Complete the remaining T19 integration coverage and all full-chain prerequisites.

T15 follow-up inspection found the period metric backends have no production
Summary consumer. Period propagation/rows and precise distance display need a
concrete product proposal before implementation; do not change achievements to
create a caller. This is recorded work, not a new implicit UI approval.

## October 8 practical design continuation

- [x] Record all four explicit answers and the general whole-application design
  preference in EXECUTION_DECISIONS.md. Preserve approved behavior with source-local
  implementation; do not add arbitrary data restrictions.
- [ ] Complete Steps live-counter continuity and reuse its lifetime in the existing
  portable reader so quality-only revisions do not hide valid recorded steps.
- [ ] Complete ordinary saved Location summaries with nullable accuracy and shared
  retained qualification; wire the existing product callers.
- [ ] Implement the approved shared observation list/read mode for Timeline and
  Calendar's selected day, with measured dates, paging and independent failures.
- [ ] Preserve the approved older fields/records through the existing import receipt
  workflow, following the source-reviewed smallest migration seam.
- [ ] Apply independent practical-use findings to real callers; review concrete
  restrictions before removing them and keep necessary Stop/delete/permission rules.

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED. All tests NOT_RUN. All 25 full-chain
prerequisites and T25 remain OPEN. No gates or dev/v10 integration started.

- [x] Select the independently reviewed Steps portable lifetime convergence: one
  prepared source-local run interval map, original marked recording manifests,
  preserved null-origin behavior and four new Room cases. See
  ../2026-10-08/STEPS_PORTABLE_LIFETIME_SELECTED/SOURCE_REVIEW.md; tests NOT_RUN.
  Live provider continuity and complete format integration remain open.
- [x] Obtain independent practical whole-app source review. Actual missing History
  wiring, legacy browse coverage, dense-day pagination, optional map failure and
  unreachable radio/session exports are recorded in
  ../2026-10-08/PRACTICAL_APP_REVIEW/automatic_stop_fence_review_1007_facbb/REVIEW.md.
  These are implementation findings, not device results or acceptance.

- [x] Select independently reviewed radio map failure isolation: valid legacy
  data survives unavailable optional context, valid retained device points survive
  a failed legacy reader, successful reload removes old enrichment, and cancellation
  stays fenced. Five new layer cases plus an existing repaired case are NOT_RUN.
  See ../2026-10-08/RADIO_MAP_PARTIAL_READ_SELECTED/SOURCE_REVIEW.md.

- [x] Select independently reviewed shared radio row reuse: optional map context
  consumes already accepted typed native rows from the existing source read, with
  unchanged Snapshot/detail/identity checks and no repeated raw-window query.
  Two new Room methods and one extended sibling case are NOT_RUN. See
  ../2026-10-08/RADIO_QUALIFIED_ROW_REUSE_SELECTED/SOURCE_REVIEW.md.

- [x] Select independently reviewed released-data preservation: 17 nullable original
  fields, two original raw tables, the same NetworkOperator registration and
  segment-label backfill through the existing atomic import/receipt workflow.
  Thirteen new host/migration methods are NOT_RUN. See
  ../2026-10-08/RELEASED_DATA_PRESERVATION_SELECTED/SOURCE_REVIEW.md.
  Generated schema, actual History/portable use, legacy universal erase and direct
  trip-delete annotation cleanup remain open; full T18/T25 are not closed.

- [x] Select independently reviewed Steps live-counter continuity V2: fresh
  accepted settings keep only the exact running Steps recording; actual owner/
  listener/baseline rechecks and native ingress stay connected. Genuine boundaries
  retire the original reference; End has its exact final callback cutoff.
  Nine new Room/runtime/recovery methods are NOT_RUN. See
  ../2026-10-08/STEPS_CONTINUITY_RUNTIME_SELECTED/SOURCE_REVIEW.md.
  Actual hardware, historical saved-read repair and full T03/T25 remain open.

- [x] Obtain explicit historical saved-reading and shared live-Location-total
  approvals. Confirmed actual quality/End generation loss blocks the held Location
  V2 summary selection; repair across affected historical sources is in progress.
- [ ] Finish and freshly review that historical repair, shared live consumer update
  flow and actual measured-observation History caller/data wiring.

### October 8 — bounded background radio parent reads

- [x] Author and independently review exact current Wi-Fi/Cell parent and saved-gap ID reads in the existing source repositories. Native and imported scope stays separate internally; existing authority, child completeness, correction lineage, erase and retention checks are reused. Four production paths and two new actual Room test methods selected; IMPLEMENTED_UNVALIDATED, tests NOT_RUN. Evidence: `../2026-10-08/BACKGROUND_RADIO_SELECTED_PARENT_READ/`.
- [ ] Finish exact measured radio leaf qualification across captured, background, imported and released records, source-level failure recovery, and actual shared History routing. The selected read hook does not close T13 or any full-chain prerequisite.

All 25 prerequisites and T25 remain OPEN. No validation, provider activation, integration or push.

### October 8 — original saved authority and Location summaries

- [x] Select the freshly reviewed 43-path historical repair and carried Location summary V2. Original immutable recording manifests and the existing first Stop receipt determine saved authority; later quality/End operation generations do not hide valid readings. New-callback and erase/retention checks remain. Ordinary coordinate summaries preserve unknown accuracy; optional radio matching keeps its own accuracy requirement.
- [x] Author 26 new historical and preserve 23 carried Location test methods, all NOT_RUN. Exact inputs and V1/V2 static acceptance: `../2026-10-08/HISTORICAL_SAVED_AUTHORITY_SELECTED/` and `HISTORICAL_SAVED_AUTHORITY_REVIEW/`.
- [ ] Complete the shared live total and its actual consumers, measured six-source History dispatcher, native portable export checks, effective Location corrections and remaining full-chain integration.

IMPLEMENTED_UNVALIDATED. All 25 full-chain prerequisites and T25 remain OPEN. No execution gates, activation or dev/v10 integration.

### October 8 — shared saved Location live total

- [x] Select the independently accepted V4 shared live-total core, composed exactly with committed historical authority. One shared controller observation seeds saved data, catches up new receipts in finite pages and preserves dependency wakeups through conflation. Relevant lifecycle/data changes clear or rebuild the temporary total.
- [x] Author 26 Room/controller/handoff/fake-contract methods, all NOT_RUN. Exact composition: `../2026-10-08/LIVE_SAVED_LOCATION_TOTAL_COMPOSED/`; independent source acceptance: `LIVE_SAVED_LOCATION_REVIEW/V4_ACCEPTANCE.md`.
- [ ] Finish the Tracker/notification/widget numerical consumers and installed-widget refresh decision. Effective imported corrections, logical multi-run/day/imported numerical readers and full source chains remain open.

IMPLEMENTED_UNVALIDATED. All 25 full-chain prerequisites and T25 remain OPEN; no gates, activation or dev/v10 integration.

## October 8 measured-History assembly progress

These checks record completed source authorship/review only. The following inputs
are **held**, not selected into production, until their connected companions are
composed. All authored methods are NOT_RUN; T14/T17/T25 and all 25 complete chains
remain open.

- [x] Author and independently review native Activity V2 (7 paths/12 methods),
  Pressure V2 (5/7) and Steps V2 (6/8) measured-window readers. Selected original
  parent checks preserve valid siblings instead of expanding unrelated recordings.
- [x] Author and independently review imported Activity/Pressure/session Steps V2
  (7 paths/15 methods) and local/imported all-day Steps (7/14). Preserve real count
  windows, original imported receipts, retained gaps and reversed Activity clock
  endpoints without invented times or counts.
- [x] Author and independently review actual shared History dispatcher V2
  (9 paths/10 composed methods). Actual saved Steps fixture now passes its original
  allowed product lane; this is no claim of platform admission.
- [x] Author and independently review shared History resume/local-date and concise
  source Retry presentation V2 (4 paths/5 new,9 carried methods). Every original
  error reason remains in page metadata; one visible Retry per source suffices.
- [x] Author and independently review native radio canonical production V2
  (4 paths). One physical reading is presented once only when original event and
  full saved tuple match. Each original purpose independently qualifies; valid
  background/legacy/earlier rows survive another copy or query failing. The focused
  joined-purpose Room companion is still being authored.
- [ ] Freeze and independently review the canonical radio test companion; compose
  all exact source overlays and the actual Timeline/Calendar callers together.
- [ ] Finish optional session labels under original organization interval/import
  membership. Native adapter labels must obey edited closed intervals. The question
  about showing both names for overlapping sessions is pending.
- [ ] Finish Calendar month presence using the same measured source readers.
  The question about simple all-source data dots versus retained distance shading
  is pending. No new calendar store or raw-key authority is selected.
- [ ] Finish shared Location numerical consumer V2 review and approved remaining
  behavior. Moving-average speed and installed-widget refresh questions are pending;
  attribution, day/imported/multi-run/effective-correction readers remain open.

Exact source/review packets are in `../2026-10-08/`. Whole-recording APIs, strict
Steps totals/rewards, current callback admission and deletion authority are separate
and remain unchanged by measured leaf selection. No Gradle/compile/test/lint/schema,
provider/device/battery/CI or dev/v10 integration has begun.

### October 8 - shared saved Location consumer checkpoint

- [x] Select the independently accepted V2 Tracker/Dashboard, notification and active-Glance consumer wiring. All use the existing shared source-qualified saved Location values for the exact active segment, with current source participation and teardown guards; no raw Location fallback, extra Room observer, sensor or database copy.
- [x] Author 23 helper/Compose/widget/generator/component methods, all NOT_RUN. Exact source and final static acceptance: `../2026-10-08/LIVE_SAVED_LOCATION_CONSUMERS_SELECTED/` and `LIVE_SAVED_LOCATION_CONSUMERS_REVIEW/V2_ACCEPTANCE.md`.
- [ ] Finish moving-average speed, installed-widget delivery outside active composition, native mode attribution, all-day/released/imported/logical totals and effective corrections. Pending questions and limitations remain explicit; T17/T25/all25 full chains stay OPEN.

IMPLEMENTED_UNVALIDATED. No compile, test, schema, lint, device/provider, battery, CI or dev/v10 integration.

### October 8 - selected six-source measured History foundation

- [x] Compose and select the freshly reviewed measured browsing foundation: actual
  six-source native/background/imported/legacy selectors, strict keyset frontier,
  independent source failures and concise Retry, Timeline and Calendar selected-day
  callers, local measured dates and pending empty pages. Native names follow edited
  intervals. Valid joined radio copies appear once; each purpose qualifies separately.
- [x] Include released count-only Wi-Fi observations without invented AP linkage,
  preserve committed finite/live Location summary wiring, and select surviving
  Location purposes after exact original erase checks. Older proof gaps stay honest
  Unknown without reviving erased native copies or dropping retained siblings.
- [ ] Finish retrospective/radio/imported organization names, Calendar month presence,
  numerical consumers/corrections, automatic setup and remaining source chains.

Exact source/review: `../2026-10-08/UNIFIED_HISTORY_FULL_COMPOSITION_SELECTED/` and
`UNIFIED_HISTORY_FULL_COMPOSITION_REVIEW/`. Every authored test is NOT_RUN.
IMPLEMENTED_UNVALIDATED. T13/T14/T17/T25 and all 25 full-chain prerequisites remain
open. No execution gate, provider activation, dev/v10 integration or push.

### October 8 - trip annotation cleanup

- [x] Select the existing exact segment-and-label deletion transaction with independent group preservation and rollback. Three actual Room fixture methods NOT_RUN; full logical/source erase remains open.

Exact source and independent acceptance: `../2026-10-08/TRIP_ANNOTATION_CLEANUP_SELECTED/` and `TRIP_ANNOTATION_CLEANUP_REVIEW/`. IMPLEMENTED_UNVALIDATED; T25/all25 remain OPEN. No gates, activation or dev/v10 integration.

### October 8 - daily summary cancellation

- [x] Preserve coroutine cancellation in the existing daily summary read; ordinary storage-error handling stays intact. One focused host fixture method NOT_RUN; all-day numerical composition remains open.

Exact source and independent acceptance: `../2026-10-08/DAILY_SUMMARY_CANCELLATION_SELECTED/` and `DAILY_SUMMARY_CANCELLATION_REVIEW/`. IMPLEMENTED_UNVALIDATED; T25/all25 remain OPEN. No gates, activation or dev/v10 integration.

### October 8 - passive location delivery chunks

- [x] Remove the arbitrary total64 passive Location cap. Normalize the complete delivery once, preserve one-fix identities, and use cancellable64 work chunks within the unchanged receiver deadline. Seven new and one revised fixture methods NOT_RUN; retained delivery gaps and actual provider/device evidence remain open.

Exact source and independent acceptance: `../2026-10-08/PASSIVE_LOCATION_DELIVERY_CHUNKS_SELECTED/` and `PASSIVE_LOCATION_DELIVERY_CHUNKS_REVIEW/`. IMPLEMENTED_UNVALIDATED; T25/all25 remain OPEN. No gates, activation or dev/v10 integration.

### October 8 - active session widget removal

- [x] Remove the requested Active Session launcher widget, receiver, provider XMLs, preview, exclusive localized strings/helpers/tests, scheduler call and unused History entry-point dependency. One packaged-manifest/resource case authored NOT_RUN; two other existing widgets retain their registrations and updater.

Exact source/review: `../2026-10-08/ACTIVE_SESSION_WIDGET_REMOVAL_SELECTED/` and `ACTIVE_SESSION_WIDGET_REMOVAL_REVIEW/`. IMPLEMENTED_UNVALIDATED; T25/all25 OPEN; no gates, activation or integration.
