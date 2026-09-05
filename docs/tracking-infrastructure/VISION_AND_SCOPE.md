# Tracking Infrastructure Vision and Scope

Last updated: 2026-09-05

This document explains, in product language, what Tracker's tracking-infrastructure program is
trying to achieve and where its boundaries are. It is the stable north star, not an implementation
status report or a replacement for the detailed architecture.

- The authoritative technical design is
  [`../TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md`](../TRACKING_INFRASTRUCTURE_FINAL_PLAN_AND_DESIGN.md).
- The acquisition and battery design is
  [`ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md`](ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md).
- Current progress and reproducible evidence are in [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md),
  [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md), and [`VERIFICATION_MATRIX.md`](VERIFICATION_MATRIX.md).

## The outcome

Tracker should reliably turn the sources a user chooses into useful, understandable, local history.

> **In one sentence:** Collect every fresh fact the user directly authorized, let each source remain
> useful by itself, reuse compatible facts that already exist, and spend battery only where it buys
> measurable product quality.

Location, Wi-Fi, Cell, Activity, Steps, and Pressure must each be useful when it is the only captured
source. Enabling one source must not silently enable another captured source. If automatic operation
needs a control source, that dependency must be visible and must remain separate from captured
history.

Collection is not complete when a service starts or a callback fires. It is complete when fresh,
authorized evidence is durable, correctly classified, converted exactly once in effect into its
source product, returned by the production history path, and represented honestly to the user.

The redesign should improve useful tracking quality per unit of battery. It is not enough to retain
today's quality behind a more complicated architecture, and it is not acceptable to save power by
silently making the user's only selected source ineffective.

## The promises to the user

### Every source stands alone

A user may select any one source and disable the other five. When that source is supported,
permitted, and authorized, Tracker starts it, records the best evidence it can provide, and presents
a source-appropriate result. Otherwise Tracker names the exact unavailable or degraded condition
without enabling another capture source as a substitute.

"Only X" has three precise meanings:

- **Manual:** X is the only captured source and no hidden control source is required.
- **Automatic:** X is the only captured source; any source used to start or stop tracking is an
  explicitly enabled `CONTROL` dependency and does not appear in captured history.
- **Ambient:** X may create useful history outside a session only through a separate, explicit
  ambient product setting.

Automatic tracking may be unavailable when the user disables every viable control strategy. Manual
tracking must remain available. Tracker reports that limitation instead of secretly registering a
provider.

### A running provider produces durable facts

When an authorized provider is running and produces a valid, fresh, source-qualified delivery,
Tracker durably records a quality-preserving representation before later classification or optional
enrichment.

This does not mean storing every API response:

- a request attempt, timer tick, permission check, or cache read is not an observation;
- stale, pre-effective, malformed, wrong-generation, or clock-unverifiable payload is rejected;
- reading the same cached content with the same provider time again has zero durable and product
  effect;
- a genuinely new, fresh callback with unchanged or empty content may retain compact coverage
  evidence without duplicating the prior payload; and
- high-rate sources are persisted at their approved product boundary, such as a Pressure window,
  rather than as one database transaction per sensor callback.

Durable also does not mean permanent. Control evidence and raw observations use purpose-specific
retention and remain subject to consent revocation and deletion fences.

### Ambient collection is useful, explicit, and honest

Ambient collection lets selected sources contribute outside a tracking session. It is independently
controlled, consented, retained, exported, deleted, and explained.

The intended starting position is:

| Source | Ambient position | Honest product promise |
| --- | --- | --- |
| Steps | Recommended, default off | One selected continuity provider with explicit coverage and gaps |
| Activity | Allowed for explicit control | Transition/control coverage; captured history needs separate enablement |
| Location | Opt-in and conditional | Passive or opportunistic points, not continuous route coverage |
| Wi-Fi | Conditional | Fresh passive scan-result callbacks; no default active ambient scans |
| Cell | Conditional | Fresh change callbacks; no forced refresh or sleep cadence promise |
| Pressure | Off by default | Session capture unless a separate ambient product is approved |

An ambient provider may outlive a session when a direct ambient or control demand still authorizes
it. Ending a session should downgrade that provider to its remaining valid plan instead of
unnecessarily stopping and restarting hardware.

### Context enriches; it never creates a dependency

Each primary source fact is independently valid. Compatible facts that Tracker is already collecting
may add optional context, subject to freshness, purpose, consent, time-domain, and deletion rules.

For example:

- Wi-Fi alone records fresh, privacy-minimized Wi-Fi information without Location.
- If Location is independently active and eligible, the Wi-Fi observation may also receive an
  approximate, uncertainty-labelled location context.
- If Location is disabled, stale, deleted, or incompatible with the Wi-Fi purpose, Wi-Fi continues
  normally without that context.

Enrichment is allowed to read eligible retained facts. It is never allowed to start, escalate, or
keep a provider alive. A source never waits for optional context before becoming durable or
advancing its own recording state.

