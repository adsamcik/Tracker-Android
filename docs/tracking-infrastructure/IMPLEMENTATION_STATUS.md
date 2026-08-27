# Tracking Infrastructure Implementation Status

Last updated: 2026-08-27

Execution-grade work items, ownership, dependency gates, verification commands, and rollback
behavior now live in `EXECUTION_PLAN.md`. This status file remains the checkpoint summary and
evidence index.

Current code integration checkpoint: `b20e1efaa` on `dev/v10`
(`feat(steps): select history by service run`), following queued writer provenance at
`30051416d`, durable service-run ownership at `ef1da62d2`, and the dormant Steps writer and
retention checkpoints `8bb6d606f` and `af846143c`. The initial audited baseline was
`068ebe052`; no pull, rebase, push, publish, deployment, remote configuration, or external rollout
was performed. The original `.docx` source, verbatim orchestrator prompt, and protected Dashboard
test remain untracked and untouched. On 2026-08-22 the user authorized clear scoped local commits
after each verified chunk.

## Current gate

- Current phase: TI-410 manual/session Steps is `IN_PROGRESS`. Durable logical-session and
  service-run identity now reaches the released session row and dormant Steps facts; a permanent
  exact destination-owner generation fences both legacy and candidate writes. Historical Steps
  selection now resolves each segment from its immutable service-run manifests, exact product lane,
  acquisition completeness, retention floor, and current deletion epoch rather than the current
  global owner. Legacy generation 1 remains the only active owner and there is no production
  owner-transition caller or production consumer of the new selector.
- Gate status: `CUTOVER_BLOCKED / PRODUCT_WIRING_BLOCKED`. Three independent R1 adversaries found
  that a global current-owner query would reinterpret older trips, logical-session completeness
  would leak across service runs, rollback/deletion recovery is not yet executable, and a one-shot
  `Materializing` UI would not converge. Commit `b20e1efaa` closes the first two historical-read
  defects without adding a table, generic history platform, rollout binding, or UI. Atomic
  run-boundary cutover/rollback/deletion reconstruction and an observable production query remain
  blocking. The attempted Trip Detail/query wiring and its query-only helpers were removed before
  `ef1da62d2`. The process-wide startup fence now orders the frozen-v27 drain,
  deletion, policy/provider/service entry, and data consumers. Manual and automatic starts use a
  durable prepared intent before external service/FGS acceptance, real start origin and accepted
  type evidence; stop, previous-exit, force-stop, permission-revocation, and deletion paths are
  fenced and recoverable in host tests. Live source drains are enrolled in the same deletion
  generation and recheck it at Activity-effect boundaries. Rollout schema v3 defaults every source
  to `CONTAINED`; persisted `EVENT` reachability now requires an atomically installed exact
  source-local product lane owned by the current executable catalog, writer identity/generation,
  activation floor, initialized CAS cursor, and retention pin. Duplicate, malformed, unknown, or
  non-executable active bindings fail closed before WAL admission. Configured contained siblings are
  named degradation rather than a reason to
  reject a reachable source. `CONTROL` can operate a provider without authorizing capture or a
  product lane. Live Activity poison is source-local, and terminal disabled/contained control no
  longer creates endless WorkManager retries; transient provider unavailability still retries.
  Activity authorization changes now re-read demands, policy/consent, rollout, and lane authority in
  the same Room transaction as mutation; capture-closing changes first fence and drain process-local
  callbacks, then publish an exact durable authorization-revision acknowledgement. Callback permits
  always release after bounded work unwinds, while only durable admission publishes effects. The
  other five authorization-boundary splitters/delivery identities,
  source-qualified lifecycle evidence, source-specific idempotent facts/recomputation, scoped
  deletion, truthful history wiring, and product-query proof remain blocking.
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
- Current-worktree verification: at `b20e1efaa`, the complete affected serial host run passes core
  database `876/876`, stats data `139/139`, and tracker engine `1,649/1,649`, with zero
  failures/errors/skips, plus `:app:assembleDebug`; `BUILD SUCCESSFUL in 8m 46s`, 720 tasks. The
  final focused selector/DAO gate passes `33/33`, and post-commit `checkRoomSchemaDrift` passes in
  30s with 46 tasks. The exact populated v27→v28 suite most recently remains `8/8` on
  `Medium_Phone(AVD) - 16` at the immediately preceding queued-provenance boundary; this selector
  slice changes no entity or migration and the committed `28.json` has no diff. Exact commands are
  indexed in `VERIFICATION_MATRIX.md`. These are host plus prior migration-device checks, not
  process/reboot/FGS, OEM, production-query, device-energy, or full-repository proof.
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
  remain open. Materializers, ambient exposure, and history UI are prohibited.
