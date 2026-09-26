# Whole-product product and copy review

Date: 2026-09-26. Status: PLANNING_ONLY within IMPLEMENTATION_ONLY. No production/test edits, execution, activation, integration, or commits by this reviewer. Recommendations require the existing design-approval gate. This is static source evidence and proposed acceptance, not a visual assessment or proof of device reliability.

## 1. Product rule

The user's chosen sources determine what is collected. The data that was actually recorded determines what can be shown, explained, exported, or deleted. Missing optional data should limit only the feature that needs it. Every visible element should answer one of: what am I collecting, what happened, what does this value mean, or what can I do next?

Keep the existing navigation and screen shells. Complete their qualified readers and actions before adding more cards or explanations. Use one everyday vocabulary across setup, settings, live tracking, history and export; preserve detailed provenance underneath it. Smaller copy must not collapse missing, partial, failed and confirmed-zero states.

## 2. Evidence and coverage

Paths below are repository-relative; line numbers refer to the inspected source snapshot. **M** = clean local `dev/v10` at `13f1a99871`; **U** = `.worktrees/unified-tracking-surface` at `487a2fc98f` plus its existing seven tracked/three untracked ambient draft files; **R** = planning/root checkout at `d6a813e41e`. The current main clean status and U draft status were reread for this pass. Production changes in candidate checkouts are not main behavior or validated outcomes.

This review read the full `POLISH_REVIEW_CONTEXT.md`, the task/approval packet including section 10, the earlier plans, relevant actual components/resources/call sites and candidate UI files. Earlier PRODUCT_PLAN.md provides detailed source-combination and first-pass evidence; this report adds whole-product consequences rather than duplicating that audit. Default-language resources were inspected for the app setup/tracking states, dashboard empty/readiness/source states, statistics history/detail/report states, and map layer/permission/empty/recovery families cited below. Locale file presence was inventoried; individual translated strings were not exhaustively audited. Unchanged settings selectors, history navigation and online/offline choice families are explicitly retained. No claim is made that every historical string or component in every module was independently inspected by this role.

| Surface | Inspected evidence | Coverage/result |
| --- | --- | --- |
| Setup | M `app/.../onboarding/ui/SetupRoute.kt:150–205`, `SetupViewModel.kt:254–329`, `steps/HowToTrackStep.kt:80–105,153–162`, `BackgroundAccessStep.kt:110–136`, `OnlineMapTilesStep.kt:50` | Source choices, modes, completion, optional background access, explicit online/offline choice |
| Tracking settings | M `app/.../settings/tracking/TrackingSettingsScreen.kt:218–553`, `SourcePurposeSettings.kt:39–96`, app default strings 375–390 | Simple/advanced sources, mode availability, ambient consent, technical details, notifications |
| Dashboard/start/stop | M `feature/dashboard/.../DashboardRoute.kt:338,428–494`, `DashboardScreen.kt:381–439`, `cards/IdleContent.kt:76–113`; U commits `441c23b063`, `487a2fc98f` | Central readiness, existing stop choices, empty/idle hierarchy, candidate non-Location live cards |
| Live/ambient | U `tracking/TrackingContent.kt:78–142`, `data/DashboardAmbientSummary.kt:44–105`, `cards/AmbientSummaryCard.kt:26–78` | Actual captured sources, independently consented ambient, uncertainty/freshness/action gaps |
| History/date discovery | M `feature/statistics/.../ui/HistoryRoute.kt:78–166`, `CalendarContent.kt:164–310`, `presenter/HistoryPresenterViewModel.kt:128,197,231,288` | Existing tabs/back/undo preserved; shared source-aware date discovery still needed |
| Detail/actions | M `SourceHistoryDetailRoute.kt:70–145,155–222`, `TripDetailRadioOverviews.kt:46,108,125,180–238`, statistics default strings 102–109,161–167,210,274–310 | Actual source detail routing and action gaps; excessive authority copy |
| Statistics | M `fragment/StatsRoute.kt:55–86,113–125`, `ui/SummaryRoute.kt:101–103,159`, `WifiStatsRoute.kt:99–101,159`, `CellSignalReportRoute.kt:101–103,138,191–208` | Trip-based empty gating; dedicated report recovery; radio metric semantics |
| Maps | M `feature/map/.../ui/MapRoute.kt:48–74`, `MapScreen.kt:322–324,477–484,626–636,681–735,783–837,895–896`, default strings 20,25–31,57,108–109,159,170–174,187–204 | Distinguish stored map data, current-position permission, layer availability and basemap failures |
| Localization/accessibility | Calendar source, setup choice semantics, default/locale resource inventories in app/dashboard/statistics/map | Existing localized resources and 48dp calendar targets; concrete missing semantics below; no translation-quality or rendered layout claim |
| Export/storage/privacy | Direct peer review with DATA_EXPORT_REVIEW owner, existing source detail action call site | Inclusion, date scope, provenance, partial data and action eligibility contracts; serializer detail owned by Data |
| Goals/awards/game/activity labels, widgets, notification, shortcut, resource/privacy/debug screens | Direct coordination with WHOLE_TRACKER_REVIEW owner; first-pass PRODUCT_PLAN secondary-surface evidence | Whole-product consequences retained in synthesis; this report does not claim a second independent exhaustive trace of these modules |

