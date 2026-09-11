# Tracking Infrastructure Completion TODO

Last updated: 2026-09-11

This is the canonical remaining-work ledger for the six source-to-product verticals. It translates
the vision, final design, adaptive acquisition design, execution ledger, decisions, current
implementation status, and handovers into checkable work. Repository evidence wins whenever this
ledger becomes stale.

The current phase is **IMPLEMENTATION_ONLY**. Write production logic and focused unit or contract
tests together, but do not run Gradle, compilation, tests, lint, Detekt, Room schema drift,
emulator/device, UI evaluation, battery, CI, release, or rollout validation. A checked
implementation item means only that the reviewed production and test code exists in a coherent
local commit marked **IMPLEMENTED_UNVALIDATED**. It does not mean that the behavior passes, is
integrated, is active, or is ready to ship.

## How to use this ledger

- [ ] TODO-META-001 Update this ledger after every coherent implementation commit.
- [ ] TODO-META-002 Record exact branch, commit, changed paths, dependency, and deferred validation
  command in IMPLEMENTATION_STATUS.md, DECISIONS.md when a decision changed, and
  VERIFICATION_MATRIX.md.
- [ ] TODO-META-003 Keep implementation work on source-owned branches and worktrees until all
  planned pieces exist.
- [ ] TODO-META-004 Mark new slices **IMPLEMENTED_UNVALIDATED**; do not mark them DONE, accepted,
  verified, integration-ready, or production-ready.
- [ ] TODO-META-005 Do not merge unvalidated source branches into local dev/v10 during this phase.
- [ ] TODO-META-006 Do not push, activate a provider or writer, change rollout state, release, tag,
  deploy, or perform a destructive or backward-incompatible migration without fresh authorization.
- [ ] TODO-META-007 Preserve exact physical ownership, source-specific semantics, and independently
  reviewable commits; do not use this checklist to justify a new universal framework.

## Protected repository and worktree state

- [ ] TODO-GIT-001 Preserve the six protected dirty root-checkout paths named in
  CONTINUATION_HANDOVER.md; never alter, stage, commit, clean, reset, copy over, or push them.
- [ ] TODO-GIT-002 Preserve the two frozen portable-import and qualified-awards draft worktrees as
  historical drafts; do not clean, rebase, or copy them wholesale.
- [ ] TODO-GIT-003 Preserve the canonical ancestry of the clean imported Steps core, product,
  summary-authority, retention/action, and presentation commits until convergence.
- [ ] TODO-GIT-004 Use the configured adsamcik identity for each coherent local commit.
- [ ] TODO-GIT-005 Stage only reviewed paths for each commit and inspect the staged content without
  running deferred validation commands.
- [ ] TODO-GIT-006 Keep the clean tracking-infra-integration worktree reserved for the eventual
  reviewed local dev/v10 integration.
- [ ] TODO-GIT-007 Keep the current published boundary distinct from newer local-only work; never
  claim that a clone contains local branches or dirty drafts.

## Existing foundation to preserve, not rebuild

The following is already present in reviewed repository history or accepted local dependency
commits. Remaining work may complete concrete bindings, but must not replace these mechanisms.

- [x] TODO-BASE-001 Room-backed SourcePolicy is the effective six-source authority.
- [x] TODO-BASE-002 Immutable manifests, logical entries, service runs, physical segments, desired
  actions, and boot or generation lifecycle fences exist.
- [x] TODO-BASE-003 Purpose-aware capture, control, and ambient demand models exist.
- [x] TODO-BASE-004 Durable source-event admission, WAL identity, source-local cursors, destination
  owner generations, and deletion fences exist as the bounded safety spine.
- [x] TODO-BASE-005 Existing Dashboard, History, Calendar, detail, map, import/export, widget,
  notification, game, goal, and achievement surfaces remain the product shells to repair.
- [x] TODO-BASE-006 Location remains protected by its existing canonical writer.
- [x] TODO-BASE-007 Dormant manual Steps facts, exact run-to-segment binding, logical replacement
  grouping, qualified history, selected deletion, numeric reads, portable export, and contained
  product surfaces provide the first concrete vertical.
- [x] TODO-BASE-008 Dormant Pressure session facts and exact selected-session deletion provide the
  second concrete source-specific storage pattern.
- [x] TODO-BASE-009 Activity durable callback admission and Wi-Fi or Cell freshness and privacy
  groundwork exist without constituting finished product verticals.

## Cross-cutting implementation closure

### Authority, schema, and migration

- [ ] TODO-CORE-001 Finish the unreleased v28 shape only where the six concrete verticals require
  fields, indexes, constraints, or source-specific tables; retain every released v27 table and fact.
- [ ] TODO-CORE-002 Complete exact effective authorization intervals for capture, control, and
  ambient purposes, including policy revision, consent epoch, boot domain, elapsed boundary, source
  use, and retention class.
- [ ] TODO-CORE-003 Persist immutable structural zone and civil-day allocation authority for every
  sessionless fact that can enter day history.
- [ ] TODO-CORE-004 Keep physical provider configuration generation independent from authorization,
  manifest, policy, purpose, and consent revisions.
- [ ] TODO-CORE-005 Complete frozen v27 decoding and conservative legacy classification for every
  released source family; unverifiable legacy purpose remains LEGACY_UNKNOWN and cannot gain new
  capture authority.
- [ ] TODO-CORE-006 Author migration and reopen tests for every new v28 field or table without
  executing them during the implementation-only phase.
- [ ] TODO-CORE-007 Keep deletion, consent, and key epochs monotonic across rollback, migration,
  restore, import, and compatibility mirrors.
- [ ] TODO-CORE-008 Add only the indexes and bounded batch queries required by real production
  consumers; prohibit per-row query fan-out.

### Demand, supervision, and provider ownership

- [ ] TODO-BROKER-001 Complete one app-scoped physical owner for each of Location, Wi-Fi, Cell,
  Activity, Steps, and Pressure.
- [ ] TODO-BROKER-002 Make provider lifetime depend only on direct SESSION_CAPTURE, CONTROL, or
  AMBIENT_PRODUCT demand.
- [ ] TODO-BROKER-003 Prohibit history, export, deletion, optional context, and enrichment paths from
  creating, escalating, or retaining provider demand.
- [ ] TODO-BROKER-004 Merge compatible same-source demands into one real provider plan while keeping
  attribution and retention purpose-specific.
- [ ] TODO-BROKER-005 Rotate authorization at the exact effective boundary without restarting
  unchanged physical hardware.
- [ ] TODO-BROKER-006 Implement provider-specific reconfiguration: generation-addressable overlap
  only where the Android API supports it, otherwise fenced break-before-make with an explicit gap.
