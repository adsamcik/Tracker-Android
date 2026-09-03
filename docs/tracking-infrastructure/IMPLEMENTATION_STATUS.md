# Tracking Infrastructure Implementation Status

Last updated: 2026-09-03

Execution-grade work items, ownership, dependency gates, verification commands, and rollback
behavior now live in `EXECUTION_PLAN.md`. This status file remains the checkpoint summary and
evidence index.

Current accepted code checkpoint: `711af5cd0` on local `dev/v10`, before this status document's
containing commit. It includes the accepted source-qualified Steps product and portable-export
foundations, dormant Pressure facts and selected deletion, correction-safe Steps deletion, and the
TI-D141 regression repair. A selected-session deletion now still validates every surviving run and
fact when no daily summary exists, and the tracker-engine Pressure fixtures once again encode the
official permanent destination, owner generation, and projection binding. The complete topology,
parallel draft inventory, and protected-root boundary are recorded in
`CONTINUATION_HANDOVER.md`.

The immediate product boundary is unchanged: the exact representative-device manual Steps-only
chain has not run, so provider callbacks, listener removal, rendered UI, process death, reboot,
foreground-service behavior, battery cost, and OEM behavior remain unproven. The truthful live/UI
slice is verified but uncommitted; awards/retention work has open integrity blockers; portable
import remains frozen and unaccepted. Everything is local-only. No push, release, artifact
publication, deployment, remote configuration, feature activation, destructive migration, or
external rollout occurred.

## Current gate

- Current phase: TI-410 manual/session Steps is `IN_PROGRESS`; its atomic writer-transition
  foundation is `IN_REVIEW`. Durable service-run provenance, dormant facts/receipts/cursor,
  historical selection, exact destination-owner fencing, and explicit candidate activation,
  rollback, and post-deletion generation reconstruction now exist. Legacy generation 1 remains the
  only active production owner; first activation and rollback have no ordinary production caller.
- Gate status: `TRANSITION_FOUNDATION_IN_REVIEW / OBSERVABLE_FACADE_PRESENT /
  SELECTED_DETAIL_CONSUMER_CONTAINED / DELETION_AUTHORITY_DORMANT /
  EMPTY_CLEANUP_CONTAINED / CRASH_PLACEHOLDER_READS_CONTAINED /
  STEPS_REVERSE_BINDING_ENFORCED / EXACT_STEPS_HISTORY_COMPOSED /
  LIVE_STEPS_PRESENTED / BOUNDED_DASHBOARD_QUERYABLE /
  MANUAL_DEVICE_HARNESS_COMPILES / MANUAL_DEVICE_GATE_BLOCKED /
  BROADER_PRODUCT_WIRING_BLOCKED`. TI-B160 connects the
  production deletion provider, default `AppDatabase` deletion, singleton lifecycle/coordinator,
  durable pending marker, and closed startup/deletion barrier through a forced post-rearm diagnostics
  failure and successful retry. The final test passes twice consecutively on the same retained
  `Medium_Phone(AVD) - 16` installation and once more after the required no-op rebase. Three fresh R1
  adversaries found two `HIGH` test-determinism issues; both were mitigated before those runs.
  Complementary focused engine suites prove stale
  epoch/owner work rejection, WAL recovery, projection non-resurrection, and high-water rebasing.
  TI-D108/TI-B162 add one observable selected-session contract whose independent availability,
  evidence, product, coverage, and cause axes fail closed. TI-D112/TI-B168 consume it only from a
  lifecycle-scoped selected Trip Detail, without changing a writer or provider. TI-D109/TI-B163–
  TI-B165 add only dormant deletion-fence authority after
  withdrawing unsafe production wiring. TI-D110/TI-B166–TI-B167 retire the racy periodic database
  mutation and exclude every zero-sample row from the named product and ActivityRecognition reads,
  while leaving other DAO paths unchanged and claiming no durable reclamation.
  TI-D111 narrows the next wave after scope review. TI-D113/TI-B169 bind each physical service run
  to one presentation segment and persist writer quiescence without reclaiming any row.
  TI-D114–TI-D118 add exact reverse binding, capture/qualification authority, logical grouping, and
  finite action-bearing composition. TI-D119/TI-B176 add exact live presentation. TI-D120/TI-B177–
  TI-B178 connect the finite composed read to stopped Dashboard history with stable physical
  ordering and no raw Last Session bypass. TI-D121/TI-B179 establish the synthetic positive WAL
  boundary. TI-D122/TI-B180 add the compiled disposable harness without activating an ordinary
  product path. The bounded Dashboard query passes; day/Calendar/Statistics composition,
  remaining completeness-safe consumers, portable import/no-resurrection, broader-source and
  device deletion evidence, cold process/reboot/provider evidence, the exact manual only-Steps
  device scenario, and every other source/mode remain blocked.
- Integration owner: lead orchestrator
- Structural implementation: Workstream A policy authority plus the retained v28
  manifest/lifecycle/broker schema, Room-first source-runtime coordinator demands, physical
  provider configuration identity, immutable observed-time authorization revisions, callback
  identities, and byte-authenticated observation identities are implemented. The 12 unused generic
  Phase 3 tables/repositories remain removed. Activity has the first durable automatic-action and
  atomic callback lane; Steps has one app-scoped shared physical controller. These are foundation
  seams, not production materializers or source gates.
- Blocking decisions: whether Steps corroboration remains an explicit broker-owned
  `CONTROL_AUTOSTART` option; ambient continuity promise and per-source
  retention/minimization/export/deletion behavior; local-only rollout evidence channel; and the
  final product decisions listed in `DECISIONS.md`. Existing enabled automatic mode grants bounded
  Activity `CONTROL`, never captured Activity. Process-wide startup ordering is now locally
  implemented and verified; production backup recovery, portable export/import, partial-database
  containment, and connected startup/reboot proof remain TI-184 gates.
- Current-worktree verification: at `1bb9749af`, `:app:compileDebugAndroidTestKotlin` passes in
  `26s` with 525 tasks (5 executed, 520 up-to-date), root `detekt` passes in `26s` with one task,
  and `:app:lintDebug` passes in `56s` with 1,073 tasks (7 executed, 1,066 up-to-date).
  `adb devices -l` returned no device rows, so the connected class command was not run. Fresh
  adversarial review found and the final code corrected cleanup arming, durable source-set audit,
  duplicate-zero admission, stale-baseline linkage, evidence-boundary wording, and a false
  registration-acceptance time bound for a supported synchronous callback. The final review found
  no remaining commit-blocking defect. This is instrumentation compilation plus static evidence,
  not provider, rendered-UI, listener-removal, process/reboot/FGS, battery, OEM, or rollout proof.
  Earlier, the post-review command selecting `TripDaoTest` plus five focused Dashboard
  recent-history suites, root `detekt`, and `:feature:dashboard:lintDebug` passes in
  `4m 21s` with 610 tasks (46 executed, 564 up-to-date). XML records `56/56` tests: core DAO
  `16/16` and Dashboard `40/40`, with zero failures/errors/skips. Lint reports zero errors and one
  previously recorded guarded `InlinedApi` warning for the API-29 Activity Recognition permission.
  All 28 Dashboard resource sets parse and contain exactly one copy of each of the nine affected
  recent-history/widget keys. Independent storage and product reviews have no remaining finding
  after the stale lifecycle replay was reset to typed `Loading` and covered by virtual-time
  unsubscribe/resubscribe evidence. The first sandboxed wrapper attempt failed before Gradle
  configuration because network access to the pinned distribution was denied; the approved rerun
  above is the counted evidence. This is host/resource evidence, not emulator/device, visual,
  provider/listener, process/reboot/FGS, battery, or OEM proof.
  Earlier, `9aeb8853a` passes the exact presentation lifecycle/component/
  store/orchestrator/crash suite (`BUILD SUCCESSFUL in 4m 20s`; 230 tasks), focused Room DAO and
  tracker-API descriptor tests, root Detekt (`13s`), affected-module lint (`2m 19s`; 383 tasks; no
  new issue), and committed-tree `checkRoomSchemaDrift` (`8s`; 46 tasks). The v27→v28 migration
  instrumentation source compiles, but no device is attached, so this revision has no connected
  migration execution. After `ciUnitTest` exposed one stale zero-cycle deletion assertion,
  `d067704a9` corrected that test to the all-source retention invariant; its focused class passes in
  `2m 7s`, and the full rerun passes in `7m 2s` with 990 tasks. Earlier, `f9b1c3c45` passes 33 focused selected-detail tests with zero
  failures/errors/skips (history mapping `10/10`, presenter `9/9`, ViewModel `6/6`, Compose `8/8`),
  root Detekt in `18s`, and `:feature:statistics:lintDebug` in `32s` with no new issue. It has not run
  full `ciUnitTest`, `ciCheck`, emulator/device, screenshot, accessibility, provider, or energy
  evidence. Earlier, `88309387d` passed focused worker/quiescer/complete
  architectural-fitness tests in `1m 9s` (575 tasks; worker `3/3`, quiescer `3/3`, architecture 37
  assertions) and its original Room DAO `11/11`. At code tip `24b9aeffb`, the corrected focused Room
  suite passes in `1m 5s` (79 tasks; XML `12/12`, zero failures/errors/skips), and root Detekt passes
  in `39s` (5 tasks). The most recent authoritative
  full gate remains code commit `c5118e186`: `ciCheck --continue --no-parallel` passed in `8m 32s`
  with 1,991 actionable tasks, and `ciUnitTest` passed in `12m 18s` with 990 tasks. The exact
  populated v27→v28 suite most recently remains `8/8` on
  `Medium_Phone(AVD) - 16` at the pre-reconciliation schema boundary. These are host plus prior
  migration-device checks, not provider/process/reboot/FGS, OEM, production-query, device-energy,
  source-scenario, or rollout proof.
- Focused source reviews: all six complete — Location (`DEGRADED`), Wi-Fi (`FAILED`), Cell (`FAILED`), Activity (`FAILED`), Steps (`DEGRADED`), Pressure (`FAILED`)
- Fresh adversarial review: prior R0/R1/R1b/R2 and adaptive-collections R3/R4 are complete. The
  prior 2026-08-24 R1 returned `BLOCK`. Commits `f14a4a2b1` through `c4d97333d` address its global
  acquisition reachability defect, unknown v27 runtime-boundary fabrication, deletion/drain race,
  stale Activity/Steps control reachability, startup/lifecycle authority, and the resulting fixture
  debt. Three new independent reviewers then returned aggregate `FAIL`: data/migration found
  metadata-only source activation and global live-projection head-of-line blocking; Android/power
  found all-enabled-or-nothing source rollout, infinite terminal-control recovery wakeups, and Cell
  cross-process replay identity; product/scope found capture-coupled Activity control, bundled Steps
  modes, stale evidence artifacts, and missing truthful Steps state axes. The shared findings are
  now committed and verified through `2b9265ce8` before a fresh rerun; Cell replay remains a
  Cell-specific gate. Crash-auditable
  radio handoff, failed-unregister ownership, portable
  export/minimization, immutable ambient day identity, and `ACTIVE` versus qualified `RECORDING`
  remain open. Materializers, ambient exposure, and history UI were prohibited at that gate.
- Initial scope proportionality review: `BLOCKED / RESCOPE`. Three fresh read-only adversaries concluded that the safety spine is justified but the then-current unshipped 23-table v28 expansion, generic Phase 3 platform, mandatory day-journal redesign, all-at-once ambient breadth, fleet telemetry, and exhaustive governance were disproportionate to the app's current offline personal-product workflows and runnable release infrastructure. The user subsequently confirmed ambient persistence as a product requirement; it proceeds only as independently gated, default-off source verticals. The follow-up disposition is the updated R2 gate below.
- Updated R2 proportionality gate: `IN_PROGRESS / RESCOPED`. v28 never shipped, so it was trimmed
  and regenerated in place rather than preserved or repaired in v29. The 12 ownerless generic Phase
  3 tables are removed and the duplicate lifecycle lease is unified. At that R2 checkpoint, the schema had `66`
  entities: all `51` released-v27 entities plus `15` narrowly owned v28 additions, including the two
  released-v27 recovery records, Activity automation action/epoch state, and one source-local
  product-lane activation/cursor/retention fence. This does not restore a generic platform.
- Adaptive collections target: TI-D054–TI-D087 and `ADAPTIVE_COLLECTIONS_GREENFIELD_DESIGN.md` define sole-source capture, explicit ambient/control authority, authorization-homogeneous source-native durable units, source-specific platform-mechanism ladders and handoffs, measured rather than assumed energy separation, item-level freshness/relevance admission, zero-effect exact replay, quality floors/targets, one supervisor/runtime owner per source, one thin data-plane mutation owner with source lanes created on demand, distinct delivery and logical correction identities, narrow deletion/owner fences, platform-legal automatic starts, one selected Ambient Steps continuity provider, explicit radio aggregate/identity tiers, optional query-time Location context, and one minimum production history facade. The current v28 runtime implements the shared authority/admission substrate and Activity's first atomic callback adapter; source-local facts and every product claim remain blocked.

## Observed repository state

- Acquisition and product-writer rollout are separate per-source state.
  `TrackingRolloutState`
  schema v3 defaults all six source owners to `CONTAINED` with retained legacy product stages. An
  absent row, a v27 row, or the old global-v2 event marker migrates to contained state and cannot
  register a provider. `RoomTrackingRolloutStateStore` rejects or repairs an `EVENT` marker unless
  the exact source has a matching active product lane, stage, writer generation, activation floor,
  initialized cursor, retention pin, and exact candidate destination owner. Shadow-lane installation
  and rollout activation are one Room transaction. The source-specific transition API has no
  ordinary first-activation caller, and no candidate canonical writer is active.
- Shared registration acceptance no longer advances the durable pointer or retires the accepted generation until a replacement is externally accepted. Activity uses generation-addressable PendingIntents for a real make-before-break swap, durable `RETIRING` cleanup, cancellation-transparent convergence and idempotent retry; failed replacement retains the accepted provider. Location, Wi-Fi, Cell, Steps and Pressure still use shutdown-before-start runtimes and require provider-specific fenced handoff/gap/rollback tests rather than inheriting an unsupported zero-gap claim.
- Legacy `TrackingParamsState` still exposes Boolean enablement and semantic frequency, but the production repository now normalizes both into one Room policy mutation and projects only the effective six-source snapshot. Corrupt/unknown legacy source semantics fail closed and cannot bootstrap consent.
- New durable source demands carry consumer identity, purpose, persistence eligibility, consent/policy epochs, logical tracking identity, effective boot/elapsed time, and QoS. Physical registration rows contain only provider configuration/identity, boot/data epoch and lifecycle state. Demand changes append an independent authorization revision without restarting a compatible provider.
- `source_event_wal` is durable and deduplicated. Admission resolves the physical half-open lifetime and immutable authorization revision at the observation's boot/elapsed time, derives capture identity from that authorization rather than caller stamps, persists control-only observations without session attribution, and rejects an unsplit interval that crosses an authorization boundary. Activity callback members are point units and now use one process-stable, order-normalized delivery identity; ambiguous migrated v27 queued rows still require complete one-time legacy-drain proof.
- TI-D055/TI-D056 establish the acquisition/persistence boundary: enrichment has no demand authority, while every valid source-qualified provider delivery gets a quality-preserving durable representation before purpose-specific use. Activity now implements the first complete callback splitter/admission adapter. Purpose-specific bounded retention, source-native freshness calibration, the other five splitters/identities, and removal of synchronous global drain work remain open.
- TI-D078–TI-D083 refine that boundary. The containment slice evaluates Wi-Fi/Cell children independently, rejects missing/non-positive/future/over-age provider timestamps, omits startup cache and failed-scan reads, keeps request outcomes out of the observation WAL, preserves each Wi-Fi child's provider time in payload v2 while retaining v1 decoding, records only provider-confirmed fresh empty results as zero coverage, withholds radio/subscription identifiers, advances live replay state only after durable/duplicate handoff, and grants one first-evidence active request only to direct capture while passive callbacks remain. Exact replay is still registration/process-local: source-native durable delivery identity, observed-time authorization, compact unchanged-content references, any measured repeated-attempt mode, keyed identity where product value later requires it, and device/runtime-fake evidence remain blocking before either radio materializer or product query is wired.
- The current semantic-frequency labels do not all represent physical power differences: Wi-Fi `CACHED_ONLY`/`BROADCAST_DRIVEN`, Cell Battery Saver/Balanced, and Activity Battery Saver/Balanced share their respective provider mechanism. Pressure changes requested rate/report latency, but the benefit depends on actual FIFO capacity and is undermined by per-sample runtime-state writes. `QualitativeBatteryImpactEstimator` explicitly has zero samples and low confidence. TI-D078 requires collapsing identical rungs and measuring the rest before user-visible battery claims.
- WAL rows recompute the payload hash from stored bytes before decoding and bind that verified
  checksum into the length-prefixed immutable envelope identity. Corrupt rows are durably
  quarantined before projector invocation, the contiguous cursor advances, and healthy later rows
  continue. The process-wide startup gate now makes the frozen one-time v27 drain terminal before
  providers/services/data consumers proceed, with deletion taking precedence. Connected
  migrate-to-runtime ordering and production backup recovery remain unverified. New admission is
  observation-first and sessionless; product attribution/materialization remains unwired.
- The unused generic attribution, writer-activation, contribution-receipt, counting-head, tombstone, partition-cursor, failure, and ledger-state platform has been removed from unreleased v28. Under TI-D066/TI-D071, stable delivery identity is the idempotence receipt while source-specific logical range identity handles correction; the first production vertical must prove both plus deletion/history invalidation before any generic mechanism is reconsidered.
- `TrackerServiceApi`, `DefaultTrackingStartRequestCoordinator`, `TrackerService`, and
  `AuthoritativeSessionCoordinator` implement the local two-phase start: durable prepared intent and
  source-runtime desired state precede the external service request; accepted runtime/FGS evidence
  follows, and rejected/failed starts are terminalized. Host crash-boundary and redelivery tests
  pass; connected Android legality and OEM recovery remain required.
- `TrackerService` is the sole tracking service owner; the separate `ActivityWatcherService` and
  restart receiver are removed. The service reconciles its foreground-service type candidates from
  the real start origin and accepted source plan. Pinned provider/device acceptance evidence remains
  a source gate.
- Reconfiguration appends immutable manifest and lifecycle-intent revisions. Effective time cannot regress within a boot domain, failed starts append terminal intent, and disabling the last accepted source cannot leave the logical session `ACTIVE`.
- Coordinator leases use boot identity, elapsed-realtime expiry, and monotonic generations. Release/expiry reacquisition increments the generation and stale tokens are fenced; SQL state-transition CAS and a durable lease/action reconciler remain absent.
- Runtime acceptance moves the session to `ACTIVE`; source-qualified `RECORDING`, `MATERIALIZED`, and `QUERYABLE` lifecycle evidence remain distinct but are not yet represented.
- Legacy automatic-mode upgrade semantics are now explicit: an enabled existing automatic preference grants nonpersistent Activity `CONTROL` only, and later mode transitions append control consent epochs without changing Activity capture consent. Automatic requests still lack the complete durable trigger envelope and fail closed after Android service request, so this is upgrade preservation rather than automatic-mode completion.
- `DailySummaryAggregator` still rebuilds from `SessionSegment`, and History/Calendar still fabricate
  zero-looking summaries from missing data. A Hilt-bound `TrackingHistoryRepository` now observes one
  selected local session and preserves Steps availability, evidence, product state, coverage, and
  named causes. Trip Detail is its first contained read-only consumer; ordinary history discovery,
  day/list/live composition, and safe deletion remain unchanged. A persisted universal `DayOverview`
  remains intentionally outside this source-local gate.
- Location production legality is bypassed: `LocationSourceRuntime` evaluates `SESSION_ALREADY_FOREGROUND` instead of the real origin, while the production capability provider hardcodes FGS/start legality. Batched pre-start fixes can also be admitted because recording freshness is not checked against the capture boundary.
- Location writer ownership is contradictory: the established `LocationTrackerComponent`/`PersistenceProcessor` path remains product-visible, while rollout metadata calls `LocationDomainProjection` canonical even though its output effects have no production consumer. The established writer remains protected pending shadow evidence and an explicit cutover decision.
- Wi-Fi capability, onboarding, settings, and manifest now agree on the scan APIs actually used: precise location plus Wi-Fi state/change permissions and enabled Location Services; an unrelated Nearby Devices grant is not required. Startup cache persistence is removed and generic ingress resolves post-start observed-time authorization; durable cross-process radio delivery identity remains unimplemented.
- New Wi-Fi/Cell runtime payloads withhold radio and subscription identifiers until the rotatable purpose/epoch HMAC lifecycle is implemented. Released legacy storage/export/UI still expose raw radio identifiers and require a scoped migration/product decision rather than silent rewriting.
- Cell callbacks are item-age filtered and a genuinely empty callback can now prove zero coverage; generic ingress resolves authorization at each admitted callback/item timestamp. Process-stable replay identity, multi-SIM partial-failure semantics and a destination writer remain missing.
- Previous-exit, explicit-stop, force-stop, boot, and inactive-descriptor finalizers now converge
  stale automatic/runtime state without restarting `STOPPING` or terminal sessions in host tests.
  Connected process-kill/reboot proof is still required before an automatic source gate.
- Activity has a shared physical-registration arbiter whose PendingIntent carries only physical identity/configuration. Purpose/policy/manifest/consent changes rotate observed-time authorization without restarting unchanged GMS configuration; one parsed callback is now admitted in one Room transaction and sparse eligible members map back to the exact original recognition/transition indexes. Exact duplicate delivery reloads/recovery-drains but cannot repeat motion, backend flows or the receiver cache. Provider replacement is accepted before the old generation's half-open retirement boundary; full process-wide durable-demand/provider recovery remains blocked.
- Activity automation still drops parts of the durable trigger envelope before external start, source-native staleness thresholds are not calibrated, the synchronous global drain remains, and no typed movement-band materializer or production query exists. The adapter is admission evidence, not an Activity source gate.
- `SharedStepSourceController` now owns one app-scoped physical counter registration shared by
  capture and any explicitly declared corroboration demand. The product decision whether Steps
  corroboration remains `CONTROL_AUTOSTART` is still unresolved; the implementation cannot make it
  a hidden capture dependency. Runtime callbacks and baselines carry immutable
  generation/eligibility identity, and an effective-boundary change makes the next callback
  baseline-only instead of attributing a disabled interval.
- Steps legacy session totals are additively mutated before the enclosing event-frame outbox acknowledgement. A crash at that boundary can apply the same effective contribution twice even though `StepInterval.sourceSignalId` is unique.
- Pressure callback tokens and accumulator checkpoints now prevent restored partial windows from mixing registration generations. Qualified capture windows can also enter a dormant append-only session-fact lane under exact run/segment, writer, manifest, policy, consent, deletion, and completeness authority. The lane is not activated, and provider-active, first durable sample, materialized fact, queryable product, and stable trend readiness remain distinct lifecycle/product states.
- Pressure-only sessions can be retained, yet `DailySummary` has no Pressure metric and the production UI can show a zero-distance/zero-step shell. Stored standard-atmosphere `altitude_m` is not calibrated elevation and cannot be promoted as vertical history.

## Focused source review evidence

| Source | Current verdict | Acquisition / durability | Canonical / product proof | Blocking evidence |
| --- | --- | --- | --- | --- |
| Location | `DEGRADED` | Immutable registration identity and boot/effective-time WAL admission exist; source-qualified accuracy/freshness state does not | Established writer still serves product; event projection has no consumer; no `DayOverview` | real origin discarded, global ownership absent, hidden Steps, ghost sessions, contradictory writer ownership |
| Wi-Fi | `FAILED` | Fresh post-registration broadcast children and confirmed-empty coverage can reach observed-time-authorized WAL after item-level age checks; payload v2 retains item time and withholds identity; startup cache/failed updates are contained; direct capture has one first-evidence scan request | Legacy `WifiObservation` path only; no terminal event materializer or day query | cross-process cache replay, source-native boundary/identity proof, any measured repeated-attempt mode, keyed identity lifecycle if approved, runtime/device proof, no qualified recording state |
| Cell | `FAILED` | Fresh timestamped callback children and confirmed-empty coverage can reach observed-time-authorized WAL; operational outcomes and persistent radio/subscription identity are omitted; direct capture has one first-evidence refresh group | Legacy `cell_sample` only; joined event frame has no terminal writer or day query | cross-process identity, source-native boundary proof, any measured repeated-refresh mode, hidden controls, multi-SIM partial failure and device proof absent |
| Activity | `FAILED` | Shared GMS physical arbiter, independent observed-time purpose authorization, atomic callback delivery, sparse writer-stamped WAL admission and zero-effect replay exist | Activity-only input has no terminal materializer or day query | stale/epoch-incomplete automation start, synchronous global drain, no movement-band writer/owner, no production query |
| Steps | `DEGRADED` | Positive deltas reach WAL; generation-bound baselines reject disabled-gap relabeling | `StepInterval` and legacy session/day totals exist; one selected Trip Detail consumes the durable read facade, but the additive aggregate is replay-unsafe and no truthful outside-session day contract exists | ordinary source-only discovery, duplicate physical listeners, corroboration decision, no typed recompute/deletion path, ambient not implemented |
| Pressure | `FAILED` | Direct sensor runtime, generation-homogeneous qualified aggregate WAL windows, and a dormant source-local fact projector exist; first-sample `RECORDING` state does not | append-only `pressure_fact_revision` and exact writer provenance exist, but the lane is not activated and no qualified day query or Pressure history UI exists | no qualified recording/query state, no product read/deletion/retention/portable contract, uncalibrated altitude risk, no device proof |

## Dependency and ownership map

| Order | Boundary | Primary files/modules | Owner | Edit state |
| --- | --- | --- | --- | --- |
| 1 | Policy and consent epochs | `:core:base`, `:data:preferences`, tracker planning/status | lead orchestrator | local implementation in review |
| 2 | Manifest and desired lifecycle state | `:core:base`, `:tracker:engine` coordinator/service | lead orchestrator | append-only schema and source-runtime intent implemented locally; Android gateway/reconciler blocked |
| 3 | Purpose broker and physical ownership | `:core:base`, `:tracker:engine`, source runtimes | one `SourceSupervisor` owner with bounded source adapters | physical/auth lifetime split is locally implemented; process reconciliation, callback barriers and device matrix remain blocked |
| 4 | Observation and source facts | `:core:base`, `:tracker:engine`, `:stats:data` | one `TrackingWriter` mutation owner; six source-local projector owners | authenticated WAL/quarantine implemented; single mutation boundary, source cursors, typed provenance, idempotent facts/recompute and scoped deletion remain |
| 5 | Minimum history product | one `TrackingHistoryRepository` composing existing Today/Timeline/Calendar/selected-day plus export/delete/map | unassigned until prior contracts land | audit only; no source-only fact may disappear behind a legacy trip requirement |
| 6 | UI | `:feature:statistics`, `:feature:dashboard` | unassigned until the sealed per-source completeness/query contract stabilizes | audit only; full Days-first journal remains deferred |
| Cross-cutting | Quality, privacy, lifecycle, rollout | tests, diagnostics, device evidence, these artifacts | lead orchestrator | in progress |

Source reviewers are read-only and own no files. Only one implementation owner will be assigned to an overlapping file set at a time.

## Phase 3 R1 adversarial finding dispositions

Three fresh read-only reviewers attacked the then-current ledger, migration, attribution, activation, pruning, deletion, and test diffs. This is a historical record: generic-specific findings were superseded when TI-D051 removed that platform. `MITIGATED_LOCALLY` records a scoped code/test correction; it does not pass Phase 3 or authorize a writer.

| Finding | Severity | Disposition | Evidence / remaining gate |
| --- | --- | --- | --- |
| P3-R1-D01 duplicate v28 integrity migration | `BLOCKER` | `MITIGATED` | duplicate `ALTER TABLE` statements removed; the later four-case connected migration suite validates the final containment schema |
| P3-R1-D02 delayed APPLY after consent revoke | `BLOCKER` | `MITIGATED_LOCALLY` | APPLY rechecks current consent inside the destination/receipt transaction; RETRACT remains allowed to restore zero |
| P3-R1-D03 raw-prune/tombstone prevented retraction | `BLOCKER` | `MITIGATED_LOCALLY` | retained receipt/attribution can retract after WAL pruning and deletion fences block APPLY rather than zero-sum RETRACT |
| P3-R1-D04 retry/cancellation/integrity/hash defects | `HIGH` | `MITIGATED_LOCALLY` | terminal quarantine preflight, `CancellationException` propagation, recomputed envelope integrity, length-prefixed identities, and cross-generation duplicate tests pass |
| P3-R1-D05 attribution counting intent drift | `HIGH` | `MITIGATED_LOCALLY` | same-revision counting-intent changes reject as an identity collision; deterministic mandatory counting remains P3-R1-P03 |
| P3-R1-D06 session-bound raw admission | `BLOCKER` | `ACCEPTED` | globally admit one immutable provider observation before any session association; shared-session callback race test required |
| P3-R1-D07 poison before partition identity | `BLOCKER` | `ACCEPTED` | corrupt raw payload can still block later global ordinals before a materializer partition is known |
| P3-R1-P01 actual canonical destination/legacy writer is unfenced | `BLOCKER` | `ACCEPTED` | typed contract/destination registry and durable bootstrap/drain of every legacy writer are required; Location remains protected |
| P3-R1-P02 source-qualified `RECORDING` is caller-asserted | `BLOCKER` | `ACCEPTED` | source decoders must append typed qualification evidence and lifecycle transitions for all six source contracts |
| P3-R1-P03 counting is optional and caller-order dependent | `BLOCKER` | `ACCEPTED` | additive contracts require centrally deterministic counting algebra and a global decision revision |
| P3-R1-P04 destination day/scope is caller-controlled | `BLOCKER` | `ACCEPTED` | typed contract must derive destination scopes from stable attribution/day-slice records and atomically move corrections |
| P3-R1-P05 no dirty-day or production query transition | `BLOCKER` | `ACCEPTED` | every applicable attribution/contribution transition must atomically require day repair; `DayHistoryRepository` is absent |
| P3-R1-P06 scoped deletion/key rotation is not orchestrated | `BLOCKER` | `ACCEPTED` | atomic tombstone, contribution reversal, dirty-day, key-rotation, export, and no-resurrection path is required |
| P3-R1-P07 READY/cutover has no coverage/parity evidence | `HIGH` | `ACCEPTED` | durable contiguous coverage, parity, outgoing drain, product query, and rollback evidence must gate READY/cutover |
| P3-R1-P08 active writer pins retention floor | `HIGH` | `ACCEPTED` | add gap-aware per-input completion/cursors; current activation start protects history indefinitely |
| P3-R1-P09 ambient/control attribution is absent | `HIGH` | `ACCEPTED` | purpose-specific `BETWEEN_SESSIONS`/`CONTROL_ONLY` attribution and independent privacy/product tests are required |

