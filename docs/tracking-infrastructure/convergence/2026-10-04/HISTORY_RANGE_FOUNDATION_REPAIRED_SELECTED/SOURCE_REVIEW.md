# Independent re-review — History H1 fixture repair

Status: **ACCEPTED for the repaired bounded source prerequisite, IMPLEMENTED_UNVALIDATED.**

Reviewer: `/root/fresh_radio_schema_review_1004`. This is a read-only recheck of H1 against the original inherited user messages/approvals, current AGENTS.md, original frozen source review and exact repaired snapshots. No recursive delegation.

## Verified input

BASE remains `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`.

| Artifact | SHA-256 |
| --- | --- |
| Repaired FILES.json | 5835f47c68f8c6151b90f5af810089d6b18f8a7f18a0fac1b7d8ff44ad88623a |
| Repaired SELECTION.patch | 7454f4f4030b56660e820687faddd2b4f2e1fddbf6402ff937f2123c10787e76 |
| REPAIR.patch | f81f931c161601f7c9d5392abd409be4e4c49ad675367f995538a795b65eda6f |
| Repaired SOURCE_BOUNDARY.md | 1ecf660cfbbf6846b5b858bf91e734fe8f645b574439c182e42f7fc88dae2125 |
| UnifiedHistoryRangeReaderRoomTest.kt SELECTED | 995da9d9ff50f317e62634c6e2fc13ba45fd4ab183d70ef775ef0f5950b48530 |
| Original rejected SOURCE_REVIEW.md | 6474441a920acc632bfcafcb967a1142abe09f24d82e84cbc1d7a5e3063488bb |

All 30 repaired SELECTED hashes match the new manifest. Twenty-nine selected files, including all 23 production files, are byte-identical to the original reviewed packet. The sole changed test file is exactly the original bytes with the eight specified imported=false arguments inserted; direct text reconstruction compares equal. No other source/test drift is present.

Original BASE/dependency pins and the committed common UnifiedHistory contract were verified in the original review and are retained as that prerequisite. The original rejected packet and review remain intact.

## H1 resolved

Both intended legacy interval scenarios now mark their four saved Location points imported=false:

- UnifiedHistoryRangeReaderRoomTest.kt:215–218 — retrospective narrow annotation.
- UnifiedHistoryRangeReaderRoomTest.kt:253–254 — linked recording interval.

These points now have LEGACY descriptive origin and are not included in importedHistorySampleIds. The unchanged source reader can include them in the legacy recording while selecting exact interval times 1100 and 1499, excluding 1099 and 1500. Original stored Location points and the whole 100 Steps count remain unchanged; the crossing interval withholds that whole count with TIME_UNCERTAIN, while the complete interval can retain it.

The actual imported-point exclusion rule remains intact, so an unrelated file point still cannot join a legacy session merely because its time overlaps.

No remaining concrete blocker was found in this narrow repair. The original production/source trace and scoped acceptance carry forward unchanged.

## Evidence limits

No tests, Gradle, compilation, lint, schema/Room checks, diff --check, CI, Git mutation, UI/device/battery work or provider activation were performed. The 35 authored/revised scenarios remain unexecuted. The original “hidden or narrowed” title still covers a narrowed interval in its body; do not treat that as executed hidden-case evidence.

Main History UI/calendar/timeline/details/actions, Page.problems presentation, full source subsets, crossing whole-source presentation, exact arbitrary-window Steps, alias memberships and Location Replace remain open as documented in the original boundary. All 25 full-chain prerequisites remain pending. This acceptance does not declare assembly complete or ready for dev/v10 integration.
