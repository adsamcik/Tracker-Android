# Steps with a populated session list — independent source review

2026-10-03. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewed the exact three-path continuation over the previously committed `e833d2e773` selection, original source-independence requirements and the approved existing seven-day summary at every session count. No production/Git changes or validation were performed.

## Result and source trace

No concrete defect remains in the selected delta. `StatsRoute.kt:53–63,87–90` observes the existing authoritative Steps choice and subscribes to the same retained weekly stream independently of session paging. The Hilt entry-point pattern and `:data:preferences` dependency already exist in this application; no factory, provider demand, storage, calendar or dependency was added. Application composition supplies `AuthoritativeTrackingParamsRepository`, whose policy stream preserves cancellation and retries read failures with fail-closed choices. A false initial choice does not invent consent.

`StatsScreen.kt:306–317,426–433` reuses one existing footer: selected Steps displays its typed state at any session count; Ready/PARTIAL_CAPTURE keeps retained information visible when Steps is off. Materializing or generic storage/calendar/source-evidence failure alone does not claim saved Steps. The dense footer receives zero sparse-session count solely to omit the existing sparse-list subtitle and unsupported distance. Existing sessions, paging states and History/Summary actions remain reachable.

The unchanged card consumes the original Ready `Long` values and epoch days (`StatsScreen.kt:625–646,698–715,730–735,830–859`). Missing days are not manufactured; real covered zero remains zero; incomplete/unavailable results remain nonnumeric. The unchanged presenter requests seven local days and uses `WhileSubscribed(0, replayExpirationMillis=0)` (`StatsPresenterViewModel.kt:126–147`). Its Room repository observes retained dependency invalidations, performs a coherent read on the injected IO dispatcher and never starts a provider (`RoomStepsNumericSummaryRepository.kt:68–159`). Reading while Steps is off is necessary to discover independently retained history; it does not resume collection.

## Authored evidence and limits

The patch adds exactly **five unexecuted Compose methods**, removes one obsolete predicate-only method, and changes only the existing sessionless fixture's collector invocation; method counts are 27 before and 31 after. New cases use actual LazyPagingItems plus the production collector/card: three sessions with saved Steps off (including a Long above Int.MAX_VALUE and covered zero); selected nonnumeric states; retained incomplete data after Off; no added dense card for Off plus unproven data; and two-to-three session paging with subsequent count updates and a single Steps subscription. Session click, History, omission of dense distance and no false zero are asserted. Existing visibility/lifecycle source remains intact.

The tests use Flow and paging fixtures, not the actual StatsRoute Hilt graph or on-device providers. They have not been compiled or run. Hilt generation, rendering, lifecycle timing, complete backend authority, device/battery behavior and all convergence gates remain open. This receipt accepts only this coherent source slice, not the overall Statistics surface or assembly.

## Exact pins

| Input | SHA-256 |
| --- | --- |
| `DENSE_STEPS_STATS_FILES.json` | `dcae7875c07a408661165e9ba5899c28fb1d71282345a0db7661d7c0ce439c8d` |
| `DENSE_STEPS_STATS_SOURCE_BOUNDARY.md` | `14e5f0350ba2ee4a8dcd5b2f37e755d896b18745ba3978293b9494216b7f5ecd` |
| `DENSE_STEPS_STATS_SELECTION.patch` | `33f9fc3d630df8dc09d1d6a7acef3e05840127d2488315e7958456371543279f` |
| `StatsRoute.kt` | `f8011710b21c99c8513f7caf275ca873fad6b3968076a9785c9a1202a62a46b8` |
| `StatsScreen.kt` | `988c82273c7f5b482361af430f50a6b513fe25d5c688b64cece426ee932a0a80` |
| `StatsScreenComposeTest.kt` | `24c4aa7d45347652ae4f4a0fc272114b400d958cde17a37c4c55111f6ce63d9a` |

## Final placement correction — independent static recheck

2026-10-03. The coordinator identified a missed part of the approved placement: the earlier accepted source still put the chosen/retained Steps card after all sessions. The final source supersedes that footer placement and the earlier five-case/31-method inventory above; those earlier pins describe the preserved review history only.

Rechecked the full three-path selected delta, current source, unchanged card/collector seams, and all six authored additions. No concrete defect remains in this final slice. `StatsScreen.kt:329–348` now emits the one chosen/retained Steps summary immediately after header actions, before the heatmap, filter and session list. The existing footer at `:436–444` requires the complementary `!showWeeklyStepsSummary`, so it cannot duplicate this card. Non-Steps sparse fallback keeps its existing footer. The original qualified Ready Long values, actual covered zero, epoch-day lookup and nonnumeric missing states remain unchanged; neither session arrival nor placement creates a count or provider demand. The retained Steps read remains independent of session count and lifecycle-owned in the unchanged Route delta.

The three-session case now asserts the Steps metric is initially displayed before explicitly scrolling to and clicking the first real session (`StatsScreenComposeTest.kt:698–743`). The additional fifty-session case asserts loaded count 50, History and the actual Steps value in the initial viewport without any preceding scroll (`:746–770`). The four other new state/paging cases remain meaningful and unchanged. There are exactly **six new unexecuted methods**, one obsolete method removed, and 32 current test methods versus 27 in the preserved before source. Existing sessionless and visibility/lifecycle cases remain in the selected source.

This is static source closure only. The fixture does not exercise the production Hilt graph, real retained storage or devices. Compilation, Compose execution, small-screen/font-scale rendering, Hilt generation, lifecycle timing, provider/battery evidence and all convergence gates remain deferred. This acceptance does not declare Statistics or the 25 full chains complete.

### Final exact pins

All six current hashes were read directly and match the frozen manifest/parent handoff.

| Input | SHA-256 |
| --- | --- |
| `DENSE_STEPS_STATS_FILES.json` | `b96a24ed77ba213bd16025025a6a39d6d024b4c2b410c5942d7c774130172cd3` |
| `DENSE_STEPS_STATS_SOURCE_BOUNDARY.md` | `d79115b949793bf6fa8a4b752ac4e9366ae7568701ccc0937c4b6052568c2ef4` |
| `DENSE_STEPS_STATS_SELECTION.patch` | `130ef0b8d06ad65421c251435dcda1d7714171b694afc0b682bd1a5ec96d998f` |
| `StatsRoute.kt` | `f8011710b21c99c8513f7caf275ca873fad6b3968076a9785c9a1202a62a46b8` |
| `StatsScreen.kt` | `df50f5ff1f11fe4ccf46dad183c3d97dad2ded923c087dc8cccbbc6a0baeaf29` |
| `StatsScreenComposeTest.kt` | `ea4b88b4f906336e586b02e93cb1858e2f2e9e0e293ba22256440db24979faa1` |
