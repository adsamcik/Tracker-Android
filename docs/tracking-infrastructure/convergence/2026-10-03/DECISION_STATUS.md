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

- [ ] Implement and independently review the approved radio temporary-file slice.
- [ ] Implement and independently review the approved Wi-Fi format marker slice.
- [ ] Trace and implement period metrics through real dated source data, including
  missing evidence and correction/deletion behavior; never substitute All-time.
- [ ] Update source-selection receipts and checklists after each coherent commit.

No tests, compilation, schema generation, device/provider work or gates ran for
this decision checkpoint. All 25 full-chain prerequisites remain open.