- [ ] TODO-BROKER-007 Reconcile provider registration after process restart, boot, update, policy
  change, permission change, consent revocation, session stop, and collected-data deletion.
- [ ] TODO-BROKER-008 Author source-specific registration-set tests for manual only-X, automatic
  only-X plus declared controls, and each supported ambient mode.

### Lifecycle and Android actions

- [ ] TODO-LIFE-001 Complete durable intent-before-action handling for manual and automatic starts,
  stops, reconfiguration, provider registration, and foreground-service promotion.
- [ ] TODO-LIFE-002 Require at least one requested source runtime to be accepted before ACTIVE;
  otherwise terminate with a truthful typed failure.
- [ ] TODO-LIFE-003 Advance RECORDING only from durable source-qualified post-effective evidence,
  never from a request, registration, permission check, timer, cache read, or baseline alone.
- [ ] TODO-LIFE-004 Advance MATERIALIZED only after the active canonical writer commits the exact
  source product and source-local cursor or receipt atomically.
- [ ] TODO-LIFE-005 Advance QUERYABLE only when the production history repository returns the
  materialized source fact with truthful completeness.
- [ ] TODO-LIFE-006 Complete exact foreground-service type-mask composition from accepted direct
  demands and persist action acceptance or failure.
- [ ] TODO-LIFE-007 Complete stale-run finalization and recovery so STOPPING and FINALIZED never
  restart and old boot, automation, policy, consent, owner, or deletion generations cannot commit.
- [ ] TODO-LIFE-008 Retire duplicate service ownership only after every required caller uses the
  durable action gateway; do not preserve parallel canonical owners.
- [ ] TODO-LIFE-009 Author crash-point, redelivery, cancellation, process-restart, reboot, and stale
  action tests without executing them yet.

### Durable admission, projection, and maintenance

- [ ] TODO-DATA-001 Complete source-native durable delivery identity for the five source adapters
  not yet fully using the admission seam.
- [ ] TODO-DATA-002 Split each delivery at observed-time authorization boundaries into
  authorization-homogeneous units without relabelling retained facts.
- [ ] TODO-DATA-003 Reject malformed, pre-effective, stale, future, wrong-boot, wrong-generation,
  revoked, deleted, or clock-unverifiable callbacks before product effect.
- [ ] TODO-DATA-004 Make exact replay with identical source content and provider time zero-effect
  across retry and process death.
- [ ] TODO-DATA-005 Preserve compact coverage evidence for a genuinely new qualified empty or
  unchanged delivery only where the source contract gives it product meaning.
- [ ] TODO-DATA-006 Complete one bounded, serialized projection lane per source so poison or retry
  in one source cannot block the other five.
- [ ] TODO-DATA-007 Commit source cursor, receipt, typed mutation, membership, completeness, and
  invalidation in the same source-local writer transaction.
- [ ] TODO-DATA-008 Implement stable logical fact identity plus source-specific UPSERT, DELETE, and
  bounded replacement semantics so corrections do not double count.
- [ ] TODO-DATA-009 Recheck destination owner generation inside every canonical destination
  transaction and fence both legacy and candidate writers.
- [ ] TODO-DATA-010 Make retention, import, deletion, repair, and backfill bounded, resumable,
  cancellation-safe, and unable to starve live admission.
- [ ] TODO-DATA-011 Author source-specific replay, collision, correction, deletion, retention-floor,
  cancellation, poison-isolation, and no-resurrection tests without running them yet.

### Acquisition plans and battery semantics

- [ ] TODO-QOS-001 Replace labels that map to identical provider behavior with the smallest honest
  source-specific mode ladder.
- [ ] TODO-QOS-002 Give every plan a real difference in quality, latency, batching, provider cost,
  write cost, or active-request budget.
- [ ] TODO-QOS-003 Prefer passive callbacks, provider batching, FIFO, coalesced processing, and
  longer acceptable latency when they satisfy the direct demand floor.
- [ ] TODO-QOS-004 Make paid Location, Wi-Fi, Cell, and high-rate work finite,
  direct-demand-only, cancellation-safe, and incapable of being authorized by enrichment.
- [ ] TODO-QOS-005 Preserve the sole requested source under saver, Doze, thermal, and inactivity
  adaptation; degrade only within its declared adaptable floor and expose the reason.
- [ ] TODO-QOS-006 Add hysteresis, minimum dwell, and cooldown only where concrete source adapters
  need them to prevent real provider-plan flapping.
- [ ] TODO-QOS-007 Author deterministic plan and demand-merging tests for each concrete source
  without claiming battery improvement before later measurement.

## Steps vertical

### Current imported-history lane

- [x] TODO-STEPS-IMPORT-001 Review and finish the preserved eight-path imported Steps day-repair
  draft without relying on the stopped test run as proof.
- [x] TODO-STEPS-IMPORT-002 Compose imported local-day contributions from exact retained portable
  fact identity, original structural zone, explicit coverage, gaps, baseline, correction, and
  truncation state.
- [x] TODO-STEPS-IMPORT-003 Discover authenticated imported source facts that fall outside a
  presentation wall envelope without inferring ownership from wall-time overlap.
- [x] TODO-STEPS-IMPORT-004 Preserve exact imported versus local ownership partition, replacement
  grouping, Long-valued counts, and null or partial state; never invent tracked duration.
- [x] TODO-STEPS-IMPORT-005 Keep partial import materialization compatible with legacy summary
  consumers without turning missing or unavailable Steps into a qualified zero.
- [x] TODO-STEPS-IMPORT-006 Author focused accumulator, Room, selected-deletion, and worker tests for
  imported day composition, then commit the coherent slice as IMPLEMENTED_UNVALIDATED.

### Production portable import and no-resurrection

- [x] TODO-STEPS-PORT-001 Implement a real production portable Steps importer behind an inactive
  entry point using the frozen versioned format and strict codec.
- [x] TODO-STEPS-PORT-002 Validate the complete bounded snapshot, checksums, correction lineage,
  original capture set, source qualification, settlement, deletion scope, and all limits before
  mutating production storage.
- [x] TODO-STEPS-PORT-003 Stage and commit one import atomically, with entry-wide global identity
  uniqueness and typed collision or replay outcomes.
- [x] TODO-STEPS-PORT-004 Persist original entry, run, segment, manifest, purpose, consent, policy,
  owner, deletion scope, fact, receipt, zone, and provenance identity verbatim where supplied.
- [x] TODO-STEPS-PORT-005 Never fabricate a local service run, local elapsed time, local consent,
  local provider generation, full original capture set, or tracked duration for an imported fact.
