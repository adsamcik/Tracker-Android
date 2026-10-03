# Saved-point longest trip — independent source review

2026-10-03. **IMPLEMENTATION_ONLY / IMPLEMENTED_UNVALIDATED.** Reviewed the frozen `LONGEST_TRIP_SELECTED` packet over `80eb0b0cbe748971b256524e776b2aa3c87b4b40`, original requirements and approved whole saved-Location distance by completed trip's original start. Mixed DAO/scope review used the selected before/after snapshots, not the held R11 drafts. No production edits, Git operations, gates or tests were executed.

## Concrete findings

1. **Constructor companion is missing.** `DefaultWindowedMetricsProvider.kt:40` adds required `SavedTripDistanceReader`, but `WindowedDiscoveryStreakRoomTest.kt:36–38` still constructs the production provider with its old six arguments. This actual existing Room fixture is not included in the ten-path selection, so the selected source set is incomplete. Supply the real reader with the same database/test dispatchers in that fixture and select only that companion delta.
2. **Real saved legacy Location can be excluded by unrelated metadata.** Selected `SessionSegmentDao.kt.after:41` requires `segment.sample_count > 0` despite already probing actual saved Location rows. `SessionSegment.kt` expressly defines that count as source-neutral processing-cycle metadata, not Location evidence. A legacy/imported segment with zero metadata count and two valid saved points returns no candidate. Remove that predicate from the new query and author a real Room case with zero count and saved points.
3. **Exact imported-epoch branch lacks authored coverage.** The legacy reader correctly requires `HISTORY_CURRENT_IMPORT_COPY` when a point has archive membership (`LocationPurposeDao.kt.after:55–56`). The claimed imported Room case at `SavedTripDistanceReaderRoomTest.kt:96–105` only inserts `LocationFileIdentityEntity`, so it never exercises the archive/identity/current-epoch join. Add a focused real archive and declared membership fixture showing an old-epoch copy is withheld and an independently matching current-epoch archive copy survives. This is a source-test gap, not a discovered defect in that predicate.

The first two findings were sent immediately to the coordinator. This receipt records the original freeze; repairs require affected-source recheck before source acceptance.

## Trace and limits

The provider routes All time, closed Interval and nonnegative Rolling windows to the new saved reader and withholds bounded seasons instead of substituting a lifetime mask (`DefaultWindowedMetricsProvider.kt:47–61`). Existing kilometre integer units remain unchanged. The Hilt binding already exists in `StatsDataModule.kt:157–161`; the new reader has an injectable constructor using existing Room and dispatcher dependencies.

`SavedTripDistanceReader.kt:37–83` reads one Room snapshot, discovers original-start candidates with stable 64-row keyset pages, traverses all physical replacement members and reads Location with stable 256-row `(time,id)` pages. There is no total history/point cutoff. Its 257-row member probes expose, rather than silently clip, the existing 256-member budget. Raw reverse membership union plus run/segment correspondence at `:94–112` catches orphan/mismatched members; native reads require the exact purpose/run/segment and current native epoch/high-water. Scope erase fences remove only that source's capture contribution (`:114–138`), not independently saved siblings. Existing retained-floor settlement and clipped trip starts produce typed unavailability. Legacy/imported intervals retain their explicitly weaker compatibility semantics and do not borrow receipt-bearing native/ambient points.

The accumulator at `:187–215` uses current saved coordinates, resets on invalid coordinates, preserves anchors across page boundaries, and never connects native registration/sequence/clock/elapsed gaps or unkeyed reversed/over-two-minute gaps. Only a real measured pair establishes a number, including stationary zero. Missing pairs remain `NotFound`; dependency errors remain typed; cancellation is rethrown. Correction/deletion is read afresh without a cache. It does not start tracking, grant collection or authenticate user-edited backup payloads.

