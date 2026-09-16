package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryRunDeletionScope
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceHistoryDetailPresenterTest {
	private val dispatcher = StandardTestDispatcher()
	private val wifiRepository = mockk<WifiHistoryRepository>()
	private val cellRepository = mockk<CellHistoryRepository>()
	private val presenter = SourceHistoryDetailPresenter(wifiRepository, cellRepository)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(dispatcher)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	@Test
	fun `imported Wi-Fi selection round trips without local identity inference`() = runTest {
		val entry = importedWifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
		)
		coEvery {
			wifiRepository.lookup(requireNotNull(entry.selection))
		} returns WifiHistoryQuery.Found(entry)

		val result = presenter.load(selection).shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		result.selection shouldBe selection
		coVerify(exactly = 1) { wifiRepository.lookup(requireNotNull(entry.selection)) }
	}

	@Test
	fun `Wi-Fi stale selection maps to immutable selection changed`() = runTest {
		val entry = localWifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
		)
		coEvery {
			wifiRepository.lookup(requireNotNull(entry.selection))
		} returns WifiHistoryQuery.Failed(WifiHistoryCause.STALE_SELECTION)

		presenter.load(selection) shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		coVerify(exactly = 1) { wifiRepository.lookup(requireNotNull(entry.selection)) }
	}

	@Test
	fun `Wi-Fi stale selection relinquishes ownership and retry cannot lookup again`() = runTest {
		val entry = localWifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(8L, 12L),
		)
		coEvery {
			wifiRepository.lookup(requireNotNull(entry.selection))
		} returns WifiHistoryQuery.Failed(WifiHistoryCause.STALE_SELECTION)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		val unavailable = SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		viewModel.state.value shouldBe unavailable

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe unavailable
		coVerify(exactly = 1) { wifiRepository.lookup(requireNotNull(entry.selection)) }
		SourceHistoryDetailHandoff.consume(route.selectionToken) shouldBe null
		viewModel.close()
	}

	@Test
	fun `imported Cell selection round trips without physical ownership inference`() = runTest {
		val entry = importedCellEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.CellOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(8L, 12L),
		)
		coEvery {
			cellRepository.detail(requireNotNull(entry.selection))
		} returns CellHistoryQuery.Found(entry)

		val result = presenter.load(selection).shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		result.selection shouldBe selection
		coVerify(exactly = 1) { cellRepository.detail(requireNotNull(entry.selection)) }
	}

	@Test
	fun `imported Activity handoff preserves exact origin and action target`() {
		val selection = importedActivitySelection("activity-imported")
		val activityTarget = selection.actionTarget
			.shouldBeInstanceOf<TrackingHistoryActionTarget.Activity>()
		val exactSelection = activityTarget.selection
			.shouldBeInstanceOf<ActivityHistorySelection.Imported>()
			.selected
		val activityEntry = (selection.entry as SourceAwareHistoryPageEntry.ActivityOnly).history
		val route = SourceHistoryDetailHandoff.register(selection, nowElapsedRealtimeNanos = 10L)

		SourceHistoryDetailHandoff.consume(
			route.selectionToken,
			nowElapsedRealtimeNanos = 11L,
		) shouldBe selection
		SourceHistoryDetailHandoff.consume(
			route.selectionToken,
			nowElapsedRealtimeNanos = 12L,
		) shouldBe null
		exactSelection shouldBeSameInstanceAs requireNotNull(activityEntry.importedSelection)
		exactSelection.runDeletionScopes shouldBe listOf(
			ActivityImportedHistoryRunDeletionScope(
				runIdentity = ActivityImportedHistoryIdentity("b".repeat(64)),
				deletionScopeDigest =
					ActivityImportedHistoryDeletionScopeDigest("c".repeat(64)),
			),
		)
		exactSelection.windowIdentities shouldBe
			listOf(ActivityImportedHistoryIdentity("d".repeat(64)))
		exactSelection.readSnapshot shouldBe ActivityImportedHistoryReadSnapshot(9L, 14L)
	}

	@Test
	fun `pending handoff expires and explicit release prevents consumption`() {
		val expired = SourceHistoryDetailHandoff.register(
			importedActivitySelection("expired"),
			nowElapsedRealtimeNanos = 0L,
		)
		SourceHistoryDetailHandoff.consume(
			expired.selectionToken,
			nowElapsedRealtimeNanos = Long.MAX_VALUE / 2,
		) shouldBe null

		val released = SourceHistoryDetailHandoff.register(
			importedActivitySelection("released"),
			nowElapsedRealtimeNanos = 20L,
		)
		SourceHistoryDetailHandoff.release(released.selectionToken)
		SourceHistoryDetailHandoff.consume(
			released.selectionToken,
			nowElapsedRealtimeNanos = 21L,
		) shouldBe null
	}

	@Test
	fun `handoff eviction removes oldest unresolved owner`() {
		val routes = (0..32).map { index ->
			SourceHistoryDetailHandoff.register(
				importedActivitySelection("eviction-$index"),
				nowElapsedRealtimeNanos = 100L,
			)
		}

		SourceHistoryDetailHandoff.consume(
			routes.first().selectionToken,
			nowElapsedRealtimeNanos = 101L,
		) shouldBe null
		SourceHistoryDetailHandoff.consume(
			routes.last().selectionToken,
			nowElapsedRealtimeNanos = 101L,
		) shouldBe importedActivitySelection("eviction-32")
		routes.drop(1).dropLast(1).forEach { route ->
			SourceHistoryDetailHandoff.release(route.selectionToken)
		}
	}

	@Test
	fun `same-process Activity token recreation cannot redisplay corrected cached entry`() = runTest {
		val selection = importedActivitySelection("activity-recreation")
		val route = SourceHistoryDetailHandoff.register(selection)
		val first = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		first.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		val recreated = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()

		recreated.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = null,
		)
		first.retry()
		first.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		first.close()
		recreated.close()
	}

	@Test
	fun `destination-owned Activity selection expires without a permanent observer`() = runTest {
		val route = SourceHistoryDetailHandoff.register(
			importedActivitySelection("activity-owner-expiry"),
		)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		viewModel.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		advanceTimeBy(SourceHistoryDetailHandoff.DESTINATION_OWNERSHIP_TIMEOUT_MILLIS + 1L)
		runCurrent()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		viewModel.close()
	}

	@Test
	fun `Activity handoff without read snapshot is unavailable rather than cached`() = runTest {
		val selection = importedActivitySelection("activity-no-snapshot").copy(
			readSnapshot = null,
		)

		presenter.load(selection) shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
	}

	@Test
	fun `Activity snapshot unavailable relinquishes ownership and retry is a no-op`() = runTest {
		val selection = importedActivitySelection("activity-snapshot-vm").copy(
			readSnapshot = null,
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		val unavailable = SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		viewModel.state.value shouldBe unavailable

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe unavailable
		SourceHistoryDetailHandoff.consume(route.selectionToken) shouldBe null
		viewModel.close()
	}

	@Test
	fun `Activity selection changed relinquishes stale authority and cannot retry`() = runTest {
		val selection = importedActivitySelection("activity-selection-changed")
		val stalePresenter = spyk(presenter)
		coEvery { stalePresenter.load(selection) } returns SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = stalePresenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		val unavailable = SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		viewModel.state.value shouldBe unavailable

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe unavailable
		coVerify(exactly = 1) { stalePresenter.load(selection) }
		viewModel.close()
	}

	@Test
	fun `Activity retryable presenter failure retains one owner for a second attempt`() = runTest {
		val selection = importedActivitySelection("activity-retryable")
		val retryingPresenter = spyk(presenter)
		var attempts = 0
		coEvery { retryingPresenter.load(selection) } coAnswers {
			attempts += 1
			if (attempts == 1) {
				throw IllegalStateException("transient presenter failure")
			}
			SourceHistoryDetailState.Loaded(selection)
		}
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = retryingPresenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()
		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.RETRYABLE_FAILURE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
			canRetry = true,
		)

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe SourceHistoryDetailState.Loaded(selection)
		coVerify(exactly = 2) { retryingPresenter.load(selection) }
		SourceHistoryDetailHandoff.consume(route.selectionToken) shouldBe null

		advanceTimeBy(SourceHistoryDetailHandoff.DESTINATION_OWNERSHIP_TIMEOUT_MILLIS + 1L)
		runCurrent()
		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY,
		)
		viewModel.close()
	}

	@Test
	fun `retry clears previously loaded Wi-Fi when exact query fails`() = runTest {
		val entry = localWifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(9L, 13L),
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		coEvery { wifiRepository.lookup(requireNotNull(entry.selection)) } returnsMany listOf(
			WifiHistoryQuery.Found(entry),
			WifiHistoryQuery.Failed(WifiHistoryCause.MANIFEST_INTEGRITY_FAILED),
		)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		val collector = backgroundScope.launch { viewModel.state.collect() }
		runCurrent()
		viewModel.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
			canRetry = true,
		)
		collector.cancel()
		viewModel.close()
	}

	@Test
	fun `retry clears loaded Cell instead of rendering an origin conflict card`() = runTest {
		val entry = importedCellEntry()
		val conflict = entry.copy(
			state = CellHistoryProductState.UNVERIFIABLE,
			coverage = CellHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT),
		)
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.CellOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(10L, 14L),
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		coEvery { cellRepository.detail(requireNotNull(entry.selection)) } returnsMany listOf(
			CellHistoryQuery.Found(entry),
			CellHistoryQuery.Found(conflict),
		)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		val collector = backgroundScope.launch { viewModel.state.collect() }
		runCurrent()
		viewModel.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		viewModel.retry()
		runCurrent()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.CELL,
		)
		collector.cancel()
		viewModel.close()
	}

	@Test
	fun `Wi-Fi read budget failure stays typed and clears the selected detail`() = runTest {
		val entry = localWifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(10L, 15L),
		)
		coEvery {
			wifiRepository.lookup(requireNotNull(entry.selection))
		} returns WifiHistoryQuery.Failed(WifiHistoryCause.READ_BUDGET_EXCEEDED)

		presenter.load(selection) shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
			canRetry = true,
		)
	}

	@Test
	fun `late uncancellable lookup cannot overwrite expired retry state`() = runTest {
		val entry = localWifiEntry()
		val gate = CompletableDeferred<Unit>()
		val lateRepository = object : WifiHistoryRepository {
			override suspend fun session(segmentId: Long) = WifiHistoryQuery.NotFound
			override suspend fun imported(selection: WifiImportedHistorySelectionKey) =
				WifiHistoryQuery.NotFound
			override suspend fun lookup(
				selection: com.adsamcik.tracker.stats.api.repository.WifiHistorySelection,
			): WifiHistoryQuery = withContext(NonCancellable) {
				gate.await()
				WifiHistoryQuery.Found(entry)
			}
			override suspend fun recent(limit: Int) =
				com.adsamcik.tracker.stats.api.repository.WifiHistoryPage.Available(emptyList())
		}
		val latePresenter = SourceHistoryDetailPresenter(lateRepository, cellRepository)
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(11L, 15L),
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = latePresenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		runCurrent()

		advanceTimeBy(SourceHistoryDetailHandoff.DESTINATION_OWNERSHIP_TIMEOUT_MILLIS + 1L)
		runCurrent()
		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		viewModel.retry()
		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		gate.complete(Unit)
		advanceUntilIdle()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		viewModel.close()
	}

	private fun importedActivitySelection(key: String): SourceHistoryDetailSelection {
		val entryKey = ActivityHistoryEntryKey(key)
		val exactSelection = ActivityImportedHistorySelection(
			key = entryKey,
			identity = ActivityImportedHistoryIdentity("a".repeat(64)),
			importRevision = 3L,
			contentChecksum = ActivityImportedHistoryDigest("e".repeat(64)),
			runDeletionScopes = listOf(
				ActivityImportedHistoryRunDeletionScope(
					runIdentity = ActivityImportedHistoryIdentity("b".repeat(64)),
					deletionScopeDigest =
						ActivityImportedHistoryDeletionScopeDigest("c".repeat(64)),
				),
			),
			windowIdentities = listOf(ActivityImportedHistoryIdentity("d".repeat(64))),
			readSnapshot = ActivityImportedHistoryReadSnapshot(9L, 14L),
		)
		val entry = ActivityHistoryEntry(
			key = entryKey,
			startTime = EpochMs(1_000L),
			endTime = EpochMs(2_000L),
			storedZoneIds = emptySet(),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED),
			origin = ActivityHistoryOrigin.IMPORTED,
			capturesOnlyActivity = false,
			importedSelection = exactSelection,
		)
		return SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.ActivityOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(9L, 14L),
		)
	}

	private fun localWifiEntry() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi-local"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = WifiHistoryProductState.MATERIALIZING,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
		localSelection = WifiLocalHistorySelectionKey("a".repeat(64)),
		capturesOnlyWifi = true,
	)

	private fun importedWifiEntry(): WifiHistoryEntry {
		val selected = WifiImportedHistorySelection(
			key = WifiImportedHistorySelectionKey("b".repeat(64)),
			importRevision = 3L,
			contentChecksum = "c".repeat(64),
		)
		return WifiHistoryEntry(
			key = WifiHistoryEntryKey("wifi-imported"),
			startTime = EpochMs(1_000L),
			endTime = EpochMs(2_000L),
			storedZoneIds = setOf("UTC"),
			state = WifiHistoryProductState.MATERIALIZING,
			coverage = WifiHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
			origin = WifiHistoryOrigin.IMPORTED,
			importedSelection = selected,
		)
	}

	private fun importedCellEntry(): CellHistoryEntry {
		val selection = ImportedCellHistorySelection(
			identity = ImportedCellHistoryIdentity("d".repeat(64)),
			importRevision = 4L,
			contentChecksum = ImportedCellHistoryDigest("e".repeat(64)),
		)
		return CellHistoryEntry(
			key = CellHistoryEntryKey("cell-imported"),
			startTime = EpochMs(1_000L),
			endTime = EpochMs(2_000L),
			storedZoneIds = setOf("UTC"),
			state = CellHistoryProductState.MATERIALIZING,
			coverage = CellHistoryCoverage.NONE,
			observations = emptyList(),
			causes = setOf(CellHistoryCause.MATERIALIZATION_BEHIND),
			origin = CellHistoryOrigin.Imported(selection),
			selection = selection,
		)
	}
}
