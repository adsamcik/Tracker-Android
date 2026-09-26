# Adaptive collection and user-organized sessions

Date: 2026-09-26. Direction update after the whole-product planning checkpoint `2f8e342eea`.
Status: approved product direction, with implementation details and the decisions below still proposed. PLANNING_ONLY within IMPLEMENTATION_ONLY. No production/test edits or execution, schema generation, activation, integration or push.

## 1. Latest user direction

> Would it be possible to merge the ambient and session sources together and instead design single adaptive system that can independently scale the collections and adapt to any configuration, simply, cleanly, durably, reliable.

> Sessions will essentially ideally just be times of higher fidelity and help users organize data and perhaps designate sessions as activities trips and so on. Sessions can also be added manually afterwards.

This settles the intended role of user-facing sessions: organization plus a live fidelity request, including the ability to create a session retrospectively. The previous two-list source presentation and any assumption that every visible session must originate in a running collection session are superseded as product targets. Exact labels, fields, migration and action behavior below remain proposals under the user's design-approval instruction.

It does not approve active Location escalation between sessions, a particular CONTROL retention duration, new map thresholds, or the other unrelated pending D/P decisions. Default-off separately consented background collection remains in force until deliberately changed. The assembly and validation phase boundary is unchanged.

## 2. One collection system

Use one source configuration surface and the existing revisioned per-source policy and plan resolver. Each source has its own authorized baseline, live-session participation, quality ceiling, useful minimum, capability and actual status. Session demand can temporarily raise its requirement within approved limits. Ending the demand restores the still-authorized baseline; changing an organization label does not restart providers.

The controller chooses supported source-specific mechanisms. It does not assume one universal cadence, one global fidelity level, or identical Steps continuity and live-counter handles. Reuse existing lazy owners, guarded durable ingress, source-local writers, bounded reads and generation-safe retirement. Adaptation uses permitted control evidence or compatible evidence already being collected. Context queries create no acquisition demand.

Migration must preserve the existing capture/background pair and its consent epochs exactly. Existing ambient-purpose data can be collected during a live session, including from a source excluded from captured session membership. Therefore the earlier suggested mapping of ambient-only to "outside sessions only" is withdrawn: that would silently interrupt existing baseline behavior. One source row can expose baseline and live-focus participation without a second source list; the exact compact control is a pending UX design.

The policy resolver already checks source floors and ceilings; a unified screen does not make the incomplete providers, continuity, writer, range, export or recovery work complete. Every supported configuration must produce either a usable plan or a precise source-local limitation while available siblings continue.

## 3. Sessions organize observations

| User action | Collection effect | Data and presentation effect |
| --- | --- | --- |
| Start a session now | Add a temporary fidelity requirement for permitted sources | Create an organizational interval linked to the actual live acquisition scope; show real achieved source quality and coverage |
| End a live session | Release that fidelity demand and return to the authorized baseline | Close the interval; preserve its observations and their original provenance |
| Add a past session | No hardware demand | Select a past interval, optionally name or classify it, and compose already retained qualified records |
| Change a past name, label or interval | No hardware demand | Recompute the organizational view; historical collection boundaries, consent and achieved precision remain unchanged |
| Remove an organized session | Proposed: remove its grouping only; end its live demand first if active | Preserve underlying measurements and refresh affected views |

A retrospective session cannot manufacture missing observations, improve past accuracy, turn baseline observations into past capture authority, or create detected Activity from a user label. It can still display those originally collected observations through qualified range readers. A user-described trip with no eligible Location has no route. A date label or common organizational ID does not authorize new cross-source map joins.

Example: at 18:00, the user creates "Morning walk" for 08:00–08:40. The session shows the retained Steps, radio and Location evidence for that interval, including any gaps. If Location was sparse or absent, that remains visible. The action neither starts a provider nor claims a high-fidelity recording existed at 08:00.

## 4. Smallest proposed storage change

Add a small organization-session metadata record: stable ID, start/end, display zone, optional name and existing activity-type reference, live/retrospective origin, revision, and optional link to an actual acquisition scope. Reuse `SessionActivity` reference data where appropriate. The user's "activities, trips and so on" does not yet require a new taxonomy or tags framework.

Keep the internal logical acquisition sessions, runs, manifests, consent, source identity and immutable writer evidence. They explain how observations were collected and support recovery/deletion; they are not editable organization labels. Do not create fake acquisition runs or overload legacy inferred trip rows for retrospective sessions.

Initially compose contents dynamically through bounded qualified source-range readers. A separate per-observation membership table is not required for simple time intervals. Exact interval/clock uncertainty handling, stable selection tokens, reader bounds and invalidation are part of the concrete contract still to be designed. Adding per-record selection or frozen snapshots would be a new feature, not implied here.

This proposal needs an additive schema change in the single final convergence schema, with migration and reopen test source authored during implementation. No schema generation or test execution is allowed before the assembly freeze.

## 5. Meaning across storage, UI, effects and exports