## Phase 3 R1b pre-wiring adversarial finding dispositions

This second fresh read-only round is the explicit stop gate before any source materializer or day-product UI wiring. Reviewers independently re-read the full design and live diff; no reviewer edited files or ran Gradle. Generic attribution/receipt findings are retained as historical evidence but were superseded by TI-D051; WAL byte-authentication and raw-poison findings are now mitigated locally. All other findings remain accepted unless explicitly rejected with evidence.

| Finding | Class | Severity | Disposition | Evidence / required correction |
| --- | --- | --- | --- | --- |
| P3-R1B-D01 persisted payload bytes are not authenticated | `NEW` | `BLOCKER` | `MITIGATED_LOCALLY` | WAL recomputes the checksum from payload bytes before decode and raw quarantine prevents projector invocation. Generic receipt/head storage was removed under TI-D051; future typed serialized facts inherit this trust-boundary rule. |
| P3-R1B-D02 counting correction can leave multiple effective day/contract heads | `NEW` | `BLOCKER` | `ACCEPTED` | counting-head identity includes day and update only touches the new day; use one global counting decision/revision and atomically retire the old derived scope while installing the new one. |
| P3-R1B-D03 foreign-source legacy WAL blocks unrelated backfill | `NEW` | `HIGH` | `ACCEPTED` | `minimumUnqualifiedIntegrityOrdinal()` is global; processable gaps/floors must be source/contract scoped and reported through completeness. |
| P3-R1B-D04 same-revision attribution drift is called duplicate | `NEW` | `HIGH` | `ACCEPTED` | duplicate validation omits manifest, zone, freshness boundary/status, purpose, and classification; complete decision identity/equivalence is required. |
| P3-R1B-D05 force-stop finalization leaves active broker demands | `NEW` | `BLOCKER` | `ACCEPTED` | finalizer terminates sessions/runs but does not retire `session:<id>` demands, registration eligibility, or pending actions; one fenced recovery transaction and stale-generation callback test are required. |
| P3-R1B-D06 cancellation becomes poison/storage/terminal failure | `NEW` | `HIGH` | `ACCEPTED` | ingress, Activity recovery, projection dispatcher, retention worker, and coordinator catch cancellation broadly. Rethrow `CancellationException`; do not consume attempts, move checkpoints, or schedule retry. |
| P3-R1B-D07 low-storage admission has no durable recovery state | `CONFIRMED` | `HIGH` | `ACCEPTED` | SQLite-full collapses into a short in-memory retry/storage error while lifecycle may remain ACTIVE; persist a truthful degraded/failed state and a safe provider-specific retry contract. |
| P3-R1B-D08 raw admission remains session-bound | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | shared-session callback can be rejected before one immutable observation exists; globally admit registration-stamped evidence before zero-to-many attribution. |
| P3-R1B-D09 corrupt global input blocks later partitions | `CONFIRMED` | `BLOCKER` | `MITIGATED_LOCALLY` | coordinator catches a source/ordinal integrity exception, records terminal raw quarantine for every projection, advances contiguous progress, and applies a healthy later ordinal. Exact multi-source device/event coverage remains. |
| P3-R1B-D10 free-form owner does not fence actual/legacy destination | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | typed destination registry, legacy-owner bootstrap, coverage/parity/drain/deletion/product evidence, and destination-level uniqueness are required. |
| P3-R1B-D11 retained-floor activation pins WAL forever | `CONFIRMED` | `HIGH` | `ACCEPTED` | pruning protects immutable activation start instead of earliest unresolved input; use gap-aware contiguous completion/cursors. |
| P3-R1B-D12 scoped deletion/key rotation/dirty-day protocol absent | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | implement one recovery-safe tombstone, zero-sum reversal, dirty-day, export, and HMAC rotation protocol before backfill/materialization. |
| P3-R1B-D13 exact nonempty v27→v28 migration unexecuted | `CONFIRMED` | `BLOCKER` | `MITIGATED_LOCALLY` | populated six-source/WAL/runtime fixture now migrates, production-reopens/queries, deletes and reopens on `Medium_Phone`; portable export/import, backup recovery and released legacy-WAL drain remain TI-184. |
| P3-R1B-A01 Android service/FGS still precedes durable intent | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | persist service/FGS desired action and complete trigger first; reconcile denial as durable retryable legality state. |
| P3-R1B-A02 stale automation outbox lacks boot/registration/consent epochs | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | persist and CAS the complete trigger/automation envelope before external start/stop effects. |
| P3-R1B-A03 radio identifiers remain linkable across privacy epochs | `CONFIRMED` | `HIGH` | `ACCEPTED` | replace unkeyed hashes/raw subscription identity with purpose/epoch HMAC and atomic rotation; fence legacy raw writes/export. |
| P3-R1B-A04 Doze/OEM cadence remains unmeasured | `CONFIRMED` | `MEDIUM` | `DEFERRED_BY_EXPLICIT_DECISION` | keep Wi-Fi/Cell promises opportunistic; representative device evidence is required before any stronger acquisition claim. |
| P3-R1B-A05 settings authority/error UI violates the pre-product boundary | `NEW` | `MEDIUM` | `REJECTED_WITH_EVIDENCE` | the existing settings surface is a Phase 1 fail-closed policy repair/status surface required by one-authority truthfulness, not DayOverview/materializer product wiring. No source/day product consumer calls Phase 3 repositories. |
| P3-R1B-P01 wall-time uncertainty crossing midnight is labeled exact | `NEW` | `BLOCKER` | `ACCEPTED` | day derivation ignores `wallTimeUncertaintyMs`/clock-uncertain quality and always labels a parsable point `KNOWN`; derive an interval in the structural zone and preserve ambiguity/partial completeness when it spans a boundary. |
| P3-R1B-P02 all 12 scenarios stop before product proof | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | no typed source qualification caller, source materializer, day repository, or presenter graph exists; all twelve rows remain blocked. |
| P3-R1B-P03 current product paths fabricate zeros/completeness | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | nullable scalar `DailySummary` consumers substitute zeros/no-activity; sealed policy/evidence/materialization/completeness state must precede day UI wiring. |
| P3-R1B-P04 ambient/control facts have no product attribution | `CONFIRMED` | `HIGH` | `ACCEPTED` | attribution is hard-coded to `SESSION_CAPTURE`/`IN_SESSION`; the accepted ambient product scope remains unimplemented/default-off and control-only remains non-product until purpose-specific privacy/query contracts exist. |
| P3-R1B-P05 dirty-day lost-wakeup protocol absent | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | successful receipt commits no monotonic dirty-day requirement; implement conditional revision publish/ack before claiming `QUERYABLE`. |
| P3-R1B-P06 explanation/export/deletion/accessibility unreachable | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | no stable fact explanation, source-purpose export/deletion, or target day UI/accessibility path exists. |
| P3-R1B-P07 rollout metadata overstates real ownership | `CONFIRMED` | `BLOCKER` | `ACCEPTED` | event-canonical metadata is not proof of a terminal consumer or sole actual writer; typed per-source flags and observable production-query gates are required. |
| P3-R1B-P08 existing Today paths are not midnight/zone stable | `CONFIRMED_WITH_NEW_EVIDENCE` | `HIGH` | `ACCEPTED` | long-lived `observeToday()` captures the date once and Dashboard derives an epoch day from local-midnight milliseconds/fixed day length; use one injected clock/zone day-key stream and `LocalDate.toEpochDay`. |

Protections re-proven in R1b: destination mutation plus receipt/head changes are one Room transaction; retired generic generations are rejected; logical contribution identity excludes writer/revision; WAL rows are not rewritten for attribution; provenance-validated RETRACT can outlive raw WAL; full all-data deletion advances the epoch atomically; all new writers remain unused/shadow and no `DayOverview`/`DayHistoryRepository` exists.

## Scope proportionality adversarial dispositions

Three fresh `gpt-5.6-sol` high-reasoning reviewers independently attacked product/use-case fit, architecture/maintenance cost, and delivery/validation feasibility. They made no edits and ran no Gradle tasks. The lead rechecked their principal claims against the worktree: v27 deliberately retained 51 production-owned tables and rejected generic/permanent frameworks; v28 currently adds 23 tables and 30 persistent entity classes; the tracked diff is 5,648 additions/576 deletions across 62 files; the app supports one active logical session; generic Phase 3 repositories have no production caller; and the repository has no remote telemetry or automated device/OEM/soak pipeline.

| Finding | Severity | Disposition | Evidence / required correction |
| --- | --- | --- | --- |
| SCOPE-01 generic Phase 3 is permanent schema before product use | `HIGH` | `ACCEPTED_FOR_CONTAINMENT` | Freeze new ledger/materializer work. Before v28 ships, prove every retained table has a named production writer, reader, retention, export/deletion behavior, and next-milestone user result; otherwise trim the unshipped schema or move the capability to a later migration. |
| SCOPE-02 automatic tracking can disappear on upgrade | `BLOCKER` | `ACCEPTED` | v28 bootstrap denies `CONTROL`, runtime requires Activity control eligibility, and no grant UI exists. Product must either recognize the existing explicit automatic preference as the migration authorization or ship a user-reachable control-consent transition before release. |
| SCOPE-03 database merge import can ingest control-plane rows | `BLOCKER` | `ACCEPTED` | `DatabaseImport` imports every matching non-system table with `INSERT OR IGNORE`; this includes policy, demand, nonterminal session, writer-owner, receipt, and tombstone state. Merge import needs an explicit user-fact allowlist; validated empty-database restore is a separate operation. |
| SCOPE-04 generic attribution exceeds the demonstrated concurrency model | `HIGH` | `ACCEPTED_FOR_RESCOPE` | At review time the app permitted one active logical session and ambient products were unimplemented/unapproved; TI-D047 later accepted source-specific ambient product scope. Preserve raw-fact separation and purpose/generation fencing, but require concrete overlapping durable consumers before zero-to-many/counting-head machinery becomes shipping schema. |
| SCOPE-05 runtime writer activation exceeds the demonstrated migration need | `HIGH` | `ACCEPTED_FOR_RESCOPE` | Existing canonical tables already have source identities and Location must retain its sole writer. Prove Steps first with a typed idempotent fact/recompute path; retain generic receipts/activation only if a real non-recomputable destination demonstrates the need. |
| SCOPE-06 full DayOverview/M3 journal is incorrectly coupled to source fixes | `HIGH` | `ACCEPTED_FOR_RESCOPE` | First repair the existing Dashboard/Calendar/History contract with typed `Loading`/`Unavailable`/`NoData`/`Partial`/`Ready` state and a minimal source-visible query. The Days-first hero/ribbon/ambient redesign is a separately approved product epic. |
| SCOPE-07 ambient breadth is speculative | `HIGH` | `OVERRIDDEN_BY_PRODUCT_DECISION` | The user explicitly confirmed ambient persistence is wanted. Retain it as independently gated source verticals rather than one all-source prerequisite: source-specific setting/consent, acquisition limits, minimization, retention, export, deletion/key rotation, between-session query, and explanation are mandatory. Continuous ambient Pressure remains off pending explicit approval. |
| SCOPE-08 fleet telemetry/canary/SLO gates are not executable | `HIGH` if release-blocking | `ACCEPTED` | The app explicitly has no remote telemetry and CI has no emulator/device farm. Replace GA-critical cohort claims with local diagnostics, host contracts, exact migration/recovery execution, and a small representative physical-device smoke; retain broader OEM/soak work as risk-based backlog. |
| SCOPE-09 exhaustive review governance has become work without product output | `MEDIUM` | `ACCEPTED` | Stop mandatory review rounds while the blocker is missing implementation/device capacity. Run one fresh review at an actual migration, writer-cutover, or release boundary. |
| SCOPE-10 v28 reduction depends on whether its current shape escaped | `BLOCKER` | `DEFERRED_BY_EXPLICIT_DECISION` | If no durable install outside this worktree opened the current v28, trim/regenerate v28. If it escaped, preserve compatibility and make reductions additively in v29. Never reinterpret an external schema in place. |

Cross-review disagreement is preserved: the product reviewer proposed replacing durable broker rows with one compact effective plan; the architecture reviewer retained durable broker semantics but narrowed them to typed purposes and one reconciler. No broker schema is removed on reviewer confidence alone. The deciding experiment is a minimal Activity-control plus one-session recovery implementation that proves process-death convergence and stale-callback rejection with the smaller state model.

## R2 ambient/v28 proportionality adversarial dispositions

Three fresh `gpt-5.6-sol` high-reasoning reviewers independently re-read the live repository after two new product facts: v28 never shipped and ambient persistence is required. They reviewed product/use-case fit, table-level data/Android architecture, and capacity-aware delivery. All were read-only and ran no Gradle or device tasks.

| Finding | Agreement | Severity | Disposition | Evidence / next gate |
| --- | --- | --- | --- | --- |
| R2-S01 current v28 may be reshaped in place | unanimous | `BLOCKER` resolved | `ACCEPTED` | user confirmed v28 was never released. Regenerate `MIGRATION_27_28` and `28.json`; no v29 compatibility shell is needed. Exact populated migration remains mandatory. |
| R2-S02 twelve generic Phase 3 tables have no current product owner | unanimous | `HIGH` | `MITIGATED_LOCALLY` | the 12 tables, two DAOs, three repositories, and synthetic-only tests were removed from unreleased v28. Generic machinery may return only if a concrete non-recomputable destination proves it necessary. |
| R2-S03 ambient persistence makes sessionless provenance mandatory | unanimous | `BLOCKER` | `ACCEPTED` | WAL already permits nullable logical identity. Admission must occur once before session/between-session classification and retain purpose, consent, generation, structural time and completeness. |
| R2-S04 ambient Steps is the smallest useful first vertical | unanimous | `HIGH` | `ACCEPTED_FOR_SEQUENCE` | existing counter runtime, generation-bound accumulator, unique `StepInterval.sourceSignalId`, day/range queries and Today/History steps UI avoid radio identity, background-location and multi-SIM complexity. Product promise remains unresolved. |
| R2-S05 existing day shell can provide the minimum ambient product | unanimous | `HIGH` | `ACCEPTED_FOR_RESCOPE` | Today/Timeline/Calendar already exist but fabricate zeros and hide days without trips. Add sealed truthful state and a collapsed between-session section before a new Days-first hero/ribbon product. |
| R2-S06 ambient export/deletion is currently incomplete | unanimous | `BLOCKER` | `ACCEPTED` | JSON omits Activity/Steps/Pressure and classifies orphan facts by time overlap; trip deletion removes trip/segment rows rather than source-purpose facts. A released ambient source needs explicit portable export, full deletion and per-source ambient deletion semantics. |
| R2-S07 merge import can install runtime authority | unanimous | `BLOCKER` | `MITIGATED_LOCALLY` | production merge import now uses an explicit 24-table user-fact allowlist. A hostile backup test proves Activity facts import while foreign policy authority and active demand rows do not. Validated empty-target restore remains separate. |
| R2-S08 automatic tracking can disappear on upgrade | unanimous | `BLOCKER` | `MITIGATED_LOCALLY` | v28 bootstrap treats an existing enabled automatic preference as nonpersistent Activity `CONTROL`, never Activity capture; automatic mode enable/disable appends control epochs atomically with the policy revision. Connected schema/fact migration passes; automatic trigger and no-control-product device proof remain. |
| R2-S09 current five lifecycle/manifest additions are proportionate | disputed | `HIGH` | `RESOLVED_NARROWLY` | the 8-scenario compact model passes, but normalized manifest/source/intent and action rows have real production SQL callers. Keep them; collapse only the duplicate lifecycle lease into the shared boot-aware coordinator lease. |
| R2-S10 current three broker additions are proportionate | disputed | `HIGH` | `RESOLVED_KEEP` | the compact model proves a bounded embedded vector is logically possible, but current demand/generation/eligibility rows provide transaction-queryable callback audit and all have production callers. Retain them through the Ambient Steps proving vertical rather than pay a speculative refactor cost. |
| R2-S11 ambient promise is opportunistic or continuous/all-day | unanimous decision need | `BLOCKER` | `DEFERRED_BY_EXPLICIT_DECISION` | process death, reboot and Android FGS legality materially change the promise. UI must show gaps/completeness; silent boot continuity cannot be assumed. |
| R2-S12 WAL payload integrity remains incomplete | architecture-confirmed | `BLOCKER` | `MITIGATED_LOCALLY` | WAL admission recomputes the checksum from payload bytes before decode/use. A valid-but-mutated encoding is quarantined before projector invocation, its ordinal is terminally accounted for, and a healthy later row still applies. |

The integration lead's current lean candidate is the architecture review's middle position, not the delivery review's smallest table count: immutable intent and external action acknowledgement remain distinct, while redundant manifest/consent/demand/eligibility rows are candidates for collapse. It must pass an executable model test for start/reconfigure/stop, process death, reboot, consent revocation, ambient-only recovery, shared Activity control/capture, and late callbacks before any Room migration is rewritten.

## R3 adaptive-collections plan adversarial dispositions

Three fresh `gpt-5.6-sol` high-reasoning reviewers independently read the full final plan, adaptive design, decision log, and live production paths. All were read-only. Product/use-case and Android performance/battery/quality returned `BLOCK_RESCOPE`; data/runtime simplicity returned `PASS_WITH_CORRECTIONS`. The integrated gate is `BLOCK_RESCOPE` before further structural implementation.

| ID | Finding | Severity | Disposition | Integrated correction / evidence gate |
| --- | --- | --- | --- | --- |
| R3-01 | final plan and adaptive execution sequence conflict | `BLOCKER` | `MITIGATED_IN_DESIGN` | authoritative addendum now supersedes generic Phase 3/mandatory full-day-UI prerequisites; existing product surfaces precede optional journal work |
| R3-02 | literal per-callback checkpoint/admission/drain amplifies Room/CPU load and still overflows bounded queues | `BLOCKER` | `MITIGATED_IN_DESIGN` | TI-D056 defines quality-preserving source-native units, bulk transaction/sequence allocation, one conflated async drain, slow-Room saturation/Perfetto gate; production code unchanged |
| R3-03 | demand/manifest revisions restart unchanged providers and lose Step baselines/Pressure windows | `BLOCKER` | `ACCEPTED` | TI-D057 separates physical configuration generation from authorization revision; provider start/stop counts and replacement-failure tests block implementation |
| R3-04 | force-all-source event-canonical state can strand WAL output without a terminal product writer | `BLOCKER` | `ACCEPTED` | independent per-source ownership defaults legacy/shadow until typed writer, query, export, deletion, and one-writer proof pass |
| R3-05 | automatic Activity-only may lose the motion transition that starts its session | `BLOCKER` | `MITIGATED_IN_DESIGN` | capture-consented trigger gets explicit `AUTOMATION_TRIGGER_HANDOFF`; otherwise initial interval is partial and control remains excluded |
| R3-06 | adaptive motion policy can disable Pressure-only or reduce manual responsive Location below useful quality | `HIGH` | `MITIGATED_IN_DESIGN` | TI-D058 adds minimum useful demand floor and adaptive permission; optimizer may slow/batch but not disable the sole source |
| R3-07 | session-bound ingress can reject a mixed ambient/capture fact after stop | `HIGH` | `ACCEPTED` | raw admission becomes sessionless; observed-time authorization and manifest intervals classify use after durability |
| R3-08 | synchronous global projection lane creates callback latency and cross-source poison blockage | `HIGH` | `ACCEPTED` | one WAL remains, with source index/cursors, bounded per-source quarantine, direct typed projectors, and conflated async wakeup |
| R3-09 | Wi-Fi/Cell-only sessions and zero/unchanged callbacks disappear from typed history | `HIGH` | `MITIGATED_IN_DESIGN` | concrete compact radio snapshot headers are required; logical session is retained and existing Today/Timeline/Calendar/detail must show source-only output |
| R3-10 | passive radio capture may never deliver fresh evidence in a short direct session | `HIGH` | `MITIGATED_IN_DESIGN` | one bounded policy-ceiling-respecting direct-capture start probe or truthful no-observation/OS-limited result; never an enrichment demand |
| R3-11 | query-time Location context can regress radio heatmaps or become N+1 | `HIGH` | `ACCEPTED` | heatmaps are a named shadow-parity consumer; page/day bulk load plus linear event-time merge; narrow cache only after profiling |
| R3-12 | opportunistic Ambient Steps can falsely award complete-day goals/streaks | `HIGH` | `ACCEPTED_WITH_PRODUCT_DECISION_PENDING` | completeness accompanies values across every consumer; incomplete coverage cannot grant complete-day results; continuous visible lifecycle remains a user decision |
| R3-13 | automatic Location evaluates a fabricated start/capability context | `HIGH` | `ACCEPTED` | carry durable real origin/trigger and actual permission/FGS/background legality; API 34–36 device matrix remains blocking |
| R3-14 | scoped deletion/export can omit or resurrect ambient source facts | `HIGH` | `ACCEPTED` | rotate source-purpose epoch atomically with delete/cache/WAL fences; extend portable export to released product facts; replay/no-resurrection crash tests |
| R3-15 | unused generic join/Location shadow projections consume work and can leak control evidence | `MEDIUM` | `ACCEPTED` | unbind until a named experiment/consumer; retire after direct typed paths replace them; keep only narrow purpose-filtered Location resolver |
| R3-16 | reviewer proposal to drop repeated valid control callbacks conflicts with product persistence requirement | `MEDIUM` | `REJECTED_AS_STATED` | valid source-qualified units remain durable under TI-D055/TI-D056; reduce acquisition cadence, batch delivery, compact unchanged headers, and bound retention instead of post-receipt silent drop |
| R3-17 | one universal make-before-break or break-before-make rule is unsafe across Android providers | `HIGH` | `MITIGATED_IN_DESIGN` | provider-specific update/handoff; revocation always immediate; unpreventable gaps produce explicit completeness |
| R3-18 | source settings/copy still describe radio/Pressure as Location enhancements | `HIGH` | `ACCEPTED` | independent source outputs, purpose controls, Pressure trend, ambient limits, and repair actions require Compose/copy tests before rollout |
| R3-19 | preserving minimum quality alone undershoots the product objective | product clarification | `ACCEPTED` | TI-D060 makes quality improvement per unit of battery explicit; every source gets a protected vector, target, shadow comparison, and dominated-candidate rejection |

No R3 finding authorizes runtime rollout or claims device battery performance. The three reports attempted static verification only; the product reviewer’s scoped Gradle command could not download Gradle 9.6.1 under restricted network, and no emulator/device was available. Current local code/test baselines recorded below remain the implementation baseline.

## R0 adversarial finding dispositions

`ACCEPTED` means the finding is incorporated into the implementation contract and blocks its relevant gate until verified. `DEFERRED_BY_EXPLICIT_DECISION` means the safe alternatives materially change behavior and require user/product authorization; dependent work remains blocked. A finding is marked mitigated only where the local implementation and scoped tests now supply evidence; this does not imply the phase gate passed.

| Finding | Severity | Disposition | Required correction / gate |
| --- | --- | --- | --- |
| R0-DATA-01 schema/binary rollback | `BLOCKER` | `MITIGATED` | TI-D006 authorizes additive v27→v28; rollback remains schema-capable v28 and never APK/database downgrade; four connected migration cases pass, while backup-wrapper recovery and rollback rehearsal remain required |
| R0-DATA-02 in-flight writer race | `BLOCKER` | `ACCEPTED` | validate active generation inside every destination/receipt transaction |
| R0-DATA-03 correction identity | `BLOCKER` | `ACCEPTED` | contribution identity independent of revision/writer; atomic signed replace/retract chain |
| R0-DATA-04 legacy purpose ambiguity | `BLOCKER` | `ACCEPTED` | migrate as `LEGACY_UNKNOWN`, capture-ineligible unless independent proof exists |
| R0-DATA-05 retention resurrection | `BLOCKER` | `ACCEPTED` | atomically fence retained floor, lifecycle/consent epoch, tombstone and key rotation |
| R0-DATA-06 DataStore/Room bootstrap | `HIGH` | `MITIGATED` | TI-D021 names Room authority; verified bootstrap retries, observations are transactionally joined, mutations serialize, and DataStore is repaired as a compatibility mirror |
| R0-DATA-07 overlapping attribution | `HIGH` | `ACCEPTED` | separate auditable associations from one canonical counting classification/algebra |
| R0-DATA-08 dirty-day lost wakeup | `HIGH` | `ACCEPTED` | monotonic required revision and conditional acknowledgement in publish transaction |
| R0-DATA-09 global poison blockage | `HIGH` | `ACCEPTED` | independent materializer/source partitions and truthful affected completeness |
| R0-DATA-10 ledger retention floor | `MEDIUM` | `ACCEPTED` | ledger-owned floor independent of active projection checkpoints |
| R0-AND-01 privacy rollback | `BLOCKER` | `MITIGATED_FOR_POLICY_SLICE` | append-only purpose epochs, monotonic CAS authority, fail-closed projection, deletion survival, and current-policy plan validation are implemented; provider callback/attribution fencing remains TI-200/TI-300 |
| R0-AND-02 replayed Android exemption | `BLOCKER` | `ACCEPTED` | persist trigger identity/boot/expiry; generic reconciliation cannot manufacture legality |
| R0-AND-03 shared purpose epoch | `BLOCKER` | `ACCEPTED` | eligibility generation/vector distinct from physical registration generation |
| R0-AND-04 mixed-generation observations | `BLOCKER` | `ACCEPTED` | accumulators/checkpoints close, split, or discard at every effective boundary |
| R0-AND-05 layered acknowledgements | `HIGH` | `ACCEPTED` | separate service request, foreground/type confirmation, provider acceptance and failures |
| R0-AND-06 lease fencing token | `HIGH` | `ACCEPTED` | CAS every mutation/callback/ack on monotonic lease generation |
| R0-AND-07 boot-domain effective time | `HIGH` | `ACCEPTED` | use boot identity + elapsed time + monotonic revision; fail cross-domain recovery closed |
| R0-AND-08 durable stop grace | `HIGH` | `ACCEPTED` | persist stop candidate, automation epoch and boot-domain deadline before delay |
| R0-AND-09 automatic Location legality | `HIGH` | `ACCEPTED` | per-action API/visibility/trigger/permission/service/FGS-type legality matrix |
| R0-AND-10 rollback vs consent deletion | `HIGH` | `ACCEPTED` | technical rollback preserves data; only explicit consent/deletion tombstones or rotates |
| R0-AND-11 Activity control retention | `HIGH` | `DEFERRED_BY_EXPLICIT_DECISION` | approve minimized fields, TTL/state, export/deletion and diagnostics for mandatory control evidence |
| R0-AND-12 capability callback guard | `HIGH` | `ACCEPTED` | monotonic capability revision, source trigger matrix and callback-entry recheck |
| R0-AND-13 radio minimization timing | `HIGH` | `ACCEPTED` | HMAC/minimize before durable product admission; isolate any approved raw diagnostics |
| R0-AND-14 `specialUse` legality | `MEDIUM` | `ACCEPTED` | source × FGS type × API/device/release-policy evidence before rollout |
| R0-AND-15 QoS downgrade failure | `MEDIUM` | `ACCEPTED` | persist requested/applied QoS and bound over-provisioned registration state |
| R0-P01 nullable day query | `BLOCKER` | `ACCEPTED` | sealed day query plus per-source policy/capability/evidence/materialization/repair state |
| R0-P02 fake production proof | `BLOCKER` | `ACCEPTED` | provider-to-real-presenter production graph tests; direct row/fake UI is insufficient |
| R0-P03 non-additive metrics | `HIGH` | `ACCEPTED` | typed metric products and declared algebra; additive identity only where valid |
| R0-P04 fabricated cross-midnight data | `BLOCKER` | `ACCEPTED` | exact timestamped split; legacy ambiguity becomes partial/unavailable; define traveling-zone key |
| R0-P05 ambient matrix omission | `HIGH` | `ACCEPTED` | one explicit enabled/disabled acceptance row per source with privacy/product evidence |
| R0-P06 UI/accessibility omissions | `HIGH` | `ACCEPTED` | individually gate every required state, hero, layout, navigation and accessibility dimension |
| R0-P07 explanation provenance | `HIGH` | `ACCEPTED` | stable single/aggregate explanation handles on every actionable displayed item |
| R0-P08 rollback hides v2 facts | `HIGH` | `ACCEPTED` | compatible v2 reader or truthful retained-unavailable entry after UI rollback |
| R0-P09 local-only canary evidence | `HIGH` | `DEFERRED_BY_EXPLICIT_DECISION` | choose device-lab/user-initiated local diagnostic evidence or separately authorize privacy design |
| R0-P10 live Today rollover | `HIGH` | `ACCEPTED` | clock/zone/day-key flow switches long-lived collectors at midnight/DST without moving old facts |

## Fresh lifecycle-review dispositions

Three fresh read-only reviewers attacked the integrated manifest/lifecycle diff. `BLOCKER` and `HIGH` findings remain phase-gate blockers even where a narrower local symptom was corrected.

