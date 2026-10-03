# Decision status — 2026-10-03

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** These are user decisions and
source-work boundaries, not an assembly freeze, executed verification or integration.

## Answers received today

- [x] Reuse existing temporary private export storage for Wi-Fi/Cell: build one
  coherent file inside the existing Room snapshot, release the database transaction,
  then copy to the chosen destination. Keep the existing roughly 64 MB file ceiling
  and remove the owned temporary file after success, failure or cancellation.
- [x] Save one small native Wi-Fi format-version value with new scans so a missing
  new network list is distinguishable from an older summary. Keep older summaries
  usable; add no signature or restriction to user-edited backups.
- [x] The user's released `master` reference meant current `main`. Only work on
  that branch was ever released; candidate schema versions are not released baselines.
- [x] Longest trip for a month/year counts complete measured trips by start date;
  explain this scope when presenting the metric.
- [x] Discovery day/week streaks count only consecutive qualifying periods inside
  the selected month/year. All-time retains the complete streak.
- [x] Seasonal exploration counts actual visits and revisits during the selected
  period, including places first discovered earlier.
- [x] Group observations collected without a session by the local calendar day
  shown in History. Put each Wi-Fi/Cell reading on its own day across midnight.
- [x] Offer Export session on session detail, initially select every available
  saved source, allow exclusions and write one bundle with the name/activity label.
- [x] Show available Wi-Fi/Cell map points without an extra missing-location notice.
  Every reading remains available in History and its chosen export.
- [x] Leave the exploration period statistics that need the proposed dated
  visit/revisit lookup unavailable; do not add that lookup.
- [x] Disable exports until the database opens. Do not add separate blocked-startup
  export admission for preserved recovery files.
- [x] Persist unfinished external-file notices in the existing local export storage,
  retry only the named file and retain the notice through Clear all when the external
  file may remain. If access is lost, retain the notice with Files-app guidance.
- [x] Use one shared exact-session preview/write entry point, reusing the existing
  bundle writer, selected saved records, current name/activity label and source
  choices. Stop when those inputs change; never start tracking.
- [x] Calculate complete longest-trip distance from saved Location points using
  existing continuity/gap rules. Count trips by their start date and react to saved
  point changes; do not invent distance when there is insufficient Location data.
- [x] Show the existing seven-day Steps summary independently of session count
  whenever Steps are chosen or those days have saved Steps data, preserving qualified
  numbers, missing-data states, History and the session list.
- [x] Review Location replacement storage before implementation. The earlier
  sidecar representation proposal has not been approved. The reviewed one-current-edit
  proposal has now been presented for confirmation; no storage change is selected.

The first three answers are from `call_ITXXwd9m4f7gfXfR7bLb0JWu`; the three
statistics answers are from `call_lhtt5iSaT7WZD235VbsXbezt`. No default selection
or elapsed time was treated as an answer.
The two History/session-export answers are from `call_Aos5FHEL3IZBUbkrt10xb0Kx`.
Later answers are from that call's map item and `call_wKkRs4v70TaN0YwFnvQZ750M`,
`call_pHlag80xVfm35psnu6047m01`, `call_0QIayTeufmOkDt0Cj6b9cjuD`,
`call_ZSBQi9hx5Iml5TqzWB4P92lE`, `call_zw5CZYOUw1hONJelyKXDOPlA` and
`call_KtiUwkicoAfgCVbeQP2Y2uHu`. `call_Yl5o9bTE6RzsKHyl4DkenqSN` requests a
single unified, user-understandable time model; it does not approve daily storage groups.

## Questions presented, awaiting answers

- [ ] Store overlapping imported readings once, with a small lookup preserving
  membership in each file, including exact duplicate Steps file membership.
- [ ] Extend the chosen JSON file to all six sources and optional organization
  metadata, with honest absent metrics, all-day data and older-file compatibility.
- [ ] Include older radio details with Unknown origin in the chosen source file.
- [ ] In scoped Activity/Pressure History, show exact matches once and expose
  conflicting values without an unrelated global-history dependency.
- [ ] Keep small durable background Location gap records with affected times and
  reasons, without invented coordinates.
