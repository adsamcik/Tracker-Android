# Source lifecycle proof continuation

Status: **IMPLEMENTED_UNVALIDATED; incomplete whole lifecycle item**.

Candidate: `codex/source-native-map-context-root`, reconciled input `dedc7df8f1`.
Main `dev/v10` remains `13f1a99871`. No global convergence freeze or validation transition.

## Original bounded request

> Please update the checklist as things are completed.
> **Complete source lifecycle proof.** Authenticate observation identity, registration, authorization, sequence and product lineage consistently across all six sources.

All earlier user messages and corrections remain in the inherited review context and
`planning/2026-09-26-provider-freedom/TASK_CONTEXT.md`. Source independence, supported automatic
sessions, separately consented sessionless ambient, one physical owner, optional enrichment,
five-level adaptive policy, 48-hour CONTROL retention and selected-format-only export remain
constraints. No new ambient Activity/Pressure collection or preferred provider activation.

## Current source trace

Paths below are repository-relative within this candidate. Each is source evidence, not a test result.

| Source | Observation/carrier authority | Registration and authorization | Materialized and production query lineage | Remaining boundary |
| --- | --- | --- | --- | --- |
| Location | `source/location/LocationWalQualificationAdapter.kt` checks exact event/sequence, canonical bounded complete delivery and per-fix membership | Shared `authenticateHistoricalProviderRegistration`, immutable actual plan, reconstructed demand authorization and policy/consent; now also shared registration proof for ambient | Protected canonical handoff, V4 receipt integrity, accepted physical receipt and tagged purposes; lifecycle session adapter and `RoomSourceFactLookupReader.lookupLocation` authenticate canonical decision/sample | Ambient lifecycle adapter/query still reject the supported purpose. Use common receipt/purpose proof; do not require every ambient sample to have a session canonical receipt. Inactive automatic V2 transport must be completed coherently |
| Steps | `StepsWalCarrierAuthenticator.kt`, exact count-domain owners; source WAL sequence accepts zero, independent of provider counter | Lifecycle `historicalSessionAuthority`, registered interval/plan/policy/consent/manifest/action | Exact fact revision and count-domain owner proof, cursor/lane and production `lookupSteps` | Ambient system aggregates need real mutation/window identity, native registration/cursor/correction and count-domain receipt proof without a fabricated WAL ordinal |
| Activity | `ActivityCapturedWalAdmissionAdapter.kt`, canonical ordered complete delivery and `ActivityWalCarrierAuthenticator` | Historical actual registration plan, post-effective capture authorization and settled provider/session window | Canonical current correction head, window/evidence/carrier lineage, writer/cursor, production `lookupActivity` | Ambient collection remains unsupported by design; retain typed unsupported state rather than adding it |
| Pressure | `PressureWalCarrierAuthenticator.kt`, exact hPa window and separate sensor sequence; WAL sequence now accepts zero | `PressureFactProofAuthenticator.authenticateWal/authenticateLineage` and loaded immutable provider/policy/capture authority | Every retained correction identity, selected evidence versus current head, retained authority/collision/deletion proof; production `lookupPressure` | Ambient collection remains unsupported by design; keep hPa and do not invent elevation |
| Wi-Fi | Captured `WifiWalQualificationAdapter` and canonical carrier; ambient writer now uses bounded WAL/sequence lookup | Session historical provider/policy/consent/manifest/action. Ambient now uses shared callback registration lifetime proof, reconstructs exact demand members and requires observation-time authorization; deletion highwater blocks surviving WAL replay | Captured minimized aggregate/coverage owner/cursor and production `lookupWifi`; ambient source-local facts have their existing authority/retention/cursor | Ambient carrier and per-item historic qualification still need completion, including mixed ages/join/revoke. Ambient lifecycle and production presentation routing remain absent; immutable V2 transport incomplete |
| Cell | Captured `CellWalQualificationAdapter` and shared canonical carrier; ambient writer now uses bounded WAL/sequence lookup | Session historical provider/policy/consent/manifest/action. Ambient now uses shared callback registration lifetime proof, reconstructs exact demand members and requires observation-time authorization; deletion highwater blocks surviving WAL replay | Captured minimized aggregate/coverage owner/cursor, subscription completeness and production `lookupCell`; ambient source-local facts have their existing authority/retention/cursor | Same ambient per-item/carrier/routing and immutable V2 transport boundaries as Wi-Fi; preserve unsupported SIM/platform states |

