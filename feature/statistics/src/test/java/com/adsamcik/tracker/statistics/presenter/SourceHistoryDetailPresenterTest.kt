package com.adsamcik.tracker.statistics.presenter

import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
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
		val entry = (selection.entry as SourceAwareHistoryPageEntry.ActivityOnly).history
		val route = SourceHistoryDetailHandoff.register(selection, nowElapsedRealtimeNanos = 10L)

		SourceHistoryDetailHandoff.consume(
			route.selectionToken,
			nowElapsedRealtimeNanos = 11L,
		) shouldBe selection
		SourceHistoryDetailHandoff.consume(
			route.selectionToken,
			nowElapsedRealtimeNanos = 12L,
		) shouldBe null
		selection.actionTarget shouldBe
			com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget.Activity(
				entry.key,
				ActivityHistoryOrigin.IMPORTED,
			)
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
		advanceUntilIdle()
		first.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		val recreated = SourceHistoryDetailViewModel(
			presenter = presenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		advanceUntilIdle()

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
		advanceUntilIdle()
		viewModel.state.value.shouldBeInstanceOf<SourceHistoryDetailState.Loaded>()

		viewModel.retry()
		advanceUntilIdle()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			source = com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
		)
		collector.cancel()
		viewModel.close()
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

		viewModel.close()
		viewModel.retry()
		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = null,
		)
		gate.complete(Unit)
		advanceUntilIdle()

		viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
			source = null,
		)
	}

	private fun importedActivitySelection(key: String): SourceHistoryDetailSelection {
		val entry = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey(key),
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
