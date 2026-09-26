# Whole Tracker consequences review

Date: 2026-09-26. Planning and static inspection only, inside **IMPLEMENTATION_ONLY**. No production/test edits, build, test execution, schema generation, UI/device/battery evidence, activation, push or integration. This report proposes changes; none of its new choices is approved by being written here.

## 1. Scope, evidence and conclusion

The user's whole-product follow-up is preserved verbatim in `POLISH_REVIEW_CONTEXT.md` and `TASK_CONTEXT.md` §10. This report extends the first-pass implementation plan across existing consumers. It does not propose a new game, achievement system, navigation system, dwell-time engine, backup restore platform, or generalized acquisition architecture.

Source anchors below refer to clean main `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`, unless explicitly identified as a peer finding. Paths are repository-relative and line numbers are one-based. First-pass candidate/draft distinctions remain authoritative in `CURRENT_CODE_INVENTORY.json`, `OTHER_RELEVANT_REFS.json` and `IMPLEMENTATION_PLAN.md`; this review does not promote those drafts. The planning checkpoint was `d6a813e41e`. Static code proves the listed branches and assumptions, not device behavior or visual polish.

**Conclusion:** source independence affects entry points, game Location reuse, automatic permission prompts, rewards and summaries, widgets, map descriptions, and deletion/export explanations. These need a small number of shared-contract repairs. Most existing settings, local resources and qualified Steps safeguards should remain.

## 2. Concrete findings and bounded dispositions

### W1 — An active tracker service is not a usable Location feed

`tracker/engine/src/main/java/com/adsamcik/tracker/tracker/feed/DefaultTrackerLiveLocationFeed.kt:39` exposes `controller.isServiceRunningFlow` as `isActiveFlow`; its data path at :45 reads nullable legacy `collectionDataFlow.location`. `feature/game/src/main/java/com/adsamcik/tracker/game/minigame/location/LiveOrFusedMiniGameLocationSource.kt:49` chooses that feed whenever active and uses the standalone provider only otherwise. A Steps/Wi-Fi/Cell-only session can therefore suppress the game's actual Location source without providing eligible fixes.

**Proposed repair:** distinguish an eligible, sufficiently fresh live Location stream from generic session/service activity. Reuse valid fixes when authorized; a visible, explicitly started game may use its existing feature-scoped Location acquisition while active and legally permitted. Never change the recording source mask or silently create recorded Location. Reconcile physical ownership with the existing broker before editing; if the current `UiLocationProvider` cannot participate safely, present the exact minimal ownership/handoff change for approval. Do not solve this by always starting a second listener.

Preserve lifecycle cancellation: `GameSessionRuntime.kt:394` guards collection; :324 and :446 stop/cancel it. Preserve foreground permission gating in `MiniGameSessionViewModel.kt:332`, and the existing nonpersisting fallback in `FusedMiniGameLocationSource.kt:38`. Test start game during every capture mode, source changes during game, stale feed, permission loss, pause/resume, game stop while tracking continues, and tracking stop while game continues. Prove one eligible physical owner on device later.

Game copy also needs precise scope: `feature/game/src/main/res/values/strings.xml:57` says “Nothing is saved beyond the final score.” `MiniGameScoreEntity.kt:22` stores game ID, score, XP and played-at time; independently enabled tracking can still record. Proposed concise disclosure: **“Uses live location while you play. Saves your result and earned XP. Your tracking settings still apply.”** This wording is a proposal, with final storage scope checked alongside Data/export review.

### W2 — Precision prompts count unrelated sessions

`app/src/main/java/com/adsamcik/tracker/app/event/PrecisionUpgradeDomainEventConsumer.kt:54` discards the `SessionEnded` identity and invokes parameterless `onSessionEnded`; :71–90 checks current approximate preference and increments the counter for any session. Parent independently traced the active presentation in `MainRoot.kt:260`. Thus unrelated Steps/radio sessions can earn a precise-Location prompt.