`...` abbreviates only the existing package prefix: app=`src/main/java/com/adsamcik/tracker/app`; dashboard=`src/main/java/com/adsamcik/tracker/dashboard`; statistics=`src/main/java/com/adsamcik/tracker/statistics`; map=`src/main/java/com/adsamcik/tracker/map`. Each filename above is uniquely identified by its module and directory.

## 3. Concrete findings that block a coherent product

### U1. Setup and settings express different choice semantics

M `SetupViewModel.kt:272–310` filters selected sources by current permission/hardware and replaces the chosen automatic mode with zero when prerequisites are absent. `HowToTrackStep.kt:93` recommends automatic on-foot mode without receiving runtime availability; settings separately shows saved-but-unavailable mode. `completeSetup:254–264` swallows failure through `getOrNull()` after writing preferences/completion, with no visible completion-error/retry state in the route.

**Proposed approval:** preserve explicit source/mode intent during setup, show current availability separately, and let users finish without granting optional permissions; retain the central any-available-source start rule. Show a scoped inline completion error and retry that safely resumes the completion sequence. Do not silently start providers or claim automatic tracking is operating. This changes current setup behavior and must be approved, even though it aligns with the settled independence model. Hardware unsupported and user-off remain distinct. Root verified an implicit source change: the Balanced preset has `barometerEnabled=true`, and MAIN SetupViewModel:307 assigns preset barometer availability despite no Pressure choice in onboarding. Recommend presets change quality while preserving the explicit chosen source mask: new setup must not enable unselected Pressure, which remains available through its existing settings control; preserve existing users' masks. An alternative is an explicit visible Pressure choice, requiring separate UX approval, but adding it merely for symmetry is not recommended. The setup default/source-mask correction is approval-gated behavior, not permission inferred from this review.

### U2. Source-independent collection is still presented through Location-shaped empty/history/statistics flows

M `DashboardRoute.kt:338` passes central manual readiness into the field named `hasLocationPermission`, while `IdleContent.kt:81` and `DashboardScreen.kt:403` render `LocationPermissionBanner`; default dashboard strings 7–9 say routes cannot be recorded and request Location. A source-specific prerequisite can therefore receive Location-centric wording. The empty route renders banner, four-feature hero, a Start card and a three-step help card (`DashboardScreen.kt:403–431`). Strings 216,230–236 prescribe Location and walking even for source-only intent.

M `StatsRoute.kt:55–58` decides Empty from physical-trip paging count, and qualified weekly Steps is collected only for Content with one or two visible sessions (`:113–125`). `HistoryRoute.kt:78–166` and calendar still navigate Trip records. Completing only Recent Tracking would leave native source history invisible elsewhere. Calendar has unconditional distance/trip summary values (`CalendarContent.kt:257–272`).