| Finding | Severity | Disposition | Evidence / remaining gate |
| --- | --- | --- | --- |
| LIFE-01 Android service/FGS precedes Room intent | `BLOCKER` | `ACCEPTED` | caller-side durable start gateway and service/FGS action families are still required |
| LIFE-02 lease ABA, expiry, and mutable-row CAS | `BLOCKER` | `ACCEPTED` | release/expiry increments generation and expiry is checked; SQL state/revision CAS remains required |
| LIFE-03 `PENDING`/`APPLYING`/retryable actions have no reconciler | `BLOCKER` | `ACCEPTED` | no production consumer of `pendingLifecycleActions()` exists |
| LIFE-04 failed unregister can outlive the newest manifest | `BLOCKER` | `ACCEPTED` | actual registrations must be independently tracked and terminally reconciled |
| LIFE-05 delayed pre-cutoff evidence rejected in `STOPPING` | `HIGH` | `MITIGATED` | focused ingress test admits at cutoff and rejects post-cutoff evidence |
| LIFE-06 migrated unqualified v27 WAL can project | `HIGH` | `ACCEPTED` | quarantine or explicit activation floor is required before projection rollout |
| LIFE-07 partial logical/run identity bypass | `HIGH` | `MITIGATED` | IDs must be paired; focused bypass regression passes |
| LIFE-08 force-stop bypasses terminal intent/action classification | `HIGH` | `ACCEPTED` | common fenced terminal transaction remains required |
| LIFE-09 manifest integrity/effective ordering convention-only | `MEDIUM` | `ACCEPTED` | regressing effective time is rejected; checksum verification/update denial remains required |
| LIFE-10 automatic trigger envelope/current epoch absent in production | `BLOCKER` | `ACCEPTED` | production automatic entry remains fail-closed and cannot pass its scenario |
| LIFE-11 Activity callback lacked manifest/lease stamp | `BLOCKER` | `ACCEPTED` | immediate admission hole is repaired, but registration-time purpose/generation identity requires the broker |
| LIFE-12 delayed callback can be relabeled by current authorization | `BLOCKER` | `ACCEPTED` | authorization must be bound to physical registration generation, not looked up by source at receive time |
| LIFE-13 requested source/failure cause erased before manifest | `BLOCKER` | `ACCEPTED` | desired capture intent and applicable runtime plan must be separated |
| LIFE-14 reconfigure could claim `ACTIVE` with no accepted source | `HIGH` | `MITIGATED` | last-source disable now terminates with named failure and a terminal immutable intent |
| LIFE-15 boot fallback depended on wall clock | `HIGH` | `MITIGATED` | unreadable `BOOT_COUNT` now uses a conservative process-unique domain |
| LIFE-16 reconfigured FGS evidence is stale | `HIGH` | `ACCEPTED` | durable FGS/type action acknowledgement is still required |
| LIFE-17 rollout docs were not executable flags | `HIGH` | `ACCEPTED` | both manifest/lifecycle controls remain blocked; real durable flags are required before reachability claims |

## Fresh broker-review dispositions

Three fresh read-only reviewers attacked the integrated demand, registration, callback, and source-runtime diff. `MITIGATED_LOCALLY` records a scoped correction and regression test; it does not pass the Phase 2 gate.

| Finding | Severity | Disposition | Evidence / remaining gate |
| --- | --- | --- | --- |
| BROKER-D01 manifest cutover can silently lose old-sink callbacks | `HIGH` | `ACCEPTED` | replacement acceptance, handoff completeness, and per-generation drain evidence remain required |
| BROKER-D02 retiring a demand invalidated stop-drain callbacks | `BLOCKER` | `MITIGATED_LOCALLY` | `RETIRING` remains eligible until the callback barrier retires it; focused regression passes; device interleave remains |
| BROKER-D03 Activity stop completeness equals allocated rather than durable sequence | `HIGH` | `ACCEPTED` | stop acknowledgement must follow durable WAL/outbox completion |
| BROKER-D04 old Activity registration retires before replacement acceptance | `HIGH` | `ACCEPTED` | preserve safe old ownership or persist retryable desired/actual convergence |
| BROKER-D05 full deletion removes surviving app-demand rows | `MEDIUM` | `ACCEPTED` | policy-surviving control intent must be explicitly rebuilt before reacquisition |
| BROKER-D06 one-active registration uniqueness is convention-only | `MEDIUM` | `ACCEPTED` | DB-enforced ownership/retention invariant and crash tests remain required |
| BROKER-A01 broker does not globally own every physical provider | `BLOCKER` | `ACCEPTED` | singleton reconciler and adapters must replace session-owned shutdown/direct corroborator ownership |
| BROKER-A02 stale automatic outbox effects can act after revoke/reboot | `BLOCKER` | `ACCEPTED` | persist/revalidate trigger, boot, registration, consent, automation epoch, and freshness before CAS actuation |
| BROKER-A03 app/control admission lacked activation boot/effective-time fence | `HIGH` | `MITIGATED_LOCALLY` | eligibility snapshots boot/elapsed activation and both Activity/generic ingress reject pre-boundary observations |
| BROKER-A04 FGS illegality can be acknowledged as delivered | `HIGH` | `ACCEPTED` | durable tri-state service/FGS action executor remains required |
| BROKER-A05 capability/permission revoke is not a callback admission fence | `HIGH` | `ACCEPTED` | capability revisions and revoke-race tests are required for all six sources |
| BROKER-A06 Wi-Fi/Cell pseudonyms are unkeyed and non-rotatable | `HIGH` | `ACCEPTED` | per-install HMAC generation and deletion/consent-reset rotation remain required |
| BROKER-A07 Wi-Fi/Cell background cadence is not wake-reliable | `MEDIUM` | `MITIGATED_BY_CONTAINMENT` | product/runbook call it opportunistic; Doze/OEM measurement remains required |
| BROKER-P01 all automatic scenarios lack the durable trigger handoff | `BLOCKER` | `ACCEPTED` | six automatic only-X scenarios remain fail-closed |
| BROKER-P02 Step corroborator bypasses broker ownership | `BLOCKER` | `DEFERRED_BY_EXPLICIT_DECISION` | user/product must remove it or retain it as declared `CONTROL_AUTOSTART` |
| BROKER-P03 qualified `RECORDING` state is absent | `BLOCKER` | `ACCEPTED` | persist source-qualified evidence and make UI distinguish provider-active from recording |
| BROKER-P04 production history query is absent | `BLOCKER` | `ACCEPTED` | no source is `QUERYABLE` until real `TrackingHistoryRepository` and applicable consumer proof exists |
| BROKER-P05 ambient authority has no producer/product contract | `HIGH` | `ACCEPTED` | explicitly disabled acceptance or full consent/retention/export/deletion/UI path required per source |
| BROKER-P06 tests prove components rather than 12 contracts | `HIGH` | `ACCEPTED` | parameterized provider-to-production-query/UI harness and device evidence remain required |

## Work items

| ID | Design / invariant | Workstream | Files/modules in scope | Dependencies | Acceptance assertions | Verification / device evidence | Rollout and rollback | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TI-000 | Required source of truth | G | design, repository instructions, worktree, CI | none | Full plan/instructions read; user changes preserved; commands discovered | `git status --short --branch`; CI workflow inspection | Documentation only | `DONE` |
| TI-001 | §3, §11; product proof | G | all source acquisition, writers, queries, tests | TI-000 | Six evidence-backed source verdicts and writer/query map | Six independent source reports accepted into status/matrix | No runtime change | `IN_REVIEW` |
| TI-003 | §10.1 Phase 0; stale automation containment | C/G | `BackgroundTrackingApi.kt`, `BackgroundTrackingApiLogicTest.kt` | R0 | `STILL` cannot enable an inactive watcher even with Activity permission | scoped regression plus full tracker-engine unit/lint | no flag; restores fail-closed documented preference semantics | `DONE` |
| TI-002 | §10.1 Phase 0; one writer | G | rollout/diagnostics/source ownership | TI-001, R0 | Unexpected captured writes and writer generations observable; per-source safe stop exists | rollout-state and telemetry tests | Default off for new writers; rollback stops new writes only | `NOT_STARTED` |
| TI-100 | §4.2; one authority/privacy epochs | A | `:core:base`, `:data:preferences`, planning/status/FGS inputs | TI-001, R0 | Boolean/frequency conflicts cannot revive a source; corrupt legacy intent cannot fabricate consent; one effective six-source snapshot drives current consumers; capture/ambient/control epochs and acquisition ceilings never roll back | Room bootstrap/CAS/ledger-corruption/deletion tests; mirror-block/failure retry tests; plan/WAL/control-gating/status/UI tests; scoped module suites | v28 safety state is always authoritative; rollback must remain schema-capable and fail closed | `IN_REVIEW` |
| TI-110 | §4.3; immutable intent | A/C | Room entities/DAOs/migration, coordinator | TI-100 | starts append manifest v1; reconfiguration appends effective manifest/intent revisions; prior bindings remain unchanged; effective time is monotonic per boot | focused manifest/reconfigure/terminal-intent tests and four connected v27→v28 containment cases pass; requested-vs-accepted manifest/device lifecycle cases remain | additive unreleased v28 expansion; immutable rows retained on rollback | `BLOCKED` |
| TI-120 | §4.5, §9; no ghosts | C | desired actions, coordinator, service, recovery | TI-110 | source-runtime intent/actions commit before provider calls; boot/expiry/generation fences; terminal identity cannot restart; last-source disable is not `ACTIVE` | 29 focused tests pass including lease ABA, stop cutoff, terminal intent, stale automatic boot, and identity fencing; external-stage crash/reboot/reconciler tests pending | `lifecycle_v2` remains off/blocked; records are retained | `BLOCKED` |
| TI-200 | §4.4; purpose separation | B | `SourceSupervisor`, demand store, six adapters, plan optimizer | TI-100, TI-110, step decision | Manual only-X has no hidden control; automatic only-X has exactly declared Transition control; enrichment cannot add/retain demand; one physical runtime/source survives authorization-only revision; the cheapest plan satisfies every direct floor within ceilings; callbacks carry physical and authorization identities; legal trigger/action envelope commits before FGS | durable demand/vector/generation rows and current fencing pass focused tests; supervisor ownership, physical/auth split, no-enrichment and policy×floor properties, unchanged-source zero-restart, Transition-only trigger/type-union tests, 12 scenarios and device evidence are missing | `broker_v2:<source>` remains off; legacy registration retires only after parity | `BLOCKED` |
| TI-300 | §6.3, §8; replay/performance safety | D | `TrackingWriter`, source-native WAL, six source cursors/projectors and typed facts | TI-200, typed contract/legacy-owner design | Every valid qualified unit is durably represented before classification; one component owns tracking mutations; admission is bulk; capture/ambient facts and bounded control evidence remain separated; typed identity/correction/deletion are idempotent; poison is source-bounded; Pressure uses qualified microsegments | WAL byte authentication and terminal raw quarantine pass. Current persistence gates, multiple transactions, per-event work, synchronous global drain and force-canonical projections violate TI-D055–TI-D067; stress/device and typed destination evidence remain | every new source path remains off; legacy Location writer protected; technical rollback preserves facts/WAL | `BLOCKED` |
| TI-400 | product proof; TI-D059/TI-D069/TI-D073–075 | E | one read-only `TrackingHistoryRepository` over existing Today/Timeline/Calendar/detail/map contracts; separate export/delete/context services | TI-300 | Every source is independently queryable without Location; ambient facts appear outside sessions; optional context is purpose/session/epoch compatible and cannot block/mutate the primary; verified zero differs from no observation; stable day/zone and orthogonal truthful status flow to all consumers | six sole-source provider-to-production-history slices, verified-zero Steps, Wi-Fi-only/purpose-safe Wi-Fi+Location context, status renderer, migration, midnight/DST and ambient-only tests | compatible legacy reads survive rollback; no retained fact becomes undiscoverable | `BLOCKED` |
| TI-500 | §7; truthful product | F | existing Today/Timeline/Calendar/selected-day UI | TI-400 | all sole-source, ambient, empty/error/completeness states are discoverable and explained; accessibility holds | production-presenter, Compose, screenshot, 200% text, TalkBack, reduced-motion and device tests | UI rollback cannot hide retained source-only facts; full Days-first journal remains backlog | `BLOCKED` |
| TI-600 | §10.2, §11.5; source rollout | B/D/E/F/G | per-source gates in order | TI-500 | Independent manual/automatic/ambient/dynamic/failure/product gate per source | verification matrix plus device/canary evidence | Steps → Pressure → Location → Activity → Wi-Fi → Cell unless decision records change | `NOT_STARTED` |
| TI-700 | §10.1 Phase 7; GA | F/G | battery, storage, export/deletion, privacy, accessibility | TI-600 | All definition-of-complete evidence reproduced; no unresolved blocker/high review findings | full gates, device/OEM/soak, rollback rehearsal | External rollout remains unauthorized | `NOT_STARTED` |

## Baseline commands discovered

- Unit: `.\gradlew.bat testDebugUnitTest --no-daemon --stacktrace --console=plain`
- Scoped unit: `.\gradlew.bat :<module>:testDebugUnitTest --no-daemon --console=plain`
- Debug build: `.\gradlew.bat :app:assembleDebug`
- Debug lint: `.\gradlew.bat lintDebug --no-daemon --stacktrace --console=plain`
- Release lint: `.\gradlew.bat :app:lintRelease --no-daemon --stacktrace --console=plain`
- Release builds: `.\gradlew.bat :app:assembleRelease :app:assembleRelease_nominify --no-daemon --stacktrace --console=plain`
- Room schema: `.\gradlew.bat checkRoomSchemaDrift --no-daemon --stacktrace --console=plain`
- Connected instrumentation: `.\gradlew.bat :app:connectedDebugAndroidTest`

No repository command was found for a separate Detekt task in CI. Device/OEM, doze, reboot, multi-SIM, low-storage, and long-soak evidence cannot be inferred from host tests.

## Latest containment verification

- `:tracker:engine:testDebugUnitTest --tests "*BackgroundTrackingApiLogicTest"`: `BUILD SUCCESSFUL in 2m 47s`.
- `:tracker:engine:testDebugUnitTest :tracker:engine:lintDebug`: `BUILD SUCCESSFUL in 6m 34s`; lint reports two existing warnings and no failure.

## Latest policy/v28 verification

- `:core:base:testDebugUnitTest :data:preferences:testDebugUnitTest :tracker:engine:testDebugUnitTest :core:base:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebug`: `BUILD SUCCESSFUL in 5m 52s` after the final adversarial corrections; 737 tasks completed and the exact migration test compiles.
- `:data:preferences:lintDebug`: `BUILD SUCCESSFUL`; seven pre-existing missing-translation errors remain baseline-covered, and the introduced indentation finding was removed.
- `:app:lintDebug`: rerun after fixes and exactly matches the untouched baseline at 43 errors/48 warnings; the first failure remains the pre-existing Irish plural resource and no changed policy/UI file appears in the report.
- `checkRoomSchemaDrift`: intentionally reports the required new untracked `28.json`; the generated schema must be included in an authorized commit. No schema content drift was reported beyond the new version file.
- At this earlier policy checkpoint the exact test was compile-only; the later four-case populated suite now passes on `Medium_Phone` and supersedes that limitation.

## Latest broker/v28 verification

- Initial combined broker, registration, ingress, coordinator, Activity, Steps, and Pressure regressions: `BUILD SUCCESSFUL in 3m 15s`; 281 tasks.
- Stop-drain and automatic-purpose focused rerun: `BUILD SUCCESSFUL in 3m 48s`; 281 tasks.
- Effective boot/time eligibility, deletion survival, migration-test compilation, Activity sequence admission, and source callback fencing: `BUILD SUCCESSFUL in 3m 32s`; 290 tasks.
- Fresh Android adversary independently ran the complete `:tracker:engine:testDebugUnitTest`: `BUILD SUCCESSFUL in 2m 44s`; 232 tasks.
- `:app:assembleDebug`: `BUILD SUCCESSFUL in 2m 9s`; 654 tasks, with one existing debug-seeder type warning and the existing Moshi Kapt deprecation warning.
- A broad `:core:base`/`:tracker:engine`/`:sensor:activity` unit+lint run reached tests/lint but failed in Gradle result-file handling (`NoSuchFileException`), not a test assertion. Activity lint was clean; tracker lint retained two existing warnings; core lint retained 13 existing missing-translation errors.
- `checkRoomSchemaDrift`: reports only `?? core/base/schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/28.json`, the required untracked v28 schema handoff.
- At this earlier broker checkpoint no device was attached. The later four-case migration suite passes on `Medium_Phone`; provider interleaves, reboot, Doze, and OEM execution remain unverified.

## Latest lean-v28 integration verification

- The 12 ownerless generic Phase 3 tables, their two DAOs, three repositories, synthetic-only tests, and the duplicate lifecycle lease are removed. The later v27 recovery-containment slice adds only its obligation and per-target records; `MIGRATION_27_28`, `AppDatabase`, retention, ingress, deletion, and generated `28.json` now describe a 63-entity schema.
- Complete `:core:base:testDebugUnitTest`: `BUILD SUCCESSFUL in 1m 15s`; 75 tasks pass after the v28 reduction.
- Focused import tests: corrected rerun `BUILD SUCCESSFUL in 10s`; 13 tests prove the 24-table allowlist and hostile control-plane exclusion.
- Focused ingress/projection tests: `BUILD SUCCESSFUL in 2m 07s`; focused coordinator raw-quarantine test: `BUILD SUCCESSFUL in 1m 20s`.
- Focused policy/authoritative-settings tests: `BUILD SUCCESSFUL in 17s`; 108 tasks prove automatic Activity-control bootstrap and grant/revoke epochs without capture consent.
- `:core:base:compileDebugAndroidTestKotlin`: `BUILD SUCCESSFUL in 2s`; `:app:assembleDebug`: `BUILD SUCCESSFUL in 19s` with 650 tasks.
- Changed-module lint across `:core:base`, `:data:preferences`, `:tracker:engine`, and `:feature:import-export`: `BUILD SUCCESSFUL in 1m 30s`; only recorded repository baseline findings are reported.
- Test-only lean recovery comparison: 8/8 scenarios pass. After unifying the lease table, focused `LeanTrackingRecoveryModelTest`, `AuthoritativeSessionCoordinatorTest`, and `TrackingCoordinatorTest` pass 21/21 with migration-test compilation (`BUILD SUCCESSFUL in 2m 14s`; 241 tasks).
- Post-lease broad component gate: `:core:base:testDebugUnitTest`, Android-test compilation, debug APK assembly, and `:core:base`/`:tracker:engine` lint completed; the simultaneous tracker-engine test task was discarded after two overlapping Gradle invocations collided on `in-progress-results-generic.bin`. The isolated sequential `:tracker:engine:testDebugUnitTest --no-parallel` rerun passed (`BUILD SUCCESSFUL in 3m 47s`; 228 tasks).
- Repository-wide `testDebugUnitTest` is not green: `:app:testDebugUnitTest` reports 205 of 579 failures rooted in host `UnsatisfiedLinkError: no sqliteX in java.library.path`, with coroutine pre-test failures cascading from the same loader error. The changed-area focused suites remain green, but no broad pass is claimed.
- At this earlier lean-v28 checkpoint the generated schema was uncommitted and no device was attached. The later four-case migration suite passes; process-kill boundaries, deletion/key rotation, legacy-writer interleaves, and source/OEM behavior remain unverified.

## Latest populated v27→v28 migration evidence

- The deterministic exact-v27 fixture contains Location raw/projected facts, positive and reset Step intervals, Activity, Wi-Fi, Cell, Pressure, session/day summaries, stale lease/registration/runtime state, checksum-valid pending legacy WAL, projection checkpoint/outbox/completeness, pending signal, import receipts, and interrupted automatic runtime.
- Migration terminalizes all nonterminal logical sessions and service runs as `V28_MIGRATION_INTERRUPTED` using only factual start/cutoff/completion boundaries, closes an otherwise open `tracker_run` at its own known start, and leaves v28 policy/consent/manifest/broker authority empty and fail-closed.
- Production Room/SQLiteX closes and reopens, production DAOs preserve all seeded facts and observation stamps, full deletion runs with foreign keys enabled, Location projection children cascade with their parent, and another reopen proves no resurrection.
- Integration-owner connected rerun on `Medium_Phone`: `BUILD SUCCESSFUL in 17s`; 1/1 test; 104 tasks. Core unit plus committed Room-schema guard: `BUILD SUCCESSFUL in 9s`; 85 tasks. The implementation run also passed Android-test compilation, core lint, and the same connected test.
- This closes the populated migration/ghost-runtime/deletion-reopen slice only. Portable export/import, backup recovery, legacy-WAL one-time drain, physical-provider/reboot/Doze/OEM behavior and per-source product queries remain unverified.

## Latest atomic source-delivery admission evidence

- The unreleased v28 WAL now records an opaque source-native delivery identity, original unit index
  and count, and observed interval start. Legacy v27 rows migrate with null delivery metadata and
  keep their released interpretation.
- `DurableSourceDeliveryIngress` encodes a complete provider delivery before entering Room, resolves
  every unit against historical physical-registration and authorization intervals, omits denied or
  pre-retention units without renumbering eligible siblings, and rejects an unsplit unit that spans
  an authorization or physical boundary.
- Eligible units allocate one contiguous source-sequence range and insert with `ABORT` in the same
  Room transaction. Injected insertion conflict rolls the sequence allocation and the whole WAL
  batch back. Injected cancellation during payload encoding propagates with zero allocation, and
  cancellation after a batch insert rolls both the WAL write and sequence range back.
- Exact replay is recognized before process-local registration-state validation and compares only
  the source-native unit structure, observed interval, payload version, and checksum. Receipt clocks,
  wall-time reconstruction, quality metadata, source instance, provider generation, and local
  sequence do not turn the same delivery into a new fact. A delivery with historically denied units
  replays as the same sparse persisted subset.
- Focused DAO/ingress implementation run: `BUILD SUCCESSFUL in 2m 45s`; the first integration-owner
  rerun was `BUILD SUCCESSFUL in 10s`, and the physical-retirement regression rerun was
  `BUILD SUCCESSFUL in 1m 54s`; the cancellation-injection rerun was `BUILD SUCCESSFUL in 1m 57s`.
  Populated v27→v28 connected migration runs on `Medium_Phone` remained
  green (`28s` implementation run; `20s` integration-owner rerun). The full debug app assembles in
  `1m 5s`, and affected-module lint succeeds in `1m 26s` with only recorded baseline findings.
- This remains substrate, not a source-completion claim. Activity is now the first adapter to call
  the delivery API; the other five source adapters, source cursors/gaps, scoped
  deletion/no-resurrection, bounded provider batch limits, and canonical materialization/product
  queries remain unimplemented.

## Latest Activity atomic callback adapter evidence

- One parsed Activity Recognition/Transition `PendingIntent` callback becomes one order-normalized,
  versioned source-native delivery. Receipt clocks, broker generations, authorization, local
  sequence and original callback indexes cannot change its identity; exact native multiplicity is
  preserved.
- Room resolves each point independently against historical physical and authorization intervals,
  commits the eligible sparse subset atomically, and the adapter reloads exact ordinal/event-ID
  rows before using writer-stamped evidence. A real Room test admits only the post-registration
  member, preserves its original index and capture identity, and leaves one WAL row after replay.
- Only newly admitted members can reach motion/backend/cache publication. Exact duplicates are
  durable zero-effect. `LeaseUnavailable`, `LeaseLost`, `ProjectionFailed`, and a `Complete` result
  below the delivery ordinal all return retryable with empty selection and no transient effect.
- A fresh read-only adversary found the discarded recovery result and duplicate transient replay as
  two HIGH commit-local defects. Both are mitigated in the integrated code and regression tests; no
  other BLOCKER/HIGH/MEDIUM survived its falsification.
- Focused final gate: 47 tests, zero failures/errors/skips, `BUILD SUCCESSFUL in 3m 48s` (267 tasks).
  Full tracker-engine plus Activity module tests pass in `7m 18s` (267 tasks). Activity API/module
  and tracker-engine lint plus debug APK assembly pass in `3m 17s` (881 tasks); Activity lint is
  clean and tracker-engine retains only its two recorded warnings.
- Full GMS probable-activity ranking, calibrated Activity freshness, asynchronous source-local
  drain, automation epoch/start fencing, typed movement bands, scoped deletion/export and a
  production history query remain explicit blockers. `TI-413` stays `BLOCKED`.

## Latest released-v27 recovery-containment evidence

- `MIGRATION_27_28` now captures the `source_event_wal` AUTOINCREMENT high-watermark and collected-data
  epoch in one durable singleton. It snapshots or seeds exactly the four released v1 projections,
  clamps their work cursor to the retained WAL floor, and blocks any unexpected projection target.
- Released v1 registrations are inactive after migration. The four live projections are version 2
  and first activate at `cutoff + 1`; Activity and event-frame dispatchers query their exact live
  projection ID/version, so a retained v1 effect cannot cross into current runtime behavior.
- Undelivered legacy effects below the cutoff participate in the WAL retention floor even when the
  v1 projection checkpoint is already caught up. Once bridged or terminally suppressed, bounded
  maintenance removes both eligible WAL and terminal effect payloads. Full collected-data deletion
  atomically removes the recovery records with WAL/projection state.
- The frozen v1 payload decoder has golden-byte coverage for all eight released payload forms.
  Location remains legacy-owned; this containment does not add a second canonical writer or grant
  v27 rows v28 purpose, consent, manifest, or session attribution.
- Three read-only adversaries independently reviewed migration/projection isolation, process startup,
  and backup/import scope. One implementation review found the pending-outbox WAL-retention hole and
  indefinite terminal-effect retention; both are fixed and covered. The reviewers still block source
  materializers on a process-single-flight startup fence, crash-safe frozen drain, stale descriptor
  reconciliation, production backup-wrapper proof, and partial-database merge containment.
- A fresh final-diff adversary found no remaining commit-local `BLOCKER` or `HIGH` issue and confirmed
  SQL/entity/schema parity, the exact released targets, pruned high-watermark, v1/v2 isolation,
  retention/cleanup, deletion ordering, and frozen decoder bytes. It did not certify the intentionally
  absent startup/drain runtime.
- Final verification: complete `:core:base` + `:tracker:engine` unit suites pass in `8m 17s` (246
  tasks); four connected migration cases pass on `Medium_Phone` in `33s` (104 tasks); scoped lint and
  `:app:assembleDebug` pass in `5m 5s` (860 tasks), retaining only recorded lint baselines/warnings.
- This is a containment boundary, not recovery completion. No provider, materializer, history query,
  restore path, or rollout flag is activated by it; TI-184 remains `IN_PROGRESS`.

## Latest frozen-v27 runtime-drain evidence

- `LegacyV27ProjectionRecovery` is a process-single-flight, boot-aware elapsed-lease owner for only
  the immutable migration cutoff. Every DAO mutation is generation/data-epoch fenced; target
  terminalization now also requires `last_completed_ordinal = required_through_ordinal`.
- Activity v1 and unconsumed joined-frame effects are terminally suppressed without invoking
  automation or capture. Location shadow state is preserved and no current or canonical Location
  projector is called. Event-frame v1 alone bridges into the existing Steps/Pressure typed tables.
- Destination insertion/semantic duplicate verification, poison evidence and cursor progress share
  one Room transaction. Exact replay is one effect; changed metric/window identity never overwrites.
  Terminal event-frame failure evidence is retained rather than erased during v1 retirement.
- A pending v1 event-frame effect whose WAL was already pruned uses a frozen effect-only fallback.
  Its typed metric is retained with `LEGACY_UNKNOWN` clock/boot provenance and explicit
  `LEGACY_V27_OUTBOX_ONLY,PARTIAL_CLOCK_PROVENANCE`; current process clocks are never substituted.
  Sparse AUTOINCREMENT ordinals are not mislabeled as loss, while checksum/decode/collision,
  retention rejection, incomplete Location shadow and outbox-only recovery yield truthful partial.
- The immutable cutoff includes WAL sequence, retained WAL and retained outbox high-watermarks.
  Active-target outboxes at nonpositive or pre-activation ordinals are terminally quarantined, and
  an outbox paired with raw WAL must match the exact frozen v1 cycle before it can be acknowledged.
  Both acquisition and destination timestamps must survive the effective retention/deletion floor.
- The focused DAO substrate passes 17/17 tests; bridge plus frozen projection compatibility passes
  10/10; and the coordinator recovery contract passes 18/18 Robolectric tests, including retry,
  retention, poison continuation, unknown-generation fail-closed behavior, lifecycle epoch fencing,
  Location non-interference, outbox-only recovery, activation quarantine and semantic conflict. The
  full affected-module suite passes in `4m 55s`; seven connected migration cases pass on
  `Medium_Phone` in `40s`; lint, full debug assembly and schema drift pass in `3m 6s`. A fresh
  correction verifier found no new commit-local `BLOCKER`/`HIGH`. Connected migrate→runtime proof
  still depends on the separate process-wide startup fence, which is required before any
  provider/live-v2 work may run.

## Latest pre-wiring R1b evidence

- Three new `gpt-5.6-sol` high-reasoning reviewers independently completed read-only data/migration, Android/privacy/power, and product/validation attacks. They re-read the full design and live diff, ran repository-reference/static inspections, and made no edits or Gradle invocations.
- Lead verification reproduced that the generic Phase 3 repositories had no production call sites, and TI-D051 removed them. No typed outside-session source path, dirty-day queue, truthful day repository state, explanation contract, or ambient day-product presenter exists.
- R1b remains `BLOCKED` before product wiring. WAL payload authentication/raw quarantine are mitigated; force-stop ghost demands, typed correction/day repair, scoped deletion, wall-time uncertainty, low-storage recovery, cancellation coverage, and Today rollover remain.
- No source materializer or day-product UI wiring is authorized. The existing policy-authority settings error/repair state remains the explicitly documented Phase 1 safety exception.

## Latest scope-proportionality review evidence

