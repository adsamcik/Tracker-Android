# Independent review — Statistics local date picker

Status: **ACCEPTED for this narrow source selection, IMPLEMENTED_UNVALIDATED.**

## Exact input

Independent reviewer: `/root/fresh_radio_schema_review_1004`. Full inherited user requests and approved decisions, current AGENTS.md, exact BASE/SELECTED source/test bytes, diff, existing route/view-model call sites and module dependencies were reviewed. No recursive delegation.

BASE: `c315f7dc1c649a54c575485bd8de18e16a5fa162`.

| Input | Verified SHA-256 |
| --- | --- |
| FILES.json | 5db0ee340514f83cc7fa4f040b7b17bc9885601ffb0b41d58497071f74374131 |
| SELECTION.patch | b8722984710f4eb73fb74d967152ed2b3f460ed0899af5aa6f4cdb8e802379cb |
| StatsDateRangeDialog.kt BASE | 0e099fced2ac6868782da953d311ff6faea08f7799eb61e008dcdbb4ca02e5fb |
| StatsDateRangeDialog.kt SELECTED | dc1b10b6b8c9c84710c83340dd4907a8a3bab83e85fd4d2d8dd7b79baf58dc50 |
| StatsDateRangeDialogComposeTest.kt BASE | 9e8e15db576004b16c80726c080870aaf55f5e98cabe172fe03b457a9d0173d0 |
| StatsDateRangeDialogComposeTest.kt SELECTED | 932e0b7728c06d956c936044539cddaf07c68848bd40d106c192d684e8c98113 |

Paths are the two feature/statistics production/test paths listed in FILES.json. All pins match. The selected diff is confined to those files.

## Source trace

No concrete blocker found in this approved local-calendar repair:

- Initial saved UTC instants are resolved into their local calendar dates, then encoded as UTC-midnight date tokens solely for Material's picker (130–132, 160–163). The same conversion is used for preset choices (241–244).
- Picker tokens are decoded in UTC for the visible date caption (179–185), avoiding a second local time-zone shift.
- Confirmation converts each selected calendar date to its actual local start, and the end to one millisecond before the next local day's start (134–137, 270–275). It uses calendar-day transitions rather than a fixed 24-hour addition, preserving 23-hour/25-hour days and the caller's existing inclusive end convention.
- StatsRoute.kt:118–129 passes existing DateFilter.startMs/endMs directly to the dialog and sends the confirmed range to setDateRange; clear and dismiss behavior remains intact.
- Preset selection is derived from the picker state (166–175), so manual calendar changes cannot leave a stale All time or preset flag. All time clears both picker tokens (236–237); a following start-only selection disables confirmation, and a complete selection confirms the chosen dates.
- The display zone and reference time are captured when this dialog opens, following its existing convention. No data timestamps, settings, storage, metric contract, dependency or new UI/copy were introduced. Two small conversion helpers reuse java.time and the existing preset calculator; no date framework or alternate flow was added.

## Test-source review

Seven new actual Compose cases are authored and unexecuted:

- East/west UTC custom range round trips through the real dialog/callback, also checking the date caption.
- Prague's complete spring 23-hour day and autumn 25-hour day, with caption and confirmed bounds.
- Today east of UTC and Last month west of UTC through actual preset chips.
- All time followed by actual calendar day nodes 17 and 18 in the initially displayed June2026 grid, confirming June17–18 local bounds without calling clear.

TimeZone changes are restored by @After. The tests exercise the real picker bridge rather than testing only the new helpers. Preset expectations reuse the existing separately covered calculator; these cases prove no metric execution. Existing clear/cancel/dialog scenarios are preserved.

## Phase and disposition

Static acceptance is limited to these two frozen paths. No production/test-source edits, Git mutation, compilation, test execution, lint, Room schema/drift/diff checks, CI, UI evaluator, device/battery work, provider activation or integration was performed. Authored tests are not passing evidence.

All 25 complete prerequisite chains, product period-metric wiring, complete History and final convergence remain open. This receipt is not assembly completion or readiness to integrate into dev/v10.