- [x] TODO-STEPS-PORT-006 Distinguish an identical replay from a conflicting reuse of entry, run,
  segment, fact, correction, receipt, or deletion-scope identity.
- [x] TODO-STEPS-PORT-007 Invalidate exact source evidence, logical history, affected civil days,
  numeric consumers, and product observers after a committed import.
- [x] TODO-STEPS-PORT-008 Make retained imported origins exportable without reconstructing facts
  from lossy compatibility aggregates.
- [x] TODO-STEPS-PORT-009 Return a typed refusal when retention has removed identity required to
  reproduce the original portable scope.
- [x] TODO-STEPS-PORT-010 Connect the production file import path and registry to the new importer
  without touching the six protected root-checkout drafts.
- [x] TODO-STEPS-PORT-011 Author atomicity, malformed input, collision, replay, correction,
  cancellation, storage failure, reopen, and bounded-resource tests without executing them.

Implementation checkpoints: `8abd7c6e3` supplies PORT-001 through PORT-006 and preserves the
already implemented retained export/refusal behavior in PORT-008 and PORT-009. `3dfa4eaad`
completes PORT-007 by holding all conservatively affected civil-day locks before the owning Room
transaction, resolving persisted-or-imported stored-zone authority, repairing exact source totals,
and publishing source/table invalidation only after commit. Partial or Int-unrepresentable Steps
requires a pre-existing compatibility integer; conflicting or unverifiable day authority rolls the
whole hierarchy back. `c58fcc85f` supplies PORT-010 with a strict bounded `.trackersteps` adapter,
registry and worker routing, importer-owned transaction dispatch, post-success receipt recording,
and focused unexecuted file/transaction tests. `03db9e565` completes PORT-011 with fail-closed
malformed retained-state, extra correction-lineage, and fresh-Room-reopen replay contracts. All
portable importer tests remain deliberately unexecuted until convergence.

### Imported selected deletion and repair

- [x] TODO-STEPS-DEL-001 Extend typed selected-session deletion to exact imported ownership without
  treating a portable source-qualified entry as locally candidate-owned.
- [x] TODO-STEPS-DEL-002 Validate complete original entry, run, segment, capture, manifest,
  purpose, zone, fact, correction, retention, and deletion-scope identity before mutation.
- [x] TODO-STEPS-DEL-003 Install original-scope and current local deletion fences before deleting
  imported facts, memberships, receipts, product rows, and presentation identity.
- [x] TODO-STEPS-DEL-004 Recompose every affected day from the complete surviving local and imported
  fact set in the stored zone.
- [x] TODO-STEPS-DEL-005 When a surviving imported remainder is partial, redact the legacy
  daily-summary Steps compatibility value so byte-level database export cannot leak a stale total;
  treat its zero sentinel as redaction, never as qualified product zero.
- [x] TODO-STEPS-DEL-006 Preserve typed blocked outcomes for retained-history loss, unverifiable
  original scope, active or unsettled work, unsupported mixed ownership, and retryable storage
  failure.
- [x] TODO-STEPS-DEL-007 Prove by authored tests that replay, re-import, delayed projection,
  correction, retention, restore, and process restart cannot resurrect deleted imported Steps.

Implementation checkpoint: `89454b8c1` adds the source-local imported deletion branch, shared
portable day authority, dual original/current-local fences, payload-free retractions, complete
replacement-member removal, post-delete day composition, compatibility redaction, and the first
focused contracts. `3ff5c086c` completes the authored correction-lineage and retention-loss
contracts. Replay/re-import, backup-to-fresh-Room reopen, cancellation, malformed hierarchy,
partial compatibility state, and no live-provider drain are covered in source. All tests remain
deliberately unexecuted until convergence.

### Portable round trip

- [x] TODO-STEPS-ROUND-001 Author a complete local export to import to history round-trip scenario.
- [x] TODO-STEPS-ROUND-002 Cover covered zero, positive, partial, replacement runs, corrections,
  retained prefixes or suffixes, and imported-only logical entries.
- [x] TODO-STEPS-ROUND-003 Cover imported correction followed by day repair, retention, selected
  deletion, full deletion, reopen, replay, and re-import.
- [x] TODO-STEPS-ROUND-004 Keep original portable bytes or canonical reconstructed output stable
  where all required identity remains retained.
- [x] TODO-STEPS-ROUND-005 Ensure control evidence, unrelated sources, raw radio identity, and
  fabricated local authority never enter a Steps portable artifact.

Implementation checkpoint: `19b8789b2` adds the missing source-owned `.trackersteps` export
adapter and makes the versioned format bidirectional. It supports bounded date-range and whole-
history reads without a Location-data prerequisite, uses the strict canonical codec and shared
format constants, and does not display a precise-Location warning for the privacy-minimized
artifact. `2d7af2b70` authors a two-database production-class host contract from authenticated local
Room facts through `RoomExportPortableSteps`, `RoomImportPortableSteps`, and
`TrackingHistoryRepository`, including stable re-export and absence of fabricated local authority.
The established focused contracts cover typed zero/partial/replacement/correction/retention,
selected and full deletion, reopen/replay/re-import refusal, canonical byte stability, and privacy
whitelisting. Imported portable v1 content corrections remain conflicts; extra stored correction
lineage remains typed unverifiable rather than being collapsed. Nothing in this checkpoint was
compiled or executed.

### Manual and session Steps completion

- [x] TODO-STEPS-MANUAL-001 Connect every real manual-start surface to one centralized source-aware
  start decision and immutable manifest creation path.
- [x] TODO-STEPS-MANUAL-002 Make a Steps-only manual request register exactly Steps and no Location,
  Activity, Pressure, Wi-Fi, Cell, CONTROL, or AMBIENT demand.
- [x] TODO-STEPS-MANUAL-003 Complete app-scoped TYPE_STEP_COUNTER registration, post-effective
  baseline, boot or reset gap handling, positive fresh delta admission, and listener removal.
- [x] TODO-STEPS-MANUAL-004 Keep a baseline, missing interval, counter reset, unchanged boundary,
  covered zero, positive delta, partial coverage, unsupported sensor, permission limitation, and
  storage failure distinct.
- [x] TODO-STEPS-MANUAL-005 Wire the dormant canonical writer transition through contained
  activation, exact owner fencing, legacy drain, rollback, deletion rearm, and product invalidation
  without enabling it by default.
- [x] TODO-STEPS-MANUAL-006 Ensure a positive post-baseline delta can advance RECORDING, the one
  canonical writer can advance MATERIALIZED, and the production history path can advance QUERYABLE.