- Three fresh `gpt-5.6-sol` high-reasoning reviewers completed independent read-only product/use-case, architecture/maintenance, and delivery/validation attacks. None edited files or ran Gradle.
- All three returned `BLOCK / RESCOPE`. They independently preserved policy authority, explicit Activity control/capture separation, immutable session intent, boot/generation callback fencing, source-qualified post-start evidence, the sole Location writer, Steps replay safety, ghost-session cleanup, exact migration execution, and truthful scheduling/privacy presentation.
- All three independently rejected or deferred the current-release requirement for a generic unused Phase 3 platform, full DayOverview/M3 journal, ambient breadth, production cohorts/SLOs, exhaustive OEM/soak evidence, and repeated review governance without a production vertical slice.
- At review time, lead verification confirmed that v27's 51 owned tables had become 74 under local v28, `AuthoritativeSessionCoordinator` exceeded 2,200 lines, generic Phase 3 repositories had no production callers, CI had no device farm, and the app advertised no remote telemetry.
- Follow-up integration removed the 12 unused generic tables, isolated merge import, preserved
  explicit automatic Activity-control intent, authenticated/quarantined raw WAL payloads, and
  unified the two lease mechanisms. At that follow-up checkpoint, v28 had 66 entities after the two narrowly owned v27
  recovery records, Activity automation action/epoch state, and the source-local product-lane
  activation/cursor/retention fence; these are local mitigations, not phase completion.
- No external system or rollout state was changed by the scope review or follow-up integration.
- Product follow-up resolved one scope point: ambient persistence is required. TI-D007/TI-D047 and the ambient matrix now treat it as phased, per-source product work with default-off rollout gates, not as removed scope. This does not authorize continuous ambient Pressure or active background Wi-Fi scans.
- Product follow-up also made sole-source capture a hard invariant under TI-D054: manual only-X has no hidden control; automatic only-X persists only X while separately declared controls remain non-product inputs. No source may depend on Location or another captured source to become queryable.

## Latest platform, source-quality, and pipeline expert synthesis

Three read-only `gpt-5.6-sol` high-reasoning specialists independently reviewed current repository ownership, Android execution constraints, and all six acquisition paths before the revised pipeline was written.

- **Android platform/lifecycle:** automatic cold start must use a documented exemption such as an Activity Transition `PendingIntent`; Sampling is not that exemption. Current official docs list `ON_FOOT` as supported, so acceptance must be verified against the pinned client/provider instead of rejected by assumption. Durable origin/type/epoch intent must precede the external service call. Foreground-service type and while-in-use eligibility are independent gates. WorkManager is inexact maintenance, not a sampler. Provider `PendingIntent` registration needs boot/update/force-stop reconciliation.
- **Source power/quality:** current floor-blind planning can make responsive Location passive or disable sole-source Pressure; `trySend` can silently lose qualified callbacks; authorization-only changes can restart providers; per-event/global drain work amplifies writes and cross-source stalls. Pressure currently reaches roughly 61/306/1,230 writes per minute at saver/balanced/responsive and should use source-qualified microsegments. Radio headers must preserve empty/unchanged/freshness, and Steps needs interval/boot/reset ownership.
- **Repository architecture:** keep one concrete app-scoped `SourceSupervisor`, one physical runtime per source, one `TrackingWriter` Room mutation owner, one source cursor/projector lane per source, typed facts/membership/completeness, and one `TrackingHistoryRepository`. Retire the global projection dispatcher/drain, duplicate callback transaction owners, forced all-source canonical state, session-centric composite persistence and coordinate mutation. Do not restore the unused generic join/activation/receipt platform.
- **Official API research:** the project currently targets/compiles API 37 with min API 26, WorkManager 2.11.2, Play services Location 21.4.0 and Room 2.8.4. Current official guidance supports provider batching, passive/process-resilient Location `PendingIntent`s, opportunistic radio callbacks, Health Connect on-device Steps on API 34 + Extension 20, the accountless Recording API fallback, and Power Profiler/system tracing plus Macrobenchmark `PowerMetric`. Android documents Battery Historian as no longer maintained.

The expert synthesis produced TI-D061–TI-D069 and fresh R4 produced TI-D070–TI-D076. No runtime code or production UI was changed by these design reviews and no tests were rerun for documentation-only edits. The next gate is implementation of the revised v28 boundary, followed by scoped host tests and exact Android migration/device evidence—not another review of unchanged prose.

## Fresh R4 adversarial dispositions

R4 is deliberately incremental: each fresh perspective reviews the pipeline after the preceding accepted corrections. The data/migration reviewer completed a read-only live-repository attack and returned `BLOCK_RESCOPE`. Its architecture proportionality judgment was positive: one writer plus six logical lanes is suitable for this local six-source SQLite app, provided the writer stays a thin transaction executor rather than becoming another coordinator god object.

| Finding | Severity | Disposition | Integrated correction / remaining evidence |
| --- | --- | --- | --- |
| R4-D01 current v28 encodes coupled physical/authorization lifetime and lacks target records | `BLOCKER` | `ACCEPTED` | TI-D057/TI-D068 remain unimplemented; reshape v28 with authorization intervals, purpose epochs/retention, source cursors/gaps/membership, deletion/owner fences and radio headers before schema freeze |
| R4-D02 one authorization stamp can straddle revoke/session boundaries | `BLOCKER` | `MITIGATED_IN_DESIGN` | TI-D070 requires authorization-homogeneous subranges, Pressure boundary close and Step baseline+gap; six-source delayed-boundary tests remain |
| R4-D03 delivery receipt cannot express cardinality-changing correction | `BLOCKER` | `MITIGATED_IN_DESIGN` | TI-D071 separates delivery receipt from logical range identity and requires explicit upsert/delete/replace-range mutations; typed executable proof remains |
| R4-D04 scoped deletion and current merge import can resurrect/inconsistently restore facts | `BLOCKER` | `MITIGATED_IN_DESIGN` | TI-D072 defines narrow fences, staged base-fact import, identity+checksum conflict rules and derived recomputation; implementation/crash proof remains |
| R4-D05 realistic v27→v28 migration/reopen/product/delete/import execution absent | `BLOCKER` | `MITIGATED_PARTIALLY` | four connected migration/reopen/fact/deletion containment cases now pass on `Medium_Phone`; frozen runtime drain, production backup-wrapper, portable export/import, partial-database rejection and shared production-history proof remain pre-freeze gates |
| R4-D06 cancellation is converted to storage/projection failure | `HIGH` | `ACCEPTED` | rethrow `CancellationException` at encode/admission/projection/drain and assert no attempt/cursor/quarantine change; current code remains unfixed |
| R4-D07 legacy cutover lacks atomic destination owner fence | `BLOCKER` | `MITIGATED_IN_DESIGN` | TI-D072 adds narrow persisted source/destination owner generation checked in every legacy/candidate mutation; paused-writer race remains |
| R4-D08 portable export omits Activity/Steps/Pressure and exposes legacy raw radio identity | `HIGH` | `ACCEPTED` | export must come from typed product facts/membership/completeness for all released sources and exclude control/raw radio data; current exporter remains blocking |
| R4-A01 Sampling can still create automatic start effects; durable trigger/type envelope is absent | `BLOCKER` | `ACCEPTED` | TI-D062 already specifies Transition-only pre-service CAS; current `ActivityAutomationProjection`/`BackgroundTrackingApi` and service-first launch remain blocking |
| R4-A02 bounded `trySend` queues and radio dedupe silently lose qualified deliveries | `BLOCKER` | `ACCEPTED` | TI-D056/TI-D061 require lossless bounded handoff/backpressure and radio headers; current five runtime queues and unchanged-result drops remain blocking |
| R4-A03 receipt-time/persistence gates reject valid pre-boundary and control evidence | `BLOCKER` | `ACCEPTED` | TI-D055/TI-D070 require observed-time authorization-homogeneous bulk admission; current registration-ACTIVE and persistence gates remain blocking |
| R4-A04 demand floors are not operative; sole-source Pressure can be disabled | `BLOCKER` | `ACCEPTED` | TI-D058/TI-D060 already define binding floors/targets; `SourceDemand`/resolver implementation and exhaustive property/device proof remain |
| R4-A05 FGS union drops `specialUse` in mixed plans and Activity watcher remains a second owner | `HIGH` | `ACCEPTED` | TI-D062 exact union and one service owner remain unimplemented; test every nonempty source mask on API 34–37 |
| R4-A06 Pressure still checkpoints Room per raw 1/5/20 Hz callback | `HIGH` | `ACCEPTED` | TI-D065 qualified microsegments and bounded crash gap remain unimplemented; window-proportional transaction/power gate blocks rollout |
| R4-A07 ambient Location PI/rearm and selected HC/Recording Steps continuity are absent | `HIGH` | `ACCEPTED` | TI-D063/TI-D064 are target-only; process/reboot/update/force-stop/provider-switch/overlap/device evidence remains |
| R4-P01 zero Step delta is discarded and no-row equals zero | `HIGH` | `MITIGATED_IN_DESIGN` | TI-D073 distinguishes baseline-only, covered verified zero, and positive-delta `RECORDING`; runtime/query/UI implementation remains |
| R4-P02 incompatible status vocabularies invite null/empty false-zero UI | `HIGH` | `MITIGATED_IN_DESIGN` | TI-D074 defines orthogonal availability/evidence/product/coverage axes and exhaustive consumer mapping; current History/Today remain blocking |
| R4-P03 Location context may borrow capture consent for ambient radio | `HIGH` | `MITIGATED_IN_DESIGN` | TI-D075 requires typed membership/session/purpose/epoch compatibility and excludes control/cross-session reuse; resolver tests remain |
| R4-P04 central ownership thinness is not enforced | `HIGH` | `MITIGATED_IN_DESIGN` | TI-D076 bounds supervisor/projector/writer/history and separates export/deletion/import/context orchestration; architecture/stress tests remain |

No code, migration, UI, or external state was changed by R4. The aggregate R4 gate remains `BLOCKED` until accepted blockers/highs are implemented and verified; product/scope explicitly found the reduced architecture proportionate for this app.

## Fresh pre-materializer R1 dispositions (2026-08-22)

Three fresh read-only reviewers attacked the actual containment diff and executable plan from Android/power/privacy, data/migration, and product/scope perspectives. The aggregate verdict is `BLOCK / RESCOPE NARROWLY`. These dispositions supersede confidence-based progress claims; no materializer or production history/UI wiring is authorized.

| Finding | Severity | Disposition | Integrated correction / remaining evidence |
| --- | --- | --- | --- |
| R1-A01 successful fresh-empty Wi-Fi/Cell callbacks were dropped | `BLOCKER` | `MITIGATED` | confirmed fresh empty is persisted as zero coverage; failed/unknown/all-stale delivery remains absent; runtime-fake/device proof remains |
| R1-A02 paid Wi-Fi scans/Cell refreshes could continue for registration lifetime | `HIGH` | `MITIGATED` | TI-D083 adds finite direct-capture-only first-evidence budgets and keeps passive callbacks; calibration/reconfiguration/device evidence remains TI-212 |
| R1-A03 exact replay identity is only in-memory and generation-local | `HIGH` | `MITIGATED_FIRST_ADAPTER` | Activity now constructs a compact source-native identity before sequence allocation and recognizes exact replay across process-local receipt metadata and registration changes; replay produces no repeated motion/backend/cache effect. The other five adapters and source rollout remain blocked. |
| R1-A04 Wi-Fi lost child provider time | `HIGH` | `MITIGATED` | payload v2 retains each admitted AP's provider elapsed time and decodes v1 unchanged |
| R1-A05 radio identifiers were unkeyed and Cell retained subscription identity | `HIGH` | `MITIGATED` | containment withholds identifiers; TI-D087 makes identity products unavailable until purpose/epoch HMAC rotation and non-identifying SIM grouping exist |
| R1-A06 cancellation was swallowed as storage/projection failure | `HIGH` | `ACCEPTED` | generic guarded `runCatching` sites now rethrow cancellation; coordinator catches, sequence allocation and boundary injection remain TI-312 blockers |
| R1-A07 Wi-Fi required an unrelated Nearby permission | `HIGH` | `MITIGATED` | capability, onboarding/settings and manifest now request the permissions used by `startScan()`/`getScanResults()`; device/API proof remains |
| R1-A08 attempt/result linkage and oversized delivery identity were weak | `MEDIUM` | `ACCEPTED` | operational attempt identity and compact source-native delivery identity belong to TI-182/TI-212; no product claim depends on current diagnostics |
| R1-D01 receipt-time authority rejects valid pre-revocation evidence | `BLOCKER` | `MITIGATED_LOCALLY` | ingress now resolves immutable authorization by observed boot/elapsed time, accepts valid pre-boundary evidence and denies exact/post boundary; six source-specific batch/window splitters and device proof remain |
| R1-D02 physical generation and authorization lifetime are inseparable | `BLOCKER` | `MITIGATED_LOCALLY` | unreleased v28 now has physical-only generations and independent authorization revisions; shared acceptance and Activity provider fakes prove accepted-before-retire replacement, failed-replacement preservation, durable stale cleanup and cancellation convergence. The five non-Activity provider handoffs and process recovery remain independently gated. |
| R1-D03 radio replay identity fails across process/generation changes | `BLOCKER` | `MITIGATED_SUBSTRATE` | the WAL/DAO seam now persists process-stable delivery identity and exact sparse-subset replay, but Wi-Fi and Cell do not yet construct privacy-safe canonical delivery bytes or call this seam; both materializers remain blocked |
| R1-D04 checksum-valid pending v27 WAL would be quarantined as corruption | `BLOCKER` | `MITIGATED_CONTAINMENT` | pending rows are checksum-classified; migration snapshots a high-watermark and the exact four released v1 targets; live projections move to v2 at cutoff + 1; live outbox dispatch is generation-filtered; pending effects pin WAL; and four connected migration cases pass. The process-wide frozen drain and crash/no-new-purpose proof remain TI-184. |
| R1-D05 migration fixture omits released facts and exposes legacy active ghosts | `HIGH` | `MITIGATED_LOCALLY` | the populated fixture preserves all six typed source families/history/WAL state and terminalizes nonprovable session/service/tracker runtime before production DAO reads; emulator reopen/delete/reopen passes |
| R1-D06 delete followed by old merge import resurrects base/derived data | `BLOCKER` | `MITIGATED_CONTAINMENT` | recognizable v2-v28 Tracker databases and `tracker-database-backup` ZIPs now fail before merge mutation and cannot report zero-row success; provenance-bearing partial/foreign database merge still requires scoped fences and writer staging before typed import activation |
| R1-D07 cancellation can consume sequence/state without a WAL fact | `HIGH` | `MITIGATED_FIRST_ADAPTER` | encode cancellation propagates with zero state, cancellation after delivery insert rolls back WAL plus its contiguous sequence range, and Activity cancellation cannot publish a transient effect; the other adapters and future cursor/gap processing remain gated |
| R1-D08 identity-free containment cannot backfill identity products | `HIGH` | `MITIGATED` | TI-D087 splits aggregate and identity tiers; containment rows are explicitly unavailable to unique-network/cell/map products |
| R1-D09 no atomic destination-owner fence protects a real cutover | `BLOCKER` | `ACCEPTED` | add the narrow fence only with an actual candidate writer; both legacy and candidate transactions must recheck it; Location remains protected |
| R1-P01 first Steps vertical had a circular dependency | `BLOCKER` | `MITIGATED` | TI-410 now owns its local writer/cursor/correction/deletion/export/query slice; TI-322 generalizes only afterward; add plan-DAG test |
| R1-P02 rollout preference was encoded as a global source dependency chain | `HIGH` | `MITIGATED` | source verticals now depend on shared/source-specific prerequisites, not the previous source's gate |
| R1-P03 Dashboard blocks non-Location only-X starts on Location permission | `HIGH` | `MITIGATED` | a centralized Dashboard decision now starts ready Pressure/Steps/Activity without Location, requests precise Location only for Location/Wi-Fi/Cell, and passes 17 source-aware assertions; device permission flow and other entry surfaces remain |
| R1-P04 completeness is erased by goal/game/widget/notification consumers | `HIGH` | `ACCEPTED` | TI-500 now gates all numeric daily-metric consumers, not only History surfaces |
| R1-P05 sessionless ambient facts lack stable historical-zone identity | `HIGH` | `ACCEPTED` | TI-180 WAL/day allocation adds immutable structural-zone identity; Prague/New York, DST and delayed-import proof required |
| R1-P06 radio containment could silently reduce existing identity products | `HIGH` | `MITIGATED` | TI-D087 requires explicit aggregate availability and identity-product unavailability plus legacy shadow ownership |
| R1-P07 optional Location context purpose compatibility is ambiguous | `MEDIUM` | `ACCEPTED` | explicit primary-purpose × Location-purpose/session/epoch/freshness matrix and named `CONTEXT_NOT_AUTHORIZED` absence required; context never creates demand |
| R1-P08 future paged Days contract delays the first useful query | `MEDIUM` | `MITIGATED` | TI-500 begins with Today/day/session/fact/completeness over existing consumers; paged Days remains separate product work |
| R1-P09 device matrix was universal yet lacked per-source numeric budgets | `MEDIUM` | `MITIGATED` | TI-212 uses source-relevant devices and current Power Profiler/system trace/Macrobenchmark; each source still needs a baseline, quality floor and active/write/wakeup budget |

The accepted blockers are data-loss, privacy, sole-source, or truthful-product boundaries—not invitations to rebuild the removed generic platform. The next structural implementation remains TI-180/TI-181/TI-184, with TI-213 proceeding independently and no source inheriting another source's rollout blocker.

## Fresh R1 authority/source-rollout dispositions (2026-08-24)

Three new read-only reviewers attacked the current repository and next implementation wave from data/migration, Android/power/privacy, and product/scope perspectives. The aggregate gate is `BLOCK`. R1-DM01 and R1-DM02 are locally mitigated and focused verification passes; every other row below remains a gate, accepted follow-up, or explicitly preserved disagreement. This round does not authorize a materializer, ambient rollout, or production UI consumer.

| Finding | Severity | Disposition | Integrated correction / remaining evidence |
| --- | --- | --- | --- |
| R1-DM01 policy and registration used incompatible boot-domain identities | `BLOCKER` | `MITIGATED_LOCALLY` | one process-canonical `BootClockDomainProvider` now supplies the readable BOOT_COUNT identity and a shared process-stable fallback; focused policy-provider and registration-revocation tests pass |
| R1-DM02 delayed Activity evidence could cross a suppression automation epoch | `BLOCKER` | `MITIGATED_LOCALLY` | the epoch persists boot domain plus effective elapsed boundary; delivery, outbox, action, and finalizer paths reject or strip automation eligibility from pre-boundary/different-boot observations; focused tests pass |
| R1-DM03 Cell replay identity includes generation/process-local state | `HIGH` | `ACCEPTED` | construct a privacy-safe source-native identity before sequence allocation and prove replay across process and physical-generation changes; Cell materialization remains blocked |
| R1-DM04 portable export omits Activity/Steps/Pressure and exposes raw radio identity | `HIGH` | `ACCEPTED` | complete the six-source purpose-aware export/minimization contract and prove control-only/raw SSID/BSSID/tower/subscription identity is absent before any source rollout |
| R1-DM05 migration-time terminalization may contradict later retained v27 facts | `MEDIUM` | `UNRESOLVED_DISAGREEMENT` | containment treats unprovable runtime as interrupted; the reviewer argues later retained facts can make that timeline contradictory. Preserve both positions until a populated migrate→drain→query recovery timeline proves the truthful result |
| R1-DM06 populated migration fixture seeds only 26 of 51 released tables | `LOW` | `ACCEPTED` | expand only tables that can affect tracking recovery/retention/import semantics; do not turn this into an indiscriminate fixture framework |
| R1-AP01 default rollout enables EVENT acquisition without reachable product writers | `BLOCKER` | `MITIGATED_LOCALLY_PENDING_FRESH_R1` | rollout schema v3 defaults every source to `CONTAINED`; missing/v27/old-global rows authorize no provider; explicit named-source event ownership requires a shadow/canonical stage. No actual source is activated. |
| R1-AP02 Activity and Wi-Fi low-power plans violate sole-source capture floors | `BLOCKER` | `MITIGATED_LOCALLY_PENDING_FRESH_R1` | `SourceAcquisitionFloor` makes direct capture floors source/purpose-specific and focused source-floor/resolver/session tests pass. Fresh review and provider/device evidence must still prove Activity classification and fresh Wi-Fi evidence cannot be silently downgraded. |
| R1-AP03 failed unregister forgets the provider handle and permits an orphan alongside G2 | `HIGH` | `ACCEPTED` | retain durable retirement ownership until provider removal succeeds or is truthfully quarantined; prove failed unregister/restart/reconcile cannot create two effective registrations |
| R1-AP04 Cell handoff/replay is unbounded and generation-local | `HIGH` | `ACCEPTED` | introduce a bounded crash-auditable pre-WAL lane with source-native replay identity and explicit gap evidence; no implicit loss or duplicate product effect |
| R1-AP05 boot/update can leave automatic Activity Transitions unrearmed | `HIGH (release gap)` | `MITIGATED_LOCALLY_PENDING_DEVICE` | Activity automatic actions are durable and boot/epoch/expiry fenced; startup, boot/update, previous-exit, force/explicit-stop and inactive-descriptor paths reconcile in host tests without reviving terminal entries. Connected boot/update/force-stop proof remains. |
| R1-AP06 ambient collection lacks a production host/source vertical | `CONDITIONAL BLOCKER` | `ACCEPTED` | safe only while ambient rollout remains off; blocker for any ambient exposure. Begin with one complete source vertical, planned as Steps, including consent, retention, export/delete, civil day, query, and explanation |
| R1-PV01 Activity/Wi-Fi acquisition rungs contradict declared only-source quality | `BLOCKER` | `MITIGATED_LOCALLY_PENDING_FRESH_R1` | same source-floor correction as R1-AP02; no battery tier may silently remove the direct source evidence needed for only-X. Provider/device quality and energy proof remains. |
| R1-PV02 bounded Location/Wi-Fi queues can lose an overflow gap on crash; Cell is unbounded | `BLOCKER` | `ACCEPTED` | make handoff durability source-local, bounded, and gap-auditable before receipt; storage stalls and process death must preserve fact or explicit completeness loss |
| R1-PV03 provider/service `ACTIVE` is conflated with source `RECORDING` | `HIGH` | `ACCEPTED` | persist distinct accepted-runtime and source-qualified-evidence transitions and expose truthful no-observation/degraded states |
| R1-PV04 ambient WAL lacks immutable civil-zone/day allocation | `BLOCKER` | `ACCEPTED` | blocks v28 schema freeze and ambient product exposure until structural zone/day identity survives delayed admission/import, travel, DST, and clock uncertainty |
| R1-PV05 generic purpose-blind Location enrichment is dead or unsafe scope | `MEDIUM` | `MITIGATED_BY_SCOPE` | keep it out; optional Location context is query-time only, purpose/session/epoch/freshness compatible, and never creates provider demand |

Focused verification on 2026-08-24 is `BUILD SUCCESSFUL` for the canonical boot clock, policy effective-time provider, registration revocation boundary, Activity delivery epoch, durable ingress, automation epoch authority, outbox dispatcher, automatic-start action, authoritative coordinator, and force/explicit/previous-exit finalizers. This is evidence only for R1-DM01/R1-DM02 and nearby regressions; it does not close the aggregate R1 gate.

## Latest database-backup import containment evidence

- Raw Tracker databases with the released schema signature are classified before merge planning or
  target mutation and return an explicit restore-required failure. A deleted target therefore cannot
  be repopulated by selecting an older full Tracker database as a merge import.
- `tracker-database-backup` ZIPs are boundedly preclassified from their manifest before any archive
  entry reaches an importer. Their extensionless database entries are no longer skipped and followed
  by a misleading zero-row `COMPLETE` job.
- Failed backup selections remain incomplete and can be selected again after the separately gated
  cold-start, empty-target restore path exists. This containment commit does not implement restore
  and does not authorize provenance-free partial database merge.
- The complete `:feature:import-export:testDebugUnitTest` suite passes 317/317 together with
  `:feature:import-export:lintDebug`; the lint report contains only the module's recorded translated
  plural baseline and no changed-file finding.

## Corrected R1 gate and bounded follow-ups (2026-08-26)

Three fresh `gpt-5.6-sol` high-reasoning reviewers independently attacked the committed boundary at
`d51723280` from data/migration, Android/power/privacy, and product/scope perspectives. The corrected
R1 outcome is **PASS only to begin TI-410's smallest manual/session Steps vertical**. It is not a
source rollout, ambient authorization, general materializer approval, or approval for a new history
destination. Automatic Steps, ambient Steps, the other five source writers, and broad production UI
wiring remain independently gated.

| Finding | Severity | Disposition | Integrated correction / Steps completion gate |
| --- | --- | --- | --- |
| R1C-D01 metadata or a projector alone could make Steps look executable | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | install exactly one compile-time Steps binding, source-local cursor/drain, and atomic rollout/lane activation; metadata-only activation remains contained |
| R1C-D02 replay/correction/deletion could diverge without one source-local commit boundary | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | one Room transaction must combine stable logical fact/correction identity, typed mutation/receipt, deletion epoch/cutoff, and source cursor; crash/replay/correction/delete/no-resurrection tests block completion |
| R1C-D03 Steps status could collapse baseline, covered zero, positive evidence, partial, materialized, and queryable | `MEDIUM` | `ACCEPTED_AS_TI_410_GATE` | add a Steps-local truth type and preserve all axes through production consumers; no row is never fabricated zero |
| R1C-D04 interrupted Activity callback `.bin.new` files escaped the advertised spool bound | `MEDIUM` | `MITIGATED` | `bfa0c9da1` removes exact orphans before capacity checks, syncs cleanup, and fails closed if inventory/deletion/durability cannot be proven; full Activity suite passes 310/310 |
| R1C-A01 a permanently blocked post-deletion worker could leave process-local writers/control paused after an in-process repair | `MEDIUM` | `MITIGATED` | `648f894a4` retains one deletion-epoch/startup-generation-fenced obligation and rearms only on an explicit authoritative `Ready`; no permanent-state polling; full app suite passes 652/652 |
| R1C-A02 process-local partial startup initialization remains fail-closed until process restart | `LOW` | `ACCEPTED_FOR_TI_410` | acceptable for manual Steps; device startup/process evidence remains required before rollout |
| R1C-P01 numeric Steps consumers erase missing/partial truth | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | update the existing session/Today/Dashboard and completion-producing consumers so only a complete/ready value can drive goals, widgets, notifications, achievements, or streaks |
| R1C-P02 the live tracking surface is Location-shaped and misleading for only-Steps | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | make the existing surface source/evidence-aware; do not add a destination or the deferred Days-first journal |
| R1C-P03 manual-start truth is not shared by widget, shortcut, and stale-service restart paths | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | centralize a typed start result around current reachability plus durable enqueue acknowledgement and reuse it at every existing manual entry surface |
| R1C-P04 portable export contains only legacy numeric Steps and cannot round-trip the authoritative typed fact | `HIGH` | `ACCEPTED_AS_TI_410_GATE` | add typed Steps portable export/import through the authoritative source writer and deletion fence; replay/import must be idempotent and cannot resurrect deleted facts |
| R1C-P05 a cached prerequisite snapshot may prompt once after authority changes | `LOW` | `ACCEPTED_FOR_TI_410` | current start still fails closed; refresh/device permission-flow evidence remains before rollout |

The reviewers explicitly rejected another horizontal framework wave. TI-410 must reuse the shared
counter runtime, released `StepInterval` as frozen legacy evidence rather than a new-write target,
the source-product lane, deletion epoch, and existing product surfaces. A universal writer command
language, six-source receipt schema, full
`DayOverview`, automatic control expansion, Health Connect/Recording ambient continuity, and new
navigation remain outside this gate. Device proof remains unverified on the current boundary.

Lead verification after the two bounded corrections is green: full tracker-engine 1,544/1,544,
Activity 310/310, app 652/652, Dashboard 200/200, focused architecture/recovery tests, and
`checkRoomSchemaDrift`. The current local checkpoint is `648f894a4`; all candidate source ownership
still defaults to `CONTAINED`, so no source/materializer/product rollout claim follows from these
passes.

## Manual Steps source-local writer checkpoint (2026-08-26)

### Outcome

The program remains on the corrected R1 critical path. The first concrete source-local candidate
writer now exists for manual/session Steps, with exact cursor fencing, durable contribution
identity, source-local poison isolation, retained-floor handling, and retention-worker
reconciliation. It is intentionally dormant: production has no executable Steps catalog binding,
canonical activation, legacy-writer cutover, fact reader, or UI wiring. Therefore this checkpoint
is implementation progress, not a `MATERIALIZED`, `QUERYABLE`, or rollout claim.

| Commit | Scope | Result |
| --- | --- | --- |
| `540d4a037` | make `EVENT_SHADOW` product-inert and require exact canonical admission | committed; shadow cannot acquire or persist product capture |
| `8bb6d606f` | source-local `steps-session-facts` lane, exact cursor CAS, durable fact receipt, recovery/admission hints, poison isolation | committed; no polling or wake scheduler, no production activation |
| `af846143c` | drain the Steps lane before WAL pruning and remove expired self-contained UPSERT payloads at the authoritative monotonic retention floor | committed; redacted deletion tombstones survive and stale facts cannot remain queryable |

### Evidence

- Core/engine writer, DAO, recovery, and sink shard: `BUILD SUCCESSFUL in 3m 25s`.
- Strengthened negative product-time/retention test: `BUILD SUCCESSFUL in 3m 4s`.
- App retention workers with lazy lane provider and startup-generation operation lease:
  `BUILD SUCCESSFUL in 1m 47s`.
- Steps-fact DAO plus both real retention-worker paths: `BUILD SUCCESSFUL in 4m 2s`; the final
  monotonic-floor worker rerun is `BUILD SUCCESSFUL in 1m 39s`.
- Fresh adversaries found and drove corrections for destination-time resurrection, stale terminal
  pinning, negative-time poison, valid-prefix loss, retention liveness, startup-generation fencing,
  and retained self-contained fact payloads. A different final reviewer reported no remaining
  `BLOCKER`, `HIGH`, or `MEDIUM` finding in the corrected slice.

### Gate status

- Current phase: TI-410 manual/session Steps, `IN_PROGRESS`.
- Passed assertions: source-local replay is exactly-once in effect; shadow is product-inert; poison
  blocks only Steps; valid prefixes commit atomically; stale retained events/failures converge;
  retention cannot prune needed WAL first or retain expired Steps UPSERT payloads.