**Proposed repair:** qualify the ended session's actual relevant Location capture and precision evidence, preserving existing dismissal and threshold. Current preferences alone cannot establish historical collection. No new prompt frequency or onboarding nag. Test non-Location sessions never contribute, imported/control/ambient records do not masquerade as captured sessions, a genuine approximate Location session remains eligible, and replay does not invent eligibility. The unused duplicate `PrecisionUpgradeOverlay.kt` is not the active call site.

### W3 — “Points today” freezes its upper time bound

`feature/game/src/main/java/com/adsamcik/tracker/game/repository/DefaultGameRepository.kt:78` creates `countBetweenFlow(startOfDay(Time.nowMillis), Time.nowMillis)` once per collection. `domain/points/src/main/java/com/adsamcik/tracker/points/database/PointsAwardedDao.kt:26` includes only rows at or before that fixed upper bound. Later awards invalidate the query but remain outside it. The day bound also requires rollover handling.

**Proposed repair:** use the current structural day interval and existing day-change invalidation, retaining timestamp semantics and avoiding per-row timers. Author tests for a later award while subscribed, midnight/zone transition, revocation/correction, and duplicate reward delivery. Preserve the existing idempotent goal-effect receipts and reward repair; do not add XP sources or rewrite progression.

### W4 — Legacy non-Steps metrics need a named meaning, not silent expansion

`feature/game/src/main/java/com/adsamcik/tracker/game/leaderboard/GhostLeaderboardProvider.kt:49` correctly excludes Steps pending qualified support. At :63–76 and :125–134 it reads `DailySummary` for distance, active time and sessions; an empty list aggregates to zero. `GameViewModel.kt:96` rebuilds the leaderboard on metric/retry changes, rather than a source-data invalidation stream. These are existing legacy metrics, not proof that new source-only sessions are represented.

`stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultWindowedMetricsProvider.kt:34` rejects raw Steps; :49–69 and :84–105 still derive session counts, active minutes and other awards from legacy daily/segment/exploration tables. Therefore a change to the meaning of shared “Sessions” or “Active time” must reach game/awards as well as Statistics. **Before implementation, inventory metric definitions and select the smallest disposition:** existing distance/exploration remain Location-qualified; universally labelled session counts use the approved native session identity; movement duration must not become elapsed radio-session time. Where source qualification is unavailable, show unavailable/limited coverage instead of manufacturing zero or rewards. Do not enable all new radio/ambient rewards implicitly.

Preserve `AchievementMetricQualification.kt:15–59`: XP/meta families are deliberately withheld and qualified Steps require exact READY provenance. Preserve source-qualified daily/weekly goals and their corrections (`SourceQualifiedStepsSummary.kt:74–150`) and `GoalNotificationWorker.kt:63–76`, which retries materialization/storage failures and suppresses unavailable/partial progress. A new source switch must not rewrite retained historical totals. Qualified steps of zero remain a real zero; unavailable stays distinct. Any changed reward eligibility is an approval-dependent policy, not cosmetic copy.

### W5 — Secondary surfaces must use the same facts and repair path

`app/src/main/java/com/adsamcik/tracker/app/widget/glance/TodaySummaryWidget.kt:97` joins legacy daily summary with qualified Steps. Its presentation at :185–207 contains distance, Steps, duration and session count only; :119 chooses an empty state from those fields. A native radio-only day needs a truthful compact summary or explicit source limitation, not “no data” based solely on legacy tables. Do not force radio metrics into a Steps goal or add another widget type.

First-pass active-widget and notification gaps remain: `ActiveSessionWidget.kt:234`, :296, :326 display speed/path/distance; notification components read legacy collection cycles. Apply approved source-aware presentation to these consumers, preserve user component order and units, and expose partial/unavailable values without fake zeros. Static layout judgment needs later rendered/device checks.

Good entry-point contract to preserve: `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/shortcut/ShortcutActivity.kt:42–55` and `WidgetActions.kt:24–43` already use centralized manual-start admission and open Dashboard for repair. Stop must remain usable after permissions are lost. Its label means stopping the session; separately consented ambient can continue. Do not silently redefine this as global consent revocation. Preserve existing timed-lock/until-charging/just-stop options in the main routes.