Exactly **11 actual Room methods**, four pure accumulator methods and five new provider methods are authored and unexecuted. Their fixtures cover the production read paths, not actual provider capture, lifecycle completion, schema migration, Hilt generation or device performance. Long All-time transaction duration/writer contention remains unmeasured. Existing no-Location and active sibling cases remain separate from completion/membership errors.

No production consumer of `WindowedMetricsProvider` was found beyond its binding; `SummaryRoute` still obtains `SessionStatsRepository` through its presenter. This backend slice cannot close period Statistics UI integration, metrics effects/retraction or any of the 25 full-chain prerequisites. Pending Location Replace must eventually feed the same effective saved-point seam; this slice introduces no replacement representation or dated exploration storage.

## Original exact input pins

| Input | SHA-256 |
| --- | --- |
| `LONGEST_TRIP_FILES.json` | `6e6eb317fafedd08891f6a38249bf01c416de30447bea202fc2ca56c4ffd6a58` |
| `LONGEST_TRIP_SELECTION.patch` | `23692ff1a49885998fef59a70344953f456549a8723dcddfd4a92f061b6a2393` |
| Selected `SourceSessionDao.kt.after` | `c4497a24b427c67c02c760df82e6ba8c4502aab575e4f1a7c6ba1a158e417bd9` |
| Selected `SessionSegmentDao.kt.after` | `e213c0fb3bdf51b7992e404424b63abdbe3c4a9bf8a30783f257b58691fb9cb6` |
| Selected `LocationPurposeDao.kt.after` | `ffb75b2a38434a808bb5f7a60c440b86a0683582b9484b1f09d5650baef7ff24` |
| Selected `LocationReadScope.kt.after` | `f87f4c6fe765dd9d95129292c340a690b5bdac96f10f7a0854285446948e3554` |
| Actual `WindowedMetricsProvider.kt` | `f08801855be4d186f54efbf30772dae201a3cce2dab4efd42d72268b414c5acc` |
| Actual `DefaultWindowedMetricsProvider.kt` | `20a13a1e3fef15ba44a5c58fb2e58f27c3a40f1557b34d8b92eb32b322b68c13` |
| Actual `DefaultWindowedMetricsProviderTest.kt` | `f98c334fe03296a87b01ec4d8c58ab39fedbbb65af9288fe5dbca49a913508f6` |
| `SavedTripDistanceReader.kt` | `38b7466823ffdd187b851569b76fbef386144a009ef68afa4b5bb4470126677d` |
| `SavedTripDistanceReaderRoomTest.kt` | `7e9308634a6833c9f8ca6f5f3f8397ddc06c5e36134d2f4f85f968a223fc6e93` |
| `SavedTripDistanceAccumulatorTest.kt` | `f81c28e5acd139389de59b73a9022291eb6559a6c50993e375434402a5892a8b` |

Actual owned-file and selected mixed-snapshot hashes were read directly and match the original manifest's respective fields. Selected patch hashes for the three owned modified files differ from actual hashes as recorded in that manifest; no whole dirty DAO or held R11 reader was accepted.

## Repaired eleven-path freeze — final static closure

2026-10-03. Independently rechecked all three findings and the narrow added saved-point query. **All three findings are closed in source; no concrete defect remains in this selected slice.** This supersedes the original ten-path acceptance hold and 11-Room-method inventory above, while retaining its finding history.

- The existing streak Room fixture now supplies the real `SavedTripDistanceReader(database, dispatchers)` at `WindowedDiscoveryStreakRoomTest.kt:36–39`. The constructor search shows both existing direct construction sites receive the required dependency. Its unrelated streak scenarios remain unchanged.
- Selected `SessionSegmentDao.kt.after:39–47` no longer requires the source-neutral count. The new actual Room case at `SavedTripDistanceReaderRoomTest.kt:120–124` inserts a zero-count segment and two saved points, then asserts their measured distance.
- The new actual archive fixture at `:127–152` writes real portable identities, archive rows and declared membership. It asserts the original current epoch result, advances the retained-data epoch without pretending to physically clear the fixture, asserts `NotFound`, then adds a separate matching current-epoch archive copy and asserts the original distance again. This reaches the exact archive/identity/epoch branch instead of only the identity fallback.