- [x] TODO-STEPS-MANUAL-007 Finish source-only recent or list, Today, Calendar, selected detail, and
  live state composition without requiring a Location trip or positive legacy sample count.
- [x] TODO-STEPS-MANUAL-008 Author the exact provider-to-WAL-to-writer-to-history-to-UI contract
  tests and listener teardown test without executing the physical scenario yet.

Implementation checkpoint: the production graph already routes Dashboard, Tracker, shortcut, and
widget starts through `TrackerServiceApi.requestManualTrackingStart`; the service-owned plan emits
only reachable event sources, gives manual sessions no control dependency, and binds the immutable
manifest, run, demand, authorization, WAL and source-local writer identities exactly. The app-scoped
step-counter runtime preserves a post-effective baseline, reset/gap and freshness semantics, atomic
durable admission, generation fencing, and exact listener retirement. Existing history, recent
list, Today/Calendar, Detail and live compositions keep missing, materializing, partial, covered
zero and positive states distinct without treating `sampleCount` as source proof. The newly added
host contract fixes the exact manual `{Steps}` plan/no-control boundary; the disposable Android
gate already covers the complete provider-to-product chain and listener teardown. All of this
remains `IMPLEMENTED_UNVALIDATED`: no test, build, static-analysis, device, UI, battery, activation,
integration or publication command ran for this checkpoint.

### Qualified numeric consumers and effects

- [ ] TODO-STEPS-NUM-001 Audit every remaining read of daily_summary Steps, SessionSegment.steps,
  raw StepInterval, or nullable numeric fallback in product and effect code.
- [x] TODO-STEPS-NUM-002 Route goal progress and award mutation through exact qualified revision,
  stored zone, period, target, and completeness.
- [x] TODO-STEPS-NUM-003 Make streak creation, continuation, repair, and removal depend on qualified
  complete periods rather than raw totals.
- [x] TODO-STEPS-NUM-004 Make achievements, points, XP, badges, lifetime totals, best-day metrics,
  and game effects use qualified source facts and retract or repair after correction or deletion.
- [ ] TODO-STEPS-NUM-005 Ensure widgets and notifications distinguish ready zero, positive,
  partial, materializing, unavailable, disabled, and storage failure without raw fallback.
- [x] TODO-STEPS-NUM-006 Prevent imported, ambient, session, or retained facts from awarding twice
  when their intervals overlap or their presentation grouping changes.
- [x] TODO-STEPS-NUM-007 Persist effect identity and revision so identical replay is zero-effect and
  correction or deletion performs an exact replace or retract.
- [ ] TODO-STEPS-NUM-008 Author focused tests for every audited consumer and every positive,
  zero, partial, correction, deletion, retention, and imported-origin case without running them.

Implementation checkpoints: `7eaa891b3` adds the bounded coherent numeric-read foundation.
`ead24608c`, `759c369b4`, and `343f13577` persist exact source revision, digest, stored-zone
authority, period, target, completion state, and revisioned effect identity. `8bbe2d0d5`,
`26442893a`, and `1f4041d00` add source-local point and XP fences plus idempotent reversible
projection. `2745384f8` and `dc23917e5` add durable at-most-once notification claims and a
deliberately dormant coordinator. `9520af007` adds historical repair for fact projection,
materialization, import, selected deletion, and retention; qualified streak/perfect-week
replacement; exact unlock-time/high-water handling; and fail-closed product filtering.
`e9f344f46` and `90f603c36` remove the raw session/daily-summary goal bridge and close its static
review follow-ups. `77a48b2b1` defines typed retained lifetime/best-day decisions, `04aaf16e8`
composes them from exact retained local/imported source facts, and `be1f0cd7b` projects the two
retained metrics into qualified, correction-safe achievement rows with exact revision/digest
authorization and durable notification high-water. `8e3cb68e5` replaces nullable widget Steps
with explicit ready, partial, materializing, not-captured, disabled, unavailable, and storage-
unavailable presentation states; fences the active widget to its exact segment; and makes the
legacy goal-notification worker retry transient qualified states without consuming a claim when
Android notification permission denies delivery. `7a36129db` removes Steps from the generic
`TripSummary` API and from newly written schema-3 JSON sessions; old optional JSON Steps remain
importable, while exact source-owned transfer stays in `.trackersteps`.

All commits remain **IMPLEMENTED_UNVALIDATED**. NUM-001, NUM-005, and NUM-008 remain open. NUM-005
still needs an authoritative current-policy signal before a daily widget or notification can call
an otherwise not-captured day `disabled`; session history already carries that distinction. Finish
the consumer inventory: raw `DailySummary.totalSteps`, `SessionStatsSnapshot.steps`, and historical
trajectory StepInterval enrichment remain. Complete the consumer-wide authored test matrix. The
v28 Room schema identity hash is intentionally not guessed and must be regenerated during final
convergence validation.

### Automatic Steps

- [ ] TODO-STEPS-AUTO-001 Resolve the explicit product decision on optional Step corroboration; it
  must be a visible CONTROL demand or absent.
- [ ] TODO-STEPS-AUTO-002 Connect an eligible fresh Activity control trigger through the durable
  automatic gateway before Android service launch.
- [ ] TODO-STEPS-AUTO-003 Create an immutable automatic manifest with capture set exactly Steps and
  declared controls separately attributed.
- [ ] TODO-STEPS-AUTO-004 Reconcile provider demand so Steps is captured and Activity, when used,
  remains CONTROL only.
- [ ] TODO-STEPS-AUTO-005 Keep control evidence purpose-limited, short-lived, absent from normal
  history and portable export, and independently revocable and deletable.
- [ ] TODO-STEPS-AUTO-006 Fence stale triggers by observed and received clocks, boot identity,
  automation epoch, policy, consent, registration generation, action identity, and consumption.
- [ ] TODO-STEPS-AUTO-007 Complete automatic stop, interrupted-session finalization, process or
  reboot recovery, manual fallback, and rollback without reviving a finalized session.
- [ ] TODO-STEPS-AUTO-008 Author trigger-to-provider-to-query and control-nonleakage tests without
  running them.

### Default-off Ambient Steps

- [ ] TODO-STEPS-AMBIENT-001 Resolve the continuity-provider and user promise decision:
  opportunistic versus visible-foreground continuity.
- [ ] TODO-STEPS-AMBIENT-002 Add a separate default-off ambient Steps setting, consent epoch,
  capability result, permission flow, retention class, explanation, and revocation path.
- [ ] TODO-STEPS-AMBIENT-003 Select exactly one capable ambient continuity provider and never add
  overlapping direct, Health Connect, and Recording intervals.