`feature/tracker/src/main/java/com/adsamcik/tracker/feature/tracker/notification/NotificationManagementScreen.kt:70` loads asynchronously; the empty list presents a spinner and persistence is optimistic. Error/retry and accessible reorder need focused source/UI verification before any change. This is an inspection debt, not an asserted device failure. Keep notification customization; do not remove settings simply because a source is presently off.

### W6 — Map legends must describe their actual calculation

`feature/map/src/main/java/com/adsamcik/tracker/map/viz/catalog/HeatmapCatalog.kt:114–118` builds terrain from raw Location fixes and `LegacyTileAggregatorStage`. `feature/map/src/main/java/com/adsamcik/tracker/map/graphics/LegacyTileAggregator.kt:45–70` counts rows per tile and normalizes by the maximum count. It does not compute dwell time. `feature/map/src/main/res/values/strings.xml:57–60` nevertheless describes places where the user spends the most time and frequency.

**Proposed copy-only repair:** retain the visualization and explain **“Height shows relative density of recorded locations.”** Legend: “Fewer observations / More observations.” Avoid time-spent or real-terrain claims. Sampling/coverage limitations belong in layer Details. The legacy setting subtitle at strings.xml:137 says 10 m, while `LegacyTileAggregator.kt:21` uses 25 m; correct or omit that incidental size. No new dwell inference is requested.

Preserve the first-pass distinction between native observed radio context and existing inferred radio fields. `HeatmapCatalog.kt:57–82` feeds identity observations to coverage/site-estimate renderers; pending observed-point policy does not authorize relabelling those estimates as device observations. `HeatmapSources.kt:104–114` filters unavailable signal normalization; missing readings cannot prove signal absence. Unlocated radio records remain discoverable in History/export. No histogram-specific production component was found in the inspected map UI; this is a search result, not evidence all map aggregators are audited.

### W7 — Destructive and privacy copy must state the real scope and progress

`app/src/main/java/com/adsamcik/tracker/app/settings/data/DataSettingsScreen.kt:440–459` uses generic confirmation; :254–268 maps completion to a toast. `DataSettingsViewModel.kt:399` collapses pending/failure results into failure. The underlying deletion protocol is intentionally durable: `CollectedDataDeletionService.kt:77`, :966–985 and :1001 require barriers/receipts and diagnostic cleanup. `core/base/.../database/AppDatabase.kt:1198–1230` removes summaries, exploration, awards, export logs, XP/player profile and mini-game scores, as well as raw/source data; Points storage is cleared by the service.

**Proposed repair:** retain one destructive confirmation but name affected history, derived progress/game results and local diagnostics; state external user-exported files separately after Data review confirms scope. Represent pending recovery honestly and offer the existing recovery action if available. Do not claim success until durable completion, offer an invented undo, or weaken deletion barriers for smoother UI. Keep migration backups and preserved legacy database actions distinct from portable export and live collected data.

Retention is a separate contract: `DataSettingsViewModel.kt:220–231` applies one duration across raw, radio, trip, summary and exploration preferences, followed by explicit approval reconciliation. Preserve fail-closed policy evidence; do not invent independent per-source retention sliders. Detail/export must explain when raw evidence aged out while an allowed derived record survives. Data peer owns full storage/export scope and exact serializer semantics.

### W8 — Source choice must survive setup, terminology and navigation

Root settings are already grouped into Tracking/Data/Activities, units/language, Map/Game, and About/Diagnostics (`RootSettingsScreen.kt:121–285`). Preserve this navigation. `feature/activity/.../ui/SessionActivityViewModel.kt` manages user-defined session labels, not captured Activity sensor data or automatic CONTROL. The visible term “Activities” needs contextual clarification where these meanings meet; proposed label **“Session labels”** for the existing editor, **“Detected activity”** for captured sensor facts, and **“Automatic start”** for the controller. No persisted enum/schema rename follows from this copy proposal.

