# Tracking completion plan

Updated: 2026-10-07. Inspected candidate: `5b19ca833596b602643e99d291c4ceae0d3b5cc6`.

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
  acceptance. These coordination choices await approval. Finish exact one-use
  permission continuation for app/widget/shortcut starts, preserving the original
  source choices and rejecting superseding Start/End/Off or stale process requests.
  Keep Off saved until that exact Start is accepted. Check every external action
  and cleanup acknowledgment; End releases the session, Off stops collection.

- [ ] **T03 — Preserve Steps through quality changes.** Independently review and
  approve the smallest connected continuity design, then implement it through
  caller authority, physical ownership, storage and retained readers. A fixed
  latency alone and an unchecked carried counter baseline are incomplete repairs.
  Cover other-source changes, End/Off, real consent boundaries, reset/reboot,
  retries, exact counts and existing file compatibility. The current Astra
  recording-reference proposal is unapproved, including its schema semantics.

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
  counters to overlapping provider counts. After display approval, stop a future
  midnight cursor from hiding trustworthy saved subtotals; keep independently valid
  days visible and preserve strict game/achievement completeness. Optional first-enable
  permission-prompt synchronization awaits approval; the clicked repair already works.

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
  The remaining lifecycle Location proof still relies on run-only WAL lookup;
  reconcile it with retained authority rather than adding another proof store.

## B. Complete everyday organization and product surfaces

- [ ] **T13 — Finish session organization.** Preserve existing annotation creation,
  editing, removal, names/activity labels and paging. Finish exact six-source contents,
  retrospective membership and relation to unified History. Sessions group observations
  without moving their measured dates or owning daily Steps. Check edits/deletions
  and detail navigation; do not rebuild the existing editor.

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
  trustworthy saved products; the shared saved Location-summary interface awaits
  approval. Finish recovery, current-choice permission repair, concise localized copy,
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
| Existing cancellation/lock-order repair for both Start failures | T02, T09 | Previously asked, unanswered. |
| Smallest Steps recording continuity design and persistent meaning | T03, T18 | Astra proposal; independent design review/approval still required. |
| Recorded-so-far daily Steps with honest missing coverage | T08, T15, T17 | Previously asked, unanswered; strict numeric/game API stays strict. |
| Shared saved Location summary for Tracker/notification/widget values | T17 | Previously asked, unanswered; reuse existing readers, no new collection. |
| Optional two-second first-enable provider-status wait | T08, T17 | Previously asked, unanswered; existing repair button remains usable. |
| Saved network/tower rows in existing radio detail screens | T11, T14, T17 | Concrete scrolling-list presentation needs approval; preserve summaries, current local measurement dates and no arbitrary visible cap. |
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
- [ ] Complete the remaining T19 integration coverage and all full-chain prerequisites.

T15 follow-up inspection found the period metric backends have no production
Summary consumer. Period propagation/rows and precise distance display need a
concrete product proposal before implementation; do not change achievements to
create a caller. This is recorded work, not a new implicit UI approval.