The added descriptive ambient-purpose flag in `savedLogicalTripPointPage` (`LocationPurposeDao.kt.after:31–49`) does not change membership, continuity or numeric contributions. New `savedFileSamples` (`:65–75`) selects only explicitly supplied primary keys, returns each row once in stable `(time,id)` order, and reuses the same retained-floor/settlement and descriptive purpose flags as the committed `savedFileChunk` (`:142–162`). It never discovers a broader date interval, creates coordinates/identity/grants or starts tracking. Missing historical proof remains allowed for ordinary user data backups. The new actual query case at test `:213–238` checks dual-purpose labels, unchanged measured distance, duplicate requested IDs, an unrelated same-period point remaining excluded, and retained-floor exclusion. The future caller owns bounded ID batches and coherent snapshot admission; the separately authored session export consumer is outside this acceptance.

There are now exactly **14 new Room methods, four pure methods and five provider methods**, all unexecuted, plus the constructor-only existing streak fixture delta. No compilation, Hilt, Room/schema, rendering, device or performance result is claimed. The no-production-period-UI-caller and long snapshot duration limits remain open, as do all 25 full chains.

### Final exact pins

All eleven actual source hashes and the four selected mixed after snapshots were read directly and match the repaired manifest's respective fields. The manifest still separately records normalized selected hashes for the three whole owned modified files; review uses their unchanged actual contents plus the selected patch. Unrelated mixed DAO/History deltas remain excluded.

| Input | SHA-256 |
| --- | --- |
| `LONGEST_TRIP_FILES.json` | `658580e07b43d3486b1066c3093bcc9ce0cb52d2d2610b573f43a1082814b76c` |
| `LONGEST_TRIP_SELECTION.patch` | `f243043752f61134b335d6d8f46acb08dd79a3860c0503811085961a4bbdc75a` |
| `LONGEST_TRIP_SOURCE_BOUNDARY.md` | `c9829a3afc1269bd27843461e1c76c141987d506b3b390f859fe0525cc61ee37` |
| Selected `SourceSessionDao.kt.after` | `c4497a24b427c67c02c760df82e6ba8c4502aab575e4f1a7c6ba1a158e417bd9` |
| Selected `SessionSegmentDao.kt.after` | `e340284863e2dd951b72cd46022af1171b2478f9633ddab87c6eae6ec859a0ed` |
| Selected `LocationPurposeDao.kt.after` | `b7f6c2c755a7dac40c30efc192734142d264765962f5f3507ded24a151e9f417` |
| Selected `LocationReadScope.kt.after` | `f87f4c6fe765dd9d95129292c340a690b5bdac96f10f7a0854285446948e3554` |
| Actual `WindowedMetricsProvider.kt` | `f08801855be4d186f54efbf30772dae201a3cce2dab4efd42d72268b414c5acc` |
| Actual `DefaultWindowedMetricsProvider.kt` | `20a13a1e3fef15ba44a5c58fb2e58f27c3a40f1557b34d8b92eb32b322b68c13` |
| Actual `DefaultWindowedMetricsProviderTest.kt` | `f98c334fe03296a87b01ec4d8c58ab39fedbbb65af9288fe5dbca49a913508f6` |
| `SavedTripDistanceReader.kt` | `38b7466823ffdd187b851569b76fbef386144a009ef68afa4b5bb4470126677d` |
| `SavedTripDistanceReaderRoomTest.kt` | `cee99960e3e5880b5f6f25a86a13cbdcae37e5eecfa724f314175f236aa3daf5` |
| `SavedTripDistanceAccumulatorTest.kt` | `f81c28e5acd139389de59b73a9022291eb6559a6c50993e375434402a5892a8b` |
| `WindowedDiscoveryStreakRoomTest.kt` | `cea098c068ce50de5fed304460b9156bbaa9fb822c45f5f1fe8fede4be83e8d4` |
