# Independent ambient radio recovery review — 2026-10-04

**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Static review by
`/root/fresh_bundle_context_review_0930`, using the inherited original requests and
approved decisions, current `AGENTS.md`, author packet, supplied HEAD snapshots and
actual dependencies/callers. No production edits, Git, tests, compilation, gates,
provider/device work or delegation. All 25 full-chain prerequisites remain open.

## Findings requiring repair

**AR1 — the production gate serializes the supposedly independent background drains.**
`tracker/engine/.../source/coordinator/SourcePipelineRecovery.kt:253–258` runs each
ambient drain through `withReadyGenerationDrain` (`501–509`). The real
`app/.../startup/DefaultTrackingStartupGate.kt:351–389` delegates this to
`TrackingStartupDeletionBarrier.withStartupRecovery`, whose mutex covers the entire
suspending operation (`255–262`). A slow Wi-Fi projection therefore prevents Cell from
entering its drain, despite the two channels and source mutexes. It also occupies the
startup/Stop control boundary throughout the WAL read/write loop. The independence
fixtures omit the gate; the deletion fixture models its serialized operation but
does not test sibling concurrency.

Reuse an existing nonserialized structured admission boundary that physically joins
on deletion, preserving original generation/epoch checks and the separate source
locks. The already committed `withReadyGenerationTransfer`/`withTransfer` machinery
has the cancellation/join property without holding the startup mutex, and admits
retained-data work after collection Off. Root should select its precise existing-boundary
reuse rather than introduce another registry, table, cursor or budget. Add a shared
non-null gate case proving Cell enters while Wi-Fi is suspended, plus closure/drain
coverage for that boundary.

**AR2 — new ambient consumers become prerequisites for unrelated session End/Suspend.**
`SourcePipelineModule.kt:77–83` adds both ambient writers to the same Projection set.
`ProjectionDispatcher.kt:123–134` still dispatches every member globally, and
`TrackingCoordinator.kt:41–45,66–75` registers all, starts from every active checkpoint
and stops on any consumer failure. The real `AuthoritativeSessionCoordinator.kt:4374–4380`
and `4561–4572` require this global drain before terminal session settlement. An older
background Cell/Wi-Fi transient failure can consequently make a Steps-only session's
End or Suspend return `DrainPending`. Independent ambient signals do not remove this
new coupling. Source-aware pruning also means the global cursor must not continue
using an unrelated pending ambient checkpoint after other source WAL rows are pruned.

Exclude only the exact known ambient writer IDs/versions consistently from global
session-settlement dispatch and its active-checkpoint cursor/registration selection;
keep them independently registered, drained and protected by their source checkpoint.
Preserve unknown consumers' existing conservative behavior. Add a meaningful real
global-coordinator/session-settlement fixture with pending background radio showing
unrelated completion and continued retention/retry of the radio callback.

**Current selection is not accepted until AR1 and AR2 are repaired and rechecked.**

## Other traced behavior

- Existing durable sink callbacks queue the corresponding session and ambient source
  hints only after successful/duplicate admission. Hints conflate independently and
  failed drains wait for a later hint/startup rather than polling.
- Startup registers the exact existing ambient writer IDs/version before queuing work.
  The proposed actor waits for the real Ready signal. Initial registration starts
  at the existing legacy/deletion boundary, not the current WAL end, and retained
  callbacks still go through their original registration, purpose and epoch rules.
- Each proposed dispatcher drain captures a finite high-water, requests source-only
  pages of 64, checks cancellation and yields between pages. Raw integrity/decode
  failure quarantines only that consumer after a valid prefix. Generic/transient
  errors leave a retryable failure and do not advance its checkpoint.
- The thin adapters do not use the callback's current purpose mask to decide history.
  Existing projectors independently qualify historical groups, save valid siblings,
  propagate cancellation, and use the outer projection transaction/checkpoint to
  roll back a whole callback on transient failure. No new product identity is invented.
- Maintenance excludes only the exact known ambient versions from the global minimum
  and applies each one's checkpoint to its own source. Missing checkpoints fall back
  to registration boundaries; unknown consumers/versions retain global protection.
  Existing bounded cleanup authenticates/persists retention receipts before raw WAL
  deletion; this slice does not delete independently retained user facts/children.

Inspected all **17 new and four upgraded unexecuted scenario sources**: five recovery
actor cases, eight adapter/dispatcher cases, four maintenance cases, and the four
actual Wi-Fi/Cell ingress/group fixture upgrades. The actor/adapter doubles establish
authored intent only. Actual group fixtures exercise existing Room facts/children,
replay and a failing second-group trigger. None establishes a passing runtime result.

## Exact source selection inventory and pins

Supplied baseline HEAD: `1c127f215a`; author provenance base: `8bbb29490c`.
All nine tracked author-before snapshots are text-identical to supplied HEAD.
Six tracker paths differ only in CRLF/LF bytes; the three core paths match raw bytes.
Both new-file HEAD snapshots are empty. All eleven actual files and author-after
snapshots match the exact after hashes in `AUTHOR/PACKET.json`.

The smallest current HEAD-compatible inventory is exactly the packet's eleven paths:

- Production: `SourcePipelineModule.kt` two imports/set bindings;
  `SourcePipelineRecovery.kt` constructor plumbing, channels/actors and startup/callback
  hints; `ProjectionDispatcher.kt` ambient registration/drain plus scoped quarantine
  extraction; new whole `AmbientRadioProjection.kt`; `SourceProjectionStateDao.kt`
  two queries; `SourceEventStorageMaintenance.kt` known-source boundary wiring.
- Tests: corresponding additions in `SourcePipelineRecoveryTest.kt` and
  `SourceEventStorageMaintenanceTest.kt`; new whole `AmbientRadioProjectionRecoveryTest.kt`;
  scoped ingress upgrades/imports in `WifiDurableSourceIngressTest.kt` and
  `CellDurableSourceIngressTest.kt`.

No held draft must be pulled in to satisfy these existing projector/API/entity seams.
The AR1/AR2 repairs may add narrowly owned boundary/coordinator test/query deltas;
they need fresh exact pins. Historical session Cell admission and selector propagation,
full Hilt/Room/schema convergence, physical providers and integration remain separate.

| Frozen metadata | SHA-256 |
| --- | --- |
| `AUTHOR/PACKET.json` | `074ba467eabc47353dd9c791e7e4039c0385e7fa8f4d89c6bf1d5c5dc29c7952` |
| `AUTHOR/before-pins.json` | `4293ed8cd323fdadeeeaff93fa549220d81cb8911e8e21c1a4ddc9bd9867666d` |
| `AUTHOR/before-test-pins.json` | `0f46452beff9e15a873e0518d4b9f837f448750903a89744e1f835fb9d162074` |
| `AUTHOR/after-pins.json` | `b1049fd3a647d9196afe16f3abcd8f18005c6d74cf7e50b2b600ce5b743df821` |
