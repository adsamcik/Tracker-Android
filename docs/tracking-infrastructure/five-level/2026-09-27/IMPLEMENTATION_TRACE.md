# Five-level configuration implementation trace

Date: 2026-09-27. Candidate: `codex/source-native-map-context-root`, starting at `1d77cce2d1`.
Status: **IMPLEMENTED_UNVALIDATED**. Independent review: accepted; see REVIEW_RECEIPT.md.

## Requirement

> **Complete five-level configuration across sources.** Separate participation from quality,
> preserve legacy/custom settings, and apply live-session quality preference only to participating sources.

Approved input: `../../planning/2026-09-26-provider-freedom/FIVE_LEVEL_ADAPTIVE_POLICY.md`, with
newer Location targets in ADAPTIVE_LOCATION_IMPLEMENTATION.md. The complete original user packet
and latest correction are preserved in TASK_CONTEXT.md in that folder.

## Configuration and useful contracts

Quality is independent of source participation and background/helper consent. Levels are Power
first, Favor power, Balanced, Favor detail and Detail first. New setup defaults to Balanced (3).
Existing configurations keep legacy/custom behavior until explicit profile adoption. Adoption
preserves source masks, old frequency vector, numeric custom controls, accuracy filters and
purpose consent; it explicitly changes collection targets. Earlier explicit Location adoption
survives first overall adoption as its source override. Each override can remain saved while its
source is off; null inherits the overall preference.

| Source | Five baseline targets, levels 1 through 5 | Stable useful contract |
| --- | --- | --- |
| Location | 60 / 30 / 10 / 5 / 1 s, zero displacement | 120 s age/delivery, low-power capability floor; levels 3–5 high accuracy where available |
| Activity | 60 / 45 / 30 / 15 / 5 s classification delivery | Confidence 65, 120 s freshness/delivery; sampled coverage ends at cadence, never at freshness horizon |
| Steps | 300 / 120 / 60 / 15 / 5 s report; 60 / 30 / 15 / 5 / 2 s checkpoints | Direct-counter floor and 300 s usefulness; native ambient history retains its own cursor contract |
| Pressure | 1 / .5 / .2 / .1 / .05 s sample; 60 / 30 / 10 / 5 / 1 s report; 60 / 30 / 10 / 5 / 2 s aggregate | 60 s useful floor |
| Wi-Fi | Callback at 1–3; authorized finite attempts at 4–5 spaced at least 120 / 60 s | Callback floor; 10 min age/dedupe |
| Cell | Callback at 1–3; authorized finite refresh at 4–5 spaced at least 300 / 120 s | Callback floor; 10 min age |

Only actual accepted live participants request max(saved level, 4); saved level 5 remains 5.
A background-only sibling receives its baseline. Session end restores baseline. Retrospective
labels create no live demand. Availability, safety and existing finite attempt budgets still
constrain physical acquisition. Ambient radio remains callback-based with independent QoS-zero
purpose demand and its saved baseline profile, never capture quality/consent borrowed from a
session. CONTROL semantics and native aggregate ambient Steps mechanisms remain unchanged.

## Source trace

All paths below are repository-relative to this candidate.

| Boundary | Concrete implementation |
| --- | --- |
| Saved settings and legacy absence | `data/preferences/.../tracking/SourceBalanceSettings.kt`, TrackingParamsState.kt, TrackingParamsRepository.kt, DefaultTrackingParamsRepository.kt, `data/preferences/src/main/proto/tracking_params.proto` |
| Atomic six-source policy and effective mirror | RoomSourcePolicyRepository.kt, SourcePolicy.kt, AuthoritativeTrackingParamsRepository.kt in that preference package |
| Additive durable metadata | `core/base/.../database/data/SourcePolicyEntities.kt`, SourceSessionEntities.kt; AppDatabaseMigrations.kt and DevelopmentV28DatabaseContainment.kt in database package |
| Desired targets and useful floors | `tracker/engine/.../source/coordinator/SemanticAcquisitionPlanFactory.kt`; `source/model/SourcePlan.kt`, SourceAcquisitionFloor.kt |
| Accepted participant set | Existing `tracker/engine/.../service/DefaultTrackingStartRequestCoordinator.kt` and TrackerService.kt supply actual live sources; TrackerServiceSourceSession.kt uses adopted participation rather than dormant old frequency OFF |
| Resolution and durable demand | SourcePlanResolver.kt and AuthoritativeSessionCoordinator.kt in source/coordinator; `source/runtime/SourceBroker.kt` |
| Policy ceiling | AuthoritativeSessionCoordinator.kt authenticates baseline/focus and rejects stronger cadence, hidden Steps low latency or weaker radio backoff |
| Desired/applied/retry identity | RoomSourcePlanStore.kt and SourceSessionPlanFingerprint.kt in source/coordinator; TrackerServiceSourceSession.kt catalog-debt reconstruction carries exact level/focus against current policy and participant set |
| Activity contract lineage | SourcePlanCodec.kt format 3; `source/runtime/ActivitySourceRuntime.kt`; ActivityCapturedObservationAdmission.kt and ActivityCapturedFactWriter.kt in source/activity; core ActivityCapturedFactIntegrity.kt and ActivityCapturedPortableTransfer.kt |
| Historical radio/portable reconstruction | `source/wifi/WifiCapturedFactMaintenance.kt` reconstructs capture/ambient contracts from immutable profile policy; core CellCapturedPortableTransfer.kt authenticates exact manifest policy and rejects unsupported/incomplete profile metadata before age mapping |
| Honest settings/setup display | `app/.../settings/TrackingSettingsViewModel.kt`, tracking/SourceBalanceSettings.kt, TrackingSettingsScreen.kt and TrackingStatusComponents.kt; `app/.../onboarding` draft/ViewModel/route/HowToTrackStep; locale-safe new active-change notice (old translated restart key is no longer used here) |

