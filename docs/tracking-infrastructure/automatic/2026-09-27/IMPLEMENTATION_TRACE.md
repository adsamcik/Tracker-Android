# Automatic tracking integration trace

2026-09-27. Candidate `codex/source-native-map-context-root`, base `047423c6a3`.
**IMPLEMENTED_UNVALIDATED bounded corrections — full item remains open.**

## Shared flow and authority

1. `sensor/activity`'s existing Transition receiver/registration arbiter owns Activity delivery.
   `AutomaticStartTransitionMonitor` creates separately authorized sessionless Activity CONTROL
   demand, accepts Transition control only and compensates rejected registration. Continuous
   captured Activity does not confer a cold-start callback exemption; no Steps corroboration or
   other source is started merely to authorize Activity.
2. `ActivityAutomationProjection`/`ActivityAutomationOutboxDispatcher` qualify CONTROL evidence,
   automation epoch and the live callback start context. `ActivityAutomaticStartActionRepository`
   reserves/checks the exact immutable trigger/action before the Android enqueue; service enqueue
   is not durable lifecycle acceptance. Historical outbox drains cannot invent a fresh permit.
3. Manual and automatic use `DefaultTrackingStartRequestCoordinator`, then `TrackerService`,
   `TrackerServiceSourceSession` and `AuthoritativeSessionCoordinator`. Requested source selection,
   accepted per-origin capabilities, exact foreground envelope, manifest, run and writer authority
   remain distinct and immutable. Provider ownership and adaptive collection are shared. There is
   no new automatic-specific collector or canonical writer in this slice.
4. Manifest CONTROL members and SESSION_CAPTURE members are separate. Only independent capture
   authority can produce captured Activity facts; CONTROL alone is not normal history or portable
   product data. Short trigger validity is distinct from the approved 48h raw CONTROL lifetime.
5. Existing continuation grace cancels on compatible/user-session callbacks and before a fresh
   automatic start from an inactive runtime. Interrupted automatic descriptors cannot redeliver
   into a resumed automatic session. The grace timer does not pin an exact session identity, but
   review did not establish a reachable successor-stop regression under the existing paths. Do
   not add a speculative stop API; retain this seam for the whole lifecycle convergence review.

Engine paths above are under `tracker/engine/src/main/java/com/adsamcik/tracker/tracker`, with
coordinator, runtime, projection, service and api packages. Existing Hilt/provider/Room boundaries
and dependencies are unchanged.

## Authored corrections

| Correction | Source | Authored regression coverage |
| --- | --- | --- |
| Setup preserves selected automatic mode even when permission restricts operation | `app/.../onboarding/ui/SetupViewModel.kt` | Declined background Location keeps selected mode; denied Activity keeps mode and no-op legacy watcher poke remains disabled. |
| Permission opportunity follows successful saved intent | `app/.../settings/TrackingSettingsViewModel.kt`, `settings/tracking/TrackingSettingsScreen.kt` | Callback observes saved mode; a superseded pending save cannot request permission for an old choice. Monotonic process intent generation is checked around suspended exact-authority lookup and before launch; UI must still be resumed. |
| Late permission result refreshes capability rather than restoring old intent | same ViewModel | Denial preserves requested mode; grant after Off/newer movement choice cannot rewrite it; a stale true grant result does not override actually revoked permission. Existing policy reconciliation is retried without changing intent/consent. |
| Denied Location cannot veto a runnable Steps sibling | `tracker/engine/.../api/BackgroundTrackingApi.kt` | Location-only denied remains ineligible; Location+Steps with Steps available is eligible without changing saved Location choice; neither available is ineligible. Existing exact requested/accepted-mask/FGS tests already cover requested Location+Steps and accepted Steps. |

The eligibility helper is a preliminary check. The actual per-origin accepted mask, service type,
immutable trigger envelope and service preparation still reject unsupported capture; this does
not claim radio background starts are legal merely because the preliminary helper allows them.
Source selection or permission result cannot lend consent backward or enable an unselected source.

Test sources are authored in SetupViewModelCompletionRobolectricTest, TrackingSettingsViewModelTest
and BackgroundTrackingApiLogicTest; uncompiled/unrun. The older planning paragraph saying 24h was
corrected to the already-approved 48h. No new retention behavior was implemented by that edit.

## Current unsupported or unwired execution

- `registerAutomaticControlOwner` has no production caller. Purpose publication remains fail-closed
  with no owner, despite existing exact lease/demand/publication interfaces.
- `RoomSourcePolicyRepository` rejects persistent CONTROL eligibility; the retention authority
  producer currently covers ambient purposes, not a fully implemented CONTROL TTL. The source
  provider catalog still contains Activity CONTROL under the old AUTO_005 unresolved reason. A
  settled duration is not proof of expiry/admission/maintenance/export enforcement. Removing a
  containment marker alone would incorrectly activate incomplete behavior.
- `AutomaticStartTransitionMonitor` rejects Transition-off configurations. Settings operational
  status currently omits that check and actual provider availability; the repair switch is gated
  by operational status. The ordinary unavailable card still exposes technical reason codes and
  stale "until approved" copy. Setup recommends automatic without actual capability. Concrete
  capability presentation remains pending confirmation, not complete in this checkpoint.
- `ExecutableSourceLaneCatalog` in TrackingRolloutStateStore keeps Steps generation1 manual-only
  and generation2 manual+automatic. Activity/Pressure/Wi-Fi/Cell selected candidate base bindings
  remain manual-only. Pressure/radio coordinator bindings additionally reject automatic modes.
  Extending these needs immutable source/writer contracts and traced admission/query/transfer,
  preserving retained manual history. Preserved alternate V2 drafts are not adopted wholesale.
- Existing mode filtering exposes blocked requested siblings in source status while allowing a
  supported accepted automatic sibling. Manual availability does not imply automatic capability;
  captured Activity manual-only support is separate from the CONTROL trigger's provider.
- Location's protected path and native candidate lifecycle/product proof remain separately gated.
  This trace is not a complete six-source automatic lineage or device evidence receipt.

## Remaining work and evidence

Confirm detailed capability and CONTROL owner composition in CONTRACT_DECISIONS.md; then implement
the bounded missing owner/TTL paths and source-specific automatic bindings with required test source.
Recheck full only-X trigger-to-provider-to-product behavior and shared-purpose revocation/recovery.
Do not activate a provider/writer or claim reliable Android operation from an authored test.

Independent bounded static review is recorded in REVIEW_RECEIPT.md. No Gradle, compilation,
tests/lint/Detekt/schema/diff-check/device/UI/battery/CI/release/rollout gates, push, activation or
dev/v10 integration performed. The assembly gate and deferred final validation remain open.