## What each source should produce

| Source | Useful product when it stands alone | Acquisition direction | Important limitation |
| --- | --- | --- | --- |
| Location | Route, distance, places, or honest point coverage | Passive → low power → balanced → high accuracy, plus a bounded first-fix probe | Accuracy and freshness qualify points; preserve one canonical writer |
| Wi-Fi | Observation count, band mix, availability, and coverage; unique/new counts only with an approved keyed-identity lifecycle | Passive scan-result callbacks → bounded directly requested scan budget | Cache reads and scan attempts do not qualify; hide raw SSID/BSSID by default |
| Cell | Technology mix, quality distribution, weak periods, and availability | Change callbacks → bounded directly requested refresh budget | Prefer callbacks; minimize subscription and tower identity |
| Activity | Understandable movement bands and active time | Transition events → sampled classifications when directly needed | Control-only events cannot become captured Activity history |
| Steps | Session deltas and covered day totals, including between-session Steps when enabled | One ambient continuity provider plus direct batched/low-latency session boundaries | A baseline or missing interval is not zero; overlapping providers are never added twice |
| Pressure | Pressure trend and covered statistical windows | Low/standard/bounded high-rate windows with real FIFO batching where supported | No default ambient mode or uncalibrated elevation claim |

Source-specific Android behavior remains source-specific. Shared architecture must not erase the
difference between a Location fix, Step counter boundary, Pressure window, Activity transition,
Wi-Fi result, and Cell callback.

## Battery and quality strategy

Tracker minimizes battery use through real mechanisms rather than labels:

1. A provider runs only for a direct session-capture, ambient-product, or automation-control demand.
2. Compatible same-source demands share one app-level provider owner.
3. The provider uses the cheapest supported plan that still satisfies every accepted demand's
   minimum useful quality.
4. Hardware/provider batching, longer acceptable report latency, passive callbacks, coalesced
   processing, and source-native database batches reduce wakeups and writes.
5. Physical provider configuration changes only when the actual Android plan changes. A policy,
   consent, purpose, or session-attribution revision alone must not restart unchanged hardware.
6. Power saver, Doze, thermal state, and fresh motion evidence may reduce an adaptable plan within
   its quality floor. They cannot disable the sole requested source.
7. Adjacent acquisition modes that do not produce a measured quality, latency, or energy difference
   collapse into one mode.
8. Active Wi-Fi scans, Cell refreshes, high-accuracy Location, and other paid work require a direct
   demand and a bounded policy. Optional enrichment never authorizes them.

The preferred plan is non-dominated: it either improves freshness, coverage, accuracy, continuity,
completeness, useful context, or reliability within the accepted battery/write budget, or it
materially reduces cost while matching the protected quality floor. A replacement that is lower
quality and no more efficient does not ship.

## The architecture we are keeping and refining

This is an evolution of Tracker's existing local-first architecture, not a rewrite.

```text
SourcePolicy and consent epochs
        -> direct purpose demands
        -> one app-scoped broker/supervisor
        -> one physical owner per source
        -> fresh source-native delivery
        -> one tracking data-plane mutation owner and durable WAL
        -> isolated source-specific typed facts and projectors
        -> one production history facade
        -> existing Today, Timeline, Calendar, detail, map, export, and deletion surfaces
```

| Keep | Refine | Do not build pre-emptively |
| --- | --- | --- |
| Room, Hilt, Flow, module boundaries, current provider adapters, existing product surfaces | Authority, purpose separation, lifecycle recovery, source qualification, source-only discovery, typed completeness | A second app architecture or wholesale package-moving campaign |
| Existing canonical Location path | Shadow comparison and fenced source-specific cutover | A simultaneous second Location writer |
| Existing history, dashboard, calendar, detail, and map shells | One coherent logical-history composition over trusted source facts | A mandatory new Days platform before existing surfaces prove insufficient |
| Source-specific facts and algorithms | Share a mechanism after two real verticals demonstrate identical semantics | A generic attribution graph, materializer language, contribution ledger, or tombstone framework |

The deliberately small safety spine exists because it prevents real failures:

- `SourcePolicy` is the single authority for enablement, acquisition ceiling, persistence, consent,
  and effective time.
- Session intent and source/control selections are immutable over their effective interval.
- Provider callbacks carry physical and authorization generations so stale work cannot borrow new
  consent or session meaning.
- One source-specific owner writes each canonical destination.
- Replay, correction, retention, import, and deletion are idempotent and cannot resurrect removed
  facts.
- Automatic lifecycle recovery uses boot-aware monotonic evidence and cannot revive stopped or
  finalized sessions.
- One slow or malformed source cannot block the other five.

Database version 28 has never shipped, so its schema may be shaped directly around this bounded
design. Every released v27 user fact must still survive a populated v27-to-v28 migration and reopen.

## Product history and truthful states

