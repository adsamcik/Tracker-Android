# Period metric product trace — 2026-10-04

**Static source inspection only. IMPLEMENTED_UNVALIDATED.** No code edits, Gradle, compilation, tests, lint, Room generation, device/provider activation, Git or integration. Candidate parent reported by root: `c315f7dc1c`; root confirms period paths unchanged since `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`. Working source pins below make this inspection reproducible without claiming all inspected bytes are committed. Radio/History source packets remain untouched.

## Approved scope

- Longest trip: measure complete saved Location trips selected by the original trip start; explain whole-trip scope.
- Discovery day/week streak: longest consecutive qualifying periods inside the selected window; All time keeps its complete streak.
- Exploration period statistics requiring the declined dated visit/revisit lookup remain unavailable. Add no new lookup.
- Saved timestamps remain UTC milliseconds; displayed day/date selections represent local calendar days.

Source: `convergence/2026-10-03/DECISION_STATUS.md:17–40,208–215,263–269` and inherited original user follow-ups. This trace does not select a new period UI design.

## Actual product paths

| Surface | Actual request and presentation | Assessment |
| --- | --- | --- |
| Statistics → Dates → This/Last month or This year | `StatsDateRangeDialog.kt:70–105` produces local-day bounds. `StatsRoute.kt:121–123` saves the chosen filter. `StatsPresenterViewModel.kt:82–100,329–332` uses it only for `getPagedTripsOverlapping`. | This is a session-list filter, not a request for bounded longest-trip, streak or seasonal metrics. The prompt explicitly says “filter sessions” (`strings.xml:31`). The picker bridge defect below alters those bounds. |
| Statistics → Summary | `StatsNavGraph.kt:53–55,71–74` navigates the period-free `StatsSummary` object (`StatisticsRoutes.kt:14`). `SummaryRoute.kt:58–64` creates its own ViewModel and calls `loadSummaryStats`; `StatsPresenterViewModel.kt:240` calls `SessionStatsRepository.getAllTime`. | All-time summary. No selected month/year travels to it. No longest-trip/streak/season row exists in `SessionStatsUiFormatter`. Do not describe this as a bounded reader falling back: it never calls that reader. |
| Statistics seven-day Steps/compact card | `StatsPresenterViewModel.kt:130–153` requests seven local epoch days, independently of session paging. `StatsScreen.kt:678,730` uses the existing “This week at a glance”/daily-activity scope. Steps materializing/partial/unavailable is kept by `summaryValueText` at `:753–760` for the Steps-only presentation, and withheld as N/A at `:713–716` when sessions are present. | Actual separate seven-day scope, not the selected month/year. No All-time Steps substitute found. |
| History calendar | `HistoryPresenterViewModel.kt:292–304` requests month-bounded daily summaries; selected-day Steps uses its separate numeric repository (`:214–219`). | Month/day presentation exists; it does not request longest-trip or discovery-period streaks. The history author owns its broader convergence work. |
| Game exploration | `GameUiRepositories.kt:55–67` observes lifetime cell count, stored daily current/best streak and combined seasonal bitmask; `ExplorationViewModel.kt:74–91` maps them. | No selected period exists here, so lifetime values are not being placed under a month/year label. |

## Concrete defect: P2 — Date picker bridge converts local calendar choices to UTC days

`feature/statistics/src/main/java/com/adsamcik/tracker/statistics/ui/compose/StatsDateRangeDialog.kt:159–161,173–174,233–235,266–268`

The app gives local-midnight instants to Material's UTC-calendar picker, then confirms its UTC-midnight values directly with a fixed 24-hour end. Every preset also passes through `setSelection` before OK, so correct pure preset calculations do not protect the production path. In a positive-offset zone, a local midnight lies on the preceding UTC date; in a negative-offset zone, the summary formats a UTC date as the previous local date. The fixed day addition also fails the approved local-day scope on DST days. A custom Prague date can omit its early local hours and include early hours of the following date. This affects the real month/year session filter before any metric API call.

Smallest existing-convention repair: keep selections as `LocalDate`; encode a date as UTC midnight only when exchanging with Material; decode picker values with UTC into `LocalDate`; resolve query bounds through `date.atStartOfDay(displayZone)` and `endDate.plusDays(1).atStartOfDay(displayZone) - 1`. Use the same conversion for initial values, preset setSelection, headline and OK. This implements the already approved local-calendar behavior, with no new UI or data model. Author a non-UTC preset-to-picker-to-confirm case, a custom-date case, and both DST boundaries. Existing `StatsDateRangePresetOptionsTest.kt:29–30` deliberately fixes UTC and tests the pure helper; current Compose tests do not verify emitted bounds outside UTC. No test was run.

Static framework evidence: cached Material3 source `DateRangePicker.kt` states selected dates and initialization/setSelection are UTC-day values, and `DateRangePickerStateImpl.setSelection` canonicalizes them. Inspected locally in the 1.5.0-alpha27 source archive; equivalent cached 1.4.0/alpha26 archives are available. This is source contract evidence, not proof of the resolved dependency or UI execution.

## Remaining connection: bounded metric API has no production consumer