- Failed or unverified assertions: no executable writer activation; no legacy/candidate destination
  owner fence; no logical-session bridge; no truthful `StepCountState`; no production Today/session
  query; no typed portable round trip; no existing-product consumer proof; no device/process/reset
  evidence. Automatic and ambient Steps remain separate blocked gates.

### Next wave

Implement segment-effective writer provenance and service-run-scoped completeness first. Then add
a cutover coordinator that drains/fences legacy commands without rolling back unrelated batch
destinations, binds replacement writers to the new generation, and reconstructs a coherent empty
writer generation after full deletion. Retention-boundary and trip-deletion/no-resurrection tests
must pass before a truthful observable `StepCountState` production query or UI is reintroduced.
Automatic Steps, ambient Steps, other sources, and broad `DayOverview`/new navigation remain outside
this wave.

## Steps durable ownership R1 disposition (2026-08-26)

### Outcome

Commit `ef1da62d2` closes the minimum durable identity and dormant one-writer-fence slice without
shipping the premature product reader. New v28 sessions persist paired logical/session-run IDs;
candidate facts carry the physical run ID; zero-delta covered intervals keep an otherwise empty
Steps-only segment; and both released legacy Steps destinations and the dormant candidate fact lane
must match an exact owner generation inside their Room write transaction. The low-level owner CAS
is explicitly not a cutover coordinator and has no production caller.

### Evidence

- v27→v28 Room migration: `8/8` instrumentation tests pass on `Medium_Phone(AVD) - 16`, including
  production reopen/query/delete/reopen and preservation of the legacy owner fence.
- Focused core owner/fact DAO tests: `BUILD SUCCESSFUL in 50s`.
- Seven focused tracker-engine bridge/fence/recovery classes: `BUILD SUCCESSFUL in 1m 19s`.
- App retention/deletion graph and workers: `BUILD SUCCESSFUL in 2m 26s`.
- Post-commit `checkRoomSchemaDrift`: `BUILD SUCCESSFUL in 33s`.
- Three independent R1 perspectives returned `NO_GO` for candidate cutover or Trip Detail wiring,
  while accepting the dormant identity/fence foundation as proportionate and battery-neutral. Two
  reviewers were fresh; the product/scope perspective used the available independent reviewer when
  the four-thread ceiling prevented another fresh thread.

### Gate status

- Passed: durable run attribution, exact current-owner checks, additive unreleased-v28 migration,
  deletion survival of the legacy fence, no new provider registration/timer/wakeup, and clean
  focused host/device/schema gates.
- Blocked: owner-generation-aware rollback, mixed-batch cutover, historical segment-effective
  writer selection, per-run completeness, partial-retention truth, trip deletion/replay fencing,
  post-candidate full-deletion reconstruction, observable materialization refresh, localization,
  production query, UI, activation, and rollout.

## Exact service-run provider ownership R1 correction (2026-08-26)

### Outcome

Commits `ca5b1ffad` and `47fb0d842` close the next shared lifecycle prerequisite without widening
the Steps product gate. Every provider-side attempt is now bound to an exact logical session,
service run, manifest, lifecycle action, lease generation, and attempt. Run retirement reconstructs
only those exact claims, newest first, and never quiesces another run merely because it uses the
same source family. Service destruction retains stop-delivery ownership and retries cleanup with a
capped backoff until retirement succeeds or the process exits.

This is lifecycle containment, not source rollout. It activates no candidate writer, adds no
materializer or production history reader, starts no provider, changes no sampling cadence, and
authorizes neither ambient collection nor UI wiring. The direct v27→v28 migration remains the
appropriate boundary because v28 has never shipped.

### Evidence

- Two additional fresh correction reviewers independently returned `NO_GO` after finding that a
  cancelled `APPLYING` action could publish provider work and then disappear from retirement. The
  data reviewer subsequently found the adjacent accepted-automatic replay timeline. The third
  fresh correction thread was unavailable under the four-thread ceiling; this extra check is not
  mislabeled as a new three-review round. The required earlier three-perspective corrected R1 is
  retained at TI-B125.
- The correction keeps attempted `APPLYING`, `START_ACCEPTED`, and `CLEANUP_REQUIRED` outcomes live
  until exact retirement; a latest exact `STOP_ACCEPTED` suppresses its historical predecessor.
  Successful whole-run retirement terminalizes older unresolved ownership rows. Both correction
  reviewers returned `GO` for their respective data and Android/service scopes after inspection;
  these were same-reviewer correction verifications, not fresh certifications.
- Focused coordinator/service-session verification: `BUILD SUCCESSFUL in 1m 21s`; `71/71`, zero
  failures, errors, or skips. It covers cancellation after provider publication, incomplete
  removal and retry, newest reconfigure claim selection, `APPLYING` and `START_ACCEPTED` automatic
  replay, and full-stop routing after service coroutine cancellation.
- Source runtime/broker/registration shard: `BUILD SUCCESSFUL in 1m 1s`. Finalizer and shutdown
  shard: `BUILD SUCCESSFUL in 37s`. One earlier oversized combined shard ended with a Gradle
  test-results `EOFException` and no assertion report; both split shards and the later complete
  suite passed, so it is recorded as a tooling failure rather than hidden or called a regression.
- Complete tracker-engine suite: `BUILD SUCCESSFUL in 6m 52s`; `1,640/1,640`, zero failures,
  errors, or skips. Complete core database and tracker API suites: `870/870` and `65/65`, zero
  failures, errors, or skips.
- Exact populated v27→v28 migration: `8/8` on `Medium_Phone(AVD) - 16`, `BUILD SUCCESSFUL in 34s`.
  Debug APK assembly: `BUILD SUCCESSFUL in 1m 9s`. Post-commit `checkRoomSchemaDrift`:
  `BUILD SUCCESSFUL in 19s`.

### Gate status

- Current phase: TI-410 manual/session Steps, `IN_PROGRESS`.
- Passed: immutable service-run manifest integrity; exact provider claim/ack membership across all
  six runtimes; strict provider-removal/flush/app-drain retirement evidence; cancellation-safe
  start/reconfigure ownership; stale automatic replay fencing; run-scoped recovery/finalizers;
  persistent teardown delivery; unreleased-v28 migration and schema agreement.
- Still blocked: Steps writer cutover/rollback, segment-effective provenance, per-run materialized
  completeness, typed query state, portable typed export/import, deletion replay proof, existing
  product consumer truth, device/provider validation, automatic Steps, ambient Steps, every other
  source vertical, broad `DayOverview`, UI activation, and rollout.

### Next wave

Return directly to TI-410. Implement segment-effective Steps writer provenance and service-run
materialization completeness, then the narrow legacy/candidate cutover and rollback protocol.
Only after deletion/no-resurrection and a truthful production Steps query pass should existing UI
consumers be wired. Do not add another shared lifecycle layer or generic materializer platform.

## Queued Steps writer-provenance checkpoint (2026-08-26)

### Outcome

Commit `30051416d` closes the queued-command half of the Steps one-writer fence without activating
the candidate writer. A Steps-bearing pending command now freezes the exact owner and ABA
generation from its source event's immutable manifest revision before the command becomes durable.
The legacy `StepInterval` writer reloads the permanent destination owner in the same Room
transaction as its write. Missing authority is retryable and retains the WAL row; stale legacy or
candidate ownership suppresses only the Steps destination; an unstamped Steps command moves to a
source-local durable quarantine while sibling Location or other destinations still commit.

The same correction preserves the original session ID and resume state when a policy-tier change
starts a previously inactive processor. Quarantine retains the provider acquisition clock and is
pruned by raw-evidence age, not by a newer WAL/quarantine write time. The direct v27→v28 migration
stamps released pending commands as legacy generation 1 and treats legacy quarantine acquisition
time as unknown/expired. No provider, timer, poller, wakeup, generic writer platform, product query,
or UI surface was added.

### Evidence

- Focused writer-fence/recovery gate: `48/48`, zero failures/errors/skips.
- Complete serialized suites and app build: tracker-engine `1,649/1,649`, core database
  `872/872`, app `654/654`, all zero failures/errors/skips; `:app:assembleDebug`
  `BUILD SUCCESSFUL` in the same `9m 17s` run.
- Exact populated v27→v28 migration: `8/8` on `Medium_Phone(AVD) - 16`.
- Post-commit `checkRoomSchemaDrift`: `BUILD SUCCESSFUL in 20s`; 46 tasks.
- Three independent focused reviews covered exact-manifest selection, quarantine privacy/retention,
  and the complete writer-fence diff. They found and drove corrections for policy-tier session ID
  loss, missing-owner silent acknowledgement, overclaimed malformed-row recovery, acquisition-time
  retention, and valid migrated Steps replay. The final diff review found no remaining
  `BLOCKER`/`HIGH` in this scoped checkpoint.

### Gate status

- Current phase: TI-410 manual/session Steps, `IN_PROGRESS`.
- Passed: event-exact immutable writer provenance; ambiguous retry preserves the already-durable
  generation; control-only and consent-mismatched events cannot acquire writer provenance; missing
  writer authority cannot consume a valid pending command; valid migrated legacy Steps replay once;
  stale/candidate commands cannot invoke the legacy writer; mixed-source persistence is
  source-local; quarantine follows raw retention and full deletion; processor tier transitions
  retain session identity.
- Still blocked: atomic activation/cutover/rollback and its process-death latch matrix; historical
  segment-effective product selection; run-scoped materialization completeness; deletion re-arm;
  typed portable export/import; truthful production query and existing-product consumers;
  device/provider validation; automatic Steps; ambient Steps; every other source vertical;
  `DayOverview`, broader UI, and rollout.

### Risks and next wave

Logical deletion is covered, but a raw SQLite backup may still contain deleted bytes in free pages;
byte-level backup erasure (`secure_delete`/compaction behavior in the bundled runtime) remains a
separate privacy verification item. It does not justify expanding this Steps writer commit.

Next, add service-run materialization completeness and the segment-effective Steps history selector,
then implement the narrow cutover coordinator. The coordinator must latch an in-flight legacy write,
switch the owner generation transactionally, survive process death/rollback, reconstruct a coherent
post-deletion generation, and prove continuous query/export/deletion visibility. Production query
and UI wiring remain prohibited until those assertions pass.

## Segment-effective Steps history checkpoint (2026-08-27)

### Outcome

Commit `b20e1efaa` implements the dormant historical-read half of the manual/session Steps vertical.
One internal selector resolves a `SessionSegment` from its exact logical session, service run,
checksum-valid manifest revisions, immutable writer binding, exact active or retired product lane,
run-scoped acquisition completeness, materializer cursor, retained-data floor, and collected-data
epoch. It never reads the current destination owner to reinterpret old history. No schema, provider,
timer, poller, rollout binding, export adapter, production repository, or UI surface was added.

The result keeps availability, evidence, materialization, and coverage orthogonal. Disabled capture,
baseline-only evidence, covered zero, positive lower bounds, partial coverage, deletion, retention
loss, materializing, degraded legacy evidence, and failed invariants cannot collapse into an
invented zero. A legacy positive value remains visible but explicitly replay-unverified; legacy zero
and unavailable historical input are never promoted to verified data.

### Evidence

- Three independent focused read-only audits covered historical writer selection, the smallest
  run-boundary cutover design, and downstream consumer truth. They converged on immutable manifests
  plus `SessionSegment.serviceRunId`, no new table or generic `DayOverview` platform, no mid-run
  writer switch, and no production consumer until all numeric consumers can preserve completeness.
- Data adversaries found and drove corrections for retention-floor filtering, reattribution across
  service runs, redacted retractions, stale deletion epochs, overflow, and retired-lane liveness. A
  fresh final adversary then found older-scope resurrection after retention and malformed lane
  lifecycle acceptance; both were corrected before integration. No scoped `BLOCKER` or `HIGH`
  remains in the selector checkpoint.
- Focused Room/selector verification: `33/33` — `StepFactRevisionDaoTest` `9/9`,
  `SourceProjectionStateExactCursorTest` `3/3`, and `StepsSegmentHistorySelectorTest` `21/21`.
- Complete affected regression plus debug graph: core database `876/876`, stats data `139/139`,
  tracker engine `1,649/1,649`, all zero failures/errors/skips, and `:app:assembleDebug`;
  `BUILD SUCCESSFUL in 8m 46s`, 720 tasks.
- Post-commit `checkRoomSchemaDrift`: `BUILD SUCCESSFUL in 30s`, 46 tasks; `28.json` unchanged.

### Gate status

- Current phase: TI-410 manual/session Steps, `IN_PROGRESS`.
- Passed: immutable historical writer selection; run-scoped completeness and exact lane progress;
  current-owner independence; correction/reattribution isolation; retained-floor partial truth;
  redacted deletion and collected-data-epoch fencing; retired-lane evidence; overflow and malformed
  lifecycle fail-closed behavior; no new acquisition or battery work.
- Still blocked: atomic run-boundary activation/cutover/rollback; process-death interleaves;
  post-deletion generation reconstruction; typed portable export/import; an observable production
  query and every numeric consumer; device/provider proof; automatic and ambient Steps; all other
  source verticals; broader day product/UI; rollout.

### Next wave

Implement the narrow Steps cutover coordinator. It must refuse an active service run, fence and
drain the exact legacy generation, install the candidate lane and replacement owner atomically,
contain rollback without activating two writers, and re-arm a coherent empty generation after full
deletion. Only after its crash/interleave matrix passes may the typed selector become observable
through the existing production Trip Detail/query boundary.

## Reconciled atomic Steps transition checkpoint (2026-08-27)

### Outcome

Merge `9b8ab4b43e0d5ca2e729f511b48936b0dbdfbb6a` joins the reconciled remote/local tree with the
source-specific Steps activation, rollback, and full-deletion re-arm foundation. Candidate owner
authority is checked by rollout state and durable admission. The transition remains contained and
`IN_REVIEW`: legacy generation 1 is still the active production owner, and no production history/UI
consumer or first-activation caller was added. See `CONTINUATION_HANDOVER.md` for the graph and
continuation commands.

### Evidence and review

- Database/DAO review found no remaining `BLOCKER`, `HIGH`, or `MEDIUM` and verified atomic
  activation, rollback, deletion re-arm, run boundaries, and receipt/cursor behavior. Its `LOW`
  pending-signal half-pair finding was mitigated with a raw malformed-row test.
- Lifecycle review's `HIGH` owner-drift timeline was mitigated with transaction-local destination
  owner checks in rollout authorization and WAL admission.
- App review's `MEDIUM` residual legacy Steps row was mitigated with a deletion guard and focused
  re-arm test. One `MEDIUM` end-to-end deletion-service/re-arm integration test remains.
- The exact merge passes the authoritative serialized `ciCheck --continue`: `BUILD SUCCESSFUL in
  21m 46s`; 1,991 actionable tasks, 224 executed and 1,767 up-to-date.

### Gate status and next wave

- Passed locally: contained transition transactions, destination-owner admission authority,
  rollback/deletion generation fencing, complete repository host quality gate.
- Still blocked: production Steps query and all completeness-sensitive consumers, typed portable
  export/import, full deletion-service seam, provider/device/process/reboot/energy evidence,
  automatic/ambient Steps, other source verticals, UI activation, and rollout.

## Connected deletion/re-arm integration checkpoint (2026-08-29)

### Outcome

Local commit `3b23655472316d953b7d9e5fae20e17cb1841c5c` adds a narrow production Hilt entry point and a
targeted Android integration test for the same-process collected-data deletion retry. The test uses
the real default `AppDatabase`, lifecycle store, startup/deletion barrier and gate, and
`StepsSessionFactWriterTransitionCoordinator`. It drives the production deletion provider with
controlled peripheral collaborators, forces post-rearm diagnostics to fail, proves the durable
marker and barrier stay closed, then proves retry repeats deletion, advances fresh privacy/owner/
rollout generations, reconstructs an empty manual-capture-authorized Steps lane at the retained WAL
high-water, publishes the matching startup generation, and returns startup to Ready.

### Evidence and R1 disposition

- The final source tree passes the exact targeted `:app:connectedDebugAndroidTest` class twice
  consecutively without clearing the installed app on `Medium_Phone(AVD) - 16` / API 36. Both runs
  execute `1/1` test successfully; the latest XML reports zero failures/errors/skips and a `2.535s`
  instrumented body.
- Seven complementary `:tracker:engine` suites covering deletion re-arm, writer transitions, stale
  ingress, stale WAL recovery, projection fencing, high-water rebasing, and pipeline recovery pass:
  `BUILD SUCCESSFUL in 3m 11s`; 234 actionable tasks.
- `CollectedDataDeletionServiceTest` passes: `BUILD SUCCESSFUL in 52s`; 575 actionable tasks. Root
  Detekt passes after decomposing the scenario and fixture helpers without a suppression:
  `BUILD SUCCESSFUL in 33s`; 5 actionable tasks.
- The branch was already up to date when rebased onto local `dev/v10`. On that exact post-rebase tree,
  the targeted connected test passes again in `48s` (`1/1`; 714 actionable tasks) and root Detekt
  passes in `18s` (5 up-to-date tasks).
- Three fresh `gpt-5.6-sol` high-reasoning reviewers inspected the diff. Two Android `HIGH` findings
  were mitigated: setup no longer erases a real pending-deletion marker/reopens the barrier, and an
  already-canonical retained writer is now a valid rerun baseline. The data review's stale-work
  resurrection challenge is covered by the seven focused engine suites rather than duplicated in
  the app seam. Cold-process restart remains an explicit unverified boundary.
- A test-local Hilt entry point was tried before the production contract and failed with
  `ClassCastException` in both host and device execution. The retained production entry point is
  therefore limited to the two singleton authorities instrumentation must reach. The test mutates
  the installed debug database and is evidence only as the exact targeted class on a disposable AVD;
  it is not a full connected-suite ordering result.

### Gate status and next wave

- Passed locally: the production deletion-service -> database -> closed barrier -> empty Steps
  re-arm seam under a same-process diagnostics failure/retry, plus complementary stale-work and
  high-water host contracts.
- Still blocked: cold process/reboot/provider interleaves, actual post-rearm provider capture,
  production Steps consumer/`QUERYABLE` proof and all completeness-sensitive consumers, typed
  portable export/import and continuous deletion/no-resurrection visibility, ordinary activation/release action,
  automatic/ambient Steps, other sources, UI activation, device power/OEM evidence, and rollout.
- The previously named next slice—exposing the immutable selector without changing writer ownership
  or adding a generic day platform—is completed by the checkpoint below.

## Observable Steps session-history checkpoint (2026-08-30)

### Outcome

Commit `c5118e186` completes the next bounded TI-410 slice. `:stats:api` now owns a read-only
`TrackingHistoryRepository` contract for one selected local `SessionSegment`; `:stats:data` maps the
existing immutable selector into that contract and binds it through Hilt. The Room observer watches
the exact ten tables used by selection and resolves the segment plus its dependent evidence in one
transaction. Observation cannot start a provider, change a writer, repair a projection, or write a
summary.

Availability, acquisition evidence, product readiness, coverage, and stable causes remain separate.
Only explicit disabled policy across every referenced revision proves `DISABLED`; inconsistent or
missing retained evidence is `UNAVAILABLE`. Baseline-only remains null/`NONE`, a covered zero is
`ACTIVE`, a positive delta is `RECORDED`, a lane-behind value is a partial lower bound, and unknown
legacy coverage is neither complete nor a lower bound. No existing Today, Timeline, Calendar,
session detail, goal, streak, achievement, widget, notification, export, or deletion consumer has
been rewired, so the program still makes no `QUERYABLE` or product-visible claim.

### Evidence and review

- Three independent focused reviews attacked API/product truth, Room snapshot/performance behavior,
  and app-scope proportionality. Their blocking/high findings were mitigated: missing capture intent
  no longer implies disabled, legacy unknown coverage is not a lower bound, completeness no longer
  depends on later availability, baseline is not active evidence, and candidate lag cannot claim
  complete coverage. The final reviewer additionally required explicit disabled policy and rejected
  zero plus `RECORDED`; both corrections have direct tests.
- The Room/performance review found no `BLOCKER` or `HIGH`. Its remaining `MEDIUM` fan-out risk is
  accepted only for this documented one-selected-session API; add batching/shared composition before
  any list/day surface. A final read-only diff audit found no `BLOCKER`, `HIGH`, or `MEDIUM`; its one
  indentation cleanup was applied before the final focused gate.
- Exact committed-tree focused verification passes: `:stats:api:allTests
  :stats:data:testDebugUnitTest --no-parallel`, `BUILD SUCCESSFUL in 49s`; 219 actionable tasks,
  5 executed, 1 from cache, 213 up-to-date.
- The behavior-complete tree passed `ciUnitTest --no-parallel` in `12m 18s`; 990 actionable tasks,
  6 executed and 984 up-to-date. The only later code change was indentation normalization.
- `:stats:data:lintDebug :app:hiltJavaCompileDebug --no-parallel` passed in `40s`; 569 actionable
  tasks, with no new lint issue and only baseline-filtered findings. Root Detekt passed in `30s`.
- The exact committed code checkpoint passes `ciCheck --continue --no-parallel`: `BUILD SUCCESSFUL
  in 8m 32s`; 1,991 actionable tasks, 662 executed, 297 from cache, and 1,032 up-to-date.

### Gate status and next wave

- Passed: typed selected-session observation; one-transaction historical snapshot; policy and lane
  invalidation; segment deletion to `NotFound`; explicit disabled/unavailable distinction; truthful
  zero/baseline/positive/partial/legacy mapping; Hilt graph compilation; no acquisition, writer,
  schema, cadence, network, or rollout expansion.
- Still blocked: any product consumer and therefore `QUERYABLE`; selected-session deletion of all
  scoped candidate facts; day/list composition without per-row observers; every numeric consumer;
  typed portable export/import and continuous deletion/replay/no-resurrection visibility; manual
  only-Steps provider/device proof; ordinary activation; automatic/ambient Steps; other sources;
  UI, device power/OEM evidence, and rollout.
- Next: close the scoped deletion/consumer boundary before wiring selected session detail, then
  propagate the contract through completeness-sensitive consumers. Do not instantiate one observer
  per history row or add a generic day platform. Typed export/import may proceed as a separate
  non-overlapping source-local slice.

## Dormant source-deletion fence checkpoint (2026-08-30)

### Outcome

Commit `03bbda2f1` adds the minimum persisted source/purpose/logical-run deletion authority to the
unreleased v28 schema and makes the dormant candidate Steps writer plus the one-selected-session
history facade honor it. It does not wire the production trip-delete action and does not activate a
writer. Legacy Steps generation 1 remains the only production destination owner.

The fence retains no logical or service-run identifier: mutation/read paths derive the same
domain-separated SHA-256 scope digest from immutable attribution. A matching fence makes a current
candidate WAL event a validated no-effect, permits a matching old terminal projection failure to be
released on the next drain, and makes selected history report a named deleted cause without
removing the segment. Full collected-data deletion clears the fence table in its existing database
transaction.

### Evidence

- Three fresh `gpt-5.6-sol` R1 adversaries returned `NO_GO` for the first production-wired draft.
  Their shared blocking/high timelines were active legacy Steps bypass, teardown resurrection,
  unchecked active/`STOPPING` UI failure, stale `daily_summary`, and misleading permanent-deletion
  scope. The production `DefaultTripRepository` delegation and presentation-owned retraction writer
  were withdrawn before commit. Retention, import/future-writer, source-wide deletion, and automatic
  cursor wake remain explicitly open rather than hidden behind local tests.
- Main Kotlin compile passed for `:core:base`, `:tracker:engine`, and `:stats:data` in `2m 17s`
  (136 tasks). Isolated focused XML is green: fence DAO `3/3`, Steps fact DAO `9/9`, candidate lane
  `14/14`, and selected-session selector/observer `27/27`, with zero failures/errors/skips.
- `detekt :app:hiltJavaCompileDebug` passed in `2m 38s` (366 tasks). The app/Hilt graph therefore
  resolves the 69-entity v28 database; unchanged TestDataSeeder type and Moshi Kapt deprecation
  warnings remain non-blocking baseline output.
- `:core:base:compileDebugAndroidTestKotlin` completed successfully. The same pre-commit invocation
  then failed only because `checkRoomSchemaDrift` correctly detected the intentionally uncommitted
  regenerated `28.json`. After `03bbda2f1`, the exact schema guard passed in `22s` (46 tasks).
- One oversized multi-module focused run ended in Gradle test-results `EOFException`; an immediate
  forced rerun hit a Windows lock held on `classes.jar`. After stopping the two stale daemons, every
  affected class passed in isolated serialized runs. No assertion failure is attributed to that
  environment event.

### Gate status

- Passed: v28 entity/migration/schema parity; opaque exact-key fence integrity; full-delete cleanup;
  candidate pre-write rejection; stale terminal-failure recognition on a later drain; observable
  selected-session fence invalidation; app graph and static analysis.
- Failed or unverified: a production fence producer; active legacy `StepInterval` removal/replay;
  exact segment ownership and presentation acknowledgement; typed delete/unsupported UX;
  documented-zone day repair; distinct Steps retention/import/export, generic merge containment,
  future writer/source checks; post-commit drain integration; connected migration execution for this
  schema revision; process/reboot/provider/device/OEM evidence; `QUERYABLE`, UI, activation, or
  rollout.

### Next wave

1. Keep the contained selected Trip Detail Steps read intact, then define an exact historical
   capture-set and qualifying-Location contract before adapting its Location-shaped sections. Build
   the live Steps surface against the same read authority. Missing Steps is not zero; Steps-only
   promotes Steps/duration and hides Location-specific map, route, speed, accuracy, and coordinate
   controls. Define typed delete UX without enabling mutation.
2. Persist exact `(logicalTrackingId, serviceRunId, sessionSegmentId)` ownership, choose durable
   post-presentation acknowledgement for source-neutral session/Ski writers, and relocate graceful
   empty-row cleanup behind it. Previous-exit crash-orphan reclamation remains a later bounded slice.
3. Add exact manifest/run/purpose/epoch attribution for newly admitted v28 `SESSION_CAPTURE` Steps
   and monotonic fence checks only to legacy, candidate, and portable-import Steps writes. Migrated
   unverifiable rows remain a typed unsupported state; never infer ownership by wall-time overlap.
4. Add a data-plane `DeleteSelectedSession` contract with `Deleted`, accepted `NotFound`,
   `BlockedActive`, `LegacyUnverifiable`/`UnsupportedScope`, and retryable failure. Only the first two
   dismiss the row. Define the current summary's zone authority before cross-midnight/day repair, and
   issue the existing post-commit conflated drain hint; startup recovery covers a crash before it.
5. Wire the contained historical/live consumers and immediately prove the exact manual Steps-only
   capture/registration set `{Steps}` with no other source, control, or ambient demand, listener
   removal after stop, no polling/scheduled recovery, and truthful
   `RECORDING -> MATERIALIZED -> QUERYABLE`. Keep production activation blocked.
6. Add distinct cutoff retention, portable import, and read-only export services over the narrow
   Steps evaluator; preserve batching and module direction. Do not route them through the
   selected-row command or raw-merge `daily_summary`.
7. Audit numeric consumers and add measured shared/batched history composition only before list/day
   fan-out. Then run full host, connected migration, provider, process, and device gates.

## Empty-session cleanup containment checkpoint (2026-08-30)

### Outcome

Commit `88309387d` retires the unsafe six-hour `sample_count = 0` database deletion. The historical
`DatabaseMaintenanceWorker` class and unique name remain so an already-persisted WorkSpec can load,
but the worker is an inert success. UI maintenance startup requests asynchronous cancellation;
collected-data deletion awaits cancellation and never restores the work. A persisted periodic
request may still wake the inert shell until cancellation completes. Commit `88309387d` adds
positive-sample predicates to daily/live plus source/all-time activity reads; `24b9aeffb` adds them
to app-age/hour/night/dawn and ActivityRecognition reads. Every zero-sample row, including the tested
identity-stamped crash fixture and matching legacy/imported rows, is excluded from those named reads;
other DAO reads remain unchanged.

This is safety containment, not orphan reclamation. A source run becomes terminal before tracking
presentation components finish shutdown, so no predicate over current lifecycle state can prove an
empty row abandoned. A crash may still strand one physical placeholder indefinitely because the
durable descriptor is not yet updated with its generated segment ID and retention is optional.

### Evidence and review

- Original focused Room verification at `88309387d` passes `11/11` in `1m 13s`. The corrected
  `24b9aeffb` suite passes `12/12`, zero failures/errors/skips, in `1m 5s`; 79 actionable tasks.
- Focused app verification passes: worker, deletion-quiescer, and complete architectural-fitness
  tests, `BUILD SUCCESSFUL in 1m 9s`; 575 actionable tasks. XML is worker `3/3`, quiescer `3/3`,
  and 37 nested architecture assertions, all with zero failures/errors/skips.
- Root Detekt passes at the corrected tip: `BUILD SUCCESSFUL in 39s`; 5 actionable tasks. Its first
  follow-up run found five missing public DAO contracts; those were documented before commit. App
  production/test Kotlin, Hilt, and the simplified assisted worker constructor compiled in the
  focused app gate.
- The first combined verification attempt collided with an independently running shared-worktree
  Gradle test process and ended in a test-results `EOFException`; it produced no trustworthy
  assertion result and is not counted. Serialized reruns above passed.
- Lifecycle and cleanup reviewers independently falsified terminal-state-only SQL and required
  retirement. A fresh evidence audit then found the app-age/hour/night/dawn/ActivityRecognition gap;
  `24b9aeffb` corrects those reads. A final falsifier corrected the asynchronous UI-cancellation and
  affected-query-wide zero-sample wording. An independent scope reviewer and document falsifier
  found four `HIGH` and three `MEDIUM` next-wave issues, all dispositioned in TI-D111.

### Gate status and next wave

- Passed: no periodic/global zero-row database mutation; persisted worker compatibility; UI
  maintenance cancellation request plus deletion-time awaited cancellation/no reschedule;
  identity-stamped placeholder preservation; all zero-sample rows absent from the named
  product/ActivityRecognition reads; unchanged DAO paths explicitly outside the claim; static worker
  fencing.
- Failed or unverified: durable segment-ID handoff, exact presentation-writer acknowledgement,
  process-death orphan reclamation, storage cleanup, selected-trip deletion, device execution, and
  full `ciUnitTest`/`ciCheck` at this code checkpoint.
