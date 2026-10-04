# Independent static review — first N4 selection

Date: 2026-10-04. Reviewer: `/root/fresh_radio_schema_review_1004`.
Phase: **IMPLEMENTATION_ONLY**. Status: **IMPLEMENTED_UNVALIDATED**.

Read inherited original requests/approvals, AGENTS.md, preceding independent N1–N4
receipts, exact N4 snapshots/patch and revised lookup companions. No production
edits, Git mutations, compilation, tests, schema/gate or provider/device execution
occurred.

**N4 behavior is repaired, but this first selection is held for N5.** The new
value-free helper delegates to the existing complete bounded protected-owner
reader. Its real Room case keeps a retained native revision and checks a foreign
run claiming its window ID, preserving independent import visibility and the
existing conflict result. The test is authored, unexecuted.

**N5 — the selected snapshot removes existing required methods.** The selected
`DefaultActivityHistoryRepository.kt:979` still calls
`selectBySegmentIdsInTransaction`, but the N4 repair hunk removes that method and
`recentActivityOnlyInTransaction`. Both already exist in committed `640a27da4f`
source, and `TrackingHistorySourceUnionReader` also calls them. They are existing
dependencies, not solely held R11 drafts. Restore their unchanged committed
bodies in the selected snapshot; no new API or architecture is needed.

Initial manifest: `84cb1c58b9a472f65fe8c529011c6109994b69ee78e6d1cfad8c87eada534b7d`.
Cumulative patch: `a755455a64b215315a71c3c2a72b15d91238415f6e2508cda770d0a52b03fe52`.
N4 repair: `0df477235401da668020d1a4f649c24d0465703be9877937d3b183c07e19ab83`.
Boundary: `6a44da7a99aead0dabd1e951076a163f955f21785dabd61ab0c07ce0b54b339b`.
Selected repository: `1e029a15c86dbf682bd52ab63b92331c05e254ccdfe2798be968756626607b30`.

Keep this first freeze and rejection intact. N5 is a separate selection repair;
all 25 assembly chains and deferred gates remain open.
