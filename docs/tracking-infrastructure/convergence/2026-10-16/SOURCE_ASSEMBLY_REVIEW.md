# Independent final assembly source trace - current58

**STATIC REVIEW RECEIPT / IMPLEMENTED_UNVALIDATED. Not final runtime acceptance.**

Reviewer: `/root/activation_reality_review58`; date: 2026-10-16.
Worktree: `C:/Users/adam-/GitHub/Tracker-Android/.worktrees/tv1010`.
Observed HEAD: `d570858cd645dbc7115c315dc02c872c00793812`.
Scope: frozen actual58 production/test source plus selected architecture102, product66 and control104. Selected portable-Steps placement68 is test-placement repair only; its manifest is recorded below, but this reviewer did not inspect its actual delta or independently review it.

## Original requirements and accepted corrections

Relevant original user messages:

> The MVP requires location/steps/wifi/cell tracking to work independently of any other active tracking but also have the capability to be enriched by whatever makes sense, eg. location enriches wifi + cell for map displays and so on.

> All three where supported

The latter answers whether independence covers manual, automatic and ambient tracking.

> Sessions will essentially ideally just be times of higher fidelity and help users organize data and perhaps designate sessions as activities trips and so on. Sessions can also be added manually afterwards.

> The adaptive system should be able to leverage hints from multiple sources that tell it whether the user is moving, stationary, what is the settings what balance between quality and power it should use. This includes whether session is active and more, fill in all the types. The system should then based on this adapt the tracking and setups so that its working reliably and trustworthy, especially trying to balance the value. The goal is that everything should work through a central systém and it should adapt to balance what the user wants if they start a session they want to prefer quality over power otherwise based on settings other preferences may be there. Use a 5 step configuration for balance of power and performance and figure out the best system that can support it.

> Users don't care about internal implementations, they care about data.

> Please close all the remaining gaps and complete the implementation work on the feature, ensuring high maintainability of the end result, streamlined implementation and no overbulding or overdesigning for the need of the app.

> Complete everything, including source files and backup/restore, before freezing

> Can we migrate to kotlin json serializer?

Accepted later corrections used in this review:

