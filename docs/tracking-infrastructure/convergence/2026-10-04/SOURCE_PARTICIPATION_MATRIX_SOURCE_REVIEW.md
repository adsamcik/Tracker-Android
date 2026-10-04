# Source participation matrix: independent static review

## Result

**ACCEPTED_SOURCE_ONLY.** No confirmed source or expectation blocker in the frozen four-method test slice against committed production `d2bff19a88ba5224c4dd2e883fd313163c4a17dc`.

This is test-source authorship and static review in the **IMPLEMENTATION_ONLY** phase. Nothing was compiled or executed. It is not provider activation, storage/UI/permission-dialog evidence, assembly completion or integration readiness; all 25 full-chain prerequisites remain open.

## Exact inputs

Frozen test: `tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/SourceParticipationMatrixTest.kt`  
SHA256: `a5fd296d8f810d4368627731ff5aa444044f5fb419f23066795720d0a13dd5bf`

Production Git objects at the commit above:

| Repository path | Git blob |
| --- | --- |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SemanticAcquisitionPlanFactory.kt | 06212f317244eb12913c493fad35e1690c423151 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePlanResolver.kt | 6015891ecf3f518cc71ba301b3cb52fb1296fd93 |
| data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/SourceBalanceSettings.kt | 6ffc9a5c5268afec6e9c4dce331eb06fc24375f7 |
| data/preferences/src/main/java/com/adsamcik/tracker/shared/preferences/tracking/TrackingParamsState.kt | c03d9dec947637132dcfffabe3637ec0f27b060b |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/model/SourcePlan.kt | 273e47053a8146a6362417869d3f903b478cde83 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/control/CollectionMotionPolicy.kt | c6037028fc91379dd61a14d27412a820e081a9fc |
| tracker/engine/build.gradle.kts | 9a3eb6a544cc9347d26f9f866948e6349c77c182 |
| build-logic/convention/src/main/kotlin/com/adsamcik/tracker/buildlogic/AndroidFeatureConventionPlugins.kt | 871b0c3cb28b0d145ffd75f56d87c40e19bae4db |
| gradle/libs.versions.toml | 57d466f436b7be7ac61225fe4c5930cace5c8b7c |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/FiveLevelSourceConfigurationTest.kt | a78e6a7668e9e111d20e159b727e8cb949aa35ee |

## Requirements and source reasoning

The user's approved design separates participation from quality, supports independent chosen sources, preserves selected choices when permissions are unavailable, and applies live-session quality preference only to participating sources. The adopted profile deliberately uses six selected-source toggles despite obsolete frequency values of OFF.

The factory supplies all six immutable plans and preferences, including disabled sources. `captureParticipates` uses the selected toggle for the adopted profile; the test's OFF legacy frequencies therefore exercise the intended compatibility boundary. Location's compatibility profile and balance fields are set consistently with its source override. The all-off case is valid planning input.

The neutral resolver context has no demands, motion reductions, power restrictions or source constraints. It must preserve the desired plan exactly. A single missing permission disables that source only and emits its existing PERMISSION_MISSING reason without a demand-floor failure. Keeping `resolved.desired` and rebuilding from unchanged settings correctly checks that resolution does not mutate the choice.

An individual live-focus source gets at least level 4 only if selected; the other five plans remain unchanged. The immutable baseline recreation verifies returning to the same desired configuration. Each source override changes only that source's requested level; unselected plans stay disabled and sibling plans/preferences remain identical.

JUnit 4 and Kotest assertion dependencies exist through the module's existing test convention. The assertion style is already used by committed module tests. No new dependency, production behavior, persisted setting or abstraction is introduced.

## Authored coverage and practical limits

The four methods contain these **NOT_RUN planning configurations**, not executed whole-chain scenarios:

- 320 baseline subset/level combinations (64 subsets, including all-off, times five levels).
- 1,920 individual live-focus configurations, each followed by immutable baseline recreation.
- 960 selected-source permission-block configurations.
- 1,920 individual source-override configurations.

The baseline overlaps the existing five-level factory tests, while adding neutral resolver equality. The individual-focus, permission-isolation and sibling-override loops add distinct coverage with a small local helper.

The live and override assertions establish requested quality metadata and source independence. They do not independently prove the changed source's physical cadence; existing cadence/floor tests cover those mappings. “Leaving” here recreates an immutable desired plan and does not prove runtime retirement or provider removal. Empty neutral demands do not exercise real purpose ownership, demand floors, automatic/ambient eligibility, registration, optional enrichment, request budgets, retention, storage, foreground services, or UI. Those limits should remain explicit in the checklist.

No extra test or production change is required to accept this narrowly stated planning slice.