Product owns setup and primary selection review. Parent independently verified `TrackingPolicyPreset.kt:54–69` and :103 default BALANCED enables Pressure, while `SetupViewModel.kt:324` applies that preset if hardware exists without a Pressure choice in WhatToCollect. The desired source mask must not silently gain Pressure. Product also reports permission-filtered selection in setup and swallowed completion failures. These require exact approved desired-intent/preset behavior before implementation; preserve existing user masks and avoid a broad defaults migration. Existing Activity/Pressure capabilities remain part of six-source compatibility, not a hidden new four-source presentation project.

## 3. Whole-application coverage ledger

“Change” means a proposed bounded correction; “regression” means preserve and include a scenario; “no direct change” means no task-driven edit identified in the inspected seam. It does not assert every line of a module has been audited.

| Surface / module group | Disposition and inspected evidence |
|---|---|
| `:app` navigation, setup, root/tracking/data/game/map/diagnostic settings | Change W2/W7/W8; root routes at RootSettingsScreen:121–285 inspected. Setup/primary navigation deep review delegated to Product. Preserve origin/back navigation and all existing feature destinations. |
| `:feature:dashboard`, `:feature:dashboard:api`, `:feature:tracker` | Change through first plan + Product: selected-source start/stop/live/ambient/detail state, notification content. Regression W5 centralized widget/shortcut admission. No second collection shell. |
| `:feature:statistics`, `:feature:statistics:api`, `:stats:api`, `:stats:engine`, `:stats:data` | Change shared native history/session counting and consumer meaning; Product owns charts/calendar; W4 inspected downstream metrics. Preserve typed Steps and correction authority. Activity/Pressure selectors require explicit existing-scope disposition. |
| `:feature:game`, `:feature:game:api`, `:domain:points` | Change W1/W3 and scoped W4 eligibility/presentation. Inspected live/fallback/game lifecycle, goal summary, leaderboard, windowed metrics, award qualification and points DAO. Preserve idempotence, hidden unverifiable families and local-only comparison. |
| `:feature:activity` | Copy clarification W8; CRUD session-label repository remains. Do not conflate labels with captured Activity or control. |
| `:feature:map`, `:feature:map:api` | Change native source discovery/context per first plan + W6 legends; Product owns route/permission hierarchy. Regression offline assets, layer IDs/preferences, display units, native/imported separation. No capture demand from browsing historical data. |
| `:domain:geocoder` | No direct change. `DefaultReverseGeocoder.kt:10–29`, :41–65 reads local places dataset and fails closed without network. Preserve locality label uncertainty; it is not recorded location precision. |
| `:feature:import-export` | Change full source coverage/date scope/completeness and format copy in Data review. Do not label legacy trips as all native history. DB export is not a proven full restorable app backup. |
| `:tracker:api`, `:tracker:control`, `:tracker:engine` | First-plan core changes + W1 qualified live feed and W5 entry-point contracts. Preserve shared owner, purpose/consent separation, protected writer, no implicit Location and scoped listener retirement. |
| `:sensor:activity-api`, `:sensor:activity` | Regression/first-plan CONTROL changes: automatic operation independent of capture, no control data in goals/history/export; shared Activity delivery can retain captured authority after control TTL. |
| `:core:base`, `:core:model`, `:core:common` | Change only bounded contracts/provenance/deletion needed by plan; AppDatabase deletion inspected. Preserve one canonical sample and separate purposes, stored epochs/zones, no fake sessions, no universal zero fallback. |
| `:data:preferences` | Regression desired settings vs capability, preset choices and privacy reconciliation. Current preference must not rewrite historical meaning. No new generalized tuning controls. |
| `:core:ui` | Regression typography, units, accessibility, long localized strings, no color-only evidence; reused components. No app-wide visual redesign proposed. |
| `:core:network` | No new egress. `MapTilesPolicyContributor.kt` derives only user-enabled map hosts; retain gateway policy, HTTPS/allowlist and cancellation. Offline or recording-disabled state never auto-enables online maps. |
| `:core:diagnostics` | Regression `TrackingDiagnosticPrivacy.kt:1–130` allowlists structural fields and rejects tracked payload/identifiers. Normal UI can show plain provenance descriptions; diagnostics remain payload-free, local, explicitly shared only. |
| `:core:sqlite-runtime`, `:core:testing`, `build-logic` | No new product feature. Preserve SQLite/runtime/migration and deferred gate contracts; shared schema changes still require final convergence. No checks executed in this pass. |
| `:tools:ski-data-generator` | No source-freedom code change identified; offline resource generation is outside production capture. Preserve formats consumed by map import; no regeneration requested. |
| About/licenses/privacy, unit/language preferences | No unrelated redesign. Update only statements contradicted by approved capture/export behavior; verify translations with changed copy. Root settings routes inspected; full legal/localized text audit remains a targeted execution task. |
| Widgets, launcher shortcuts, ongoing/goal/game notifications, debug tools | W1/W2/W5/W7 plus regression scenarios. Debug reset/settings controls must not be presented as safe history deletion or privacy reset. Full debug command implementation audit is not claimed. |