Engine paths are under `tracker/engine/src/main/java/com/adsamcik/tracker/tracker/`.
Shared carrier, registration, receipt and count-domain authorities are under
`core/base/src/main/java/com/adsamcik/tracker/shared/base/database/`.
Production query paths are under
`stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/`.

## Completed bounded changes

1. `9ad1a05b1a`: corrected all located positive-only WAL sequence predicates in Activity,
   Steps and Pressure, including Activity fact construction/admission and Steps ownerless
   maintenance. Positive admission ordinal, registration generation and sensor counters remain
   distinct. Added zero and negative carrier cases, zero Activity admission/delivery and zero
   ownerless Steps pruning test sources.
2. Ambient Location now uses the same historical provider lifetime proof as captured Location,
   including current boot, residency, exact identity, wall/elapsed boundaries and data epoch.
   Authorization lookup is bounded. Added inconsistent acceptance-wall and open prior-boot cases.
3. `0b177a0d21`: Ambient Wi-Fi/Cell bound WAL payload reads to existing canonical codec limits, check exact
   nonnegative source sequence identity, fence deleted admissions, reconstruct authorization
   from exact demand rows and compare the observation-time authorization snapshot. Three
   authored rejection cases per source cover rehashed negative sequence, surviving deleted WAL
   and corrupted demand persistence authority. Fixture authorization now uses the production
   row builder and its actual fingerprint. Both now also use the shared callback registration
   lifetime proof with current boot, exact process residency and wall/elapsed lifetime. Wi-Fi
   fixture wall times now use milliseconds rather than elapsed nanoseconds. Two additional
   authored cases per source cover invalid acceptance wall and prior-boot open registration.
4. The lifecycle coordinator supports requested source subsets and empty tracking without
   invented keys or unrelated adapter activation. Stage transitions reject substituted evidence,
   admission, regressing revision, query fact/coverage/completeness and data epoch. Authored
   tests cover all 64 subsets, duplicate keys, substituted query fact/epoch, wrong adapter source,
   substituted recording evidence, materialization evidence/admission/revision and no database
   subscription for empty observation.

## Pending contract confirmation

The question tool asks whether the existing shared proof API should explicitly represent native
ambient Steps provider aggregates using their real mutation/window, registration and revision,
with no WAL admission number. Recommendation: support the truthful aggregate identity in the
shared proof contract, with the absent WAL field constrained to that native aggregate variant.
Alternative: keep aggregate Steps proof separate. No response is assumed from elapsed time.
Repository AGENTS.md requires confirmation of new data-contract/architecture decisions.

This pending choice does not authorize invented sessions or synthetic admission ordinals. Existing
Steps opaque product/scope identity is `ambientFactScopeIdentity(logicalFactId)`; existing exact
count-domain owner is `OWNER_AMBIENT_FACT` with writer/version/logicalFactId/semanticRevision.

## Review and verification boundary

A fresh independent review-only subagent received the original full conversation, approved
decisions, exact current code/diff, test sources and implementation-only restrictions. It found
the ambient routing, radio lifetime/per-item, zero-sequence and native aggregate identity gaps
above. The first slice was accepted after correcting Activity completeness.lastSourceSequence
to the real zero-based tail. Radio review identified placeholder authorization fixtures; those
were replaced with the production authorization row builder before final rereview.

Final bounded rereview found no remaining blocker in the radio/coordinator changes or additional
registration slice. It confirmed that the added tests target rejection paths before downstream
work, empty observation checks subscription avoidance, constructor sites carry the fixture boot,
and the Wi-Fi fixture wall-time correction matches the shared authenticator. This acceptance is
limited to these source changes; it does not close the remaining ambient or immutable-generation
work above. Review-only agent `/root/lifecycle_proof_review` did not edit, delegate or run checks.

No Gradle, compiler, tests, lint, Detekt, Room schema/drift, diff-check, emulator/device, UI,
battery, CI, release or rollout checks ran. A source trace and authored checks do not establish
runtime reliability. The complete lifecycle item, assembly gate, validation, activation and
`dev/v10` integration remain unchecked. Original refs/drafts and six protected paths are preserved.
