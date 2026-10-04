# Approved History range/day backend source selection

2026-10-04. Phase **IMPLEMENTATION_ONLY**. Status **IMPLEMENTED_UNVALIDATED**.
Immutable exported base: `c053ec16d4c2fc2cdf6609331de98c0d00cce2e6`.

## Exact scope

`FILES.json` selects 30 changed paths: 23 production paths, six test classes and one shared Room fixture. `SELECTED/` contains exact HEAD-based bytes; `SELECTION.patch` contains only those deltas. Root's original `BASE/`, `WORKING_BEFORE/`, `HEAD_BASE.json` and `HEAD_BASE_TEST_COMPANIONS.json` remain intact. The prior September30 and measured-day packets remain intact. Ten exported paths are dependency-only and are not patch additions. The committed common UnifiedHistory contract is an additional semantic dependency; its current LF hash equals the previously reviewed committed contract (only CRLF differs).

This assembles the already approved bounded range/day History architecture. It does not select a new source policy, table, schema, identity, format or UI. No production file was rewritten during assembly; root owns staging and coherent source commits.

## Production trace

- DefaultTrackingHistoryRepository delegates range reads/Flow/PagingSource to one UnifiedHistoryRangeReader; the same singleton supplies TrackingHistoryRepository and PagedTrackingHistoryRepository. Existing module dependencies include Room, coroutines, Paging and Hilt. No new dependency or cross-feature edge.
- The range reader discovers at most100 source keys plus a true lookahead, merges exact stable continuation keys, and qualifies bounded payloads in one Room snapshot on injected IO. Cancellation propagates. Source-local storage/integrity/overflow failures remain explicit without discarding a valid sibling. Active retention settlement/current epoch failures refuse the shared snapshot.
- Captured source bridges use exact selected owners/import identities. Activity's committed nativePortableOwnerSeeds lookup and complete retained window/deletion-scope checks are preserved; exact typed comparison uses selected native segments, never date discovery/global census. Its existing range callers are adapted to the committed composed-entry signature. Pressure uses committed exact requested entry/run/window owner qualification and the shared comparison loader. The old unused global Pressure census/property deltas are excluded.
- The NativeReader filters saved Location queries and receipt dependency work to the requested measured range before budgets. Unknown/older/imported saved points remain visible with honest coverage; optional enrichment proof absence does not remove ordinary saved points. Linked and retrospective intervals select exact points, preserve complete source metadata and mark crossing whole aggregates uncertain rather than prorating them.
- Sessionless Location and radio discovery uses the displayed local calendar date of actual saved measurements; sparse day pagination skips entire displayed days without scanning each reading. Radio source records keep their original fact IDs, stored zone, complete child groups, real coverage and gap intervals; each display section has an observation window selecting its actual children. Positive-duration gaps use half-open overlap; impossible zero-length stored ambient gaps were ruled out by existing contracts, so no gap predicate/schema expansion is selected.
- Steps' real structural aggregate days remain whole. Exact imported day selectors use existing authenticated retained readers and original IDs/epoch; they never borrow a neighboring date. Imported captured Steps preserve their original entry header after surviving-member deletion; this is a containment contract repair, not invented bounds.
- Organization selectors use exact imported source membership. Location archive selection never substitutes a same-time point and honors saved descriptive SESSION/BACKGROUND/BOTH/UNKNOWN labels. Shared bounded interval contents conservatively report dependency overflow whenever continuation exists.
- Flow/Paging invalidation retains existing source, child, correction/deletion, retention, annotation and Location archive dependencies. Paging remains forward-only and refreshes the requested range; no unapproved anchor mechanism.

## Narrow mixed-path dispositions

- DefaultCellHistoryRepository: select only the exact imported range bridge. Its child-detail helper is already committed; no held export candidate query enters this slice.
- LocationPurposeDao: select LocationHistoryDayRow and three descriptive History query methods; exclude latestRegistrationPlan.
- LogicalTrackingHistoryReader: select only bounded logical-ID selected-members overload.
- ImportedAmbientStepsDao and DefaultAmbientStepsHistoryRepository: select only the preserved R11_EXACT_STEPS_DAY.patch. No recent/cursor/session-number drafts.
- StatsDataModule: select only PagedTrackingHistoryRepository binding.
- ImportedPressureDao: include the already-authored bounded selectedHistoryCandidates query required by exact selected source readers/exports. Shared History query semantics remain unchanged.
- PressureHistorySelector and PressureHistoryReadModel: committed dependencies only; exclude obsolete discoverPressureCaptureDuplicateAuthority/hasPressureCaptureIntent.
- RoomImportedAmbientStepsTransferTest: only three exact-day/DST cases. Other Steps recent tests/helpers remain preserved and excluded.

## Authored scenarios; no execution

35 authored or revised scenarios are selected: six displayed-day Location cases; fifteen bounded range/organization/paging cases; eight composed measured-day Location/radio cases; two imported captured Steps Room cases; three exact imported Steps day/DST Room cases; one revised original-header containment contract case. `FILES.json.testInventory` names every selected case. Seven previously committed candidate/continuation Room cases remain a dependency, not new authored evidence.

The composed cases use actual AppDatabase and the production range reader/source-owned saved/imported readers; unused capture sources are absent/mocked in the shared fixture. The unavailable Wi-Fi/proof cases deliberately inject a source failure. This does not prove every six-source subset, native provider delivery, all lifecycle/recovery edges or all correction effects.

## Explicit remaining work

This is a backend prerequisite, not full History completion. Main History still uses the legacy trip pager; calendar/timeline/details/actions, global and per-row problem presentation and full source selection need integration. PagingSource rows do not carry Page.problems. Activity/Pressure/captured-radio measurement-only subsets and summary-only presentation remain open; crossing whole products are currently withheld honestly. Arbitrary-window exact Steps counts and gap-only day discovery remain open. Background radio overlapping-file alias membership and native Steps duplicate-file membership remain explicitly unavailable rather than widened by dates. Location Replace representation is separately pending. All six-source subsets and all25 full chains remain open.

No compilation, Hilt/Room/schema checks, tests, lint, device/UI/provider activation, lifecycle/battery run, CI, integration or Git mutation was performed. An authored case is not a pass. Fresh independent review and the final convergence batch remain required.

## H1 repair

The first frozen selection and rejected review are preserved unchanged. Eight fixture points in two organization-range scenarios are now explicitly legacy (imported=false), matching their requested LEGACY+ORGANIZATION origins. The production import-exclusion rule and all other selected source bytes are unchanged. Seven test-source files contain the previously documented 34 new scenarios plus one revised contract; none was executed. This is a corrected implementation-only source input, not an assembly freeze or gate pass.
