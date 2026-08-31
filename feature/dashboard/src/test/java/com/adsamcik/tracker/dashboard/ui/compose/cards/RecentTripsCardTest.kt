package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentTripsCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyHistoryShowsNeutralEmptyMessage() {
		setContent(DashboardRecentHistoryState.Content(emptyList()))

		composeRule.onNodeWithText("Recent Tracking").assertIsDisplayed()
		composeRule.onNodeWithText(
			"No recent tracking sessions. Start tracking to see them here.",
		).assertIsDisplayed()
	}

	@Test
	fun loadingIsRenderedDistinctly() {
		setContent(DashboardRecentHistoryState.Loading)
		composeRule.onNodeWithText("Loading recent tracking…").assertIsDisplayed()
	}

	@Test
	fun unavailableIsRenderedDistinctly() {
		setContent(DashboardRecentHistoryState.Unavailable)
		composeRule.onNodeWithText("Recent tracking is unavailable").assertIsDisplayed()
	}

	@Test
	fun physicalRowPreservesMetricsAndClickIdentity() {
		var clickedId: Long? = null
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(DashboardRecentHistoryEntry.Physical(trip(42L))),
			),
			onTripClick = { clickedId = it },
		)

		composeRule.onNodeWithText("km", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("30 m", substring = true).assertIsDisplayed()
		composeRule.onNodeWithContentDescription("View details").performClick()
		clickedId shouldBe 42L
	}

	@Test
	fun allStepsStatesAreNonnumericNonclickableAndOpaqueKeysDoNotCollide() {
		val now = System.currentTimeMillis()
		setContent(
			DashboardRecentHistoryState.Content(
				StepsOnlyHistoryListState.entries.mapIndexed { index, state ->
					DashboardRecentHistoryEntry.StepsOnly(
						stepsEntry(
							key = "opaque-$index",
							state = state,
							startTimeMs = now - (index + 2) * 3_600_000L,
						),
					)
				},
			),
			onTripClick = { error("Steps-only rows must not expose a physical click identity") },
		)

		composeRule.onAllNodesWithText("Steps session").assertCountEquals(3)
		composeRule.onNodeWithText("Steps history available")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithText("Preparing Steps history…")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithText("Partial Steps history")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("View details").assertCountEquals(0)
	}

	@Test
	fun distinctOpaqueKeysWithIdenticalStringRepresentationRenderBothRows() {
		val first = stepsEntry("first", StepsOnlyHistoryListState.AVAILABLE, 1_000L)
		val second = stepsEntry("second", StepsOnlyHistoryListState.PARTIAL, 2_000L)
		first.key.toString() shouldBe second.key.toString()

		setContent(
			DashboardRecentHistoryState.Content(
				listOf(
					DashboardRecentHistoryEntry.StepsOnly(first),
					DashboardRecentHistoryEntry.StepsOnly(second),
				),
			),
		)

		composeRule.onAllNodesWithText("Steps session").assertCountEquals(2)
	}

	@Test
	fun getTripIconSupportsGmsNativeAndSkiIds() {
		getTripIcon(7) shouldBe Icons.AutoMirrored.Filled.DirectionsWalk
		getTripIcon(-2) shouldBe Icons.AutoMirrored.Filled.DirectionsWalk
		getTripIcon(1) shouldBe Icons.AutoMirrored.Filled.DirectionsBike
		getTripIcon(-4) shouldBe Icons.AutoMirrored.Filled.DirectionsBike
		getTripIcon(-22) shouldBe Icons.Filled.DownhillSkiing
	}

	private fun setContent(
		recentHistory: DashboardRecentHistoryState,
		onTripClick: ((Long) -> Unit)? = null,
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				RecentTripsCard(
					recentHistory = recentHistory,
					onTripClick = onTripClick,
				)
			}
		}
	}

	private fun trip(id: Long): Trip {
		val start = System.currentTimeMillis() - 3_600_000L
		return Trip(
			id = id,
			startTimeMs = start,
			endTimeMs = start + 1_800_000L,
			distanceM = 2_500f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 10,
			source = SegmentSource.USER_CREATED,
			createdAt = start,
		)
	}

	private fun stepsEntry(
		key: String,
		state: StepsOnlyHistoryListState,
		startTimeMs: Long,
	) = StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 1_800_000L),
		state = state,
	)
}