**Required implementation under approved plan:** drive shared history/date discovery and summary eligibility from qualified retained source records, with source-appropriate actions. **Polish proposal:** adapt existing labels from Trips to Sessions only where that list truly contains source sessions; preserve route-specific Trip terminology where applicable. Describe each prerequisite by the action/source it affects. In the empty dashboard retain one obvious start or repair action and a settings link; combine repetitive setup help rather than add another tutorial/card. Preserve user widget order and already-useful navigation.

### U3. Controls/actions must reflect exact scope

Keep the existing automatic stop options in DashboardRoute:428–494 and TrackerRoute:313–382 (timed pause, until charging, just stop). Session Stop must remain usable after permission loss; it does not revoke separately consented ambient. The U dashboard fix does not fix M `feature/tracker/.../ui/compose/TrackerDashboardControls.kt:170–197`, which still tests permission before Stop.

M `SourceHistoryDetailRoute.kt:70–87` renders unavailable actions for every loaded source, and `:123–129` rejects Steps/Pressure detail entries. Implement approved authenticated source-local/mixed selectors, export/delete/pending/retry rather than polish an unavailable menu into apparent completion. Route/GPX actions require suitable route data; local source export/delete does not. Historical data access must not be blanket disabled merely because future capture is off or Android permission was revoked: recheck retained record/consent/privacy authority, deletion/pruning and action scope. Ambient consent revocation and Android permission revocation are different events.

### U4. Ambient values need a clear time window and retained-history route

The U uncommitted AmbientSummaryCard:26–78 shows consent-gated Steps/Wi-Fi/Cell counts but no timestamp/window, details action, or recovery action. Its reader uses latest structural Steps day versus recent radio facts, and collapses some gap/unavailable cases into no recent evidence (see first-pass P4). Count labels without their scope would imply a common period. Turning acquisition off must not make retained, still-authorized history undiscoverable.

Complete required passive ambient Location, including sparse point truth and actions, under its already-approved baseline scope; it is not a new optional source. Present ambient within existing dashboard/history shells, clearly separate from sessions. **Pending UI decision:** proposed compact “Between sessions” summary, one shared explicit window or clearly labeled per-source windows, freshness, details/recovery, and event-based refresh. No automatic 30-second poll or expanded active ambient Location is approved. Missing passive points must not create a route, zero coverage, or a promise of continuous tracking.

### U5. Map browsing is unnecessarily tied to current-position permission

M MapRoute:62 mounts a contextual Location request on map entry without permission. MapScreen:783–837 also presents an error-colored permission banner regardless of whether the user requested current position. Stored map browsing and offline map use need not imply new acquisition. Existing source-specific empty messages (`strings:170–173`) correctly distinguish unmapped radio records from missing records; preserve that meaning.

**Proposed approval:** request current-position permission from the existing My location action, with contextual recovery; show retained map layers without a blanket permission error. Do not change capture settings. Keep layer-empty, layer-read failure, basemap failure and no compatible Location context distinct. Historical context remains usable only while its stored authority qualifies; rechecking eligibility is not blanket hiding on current permission/toggle loss. Native aggregate radio maps must describe observations at recorded locations, not inferred access-point or tower positions. Legacy identity-bearing layers can retain their accurate distinct-identity vocabulary and must not silently blend different metric meanings.

### U6. Dedicated reports have no direct recovery action

M SummaryRoute:103/159, WifiStatsRoute:101/159 and CellSignalReportRoute:103/138 render errors without an onRetry callback; returning/reopening is the available workaround. The Stats pager already has retry (`StatsRoute:76`). Extend the existing pattern to these bounded reads and show a user-meaningful failure plus Retry; preserve the last qualified visible result only if explicitly labeled stale and backed by the read contract. Do not display raw exception messages or turn read failure into an empty encouragement.

## 4. Element and copy ledger

These are proposed changes, not permission to implement. Visual prominence judgments below are source-derived hypotheses to inspect after assembly.

