# Ambient radio delivery/recovery — independent repair review

Date: 2026-10-04. Reviewer: `/root/fresh_bundle_context_review_0930`.
**IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.**

## Result

**AR1 and AR2 are closed by static source inspection.** No remaining concrete defect
was found in the combined original ambient slice and repaired selection described
below. It is coherent for a local unvalidated implementation commit. The original
rejection in `SOURCE_REVIEW.md` remains intact as review history.

This review used the inherited original user messages and approved decisions, current
AGENTS phase, the original preserved author/HEAD packet, repaired before/after sources,
actual callers and shared production gate, source-owned projectors and maintenance,
and the narrow HEAD-based session test snapshot. No code execution, tests, compilation,
Room generation, Git operation, provider activation or delegation occurred.

## AR1: independent retained drains and physical deletion wait

- `SourcePipelineRecovery.kt:250–273,536–574` now enrolls each ambient actor's finite
  drain through existing `withReadyGenerationTransfer`, using storage/deletion
  generation checks rather than acquisition readiness. It does not hold the startup
  recovery mutex throughout projection I/O or borrow new collection permission after
  Off. Separate source channels and dispatcher mutexes remain intact.
- The actual `DefaultTrackingStartupGate.kt:392–412` delegates this boundary to
  `TrackingStartupDeletionBarrier.withTransfer:272–290`. It registers a dedicated
  structured child under the existing short admission monitor. The startup recovery
  mutex is absent from this long operation. Closure cancels those children, physically
  joins them and their cleanup before publishing the next generation (`192–236`).
  The existing gate/interface are unchanged by this slice.
- Ambient actors retain the startup hint until Ready when storage is unavailable.
  Deletion-child cancellation is caught only after the structured call has drained;
  `ensureActive` preserves application/parent cancellation while allowing the same
  source actor to consume a later-generation hint. Original epoch and purpose
  qualification still belong to the retained projectors; no new consent or identity
  is granted by this reuse of the existing boundary.
- The new actual-gate app sources explicitly suspend Wi-Fi while Cell and the
  control boundary enter, assert acquisition remains denied after Off, and exercise
  cancellation plus delayed NonCancellable cleanup, old-generation refusal and
  recovery by the same actor after reopening. They are meaningful authored boundary
  scenarios, not Android or executed concurrency evidence.

## AR2: background debt does not join global session settlement

- `ProjectionDispatcher.kt:32–58,122–144,351–357` consistently excludes only the
  exact known ambient writer IDs **and versions** from global registration, dispatch
  and raw quarantine. They remain independently registered and drained through their
  original source-owned checkpoints. Unknown IDs/versions retain existing global
  handling; no broad source or ID-prefix exemption was introduced.
- `TrackingCoordinator.kt:41–44` uses the matching
  `SourceProjectionStateDao.minimumActiveGlobalCheckpoint:445–464`. Its fallback
  and missing-checkpoint registration boundary remain conservative. A pending
  ambient checkpoint cannot rewind the global continuation or block unrelated End
  and Suspend at their actual coordinator calls (`AuthoritativeSessionCoordinator.kt:
  4374–4380,4561–4572`). Selected source product settlement is unchanged.
- Existing source-aware retention at `SourceEventStorageMaintenance.kt:339–380`
  retains each ambient source's own required checkpoint. Unknown retaining consumers,
  durable joins and legacy boundaries remain global constraints. Bounded WAL pruning
  still authenticates and inserts the original retained receipts before deleting raw
  rows in the same transaction (`440–519`); retained user facts and children are not
  deleted by this patch. Retry/poison handling and cancellation are unchanged.
- Four new real Room/global-coordinator sources cover registration, repeated forward
  continuation despite background failures, raw quarantine isolation, and unknown
  version/registration protection. Two new real session-coordinator sources cover
  Steps-only End and Suspend while ambient checkpoints, failures and WAL remain
  pending. Together with two new real-gate sources, the repair adds eight unexecuted
  cases and updates two earlier boundary cases. The original 17 new and four upgraded
  scenario sources remain authored and unexecuted.

## Exact selection and dependencies

The combined selection has **15 unique production/test paths**: the original eleven
plus `TrackingCoordinator.kt`, `TrackingCoordinatorTest.kt`,
`AuthoritativeSessionCoordinatorTest.kt` and `DefaultTrackingStartupGateTest.kt`.
The two original new files and all existing API/entity/projector seams are sufficient;
no new table, cursor, budget, public test API, dependency or generic runtime framework
is required. Existing app-to-engine dependency supports the actual-gate fixtures;
their scoped internal-access suppressions do not change production visibility.

Seven original after files are untouched and match their original packet hashes.
The eight repair actual files match the repaired after snapshots. **Exception to
whole-working-file selection:** `AuthoritativeSessionCoordinatorTest.kt` contains
preserved unrelated drafts. Its repair-before is not HEAD. Only the separately
reviewed `AuthoritativeSessionCoordinatorTest.kt.selected-after` is accepted for
that path, relative to the supplied `.head` snapshot. The working file hash
`c2c354cfdbba1520d29b8dbc82ac80b0f3c13f32c8ade172787dc4a8a3551aad`
is an inspected author artifact, not an accepted whole-file selection.