- Next: first define contained source-adaptive live/history states and typed unsupported UX. Then
  preserve exact segment identity, validate it on resume, and add lifecycle-owned acknowledgement
  after cycle drain, final segment decision, pipeline stop, Ski flush/join, and shutdown
  materialization. Relocate graceful cleanup behind that boundary. Previous-exit orphan cleanup is
  a separate later slice, not a prerequisite for the first honest manual Steps product proof.

## Contained Trip Detail Steps consumer checkpoint (2026-08-30)

### Outcome

Commit `f9b1c3c45` makes the existing selected Trip Detail the first production consumer of
`TrackingHistoryRepository.observeSession(segmentId)`. It replaces the hardware/current-device
guess and legacy summary coercion for the Steps metric with typed durable states: complete covered
zero or positive count, partial lower bound, legacy-unverified, materializing, not captured,
disabled, unsupported, permission-required, OS-limited, unavailable, no observation, deleted, and
failed. A history delay shows the trip immediately with Steps materializing; history failure is
isolated to Steps, keeps unrelated trip/Location content, and offers an explicit selected-history
reread that does not activate materialization.

The observer uses `WhileSubscribed` and lifecycle-aware Compose collection. Optional Location/Ski
reads start only while the selected screen is composed, and repeated requests cancel the previous
job. This slice starts no provider, changes no acquisition demand or writer, and observes one row
rather than fanning out over a list.

Two adversarial blockers were removed rather than hidden: `SessionSegment.sampleCount` is a generic
collection-cycle counter in the current runtime and is not used as Location evidence; Trip Detail's
presentation-only delete action was removed because it could leave append-only Steps facts behind.
Other History/Stats delete paths still delegate to presentation-row deletion and remain blocked.

### Evidence

- Four focused suites pass `33/33` with zero failures/errors/skips: history mapping `10/10`,
  presenter `9/9`, ViewModel `6/6`, and Compose `8/8`; Gradle completed in `36s` with 194 tasks.
- Root `detekt` passed in `18s`. `:feature:statistics:lintDebug` passed in `32s` with 338 tasks and no
  new issue; the existing module baseline still filters 137 errors and 32 warnings.
- The product adversary's commit-local `HIGH` findings (whole-screen source failure and loss of a
  surviving partial lower bound) were mitigated. Its misleading-icon/`sampleCount` inference was
  withdrawn; English-only fallback copy remains an explicit `MEDIUM` localization gap.
- The Android adversary's commit-local `HIGH` findings (off-screen observation and whole-screen
  failure) were mitigated. Lifecycle collection, disabled precedence, and duplicate supplemental
  reads were corrected.
- The validation adversary's two commit-local `BLOCKER` findings were mitigated by removing the
  presentation-only delete and every new `sampleCount`-as-Location branch. Its separate `HIGH`
  finding remains accepted: ordinary list queries require `sample_count > 0`, so source-only
  discoverability and `QUERYABLE` stay blocked.
- The fresh corrected-diff reviewer required explicit retry, UI-owned cancellable optional reads,
  `NotCaptured` mapping, and immediate materializing content; all were corrected before
  `f9b1c3c45`. Thus every commit-local `BLOCKER`/`HIGH` is mitigated, not every program-level gate.

### Gate status and next wave

- Passed: one lifecycle-scoped selected-session production read; correction updates; covered-zero
  versus missing truth; partial/lower-bound and legacy truth; history failure isolated to Steps plus
  explicit user-triggered reread; no direct Trip Detail delete; no new `sampleCount` inference; no
  provider/list fan-out.
- Failed or unverified: ordinary discovery of source-only sessions, exact capture-set and qualifying-
  Location evidence, source-adaptive Location/map/export hiding, live Dashboard consumption,
  list/day batching, every other numeric consumer, safe app-wide selected deletion, localization,
  `ciCheck`, emulator/device/visual/accessibility/provider/energy evidence, and
  `QUERYABLE`.
- Next: exact segment ownership and post-presentation acknowledgement are now in `9aeb8853a` and the
  current `ciUnitTest` passes. Require the new-v28 Steps reverse binding, logical-entry grouping, and
  an exact historical capture-set/Location-evidence contract before adapting Trip Detail or live UI;
  never use `sampleCount` for that decision. Then prove the exact `{Steps}` manual path through
  ordinary production navigation before changing activation state.

## Exact service-run presentation settlement checkpoint (2026-08-31)

### Outcome

Commit `9aeb8853a` closes the exact run-to-presentation ownership and post-writer acknowledgement
slice without restoring cleanup. `source_service_run` now owns a nullable unique
`session_segment_id` plus `PENDING`, `QUIESCED`, or `LEGACY_UNVERIFIABLE` settlement evidence.
Opening a session creates and binds the segment in one Room transaction; a same-run retry resumes
only that binding, while a replacement service run creates another physical segment under the same
logical tracking ID. The recovery descriptor mirrors the binding through an exact atomic CAS and
cannot overwrite a concurrent stop candidate.

The orchestrator retains an exact shutdown receipt across retry and emits it only after the final
session row, processing pipeline, Ski writer, ancillary components, and summary attempt have
stopped. `TrackerService` acknowledges only an exact completed `FINALIZED` or `FAILED` run before
clearing its descriptor. Missing/mismatched ownership remains `PENDING` and is logged without
tracked payload; nonterminal or transient database failure retries. No physical row is deleted.

### Evidence and adversarial disposition

- The schema reviewer accepted one segment per physical run, the nullable unique reverse index,
  migrated `LEGACY_UNVERIFIABLE`, and both terminal run states. Its request to drop an unused
  elapsed acknowledgement timestamp was accepted.
- The lifecycle reviewer found and the implementation mitigated descriptor overwrite ordering,
  stale tier writes, shutdown-receipt loss, one-shot Ski failure semantics, and infinite retry on
  permanent ownership corruption. Its proposed extra `NEEDS_SOURCE_EVIDENCE` column was deferred:
  no evaluator or consumer exists, and `QUIESCED` is explicitly forbidden from implying retention,
  materialization, emptiness, or deletion.
- The product/ownership review preserved the `HIGH` product limitation: replacement runs now create
  multiple physical Trip rows for one logical entry. Day/history grouping and exact Steps selector
  reverse-binding remain required before a logical-entry or `QUERYABLE` claim.
- Focused engine verification passes in `4m 20s` with 230 tasks. Focused core DAO and tracker API
  tests pass; migration Android-test Kotlin compiles. Root Detekt passes in `13s`. Affected-module
  lint passes in `2m 19s` with 383 tasks and no new finding. Post-commit Room drift passes in `8s`
  with 46 tasks. The first full host aggregate correctly found one obsolete assertion that expected
  zero-cycle rows to be deleted; `d067704a9` makes the conservative retention contract explicit.
  Its focused class passes in `2m 7s` (234 tasks), and `ciUnitTest --no-parallel` then passes in
  `7m 2s` (990 tasks). Connected migration execution is unavailable because no device is attached.

### Gate status and next wave

- Passed: exact Room binding; same-run idempotence; replacement-run separation; descriptor CAS;
  terminal/idempotent acknowledgement; receipt retry; final-row retention; no direct empty-row
  deletion; Wi-Fi/Cell-compatible conservative preservation; v28 schema parity.
- Failed or unverified: an all-six-source qualified-evidence evaluator, graceful or previous-exit
  reclamation, new-v28 Steps selector reverse binding, logical-entry product grouping, ordinary
  source-only discovery, selected deletion, materialization, `QUERYABLE`, connected migration,
  process/reboot/provider/device/energy behavior, and rollout.
- Next: require exact reverse binding in the new-v28 Steps history selector, add source-aware
  ordinary discovery and logical-entry composition without per-row observers, then implement the
  source-local Steps attribution/fence and typed deletion boundary. Do not turn `QUIESCED` into a
  deletion predicate or add a generic tombstone platform.

## Exact Steps history reverse-binding checkpoint (2026-08-31)

### Outcome

Commit `f2c7a3225` requires a selected new-v28 Steps segment to be the exact presentation row owned
by its physical service run. Null or foreign reverse bindings fail closed before any deletion-fence,
manifest, lane, completeness, or fact selection. Migrated `LEGACY_UNVERIFIABLE` ownership remains a
distinct typed unavailable result.

This changes only the contained selected-session read contract. It adds no schema, provider demand,
writer activation, cleanup, deletion, or product grouping.

### Evidence

- `./gradlew.bat :stats:data:testDebugUnitTest --tests '*StepsSegmentHistorySelectorTest'
  --tests '*TrackingHistoryMappingTest' --no-daemon --no-parallel --max-workers=1
  '-Pksp.incremental=false' --console=plain --no-configuration-cache` passes in `43s` with 209 tasks;
  selector `30/30` and mapping `8/8` pass with zero failures/errors/skips.
- `./gradlew.bat :stats:api:allTests :stats:data:testDebugUnitTest --no-daemon --no-parallel
  --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache` passes in
  `46s` with 219 tasks. Stats data is `156/156`; stats API JVM and Android host are each `308/308`.
- `./gradlew.bat detekt :stats:data:lintDebug checkRoomSchemaDrift --no-daemon --no-parallel
  --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache` passes in
  `1m 18s` with 345 tasks. Lint reports no new issue and the committed v28 schema is unchanged.
- Two independent final read-only reviews report no remaining scoped `BLOCKER`, `HIGH`, or `MEDIUM`.
  The review-requested deletion-fence and missing-manifest precedence assertions pass.
- No emulator/device/provider/process/reboot/energy evidence was produced.

### Gate status and next wave

- Passed: exact, null, and foreign reverse-binding behavior; migrated legacy typed failure;
  exhaustive public cause mapping; unchanged schema/provider/writer ownership.
- Failed or unverified: exact capture-set and qualified-source evidence, ordinary source-only
  discovery, logical-entry grouping, batched list/day composition, live UI, selected deletion,
  `QUERYABLE`, device/provider/process/energy proof, activation, and rollout.
- Next: add exact historical capture-set and qualified-source evidence, then logical-entry grouping
  and shared/batched discovery. Do not infer source qualification from `sampleCount`.

## Exact Steps capture and qualified-history evidence checkpoint (2026-08-31)

### Outcome

Commit `c1d6a4a62` adds a bounded read-only history selector for exact historical capture intent and
qualified source evidence. It reads checksum-valid revisioned manifests using the persisted
`SESSION_CAPTURE`/`CONTROL` purpose vocabulary, keeps control membership separate, and fails closed
on unknown purpose or source values. Steps qualifies only from exact captured intent plus current,
non-deleted, epoch-valid covered or recorded Steps evidence. Retractions, source-local fences,
terminal failures, unavailable prefixes, and correction reattribution remain typed.

`SessionSegment.sampleCount` is now documented and enforced as a source-neutral processing-cycle
count. Compatibility visibility is limited to null/null legacy/imported rows or durable
`LEGACY_UNVERIFIABLE` runs, and it qualifies no source. A zero-sample Steps run can therefore be
found from real Steps evidence, while a positive sample count cannot fabricate Steps, Location, or
capture intent.

Candidate traversal uses `(startTimeMs, id)` keyset pagination in 64-row physical batches and keeps
loading after rejected batches until the requested accepted-result limit is met or candidates end.
Run, manifest, source-policy, lane, fence, failure, and fact state are loaded in fixed-count batch
queries inside one Room transaction. Latest semantic corrections are resolved globally and retain
their exact physical-run attribution. No entity, table, migration, or committed `28.json` changed.
The selector still has no production list caller, so this checkpoint does not establish ordinary
navigation or `QUERYABLE`.

### Evidence

- `./gradlew.bat :stats:data:testDebugUnitTest --tests
  "com.adsamcik.tracker.stats.data.repository.StepsSegmentHistorySelectorTest" --no-daemon
  --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain
  --no-configuration-cache` passes in `1m 12s` with 209 tasks; selector `42/42` passes with zero
  failures/errors/skips.
- `./gradlew.bat :core:base:testDebugUnitTest :stats:api:allTests
  :stats:data:testDebugUnitTest --no-daemon --no-parallel --max-workers=1
  '-Pksp.incremental=false' --console=plain --no-configuration-cache` passes in `1m 26s` with 233
  tasks. Core base is `896/896`, stats data is `168/168`, and stats API JVM and Android host are each
  `308/308`, all with zero failures/errors/skips.
- `./gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false'
  --console=plain --no-configuration-cache` passes in `32s` with 5 tasks after DAO documentation,
  batch-reader extraction, and required-braces corrections. The initial attempted module paths
  `:core:base:detekt :stats:data:detekt` do not exist and failed task selection before analysis; the
  authoritative root task was then used. The first focused compile after extraction exposed one
  removed `ScopedStepFactState` import; restoring that import produced the passing run above.
- `./gradlew.bat :core:base:lintDebug :stats:data:lintDebug checkRoomSchemaDrift --no-daemon
  --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain
  --no-configuration-cache` passes in `2m 16s` with 347 tasks. Both lint tasks report no new issue;
  existing baselines filter core `13` errors/`6` warnings and stats data `283` errors/`270`
  warnings. The committed Room schema is unchanged.
- Three independent corrected-diff reviews and one final post-extraction read review report no
  remaining scoped `BLOCKER` or `HIGH`. They confirm exact purpose handling, control exclusion,
  stale/fenced/retracted no-resurrection, keyset progress, correction-run isolation, and unchanged
  transaction/batch semantics.
- No emulator/device/provider/process/reboot/energy evidence was produced.

### Gate status and next wave

- Passed: exact revisioned capture/control evidence; zero-sample qualified Steps discovery;
  source-neutral sample-count containment; bounded keyset/batch loading; source-local fence, epoch,
  failure, completeness, and correction attribution; unchanged schema and writer ownership.
- Failed or unverified: a production list caller, replacement-run logical grouping, ordinary
  product navigation, shared list/day/live composition, selected deletion, `QUERYABLE`, device or
  provider behavior, activation, and rollout.
- Next: compose replacement-run physical segments under one logical tracking entry before applying
  the consumer limit, retaining exact physical run and segment ownership internally. Then expose the
  smallest truthful Steps-only list/live surface without fabricating zero or enabling unsafe
  presentation-only deletion.

## Logical Steps history composition checkpoint (2026-08-31)

### Outcome

Commit `923bf2025` adds an internal, read-only logical history composition for Steps. Evidence-bearing
members discover an entry identity, all explicitly eligible replacement-run siblings are then loaded
before the consumer limit, and recency comes from the newest eligible physical `(startTimeMs,
segmentId)` tuple. This keeps a newer baseline-only or materializing replacement run attached to and
ordering its logical entry without making that member source-qualified.

Exact new-v28 membership still requires the run's reverse `sessionSegmentId` binding. Migrated
`LEGACY_UNVERIFIABLE` members may share a nonblank logical identity only after matching explicit
forward segment/run identity; each remains typed unavailable and qualifies no source. Null/null
legacy rows remain independent physical compatibility entries, and partial or mismatched identity
is excluded. No membership is inferred from wall-time overlap.

Candidate identities and physical siblings use separate `(startTimeMs, segmentId)` keyset loops with
64-row Room pages inside one transaction. Every physical member retains its exact segment, run,
manifest/capture, deletion-fence, completeness, projection-lane, and Steps state oldest-first. The
composition unions qualified source names only; it deliberately creates no cross-run numeric total.
The new reader remains internal and has no public repository, list, day, live, or UI caller.

### Evidence

- `./gradlew.bat :stats:data:testDebugUnitTest --tests
  "com.adsamcik.tracker.stats.data.repository.StepsSegmentHistorySelectorTest" --no-daemon
  --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain
  --no-configuration-cache` passes in `1m 19s` with 209 tasks; `53/53` pass with zero
  failures/errors/skips.
- `./gradlew.bat :core:base:testDebugUnitTest :stats:data:testDebugUnitTest --no-daemon
  --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain
  --no-configuration-cache` passes in `1m 26s` with 223 tasks. Core base is `896/896` and stats data
  is `179/179`, with zero failures/errors/skips.
- `./gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false'
  --console=plain --no-configuration-cache` passes in `35s` with 5 tasks. Two development runs had
  failed only on the new reader's complexity/required-brace style; the findings were corrected before
  this final gate.
- `./gradlew.bat :core:base:lintDebug :stats:data:lintDebug checkRoomSchemaDrift --no-daemon
  --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain
  --no-configuration-cache` passes in `1m 55s` with 347 tasks. Both lint tasks report no new issue;
  existing baselines filter core `13` errors/`6` warnings and stats data `283` errors/`270` warnings.
  The committed Room schema is unchanged.
- Three final independent reviews report no remaining scoped `BLOCKER`, `HIGH`, or `MEDIUM`. Earlier
  rounds found and the final code corrects a synthetic independent-max cursor, unbounded sibling
  materialization, attributed migrated-row splitting, incomplete 65-member assertions, and recency
  based only on a coarse discovery seed.
- No emulator/device/provider/process/reboot/energy or UI evidence was produced.

### Gate status and next wave

- Passed: grouping before the consumer limit; replacement-run membership; exact reverse-binding and
  typed migrated boundaries; newest-member recency; bounded candidate/sibling paging; per-run
  materializing/ready/deleted truth; no fabricated aggregate zero; unchanged schema and writer
  ownership.
- Failed or unverified: a public production list caller, ordinary navigation, live/day composition,
  selected deletion, numeric aggregation policy, `QUERYABLE`, device/provider behavior, activation,
  and rollout.
- Next: expose the smallest truthful Steps-only list/live surface while preserving contained Trip
  Detail and keeping unavailable, partial, and materializing state distinct from zero. Do not route
  a logical entry to presentation-only deletion or an arbitrary physical detail row.

## Public Steps-only history read checkpoint (2026-08-31)

### Outcome

Commit `90f7e758e` exposes the bounded logical Steps-only history read through the production
`TrackingHistoryRepository`. Selected-session history now carries exact revisioned capture/control
authority plus qualified source names. Public construction enforces that every qualified source was
captured by exact historical authority; migrated or incomplete authority remains `Unverifiable` and
cannot qualify a source. A session is Steps-only only when every retained revision captured exactly
Steps. Separately named control sources do not become captured history.

The recent list filters exact Steps-only entries before applying its `1..100` accepted-result limit,
so a newer mixed-source entry cannot starve an older valid entry. Replacement-run members remain
grouped by the existing logical reader. The public row contains only a non-inspectable equality key,
the physical-membership time envelope, and `AVAILABLE`, `MATERIALIZING`, or `PARTIAL`; it exposes no
cross-run number and no segment identity usable for detail, map, export, or deletion.

An initially proposed physical-Trip suppression set was withdrawn during adversarial review. A
suppression set derived from an independently limited logical list cannot safely cover a separately
paged Trip consumer. Any action-bearing list must instead add one bounded composition against that
consumer's actual physical candidate window. No speculative global alias or suppression API remains.

The Room observer shares the selected-history invalidation tables, cancels stale reads with
`mapLatest`, runs on the injected I/O dispatcher, and reacts to source-local fence changes. This
checkpoint adds no provider demand, writer, schema, migration, numeric aggregate, UI, navigation,
deletion, export, activation, or rollout behavior.

### Evidence

- `./gradlew.bat :stats:api:allTests :stats:data:testDebugUnitTest
  :feature:statistics:testDebugUnitTest detekt :stats:data:lintDebug
  :app:hiltJavaCompileDebug --no-parallel` passes in `41s` with 672 tasks (`9` executed, `663`
  up-to-date). Stats API JVM and Android host are each `313/313`, stats data is `183/183`, and
  feature statistics is `427/427`, with zero failures/errors/skips. Stats-data lint reports no new
  issue; its existing baseline filters 283 errors and 270 warnings.
- `./gradlew.bat checkRoomSchemaDrift --no-parallel` passes post-commit in `2s` with 42 tasks; the
  committed v28 schema is unchanged.
- Three independent final corrected-diff reviews report no remaining scoped `BLOCKER`, `HIGH`, or
  `MEDIUM` in the public authority contract, Room selection/invalidation behavior, or tests.
- Development verification first exposed missing public KDoc and one over-complex reducer; both
  were corrected. A new reactivity test initially waited on a manifest write that did not change
  the selected immutable run snapshot, then briefly became a non-void JUnit expression after its
  dispatcher correction. The final test instead uses an observed source-local fence, returns
  explicit `Unit`, passes alone, and passes in the complete gate above.
- No emulator/device/provider/process/reboot/FGS/battery, visual, accessibility, or UI evidence was
  produced.

### Gate status and next wave

- Passed: exact public capture/control authority; qualification consistency; migrated typed block;
  every-revision Steps-only filtering before limits; grouped opaque nonnumeric rows; fence-reactive
  bounded Room observation; contained Trip Detail regression; unchanged schema and writer ownership.
- Failed or unverified: a concrete list consumer, candidate-window Trip suppression, ordinary
  navigation, truthful live Dashboard layout, `QUERYABLE`, selected deletion, numeric consumers,
  device/provider/process evidence, activation, and rollout.
- Next: wire the smallest truthful Steps-only live/list product surface. Keep the logical row
  non-clickable, and introduce candidate-scoped batch composition only where the real physical list
  consumer requires it. Missing or unavailable selected/live Steps must remain nonnumeric.

## Candidate-scoped Steps-aware history page checkpoint (2026-08-31)

### Outcome

Commit `2ac4e399d` adds the finite composition required by a real action-bearing history consumer.
The caller supplies its complete bounded physical candidate window and a final result limit. In one
Room transaction, the repository snapshots those candidates, loads their authoritative logical
groups, suppresses every physical member of an exact Steps-only intent group, and emits a qualified
opaque logical row only when current source evidence permits it. Baseline-only, fenced, unavailable,
or otherwise unqualified exact Steps-only groups therefore remain hidden rather than resurfacing as
misleading Trip rows.

Physical results can only echo positive, distinct, existing IDs from the supplied candidate set.
Logical Steps-only results remain non-selectable and nonnumeric. Both row kinds are ordered by the
newest authoritative physical `(startTimeMs, segmentId)` member before the final `1..100` limit.
Missing candidates are omitted, inputs are defensively copied, and the observer invalidates for all
tables read by the transaction. This commit adds no provider demand, writer, schema, migration,
deletion, navigation, or UI caller.

### Evidence

- `./gradlew.bat :stats:api:allTests :stats:data:testDebugUnitTest --no-parallel` passes in `28s`
  with 215 tasks. Stats API JVM and Android host are each `314/314`; stats data is `191/191`, with
  zero failures/errors/skips.
- `./gradlew.bat :stats:api:allTests :stats:data:testDebugUnitTest --tests
  "*StepsSegmentHistorySelectorTest" --no-parallel` passes in `24s` with 219 tasks.
- The shared `detekt :stats:data:lintDebug :feature:dashboard:lintDebug
  :app:hiltJavaCompileDebug --no-parallel` run completed root Detekt, Stats lint, and the app Hilt
  compile. Stats lint reported no new issue; the overall command later failed only on ten new
  Dashboard `MissingTranslation` findings outside this commit.
- Two independent final read-only reviews report no scoped `BLOCKER`, `HIGH`, or `MEDIUM`. They
  confirm transaction coherence, intent-based no-resurrection, qualification-only logical
  visibility, newest-member ordering, candidate containment, final limiting, and invalidation.
- No emulator/device/provider/process/reboot/FGS/battery, visual, accessibility, or UI evidence was
  produced.

### Gate status and next wave

- Passed: finite candidate ownership; whole-group exact Steps-only suppression independent of
  current qualification; qualified opaque replacement; stable post-merge ordering and limit;
  missing-candidate omission; no action authority leakage.
- Failed or unverified: a production list caller, ordinary navigation, live Dashboard truth,
  `QUERYABLE`, selected deletion, device/provider/process evidence, activation, and rollout.
- Next: connect the bounded page only to a concrete finite Dashboard history list, while separately
  completing the live Steps-only surface. Do not apply it as an unbounded paging filter.

## Live Dashboard Steps-only presentation checkpoint (2026-08-31)

### Outcome

Commit `4803d8af3` adds an evidence-backed live Dashboard surface for an active exact Steps-only
physical segment. The ViewModel binds `observeSession(segmentId)` to the service-running/session
pair with `flatMapLatest`; replacement or stop cancels the stale observer. Exact revisioned capture
authority, not `sampleCount`, qualification, or the runtime accumulator, selects the Steps-only
layout. Control-only Activity remains separate. A missing row, stream failure, or foreign segment
fails closed to a neutral unavailable surface, while a later valid row may recover.

The Steps value remains typed as complete positive, covered zero, positive partial lower bound,
materializing, missing, or unavailable. Only complete covered zero renders numeric `0`; unavailable
authority takes precedence over any numeric payload. The Steps-only screen is intentionally small:
recording state, durable Steps value, and status. It has no map, Location/GPS, distance, speed,
accuracy, sensor-detail, or detail action, and it omits a clock-driven duration rather than showing
a stale value or adding a periodic timer.

The tracking pill now keeps Stop reachable without Location permission. Existing runtime
distance/Steps/time milestone behavior remains unchanged for a `Standard` presentation only when
its segment ID equals the current physical session. Steps-only, resolving, unavailable, and stale
replacement authority consume no raw milestone input. Ten new strings are present in all 27
Dashboard locale resource sets. No provider demand, writer, schema, list composition, deletion,
activation, or rollout behavior changes.

### Evidence

- `./gradlew.bat :feature:dashboard:testDebugUnitTest detekt
  :feature:dashboard:lintDebug :app:hiltJavaCompileDebug --no-parallel --console=plain` passes in
  `1m 37s` with 766 tasks. Dashboard is `200/200` across 49 suites with zero
  failures/errors/skips. Lint has zero errors and one pre-existing guarded `InlinedApi` warning;
  all localized resources compile. Detekt and app Hilt compilation pass.
- The post-correction focused six-class Dashboard shard passes in `35s` with 268 tasks: `24/24`,
  zero failures/errors/skips. It covers live-state mapping, replacement/stop cancellation,
  screen containment, Stop without Location permission, raw-milestone suppression, and typed Steps
  presentation.
- The final stale-Standard/new-segment authority correction passes its three focused suites plus
  root Detekt in `41s` with 269 tasks: `14/14`, zero failures/errors/skips.
- Two independent reviews found and the final code corrects indefinite `NotFound` resolving, a
  non-ticking duration, global milestone removal, and stale-segment milestone authority. The final
  corrected-diff verdict has no remaining scoped `BLOCKER`, `HIGH`, or `MEDIUM`.
- No emulator/device/provider/process/reboot/FGS/battery, visual, or accessibility evidence was
  produced.

### Gate status and next wave

- Passed: exact live capture authority; control separation; segment rebinding/cancellation;
  fail-closed missing/error/mismatch; truthful numeric-zero boundary; contained Steps-only UI;
  Stop without Location permission; exact-segment milestone containment; localized resources.
- Failed or unverified: finite recent-list wiring, ordinary sole-source discovery, `QUERYABLE`,
  selected deletion, manual/device provider and listener-removal proof, activation, and rollout.
- Next: wire the candidate-scoped page to the finite Dashboard recent-history consumer. Suppress
  the raw Last Session bypass when the composed newest row is opaque Steps-only, and keep the
  Statistics paging path outside this bounded contract.

## Bounded Dashboard recent Steps history checkpoint (2026-08-31)

### Outcome

Commit `123c6399e` makes both recent `TripDao` queries deterministic at equal start times with
`startTimeMs DESC, id DESC`. Commit `ab760d234` replaces the Dashboard's independently loaded raw
recent trips with one coordinated product flow: at most 20 physical candidates form an immutable
generation, `flatMapLatest` supplies that complete generation to the Stats Steps-aware page with a
final limit of five, and physical echoes are mapped only from the same snapshot. There is no
per-row lookup, raw fallback, unbounded backfill, or independently limited merge.

The feature-facing state is typed `Loading`/`Content`/`Unavailable`, with typed physical and opaque
Steps-only rows. Recent history is observed only while tracking is stopped. Lifecycle collection
uses a five-second stop timeout but expires replay immediately when that timeout elapses, so a stale
physical top row cannot regain Last Session/map/detail authority before the refreshed composed page.
Only a leading composed physical row may feed Last Session; a controller snapshot may refine it
only when IDs match. A leading Steps-only row is non-clickable, exposes no Steps total or distance,
and shows only a neutral title, state, stopped duration, and relative time. Missing, materializing, partial, loading,
or unavailable Steps never becomes zero, distance, a map, or a detail action. Existing physical-row
behavior remains unchanged. The neutral recent-tracking copy is complete in the base resources and
all 27 locale-specific sets.

This is the first ordinary bounded Dashboard caller of the production Steps-aware history query. A
qualified stopped Steps-only logical entry can therefore advance the Dashboard's `QUERYABLE` gate
even when its physical segment has zero generic samples. That statement is limited to seeded/durable
host evidence; it does not prove the sensor-to-WAL-to-fact device scenario.

### Evidence