| Disposition | Element/text and evidence | Decision or understanding served / proposed treatment |
| --- | --- | --- |
| Keep | Existing bottom navigation, History back button/tabs/undo (`HistoryRoute:105–166`) | Familiar wayfinding and recovery; extend source readers/actions in place |
| Keep | Simple/advanced settings and technical-status expansion (`TrackingSettingsScreen:391,503–524`) | Relevant controls first; detailed diagnostics on demand |
| Keep | Optional background permission and explicit online/offline choice (`BackgroundAccessStep:110–136`, `OnlineMapTilesStep:50`) | Real permission/privacy choice; no stronger tracking/network default inferred |
| Combine | Dashboard hero/start/help stack (`DashboardScreen:403–431`; default strings215–236) | One source-aware primary action, brief benefit/current state and settings link; remove repeated permission/tutorial directives, preserve discoverable destinations |
| Replace | “Location permission needed” for generic readiness (`DashboardRoute:338`; strings7–9) | “Steps needs physical activity access” or the actual blocked source; show available sources can still start where policy permits |
| Move | `status.reason.stableCode` (`SourcePurposeSettings:82–85`); app strings375–390 discussing approval, owner reconciliation, current rollout/provider startup | Normal text states effect and possible action; exact reason codes belong in existing technical details/diagnostics |
| Replace | “Source recency authority…”, “bounded read…”, “physical membership…” (dashboard strings86–91; statistics104–109,303–306) | Concise result/problem plus correct recovery: unavailable/read retry, expired selection/reopen history, invalid record/details; retain distinct underlying reasons |
| Combine/move | Repeated SourceHistoryAuthorityCard (`TripDetailRadioOverviews:46,125,185–236`; strings161–167) | Keep “Recorded here”/“Imported”, source, purpose and time near title. Put exact consent/provenance/exclusion reasons under existing/planned Why recorded/details |
| Replace/remove repetitive clause | RadioLocationExclusionNotice always at `TripDetailRadioOverviews:108,180,238`; statistics string210 | Do not repeatedly list absent route/distance/speed/altitude/identifiers in every radio detail. Show relevant map limitation near map action; retain privacy/identifier disclosure at export or Why recorded where it affects a choice |
| Keep and qualify | Pressure hPa, partial/coverage state; source radio counts; step numeric qualification | Units, period, origin and coverage must agree with storage. Pressure is not automatically elevation; observations are not unique devices or continuous signal coverage |
| Replace | Empty “No activity yet”, “Start tracking…” (statistics16–17,65–77,349–354; dashboard63) | Scope to source/date/filter and distinguish not recorded, no observations, still preparing, unavailable. No admonition to enable Location for a successful source-only user |
| Keep, correct meaning | Map density/coverage labels (`map strings20,25–31,57,108–109`) | Observed samples are not time spent or proven dead zones. Whole-app peer traced terrain through HeatmapCatalog:114–118 and LegacyTileAggregator:45–70: row counts normalized against maximum, no dwell duration. Replace terrain time-spent claim with recorded-location density; no new dwell engine or wholesale layer redesign |
| Replace/contextualize | Universal GPX recommendation/format-description promises; source export share summary | Data peer found concrete format-dependent omissions and whole-trip date behavior. Present actual included sources, range semantics and limitations before destination action; preserve full provenance in output |
| Keep/de-emphasize conditionally | Motivation/streak/achievement widgets (`IdleContent:76`, existing registry) | They can serve existing product goals. Do not remove user-selected cards because source-only work is underway; whole-app reviewer determines whether unsupported metrics currently misstate progress |

### Per-element implementation gate

Apply this small ledger to every element/string touched by a slice, including unchanged neighbors whose meaning depends on it. Record **task served, data truth, prominence, shown when, action**, then keep/combine/move/replace/remove. Do not invent an application-wide metadata framework. If no user task or understanding is served, remove the repeated/decorative explanation after approval; if the only purpose is debugging, use the existing technical surface. Hide an irrelevant control, but retain discoverability of stored data and an explanation beside a requested-but-unavailable feature. A real actionable failure or lossy export choice must remain visible.