- Sessionless ambient shares the collection infrastructure. One all-day Steps choice; supported on-device service with hourly background copies plus enable/open, bounded attempts, real windows/count domains and honest gaps. Sessions do not gate daily totals.
- Valid saved readings survive quality changes, End and Off. Explicit erase/retention/epoch rules remain; healthy sources/days remain usable when siblings fail. Keep valid coordinates when optional accuracy/speed is unknown.
- Available Wi-Fi network/access-point details and Cell tower details are recorded/exported, excluding SIM/subscriber IDs. Migrate recoverable older data. Only `main` was released (user corrected earlier `master`).
- Automatic CONTROL is distinct from recorded Activity, uses the existing physical Activity owner and 48-hour expiry, and respects permission/transition-detection choices.
- My location/games use independent shared-owner leases with approved freshness/quality overrides. Unused sunrise/sunset is removed. Optional radio map context reads qualified retained Location; Wi-Fi exact/bracketed within30s, Cell preceding within60s; coarse accuracy retained. Show available map points without the rejected generic notice.
- Unified measured-date history, local calendar days, paging, Timeline/Calendar/details and optional annotations; overlapping session names appear in expanded details. Session export defaults to all available sources.
- Export selected individual readings; `Include complete sessions`, initially on, optionally expands matching sessions. One Wi-Fi and one Cell format include session/background origins. Stable IDs/times support overlapping imports, identical deduplication and no deletion by omission. User-edited Location files offer one Keep/Replace conflict choice and one current correction; no signature restrictions.
- Data backup excludes settings/schedules. Confirm replacement and logically reimport into existing databases, without a required restart or database-file swap. Tracking stays off until explicit resume.
- Approved shared permission repair, Start/End/Off fences, provider-only Steps sessions, damaged-day isolation, independent Dashboard failures and Steps visibility during sessions. The launcher widget was removed at the user's request (`Let's remove it`).
- Some exploration period statistics remain unavailable under a later explicit user decision; longest trips use complete saved measured distance grouped by start date. Never substitute all-time under a requested-period label.

Phase boundary: this receipt does not declare final assembly complete, activate providers or authorize device validation/dev/v10 integration. This reviewer executed no builds, tests, lint, schema/diff checks or devices and made no production/test edits. The parent owns bounded convergence and exact input assembly. Hashing cited files is static receipt preparation, not behavior validation.

## Verdict

The historical blanket `all twenty-five remain pending` is stale. Against actual58 plus selected102/66/104, **no additional concrete missing production seam or required authored scenario was identified** beyond these already selected repairs:

- **104** is necessary for fresh CONTROL-only automatic setup with Activity recording off, cancellation-preserving failure isolation and canonical non-Location retirement that preserves existing source-owner CAS. Its actual production deltas/new causal cases and relevant caller/guard roles were independently reviewed; no remaining concrete finding.
- **66** preserves exact Steps/native/imported Pressure selections from Dashboard rows to existing details/actions. Actual production/test edits were inspected for this trace.
- **102** updates stale source-layout fitness assertions to extracted production phases while retaining ordering, exact-CAS and startup/transfer guards; no tracking functionality changes.

Existing connected runtime/admission/writer/retained-reader/UI/action/file/retirement suites establish the authored contracts. No extra giant provider-to-UI/file fixture or duplicated exhaustive runtime subset matrix is required. Subset policy tests alone are not physical-provider proof. Authored test source is not evidence that it passes.

## All 25 prerequisites

All dispositions below are static implementation/authorship findings, not final gate completion. Referenced source/test versions and selected drafts are pinned below.

| # / work item | Current production chain and connected authored coverage | Remaining disposition |
| --- | --- | --- |
| 1 `WI-STEPS-MANUAL-001` | Shared Start/setup -> counter runtime -> durable ingress/projection -> numeric/history -> UI/file. AutomaticCaptureChainRoomTest manual Start/quality/final flush/End/Off; StepsRecordingBoundaryIntegrationTest; numeric Room; PortableStepsProductionRoundTripTest. | 66 completes Dashboard detail handoff; no other missing seam found. |
| 2 `WI-STEPS-AUTOMATIC-001` | Activity CONTROL -> automation outbox -> same prepared Start/Steps runtime/writer. AutomaticCaptureChainRoomTest:413 reaches real prepared counter and retirement with no recorded trigger-only Activity. | 104 supplies fresh CONTROL setup and independent failure handling. |
| 3 `WI-STEPS-AMBIENT-001` | Provider owner -> hourly/open/enable worker -> bounded runner/cursor/importer -> retained daily UI/file/delete. AmbientStepsFactImporterTest:867/904/935/956; handoff/worker/day readers; PortableAmbientStepsFileProductionRoundTripTest. | Present; platform/provider availability is a capability limit. |
| 4 `WI-PRESSURE-VERTICAL-001` | Sensor -> original durable windows -> projection -> retained selector/detail -> portable file/delete. Runtime/projection/reconfigured proof/selector/import/export/delete suites. | 66 completes Dashboard exact selection handoff. |
| 5 `WI-LOCATION-VERTICAL-001` | Shared active/rearmable passive owner -> authenticated delivery -> canonical handoff -> retained summary/history/map/exact file. Passive admission/protected handoff/retained lifecycle/summary/file suites. | Present; interrupted-tail and rejected-session/valid-background cases authored. |
| 6 `WI-ACTIVITY-VERTICAL-001` | Existing arbiter/receiver -> durable ingress -> captured projection/history/file; separate CONTROL outbox and48h cleanup. Arbiter/ingress/admission/composer/file/retention suites. | 104 required for fresh CONTROL-only bootstrap/later capture join. |
| 7 `WI-WIFI-VERTICAL-001` | Receiver/shared owner -> versioned per-item admission -> captured/background writers/network children -> history/map/file. Runtime/qualification/transfer suites; WifiHistoryRepositoryRoomTest:1739 real writer through retirement. | Present. Passive callbacks survive spent active allowance. |
| 8 `WI-CELL-VERTICAL-001` | Telephony/shared owner -> independent child admission -> captured/background writers/tower children -> history/map/file. Runtime/qualification/transfer suites; CellHistoryRepositoryRoomTest:2140 real writer through retirement. | Present. Unsupported details remain explicit. |
| 9 `WI-SCHEMA-CONVERGENCE-001` | Schema30, released-main12 logical migration, child migration, unknown-candidate preservation and reopen/full clear. Released upgrade/final assembly/child migration/containment suites. | Final generated schema/populated-upgrade execution must match frozen inputs; no missing schema operation found here. |
| 10 `WI-AUTHORITY-PURPOSE-001` | Current admission vs original retained authority; radio per-item/Location, quality/End/Off, sibling erase and source lookup Room cases. | Present; 104 fixes automatic setup edge. |
| 11 `WI-PURPOSE-PUBLICATION-001` | Policy/rollout -> execution identity -> lease -> physical owner/status; replacement/late close/cancellation/debt. TrackingPurposePublicationRuntimeTest. | 104 converts automatic ordinary failure into existing debt then reconciles ambient siblings. |
| 12 `WI-BROKER-PROVIDER-OWNERSHIP-001` | Single lazy physical owner composes session/background/UI leases; acknowledged retirement precedes replacement. Shared owner/UI/passive/host suites. | Present; 104 pins canonical rollback until source-owned destination CAS. |
| 13 `WI-SOURCE-CALLER-GUARD-001` | UI/API/automatic outbox/service/recovery use shared guarded Start/caller authority. Exact guard/dispatcher/accepted-authority/recovery suites. | 102 repairs stale architecture placements without weakening meaningful guards. |
| 14 `WI-QOS-BATTERY-001` | Participation separate from five-level quality; motion/power/thermal/session inputs; counter continuity and durable radio allowance/cooldown. Five-level/subset/adaptive/counter/allowance suites. | Present; physical battery evidence separate. |
| 15 `WI-LIFECYCLE-RUNTIME-001` | Durable Start/End/Off, permission continuation, process/service recovery and user-stop fencing. Dashboard/command/service/automatic-chain cases. | Present; launcher widget intentionally removed. |
| 16 `WI-LIFECYCLE-PRODUCT-PROOF-001` | Session lookup plus existing all-day retained readers; no transient-WAL prerequisite for saved data. Lifecycle64-subsets/Room lookup/all-day/retained-radio suites. | Present; provider aggregates do not invent sensor-WAL stages. |
| 17 `WI-STEPS-NUMERIC-001` | Exact count domains/provider windows -> per-day reader -> statistics/UI/files; separate stricter goal/effect readers. Numeric/day/importer/display/goal/DST cases. | Present; no session prerequisite for daily data. |
| 18 `WI-CROSS-SOURCE-RECOVERY-001` | Source-local drains, startup generation, bounded provider/import recovery and cleanup debt. Pipeline/passive/Steps/previous-exit/redelivery/full-clear suites. | Present; 104 repairs automatic isolation/owner retirement. |
| 19 `WI-CROSS-SOURCE-PRIVACY-001` | CONTROL expiry, purpose erase, archive scope, transfer quiescence/cleanup and external residual notices. Collected deletion/full-clear/CONTROL/transfer/owned-file/residual suites. | Present; unknown old custom-folder files disclosed rather than indiscriminately erased. |
| 20 `WI-SHARED-FILE-ASSEMBLY-001` | Selected format, Location conflict/current correction, individual radio/merge, bundles and logical data restore. Export/import/codec/attachment/bundle/backup/recovery suites. | Present; later selected deltas need exact composed convergence evidence. |
| 21 `WI-SOURCE-ACTIONS-EXPLANATION-001` | Exact source-issued selectors -> shared presenter/actions -> source-owned export/delete. Detail/action/deletion/selected-export identity/revision/epoch/race cases. | 66 closes Dashboard Steps/Pressure action handoff; later-declined exploration period statistics remain unavailable. |
| 22 `WI-SHARED-HISTORY-DAY-RANGE-001` | Bounded measured range -> retained readers -> pages -> Timeline/Calendar/details; annotations. Mixed assembly/range/day/paging/Compose/editor suites. | Present. |
| 23 `WI-INTEGRATION-SCENARIOS-001` | Connected callback/runtime/admission/real writer/retained query/file/control/retirement suites; shared selection/Start and per-source adapters bridge contracts. | No additional distinct scenario gap found. No claim of one giant fixture or hardware execution. |
| 24 `WI-UI-SURFACES-001` | Six-source setup/recovery, permission repair, independent Dashboard/live totals/daily Steps/history and notification/accessibility; state/Compose suites. | 66 needed for recent-row actions; rendered/device inspection still unproven. |
| 25 `WI-NONSTEPS-EFFECTS-001` | SavedSessionRewardReader -> Points/XP; saved-data/Ready reconciliation; correction/delete/restore preserve receipt identity and replace values. Real Room reward reader plus consumer suites. | Present; duration eligible without Location, unqualified raw Steps excluded. |

## Primary inspected causal anchors

- AutomaticCaptureChainRoomTest:413/478/583/610/636/666/704/724/743/762: real prepared Steps/runtime counter, quality/reset/flush, End/Off, manual Start fences and independent healthy Steps.
- StepsRecordingBoundaryIntegrationTest:75/142: actual Room WAL -> projection; synthetic raw rows start after provider admission, not physical callbacks.
- PortableStepsProductionRoundTripTest:105: real file/import/history/re-export, manually seeded initial source/WAL/facts; connects upstream typed contracts, not hardware.
- WifiHistoryRepositoryRoomTest:1739 and CellHistoryRepositoryRoomTest:2140: real writer -> retained history -> original retirement metadata -> unchanged facts and bound-tamper rejection. Separate runtime/backend tests cover callback seams.
- PassiveLocationDeliveryAdmissionTest:194/211/239/266/297/323: stop/cancellation/tail/recreated ingress/retry/valid siblings.
- RetainedLocationPurposeLifecycleRoomTest:109/126/141/190/280: Off, sibling-purpose erase, full clear and retention.
- SavedLocationSummaryReaderRoomTest:186/209/243/274/286: qualified current rewards/restored identity/unknown accuracy/retained history.
- AmbientStepsFactImporterTest:867/904/935/956: bounded runner, later healthy day, worker cursor, Off/read and stop-during-read fences.
- UnifiedMeasuredHistoryAssemblyRoomTest: actual mixed candidates/readers/paging, source-only data, measured dates and sibling failure isolation; no sensors.
- CollectedDataDeletionServiceTest:366: clear waits for admitted export cancellation/close. TrackingSourceFullClearAssemblyTest uses real final schema/receipt floors.
- LogicalDataBackupImporterRoomTest: row import/current DB/original IDs/new epoch/unchanged choices/atomic failure/Pressure ownership rebuild.
- PlayerProgressionRepositoryTest:241/273 and PointsWorkerScoringBoundaryTest:107/251/277: duration without Location, correction/delete/stale replay/restored receipt semantics. Consumers mock the reward reader; the real Room reward output is covered above.

## Superseded acceptance wording

Old SCENARIOS S11/S12/S25 widget references are obsolete. S19 Off-excludes-crossing-sessions conflicts with selected individual readings plus optional complete sessions. Restore must not require restarting or database-file replacement. Do not require settings backup, unsupported Activity/Pressure ambient products, the later-declined period exploration lookup or rejected generic map notice.

## Host/device evidence boundary

No gate was executed by this reviewer. Parent-reported reused APK/Hilt/radio codec and Room/schema/released-SQLite results remain valid only for their exact inputs. Full58 was in progress at assignment; this receipt does not declare it successful. Composed102/66/104 plus placement68 need their own appropriate convergence result. No actual provider/device, permission-dialog, rendered UI, foreground/background lifecycle or battery evidence was supplied to this reviewer.

The candidate has real activation paths: startup/Start -> chosen setup -> canonical writers -> lazy physical runtimes -> durable sink/source drains. `Dormant` descriptor names and IMPLEMENTED_UNVALIDATED documentation are not activation kill switches. Launching can collect selected data; preserve the explicit final assembly/activation boundary.

## Pinned selected drafts

Manifest and target hashes below are static identities, not passing execution evidence. 104 production/new causal cases were independently reviewed;66 actual edits and102 assertions were inspected for this trace. 68 is parent-selected metadata only.
## Narrow104 lifetime follow-up requested during receipt finalization

A deselected source can leave an already fenced canonical writer lane/database destination owner pending: production `prepareFinalKnownWriter`/`completeRollback` callers are the chosen writer setup, and projection drains advance the cursor without performing source-owner CAS. This is not physical sensor ownership: `SourceSpecificWriterTransitionCoordinators.kt:1231-1245` already requires lifecycle/capture quiescence for final setup.

No concrete global WAL retention or48h CONTROL expiry defect was confirmed. `SourceEventStorageMaintenance.kt:423-439` computes the global safe ordinal without product lanes. Its product-lane checkpoint clamp at450-453 applies only to the matching source, so healthy other sources keep pruning. A drained source can prune its <=cutoff prefix. New Activity CONTROL-only rows expire through the existing dedicated path at181-231, which uses the global safe ordinal at199-203. `SourceEventWalDao.kt:803-817` requires a product checkpoint for an expired CONTROL carrier only when the row also has capture bit4. A separately selected ambient source remains in chosen setup; a fully deselected source does not continue producing recording WAL.

Disposition: retain104's exact canonical pin. Record this persisted pending-handoff lifetime, but do not add a new completion mechanism without a demonstrated correctness/retention consequence. This finding qualifies the earlier clear: no autonomous completion-after-deselection was claimed or found; no additional user-visible production defect was established by this narrow trace.

### quality-architecture-minimal-current-phases-future102/MANIFEST.json

SHA256: b08915e65cb3c0f30dfc9f6e0e6e59392d2039f975a41f64ae7795f68996789b

| Target | original58 SHA256 | selected draft SHA256 |
| --- | --- | --- |
| app/src/test/java/com/adsamcik/tracker/app/architecture/ArchitecturalFitnessTest.kt | cba1c9b7ca7d275f876b54aad10b3e8f08ca10b435ff12e5cf73df0a0feed996 | 88608bc7326a6f1435f7e62b73dfd7b4f27a33f458b75c4a75f94336ea0d6ef8 |


### product-steps-pressure-detail-handoff66/MANIFEST.json

SHA256: 8a6265a930ded0bb2959423f5dbe7478e239f1166994cf5c3e81fa65fd7748db

| Target | original58 SHA256 | selected draft SHA256 |
| --- | --- | --- |
| feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/data/DashboardHistoryRepository.kt | 32b2a0069e6442779a5db76ee8da2c1fe0242072fa71f4e98d9bad12052547b8 | 8c422d404495942d254467b3cf2053b487439c8edcc922e4acdeaac75277e253 |
| feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/compose/cards/RecentTripsCard.kt | d2467c65a8ca273dbff8e14e90868dc98416fb8cde11db3a3ea3f436283047e6 | 8b2b50d46095cb91b1176ee6342ff5eb8125ea20521ecae62e8bdd35d8903868 |
| feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/data/DashboardHistoryRepositoryTest.kt | 403b3fb8985b4d64e23a55fd51034f1919d243ab746bd9f68eb6d34700773ddc | 02cda654e95076db8b4c1b22fb38f2aae1863e7d77bcf04126facba359c05be4 |
| feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/cards/RecentTripsCardTest.kt | 7e706630a4f5acda9b452530a2cfd3dfd9e5c4c67ad53551ba308fb9c315eb31 | 545c862ee2a3800d00fd807b47cc31edca4f51d01755c7babaade9d0a56010c3 |


### quality-portable-steps-structure-placement68/MANIFEST.json

SHA256: 39fec86c1c3f84672bbefabcdb96d5d3d6e08a27b37239fa67289a9696bd4bda

| Target | original58 SHA256 | selected draft SHA256 |
| --- | --- | --- |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/PortableStepsSourceStructureTest.kt | 67b39f8069a654e3ef8903e72a28d728bac99e4b64d5af6071266c3a7fb9ac36 | 2324835811480e03b028921c5aee917ab9932767b62010f5c764d479597add75 |


### tracking-control-only-setup-isolation-future104/MANIFEST.json

SHA256: a20b5369a60a7b70562c36074770bde1c2e7ee0432ffcbd90c101c4b9b4fb873

| Target | original58 SHA256 | selected draft SHA256 |
| --- | --- | --- |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/ChosenSourceWriterSetup.kt | 175f8fd6e62246e6d5cf7a8aad3f0e389531298b8552d28e47eefd5dad641d2d | 1ed787611fcbd37089705509b8528a4b7d69dafa3d94cdd4d3feeda983fe640c |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/TrackingRolloutStateStore.kt | 6ef0e0b7f8bc67bea204f316d2f546cd516d1e7bbec754380ad6d79bddc26b16 | 183073dbd8ccfabd57d9a5a519dcc96290b8bb43fcce78ca47a5cf6e2ff56711 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourceSpecificWriterTransitionCoordinators.kt | 2845f3078472a3f9531d9f961db0785cdf79905d3f2d5f2aba6b84c9ac2d13e7 | 8bc887f50a84cb1a51b4296a3abc2c9090664f336624a627b75474d29add0546 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/ChosenSourceWriterSetupRoomTest.kt | 872f91f0c9bd4d08b2aae435f8872105cde40e22cf13f2414c7d6e99eac148fb | 0252ee8617834745bce66461ae8bbb061c4f6f06954283671c6431f61df041a0 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntime.kt | a55575c8b30448f1d4ebb629aeb1eca6bc4eae957f09d8128c61a278687047dd | b5d7fa8195e1d56bbb571d9580a6c9ed97fcdf178958e81149c5d9bdf51592c5 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntimeTest.kt | c90de811f4ac8500c229f82190e43193b2f8bf84492c7b82949d75956f33ecb7 | bbad40eef51df226593b26e4d38802c92ce027db29db2652e8cd620c46b4344a |

## Exact current source/test pins

Paths are relative to the worktree above. These hashes pin the cited source versions observed at receipt preparation; they do not imply every line was inspected or any test executed. Review was targeted at connected production roles, actual new deltas and causal bodies; supporting suites also supplied authored-case indexes. Selected draft hashes are separate above.

| Source/test path | SHA256 |
| --- | --- || docs/tracking-infrastructure/assembly-coverage/2026-09-28/PREREQUISITE_TRACE.md | 6b42285e0574bd14fcbab356aeacf1c1da656a1b03e96b1926f4530b493888d0 |
| docs/tracking-infrastructure/assembly-coverage/2026-09-28/SCENARIOS.md | c40ee1c2d38f2f0ae26296d33cd425b6c38b2ef9f8b303bdbe0c5e14ee77264c |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/ChosenSourceWriterSetup.kt | 175f8fd6e62246e6d5cf7a8aad3f0e389531298b8552d28e47eefd5dad641d2d |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/ChosenSourceWriterSetupRoomTest.kt | 872f91f0c9bd4d08b2aae435f8872105cde40e22cf13f2414c7d6e99eac148fb |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/TrackingRolloutStateStore.kt | 6ef0e0b7f8bc67bea204f316d2f546cd516d1e7bbec754380ad6d79bddc26b16 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/SourceSpecificWriterTransitionCoordinators.kt | 2845f3078472a3f9531d9f961db0785cdf79905d3f2d5f2aba6b84c9ac2d13e7 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntime.kt | a55575c8b30448f1d4ebb629aeb1eca6bc4eae957f09d8128c61a278687047dd |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/TrackingPurposePublicationRuntimeTest.kt | c90de811f4ac8500c229f82190e43193b2f8bf84492c7b82949d75956f33ecb7 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/di/SourcePipelineModule.kt | 42b27c7324384518097d2094902a88daccf3304f4feb21cab4cad8fec6959a7c |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/SourceRuntimeRegistry.kt | 47ec8ee2d2865c6caf5d58e4b54695d3a69f450571da5bfd25527ac2b7f89db2 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/catalog/DefaultSourceImplementationCatalog.kt | cbb05feaa16b248b32fe6941bdc527ba46f660c68468b3afb2ca4f79b3d641b4 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/service/DefaultTrackingStartRequestCoordinator.kt | 3fee394aa1cfd479d60d0a8546135fc3c30624a0e53298c70a6e143a47a74a24 |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/coordinator/AuthoritativeSessionCoordinator.kt | a2b1b700d0588203fdf1564896632c4e82dfe7a0c7a74f68e9b95de7d71cab2c |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/service/AutomaticCaptureChainRoomTest.kt | e1114a0f5c1b87c0c114cbe3e60dd39c955cb000af7d0df574750fc54c80dbd1 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/projection/StepsRecordingBoundaryIntegrationTest.kt | 17957058d7d91b56c99e33c6a8a0b83e0aef94358b1273a13f41971a4ccee51e |
| app/src/test/java/com/adsamcik/tracker/app/tracking/PortableStepsProductionRoundTripTest.kt | 86e983eb117f1bfd4865fc8547e81bd060571ba2aea10531c1145f13498ed64c |
| app/src/test/java/com/adsamcik/tracker/app/tracking/PortableAmbientStepsFileProductionRoundTripTest.kt | 0e5f06f8ac152b09f23035f3d176508fccb5aa2de42271d9cb6aef5b3c1de15d |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsImportRunner.kt | 797a52414bd75b2523f15abb010f860cb809d430af686e4ec2a85898d33984ab |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/worker/AmbientStepsImportWorker.kt | c339191f05936bb93ba6aca5a7eb58a4359b1a6a2a2fcf317967e6bc58bcc920 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsFactImporterTest.kt | 86392d90decfe7c81d5fab134a1f51ee13b1b311a5ed0e462b5b2f0e94c03c18 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/ambient/steps/AmbientStepsProviderHandoffCoordinatorTest.kt | ceeae4c03481522a9953d4891af0529d7de893a74f62995d628b72adeec05ff2 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/worker/AmbientStepsImportWorkerTest.kt | a75d6f9038fe67bf70e4d09a4d8c34574af00c4442d9ea15519792bbdac7973a |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/runtime/AmbientLocationPurposeOwner.kt | a05e2ce554e0d19bcf3cd9fe68dc08f44339a45a3ecb0c9ac1143807288e6784 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/PassiveLocationDeliveryAdmissionTest.kt | 0caa5f8f9c9ef7c93c9b9f491a31d40eac0ce99ce8f4d934bde7c18d88fc6ab5 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/location/ProtectedLocationCanonicalHandoffTest.kt | aa33b5ff0115830332ebe8ec57c67fcfb8fb27768b7b54273a1f9ad6991d44d2 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RetainedLocationPurposeLifecycleRoomTest.kt | 620b6fd9829156eecd547e3541815d16e11660561e048e10b8787dfe489a284a |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/SavedLocationSummaryReader.kt | 07676f7293636236a80d4653b17ba8cb8ddfec43b202b95b0dd987f796f2864f |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/SavedLocationSummaryReaderRoomTest.kt | c9461c56de0235b80ae65ae8fdd83f85ebd799f10e41cd6caf3af4f585d05621 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/LocationFileExporterTest.kt | ef888fff1b104b2ad4361d7cc84fb735d67d83e275ccbe1d560be7e89c720c73 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/LocationFileImportTest.kt | ade7f1adccbc339437bb728887be640e1463eb3733e8ed29a754b0a0640ccc10 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/activity/ActivityCapturedWalAdmissionAdapterTest.kt | 444419b75d2816bb81c3a632a0432437c765986936c9a5b6d947e66845a26112 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/ActivityHistoryComposerTest.kt | 842d09edc6b1bacb8068333466b97bc5ccd1e935a01a768c08432b6e9323df65 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/projection/ActivityControlRetentionMaintenanceTest.kt | 7ab69a37d9a08707219668a7c5de7549bb1925407a48b6ca901282af078b839b |
| sensor/activity/src/test/java/com/adsamcik/tracker/activity/api/registration/DefaultActivityRegistrationArbiterTest.kt | df67b1291ca4cf89fa63ff6b372d35306c4dc54250f5899535dc16533a15f623 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/projection/PressureSessionFactProjectionLaneTest.kt | 4a21adfa74e3b1cfe81481f57f78421bb46fbdbf73942663667eb0f56b516809 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/PressureHistorySelectorTest.kt | ee8474b26a251bb4032fd555e3da588e7baadd959f43e3e4076a7ec2b46b14ba |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/PressureSourceRuntimeTest.kt | a09508b3270063645f1d92a67e416a9e40ed50559c37caf404036ca646426ed1 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/lifecycle/ReconfiguredSessionSourceProofRoomTest.kt | 73d4e565d047a7a730060dd56e196d9710222ea186ddcc6a4af9a95cdb6ee4ee |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/WifiHistoryRepositoryRoomTest.kt | b1445df92fe163850697bd77168ef8bc37487e264711be0256540a44c2c9f791 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/CellHistoryRepositoryRoomTest.kt | ab16c6fd0255bb4ca3ed4811b5f736e416ff112670912b0ed2aba670912aca3f |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/WifiSourceRuntimeTest.kt | 0afb13211183d19fe10bb3fd2e5df610e94f4e8e21b67671c7cc27163c04c916 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/CellSourceRuntimeTest.kt | c1e9c0a8d4bab70f2048f8b02d76f9f555367db5c56ab4eff6d2e77d03abe139 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/RadioActiveRequestAllowanceTest.kt | 729c7b0d8e036692eb3f6a3440af9b24a9700943503740b16ba08670c82bad61 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomRetainedAmbientRadioContextTest.kt | bc6794bf3da5a3df5e2392fb9a7098ec3f5cd9beba70a0f0b9cf81be3047cb9d |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomAmbientRadioPortableTransferTest.kt | 9753583de8395ed27da95862028d71726dc993b4f8dc40aa16b21db5752869b8 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/importer/file/ImportedRadioChildAttachmentRoomTest.kt | 899b798ef4eb0973452b3419c8108a254faa87d8684fee769fa05a915a52cb8b |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/CapturedRadioDateSelectionTest.kt | e864017a034fceaa7c71cd0df44d69b731d9e01c4bfb1a5441020383db4ca106 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/portable/RadioSourceFileJsonV2CodecTest.kt | 59e9f38c8c84bd2a51c354d05a0a3991a570037509a17f31741f70d50e9f9982 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/portable/RadioSourceFileSerializationCompatibilityTest.kt | e68282850ec9eb360cac770f5819cb8d6b825dac3d52d30a14c928515463a3dd |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/lifecycle/SourceLifecycleProofCoordinatorTest.kt | c3bfccb0afaf0a3910436552e2c3bec05ec494930c088612489c63fd593c9452 |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/SourceFactQueryRepositoryRoomTest.kt | 98cea70d410d42337717c8572e2abcd375b71b4e4c99f55b65c3009c60161a80 |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/RoomAllDaySourceStatusQuery.kt | a4b41c22040b8fd918417daa0b90d1aaac47763591875c541d22755a9c84efab |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/RoomAllDaySourceStatusQueryTest.kt | 615a41c14460bc1f9fc77c64b2cc0b9f56f704c517af0ddff7aae20f28a931b2 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedLocationSourceControllerTest.kt | 56eebd399547b73d9418dc29fc648a92b2dc2aaaafa7df153110e04940b668c9 |
| docs/tracking-infrastructure/convergence/2026-10-03/ui-location-paused-selection/before/tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedLocationSourceControllerTest.kt | e55300de92821bd4fc3d86b7d0c8efa90ff067e90df55e1f240b4aa4861634e0 |
| docs/tracking-infrastructure/convergence/2026-10-03/ui-location-paused-selection/after/tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedLocationSourceControllerTest.kt | 509f1119ad808c66400dced0c8e17a39a8963553619e680d9be9a6d71bf693cb |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedAmbientRadioSourceControllerTest.kt | 11f2f4fd38f264fad0184740d062688ab20dd62f442c205a6d3b26c8838c3fdd |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedStepSourceControllerTest.kt | 6fff79fee01e40b34f82cfc55f67796c4c2e77d1b9d9c4c74f33f0125e80480e |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/SharedUiLocationProviderTest.kt | 8e1c11de9707084a5da822b2dfd11b8d2096e56a3a59db956ea157aa734a41a6 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/ExactSourceCallerGuardTest.kt | dbf3de14be28d029b83fa7ac79f83cda4faa0f9c0bb449502cf635a2d0268c20 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/runtime/GuardedSourceCallerDemandDispatcherTest.kt | fa10a83c98496fa90ed5986158b0e3a8c84d586947f9466c896fbfd4d46fa29e |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/FiveLevelSourceConfigurationTest.kt | 5dfedd532e36d5823129b3395ef0b62946e4042dfbfe93ac0afabc25c81c55d0 |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePlanResolverTest.kt | 205da8cae496bc05e72068267a701263f4e4a0032bbcc2311e316ba5d1068a5d |
| tracker/engine/src/test/java/com/adsamcik/tracker/tracker/source/coordinator/SourcePipelineRecoveryTest.kt | 262ad2a39abee44174b30caa2230735aed3f36fd87f60efc4c4b61916b20ce85 |
| app/src/test/java/com/adsamcik/tracker/app/startup/LegacyReleasedDatabaseUpgradeIntegrationTest.kt | bfde57c0b83a3d62b7193f5e5d6fefaece3187c138948b78758cfd3370c87c5b |
| core/base/src/test/java/com/adsamcik/tracker/shared/base/database/FinalTrackingAssemblyMigrationTest.kt | c5429c8683c67d6f52a3fbe91c88fe0bc71059d783e2d1ba7b3880cef11a1f72 |
| core/base/src/test/java/com/adsamcik/tracker/shared/base/database/RadioObservationChildMigrationTest.kt | dde47acf269d92da1b9fba77cb4bc1aae818b44783f6dda43a085cad109745d4 |
| app/src/test/java/com/adsamcik/tracker/app/settings/CollectedDataDeletionServiceTest.kt | 550cfbcba136a7d813553e17ff6c5459575d12977a3574bcfd52abe7a9203a27 |
| core/base/src/test/java/com/adsamcik/tracker/shared/base/database/TrackingSourceFullClearAssemblyTest.kt | 8d20113fe25567ed288ed4a0725ced1c18ef63ffdcf2a18efe86a79c47e7e617 |
| app/src/test/java/com/adsamcik/tracker/app/settings/OwnedTransferFilesTest.kt | 8fb56b72bc753b1d2be6e8b00dc3d9e3417440064ae1dc158d9af4dfaf2aac20 |
| docs/tracking-infrastructure/convergence/2026-10-03/transfer-clear-selection/before/app/src/test/java/com/adsamcik/tracker/app/settings/OwnedTransferFilesTest.kt | 691f12aebd945cfd74287e4ab55d68a314b82aaba7780dbd3fc4aa2fe59c2529 |
| docs/tracking-infrastructure/convergence/2026-10-03/transfer-clear-selection/after/app/src/test/java/com/adsamcik/tracker/app/settings/OwnedTransferFilesTest.kt | 8fb56b72bc753b1d2be6e8b00dc3d9e3417440064ae1dc158d9af4dfaf2aac20 |
| docs/tracking-infrastructure/convergence/2026-10-03/transfer-clear-selection-pre-cleanup-repair/after/app/src/test/java/com/adsamcik/tracker/app/settings/OwnedTransferFilesTest.kt | 8fb56b72bc753b1d2be6e8b00dc3d9e3417440064ae1dc158d9af4dfaf2aac20 |
| docs/tracking-infrastructure/convergence/2026-10-03/transfer-clear-selection-pre-cleanup-repair/before/app/src/test/java/com/adsamcik/tracker/app/settings/OwnedTransferFilesTest.kt | 691f12aebd945cfd74287e4ab55d68a314b82aaba7780dbd3fc4aa2fe59c2529 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/activity/ManualExportTransferBoundaryTest.kt | 7e1b86982a92eeef1c05b83e70303960e811d178ce905d737c622093a0cf62dd |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/activity/UnfinishedExportRecoveryTest.kt | df13275b812ecee1b0b30eaf0b0454559d3f462ac55391c37dd5500eafaf333f |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/exporter/automation/ScheduledUnfinishedExportRecoveryTest.kt | e3215adda6cebddc6ade3e0e3c827e5f4671c1e22cade61d9cbca2d32bebebb9 |
| feature/import-export/src/main/java/com/adsamcik/tracker/impexp/backup/LogicalDataBackupImporter.kt | 5457208f6e5f6eb8216248b9213b83274198c762bc90af7f24bcc69842a264e8 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/backup/LogicalDataBackupImporterRoomTest.kt | f9866d262c6ce7500a7e9710c90516b92bfe776fb6cc417c6ebfbfc3dfa93c89 |
| feature/import-export/src/test/java/com/adsamcik/tracker/impexp/backup/DataBackupArchiveRoundTripTest.kt | 2649162043d48eb29e1b23a0bbabecc11e1363c50442691ea8d6e943ba7947cf |
| app/src/test/java/com/adsamcik/tracker/app/settings/DataBackupRestoreRecoveryTest.kt | 053564eb200e49f2b21c8f1844aeeb26a6b6200075f4aced399f6100f13aee5f |
| stats/data/src/test/java/com/adsamcik/tracker/stats/data/repository/UnifiedMeasuredHistoryAssemblyRoomTest.kt | e13bcbcd7069878517d8b514735412c623b918cc633da4d528cd2fc2a028c24c |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/DefaultTrackingHistoryRepository.kt | 65a479162426a72bd76524e7e33ca4ea80f796c4f98280e1878f0a013a4cf702 |
| feature/statistics/src/main/java/com/adsamcik/tracker/statistics/presenter/SourceHistoryDetailActions.kt | dd2a61dd486664c133653f6f1daf980cfd6ff22ba1501ab628ca93c053d28433 |
| feature/statistics/src/test/java/com/adsamcik/tracker/statistics/ui/SourceOnlySavedReadingsComposeTest.kt | d2fe8232e53818f4034d42ea6e71279b0bf5aa57583d4ea173737233b1d472a1 |
| feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/data/DashboardHistoryRepository.kt | 32b2a0069e6442779a5db76ee8da2c1fe0242072fa71f4e98d9bad12052547b8 |
| feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/data/DashboardHistoryRepositoryTest.kt | 403b3fb8985b4d64e23a55fd51034f1919d243ab746bd9f68eb6d34700773ddc |
| feature/dashboard/src/main/java/com/adsamcik/tracker/dashboard/ui/compose/cards/RecentTripsCard.kt | d2467c65a8ca273dbff8e14e90868dc98416fb8cde11db3a3ea3f436283047e6 |
| feature/dashboard/src/test/java/com/adsamcik/tracker/dashboard/ui/compose/cards/RecentTripsCardTest.kt | 7e706630a4f5acda9b452530a2cfd3dfd9e5c4c67ad53551ba308fb9c315eb31 |
| tracker/api-module/src/test/java/com/adsamcik/tracker/tracker/api/ManualTrackingStartTest.kt | 6420d930b37d13c33a9fbb7cf24a421d3876232f378414fa098f4939148adb73 |
| stats/data/src/main/java/com/adsamcik/tracker/stats/data/repository/SavedSessionRewardReader.kt | 4408da6ebe0fcfa8554837a46c1063fa2d204aa937924b111c81b0b0b415f43a |
| domain/points/src/main/java/com/adsamcik/tracker/points/work/PointsWorker.kt | 144faaf67446194bdf2defb8bac98e47aef2d8ba37473a2d39a7c2631dbb5556 |
| domain/points/src/test/java/com/adsamcik/tracker/points/work/PointsWorkerScoringBoundaryTest.kt | 59b5e4ddc12f180110112121f4927830e732106a86dbc3a9838c5945f13cb6e1 |
| feature/game/src/main/java/com/adsamcik/tracker/game/progression/PlayerProgressionRepository.kt | 09ff3c5df8b176e8302aafff5fca350184214099ace522655306239725c47c6c |
| feature/game/src/test/java/com/adsamcik/tracker/game/progression/PlayerProgressionRepositoryTest.kt | f2ece6ca37b3689a0b0ea636c70ef84c76a87b734406a16c9ae54e50e60b0cd0 |
| feature/game/src/main/java/com/adsamcik/tracker/game/GameModuleInitializer.kt | 4cb76ef1f5ce6384b743da75bfe141b4ae65f3b146d5b6a57b56cf8d18bf6aad |
| app/src/test/java/com/adsamcik/tracker/app/architecture/ArchitecturalFitnessTest.kt | cba1c9b7ca7d275f876b54aad10b3e8f08ca10b435ff12e5cf73df0a0feed996 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/SourceEventStorageMaintenance.kt | 1d02bd22989d975256f7d579c6cc97b08ead2001f2de12279b30492071fd645f |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/SourceEventWalDao.kt | 84d4ca86abc197f7cbd8bb12e58d0de695cecb8f67e6952250a3f019273b4504 |
| core/base/src/main/java/com/adsamcik/tracker/shared/base/database/dao/SourceProjectionStateDao.kt | 6cc224a4748015ee45473f13ff35499947370829a5e3fc7e66057ec53aa3ca5d |
| tracker/engine/src/main/java/com/adsamcik/tracker/tracker/source/activity/ActivityCapturedFactProjectionLane.kt | 83dee5347f84d0157f54f3502e90fbb2d9c6e272a844c9c13007fb8924ffd891 |
