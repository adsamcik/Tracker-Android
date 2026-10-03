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

The first three answers are from `call_ITXXwd9m4f7gfXfR7bLb0JWu`; the three
statistics answers are from `call_lhtt5iSaT7WZD235VbsXbezt`. No default selection
or elapsed time was treated as an answer.
The two History/session-export answers are from `call_Aos5FHEL3IZBUbkrt10xb0Kx`.

## Questions presented, awaiting answers

- [ ] Show available radio map points with the concise partial-location notice.
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
- [ ] Add a small dated Location-to-exploration lookup for accurate period visits
  and revisits, rebuilt from still-saved points and updated on correction/deletion.
- [ ] Save newly delivered cross-midnight radio observations as separate daily
  groups with stable per-reading details; preserve older saved identities.
- [ ] Keep the existing preserved recovery-file Export actions usable while
  database startup is blocked, with separate cancellation/draining admission;
  never open the damaged database or start tracking for this export.
- [ ] Persist unfinished external-file notices in the existing export-plan store;
  retry removal of only the named file, keep the notice if access is gone and
  preserve it through collected-data clear. The user-facing notice itself was
  already approved; this storage/recovery detail was presented separately.
- [ ] Use one shared stateless exact-session preview/write entry point, following
  the existing consumer-owned export direction. Reuse the current bundle writer
  and capture metadata/source revisions; never replace membership with dates.
  Export session product behavior is approved; this new code interface is pending.
- [ ] Allow the longest-trip reader to calculate complete distance from exact saved
  Location members with existing continuity/gap rules. The period-by-start behavior
  is approved; changing the aggregate-only reader contract is pending.
- [ ] Store a user-imported Location replacement beside the existing saved identity;
  keep one visible point, apply its imported values across saved-data consumers and
  preserve the original phone reading internally for provider replay. The bulk
  Keep/Replace choice is approved; this storage representation is pending.
- [ ] Show the existing seven-day Steps summary near the top independently of
  session count, for chosen Steps or saved data, with qualified per-day values
  and missing-data states. The new layout was presented through the questions tool.

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
  source-aware Statistics remain separate work.

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