- [ ] Restart the app once after confirmed data-only restore so all readers use
  the replaced databases; preserve settings, stopped tracking and the safety copy.
- [ ] Review one consistent observation-time rule for History/export across all
  sources and midnight. The user requested a unified design instead of approving
  separate daily radio storage groups; preserve saved identities while tracing it.
- [ ] Approve the presented unified date/session export proposal: actual selected
  measurements with optional whole matching sessions, replacing the earlier radio
  Off exclusion rule only after an answer. Do not broaden geographic or exact
  Location file scope while this proposal is pending.
- [ ] Present the reviewed Location replacement representation for confirmation.
  One import-wide Keep/Replace choice is approved; storage remains pending review.

## Stale questions removed from the blocking list

Existing approvals already cover valid historical background radio groups,
per-reading source/session/purpose admission, one Keep/Replace choice for Location
conflicts, data-only backups, confirmed replacement and stopped restore, cancellation
and draining before clear, private export deletion, later unfinished-file notices,
timestamp-based Activity/Pressure measurements, retrospective measurement subsets,
all-day Steps and its supported hourly/enable/open copies, independent shared hosts,
End versus Off, optional Location enrichment and its source-owned reader, and
preserving older data without invented provenance.

Delivery/recovery wiring, source qualification, exact selector propagation and
ordinary retained reader implementation are execution of those approved behaviors.
Do not ask the same behavior questions again. A genuinely new storage or public
behavior choice still needs a concrete proposal under AGENTS.md.

## Current implementation activity

- [x] Author and independently review the approved radio temporary-file slice;
  complete held exporter definition selection remains open.
- [x] Implement and independently review the native Wi-Fi format marker core;
  commit `c7cc6a7340`; mixed file-export owner/detail deltas remain held separately.
- [x] Author and independently review source-time/gap export discovery for imported
  Wi-Fi/Cell, preserving separate History queries and the source's exact cursor.
  Commit `5f6fc0e559` selects twelve tracked diffs and two narrow Cell reader/test additions; full
  exporter definitions and their dependent scenarios remain held separately.
- [x] Select the reviewed displayed-day History contract and three authored
  half-open-day/unchanged-metadata/unknown-summary scenarios in `1e58017f39`.
  Full readers, Room composition and UI remain separately held.
- [x] Independently review H1/H2/H3 and select the retained Location qualifier
  core with seven standalone Room scenarios. Erase uses exact existing identity;
  optional context limits/errors retain it and actual read failures stay typed.
  Commit `6c192245b8` excludes composed held History scenarios; none ran.
- [ ] Select the complete displayed-day History readers, composed cases and UI.
  Their statically reviewed H1/H2/H3 fixes remain preserved with full held R11
  files; source-midnight storage and exact archive selectors remain separate gaps.
- [ ] Trace and implement period metrics through real dated source data, including
  missing evidence and correction/deletion behavior; never substitute All-time.
- [x] Select the source-reviewed bounded daily/weekly discovery streak backend:
  exact selected timestamps, captured local/locale calendar, typed failure and
  cancellation. Twenty-seven newly authored cases are unexecuted. Complete metrics,
  discovery corrections/fanout, weekly All-time production and UI are still open.
  Commit `31d5340b0c`.
- [ ] Update source-selection receipts and checklists after each coherent commit.
- [x] Select independently reviewed ordinary-transfer cancellation and physical
  draining through the existing startup boundary, including GPX/map-image shares,
  scheduled enqueue fences and bounded request-folder cleanup. The final packet
  selects 29 source paths and 40 newly authored, unexecuted cases. Creator-thread
  renderer cleanup, concurrent same-name share ownership and Off during retained
  export findings are statically closed in `TRANSFER_CLEAR_FRESH_REVIEW_1003.md`.
  Blocked-startup recovery exports and persisted later unfinished-file notices
  remain separate required gaps; this milestone does not close full privacy cleanup.
- [x] Select the independently reviewed saved Location backup query and coherent
  private staging using the existing spool. Lost historical purposes do not hide
  saved points; labels remain descriptive. Eight newly authored Room/stream cases
  and the existing expanded saved-origin case are unexecuted. Exact selection:
  `LOCATION_FILE_EXPORT_SELECTION.md`, commit `20c9ac0e64`.
  Replace remains open.