All retained product information must be accessible through the same production history semantics,
whether it came from a manual session, automatic session, or ambient collection. Existing Today,
Timeline, Calendar, selected-day/session detail, relevant maps, export, and deletion are the first
consumers to repair.

History groups multiple physical service runs under one logical user entry and distinguishes:

- facts captured inside sessions;
- facts collected between sessions;
- optional context and its provenance; and
- coverage, gaps, and completeness per source and purpose.

The UI must not flatten distinct conditions into zero or a generic inactive state. It distinguishes
disabled, unsupported, permission required, OS limited, waiting for evidence, recorded,
materializing, partial, ready, degraded, and failed. Numeric zero is shown only when qualified
coverage proves zero.

"Why was this recorded?" should explain the source, direct purpose, approximate time, controlling
setting, whether it was inside or outside a session, and any optional context used.

## Privacy and ownership

Tracker remains local-first. Tracking data stays on the device unless the user explicitly exports
or shares it.

- Ambient consent is separate from session capture consent.
- Control-only evidence is purpose-limited, excluded from normal history and export, and retained
  only as long as needed for automation and recovery.
- Wi-Fi and Cell identities are minimized; stable identifiers are not exposed in the default UI.
- Consent revocation immediately fences new use of the old epoch and removes its provider demand.
- Export and deletion operate on the same source identities and completeness rules as history.
- Deletion/key rotation prevents delayed callbacks, replay, backfill, or import from recreating
  removed data.
- Diagnostics remain payload-free and never upload tracked data automatically.

## How we will deliver it

The default source order is Steps, Pressure, protected Location, Activity, Wi-Fi, then Cell. This is
a delivery preference, not a dependency chain. Each source ships through its own thin vertical and
its own gate.

For each source:

1. define fresh qualified evidence and the exact provider registration set;
2. make that evidence durable and generation-fenced;
3. create or protect one canonical source writer;
4. implement correction, retention, export, deletion, and no-resurrection behavior;
5. return the fact through the production history path and existing applicable UI;
6. prove manual only-source operation;
7. separately prove automatic operation with declared controls;
8. separately prove any allowed ambient product; and
9. measure representative Android behavior, quality, and battery before activation.

Shared code is extracted only after concrete source verticals prove the same need. Focused host tests,
a populated migration test, and representative device evidence are required in proportion to the
risk. This personal/open-source app does not need an enterprise fleet-observability platform or an
exhaustive OEM laboratory before useful internal proof.

## Definition of success

The vision is achieved when:

- all six sources work as the sole captured source in manual mode;
- all six automatic scenarios capture only the selected source and use exactly the declared
  controls;
- each supported ambient mode is default-off and, when enabled, useful without a fabricated
  session, retained, exportable, deletable, and visible;
- fresh authorized observations survive crash/retry without loss, duplication, relabelling, or
  deletion resurrection;
- each source has exactly one active canonical writer;
- expensive providers are never hidden dependencies or enrichment side effects;
- stale cached data and exact replay with identical content and provider time have zero product
  effect;
- every applicable product surface presents the same trustworthy source facts and completeness;
- quality is maintained or improved within measured battery and storage budgets; and
- rollback can disable a candidate path without deleting canonical data.

Repository integration, passing host tests, or provider registration alone does not satisfy this
definition. A source is complete only with a reproducible provider-to-durable-fact-to-production-
query-to-user-visible result.

## Explicit non-goals

- Collecting every sensor continuously.
- Treating all sources as one synchronized sample bundle.
- Starting providers to make another source's data look richer.
- Promising periodic Wi-Fi or Cell observations during device sleep without measured Android/OEM
  evidence.
- Storing repeated stale cache contents or treating request attempts as observations.
- Exposing raw SSID, BSSID, tower, or stable subscription identifiers in the default product.
- Inferring calibrated elevation or vertical movement from Pressure without defensible calibration
  and clear estimate labelling.
- Replacing the existing Location writer before a safe shadow comparison and cutover decision.
- Building a generic data-processing platform, permanent observer network, or full History UI
  rewrite before demonstrated product need.
- Adding remote analytics, tracking-data upload, advertising identity, or automatic diagnostics
  upload.
- Choosing final retention durations or legal/privacy copy without explicit product/privacy approval.

## Decisions that remain product choices

The architecture can continue without guessing these answers, but they must be resolved before the
affected feature ships:

- whether Step corroboration remains an explicit automation control;
- how Ambient Steps selects one capable continuity provider and what continuity it promises;
- final retention duration for each source and purpose;
- whether ambient Location offers a product beyond passive opportunistic points;
- whether measured active Wi-Fi attempts or Cell refreshes become user-selectable modes;
- whether a calibrated Pressure-derived vertical estimate belongs in the product; and
- the final presentation rule for cross-midnight sessions.

Until those decisions are made, the corresponding behavior remains contained or unavailable rather
than being inferred from implementation convenience.