This accounts for the 31 application modules and tooling/include-build groups listed in `settings.gradle.kts:68–92`. Companion Product and Data reports supply depth for primary UI and serializers rather than a false claim that this role read every implementation or historical document.

## 4. Elements and copy must earn their place

| Element | Keep / change and reason | Source of truth / next action |
|---|---|---|
| Recording source chips + one Start/Stop | Keep, label session scope; exclude irrelevant speed/path slots | Desired source policy + actual session evidence; source settings or scoped repair |
| Ambient summary | Separate concise summary; do not merge into session status or count | Ambient consent + structural-day facts; Details shows source/time/why |
| Game permission explanation | Replace absolute no-save claim with scoped disclosure (W1) | Game result schema and independent tracker state; Start/permission action |
| Steps goal ring | Keep when qualified; unavailable text instead of zero ring | Qualified goal repository; actionable source setup only when relevant |
| Leaderboard/achievement empty state | Explain unsupported/limited evidence; no promise every recording earns rewards | Metric definition/qualification; change metric or inspect history |
| Terrain legend | Replace time-spent claims with observation density | Actual tile count; layer Details for sampling limits |
| All-data deletion | One confirmation with real scope; visible pending/failure recovery | Durable deletion operation; retry/recovery, never premature success |
| Session-label settings | Contextual label separates manual categories from sensor/control | Existing activity CRUD; no extra screen |
| Diagnostic details | Keep technical evidence in Details/Diagnostics | Payload-free structural status; explicit share action, no auto-upload |

These are copy/layout hypotheses from source. Final visual approval and validation must examine actual small screens, large fonts, TalkBack semantics, contrast, focus/reorder actions, pluralization, locale/units and long state messages. Removing duplicate explanation must not erase coverage, uncertainty or recovery.

## 5. Storage → presentation → export acceptance journeys

Author these scenarios during implementation, execute only after the global input freeze. Integrate with all 15 source subsets plus empty and manual/automatic/ambient coverage already required; this table adds consumer consequences without multiplying a device matrix.

