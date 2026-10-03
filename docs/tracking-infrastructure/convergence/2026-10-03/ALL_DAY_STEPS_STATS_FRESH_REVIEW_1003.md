# All-day Steps Statistics: independent static source review

Date: 2026-10-03. Status: **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED**.

Original task messages, corrections and approvals were inherited, including one Steps choice for all-day collection, useful daily statistics without sessions, retained history after Off, honest missing/partial/unavailable values, and simple source-independent surfaces. This is review-only source evidence. No production edits, Git operations, recursive delegation, compilation, tests, lint, schema, device, provider or integration gates were performed.

## Exact reviewed selection

`ALL_DAY_STEPS_STATS_FILES.json` SHA-256: `ece4e94b0f5d67fc038c9dceea932bcfd0c8a867270c9b43ae1af2ef7e40f386`.

`ALL_DAY_STEPS_STATS_SOURCE_BOUNDARY.md` SHA-256: `5454b424d4d97cfc5b3a378a9c1f2326077c82543b50a004637cf237505ff171`.

All four actual sources match their frozen after-snapshots and manifest pins. All four before-snapshot hashes also match the manifest. The full owned deltas were inspected; Czech changes only `stats_no_tracker_sessions`.

| Source | Actual / after SHA-256 |
|---|---|
| StatsRoute.kt | `ec3dcd580daa2b4767f99dbef1171bb5e4c28f5f032ae3936d266740a013c8fa` |
| StatsScreen.kt | `3ab9fb03dd24969d1d23bc13c40efcadbc9b3326bf25cba5cfef99fccd56f3f0` |
| StatsScreenComposeTest.kt | `ee03b05f82e34e0384fdc387db7fd8987c5f0a1f95e054baa4f68a4dff159709` |
| values-cs-rCZ/strings.xml | `7d6ab0995b19156ae5fb1fd05ec02b7d5b03b809b0a3ce1679508e603b7f0204` |

## Findings and static closure

1. **Saved Steps hidden by empty session paging:** `StatsRoute.kt:113` now subscribes during Empty, Loading and Error as well as the existing sparse Content states. `StatsScreen.kt:179` always renders the existing content container; HeaderActions remain reachable, and the qualified card does not demand a session. Placeholder trips are restricted to the existing Content-only test fallback.
2. **Session loading/failure suppressing another source:** `StatsScreen.kt:363` renders the existing session loading/error/Retry items alongside the card. Session-specific subtitle and distance are omitted when session paging has no successful nonempty list. This does not turn a session-read failure into a Steps-read failure or a measured zero.
3. **Unsupported numbers:** `StatsScreen.kt:693` uses original Ready totals for exact numeric values. Materializing, partial, calendar/evidence/storage failure and not-captured outcomes remain nonnumeric through existing History strings. Active days are withheld when the sessionless Steps summary cannot prove them. A Ready zero remains a genuine covered zero. The existing Room reader separately returns NOT_CAPTURED when all requested days lack authority and PARTIAL_CAPTURE for mixed coverage; it never supplies a Ready zero for missing data.
4. **Steps arriving before structural session bars:** `StatsScreen.kt:617` creates presentation rows only for days actually present in Ready, then reuses a structural row with the same epochDay when available. The original epoch-day-to-Long Steps map remains the numeric authority; zero-valued presentation fields grant no Steps or Location qualification. Active-day evaluation receives those complete presentation days, so an empty structural flow cannot temporarily suppress positive Steps days or double-count them when it later emits. There is no Int conversion or visual-intensity conversion of Steps counts; existing `Long.formatReadable()` formats the Long value directly.
5. **Misleading Czech absence claim:** the single selected resource now says `Zatím žádné relace`, matching the existing no-session meaning. The Empty prompt makes no claim that all six sources or all older history are absent; History stays reachable even where that existing prompt is retained.

No remaining concrete defect was found in this bounded selection. It reuses the existing card, actions, calendar labels, reader and state strings. It adds no storage, provider demand, settings branch, new card or generic framework.

The Steps flow remains a read of retained history, not an activation of current collection. `StatsPresenterViewModel.kt:128` requests one bounded seven-local-day window, uses `WhileSubscribed(0, replayExpiration = 0)` and resets to Materializing after cancellation. Gating that retained reader only on the current Steps toggle would hide the approved Off history. Dense successful session lists retain the existing sparse-card visibility policy; History/Summary actions remain available.

## Authored evidence and limits

Read all ten new scenarios and the adjusted visibility case. The new helper uses actual empty PagingData, the production visibility/collection helper and the real StatsScreen. Assertions inspect exact counts, day semantics, missing distance/Start prompts, working Summary/History/Retry callbacks, state invalidation, placeholder exclusion, Czech text and Steps-before-structural replacement. Existing collector subscription/cancellation and sparse qualified-zero/partial/materializing cases remain.

These are **authored, unexecuted** Compose/Robolectric cases with a controlled Steps StateFlow. They do not prove real provider acquisition, Room qualification, Hilt, device rendering, accessibility, battery or migration behavior. The underlying all-day producer chain was traced as a dependency and was not revalidated by these consumer fixtures. No passing result, completed full-chain prerequisite, assembly completion or `dev/v10` readiness is claimed. All 25 full-chain prerequisites remain open pending the frozen convergence input and required final gates.
