package com.adsamcik.tracker.statistics.ui.compose

import com.adsamcik.tracker.shared.base.database.data.SkiRunSegment
import com.adsamcik.tracker.shared.base.database.data.SkiSegmentType
import com.adsamcik.tracker.statistics.ui.ski.SkiSessionDetailSection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [SkiSessionDetailSection].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkiSessionDetailSectionTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private val now = System.currentTimeMillis()

	private fun downhillSegment(
		id: Long = 1L,
		verticalM: Float = 300f,
		maxSpeedMps: Float = 15f,
		durationMs: Long = 180_000L,
	) = SkiRunSegment(
		id = id,
		sessionId = 1L,
		runIndex = 0,
		segmentType = SkiSegmentType.DOWNHILL_RUN,
		startTimeMs = now,
		endTimeMs = now + durationMs,
		verticalM = verticalM,
		distanceM = 1000f,
		maxSpeedMps = maxSpeedMps,
		avgSpeedMps = maxSpeedMps * 0.6f,
		createdAt = now,
	)

	private fun liftSegment(
		id: Long = 2L,
		verticalM: Float = 250f,
		liftType: String? = "gondola",
		durationMs: Long = 600_000L,
	) = SkiRunSegment(
		id = id,
		sessionId = 1L,
		runIndex = 0,
		segmentType = SkiSegmentType.LIFT_UP,
		startTimeMs = now,
		endTimeMs = now + durationMs,
		verticalM = verticalM,
		distanceM = 800f,
		maxSpeedMps = 5f,
		avgSpeedMps = 3f,
		liftType = liftType,
		createdAt = now,
	)

	// ─── Empty segments ──────────────────────────────────────────────────

	@Test
	fun `empty segments renders nothing`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(segments = emptyList())
			}
		}
		composeTestRule.onNodeWithText("Ski Session").assertDoesNotExist()
	}

	// ─── Single downhill run ─────────────────────────────────────────────

	@Test
	fun `single downhill run shows header and summary`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(downhillSegment()),
				)
			}
		}
		composeTestRule.onNodeWithText("Ski Session").assertIsDisplayed()
		composeTestRule.onNodeWithText("Runs").assertIsDisplayed()
		composeTestRule.onNodeWithText("Vertical").assertIsDisplayed()
		composeTestRule.onNodeWithText("Max speed").assertIsDisplayed()
		composeTestRule.onNodeWithText("1").assertIsDisplayed() // 1 run
	}

	// ─── Time breakdown labels ───────────────────────────────────────────

	@Test
	fun `shows skiing and lift time labels`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(downhillSegment(), liftSegment()),
				)
			}
		}
		composeTestRule.onNodeWithText("Skiing").assertIsDisplayed()
		composeTestRule.onNodeWithText("On lifts").assertIsDisplayed()
	}

	// ─── Multiple segments show timeline ─────────────────────────────────

	@Test
	fun `multiple segments show segment timeline`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(
						liftSegment(id = 1L),
						downhillSegment(id = 2L),
						liftSegment(id = 3L, liftType = "chairlift"),
						downhillSegment(id = 4L),
					),
				)
			}
		}
		// Should show run labels (Run #1 and Run #2) - use assertExists since they may need scrolling
		composeTestRule.onNodeWithText("Run #1").assertExists()
		composeTestRule.onNodeWithText("Run #2").assertExists()
	}

	// ─── Lift type labels ────────────────────────────────────────────────

	@Test
	fun `gondola lift type shows Gondola label`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(
						liftSegment(id = 1L, liftType = "gondola"),
						downhillSegment(id = 2L),
					),
				)
			}
		}
		composeTestRule.onNodeWithText("Gondola").assertIsDisplayed()
	}

	@Test
	fun `chairlift type shows Chairlift label`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(
						liftSegment(id = 1L, liftType = "chairlift"),
						downhillSegment(id = 2L),
					),
				)
			}
		}
		composeTestRule.onNodeWithText("Chairlift").assertIsDisplayed()
	}

	@Test
	fun `unknown lift type shows generic Lift label`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(
						liftSegment(id = 1L, liftType = null),
						downhillSegment(id = 2L),
					),
				)
			}
		}
		composeTestRule.onNodeWithText("Lift").assertIsDisplayed()
	}

	// ─── Idle/Walk segments ──────────────────────────────────────────────

	@Test
	fun `short idle segment is hidden`() {
		val shortIdle = SkiRunSegment(
			id = 5L,
			sessionId = 1L,
			runIndex = 0,
			segmentType = SkiSegmentType.IDLE,
			startTimeMs = now,
			endTimeMs = now + 60_000L, // 1 minute - too short to show
			verticalM = 0f,
			distanceM = 0f,
			maxSpeedMps = 0f,
			avgSpeedMps = 0f,
			createdAt = now,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(downhillSegment(), shortIdle),
				)
			}
		}
		composeTestRule.onNodeWithText("Pause").assertDoesNotExist()
	}

	@Test
	fun `long idle segment is shown`() {
		val longIdle = SkiRunSegment(
			id = 5L,
			sessionId = 1L,
			runIndex = 0,
			segmentType = SkiSegmentType.IDLE,
			startTimeMs = now,
			endTimeMs = now + 300_000L, // 5 minutes - should show
			verticalM = 0f,
			distanceM = 0f,
			maxSpeedMps = 0f,
			avgSpeedMps = 0f,
			createdAt = now,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(downhillSegment(), longIdle),
				)
			}
		}
		composeTestRule.onNodeWithText("Pause").assertIsDisplayed()
	}

	@Test
	fun `walk segment over 2 min is shown`() {
		val walkSegment = SkiRunSegment(
			id = 6L,
			sessionId = 1L,
			runIndex = 0,
			segmentType = SkiSegmentType.WALK,
			startTimeMs = now,
			endTimeMs = now + 180_000L, // 3 minutes
			verticalM = 0f,
			distanceM = 200f,
			maxSpeedMps = 1.5f,
			avgSpeedMps = 1.0f,
			createdAt = now,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(downhillSegment(), walkSegment),
				)
			}
		}
		composeTestRule.onNodeWithText("Walk").assertIsDisplayed()
	}

	// ─── Run count calculation ───────────────────────────────────────────

	@Test
	fun `run count shows correct number of downhill runs`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SkiSessionDetailSection(
					segments = listOf(
						downhillSegment(id = 1L),
						liftSegment(id = 2L),
						downhillSegment(id = 3L),
						liftSegment(id = 4L),
						downhillSegment(id = 5L),
					),
				)
			}
		}
		composeTestRule.onNodeWithText("3").assertIsDisplayed() // 3 runs
	}
}