- `.\gradlew.bat :core:base:testDebugUnitTest --tests
  "com.adsamcik.tracker.shared.base.database.dao.TripDaoTest"
  :feature:dashboard:testDebugUnitTest --tests
  "com.adsamcik.tracker.dashboard.data.DashboardHistoryRepositoryTest" --tests
  "com.adsamcik.tracker.dashboard.ui.DashboardViewModelHistoryTest" --tests
  "com.adsamcik.tracker.dashboard.ui.compose.DashboardRecentHistoryPresentationTest" --tests
  "com.adsamcik.tracker.dashboard.ui.compose.cards.RecentTripsCardTest" --tests
  "com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiStateTest" detekt
  :feature:dashboard:lintDebug --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `4m 21s` with 610 tasks (46 executed, 564 up-to-date).
- XML records `56/56` selected tests with zero failures/errors/skips: `TripDaoTest` `16/16` and the
  Dashboard selection `40/40`. Coverage includes equal-start/limit ordering, configured 20/5 limits,
  composition, same-generation mapping and cancellation, underfill/no fallback, tracking gating,
  lifecycle replay expiry, top-row Last Session authority, typed mode decisions, nonnumeric and
  non-clickable Steps states, and opaque keys whose string representations collide.
- Dashboard lint has zero errors and one previously recorded guarded `InlinedApi` warning. Root
  Detekt passes. All 28 resource sets parse and contain exactly one copy of every affected key.
- Storage/query review found no issue. Product review found one stale replay window; the accepted
  correction sets `replayExpirationMillis = 0`, adds the virtual-time regression, and the final
  re-review reports no remaining scoped finding.
- The initial sandboxed Gradle attempt could not fetch the pinned distribution because network was
  denied. It failed before configuration and is not counted; the authorized rerun above is the
  passing result.
- No emulator/device/provider/listener/process/reboot/FGS/battery/OEM, visual, or accessibility
  evidence was produced.

### Gate status and next wave

- Passed: deterministic finite candidates; one same-generation composition from at most 20
  candidates to at most five results; qualified
  zero-sample Steps-only discovery; whole-group physical suppression; bounded Dashboard
  `QUERYABLE`; exact Last Session authority; lifecycle reset; truthful loading/unavailable/Steps
  presentation; physical behavior preservation; localized copy.
- Failed or unverified: a real `TYPE_STEP_COUNTER` callback, exact manual capture/demand/
  registration set, positive post-baseline WAL evidence, one canonical materialization on device,
  listener removal after stop, process/reboot/FGS/battery behavior, Statistics/day/Calendar and
  numeric consumers, selected deletion, correction/day repair, retention, export/import,
  automatic/ambient Steps, activation, and rollout.
- Next: implement only the bounded test evidence/harness needed to observe the existing durable WAL
  `RECORDING` boundary and prepare the disposable instrumentation activation path. Do not add a
  persisted `RECORDING` lifecycle state or a production drain delay. The exact provider/listener
  gate remains blocked until a real step-counter device is available; do not begin the later
  Steps-local mutation or another source before that gate.

## Synthetic Steps WAL-to-materialization boundary checkpoint (2026-08-31)

### Outcome

Commit `dff1b85fd` adds a host-only test at a synthetic post-admission WAL boundary. It inserts
integrity-valid, capture-attributed synthetic Steps WAL rows, reads them through the production
`RoomDurableSourceIngress` decoder, and distinguishes no observation, baseline only, covered zero,
positive post-baseline recording evidence, and typed corruption. The producer-shaped chain requires
exact prior cumulative count, provider sequence, and window-end continuity. Its bounded scan re-reads
a short valid prefix so a corrupt next row cannot be hidden as baseline-only evidence.

Before projection, the positive WAL ordinal has no candidate writer admission receipt and the exact
canonical lane cursor remains at zero. After the production Steps lane drains through that ordinal,
the exact receipt, session/run/manifest/purpose/binding attribution, positive effective count, and
cursor are present. The legacy destination is checked under its real `source-event:<eventId>`
identity and remains empty. The test-local `Recorded` result is deliberately not a persisted
production lifecycle state.

This fixture fabricates capture attribution after the admission boundary. It does not exercise
observed-time broker authorization, demand freshness, capture-admission fencing, provider callbacks,
or a real sensor. Those remain the exact manual/device gate rather than being inferred from host WAL
construction.

### Evidence

- `.\gradlew.bat :tracker:engine:testDebugUnitTest --tests
  "*StepsRecordingBoundaryIntegrationTest" --tests "*RoomDurableSourceIngressTest" --tests
  "*StepsSessionFactProjectionLaneTest" --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `3m 16s` with 234 tasks (7 executed, 227 up-to-date).
- XML records `65/65` tests with zero failures/errors/skips: boundary `2/2`, production ingress
  `49/49`, and canonical Steps projection `14/14`.
- `.\gradlew.bat detekt --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `21s` with one task.
- Adversarial review found four initial issues: impossible covered-window continuity, an overbroad
  admission claim, hidden mid-page corruption, and the wrong legacy signal identity. All four were
  corrected; the final re-review reports no remaining actionable semantic or flakiness issue.
- The first sandboxed focused run failed before Gradle configuration because the pinned distribution
  could not be downloaded. The authorized rerun is the counted evidence. An attempted module-local
  Detekt task did not exist; the passing repository-level `detekt` task above is authoritative.
- No exact Steps-only sensor-to-WAL production-admission, provider/listener, device, activation, or
  rollout evidence was produced by the new boundary test. No process/reboot/FGS/battery/OEM,
  visual, or accessibility evidence was produced.

### Gate status and next wave

- Passed: integrity/decode boundary; baseline and covered-zero exclusion from the positive recording
  verdict; producer-shaped positive chain; bounded corrupt-prefix handling; absence of a candidate
  receipt before drain; exact canonical receipt/cursor afterward; no legacy duplicate destination.
- Failed or unverified: production admission and freshness; exact sole-source demand/registration;
  a real `TYPE_STEP_COUNTER` callback; normal recovery timing; live/stopped UI on device; listener
  removal; process/reboot/FGS/battery behavior; activation and rollout.
- Next: add only the disposable test instrumentation seam for the exact manual Steps-only scenario.
  It must use normal production recovery rather than calling `drainThrough`, and platform listener
  removal still requires before/during/after `dumpsys sensorservice` evidence on real hardware.

## Disposable manual Steps-only device-gate harness checkpoint (2026-08-31)

### Outcome

Commit `1bb9749af` adds `ManualStepsOnlyDeviceGateTest` and the minimum Hilt integration-test access
needed to observe the existing production chain. On a cleared disposable debug install, the test
sets the exact Steps-only policy, completes production automatic-control reconciliation, requires
an otherwise empty tracking database, explicitly installs the inert Steps shadow lane, and activates
the candidate writer only inside the test. It then starts through `TrackerServiceApi`, waits on Room
invalidation instead of sleeps, and audits exact manifest/run/segment, demand, registration,
authorization, policy, consent, collected-data epoch, lease, WAL, fact, canonical-lane, stopped
history, recent-list, completeness, and durable retirement evidence.

The harness rejects any control, ambient, Location, Activity, Pressure, Wi-Fi, or Cell demand or WAL
effect. It requires a fresh baseline within the run/manifest/authorization boundaries followed by
a contiguous positive covered Steps window; every later covered window must also be positive, so an
unchanged zero delta after baseline cannot pass.
It observes normal asynchronous candidate materialization and never inserts durable tracking rows,
calls a drain/recovery shortcut, or treats `sampleCount` as source evidence. Cleanup is armed before
the start call and runs non-cancellably on every unproven terminal outcome. Setup settings and
rollout mutations remain in the disposable install, so app data must be cleared after every result.

### Evidence

- `.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `26s` with 525 tasks (5 executed, 520 up-to-date).
- `.\gradlew.bat detekt --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `26s` with one task.
- `.\gradlew.bat :app:lintDebug --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passes in `56s` with 1,073 tasks (7 executed, 1,066 up-to-date).
- `C:\Users\adam-\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l` returned only the
  header and no device rows. Therefore the connected class command was not run:

  ```powershell
  .\gradlew.bat :app:connectedDebugAndroidTest `
    "-Pandroid.testInstrumentationRunnerArguments.class=com.adsamcik.tracker.app.tracking.ManualStepsOnlyDeviceGateTest" `
    --no-daemon --no-parallel --max-workers=1 "-Pksp.incremental=false"
  ```

- Two staged-diff adversarial passes found concrete cleanup, durable-audit, zero-delta, timing, and
  evidence-boundary issues. The final source corrects them; the last review reports no remaining
  commit-blocking false-pass, cleanup, privacy, lifecycle, or source-isolation defect.

### Gate status and next wave

- Passed: instrumentation compilation; Detekt; app lint; exact test-owned setup contract; static
  review of sole-source attribution, asynchronous materialization, terminal cleanup, and durable
  audit assertions.
- Blocked: a real post-baseline `TYPE_STEP_COUNTER` callback; connected production admission and
  query execution; rendered Compose/UI and accessibility truth; before/during/after
  `dumpsys sensorservice` proof of physical listener removal; process/reboot/FGS/battery/OEM behavior.
- Next: run this exact class on one identified representative physical step-counter device with a
  cleared `com.adsamcik.tracker.debug` install, retain the logged device identity, capture
  `adb logcat -s ManualStepsGate:I "*:S"`, and collect `dumpsys sensorservice` before, during
  `STEPS_GATE_LISTENER_ACTIVE`, and after `STEPS_GATE_LISTENER_RETIRED`. Separately inspect the
  rendered live and stopped list surfaces. Do not activate Steps or treat another source's host work
  as a substitute for those assertions. TI-D123 now permits disjoint contained development in
  parallel worktrees while this device gate remains blocked.

## Local foundation convergence and first parallel wave checkpoint (2026-09-01)

### Outcome

The complete continuation through `4b25d39e2` is now integrated by fast-forward into local
`dev/v10` in the clean `tracking-infra-integration` worktree. The former continuation worktree and
merged branch were retired. Nothing was pushed. The root checkout remains detached at its original
`ffd5d372f` with exactly the six handover-protected dirty/untracked paths; SHA-256 checks before and
after convergence are identical.

Three disjoint implementation worktrees form the first parallel wave:

- `codex/ti-steps-session-deletion`: schema-free typed exact candidate-owned Steps session deletion,
  monotonic no-resurrection fence, and explicit-zone day repair;
- `codex/ti-pressure-capability-plan`: capability-normalized Pressure sample/FIFO request semantics
  with no fake batching tier or unnecessary provider restart;
- `codex/ti-cell-durable-admission`: minimized stable Cell delivery identity and atomic ingress-owned
  sequence allocation without radio/subscription identity.

Shared schema/history/export/import and these tracking documents retain one integration owner. Each
branch must pass focused gates, receive a corrected-diff review, rebase latest local `dev/v10`, and
merge locally one at a time. A free worker then takes the next dependency-ready source-local slice.

### Evidence

- `git merge-base dev/v10 codex/ti410-deletion-rearm` was `ffd5d372f`; left/right count was `0 33`,
  and none of the six protected root paths appeared in the base-to-continuation diff.
- `.\gradlew.bat ciUnitTest --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passed in `17m 42s` with 990 tasks (61 executed, 929 up-to-date).
- `.\gradlew.bat ciCheck --continue --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"` passed in `11m 17s` with 1,987 tasks (231 executed, 10 from cache,
  1,746 up-to-date). This includes Room schema drift, architecture, lint, Detekt, host tests,
  release compilation, dependency metadata, SQLite linkage, and release-evidence tests.
- `git rebase dev/v10` reported the continuation up to date. `git merge --ff-only
  codex/ti410-deletion-rearm` advanced local `dev/v10` from `ffd5d372f` to `4b25d39e2`.
- The six protected root-file hashes remain exactly `882BAD525CDA927710FD13C1A1DB1DDD11437963BACC2C50313708BB05BB73D4`,
  `01C7D5CC62AF43100B6F8A1458F45662BE293B659AEC0F23AD7CAD5F79F1D0F3`,
  `794B8A4ADAC5B6F387BEF07A58C90805538127A85CC2A0C00266209C985CD9E6`,
  `F6F46E2ABA5B2E110DD0F994E280C961B3E1315F79D8FB59C60D053BEE3FBF28`,
  `3F77380D36234BCAAD3A193CC2553E8D50F2C0F0D02928232B4CC99BFC7DA332`, and
  `E0912B613CD330949D12DAF69268E1A7B9DDE0F9181D0D235839E0CD8F8B7BF6` in the order listed by
  `CONTINUATION_HANDOVER.md`.

### Gate status and correction

- Passed: clean local convergence, authoritative host aggregate, full repository quality gate,
  exact ancestry, protected-path non-overlap/hash preservation, and first-wave ownership isolation.
- Blocked: the real manual Steps device gate and every provider/process/reboot/FGS/battery/OEM/UI
  claim named above; ordinary activation, rollout, release, deployment, tag, and push remain absent.
- Corrected repository claim: Pressure no longer performs one Room checkpoint per raw callback. The
  existing runtime and 10,000-sample stress test already show window-proportional admission and
  checkpoint work. TI-411 still lacks capability-truthful provider requests, full typed window
  quality fields, a candidate fact lane, `RECORDING`, production query/UI, deletion, and export.

## Pressure capability-normalized request checkpoint (2026-09-01)

### Outcome

The first parallel Pressure slice is accepted and fast-forwarded into local `dev/v10` at
`afcb009e1`. Pressure now derives one immutable provider request from the Android sensor's truthful
minimum/maximum delay and FIFO capabilities. The normalized sample period and report latency are
the exact tuple used for initial registration, compatible refresh comparison, replacement, and
capacity resume. Unsupported requested quality is reported as `DEGRADED`; no-FIFO hardware forces
zero report latency instead of preserving a fake batching tier.

### Evidence and boundary

- Fresh review found no remaining blocker, high, or medium issue in the corrected four-file slice.
- A forced focused rerun passed `53/53`: 7 provider-request, 29 runtime, 8 window-accumulator, and
  9 acquisition-floor tests (`BUILD SUCCESSFUL in 5m 3s`, 230 tasks executed).
- The combined affected Detekt and `:tracker:engine:lintDebug` gate passed (`BUILD SUCCESSFUL in
  31s`, 425 tasks) with no new lint issue.
- The branch was rebased onto the prior local documentation checkpoint and fast-forwarded into
  local `dev/v10`; nothing was pushed or activated.

This is JVM/mock-sensor evidence. It does not prove a device's advertised capabilities, realized
sampling cadence, FIFO delivery, flush behavior, wakeups, battery impact, process/reboot behavior,
or product UI. Pressure still needs durable atomic delivery identity/admission, complete typed
quality facts, source-local product materialization/query/UI, deletion, retention, and portable
export/import before its independent vertical is complete.

## Cell atomic delivery-admission checkpoint (2026-09-01)

### Outcome

The first parallel Cell slice is accepted and fast-forwarded into local `dev/v10` at `862e839eb`.
Cell now turns only a fresh, nonempty, timestamp-qualified callback into one minimized delivery whose
stable identity is independent of a process-local runtime generation. Room owns replay resolution
and source-sequence allocation. Cached/operational outcomes, stale/future/unknown timestamps, empty
coverage, and raw radio or subscription identity remain outside durable product evidence.

The stop acknowledgment now keeps a physical-run admission-ordinal high-water separately from
callback resolution. A later duplicate can therefore resolve a newer callback without replacing a
newer WAL boundary with the older duplicate row's ordinal. The high-water resets only on physical
start and is updated by the existing FIFO actor.

### Evidence and boundary

- Fresh review found no blocker and independently forced the exact ordinal-regression and real-Room
  replay cases: `2/2`, `BUILD SUCCESSFUL in 4m 6s`, 234 tasks executed.
- The complete focused post-rebase gate passed `38/38`: 37 Cell runtime cases and one in-memory Room
  admission/replay case (`BUILD SUCCESSFUL in 3m 50s`, 230 tasks).
- Post-rebase Detekt and `:tracker:engine:lintDebug` passed (`BUILD SUCCESSFUL in 2m 29s`, 374 tasks),
  with no new lint issue and six baseline-filtered warnings.
- The reviewed branch was rebased onto `f40ee2c3e` and locally fast-forwarded; nothing was pushed,
  activated, released, or deployed.

This is JVM/Robolectric/in-memory-Room evidence only. It does not prove real Telephony callbacks,
process death, radio timing, active-refresh realization, battery impact, or OEM behavior. Cell still
needs source-local typed materialization, production query/UI, deletion, retention, and portable
export/import before the Cell-only product vertical is complete. The next disjoint radio slice is
Wi-Fi replay-stable durable admission; it must preserve bounded direct-demand attempts and passive
callback ownership without creating repeated empty or receipt-age-dependent rows.

## Pressure atomic delivery-admission checkpoint (2026-09-01)

### Outcome

Pressure durable admission is accepted and fast-forwarded into local `dev/v10` at `3a3bcbadb`.
The runtime now submits a pre-sequence delivery candidate and Room allocates the source sequence,
WAL row, and optional sensor checkpoint in one transaction. Exact duplicate repair is deliberately
narrower than generic replay: the retained WAL row must prove the same provider envelope and the
same observed-time authorization over the complete intrinsic Pressure window before it may mutate
the runtime checkpoint.

Fresh review initially returned `NO_GO` on four authority defects. The corrected commit checks the
retention floor before replay, projects and verifies the retained source instance, registration
generation, physical fingerprint, and authorization revision, prevents a wrapper from narrowing a
Pressure or Steps interval, and removes process-local callback sequences from stable Pressure
identity. A callback-sequence-only payload change is therefore an `IDENTITY_COLLISION`, not a new
fact or a checkpoint update. Generic duplicate replay without a sensor checkpoint retains its
existing cross-runtime-envelope semantics.

### Evidence and boundary

- The corrected author gate passed `153/153` focused tests before rebase, plus Detekt, both affected
  lints, and `checkRoomSchemaDrift`.
- Independent re-review forced `SourceEventWalDaoTest` and
  `PressureDurableSourceIngressTest`: `34/34`, `BUILD SUCCESSFUL in 2m 21s`, and returned `GO` on all
  four original findings.
- The complete post-rebase set passed `153/153` in `2m 53s` with 248 tasks executed: 26 DAO cases,
  11 sink cases, 49 generic Room ingress cases, 8 Pressure Room cases, 7 provider-request cases,
  30 runtime cases, 8 accumulator cases, and 14 window-lane cases.
- Post-rebase Detekt, `:core:base:lintDebug`, `:tracker:engine:lintDebug`, and
  `checkRoomSchemaDrift` passed in `1m 53s` (388 tasks), with no new lint issue and only existing
  baseline-filtered findings. The clean reviewed branch was then locally fast-forwarded; nothing
  was pushed or activated.

This is host/Robolectric/in-memory-Room evidence. It does not prove real sensor callbacks,
advertised or realized FIFO/flush behavior, process death, device lifecycle, or battery impact.
Pressure still needs its typed quality fact lane, `RECORDING`, production query/UI, deletion,
retention, portable export/import, and representative-device proof before the independent Pressure
vertical is complete. Continuous ambient Pressure remains outside the default product.

## Location immutable observation-floor checkpoint (2026-09-01)

### Outcome

The protected Location runtime correction is accepted and fast-forwarded into local `dev/v10` at
`a4caa9f12`. A newly reserved physical registration now captures an immutable elapsed boundary,
rejects synchronous callbacks until provider acceptance is durable, and admits only fixes at or
after the stricter provider/authorization floor. A compatible authorization refresh retains the
same provider but may only raise that floor. Retry processing requalifies the immutable raw fixes
against the latest cutoff, freshness, future-time, and floor constraints.

Location stop settlement also retains a monotonic admission-ordinal high-water for the complete
physical run. A later callback that resolves through an older duplicate WAL row cannot replace a
newer durable boundary. The high-water resets only when a new physical registration starts.

### Evidence and boundary

- The author gate passed all 58 focused Location tests plus Detekt and affected lint.
- Fresh independent review found no scoped high and reran the same six suites: `58/58`, zero
  failures/errors/skips, `BUILD SUCCESSFUL in 1m 22s` with 234 tasks.
- The clean three-file commit was already based on the latest local integration checkpoint and was
  locally fast-forwarded; nothing was pushed, activated, released, or deployed.

This is JVM/Robolectric/static evidence. It does not prove real fused/framework provider callbacks,
passive or active cadence, process death, reboot, foreground-service legality, battery impact, OEM
behavior, production query/UI, deletion/export, or shadow parity. The existing canonical Location
writer remains the only canonical writer; no destination-owner cutover or rollout action occurred.

## Activity callback freshness-envelope checkpoint (2026-09-01)

### Outcome

The bounded Activity correction is accepted and fast-forwarded into local `dev/v10` at
`3c7b28545`. Recognition and transition provider times now fail closed when negative, overflowed,
pre-acceptance, cutoff-invalid, or future-dated while valid siblings retain their original callback
indexes. The receiver publishes only the sparse subset that atomic Room admission reports durable.

The first review found one remaining product-stream defect: raw recognition presence suppressed a
valid transition-derived Activity even when the recognition was omitted from durable selection.
The correction removes that raw flag. A selected recognition retains precedence; otherwise the last
selected transition updates the contained Activity state, while selected transitions always keep
their independent transition stream.

### Evidence and boundary

- The serialized focused command passed `112/112`: Activity receiver `30`, retry worker `9`,
  delivery factory `12`, Activity Room ingress `12`, and generic durable ingress `49`, with zero
  failures, errors, or skips (`BUILD SUCCESSFUL in 4m 4s`, 269 tasks).
- Root Detekt plus `:sensor:activity:lintDebug` passed in `1m 22s` (348 tasks) with no new issue.
- Fresh independent corrected-diff review inspected all ten commit paths and found no remaining
  scoped blocker, high, or medium finding. The branch was already based on the current local
  integration head and was fast-forwarded; nothing was pushed, activated, released, or deployed.

This is JVM/Robolectric/static evidence. It does not prove real Google Play services PendingIntent
delivery, process death, reboot, automation-trigger legality, foreground-service behavior, battery
impact, OEM behavior, source-local materialization, production query/UI, deletion, retention, or
portable export/import. Activity capture and control remain separate purposes, and no provider or
writer rollout occurred.

## Activity automatic trigger provider-authority checkpoint (2026-09-01)

### Outcome

The bounded Activity trigger correction is accepted and fast-forwarded into local `dev/v10` at
`0a6a8f545`. Automation projection now requires a complete, positive control-eligible Activity
stamp before it emits an effect. A missing or malformed stamp is omitted normally, so one bad
member cannot throw and poison a valid sibling.

The outbox validator and every action-repository authority check now reload the exact Activity
provider generation. Boot identity, collected-data epoch, durable acceptance floor, failed/status
state, and the existing accepted-inclusive/retired-exclusive registration interval must all cover
the observation. Historical evidence before a later retirement cutoff remains valid; evidence at
or after the cutoff is terminal. Reserve, Android-request authorization, delayed service
validation, and no-intent cold recovery share this rule. A lifecycle intent already committed under
those checks remains the durable-effect acknowledgement, while service redelivery retains its
stricter session validation.

### Evidence and boundary

- The exact final command was `./gradlew.bat :tracker:engine:testDebugUnitTest --tests
  "*ActivityAutomationProjectionTest" --tests "*ActivityAutomationOutboxDispatcherTest" --tests
  "*ActivityAutomaticStartActionRepositoryTest" --tests
  "*ActivityAutomaticStartRegistrationAuthorityTest" --tests
  "*ActivityAutomationEffectValidatorRegistrationTest" --tests
  "*AuthoritativeSessionCoordinatorTest" --no-daemon --no-parallel --max-workers=1
  "-Pksp.incremental=false"`. It passed `108/108` with zero failures, errors, or skips (`BUILD
  SUCCESSFUL in 5m 31s`, 230 tasks; XML execution `44.575s`).
- `./gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 "-Pksp.incremental=false"`
  passed in `35s`. `./gradlew.bat :tracker:engine:lintDebug --no-daemon --no-parallel
  --max-workers=1 "-Pksp.incremental=false"` passed in `7m 31s` with 377 tasks, no new issue, and six
  baseline-filtered warnings.
- Fresh independent review inspected the exact nine-path snapshot and found no scoped blocker,
  high, or medium finding. The configured `adsamcik` identity created `0a6a8f545`, and the clean
  integration worktree fast-forwarded locally. Nothing was pushed, activated, released, tagged, or
  deployed.

This is host/Robolectric/static evidence. It does not prove real Google Play services provider or
PendingIntent delivery, process death, reboot, Android foreground-service legality, battery impact,
OEM behavior, source-local Activity materialization, production query/UI, deletion, retention, or
portable export/import. Activity `CONTROL_AUTOSTART` remains noncaptured operational authority; no
control-only observation enters captured history, and no provider or writer rollout occurred.

## Steps selected-session deletion and correction-safe day repair checkpoint (2026-09-01)

### Outcome

Typed selected-session deletion for the contained v28 Steps writer is accepted and fast-forwarded
into local `dev/v10` at `b5698e635`. Deletion now requires one exact presentation reverse binding
(`serviceRun.sessionSegmentId == segment.id`), a bounded chain of exact candidate-owned Steps-only
capture manifest revisions, terminal lifecycle authority, historical manifest policy/consent
attribution, the current collected-data epoch, and exact fact attribution. Active, legacy,
mixed-source, mismatched, stale, or otherwise unverifiable rows return a typed non-mutating result;
`QUIESCED`, wall-time overlap, and `sampleCount` are never ownership predicates.

A successful transaction installs the monotonic source-local deletion fence, writes redacted
retractions, removes the exact presentation/fact membership, advances source evidence, and repairs
only affected daily summaries. Repair recomposes surviving qualified Steps facts under each summary's
stored calendar zone. Missing or mixed zone authority, materializing writer state, retention loss,
terminal projection failure, dependency overflow, or ambiguous surviving evidence fails closed
before mutation. Terminal-failure reads are deterministically ordered and limited in SQL to
`2,048 + 1`; overflow makes every affected history selection unavailable and makes deletion repair
unsupported rather than inspecting a truncated prefix.

### Evidence and boundary

- The focused pre-rebase Room/history gate passed `117/117` with zero failures, errors, or skips
  (`BUILD SUCCESSFUL in 6m 14s`, 279 tasks): `SourceSessionDaoTest` 14,
  `RoomStepsSelectedSessionDeletionServiceTest` 29, `StepsSegmentHistorySelectorTest` 64, and
  `TrackingHistoryMappingTest` 10.
- The broader post-rebase command passed `161/161` plus
  `:core:base:compileDebugAndroidTestKotlin`, `:app:hiltJavaCompileDebug`, and
  `checkRoomSchemaDrift` (`BUILD SUCCESSFUL in 12m 20s`, 505 tasks). It additionally covered
  `DailySummaryAggregatorTest` 12, `StepFactRevisionDaoTest` 11,
  `TrackingOrchestratorIntegrationTest` 4, and `StepsDailySummaryRepairComposerTest` 17.
- Final root Detekt passed in `33s`; `:core:base:lintDebug`, `:stats:data:lintDebug`, and
  `:tracker:engine:lintDebug` passed in `1m 52s`, `2m 34s`, and `4m 46s` respectively. The tracker
  lint reported no new issue and six baseline-filtered warnings.
- Fresh review first rejected an unbounded terminal-failure dependency. The accepted correction uses
  cap-plus-one SQL reads and adversarial tests where a candidate's only relevant failure lies beyond
  a prefix filled by another writer. Final review found no blocker, high, or medium finding. The
  configured `adsamcik` identity created `b5698e635`; the clean integration worktree fast-forwarded
  locally. Nothing was pushed, activated, released, tagged, or deployed.

This is host/Robolectric/in-memory-Room/static evidence. It does not prove a rendered deletion flow,
real sensor/provider behavior, listener removal, process death, reboot, foreground-service legality,
battery/OEM behavior, portable export/import, retention execution, automatic or ambient Steps, or
ordinary candidate-writer activation. The manual Steps-only device gate remains blocked because no
device is attached.

## Exact automatic-capable Steps writer binding checkpoint (2026-09-02)

### Outcome

The contained Steps writer now has two explicit immutable executable bindings, accepted and
fast-forwarded into local `dev/v10` at `b7d4900cf`. Generation 1 remains manual-session-only for
already attributed v28 facts. Generation 2 is the preferred new contract and permits manual plus
automatic session capture. Selection is exact by generation and capture-mode mask; retained V1
history is not reinterpreted as automatic-capable.

The coordinator, rollout store, projector, deletion preflight, rollback, and post-deletion re-arm
now preserve the installed binding generation. Activity remains `CONTROL` only in automatic
manifests. This commit adds no automatic demand, starts no provider, changes no durable rollout or
destination owner, and activates no writer.

### Evidence and boundary

- The final exact 14-path snapshot passed independent review with no blocker, high, or medium
  finding. The review confirmed the post-Detekt manifest-binding extraction preserves validation
  order and failure codes and that V1/V2 rollback lookup is exact.
- The final focused seven-class gate passed `188/188` before commit. After the required rebase check
  reported the branch already current, a forced integration rerun executed all 230 tasks and again
  passed `188/188` with zero failures, errors, or skips (`BUILD SUCCESSFUL in 5m 30s`).
- `:app:compileDebugKotlin` passed (`BUILD SUCCESSFUL in 2m 27s`, 352 tasks), root Detekt passed in
  `21s`, and `:core:base:lintDebug :tracker:engine:lintDebug` passed in `4m 12s` with no new issue.
  The configured `adsamcik` identity created the commit; nothing was pushed or activated.

This is host/static attribution and rollback evidence only. It does not prove an Activity trigger,
Steps provider callback, foreground-service start, process death/reboot behavior, bounded control
retention or export exclusion, production query/UI, battery/OEM behavior, ordinary activation, or
rollout. Full automatic Steps and default-off ambient Steps remain blocked by their independent
product and device gates.

## Wi-Fi durable delivery-admission checkpoint (2026-09-02)

### Outcome

The Wi-Fi durable-admission lane is accepted and fast-forwarded into local `dev/v10` at
`8000f4b16` (`e0d0ade07` plus the final hardening commit). Only a fresh, nonempty,
privacy-minimized observation can form a stable delivery candidate; process-local runtime
generations are not part of replay identity, and Room owns source-sequence and WAL allocation.
Exact replay is zero-effect while a checksum collision fails closed.

Delayed callbacks and retries now reauthenticate their complete authority. Historical registration,
authorization, physical fingerprint, demand set, session, service run, lease, purpose, immutable
manifest revisions, current policy, and the accepted-inclusive/retired-exclusive cutoff must all
agree. Manifest checksums are verified over their complete source sets. A suspended registration
can recover only from the exact current lifecycle-intent checksum and its derived source action.
Empty, stale, future, generation-invalid, incompatible-purpose, or retired-at-cutoff evidence cannot
mutate durable product evidence. Active Wi-Fi attempts remain finite and direct-demand-only; optional
context does not start or retain Location.

### Evidence and boundary

- The forced post-rebase host selection covered 15 exact classes spanning `SourceBrokerDao`, the
  durable sink and generic Room ingress, Wi-Fi runtime and Room admission, Activity Room admission,
  and Cell, Location, Pressure, registration, and window compatibility. It passed `311/311` with
  zero failures, errors, or skips (`BUILD SUCCESSFUL in 7m 32s`, 248 tasks executed).
