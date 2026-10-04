# Independent review — History range foundation

Status: **BLOCKED on two authored test fixtures; no additional production blocker found in this bounded prerequisite.**

## Scope and phase

Independent reviewer: `/root/fresh_radio_schema_review_1004`. Reviewed the original inherited user messages and approved decisions, current AGENTS.md, exact BASE/SELECTED snapshots and selection patch, committed ownership/Location dependencies, module wiring and selected test sources. No recursive delegation.

IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED remains in force. No production/test-source edits, Git mutation, compilation, test execution, lint, Room schema generation/drift, CI, device/UI/battery work, provider activation or integration was performed. All 25 full-chain prerequisites remain open.

This review covers the explicitly bounded backend foundation. It does not declare Main History UI, every source subset, calendar/timeline/details/actions, organization export, overlapping-file membership or the complete History feature finished.

## Exact input

BASE: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`.

| Artifact | Verified SHA-256 |
| --- | --- |
| FILES.json | f33099cf7be05bbeecd3b4bffa5379d3dd32fc79cd49716e48f7f1c2029fe960 |
| SELECTION.patch | 9a9ae1292af39560c235fb61cff33fadbb0e9d4b6eb7c57726c8e42ffda2ebe7 |
| SOURCE_BOUNDARY.md | c06622b2ed36cb75ca856addb2b9657771f2e5c90a4bb1953c59dcfd3954ab9e |

All 30 selected file hashes and every non-null BASE hash match FILES.json. All ten committed dependency-only BASE hashes match that manifest. The committed common UnifiedHistory.kt LF-normalized hash matches its extra semantic dependency pin: `482e2732b4bb223a7952e8e6a14fc48ad8785c14c620e88ce1874ab74440435a`.

FILES.json is the exact per-path hash inventory: 23 production paths, six test classes and one shared Room fixture. The test inventory is embedded in FILES.json; there is no separate TEST_SOURCE_INVENTORY.json in this packet. Review uses the selected bytes, not later working-tree edits.

## Concrete finding H1 — legacy interval fixtures create imported Location points

Selected UnifiedHistoryRangeReaderRoomTest.kt contains two cases with invalid Location prerequisites:

1. Lines 212–235, “narrow annotation selects exact Location timestamps and withholds crossing Steps total,” create four points at 215–218 using point(...) without an imported argument. The requested origins at 223 are LEGACY and ORGANIZATION.
2. Lines 250–271, “linked recording interval selects exact Location and withholds its crossing Steps total,” create four points at 253–254 the same way. The requested origins at 259 are LEGACY and ORGANIZATION.

The shared point helper at 329 defaults `imported = true` and persists that value in LocationFileIdentityEntity at 335. The selected production reader correctly prevents time-only imported membership: UnifiedHistoryNativeReader.kt:224–225 loads imported point IDs, and 253–257 excludes those IDs from a legacy recording. Sessionless discovery also labels these points IMPORTED through LocationReadScope.HISTORY_ORIGIN, so it cannot recover them under a LEGACY-only origin filter.

Consequently these cases cannot produce the expected Location sections at 226–227 and 264–265. This is source-traced fixture failure, not an executed test result.

**Minimal repair:** explicitly create those four points with imported=false in each case. Keep the production imported-file exclusion intact. That preserves the intended test of legacy interval selection and avoids falsely assigning imported data to an unrelated session because timestamps overlap. Freeze the repaired test snapshot separately, preserving this rejected packet.

No new behavior, storage or architecture approval is needed for this fixture correction.

## Production trace and accepted narrow behavior

- Range reads run on injected IO in one Room transaction. Current epoch/source-evidence state and active retention settlement are checked; source-local storage/integrity/overflow failures remain typed and preserve an independently valid sibling. Cancellation is rethrown throughout discovery, qualification, organization and PagingSource.
- Discovery uses finite key-only streams with 100 candidates and a true 101st lookahead. mergeHistoryBuffers retains unconsumed frontiers and continuation equality preserves the requested dates/sources/origins/purposes. Native visibility checks probe only exact already-qualified collision owners; there is no new global imported-owner census.
- Existing source-owned Activity requested-key lookup, retained/deleted protected ownership and comparison are retained. Pressure selected imports use bounded selectedHistoryCandidates and existing exact entry/run/window qualification. Wi-Fi/Cell imported comparison keeps existing exact source-owned qualification and suppresses a duplicate only when its qualified local owner is visible in the requested range.
- Native Location payload/receipt work is constrained to the selected measured range before payload budgets. Unknown/older saved points remain visible with legacy/unverifiable coverage; missing optional enrichment proof is not treated as absent Location data. Imported file membership uses canonical IDs rather than coincident timestamps.
- Location and background radio key discovery groups actual measured observations by the requested display-zone day. Sparse pagination skips a whole displayed day, retaining exact boundaries through DST. Radio sections preserve original fact IDs, stored source zone, complete child records and complete gap intervals, with a separate observation display window. Positive-duration gaps use half-open overlap.
- Steps structural aggregates preserve their original stored local day and UTC bounds; they are not allocated into invented partial counts. Exact imported-day organization selectors reuse authenticated retained lineage, original identities, deletion/fence and epoch behavior, excluding neighboring dates.
- Imported captured Steps now retain the original entry header when a physical member is deleted; surviving members must remain inside that original envelope. This is a containment repair, not a new timestamp reconstruction.
- Local retrospective and linked intervals select exact Location times and withhold crossing whole aggregates with TIME_UNCERTAIN rather than prorating totals. A truncated shared contents page adds DEPENDENCY_OVERFLOW even when an unseen older recording might overlap a later annotation.
- Existing annotation, source, child, correction/deletion, retention and Location archive invalidation dependencies are retained. Forward PagingSource refresh restarts the same range; Hilt's existing facade is now one singleton bound to both repository interfaces. Room/Paging/coroutines/Hilt dependencies already exist; no new production dependency or cross-feature module edge was added.
- The selection excludes obsolete Pressure global census/property drafts, unrelated Steps recent/cursor/numeric drafts and Location latestRegistrationPlan. The source bridge follows the committed Activity composed-entry signature and committed Pressure comparator rather than introducing another ownership model.

## Authored coverage and limits

The manifest identifies 34 new plus one revised unexecuted scenario: six displayed-day Location cases, fifteen range/organization/paging cases, eight composed Location/radio day cases, two imported captured Steps cases, three exact imported Steps day/DST cases and one header-containment contract case. All are authored source only. The seven committed candidate/continuation cases remain dependencies and were not counted as new evidence.

The selected tests use actual AppDatabase and the production range/source-owned readers where relevant. Unused capture sources are absent or mocked; the unavailable Wi-Fi and proof cases deliberately inject source failures. H1 must be repaired before this packet can be accepted as a coherent production/test-source slice.

One test title says “hidden or narrowed” (UnifiedHistoryDayGroupingRoomTest.kt:105), but its authored body exercises a narrowed interval, not a hidden interval. Treat that body as narrowed-interval evidence only; it does not establish hidden-case execution or full privacy coverage.

## Explicit remaining work and disposition

The packet honestly leaves Main History's legacy pager integration, calendar/timeline/details/actions, Page.problems presentation, full source filters/subsets, Activity/Pressure/captured-radio measurement-only presentation, arbitrary-window exact Steps, gap-only Steps discovery, background radio overlapping-file aliases, native Steps duplicate-file membership and Location Replace representation open. PagingSource rows do not carry Page.problems; the UI must address that before final delivery. These remain separate assembly work, not silently completed by this backend selection.

Repair H1 in a separate exact test-source companion and review its composition. Preserve this receipt and frozen first attempt. No whole-History, migration-gate, provider, device, integration or release success is claimed.