- **Boundary evidence:** count only Steps that the source can qualify for the interval; do not prorate a batch crossing its boundary. Show incomplete boundary coverage where exact allocation is unavailable. Location points do not prove continuous movement through gaps.
- **Overlapping groups:** proposed to allow overlaps. An observation may appear in multiple organizational views; global totals, count domains and rewards must still deduplicate underlying evidence. Summing session cards is not a valid global total.
- **Corrections and retention:** proposed dynamic contents follow source corrections, deletion and retention. An organization record cannot pin erased data or bypass a retention fence. A retained label with no remaining evidence needs an honest state; exact metadata-retention behavior is a pending privacy detail.
- **Metrics and effects:** user-organized session count and acquisition-run count have different meanings. A newly added label must not automatically grant historical activity, distance or session-count rewards. Existing effect contracts require a named disposition before changing eligibility.
- **Editing/removal:** proposed removal deletes organization only; deleting recorded data is a separate explicitly scoped operation using the existing privacy barriers. Full collected-data deletion must account for the new metadata. Active-session editing cannot rewrite past acquisition authority; the live edit contract needs definition.
- **Export/import:** preserve original source provenance and organizational metadata. An organized interval must not silently export a larger original acquisition session because its source format requires whole entries. Offer a supported exact selection or disclose the whole-entry inclusion before writing. Existing file versions cannot be silently reinterpreted; the metadata format/version and restore of organization require an explicit contract. Imports cannot mint native acquisition authority.
- **History/navigation:** existing recordings should remain discoverable through the unified session presentation without changing their evidence. The migration/backfill or read-adapter choice, stable identities and duplicate prevention need a concrete proposal. Existing calendar/day rules must be reconciled with editable organizational intervals.

## 6. Source evidence and review

Static checks use clean MAIN `dev/v10` at `13f1a99871b59d29b3d059f374fc415490da6943`. Coordinator and Astra Runtime/Data reviewed the relevant contracts; no runtime proof is claimed.

| Current source | Design consequence |
| --- | --- |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/model/SourceDemand.kt:3–23` and `source/coordinator/SourcePlanResolver.kt:53–158,171–179` | Existing per-source requirements, explicit adaptation permission, legal constraints, ceiling and floor checks are the base for adaptive planning |
| `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SourceRuntimeRegistry.kt:106–134` and `SharedWifiSourceController.kt:164–193,287–323` | Lazy source ownership and session/baseline demand joining already exist; extend and reconcile rather than add a competing engine |
| `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/SourceSessionEntities.kt:15–38` | Current logical session is acquisition lifecycle and authority, with plan/clock/cutoff/manifest/run identity |
| `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/SessionSegment.kt:24–29,104–113` | Existing segment links an acquisition run and has a unique service-run index; it also carries metrics and inferred activity |
| `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/data/Trip.kt:5–8` | Trip is a read-only projection, not a lightweight editable organization entity |
| `core/base/src/main/java/com/adsamcik/tracker/shared/base/data/SessionActivity.kt:11–20` | Existing labels are reference data and can be reused without pretending they are recorded sensor Activity |
| `stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultTripRepository.kt:62–64`; `core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/TripDao.kt:129` | Existing trip deletion deletes the legacy `session_segment` row; this is not the new organization-only contract or a source-evidence erase API |

Platform limits remain real: [Android foreground-service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and [Wi-Fi scanning limits](https://developer.android.com/develop/connectivity/wifi/wifi-scan) were checked during the preceding adaptive-design discussion. Exact host/fidelity proposals require source-specific platform confirmation; a source selection does not guarantee an arbitrary sampling rate.

## 7. Remaining decisions and implementation order

The live-session fidelity/organization role and retrospective creation are settled. The following are still proposed:

1. **Active adaptation without a session:** may explicitly consented sources actively increase collection between sessions within their budget, or must the existing passive/opportunistic baseline remain? Recommendation: support explicit bounded active adaptation where legally supported; this expands previous passive Location scope and requires approval.
2. **Overlapping organization intervals:** recommendation: allow, with shared evidence and deduplicated totals. Alternative: prohibit overlap initially.
3. **Removal and dynamic contents:** recommendation: removing a session removes its organization only, and its contents update with source corrections/deletions. Exact metadata retention and removal of a live session must be included before approval/implementation.

After these choices, present one bounded schema/API/UI/export subdesign. Reconcile this change into S0/S3/S6/S8/S9 and the affected D3/P2/P4/P5/P6/P8 decisions rather than adding a second implementation program. Retain unrelated pending decisions unchanged.

Author scenarios for active focus joining/leaving baseline, retrospective creation with no runtime activation, delayed/batched boundary evidence, sparse/no Location, overlap deduplication, correction and source deletion, metadata-only removal, active-session removal, cross-midnight/DST edits, import/export provenance and old-history migration. Then finish the remaining approved source slices, freeze the complete input, perform deferred convergence, and obtain the original requested fresh independent assembled-code review before readiness claims or integration.