The selected session test adds only two methods, their imports/helper and a private
false-default `hasNoAdmittedStepEvents` fixture flag. Both new methods set it before
Start; the zero barrier and null last sequence match their lack of Step events.
All original fixture behavior remains the supplied HEAD behavior. The new cases
call the existing subject directly on Dispatchers.IO, avoiding held global timeout,
execution-wrapper and FakeStepsRuntime adaptations. Static method inventory is
182 original methods and 184 selected methods, with no original method removed.

Root confirmed the other three newly touched repair-before files match supplied
`07d5c664` HEAD modulo CRLF/LF. The reviewer compared each repaired snapshot and
actual file byte hash, and the four overlapping repair-before snapshots directly
against their original reviewed author-after snapshots. Root owns Git selection;
this receipt does not infer an index or integrated branch state.

### Metadata pins

Repair packet directory:
`C:/Users/adam-/AppData/Local/Temp/tracker-ambient-radio-review-repair-20261004`.

| Input | SHA-256 |
| --- | --- |
| Repair `PACKET.json` | `6dd1bdac9f10da66b07809f071efda5bcd163b4836afae152b2bcf09476e5241` |
| Repair `before-pins.json` | `523763a7b052f8328443095edab9bb73df2b01288cb26a8b0069ead8d9c55ad6` |
| Repair `after-pins.json` | `f99f90b79bef578793d8e9776caf432c3ea206d70f7743c490810504ed758b08` |
| Original `AUTHOR/PACKET.json` | `074ba467eabc47353dd9c791e7e4039c0385e7fa8f4d89c6bf1d5c5dc29c7952` |
| Supplied session test `.head` | `d4d267ae915875f33eadea5398b6dd18216b2cd0ae50bc5ef998a1c0db57f5ae` |

### Combined selected after pins

Names identify the exact repository paths in the original and repaired packets.

| Source / test | Selected SHA-256 |
| --- | --- |
| `SourcePipelineModule.kt` | `5962fde0080c44fde2e1556e5abc8646cbffd0e66ed230ecf9bf4ce435f00554` |
| `SourcePipelineRecovery.kt` | `2f1560588b28e4cab5bf009e010a1d3d4dbc6fdabf7aecbf1d02bbb9d92191c0` |
| `ProjectionDispatcher.kt` | `2292359c57fb315e826b16c8cc8cd8be1b98f59d37d9fa9222472b29bcc99b0c` |
| `AmbientRadioProjection.kt` | `559da7cfe25002a9bc6598d010f4065b6a12e610711eeafcf59bf7175e88faaf` |
| `SourceProjectionStateDao.kt` | `50ea77d5ce9a445fdfd33d28c622ca9c9f4107db257979d4183a60ed9ee44e0a` |
| `SourceEventStorageMaintenance.kt` | `2d6c1affede2fdebbebd3b5a6cb9d83843593f6f90f2fc8508ac24c99fa34243` |
| `TrackingCoordinator.kt` | `a43c2596d945367ede8bf4913723326e91584e0fd7113c9fb3865ebfeeb92cdd` |
| `SourcePipelineRecoveryTest.kt` | `fbcd9e01603d31c1429295283458c778bc8bbb9ff6da66fc6a35374126d0d825` |
| `AmbientRadioProjectionRecoveryTest.kt` | `0a75af2ebdc85e77ae600f2468d2059e8f8de42270776976a54af6358bea2a8a` |
| `WifiDurableSourceIngressTest.kt` | `3a4601ec673fe33f18844d6a29e55bcdc1fceb4bdf474aa149b705ecf34c8f00` |
| `CellDurableSourceIngressTest.kt` | `408afe11816e1ea128200cad4e4c95a530345389eeadac54889d7c4d65754b22` |
| `SourceEventStorageMaintenanceTest.kt` | `26422f2896cf25202d74f389429d9074739c1895c2102bd1a9b3306329bc87dc` |
| `TrackingCoordinatorTest.kt` | `9aa9689e549828305b0dc2959338c3589182b51c29606262f32425a071f1606d` |
| `AuthoritativeSessionCoordinatorTest.kt` **selected snapshot only** | `caf7c610fe82ea8c302309acb5aa7d4335f3e2611b1cced521413948eafbad3f` |
| `DefaultTrackingStartupGateTest.kt` | `d86b4e7e3ceead1200eb28a1a293fd67b6fe97f4e7c954faf91e852f9f6a0c93` |

The eleven untouched dependencies listed in the repair packet also match actual
bytes, including the unchanged production gate/interface and module builds.

## Limits

No authored scenario was run. Cross-module compilation, Hilt/Room generation,
runtime cancellation, Android delivery/permissions, physical lifecycle and battery
behavior remain unverified. Historical Cell session admission, held draft paths,
complete export/history work, schema convergence and all 25 assembly chains remain
open. This closes two concrete source-review findings and accepts this bounded
unvalidated selection only; it does not authorize provider activation or dev/v10
integration.
