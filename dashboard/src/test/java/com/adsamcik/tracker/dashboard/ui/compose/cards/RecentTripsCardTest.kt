package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
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
	fun emptyTrips_showsEmptyMessage() {
		setContent(emptyList(), onTripClick = null)

		composeRule.onNodeWithText("Recent Trips").assertIsDisplayed()
		composeRule.onNodeWithText(
			"No recent trips. Start tracking to see your journeys here.",
		).assertIsDisplayed()
	}

	@Test
	fun withTrips_showsTripInfo() {
		setContent(
			listOf(
				Trip(
					id = 1L,
					startTimeMs = System.currentTimeMillis() - 3_600_000L,
					endTimeMs = System.currentTimeMillis() - 1_800_000L,
					distanceM = 2_500f,
					steps = null,
					primaryActivity = null,
					activityConfidence = null,
					sampleCount = 10,
					source = SegmentSource.USER_CREATED,
					createdAt = System.currentTimeMillis() - 3_600_000L,
				),
			),
			onTripClick = null,
		)

		composeRule.onNodeWithText("Recent Trips").assertIsDisplayed()
		// 2500m → "2.5 km" in metric
		composeRule.onNodeWithText("km", substring = true).assertIsDisplayed()
		// 1,800,000ms = 30 min → "30 m"
		composeRule.onNodeWithText("30 m", substring = true).assertIsDisplayed()
	}

	@Test
	fun clickCallback_firesWithCorrectId() {
		var clickedId: Long? = null
		setContent(
			listOf(
				Trip(
					id = 42L,
					startTimeMs = System.currentTimeMillis() - 7_200_000L,
					endTimeMs = System.currentTimeMillis() - 6_600_000L,
					distanceM = 1_000f,
					steps = null,
					primaryActivity = null,
					activityConfidence = null,
					sampleCount = 5,
					source = SegmentSource.USER_CREATED,
					createdAt = System.currentTimeMillis() - 7_200_000L,
				),
			),
			onTripClick = { clickedId = it },
		)

		// Forward arrow is rendered when onTripClick is non-null
		composeRule.onNodeWithContentDescription("View details").assertIsDisplayed()
		// Click the trip row via the forward arrow
		composeRule.onNodeWithContentDescription("View details").performClick()
		clickedId shouldBe 42L
	}

	@Test
	fun nullClickHandler_noForwardArrows() {
		setContent(
			listOf(
				Trip(
					id = 1L,
					startTimeMs = System.currentTimeMillis(),
					endTimeMs = System.currentTimeMillis() + 300_000L,
					distanceM = 500f,
					steps = null,
					primaryActivity = null,
					activityConfidence = null,
					sampleCount = 3,
					source = SegmentSource.USER_CREATED,
					createdAt = System.currentTimeMillis(),
				),
			),
			onTripClick = null,
		)

		composeRule.onAllNodesWithContentDescription("View details")
			.assertCountEquals(0)
	}

	private fun setContent(trips: List<Trip>, onTripClick: ((Long) -> Unit)?) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				RecentTripsCard(
					trips = trips,
					onTripClick = onTripClick,
				)
			}
		}
	}
}