An explicit search of all included production source trees (`app/core/data/domain/sensor/stats/tracker/feature`) finds `WindowedMetricsProvider` only in its interface, implementation and Hilt binding (`StatsDataModule.kt:159–161`). `LONGEST_TRIP_KM`, `DAILY_STREAK` and `WEEKLY_STREAK` likewise have no product collection call. The API's capability exists; there is no user-visible period request/row/copy to trace.

- `DefaultWindowedMetricsProvider.kt:47–57` calls the saved-Location reader for longest trip, including interval/rolling/cumulative scopes.
- `:59–60` returns `StatsError.ValidationError` for bounded seasonal visits.
- `:68–79,150–173` calculates bounded discovery streaks and returns budget/database errors.
- `SavedTripDistanceReader.kt:94–95` returns a typed NotFound when no measured trip is available rather than returning zero.

Consequently neither a bounded value nor its typed unavailable result reaches a user through this API. No current product path was found substituting All-time/zero **for these specific bounded metrics**, because no current product path requests them. Longest-trip original-start scope explanation is also absent from product resources; backend KDoc is not a user explanation.

Smallest next step depends on the intended existing destination: explicitly select whether the current Summary should gain the approved period metrics/selected date scope or whether these API capabilities should remain non-presented for MVP. Then reuse that destination's existing ViewModel/state/row conventions, preserve Left as a localized unavailable row, and provide the approved whole-trip scope explanation. This is a product connection/design choice to close, not permission to invent another statistics screen, another metric framework or a dated visit/revisit lookup. No such implementation was authored in this trace.

## Boundaries and acceptance

Backend tests exist for SavedTripDistanceReader, DefaultWindowedMetricsProvider and WindowedDiscoveryStreakRoom; they remain source-only evidence here. `loadWeeklyStats`/`weeklyStatsState` and `ExplorationProgressCard` have tests but no production consumers found; their mere existence does not close the live product path. Summary's legacy source counters/Location availability and broader history convergence are separate existing review work, not asserted here as a new bounded-metric fallback.

**Verdict:** backend period capability is statically traceable; full user-facing period-metric completion cannot be declared. One real date-selection defect remains, and the bounded API has no selected product connection. No implementation/integration/runtime validation claim.

## Exact inspected source pins

- `stats/api/src/commonMain/kotlin/com/adsamcik/tracker/stats/api/repository/WindowedMetricsProvider.kt` — `f08801855be4d186f54efbf30772dae201a3cce2dab4efd42d72268b414c5acc`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultWindowedMetricsProvider.kt` — `20a13a1e3fef15ba44a5c58fb2e58f27c3a40f1557b34d8b92eb32b322b68c13`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/SavedTripDistanceReader.kt` — `38b7466823ffdd187b851569b76fbef386144a009ef68afa4b5bb4470126677d`
- `stats/data/src/main/java/com/adsamcik/tracker/stats/data/di/StatsDataModule.kt` — `1f17176f313769013513514bbc8a455688c79eef936871304ab9b259ca38252d`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/fragment/StatsRoute.kt` — `f8011710b21c99c8513f7caf275ca873fad6b3968076a9785c9a1202a62a46b8`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/fragment/StatsScreen.kt` — `df50f5ff1f11fe4ccf46dad183c3d97dad2ded923c087dc8cccbbc6a0baeaf29`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/presenter/StatsPresenterViewModel.kt` — `fc6c7438a96f20a3de7969e8c6f56da9011c716758bc5125187b412b15c5a7a9`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/ui/SummaryRoute.kt` — `701eb09d4bdd1b2fc5823713e20ba54ddb9e544e527e66a3ff8c0d4558300a62`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/navigation/StatsNavGraph.kt` — `33fd50545fd7eb57907915d1531808b4768837047a9ce8cbe477255e5192c102`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/ui/compose/StatsDateRangeDialog.kt` — `1f5ec924b66fc92e9c6bb5bba0c74b75225607cf535674b915bdffab9abc422d`
- `feature/statistics/src/main/res/values/strings.xml` — `2d6bc1cbb3bf4aa2c73a2bfb1cd92e30d09bf072f3913fea1ef24f6935bc4058`
- `feature/game/src/main/java/com/adsamcik/tracker/game/data/GameUiRepositories.kt` — `32d5fa0370cbe4db5e60f3a09d48a916b41e445e223a518e6abf21ed82473539`
- `feature/game/src/main/java/com/adsamcik/tracker/game/viewmodel/ExplorationViewModel.kt` — `c29bc44693adb390f2c07fab6c7d552f8f3a0c8d21850f5ee3cb3352120f3b0b`
- `feature/statistics/src/main/java/com/adsamcik/tracker/statistics/presenter/HistoryPresenterViewModel.kt` — `eee2e31ff4bd8e97fa12390ce170f3525d95c600de7346c783d4a1ef49626115`
- `feature/statistics/api/src/main/java/com/adsamcik/tracker/feature/statistics/api/navigation/StatisticsRoutes.kt` — `1b9481059d17e23f68cceb876376f97bb2b18f74454f2b037f0da619b154387e`
