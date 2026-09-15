package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.WifiHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import androidx.lifecycle.SavedStateHandle
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
			presenter = SourceHistoryDetailPresenter(
				wifiHistoryRepository = mockk<WifiHistoryRepository>(),
				cellHistoryRepository = mockk<CellHistoryRepository>(),
			),
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

	private fun activitySelection(): SourceHistoryDetailSelection {
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
			origin = ActivityHistoryOrigin.IMPORTED,
			capturesOnlyActivity = false,
		)
		return SourceHistoryDetailSelection(
			entry = SourceAwareHistoryPageEntry.ActivityOnly(activity),
			readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
		)
	}

	private fun text(resource: Int): String =
		RuntimeEnvironment.getApplication().getString(resource)
}