| Element | Task / truth | Prominence | Shown when | Action |
| --- | --- | --- | --- | --- |
| Primary session control | Start/stop from central session/readiness state | Primary, reachable | Current capture context | Start; repair only when necessary; Stop irrespective of permission |
| Source limitation | Explain a selected source's actual obstacle | Beside affected row; escalated only when it blocks requested task | Selected source unavailable/partial, not merely another optional source off | Actual permission/settings/retry action, or concise non-actionable limitation |
| Metric | Understand a qualified quantity and period | Main content where relevant | Source recorded/retained with defined numeric qualification | Detail if exact selection exists |
| Origin/time/coverage | Interpret data correctly | Compact beside result | Whenever necessary to distinguish recorded/imported/partial/window | Why recorded or source detail |
| Technical reason code | Diagnose exact internal state | Subordinate details | Explicit user request for technical explanation | Existing diagnostics, no fake settings repair |
| Map control | View qualified spatial data/current position | Existing map control location | Route/context eligibility; current position only on request | View retained map or contextual position permission |
| Export inclusion/lossiness | Decide whether selected format meets intent | Visible before save/share, not buried | Format drops/expands/transforms selected data | Choose format/scope or proceed with informed choice |
| Empty helper | Explain absence in current source/date/filter | Quiet, single explanation | A completed qualified empty read | Relevant clear-filter/history/settings/start action; never generic sensor activation |

## 5. Proposed concise copy and layout examples

Examples define meaning and hierarchy, not final visual design or automatic approval. Use existing components, resources and formatters.

- **Ready, partial availability:** `Ready to track` / `Steps and Wi-Fi available · Cell needs access` / primary `Start tracking`, secondary `Review Cell access`. The actual central readiness contract decides whether start is possible; do not show a nonfunctional start.
- **Live source-only session:** `Tracking · 12 min` / `Steps 640` / `Wi-Fi 18 observations · Last saved 14:32` / `Stop session`. Add per-source partial/unavailable state only when true. Do not reserve a large empty route panel.
- **Automatic saved but unavailable:** `Automatic start is unavailable` / `Your choice is saved. You can start a session manually.` Add an actual repair action only when one exists; do not instruct users to fix a contained implementation through Android settings.
- **Sessionless ambient:** `Between sessions · Today` / `Steps 640 · Partial` / `Wi-Fi · Last observation 14:32` / `View history`. Do not use “Today” until all row windows actually match it. Passive Location uses points/last observation, not trip duration.
- **Radio detail:** `Wi-Fi · 25 Sep, 14:10–14:35` / `Recorded here · During a session` / metrics with their period / `Partial: no observations saved from 14:18–14:22` only if those bounds are known / eligible Export/Delete / subordinate `Why recorded`. A lack of radio callbacks cannot establish “0 networks” or a measured gap interval.
- **Expired detail selection:** `Open this entry again` / `Return to history and select it again.` Existing Back can serve navigation; consider one direct return action only if it avoids a dead end. Do not describe process recreation or private token mechanics.
- **Map without context:** `No Wi-Fi observations can be placed on this map for these dates.` / `Unlocated observations are available in history.` Add the existing relevant history action if safe scope exists; do not start Location. Old authorized context may still qualify even if Location capture is now off.
- **Export:** `Wi-Fi observations · 25 Sep` / `Includes observation counts and saved context` / format-specific exclusions/date expansion visibly stated / `Save file` or `Share`. Actual content must come from the writer's manifest/preflight, not this example. No universal GPX recommendation for a source-only selection.

Use “session” for an explicit bounded capture, “between sessions” for ambient, “activity recognition” for the captured source, and “activity label” for the user-defined SessionActivity feature where ambiguity exists. Automatic control evidence does not become a user-recorded Activity history merely because it triggered a session. Plain summaries must retain those distinctions.

## 6. Accessibility and localization