- [ ] TODO-STEPS-AMBIENT-004 Keep direct counter registration only for direct live demand; share
  boundaries with ambient composition without retaining a session listener unnecessarily.
- [ ] TODO-STEPS-AMBIENT-005 Persist sessionless structural day and zone identity, coverage, gaps,
  baselines, resets, provider precedence, and process or reboot discontinuity.
- [ ] TODO-STEPS-AMBIENT-006 Partition canonical totals into in-session and between-session facts
  exactly once while keeping the day total authoritative.
- [ ] TODO-STEPS-AMBIENT-007 Make ambient facts discoverable in Today, Timeline, Calendar, day
  detail, goals, widgets, notifications, export, and deletion without fabricating a session.
- [ ] TODO-STEPS-AMBIENT-008 Complete retention, correction, consent reset, deletion,
  export/import, and no-resurrection behavior for ambient-only and overlapping days.
- [ ] TODO-STEPS-AMBIENT-009 Author capability, provider precedence, overlap, DST, zone change,
  partial-day, process, reboot, consent, retention, export, and deletion tests without running them.

## Pressure vertical

- [ ] TODO-PRESS-001 Complete the Pressure demand adapter and honest low, standard, and bounded
  high-rate acquisition plans using real sensor delay, FIFO, batching, latency, or write differences.
- [ ] TODO-PRESS-002 Close qualified microsegments or windows with endpoints, count, range,
  mean, variance, trend or fit, accuracy, expected and actual coverage, and named gaps.
- [ ] TODO-PRESS-003 Amortize callback and checkpoint writes; never persist one Room transaction per
  high-rate sensor callback.
- [ ] TODO-PRESS-004 Complete the dormant source-local writer activation, cursor, correction, owner
  fencing, rollback, deletion rearm, and failure isolation without enabling it by default.
- [ ] TODO-PRESS-005 Advance RECORDING only after a qualified committed window, then expose exact
  MATERIALIZED and QUERYABLE transitions.
- [ ] TODO-PRESS-006 Compose logical replacement runs and stored-zone day history without losing
  exact physical run ownership.
- [ ] TODO-PRESS-007 Finish Today, Timeline, Calendar, selected detail, live or recent status, trend,
  range, coverage, partial, materializing, unavailable, and failure presentation.
- [ ] TODO-PRESS-008 Complete correction-safe day repair, retention, portable export/import,
  selected deletion integration, all-data deletion, and no-resurrection.
- [ ] TODO-PRESS-009 Support automatic Pressure with separately consented Activity CONTROL; manual
  Pressure remains available when automatic control is unavailable.
- [ ] TODO-PRESS-010 Keep continuous ambient Pressure unavailable by default and ensure no ambient
  demand is registered.
- [ ] TODO-PRESS-011 Expose pressure trend only; do not create elevation, ascent, or vertical claims
  unless the separate calibrated-product decision is approved and implemented.
- [ ] TODO-PRESS-012 Author FIFO and non-FIFO, batching, gap, correction, deletion, import/export,
  automatic-control, history, and UI contract tests without running them.

## Protected Location vertical

- [ ] TODO-LOC-001 Keep the current canonical Location writer as the only active writer throughout
  implementation and convergence.
- [ ] TODO-LOC-002 Route manual, automatic, and approved ambient Location through SourcePolicy,
  immutable manifests, direct purpose demands, and one app-scoped provider owner.
- [ ] TODO-LOC-003 Make Location-only manual capture register no Activity, Steps, Pressure, Wi-Fi,
  or Cell demand.
- [ ] TODO-LOC-004 Keep automatic Location capture separate from explicit Activity CONTROL and
  preserve manual operation when control is unavailable.
- [ ] TODO-LOC-005 Implement real passive, low-power, balanced, high-accuracy, and bounded first-fix
  plans only where each rung changes provider behavior and satisfies a direct floor.
- [ ] TODO-LOC-006 Qualify post-effective points by provider time, freshness, accuracy, permission,
  boot, physical generation, authorization, consent, and deletion epochs.
- [ ] TODO-LOC-007 Complete durable source identity, exact replay suppression, correction, route and
  distance recomposition, gaps, retention, export/import, deletion, and no-resurrection around the
  protected writer.
- [ ] TODO-LOC-008 Provide optional Location context only from compatible Location facts already
  being collected; no radio or other source may start or retain Location.
- [ ] TODO-LOC-009 Resolve whether ambient Location offers a product beyond passive opportunistic
  points; keep it default-off and independently consented if approved.
- [ ] TODO-LOC-010 Keep route, distance, places, point coverage, accuracy, completeness, Today,
  Timeline, Calendar, detail, and map consistent with the production history facade.
- [ ] TODO-LOC-011 Implement a read-only shadow comparator and rollback-safe cutover state only if
  a replacement writer is still justified; do not implement or activate a second canonical writer.
- [ ] TODO-LOC-012 Author manual, automatic, ambient-if-approved, freshness, accuracy, replay,
  correction, context, query, UI, and shadow tests without running them.

## Activity vertical

- [ ] TODO-ACT-001 Preserve one physical Activity registration while carrying exact eligibility for
  CONTROL, SESSION_CAPTURE, and any independently approved ambient capture.
- [ ] TODO-ACT-002 Complete captured Activity typed facts for coalesced movement bands, active time,
  confidence, coverage, gaps, and fresh STILL semantics.
- [ ] TODO-ACT-003 Keep control-only events out of captured Activity history, summaries, goals,
  export, maps, and unrelated source history.
- [ ] TODO-ACT-004 Implement transition-first acquisition and use sampled classification only for a
  direct capture quality need with a real latency or product benefit.
- [ ] TODO-ACT-005 Complete automatic Activity-only trigger handoff: seed captured evidence only
  when capture was independently authorized at trigger observed time; otherwise preserve a partial
  initial interval.
- [ ] TODO-ACT-006 Fence stale automation by provider time, expiry, boot, automation, policy,
  consent, registration, action, and deletion identity.
- [ ] TODO-ACT-007 Complete source-local writer, cursor, correction, coalescing, retention,
  export/import, deletion, no-resurrection, and rollback.
- [ ] TODO-ACT-008 Expose manual and automatic Activity-only history through Today, Timeline,
  Calendar, detail, live or recent state, active-time composition, and explanation.
- [ ] TODO-ACT-009 Keep control retention bounded and independently revocable; do not create a
  captured ambient Activity product without a separate approved setting.
- [ ] TODO-ACT-010 Author callback-batch, duplicate, coalescing, control-separation, trigger,
  process or reboot, correction, deletion, query, and UI tests without running them.