- [x] Select the saved Location export choice, one localized description/confirmation
  copy set for all 28 existing app locales, preserved public no-argument constructor
  and single-file Share without unrelated trip metrics. Three authored scenarios
  are unexecuted. Exact selection and fresh review are recorded in
  `LOCATION_EXPORT_UI_SELECTION.md` and `LOCATION_EXPORT_UI_FRESH_REVIEW_1003.md`;
  commit `3291c4b15d`. Held radio UI and complete session-bundle work remain excluded.
- [x] Connect the existing qualified Steps summary and History/Summary actions when
  no session exists, including session-paging loading/error and delayed chart states.
  Preserve saved Off history and withhold numbers for unqualified Steps coverage.
  Four selected source paths and ten new unexecuted cases are recorded in
  `ALL_DAY_STEPS_STATS_FILES.json`, `ALL_DAY_STEPS_STATS_SOURCE_BOUNDARY.md` and
  `ALL_DAY_STEPS_STATS_FRESH_REVIEW_1003.md`. Dense-list Steps visibility and whole
  source-aware Statistics remain separate work. Commit `e833d2e773`.
- [x] Preserve the existing reflected no-argument constructors of eight other
  registered exporters with AndroidX `Keep`, without changing behavior or dependencies.
  Nine actual-registry reconstruction cases are authored, unexecuted and independently
  statically reviewed. `EXPORTER_REFLECTION_FILES.json` pins the narrow selection;
  mixed Database backup, radio registration and exact session export remain excluded.
  Commit `16458b8a59`.
- [x] Repair temporary My location/game requests while the existing shared owner
  has an exactly known disabled background plan. Six new scenarios and the two
  source paths are pinned in `UI_LOCATION_PAUSED_FILES.json`; independent static
  review found no remaining defect. Unknown/cold/revised/foreign/session demand
  guards, freshness, permissions and no-recording temporary ownership stay intact.
- [x] Expire accepted automatic-start CONTROL records at 48 hours once their exact
  session is terminal, using existing bounded maintenance and the same terminal
  predicate as the repository. `CONTROL_ACCEPTED_EXPIRY_FILES.json` pins three
  paths and three new real repository/Room cases; none ran. Active-session records
  and saved session/manifest/intent data remain; missing-session recovery is separate.
- [x] Show qualified weekly Steps directly below Statistics actions at every session
  count when selected or genuinely saved. Six new Compose cases and one removed
  obsolete case are pinned in `DENSE_STEPS_STATS_FILES.json`; independent static
  review rechecked placement before the calendar/session list. No tests ran.
- [x] Author and independently review complete longest-trip distance from saved
  Location points, with original-start period selection, exact membership,
  continuity/gaps, retention and current import epoch. Eleven selected paths and
  fourteen Room, four pure and five provider scenarios are pinned in
  `LONGEST_TRIP_SELECTED/LONGEST_TRIP_FILES.json`. The existing streak test fixture
  receives the reader dependency. Declined dated seasonal statistics stay
  unavailable for a period. Metric presentation remains open; no tests ran.

The approved displayed-day History is being authored. The isolated imported-radio
export-date candidate slice is source-reviewed for narrow selection. Neither
selects the complete held History or exporter definitions. Global imported-radio ownership currently uses a capped
native census; exact selected-owner lookup remains a separate required gap. Do
not introduce a hash scan/function or new reverse lookup merely to bypass its cap.

The radio staging slice now has an independent static review receipt at
`../2026-09-30/RADIO_EXPORT_SPOOL_FRESH_REVIEW_1003.md`; its complete exporter
definitions are still held with their other unfinished dependencies. The current
exploration trace is `PERIOD_EXPLORATION_SOURCE_TRACE.md`. A narrow held Wi-Fi
export continuation repair now uses the source reader's actual newest-member
cursor; its authored scenario exports 101 entries across the real file/Room/codec
boundary with explicitly supplied source pages. This is not executed evidence.

No tests, compilation, schema generation, device/provider work or gates ran for
this decision checkpoint. All 25 full-chain prerequisites remain open.