| Journey | Required coherent result |
|---|---|
| First launch chooses only Steps; no Location permission | Desired choice survives denied/unavailable permissions; no hidden Pressure from preset; start via app/widget/shortcut has the same outcome; qualified Steps history/goals; no route or precision nag; exported source/purpose unchanged. |
| Wi-Fi/Cell-only automatic session | CONTROL starts/stops session without captured Activity; source history and compact widget/session count remain discoverable; map absence does not imply no data; no distance/active-movement reward invented; portable export includes supported radio facts and exclusions plainly. |
| Start mini-game while radio-only session runs | Explicit game Location works through approved ownership; recorded mask unchanged; game results/XP explain persistence; pause/stop retires only game demand; no game Location silently enters captured/ambient export. |
| Location+Steps recording continues while game ends | Shared physical delivery and independent ownership survive; score stored once; recorded Location retained by its own purpose; display/export do not double count game use as a trip. |
| Ambient passive Location+radio, no session | Sparse opportunistic facts labelled ambient, compatible same-purpose context only; no fake session/route/distance reward; source export retains purpose and uncertainty; revoke/delete one purpose preserves independently authorized sibling purpose. |
| New/corrected Steps and points while Game remains open | Goal/award/points state invalidates with qualified revisions; later awards and midnight represented; replay/retention/import cannot duplicate or resurrect rewards. |
| Activity or Pressure existing history | Labels distinguish captured Activity from manual labels/CONTROL; existing retained/imported Pressure values preserve units/quality; no new raw fallback or hidden activation, full scoped export/retention remains available. |
| Browse maps with all recording off and permissions denied | Retained map data/local basemap remains browsable; explicit My location is separate; no automatic permission repair or online egress; legends state observations/estimates faithfully. |
| Retain/import/export a partial day or older source generation | Counts and intervals remain qualified; user date scope and completeness are truthful; archived writer generation does not become current permission demand; no unavailable→zero coercion. |
| Delete all during active capture, game or export | Quiesce appropriate activity, enforce durable barriers, clear documented history/progress/results/local diagnostics, recover interrupted operation; no success until complete and no late callback resurrection. User-owned external exports are explained separately. |

## 6. Small proposed slices and approval boundaries

1. **W-A, dependent-consumer facts:** agree metric definitions with Product/Data; inventory exact source-aware adapters for widgets/notifications/session totals/game. Reuse existing typed read models and first-plan slices. Preserve qualified Steps and explicitly decide legacy non-Steps reward behavior before code.
2. **W-B, visible feature Location and prompts:** fix mini-game routing and contextual precision qualification. First disclose exact feature purpose/physical-owner handoff, active lifetime, freshness and persistence contract; no automatic tracking enrollment. The app's existing current-position request is subject to the same boundary.
3. **W-C, live progress:** repair Points Today interval/invalidation and any confirmed leaderboard stale-query issue using existing structural day/invalidation. No new scheduler network. Author correction/rollover tests.
4. **W-D, truthful secondary copy/state:** scoped game disclosure, terrain/legacy-size copy, source-aware compact widget/notification state and contextual session-label terminology. Confirm wording/hierarchy together with Product's primary screens; preserve units/customization and add accessibility coverage.
5. **W-E, privacy/recovery presentation:** align deletion/retention and backup/export screens with Data's exact file/table/result inventory; distinguish pending from complete, show irreducible limitations before destructive/export action. No destructive backup restore expansion.

Consolidate these into the existing implementation slices, not a parallel platform. Root's later reconciliation role owns the final approval block. Settled source freedom, required default-off passive ambient Location and existing six-source compatibility stay settled; exact new labels, acquisition handoff, metric scope and error presentation require approval. Source authorship, later validation, independent fresh review and local integration follow the original phase gates unchanged.

## 7. Remaining evidence and limitations

- Product owns detailed setup/dashboard/history/stats/chart/map route UI inspection; Data owns serializers/importers, DB files, privacy scope and automated export. Their findings must be reconciled before plan revision. Peer reports are pointers until checked; model agreement is not evidence.
- Data peer reported automated LastSession export falling back to unbounded history, JSON date overlap exporting full trips, lossy JSON quality fields and nonrestorable DB backup limitations. Those require its exact code-backed dispositions; this report does not duplicate a serializer audit.
- Non-Steps award/session-duration definitions, leaderboard invalidation, notification-management error/reorder paths, actual game provider ownership and all changed localized copy need narrow completion inspection before edits. No new current Android/API behavior is asserted here; provider/foreground legality belongs to official-doc/device gates.
- No runtime or visual findings were produced. Existing tests were inspected as contracts where relevant, not run. No universal 100% context/edge-case coverage claim; every in-scope application seam above has a disposition or explicit inspection debt.