- **Concrete defect:** CalendarContent:222 uses hardcoded English `Day $dayNum`; it conveys neither month/year nor selected state or retained-data state. Use localized full date and selected semantics, and a compact truthful recording/status description. Existing min48dp targets (:217) should remain. Do not convey source presence or partial coverage only with intensity/color.
- **Static concern to verify:** HowToTrackStep:153–162 and213–222 use a clickable card plus interactive RadioButton rather than the single selectable pattern already used in OnlineMapTilesStep. Recommend one accessible radio choice per card with selected state and no duplicated action; confirm with deferred TalkBack traversal.
- U AmbientSummaryCard:52–76 lays unconstrained label/value Text in one Row with SpaceBetween. Long translated partial/unavailable labels and large font may collide; use existing adaptive wrapping/stacking patterns if the eventual rendered check confirms risk. Do not assert current clipping without rendering.
- Default resources include many `tools:ignore="MissingTranslation"` additions (statistics102–109 and many source states; dashboard source additions). This is fallback text, not translated coverage. Put all changed copy, plurals, dates, units and accessibility labels into resources; do not claim all locale translations verified. Review existing supported locale fallback intentionally and preserve formatting arguments.
- Loading, saved/failed, selected source, partial data and pending deletion need announced state changes without repetitive announcements for every sample. Meaningful icon actions retain labels; decorative hero images need not add redundant speech. Check primary action reachability and stop controls at large fonts, narrow width, landscape and keyboard/TalkBack later.

## 7. Cross-layer product invariants

1. Current Android permission/source intent governs future acquisition; historical authority governs retained reads/actions. Disabling capture, revoking separate ambient consent, deleting records and purging context have distinct semantics. UI/cache/export must apply the actual policy, not one generic “revoked” boolean.
2. A displayed count names its source, period and qualification. Recorded zero is numeric only when the source contract proves it. Materializing, unsupported, no observations, partial capture and failed reads never become zero. Real radio callbacks currently drop empty eligible lists; do not invent complete-zero coverage.
3. Location context adds presentation only when eligible recorded points exist. It creates no demand, hidden session, inferred route, network identity or continuous-coverage claim. Required passive ambient Location remains sparse and separately consented.
4. Native, legacy and imported records can have different truthful metrics. Do not sum AP/tower identities with identity-free observation totals, infer sensor Pressure elevation, or show duplicated counts from multiple purposes/owners. Why recorded and export preserve provenance even if the summary is compact.
5. Source-only history must be reachable from dashboard, Stats and arbitrary date/calendar/timeline; a recent filter is not date discovery. Details/export/delete authenticate the exact bounded selection and have pending/stale/error outcomes.
6. Data peer verified global adapters do not currently include native Wi-Fi/Cell facts; legacy JSON/date export may write whole overlapping trips. ImportExportComposeActivity only applies no-data gating to exporters requiring Location (portable source formats are not universally blocked). Fix actual content/scope promises and source share summaries; do not make a false generic “all exports blocked” claim.
7. Whole-app peer identified source-only service starving explicitly started game Location feed, custom Activity-label naming ambiguity, and unqualified leaderboard totals. Their fixes must preserve explicit game acquisition purpose and existing navigation; never enable captured Location to repair a secondary feature.

## 8. Bounded slices and approvals

No additional architecture, dependency, navigation system, generalized brain, universal state engine or new analytics screen is proposed. Reuse the first-pass runtime/data work and existing components.