## Wi-Fi vertical

- [ ] TODO-WIFI-001 Complete one app-scoped Wi-Fi callback owner and stable durable delivery
  identity across process restart.
- [ ] TODO-WIFI-002 Treat scan attempts, timers, permission checks, getScanResults cache reads, and
  receipt-time substitution as operational state, never observations.
- [ ] TODO-WIFI-003 Admit only item-level provider times that are positive, nonfuture,
  post-effective, within the source freshness limit, and valid for the exact generation and epoch.
- [ ] TODO-WIFI-004 Distinguish confirmed fresh empty, fresh unchanged, fresh changed, absent,
  stale, failed, permission-limited, OS-throttled, and clock-unverifiable results.
- [ ] TODO-WIFI-005 Store privacy-minimized identity-free facts for observation count, band mix,
  quality, availability, coverage, and completeness.
- [ ] TODO-WIFI-006 Add per-install keyed HMAC identity, key epoch, rotation, and unique or new
  network products only if the explicit product and privacy decision approves identity-dependent
  behavior.
- [ ] TODO-WIFI-007 Keep active scan attempts finite, budgeted, cancellation-safe, and available
  only to direct capture demand; stop after qualified evidence or terminal budget outcome.
- [ ] TODO-WIFI-008 Keep ambient Wi-Fi default-off and opportunistic through fresh passive
  scan-result broadcasts; never run default active ambient scans.
- [ ] TODO-WIFI-009 Compose optional uncertainty-labelled Location context only from compatible
  retained Location facts already available.
- [ ] TODO-WIFI-010 Complete source-local writer, cursor, correction or supersession, retention,
  export/import, deletion, key rotation, no-resurrection, and rollback.
- [ ] TODO-WIFI-011 Make Wi-Fi-only entries visible with zero distance and no Location segment in
  Today, Timeline, Calendar, detail, applicable radio map or truthful unavailable state, and export.
- [ ] TODO-WIFI-012 Support automatic Wi-Fi-only capture with declared Activity CONTROL while
  keeping control out of Wi-Fi and Activity captured history.
- [ ] TODO-WIFI-013 Author freshness, empty, unchanged, replay, restart, throttle, active-budget,
  privacy, context, deletion, query, UI, automatic, and ambient tests without running them.

## Cell vertical

- [ ] TODO-CELL-001 Complete one app-scoped Cell callback owner and stable durable delivery identity
  across process restart.
- [ ] TODO-CELL-002 Prefer timestamp-qualified change callbacks and treat getAllCellInfo as cache
  retrieval, not fresh acquisition.
- [ ] TODO-CELL-003 Keep requestCellInfoUpdate finite, explicit, rate-limited, non-guaranteed, and
  direct-demand-only; never promise a wake-reliable cadence.
- [ ] TODO-CELL-004 Qualify every returned child independently by provider time, freshness,
  generation, authorization, consent, and deletion epochs.
- [ ] TODO-CELL-005 Distinguish confirmed empty, partial multi-SIM, fresh unchanged, fresh changed,
  absent, stale, failed, permission-limited, and clock-unverifiable state.
- [ ] TODO-CELL-006 Store identity-free technology mix, quality distribution, weak periods,
  availability, coverage, and completeness without raw tower or stable subscription identifiers.
- [ ] TODO-CELL-007 Use ephemeral nonidentifying slot grouping or keyed identity only when necessary
  and explicitly approved; rotate keys on deletion or consent reset.
- [ ] TODO-CELL-008 Keep ambient Cell default-off and callback-driven with no forced refresh or
  WorkManager sleep-cadence promise.
- [ ] TODO-CELL-009 Compose optional uncertainty-labelled Location context only from compatible
  retained Location facts already available.
- [ ] TODO-CELL-010 Complete source-local writer, cursor, correction or supersession, retention,
  export/import, deletion, key rotation, no-resurrection, and rollback.
- [ ] TODO-CELL-011 Make Cell-only entries visible with zero distance and no Location segment in
  Today, Timeline, Calendar, detail, applicable radio map or truthful unavailable state, and export.
- [ ] TODO-CELL-012 Support automatic Cell-only capture with declared Activity CONTROL while
  keeping control out of Cell and Activity captured history.
- [ ] TODO-CELL-013 Author callback, per-child freshness, multi-SIM, replay, restart, refresh budget,
  privacy, context, deletion, query, UI, automatic, and ambient tests without running them.

## Production history and UI

- [ ] TODO-HIST-001 Complete one read-only TrackingHistoryRepository facade for observeToday,
  observeDay, observeSession, stable fact lookup, and source completeness using existing data and
  product modules.
- [ ] TODO-HIST-002 Compose manual, automatic, and ambient facts under stable logical entries while
  retaining exact physical run, segment, fact, purpose, zone, and provenance ownership internally.
- [ ] TODO-HIST-003 Discover source-only and imported-only facts without requiring Location,
  distance, route, a legacy trip row, or positive sample_count.
- [ ] TODO-HIST-004 Expose availability, evidence, product, coverage, gaps, repair cause, and
  revision independently for every selected source.
- [ ] TODO-HIST-005 Distinguish disabled, unsupported, permission required, OS limited, waiting,
  active, recorded, materializing, partial, ready, degraded, failed, deleted, and storage
  unavailable.
- [ ] TODO-HIST-006 Show numeric zero only when source-qualified coverage proves zero; preserve null
  or typed nonnumeric states elsewhere.
- [ ] TODO-HIST-007 Partition additive metrics into total, in-session, and outside-session values
  without adding overlapping representations twice.
- [ ] TODO-HIST-008 Keep nonadditive source products typed: routes, radio sets or distributions,
  activity bands, pressure trends, and coverage do not use a generic scalar sum.
- [ ] TODO-HIST-009 Use stored structural zone and day identity so current-device zone changes do
  not move historical facts.
- [ ] TODO-HIST-010 Implement the selected cross-midnight presentation and counting decision once
  resolved; split timestamped facts exactly and keep unverifiable legacy allocation partial.
- [ ] TODO-HIST-011 Repair Today, Timeline, Calendar, selected-day or session detail, relevant maps,
  live or recent lists, export, and deletion to consume the same qualified facts.
- [ ] TODO-HIST-012 Provide a smallest truthful sole-source live and list surface for each source;
  no new UI platform or mandatory Days-first rewrite.
- [ ] TODO-HIST-013 Add a stable Why was this recorded explanation with source, direct purpose,
  approximate time, controlling setting, session or ambient classification, and optional context
  provenance.
