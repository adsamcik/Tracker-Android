package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailHandoff
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailPresenter
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailState
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailUnavailableReason
import com.adsamcik.tracker.statistics.presenter.SourceHistoryDetailViewModel
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryQuery
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import androidx.lifecycle.SavedStateHandle
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceHistoryDetailRouteComposeTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `route teardown expires loaded Activity before same ViewModel is reused`() {
		val selection = activitySelection()
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter(),
			savedStateHandle = SavedStateHandle(
				mapOf("selectionToken" to route.selectionToken),
			),
		)
		val showRoute = mutableStateOf(true)
		composeRule.setContent {
			MaterialTheme {
				if (showRoute.value) {
					SourceHistoryDetailRoute(
						onBack = {},
						viewModel = viewModel,
					)
				}
			}
		}

		composeRule.onNodeWithText(text(R.string.trip_detail_activity_session))
			.assertIsDisplayed()
		composeRule.runOnIdle { showRoute.value = false }
		composeRule.runOnIdle {
			viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.SELECTION_EXPIRED,
				source = HistorySource.ACTIVITY,
			)
			showRoute.value = true
		}

		composeRule.onNodeWithText(text(R.string.source_history_detail_unavailable))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_activity_session))
			.assertDoesNotExist()
	}

	@Test
	fun `snapshot unavailable hides Retry because destination ownership is relinquished`() {
		val selection = activitySelection(readSnapshot = null)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter(),
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		setRoute(viewModel)

		composeRule.onNodeWithText(
			text(R.string.source_history_detail_snapshot_unavailable),
			substring = true,
		).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_retry)).assertDoesNotExist()
		composeRule.runOnIdle {
			viewModel.retry()
			viewModel.state.value shouldBe SourceHistoryDetailState.Unavailable(
				reason = SourceHistoryDetailUnavailableReason.SNAPSHOT_UNAVAILABLE,
				source = HistorySource.ACTIVITY,
			)
		}
		viewModel.close()
	}

	@Test
	fun `Wi-Fi stale selection hides Retry and cannot issue a second lookup`() {
		val entry = wifiEntry()
		val selection = SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.WifiOnly(entry),
			readSnapshot = TrackingHistoryReadSnapshot(8L, 12L),
		)
		val wifiRepository = mockk<WifiHistoryRepository>()
		coEvery {
			wifiRepository.lookup(requireNotNull(entry.selection))
		} returns WifiHistoryQuery.Failed(WifiHistoryCause.STALE_SELECTION)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = presenter(wifiRepository),
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		setRoute(viewModel)

		composeRule.onNodeWithText(
			text(R.string.source_history_detail_changed),
			substring = true,
		).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_retry)).assertDoesNotExist()
		composeRule.runOnIdle { viewModel.retry() }
		coVerify(exactly = 1) {
			wifiRepository.lookup(requireNotNull(entry.selection))
		}
		viewModel.close()
	}

	@Test
	fun `selection changed hides Retry and does not requery stale Activity authority`() {
		val selection = activitySelection()
		val stalePresenter = spyk(presenter())
		coEvery { stalePresenter.load(selection) } returns SourceHistoryDetailState.Unavailable(
			reason = SourceHistoryDetailUnavailableReason.SELECTION_CHANGED,
			source = HistorySource.ACTIVITY,
		)
		val route = SourceHistoryDetailHandoff.register(selection)
		val viewModel = SourceHistoryDetailViewModel(
			presenter = stalePresenter,
			savedStateHandle = SavedStateHandle(mapOf("selectionToken" to route.selectionToken)),
		)
		setRoute(viewModel)

		composeRule.onNodeWithText(
			text(R.string.source_history_detail_changed),
			substring = true,
		).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_retry)).assertDoesNotExist()
		composeRule.runOnIdle { viewModel.retry() }
		coVerify(exactly = 1) { stalePresenter.load(selection) }
		viewModel.close()
	}

	@Test
	fun `retryable presenter failure shows Retry and reuses the owned Activity selection once`() {
		val selection = activitySelection()
		val retryingPresenter = spyk(presenter())
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
		setRoute(viewModel)

		composeRule.onNodeWithText(
			text(R.string.source_history_detail_retryable),
			substring = true,
		).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_retry))
			.assertIsDisplayed()
			.performClick()

		composeRule.onNodeWithText(text(R.string.trip_detail_activity_session))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_retry)).assertDoesNotExist()
		coVerify(exactly = 2) { retryingPresenter.load(selection) }
		viewModel.close()
	}

	private fun setRoute(viewModel: SourceHistoryDetailViewModel) {
		composeRule.setContent {
			MaterialTheme {
				SourceHistoryDetailRoute(
					onBack = {},
					viewModel = viewModel,
				)
			}
		}
	}

	private fun presenter(
		wifiHistoryRepository: WifiHistoryRepository = mockk(),
	) = SourceHistoryDetailPresenter(
		wifiHistoryRepository = wifiHistoryRepository,
		cellHistoryRepository = mockk<CellHistoryRepository>(),
	)

	private fun wifiEntry() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi-route"),
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

	private fun activitySelection(
		readSnapshot: TrackingHistoryReadSnapshot? = TrackingHistoryReadSnapshot(7L, 11L),
	): SourceHistoryDetailSelection {
		val activity = ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity-route"),
			startTime = EpochMs(1_000L),
			endTime = EpochMs(2_000L),
			storedZoneIds = emptySet(),
			state = ActivityHistoryProductState.UNAVAILABLE,
			coverage = ActivityHistoryCoverage.NONE,
			activeTime = null,
			fragments = emptyList(),
			causes = setOf(ActivityHistoryCause.SOURCE_NOT_CAPTURED),
			capturesOnlyActivity = true,
		)
		return SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.ActivityOnly(activity),
			readSnapshot = readSnapshot,
		)
	}

	private fun text(resource: Int): String =
		RuntimeEnvironment.getApplication().getString(resource)
}