Legacy frequency/QoS codes are never five-level codes. Old payloads keep their canonical bytes:
Location remains formats 1/2; new Activity profile is format 3 and both core decoders authenticate
its exact canonical bytes. Other concrete plan payloads keep format 1. Plan metadata is stored
separately and checked against the immutable historical policy, including disabled/failed desired
rows. Cancellation during recovery remains cancellation. Generated v28 Room schema JSON is deferred.

## Focused test sources authored, not executed

- FiveLevelSourceConfigurationTest: 5 × 64 source subsets, live start/end, off overrides, solo focus,
  five target tables, stable useful floors and unchanged legacy custom/codec behavior.
- TrackingSessionOwnershipStressTest: actual ownership for the same 5 × 64 selection matrix even
  while the preserved old vector is OFF.
- DefaultTrackingParamsRepositoryTest, RoomSourcePolicyRepositoryTest and
  AuthoritativeTrackingParamsRepositoryTest: durable overall/overrides, consent/history preservation,
  mixed metadata rejection, stale compatibility mirror and recreation/toggle behavior.
- RoomSourcePlanStoreTest: exact desired/applied metadata across recreation, collision rejection and
  corrupt requested-level rejection. AdaptiveLocationPlanFingerprintTest: overall/override/focus fences.
- TrackerServiceSourceSessionTest: adopted catalog retry preserves exact level/focus and identity.
- SourceBrokerTest and AmbientRadioSourceBrokerTest: immutable six-source capture contracts and
  independently consented sessionless ambient baseline callback contract.
- SourcePlanResolverTest and AuthoritativeSessionCoordinatorTest: unknown/stationary restoration,
  permission blocking, preserved rates and rejected low-latency/backoff policy substitutions.
- ActivityCapturedObservationAdmissionTest: new delayed classification accepted within 120 s with
  cadence-bounded coverage, confidence threshold, legacy rejection and both plan decoders.
- WifiHistoricalBalanceContractTest: actual portable historical reader across all levels, old
  Responsive QoS age, new fixed age, corrupt profile/missing consent, independent ambient authority.
- CellCapturedFactMaintenanceTest: full portable zero-callback settlement with explicit profile and
  fixed age, plus unsupported-marker rejection even when legacy QoS 1 has the same age.
- SetupViewModelTest, SetupViewModelCompletionRobolectricTest, HowToTrackStepTest,
  TrackingSettingsViewModelTest and TrackingSettingsScreenTest: all five setup/settings choices,
  saved drafts/old draft migration, choice isolation, overrides while off and accurate active notice.
- AppDatabaseMigration27To28Test and DevelopmentV28DatabaseContainmentTest: additive absent/default
  metadata and final-schema sentinels. These source fixtures do not substitute for schema generation.

## Independent review and delivery boundary

Reviewer `/root/five_level_review` is review-only, has the full original user packet, approved
inputs, exact source diff and source-only evidence boundary. No recursive delegation. Review
corrections include concrete version matching, Activity cadence versus freshness, full metadata
reconstruction, cancellation, policy-envelope dimensions, status labels, setup selection isolation,
ambient baseline mapping and rejection of invalid historical radio profile markers.

Final independent source review found no remaining material finding in this slice. See
REVIEW_RECEIPT.md. The whole tracking assembly, shared-item radio admission contract,
ambient aggregate Steps proof contract, backup restore decisions, convergence freeze and all
runtime/device/activation/integration gates remain open. No protected paths were changed. Main
`dev/v10` remains at `13f1a99871b59d29b3d059f374fc415490da6943`. This trace is a source-implementation
receipt, not proof that authored tests pass or that Android providers work on a device.