- Root Detekt, `:core:base:lintDebug`, and `:tracker:engine:lintDebug` then passed from scratch
  (`BUILD SUCCESSFUL in 4m 50s`, 381 tasks executed). Lint found no new issue; existing baseline
  findings remained filtered.
- The final exact four-file hardening review confirmed that the only post-test delta was narrow,
  justified Detekt annotations and found no blocker, high, or medium issue. Rebase onto local
  `dev/v10` was conflict-free. The configured `adsamcik` identity created the commits, and the clean
  integration worktree fast-forwarded locally. The root checkout still contains exactly its six
  protected paths with unchanged SHA-256 values. Nothing was pushed, activated, released, tagged,
  or deployed.

This is host/Robolectric/in-memory-Room/static evidence. It does not prove physical Wi-Fi scan or
broadcast behavior, process death, reboot, foreground-service behavior, battery cost, API/OEM
differences, a Wi-Fi product fact/materializer/query/UI, deletion, retention, portable export/import,
ordinary candidate activation, or rollout. The independent Wi-Fi-only product vertical remains
blocked on those later source-local gates.

## Activity projection-authority regression fixture checkpoint (2026-09-02)

### Outcome

The Activity automation projection regression is corrected and fast-forwarded into local `dev/v10`
at `dcfc546d1`. Production validation was already correct: automation effects require a complete
control-eligible Activity stamp, including its physical configuration fingerprint. Three tests used
an incomplete shared fixture and therefore exercised the intended rejection path instead of the
eligible-control path. The fixture now supplies the missing fingerprint; production code and
authority rules are unchanged.

### Evidence and boundary

- The exact projection class passed `5/5` before commit. The forced post-rebase rerun executed all
  234 selected-command tasks and again passed (`BUILD SUCCESSFUL in 4m 24s`). Root Detekt also
  passed before integration.
- The configured `adsamcik` identity created `dcfc546d1`; the clean integration worktree
  fast-forwarded locally, and the merged topic worktree/branch were removed. Nothing was pushed,
  activated, released, tagged, or deployed.

This is a host-test fixture repair, not new production behavior. It does not prove real Activity
Recognition delivery, process death, reboot, Android start/FGS legality, battery/OEM behavior,
captured Activity history, product UI, or rollout.

## Source-qualified Steps numeric-history checkpoint (2026-09-02)

### Outcome

The source-qualified Steps composition foundation and read-only numeric repository are accepted and
fast-forwarded into local `dev/v10` at `18e4f7acb` (`011b206a8` plus `18e4f7acb`). One coherent Room
snapshot discovers source runs as well as presentation segments, requires exact reverse binding,
immutable manifest/run/purpose/consent and writer authority, groups replacement-run physical
segments by logical tracking identity, and pages settled fact state with the total key
`(serviceRunId, firstIntervalStartTimeMs, logicalFactId, writerProjectionId,
writerProjectionVersion)`. Neither `sampleCount`, wall-time overlap, nor `QUIESCED` is source or
deletion proof.

The public numeric result is deliberately typed. Only complete covered Steps slices produce
`Ready`; active or settling exact lanes produce `Materializing`; missing, partial, stale, fenced,
retention-lost, calendar-ambiguous, or contradictory evidence produces a nonnumeric
`Unverifiable`. Missing physical segments are suppressible only when every retained run has the
exact logical-service-run Steps deletion fence. Closed-database cancellation maps to storage
unavailability, while ordinary coroutine cancellation is rethrown. The repository is read-only and
has no permanent observer, provider demand, writer activation, or product consumer yet.

### Evidence and boundary

- The forced post-rebase product/storage selection passed `103/103` with zero failures, errors, or
  skips: repair composer `61`, selected deletion/materialization `30`, Room numeric repository `9`,
  and repository result mapping `3` (`BUILD SUCCESSFUL in 3m 35s`, 234 tasks executed).
- The forced DAO selection passed `44/44`: session segments `15`, source sessions `16`, and Steps
  fact revisions `13` (`BUILD SUCCESSFUL in 1m 37s`, 79 tasks executed). The stats API JVM suite
  passed in `39s` with 12 tasks executed.
- Root Detekt passed from scratch in `58s`. `:app:compileDebugKotlin`, `:core:base:lintDebug`,
  `:tracker:engine:lintDebug`, `:stats:api:lintAnalyzeAndroidHostTest`, and
  `checkRoomSchemaDrift` passed together in `5m 9s` (594 tasks: 111 executed, 483 up-to-date), with
  no new lint issue or schema drift.
- The authoritative `ciUnitTest` aggregate passed (`BUILD SUCCESSFUL in 18m 38s`, 994 tasks: 65
  executed, 929 up-to-date). An earlier aggregate exposed five branch-local deletion/repair
  regressions and the three incomplete Activity fixtures above; all eight were corrected before the
  clean rebase and passing aggregate. The configured `adsamcik` identity created both numeric
  commits, and local `dev/v10` fast-forwarded without conflict. Nothing was pushed or activated.

This is host/Robolectric/in-memory-Room/static evidence. It does not prove the connected manual
Steps-only provider-to-query scenario, listener removal, rendered UI/accessibility, process death,
reboot, FGS, battery/OEM behavior, automatic or ambient Steps, retention execution, portable
export/import, ordinary writer activation, or rollout. No goals, streaks, achievements, widgets, or
notifications consume this API yet. Before any broad 370-day consumer is wired, settled facts must
be streamed through a precomputed day-window accumulator instead of retaining and rescanning the
whole result; a service-run-first index should be considered only with measured schema/retention
need.

## Pressure qualified-window evidence checkpoint (2026-09-02)

### Outcome

The source-owned Pressure window now persists a frozen payload-v4 record with exact first/last
pressure, online slope and coefficient of determination when defined, worst per-event sensor
accuracy, normalized provider request, target duration and expected count, maximum inter-sample
gap, and an explicit target-elapsed or source-boundary closure. The rollover sample remains owned by
the next window. Legacy payload versions 1 through 3 retain their frozen reduced shape, while a
qualified v4 record cannot be silently downgraded or compacted without its regression evidence.

V4 validation fails closed on invalid statistics, enum codes, sequence cardinality, request/target
inconsistency, truncated or trailing bytes, and internally inconsistent one-sample evidence. The
wire layout is frozen by an independently decoded 126-byte golden vector. Runtime completeness is
conservative: a target-elapsed window must also meet expected count, observed-span, and strict
maximum-gap evidence; bunched samples cannot hide a gap of two provider cadences.

### Evidence and boundary

- The final forced pre-commit selection passed `95/95` exact codec, accumulator, runtime, provider
  request, durable ingress, compaction, recovery, lane, and projection tests with zero failures,
  errors, or skips (`BUILD SUCCESSFUL in 5m 31s`, 230 tasks executed). An earlier `93/93` pass
  preceded the independent review corrections.
- Root Detekt and `:tracker:engine:lintDebug` passed from scratch after the corrections
  (`BUILD SUCCESSFUL in 3m 53s`, 374 tasks executed); lint found no new issue and retained six
  baseline-filtered warnings.
- A fresh adversarial review found two medium durable-format gaps, which were corrected with exact
  sequence/one-sample invariants and independent golden/malformed byte vectors. The corrected
  re-review found no remaining blocker, high, or medium issue and independently decoded the vector.
- Commit `d63075f2d` was rebased without conflict onto local `dev/v10`. The required forced
  post-rebase selection again passed `95/95` (`BUILD SUCCESSFUL in 5m 8s`, 230 tasks executed), and
  the clean integration worktree fast-forwarded locally. The configured `adsamcik` identity was
  used. Nothing was pushed, activated, released, tagged, or deployed.

This adds qualified durable Pressure source evidence only. It does not add a Pressure fact table,
materializer, query, UI, deletion, retention, export/import, ambient collection, destination owner,
ordinary writer activation, or rollout. It is host/Robolectric/mock-sensor/static evidence, not
physical sensor/FIFO/flush/cadence, process death, reboot, FGS, battery, OEM, or rendered-product
proof. A read-only ADB check again found no attached device, so the separate exact manual Steps-only
device scenario also remains blocked.

## Portable Steps v1 contract and codec checkpoint (2026-09-02)

### Outcome

The source-local portable Steps v1 contract and strict JSON codec are accepted and fast-forwarded
into local `dev/v10` at `fd558265a` (`89f23c54a`, `dcd120a6c`, and the contract clarification
`fd558265a`). The standalone `.trackersteps` format carries only opaque kind-namespaced SHA-256
identities, the exact existing run-deletion-scope digest, immutable Steps session-capture
attribution, explicit completeness, and latest effective typed facts. Replacement physical runs
remain grouped under one logical entry. Covered zero remains explicit; baseline, reset-gap,
partial, unavailable, or absent evidence cannot become numeric zero.

The codec enforces the frozen format/version and privacy vocabulary, canonical ordering, global
identity/scope uniqueness, semantic checksums, strict JSON types and fields, collection and 64 MiB
wire bounds, and complete input consumption. Decode is entry-streaming and requires an atomic,
replay-safe authoritative sink. Encode writes zero bytes for a typed no-entry outcome, propagates
cancellation, leaves caller streams open, and can finalize only an exact successful count. The
product contract additionally requires a complete bounded point-in-time preflight before the first
sink emission and external I/O only after the storage transaction closes.

### Evidence and boundary

- The API and codec selections passed `24/24` (`9/9` format and `15/15` codec) initially, in the
  forced pre-commit run (`BUILD SUCCESSFUL in 1m 29s`, 178 tasks executed), and after the clean
  rebase (`BUILD SUCCESSFUL in 1m 32s`, 178 tasks executed), with zero failures, errors, or skips.
- Release compilation, root Detekt, and import/export lint passed before commit and after rebase;
  the latter post-rebase gate passed in `1m 49s` with 444 tasks (63 executed) and only the seven
  already-existing lint warnings outside the portable paths. After the final KDoc clarification,
  the configured `detekt --rerun-tasks` aggregate passed again in `23s` (5 tasks executed).
  An attempted module selector, `:stats:api:detekt :feature:import-export:detekt --rerun-tasks`,
  failed during task selection because `:stats:api` exposes no `detekt` task; no source check ran in
  that attempt, and the configured root aggregate above replaced it.
- A fresh export-design audit found no codec/schema corruption, but identified obligations for the
  later Room exporter/importer. The contract now states that the per-run fact cap covers latest
  portable states, not historical correction revisions; imported opaque identities and deletion
  scopes must be durably retained verbatim; and portable qualification does not grant eligibility
  for selected-session deletion. The reviewed branch was clean and fast-forwarded locally with the
  configured `adsamcik` identity. Nothing was pushed or activated.

This is a transfer schema, typed API, codec, and host/static evidence only. There is no Room-backed
exporter, authoritative importer, file registry/UI, durable imported-identity mapping, retention
execution, database-import bridge, or end-to-end no-resurrection proof yet. The later exporter must
preflight correction-expanded dependencies and a bounded complete snapshot before emitting any
bytes. A mixed-source run's Steps slice is representable, but that does not make the run eligible
for the existing Steps-only selected-deletion path. No provider, listener, device/process/reboot,
FGS, battery/OEM, rendered UI, activation, rollout, push, or release behavior is proven.

## Steps numeric streaming checkpoint (2026-09-02)

### Outcome

The broad-day Steps numeric composer now streams settled latest fact state in deterministic 256-row
keyset pages into a precomputed, at-most-370-cell day-window accumulator. It retains only the
current physical run's coverage cursors instead of materializing every fact and rescanning the
complete fact set for every day. Replacement runs still compose under one logical tracking entry,
while manifest, writer, completeness, deletion, retention, calendar, and exact physical-run
ownership remain source-local and fail closed.

The stream preserves the prior typed semantics: exact covered slices may produce `Ready`; gaps,
overlaps, uncertainty, incompatible sources, or malformed attribution remain partial or
`Unverifiable`; active work remains `Materializing`; and no missing value becomes zero. A terminal
lane whose cursor is behind still validates any already-present fact, but permits genuinely absent
or incomplete rows until the exact target is reached. Facts above the terminal completeness target
are rejected even while the lane is behind. Manifest successors and capture slices are precomputed
for constant-time per-fact validation.

### Evidence and boundary

- The focused accumulator/composer/Room/result-mapping selection passed `80/80` repeatedly. It
  includes 95,090 facts across the full 370-day bound, 256-row page boundaries, correction and
  materializing precedence, covered zero, DST, overlap/gap, replacement, and beyond-completeness
  cases.
- After narrow method-level complexity annotations, root Detekt, `:tracker:engine:lintDebug`, and
  `checkRoomSchemaDrift` passed together in `4m 5s` (381 tasks: 180 executed, 27 from cache, 174
  up-to-date); tracker lint retained only its six baseline-filtered warnings.
- The clean branch rebased directly onto `e308141a3`. A forced post-rebase selection passed `80/80`
  in `3m 21s` with 234 tasks executed. After the final constant-time manifest-successor correction,
  the identical forced selection again passed `80/80` with 230 tasks executed. Its first sandboxed
  invocation failed only with `Permission denied: getsockopt`; the authorized identical retry
  succeeded. Gradle printed `2h 13m 16s` for that retry despite a much shorter observed command wall
  time, so the anomalous printed duration is preserved rather than treated as performance evidence.
- Commit `b5f43a2e8` contains exactly five reviewed tracker-engine files and was fast-forwarded into
  local `dev/v10`. Nothing was pushed, activated, released, tagged, or deployed.

This is host/Robolectric/in-memory-Room/static evidence. It removes the recorded broad-consumer
memory/rescan blocker, but goals, streaks, achievements, widgets, and notifications still require a
separate typed-consumer audit and implementation. It does not prove the connected manual
Steps-only provider/listener scenario, rendered UI/accessibility, process death, reboot, FGS,
battery/OEM behavior, retention/export/import, automatic or ambient Steps, activation, rollout,
push, or release.

## Steps source-qualified product-consumer checkpoint (2026-09-02)

### Outcome

The read-only numeric Steps contract now reaches the Dashboard, legacy Tracker card, Game screen,
Today widget, and goal-notification decision without borrowing a raw `daily_summary.steps` value.
Only `QualifiedStepCount.Ready`, including a verified zero, supplies a number or progress ratio.
`Materializing`, missing, partial, not-captured, calendar-ambiguous, source-evidence-unavailable, and
storage-unavailable states remain typed and nonnumeric. Daily and locale-week reads are independent,
so a complete current day can remain visible while the wider week is partial.

The consumer flow is subscription-scoped and uses existing Room/GoalTracker changes only as
invalidation signals; every displayed number is reread from the source-qualified repository. Its
materializing retry schedule is finite (`250/500/1000 ms`) and a newer invalidation or collector
cancellation cancels the old read. Widget and notification entry points wait at most five seconds
for the replay-reset `MISSING` sentinel, preserve external cancellation, and fail closed to a typed
nonnumeric result. Product presence is composed from qualified Steps plus independently present
distance, duration, or session facts. Raw aggregate Steps cannot create a data state, and a
Steps-only `Ready(0)` or positive result does not require a legacy summary or fabricate another
metric.

### Evidence and boundary

- The initial consumer selection passed `99/99`: Game `22`, Dashboard `45`, legacy Tracker `28`,
  and notification `4`. A final post-edit selection reran the affected Game `22` and Dashboard
  status `6` cases with zero failures, errors, or skips.
- A fresh review returned `NO_GO` for raw-summary presence leakage and unbounded worker/widget
  waits. The correction added explicit positive/zero/null/raw-only presentation and timeout/caller-
  cancellation cases; its focused selection passed `54/54` (core `4`, Dashboard `7`, Tracker `31`,
  app `12`). A separate corrected-diff reviewer returned `GO` with no blocker, high, or medium
  finding.
- The initial root Detekt and five-module lint wave passed in `14m 02s` (1,086 tasks). Corrected root
  Detekt passed from scratch; `:core:common`, Dashboard, Tracker, and app lint passed in `4m 19s`
  (1,086 tasks), followed by a green `40s` core lint confirmation after narrowing one helper to
  file-private. Reported warnings were pre-existing and outside changed lines.
- Commits `39afb9fe4` and `3e727f255` used the configured `adsamcik` identity. The required rebase
  check reported the branch up to date, and local `dev/v10` fast-forwarded to `3e727f255`. Nothing
  was pushed, activated, released, tagged, or deployed.

This is host/Robolectric/static product evidence, not a rendered device/accessibility check. Raw
GoalTracker award mutation, streaks, achievements, lifetime/best-day metrics, and automatic or
ambient Steps remain separate work. It also does not prove the connected provider/WAL/listener
scenario, process death, reboot, FGS, battery/OEM behavior, portable import/export, retention,
ordinary writer activation, rollout, push, or release.

## Pressure append-only session-fact checkpoint (2026-09-02)

### Outcome

Pressure now has a v28 append-only `pressure_fact_revision` table, immutable source-local writer
provenance, and a dormant manual-session candidate projector. The migration seeds exact legacy
destination ownership but does not reinterpret compatibility `pressure_sample` rows as qualified
facts. A candidate must prove its own captured Pressure purpose, bidirectional service-run/physical-
segment binding, manifest and policy version, consent epoch, writer generation, deletion epoch, and
qualified payload semantics before it can append a fact.

The finite drain freezes the maximum available Pressure WAL and deletion high-water marks, clamps
the requested cutoff to that snapshot, permits legitimate sparse global ordinals, and advances its
cursor only in the same transaction as fact, terminal failure, and destination evidence. Event-
local manifest, policy, consent, checksum, or payload poison terminalizes only that exact ordinal;
structural lane, binding, version, writer, or deletion-authority conflicts fail closed before any
ingress mutation. Trigger failure rolls back and records retryable audit evidence, cancellation
after an injected first insert rolls back the fact, evidence, failure, and cursor, and an identical
retry remains idempotent.

### Evidence and boundary

- The forced post-rebase command selected `LegacyV26ImportTest`, `PressureFactRevisionDaoTest`,
  `SourceSessionDaoTest`, `AuthoritativeSessionCoordinatorTest`, `SourcePipelineRecoveryTest`,
  `TrackingRolloutStateStoreTest`, `DurableSourceEventSinkTest`,
  `PressureWindowQualificationTest`, and `PressureSessionFactProjectionLaneTest`. It passed
  `182/182` with zero failures, errors, or skips: `BUILD SUCCESSFUL in 6m 46s`, 248 tasks executed.
- `:core:base:compileDebugAndroidTestKotlin --rerun-tasks` passed with 52 tasks executed in
  `2m 04s`. One preceding restricted-sandbox attempt could not read the configured GitHub CLI and
  Android SDK paths and reported missing Build Tools 36.0.0; the identical host-access rerun was
  green, so that attempt is environment evidence rather than a source failure.
- `detekt :app:compileDebugKotlin :core:base:lintDebug :tracker:engine:lintDebug
  checkRoomSchemaDrift --rerun-tasks` passed in `11m 20s` with all 595 tasks executed. Both lints
  reported no new issues; only their checked-in baseline warnings remained.
- A fresh corrective-delta reviewer returned `GO` with no blocker, high, or medium finding. Commits
  `32bfbdafe`, `42b3e1d7b`, `489068b34`, and `db2a460a3` use the configured `adsamcik` identity,
  were rebased onto `0d562cee2`, and fast-forwarded into local `dev/v10`. Nothing was pushed,
  activated, released, tagged, or deployed.

This is host/Robolectric/in-memory-Room/static evidence for dormant Pressure facts. It does not
activate a canonical writer or prove `RECORDING`, `MATERIALIZED`, `QUERYABLE`, production history
or UI, selected-session deletion, correction/day repair, retention, portable export/import,
automatic Pressure, physical sensor cadence/FIFO/flush behavior, process death, reboot, FGS,
battery/OEM behavior, rollout, push, or release. Continuous ambient Pressure remains outside the
default product.

## Portable Steps Room-export checkpoint (2026-09-03)

### Outcome

The frozen portable Steps v1 contract now has a production Room-backed exporter for one exact
candidate-owned logical tracking entry. Export validates the reciprocal service-run/segment
binding, complete immutable manifest/policy/consent/purpose/writer/deletion authority, replacement-
run membership, settlement, retained floors, and every latest fact before the first sink emission.
Historical correction lineage is loaded globally by writer/version/fact identity rather than only
from the selected service run, so a later correction moved to another run cannot be omitted. Each
historical UPSERT counts against the selected run's correction bound, and any off-scope UPSERT
fails closed.

Canonical live facts now carry a versioned integrity digest over every retained field except the
digest itself. The writer creates that digest and the exporter verifies it before computing the
portable checksum. The portable time envelope conservatively spans both lifecycle and presentation
timestamps, which preserves delayed segment presentation without weakening exact physical-run
ownership. The export remains a bounded point-in-time snapshot and emits nothing until all
authority, lineage, checksums, limits, and cancellation checks pass.

### Evidence and boundary

- After rebasing onto local `dev/v10`, forced focused tests passed `67/67`: Steps fact DAO `15`,
  retained-fact integrity `1`, canonical writer/projection `17`, and production Room export `34`.
  The corresponding Gradle runs were `BUILD SUCCESSFUL` in `1m 19s` (75 tasks), `4m 28s`
  (230 tasks), and `1m 54s` (205 tasks), with every listed task executed.
- The post-rebase `detekt :stats:data:lintDebug :app:hiltJavaCompileDebug checkRoomSchemaDrift`
  gate passed in `2m 46s` (571 tasks: 98 executed, 6 from cache, 467 up-to-date). Lint found no new
  issue; Hilt compilation and the Room schema guard passed.
- One restricted-sandbox attempt could not read the configured GitHub CLI, Kotlin cache, or Android
  SDK and reported missing Build Tools 36.0.0. The identical authorized host-access rerun passed;
  this is environment evidence, not a source failure.
- Fresh review first found a lifecycle/presentation timestamp-envelope defect and incomplete
  checksum-mutation coverage. Both were corrected; a second read-only review returned `GO` with no
  blocker, high, or medium finding. Commits `fd32fab2b`, `d9771257e`, `47f594f09`, and `6202d16a8`
  use the configured `adsamcik` identity and were fast-forwarded into local `dev/v10`. Nothing was
  pushed, activated, released, tagged, or deployed.

This is host/Robolectric/in-memory-Room/static export evidence. It does not provide the importer,
durable portable-origin mapping, no-resurrection round trip, export registry/UI, connected manual
Steps-only provider/listener proof, rendered UI/accessibility, process death, reboot, FGS,
battery/OEM behavior, retention execution, automatic/ambient Steps, writer activation, rollout,
push, or release. The v28 schema and candidate writer remain unshipped and inactive.

## Pressure selected-session deletion checkpoint (2026-09-03)

### Outcome

Typed selected-session deletion now covers one exact dormant candidate-owned Pressure-only v28
scope. The service requires reciprocal physical-run/presentation binding, terminal inactivity, a
complete immutable manifest chain, exact Pressure `SESSION_CAPTURE` membership, policy and consent
attribution, capture QoS `1..3`, candidate writer generation, current collected-data epoch, and
source-local fact integrity. It never uses `QUIESCED`, wall-time overlap, or `sample_count` as
ownership or deletion authority.

Pressure semantic corrections may form a contiguous same-scope lineage: revision one must exist,
each later revision increments exactly once, admission ordinals strictly increase, and immutable
writer/event/logical/run/purpose/epoch attribution cannot change. Cross-scope corrections fail
closed. All retained revisions are deleted through deterministic exact-cursor 256-row pages inside
the same Room transaction as the source deletion fence, presentation removal, evidence revision,
and bounded stored-zone daily-summary repair. Cancellation, mismatched counts, SQLite failure, and
unverifiable authority roll back; an identical retry is idempotent and delayed replay observes the
fence.

The real maximal manifest consumer now loads deduplicated consent epochs with one exact capped DAO
batch per preflight/transaction validation and validates attribution in memory. It does not perform
per-manifest query fan-out.

### Evidence and boundary

- Before rebase, the corrected focused selection passed `94/94`: Pressure fact DAO `6`, Pressure
  integrity `2`, policy/deletion survival `2`, selected deletion `21`, and shared day repair `63`.
  Gradle reported `BUILD SUCCESSFUL in 5m 17s` with all 248 tasks executed. The identical initial
  sandbox run could not read configured GitHub CLI, Kotlin-cache, or Android SDK paths and reported
  missing Build Tools 36.0.0; the authorized rerun passed, so that attempt is environment evidence.
- The first broad gate exposed only Detekt structure/style findings, which were corrected with
  mandatory braces and narrow annotations around already-reviewed exact validation blocks. The
  completed pre-rebase static wave passed Detekt, `:core:base:compileDebugAndroidTestKotlin`, both
  affected lints, and app Hilt compilation; `checkRoomSchemaDrift` then intentionally rejected the
  dirty generated `28.json`. After exact commits, the drift guard passed in `21s`.
- Commits `61608800e` and `9592c42d8` were rebased without conflict onto local `6960fb79d`.
  The forced post-rebase focused selection passed `94/94` in `3m 47s` with 244 tasks executed.
  Post-rebase Detekt, Android-test compilation, both lints, app Hilt compilation, and Room drift
  passed in `4m 11s` (615 tasks: 100 executed, 515 up-to-date); lint found no new issue.
- Fresh review first returned `NO-GO` only for the bounded but prohibited consent point-query
  fan-out. Exact source/purpose/epoch batch loading plus isolation, multi-consent, missing,
  ineligible, future-policy, and QoS endpoint tests corrected it. The correction re-review returned
  `GO` with no blocker, high, or medium finding. Local `dev/v10` fast-forwarded to `9592c42d8`.

This is host/Robolectric/in-memory-Room/static evidence for typed Pressure deletion and correction-
safe day repair. It does not activate the Pressure writer or prove `RECORDING`, production
history/query/UI, retention, portable export/import, physical sensor cadence/FIFO/flush, process
death, reboot, FGS, battery/OEM behavior, rollout, push, or release. Continuous ambient Pressure
remains outside the default product.

## Empty-summary deletion and canonical fixture regression checkpoint (2026-09-03)

### Outcome

Selected-session Steps deletion no longer rejects an otherwise valid scope merely because no
surviving daily-summary row needs repair. The ordinary numeric accumulator still rejects an empty
product-day request. Only deletion composition with an explicit excluded segment may create a
zero-window validation accumulator, and it still streams every surviving logical group and fact.
Invalid authority therefore remains `DAY_REPAIR_UNVERIFIABLE`, a valid unsettled run remains
`DAY_REPAIR_MATERIALIZING`, and a settled scope with no affected summary returns an empty repair
plan rather than a fabricated numeric zero.

The tracker-engine Pressure ingress and shared broker fixtures now use the official
`PRESSURE_SESSION_FACTS` lane, exact permanent Pressure destination, candidate owner generation,
and manifest projection provenance. Generic registration tests that exercise only Steps omit
Pressure rather than synthesizing an invalid Pressure lane. Production rollout validation was not
weakened.

### Evidence and boundary

- The exact seven-class selection passed `175/175` before commit in `4m 34s` and again after the
  required no-op rebase in `4m 25s`. The complete tracker-engine host suite passed `2015/2015` in
  `11m 4s`, with zero failures, errors, or skips.
- Root Detekt passed in `40s`. The final `SourceBrokerTest` plus tracker-engine lint gate passed
  `10/10` in `4m 44s`; lint found no new issue and filtered six checked-in baseline warnings.
  `checkRoomSchemaDrift` passed in `1m 3s`.
- The first broad run's 36 failures were resolved without relaxing production checks: two came from
  the empty deletion window and 34 from fixtures predating the canonical Pressure binding. During
  correction, Detekt rejected an over-complex fixture helper, and a focused test rejected an
  intermediate construct-then-copy manifest because entity validation runs at construction. Both
  precursors were corrected before the final green gates.
- Two fresh read-only reviews found no defect in the final seven-file delta. Commits `33e0eb71d`
  and `711af5cd0` use the configured `adsamcik` identity, were already based on current local
  `dev/v10`, and were fast-forwarded locally. The temporary worktree and merged branch were removed.

#### Exact commands

Focused selection, run before commit and again after the no-op rebase:

```powershell
.\gradlew.bat :tracker:engine:testDebugUnitTest --tests "com.adsamcik.tracker.tracker.source.ingress.PressureDurableSourceIngressTest" --tests "com.adsamcik.tracker.tracker.source.runtime.SourceBrokerTest" --tests "com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepositoryTest" --tests "com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStoreTest" --tests "*RoomStepsSelectedSessionDeletionServiceTest" --tests "*StepsDailySummaryRepairComposerTest" --tests "*StepsNumericDayWindowAccumulatorTest" --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Complete tracker-engine host suite:

```powershell
.\gradlew.bat :tracker:engine:testDebugUnitTest --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
```

Final Detekt, focused broker plus lint, and Room-schema gates:

```powershell
.\gradlew.bat detekt --no-daemon --no-parallel --max-workers=1 --console=plain --no-configuration-cache
.\gradlew.bat :tracker:engine:testDebugUnitTest --tests "com.adsamcik.tracker.tracker.source.runtime.SourceBrokerTest" :tracker:engine:lintDebug --no-daemon --no-parallel --max-workers=1 '-Pksp.incremental=false' --console=plain --no-configuration-cache
.\gradlew.bat checkRoomSchemaDrift --no-daemon --no-parallel --max-workers=1 --console=plain --no-configuration-cache
```

Required integration check:

```powershell
git rebase dev/v10
```

The diagnostic static precursor used the same flags with `detekt :tracker:engine:lintDebug` and
failed before lint on `CyclomaticComplexMethod`. The first focused broker-only rerun used the final
broker selector above without the lint task and rejected the invalid construct-then-copy manifest
(`1/10` failed). The first Detekt-only rerun then reported `LargeClass`. The final commands above
supersede all three diagnostic failures.

This is host/Robolectric/in-memory-Room/static evidence. It does not prove provider callbacks,
listener removal, rendered UI, process death, reboot, FGS, battery/OEM behavior, automatic or
ambient Steps, source activation, rollout, push, or release.