- [ ] TODO-HIST-014 Preserve access to retained typed-only facts during technical rollback even
  when new capture is disabled.
- [ ] TODO-HIST-015 Complete plain-language localization, accessibility semantics, large-text
  behavior, and truthful empty or failure copy in code and UI tests without running device review.

## Start, settings, permissions, and source actions

- [ ] TODO-ACTION-001 Audit every manual start entry point and route it through the same current
  SourcePolicy and requested-source prerequisite decision.
- [ ] TODO-ACTION-002 Ensure manual only-X requests exactly X platform prerequisites and never
  requires or enables another captured source.
- [ ] TODO-ACTION-003 Let Wi-Fi and Cell request Location permission only when Android requires it
  for their own APIs; never convert that permission into Location capture.
- [ ] TODO-ACTION-004 Keep automatic capture-source and control-source settings separate, visible,
  purpose-consented, and independently revocable.
- [ ] TODO-ACTION-005 Add separate default-off ambient settings only for approved Steps, Location,
  Wi-Fi, and Cell products; keep Pressure off and Activity control distinct from capture.
- [ ] TODO-ACTION-006 Present exact disabled, unsupported, permission, OS, control-unavailable,
  waiting, degraded, storage, and failure remediation.
- [ ] TODO-ACTION-007 Connect selected-entry deletion, day or purpose deletion, all-data deletion,
  export, and import to typed source-specific services.
- [ ] TODO-ACTION-008 Author decision, navigation, permission-result, denial, revocation, and
  accessibility tests without executing them.

## Privacy, retention, export, import, and deletion

- [ ] TODO-PRIV-001 Represent retention policy per source and purpose without choosing unresolved
  final product durations in code.
- [ ] TODO-PRIV-002 Apply source-local retention from authenticated fact identity and retained
  floors, preserve necessary boundary facts, and expose resulting partial coverage.
- [ ] TODO-PRIV-003 Remove old consent demand immediately, reject delayed old-epoch callbacks, and
  schedule exact scoped deletion where required.
- [ ] TODO-PRIV-004 Make selected entry, day, purpose, source, consent reset, and collected-data
  deletion use exact source identities rather than QUIESCED, wall overlap, sample count, or inferred
  Location ownership.
- [ ] TODO-PRIV-005 Ensure deletion fences precede removal and survive replay, backfill, import,
  restore, process restart, writer rollback, and key rotation.
- [ ] TODO-PRIV-006 Make portable export include released captured and ambient products with
  provenance, completeness, corrections, and deletion scope while excluding CONTROL evidence and
  concealed raw radio identities.
- [ ] TODO-PRIV-007 Make merge import stage validated provenance-bearing source facts through the
  source writer and never bypass policy, owner, retention, collision, or deletion fences.
- [ ] TODO-PRIV-008 Keep technical rollback nondestructive; only explicit deletion or consent policy
  performs destructive privacy work.
- [ ] TODO-PRIV-009 Keep diagnostics payload-free, local-only, and free of precise coordinates,
  radio identifiers, tracked facts, telemetry, analytics, or automatic upload.
- [ ] TODO-PRIV-010 Keep production network use within the existing controlled NetworkGateway and
  user-enabled map-resource policy; tracking infrastructure adds no new egress.
- [ ] TODO-PRIV-011 Author retention, consent, export, import, deletion, restore, key-rotation, and
  no-resurrection tests for every applicable source without running them.

## Product decisions that must be resolved before affected activation

- [ ] TODO-DEC-001 Decide whether Step corroboration remains an explicit automatic CONTROL option.
- [ ] TODO-DEC-002 Decide the single Ambient Steps continuity provider strategy and whether its
  promise is opportunistic or visible-foreground continuous.
- [ ] TODO-DEC-003 Decide final retention durations and user-facing privacy copy for each source and
  purpose, including short-lived control evidence.
- [ ] TODO-DEC-004 Decide whether ambient Location offers a product beyond passive opportunistic
  points.
- [ ] TODO-DEC-005 Decide whether measured active Wi-Fi attempts become a user-selectable mode.
- [ ] TODO-DEC-006 Decide whether measured Cell refresh attempts become a user-selectable mode.
- [ ] TODO-DEC-007 Decide whether identity-dependent Wi-Fi or Cell unique or new products justify a
  keyed identity lifecycle; otherwise retain identity-free products only.
- [ ] TODO-DEC-008 Decide whether a calibrated, fused, explicitly labelled Pressure-derived
  vertical estimate belongs in product scope.
- [ ] TODO-DEC-009 Decide the final cross-midnight session counting and presentation rule.
- [ ] TODO-DEC-010 Decide the allowed rollout evidence channel; no remote telemetry or canary claim
  is implied.
- [ ] TODO-DEC-011 Decide separately whether any legacy source writer is retired after its concrete
  candidate passes shadow, continuity, rollback, and product gates.

## Implementation-complete gate before any validation begins

- [ ] TODO-ASSEMBLY-001 Every source has manual only-X production logic and focused tests authored.
- [ ] TODO-ASSEMBLY-002 Every source has automatic only-X logic with exactly declared controls and
  focused tests authored, or a deliberate typed unavailable product outcome where no legal control
  exists.
- [ ] TODO-ASSEMBLY-003 Every approved ambient mode is default-off, explicitly consented,
  sessionless, useful, retained, exportable, deletable, visible, and covered by authored tests.
- [ ] TODO-ASSEMBLY-004 Every source has durable admission, one source-local candidate or protected
  writer, replay or correction, retention, export/import, deletion, no-resurrection, history, and
  applicable UI code plus tests authored.
- [ ] TODO-ASSEMBLY-005 Every existing numeric and effect consumer has been audited and converted to
  qualified completeness semantics where applicable.
- [ ] TODO-ASSEMBLY-006 Every known source branch contains coherent IMPLEMENTED_UNVALIDATED commits,
  no accidental rollout activation, and an exact handover record.
- [ ] TODO-ASSEMBLY-007 All product decisions required by assembled code are resolved or their
  affected behavior remains explicitly unavailable and inactive.
- [ ] TODO-ASSEMBLY-008 Create one dedicated local convergence branch from the then-current clean
  local dev/v10 and compose only the reviewed source commits in dependency order.
- [ ] TODO-ASSEMBLY-009 Freeze convergence inputs and record expected failures, commands, device,
  and environment before lifting the implementation-only validation stop.

## Deferred validation and correction phase

Do not execute any item in this section until TODO-ASSEMBLY-001 through TODO-ASSEMBLY-009 are
complete and the implementation-only stop is explicitly lifted.

### Source, schema, and repository gates