| Slice | Work after approval | Acceptance authored later |
| --- | --- | --- |
| UX1 setup/intent/recovery | Persist explicit intent separately from availability; accurate automatic copy; completion error/retry; contextual prerequisite text | Permission deny/grant/resume, missing hardware, any-source readiness, safe completion retry |
| UX2 source-complete shells | Extend existing dashboard/live/history/Stats/calendar/detail to qualified sources and exact actions; preserve stop variants | All15 L/S/W/C sets plus empty; manual/automatic and required passive ambient; Activity/Pressure regressions; non-Location records with no physical trip |
| UX3 useful hierarchy/copy | Combine empty help, move proof codes, concise origin/partial explanation, consistent source vocabulary; agreed ambient period/freshness/action | No misleading zero/route/identity; each state has appropriate action or quiet explanation; history remains discoverable after capture off |
| UX4 maps/recovery | Contextual current-position request; honest map eligibility/empty states; direct report retries | Browse imported/retained maps without current permission, no demand changes, qualified context survives only permitted retention, retries do not erase qualified data |
| UX5 cross-surface actions/export | Data-owned format inclusion/range/share/delete contracts wired into existing actions and copy; adjacent game/widget/notification consequences from whole-app report | Same source/time/count/partial meaning from card to detail to file; pending deletion and stale selection safe |
| UX6 accessibility/localization | Full date/selected calendar semantics, single accessible choices, resource copy/plurals, adaptive changed rows | Authored semantic cases; later representative small width/large font/TalkBack/locale proof |

Batch new decisions with existing IMPLEMENTATION_PLAN section9 choices: (A) setup saved-intent semantics and retry; (B) the compact hierarchy/copy and ambient window/freshness proposal; (C) contextual map permission and report recovery; (D) source-aware scope/format presentation plus agreed inclusion semantics; (E) accessibility corrections and consistent labels. The recommended smallest option is these changes within existing shells. Keeping current copy/layout avoids changes but preserves contradictions; a broad navigation redesign adds risk and is not recommended. Do not ask again whether providers are independent, whether ambient is sessionless, or whether passive ambient Location is required. Do not treat this pass as approval of 24-hour control TTL, 100m map cap, 30-second polling, new format design or expanded ambient Location.

## 9. Bounded acceptance and deferred proof

| Scenario | Expected product result and action |
| --- | --- |
| Empty selected set | No hidden provider/session; one clear select-sources action; no generic Location prompt |
| Each15 nonempty L/S/W/C combination | Successful eligible sources stand alone; names/metrics/actions match actual captured sources; map only when retained context qualifies |
| One selected source denied/unsupported | Other eligible source can start; blocked source explained with a real repair or supported limitation; Stop always works |
| Setup choose source then deny permission; completion write/scheduler fails | Chosen intent and operational result follow approved rule; no silent completion failure or surprise activation |
| Automatic/control-only | Mode selection, current operation and needed control consent are distinguished; no captured Activity history inferred from control evidence |
| Ambient only, including passive Location; session overlaps/stops | Separate consent/purpose, honest windows/last observation; source owner shared; session Stop preserves ambient and existing stop options |
| No physical Trip but Steps/Pressure/Wi-Fi/Cell retained | Dashboard/Stats/date history/detail all discover appropriate data; no false empty app or unavailable actions |
| Partial, pending, read error, no observation, proven zero | Distinct display and eligible retry; counts never silently become zero or complete |
| Source off or OS permission revoked after recording | Existing data shown according to historical authority; no blanket loss of authorized history; acquisition state explained separately |
| Imported data and stale selection | Visible origin; no local sensor provenance invented; stale action reselect/retry safe |
| Export date crosses session midnight/timezone; chosen format omits sources | Scope/omissions explained before save/share; file content matches approved semantics and displayed count/period; no accidental whole-data expansion |
| Browse map without position permission; no compatible context | Existing map remains usable; My location requests permission only when chosen; no activation and no guessed network positions |
| Calendar/TalkBack/large font/localized text | Full understandable dates and selected/state meaning, reachable start/stop/retry, no color-only state or duplicate radio action |
| Existing game/goals/widgets/notifications/shortcut/privacy/resources | Preserve independent purpose, qualified metrics and navigation; use whole-app report's concrete cases rather than refactoring unrelated screens |

These are specifications for later authored focused tests and the single final convergence/device batch. No tests were run or authored during this planning pass. Remaining evidence gaps: rendered hierarchy/contrast/wrapping, TalkBack behavior, translation quality, actual OS background limits/freshness, and export round trips. Peer reports and static traces do not prove these outcomes.
