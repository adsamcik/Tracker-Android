# Tracking implementation assembly — 2026-10-16

## Current status

**The tracking-infrastructure implementation assembly gate is COMPLETE for this source input.** Production and required test source are assembled, all 25 prerequisites have an independent source trace, and preserved drafts have explicit dispositions. This closes source assembly; full host validation, Android evidence and final integration remain below.

[Exact source input](FINAL_SOURCE_INPUT.json) records the candidate commit, 1,352 changed source/build/test states, schema 30 (215 entities), selected repairs, capability limits and test inventory. [Input dispositions](INPUT_DISPOSITIONS.json) retain 55 refs, 363 candidate-only commits, 56 original drafts, 2,122 transport artifacts and 669 earlier draft manifests. The final 106 predicate cleanup is recorded in the source input. No historical draft or branch was deleted.

[Independent review](SOURCE_ASSEMBLY_REVIEW.md) maps all 25 prerequisites to current production and authored tests. Its original 101-file receipt remains byte-exact; the later Cell predicate cleanup received a separate independent review with no findings. Static review establishes assembled source and scenarios, not passing tests or device behavior.

## Completed source work

- [x] Independent selected-source setup, shared providers, manual/automatic/all-day composition and five-level adaptation.
- [x] Steps continuity and all-day totals; independent Location outcomes and atomic passive delivery; per-reading radio qualification/details, budgets and lifetimes.
- [x] Source-aware controls, permissions, retained history, sessions, details/actions, summaries, optional map context and effects.
- [x] Typed source files, session bundles, selected-date/full-session scope, correction/overlap handling, data-only logical backup/restore and transfer cleanup.
- [x] Released-main/schema-12 migration source, final schema, recovery and integration scenarios.
- [x] Exact input/draft disposition and independent all-25 source review.

The latest repairs prepare automatic Activity control independently of recorded Activity, isolate automatic failure before all-day reconciliation, preserve source-owned writer handoffs, connect Dashboard Steps/Pressure to existing details/actions, and correct stale source-layout tests. Radio files now use Kotlin JSON serialization with real legacy v2/v3 compatibility.

## Actual host evidence

**Full61 passed the unfiltered `:app:assembleDebug ciCheck --continue` gate in 10m 34s.** ciCheck includes ciUnitTest; APK/Hilt, release lint, Room schema drift and release SQLite checks pass. All 1,363 frozen states and HEAD stayed unchanged. The [final host result](FINAL_HOST_RESULT.json) pins the source, actual receipts and debug APK.

The 29 test tasks contain **15,667 tests, zero failures/errors and seven existing skips**. Full61 freshly executed import/export (843) and statistics/data (1,580); all other current reports match successful unchanged full58/full60 inputs by hash. No report is unaccounted for. Full60 freshly passed all 4,278 engine tests and all 30 native Location-summary cases, including the two selected-date regressions. Released-main/schema-12 import/reopen/retry, final assembly migration and radio-child migration passed seven scenarios.

Full60 exposed two `AbstractMethodError` failures because unchanged generated test delegates retained an older DAO method signature. Rebuilding only their two owning test-compilation tasks and normal ASM transforms corrected all six raw/transformed classes. Full61 passes without changing source, assertions, deadlines, native SQLite, quality configuration or baseline. Detekt has zero findings. Earlier execution history remains in the dated build/storage log and preserved receipts.

Seven existing skips remain explicit: two Windows FileProvider cases, one host symlink case, three Android map/permission placeholders and one unavailable optional GPX dataset. They are not counted as passing evidence.

## Remaining acceptance and delivery

- [x] **T26:** Pass the full unfiltered build/Room/migration/quality gates on this combined input and repair concrete failures. ciCheck includes ciUnitTest. Reuse valid unchanged results.
- [ ] **T27 (user-deferred):** Use the highest stable Android API already available in the existing configured environment. Obtain bounded activation authority and record actual provider → saved data → UI/file → Stop, permission/process/reboot/foreground-service, rendered accessibility and battery/quality behavior. Create no AVD, clone or older-API matrix.
- [x] **T28:** Fresh independent final review of the combined code, actual host results and explicitly deferred device boundary; no remaining findings. See [final acceptance](FINAL_HOST_ACCEPTANCE.json).
- [x] **T29:** Rebased and fast-forwarded into clean local dev/v10 at `f294192264d6f8186ccc2b737fc4905cf61c174f`; integrated source tree and all 1,352 source states match the tested input after existing checkout line-ending normalization. No push or deployment. Original refs/drafts and unique evidence are retained.

No Android device or configured AVD was available at the final recheck. The user explicitly answered “We can test it later.” Device acceptance is deferred; no target was created or provider activated.

## Product rules and limits

Sources participate independently; sessions organize the same data and prefer quality only for participating sources. Available partial/sibling/history data remains usable. Location enrichment reads existing qualified data without starting Location. Activity/Pressure all-day recording is unsupported; platform permissions/provider availability and Android scheduling still apply.

History uses measured UTC time on the displayed local day. Exports select measured data, optionally include complete matching sessions, and keep indivisible real windows whole. One Wi-Fi or Cell file can contain its session/background readings and merge overlapping imports. Location replacement uses one current correction. Backups reimport data into existing databases, exclude settings/schedules and need no restart. Launcher widgets and the declined exploration-period lookup remain removed.

Historical TODO/plan rows retain their original checkpoint evidence. This source assembly record supersedes their claims that the current implementation is absent or that every authored test is unexecuted. It does not claim full runtime acceptance.