- [ ] TODO-VERIFY-001 Compile every affected main, test, Android-test, Hilt, and release source set.
- [ ] TODO-VERIFY-002 Run the focused unit, contract, Robolectric, in-memory Room, and architecture
  tests authored for each source and fix failures.
- [ ] TODO-VERIFY-003 Run complete affected-module test suites and fix cross-module failures.
- [ ] TODO-VERIFY-004 Run the populated v27-to-v28 migration, production reopen, backup or restore,
  import, query, deletion, and no-resurrection cases on one representative emulator.
- [ ] TODO-VERIFY-005 Run root Detekt and affected-module lint; fix new findings without absorbing
  unrelated baseline debt or weakening rules.
- [ ] TODO-VERIFY-006 Run checkRoomSchemaDrift and review the exact committed v28 schema.
- [ ] TODO-VERIFY-007 Run ciUnitTest on the frozen convergence commit and fix all attributable
  failures.
- [ ] TODO-VERIFY-008 Run ciCheck --continue on the frozen convergence commit and fix all
  attributable failures.
- [ ] TODO-VERIFY-009 Repeat focused, affected-module, schema, ciUnitTest, and ciCheck gates after
  every convergence correction until the exact final commit is green.

### Representative Android evidence

- [ ] TODO-DEVICE-001 Run the exact manual Steps-only scenario: capture set exactly Steps; no other
  capture, control, or ambient demand; positive post-baseline delta reaches RECORDING; one writer
  reaches MATERIALIZED; production history reaches QUERYABLE; UI is truthful; listener is removed.
- [ ] TODO-DEVICE-002 Run Pressure-only capture on one representative FIFO-capable or honestly
  non-FIFO device and inspect callback cadence, batching, windows, gaps, writer output, query, UI,
  and listener removal.
- [ ] TODO-DEVICE-003 Run protected Location-only manual capture and automatic capture with declared
  control, checking accuracy, freshness, route or coverage, FGS legality, history, and no second
  writer.
- [ ] TODO-DEVICE-004 Run Activity-only manual and automatic trigger-handoff scenarios, proving
  captured versus CONTROL separation and stale-trigger rejection.
- [ ] TODO-DEVICE-005 Run Wi-Fi-only manual, passive ambient, and automatic scenarios, proving fresh
  provider-time admission, bounded direct scans, OS throttling state, privacy-minimized product,
  query, UI, and no Location demand.
- [ ] TODO-DEVICE-006 Run Cell-only manual, passive ambient, and automatic scenarios, proving
  per-child timestamps, bounded refresh, multi-SIM partial state, privacy-minimized product, query,
  UI, and no Location demand.
- [ ] TODO-DEVICE-007 Run enable, disable, permission revoke, consent revoke, delayed callback,
  process death, reboot, app update, force-stop boundary, FGS rejection, deletion, replay, import,
  and rollback scenarios proportionally for each affected source.
- [ ] TODO-DEVICE-008 Inspect rendered Today, Timeline, Calendar, detail, live or recent, settings,
  permission, export, import, and deletion states with accessibility, large text, and truthful
  zero or partial representation.

### Quality, battery, and cutover evidence

- [ ] TODO-MEASURE-001 Measure provider registration duration, restarts, event and batch counts,
  writes or transactions, callback-to-durable latency, projection latency, CPU, wakeups, and energy
  for each real acquisition rung on representative hardware.
- [ ] TODO-MEASURE-002 Remove or merge any acquisition rung without a demonstrated quality,
  latency, continuity, or battery difference.
- [ ] TODO-MEASURE-003 Confirm paid active attempts are bounded, direct-demand-only, and stop on
  qualified evidence, cancellation, or budget exhaustion.
- [ ] TODO-MEASURE-004 Confirm optional enrichment never changes provider registration or primary
  source completeness.
- [ ] TODO-MEASURE-005 Shadow-compare every proposed legacy-writer replacement against its protected
  quality vector before any owner cutover.
- [ ] TODO-MEASURE-006 Reject candidates that reduce quality without reducing cost or increase cost
  without a justified product improvement.
- [ ] TODO-MEASURE-007 Rehearse source-local contain, drain, catch-up, cutover, rollback, deletion
  rearm, and retained-fact readability without two canonical writers.
- [ ] TODO-MEASURE-008 Record the exact limits of host, emulator, provider, process, reboot, FGS,
  battery, OEM, rendered UI, and accessibility evidence; do not generalize one device into an OEM
  matrix or wake-reliable radio claim.

### Final review, local integration, and publication boundary

- [ ] TODO-FINAL-001 Run fresh proportional adversarial review at the converged storage and
  lifecycle boundary, product/history boundary, privacy/export/deletion boundary, and final diff.
- [ ] TODO-FINAL-002 Resolve every scoped blocker and high-severity finding and rerun the affected
  proof on the corrected exact commit.
- [ ] TODO-FINAL-003 Confirm every source has exactly one canonical writer, every only-X scenario
  has the exact registration set, and all product surfaces agree on source facts and completeness.
- [ ] TODO-FINAL-004 Confirm the candidate paths remain default-off until their individual
  activation gate and rollback are authorized.
- [ ] TODO-FINAL-005 Rebase the final convergence branch onto the latest clean local dev/v10 and
  rerun the required final gates on the rebased exact commit.
- [ ] TODO-FINAL-006 Merge the completed branch into local dev/v10 from the clean integration
  worktree, then verify the local merge and preserve the six root protected paths.
- [ ] TODO-FINAL-007 Remove only merged temporary worktrees and local branches after exact target
  verification; preserve any still-unmerged or user-owned work.
- [ ] TODO-FINAL-008 Update IMPLEMENTATION_STATUS.md, DECISIONS.md, VERIFICATION_MATRIX.md,
  ROLLOUT_RUNBOOK.md, CONTINUATION_HANDOVER.md, and MACHINE_HANDOVER.md with exact final evidence
  and remaining authorization boundaries.
- [ ] TODO-FINAL-009 Do not push, publish, activate, tag, deploy, release, or begin external rollout
  without fresh user authorization after the local result is complete.

## Explicit exclusions

The completion effort does not include a universal materializer language, generic attribution
graph, generic tombstone platform, permanent observer network, per-row query fan-out, all-source
synchronized sample schema, new UI platform, mandatory Days-first rewrite, enterprise telemetry,
remote tracking upload, learned battery optimizer, exhaustive OEM matrix, continuous ambient
Pressure, default active ambient radio scans, unmeasured wake-reliable radio cadence, fabricated
Pressure elevation, or a second simultaneous canonical Location writer.
