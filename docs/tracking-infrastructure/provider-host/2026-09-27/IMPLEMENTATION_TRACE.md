# Shared provider and host ownership continuation — 2026-09-27

Status: bounded source slices IMPLEMENTED_UNVALIDATED; whole requested item OPEN.
Candidate: codex/source-native-map-context-root, parent b23b496e2f. Main dev/v10: 13f1a99871 unchanged.

## Current code trace

| Source | Physical acquisition owner | Purpose lifetime |
| --- | --- | --- |
| Location | Singleton SharedLocationSourceController -> LocationSourceRuntime | Exact capture and separately consented baseline share a registration; capture close preserves baseline. Temporary feature adapter still absent. |
| Activity | Singleton ActivitySourceRuntime -> DefaultActivityRegistrationArbiter | Capture, CONTROL and optional motion assistance share one arbiter; changed physical requests now retire the exact predecessor before successor apply. |
| Steps | Singleton SharedStepSourceController -> StepSourceRuntime | Direct session counter; system history baseline is independently selected and uses separate count domains. No always-on direct ambient sensor. |
| Pressure | Singleton PressureSourceRuntime | Supported capture only; existing actor/retirement fences retained. |
| Wi-Fi | Singleton SharedWifiSourceController -> WifiSourceRuntime | Exact capture and app:ambient:wifi join; ending capture retains lawful passive delivery, not scan entitlement. |
| Cell | Singleton SharedCellSourceController -> CellSourceRuntime | Exact capture and app:ambient:cell join; callback-only baseline retains selected subscriptions independently. |

SourcePipelineModule and SourceRuntimeRegistry bind all six singleton owners. This is static wiring
evidence, not actual hardware exclusivity. Remaining per-request UI/game providers and permanent
SunSetRise passive callback prevent claiming global Location exclusivity.

## Authored changes

1. Candidate claims are installed before capture-demand selection, matched by logical ID/service
   run/lease generation, and rolled back on no authority, storage exception or apply failure.
   Foreign disable/cutoff cannot release the current logical owner. Claimed close awaits the
   existing typed receipt within a 3s drain cutoff and retains logical debt if incomplete.
   Location failed physical successor compensation remains exact-claim addressable.
2. Radio cancellation join is finite. An unsettled predecessor retains actor, registration,
   claim, original stop receipt and exact provider-completion state. Retries cannot return a
   synthetic unavailable-complete receipt or start a replacement while it is still running.
   Wi-Fi settlement is noncancellable like Cell; cancellation propagates after settlement.
3. Activity reconfiguration retires/removes before reservation/provider replacement. Removal
   failure leaves an explicit retryable acquisition gap; no concurrently installed successor.
4. Prepared capture authenticates its source-only FGS mask separately from the actual host union
   and exact source membership. Background Location does not reject solo Steps/Pressure capture
   or grant captured Location consent. Existing API/type/permission policy remains unchanged.
5. TrackerParticipation derives live membership from sessionInfoFlow, and physical hosting from
   existing service publication. Session UI/Start/End/history/widgets/shortcuts/automatic decisions
   use membership. Baseline-only host acceptance publishes actual hosting. Ending capture clears
   its metadata while retaining the acknowledged host. Deletion keeps durable STOP authority and
   waits only for live presentation cleanup. Capture writers still require both live membership
   and physical host availability. The legacy read-only game feed rejects baseline-only hosts;
   its existing capture-wide Location capability/freshness limitation remains pending the adapter.
6. Existing host promotion is bounded by the shared pre-foreground budget from ticket creation,
   including authority reads and main dispatch. Exact owner and ticket checks precede promotion.
   A second service cannot steal attachment, and cancellation of an unaccepted concurrent request
   cannot release a separately accepted request with the same identity. The delayed service release
   callback rechecks the current acknowledged host before stopSelf, preserving a successor accepted
   during main dispatch (exact static service trace; no focused service harness exists for this race).

## Authored acceptance sources

SharedAmbientRadioSourceControllerTest, SharedStepSourceControllerTest and
SharedLocationSourceControllerTest cover foreign identity/disable, claimant preservation and
acknowledged close; WifiSourceRuntimeTest/CellSourceRuntimeTest add noncooperative admission
retirement/replacement fences. Activity arbiter test sources cover strict predecessor-first
success, failed successor gap and pending removal blocking. ForegroundServiceTypeCandidatesTest
covers API28/29/33/34/37 host unions and consent separation. Controller/feed, Dashboard history,
writer quiescer, host and orchestrator sources cover baseline-only state, physical host loss,
retained independent hosting and promotion deadline/concurrent-ticket behavior.

These test sources have not been compiled or executed. Static review corrected source-level imports,
undefined helper calls, stale fixtures and physical host consumers; no runtime claim is made.

## Remaining work and decisions

CONTRACT_DECISIONS.md records explicit questions for final radio host representation and typed
feature Location/sunrise behavior. No answer inferred; no dependent preference/policy/schema,
provider feature lease or host expansion is implemented. Whole host ownership stays open.

Existing unclaimed compatibility entry points are retained; production claimed cleanup is improved,
not a claim that every legacy teardown path carries a claim receipt. Radio durable power budget,
shared per-item WAL contract, aggregate Steps proof and full backup restore restrictions remain
separately unresolved. Provider/writer cutover stays default-off. No Gradle, compile/test, lint,
Detekt, schema drift, diff-check, emulator/device/UI/battery, CI/release/rollout, push or integration
was run. Final convergence freezes the complete set before the single validation batch.