- Initial scope proportionality review: `BLOCKED / RESCOPE`. Three fresh read-only adversaries concluded that the safety spine is justified but the then-current unshipped 23-table v28 expansion, generic Phase 3 platform, mandatory day-journal redesign, all-at-once ambient breadth, fleet telemetry, and exhaustive governance were disproportionate to the app's current offline personal-product workflows and runnable release infrastructure. The user subsequently confirmed ambient persistence as a product requirement; it proceeds only as independently gated, default-off source verticals. The follow-up disposition is the updated R2 gate below.
- Updated R2 proportionality gate: `IN_PROGRESS / RESCOPED`. v28 never shipped, so it was trimmed
  and regenerated in place rather than preserved or repaired in v29. The 12 ownerless generic Phase
  3 tables are removed and the duplicate lifecycle lease is unified. The current schema has `66`
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
  initialized cursor, and retention pin. Shadow-lane installation and rollout activation are one
  Room transaction, and there is deliberately no canonical-promotion API yet. No candidate
  canonical writer is active.
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
- `DailySummaryAggregator` rebuilds from `SessionSegment`, and History/Calendar fabricate zero-looking summaries from missing data. There is no single production `TrackingHistoryRepository`; a persisted universal `DayOverview` is intentionally not a prerequisite.
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
- Pressure callback tokens and accumulator checkpoints now prevent restored partial windows from mixing registration generations. Provider-active, first durable sample, and stable trend readiness are still not distinct lifecycle/product states.
- Pressure-only sessions can be retained, yet `DailySummary` has no Pressure metric and the production UI can show a zero-distance/zero-step shell. Stored standard-atmosphere `altitude_m` is not calibrated elevation and cannot be promoted as vertical history.

## Focused source review evidence

| Source | Current verdict | Acquisition / durability | Canonical / product proof | Blocking evidence |
| --- | --- | --- | --- | --- |
| Location | `DEGRADED` | Immutable registration identity and boot/effective-time WAL admission exist; source-qualified accuracy/freshness state does not | Established writer still serves product; event projection has no consumer; no `DayOverview` | real origin discarded, global ownership absent, hidden Steps, ghost sessions, contradictory writer ownership |
| Wi-Fi | `FAILED` | Fresh post-registration broadcast children and confirmed-empty coverage can reach observed-time-authorized WAL after item-level age checks; payload v2 retains item time and withholds identity; startup cache/failed updates are contained; direct capture has one first-evidence scan request | Legacy `WifiObservation` path only; no terminal event materializer or day query | cross-process cache replay, source-native boundary/identity proof, any measured repeated-attempt mode, keyed identity lifecycle if approved, runtime/device proof, no qualified recording state |
| Cell | `FAILED` | Fresh timestamped callback children and confirmed-empty coverage can reach observed-time-authorized WAL; operational outcomes and persistent radio/subscription identity are omitted; direct capture has one first-evidence refresh group | Legacy `cell_sample` only; joined event frame has no terminal writer or day query | cross-process identity, source-native boundary proof, any measured repeated-refresh mode, hidden controls, multi-SIM partial failure and device proof absent |
| Activity | `FAILED` | Shared GMS physical arbiter, independent observed-time purpose authorization, atomic callback delivery, sparse writer-stamped WAL admission and zero-effect replay exist | Activity-only input has no terminal materializer or day query | stale/epoch-incomplete automation start, synchronous global drain, no movement-band writer/owner, no production query |
| Steps | `DEGRADED` | Positive deltas reach WAL; generation-bound baselines reject disabled-gap relabeling | `StepInterval` and legacy session/day totals exist, but additive aggregate is replay-unsafe and no truthful outside-session day contract exists | duplicate physical listeners, corroboration decision, no typed recompute/deletion path, ambient not implemented |
| Pressure | `FAILED` | Direct sensor runtime and generation-homogeneous aggregate WAL windows exist; first-sample `RECORDING` state does not | compatibility `pressure_sample` writer exists; no typed correction/day query or Pressure history UI | no qualified recording state, no global broker owner, uncalibrated altitude risk, no product proof |

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
  unified the two lease mechanisms. Current v28 has 66 entities after the two narrowly owned v27
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
