package com.adsamcik.tracker.dashboard.ui.compose

import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DashboardRecentHistoryPresentationTest {
	@Test
	fun `top Steps row suppresses raw controller Last Session and makes dashboard idle`() {
		val state = DashboardRecentHistoryState.Content(
			listOf(DashboardRecentHistoryEntry.StepsOnly(stepsEntry())),
		)

		val display = resolveIdleDisplaySession(
			recentHistory = state,
			currentSession = session(9L),
			lastSession = session(8L),
		)

		display shouldBe null
		resolveDashboardMode(
			isTracking = false,
			hasTodaySummary = false,
			displaySession = display,
			recentHistory = state,
		) shouldBe DashboardMode.IDLE
	}

	@Test
	fun `matching controller retains non measurement state and uses corrected saved distance`() {
		val controller = session(7L).copy(end = 3_000L, distanceInM = 432f)
		val state = DashboardRecentHistoryState.Content(
			listOf(DashboardRecentHistoryEntry.Physical(trip(7L).copy(distanceM = 321f, distanceIsPartial = true))),
		)

		resolveIdleDisplaySession(state, currentSession = controller, lastSession = session(6L)) shouldBe
			controller.copy(distanceInM = 321f, hasMeasuredDistance = true, distanceIsPartial = true)
	}

	@Test
	fun `session without Location cannot regain a measured zero from its retained controller`() {
		val controller = session(7L).copy(distanceInM = 0f)
		val withoutLocation = trip(7L).copy(distanceM = 0f, sampleCount = 0, hasMeasuredDistance = false)
		val state = DashboardRecentHistoryState.Content(listOf(DashboardRecentHistoryEntry.Physical(withoutLocation)))

		resolveIdleDisplaySession(state, null, controller) shouldBe
			controller.copy(hasMeasuredDistance = false)
	}

	@Test
	fun `saved stationary Location remains measured zero despite a different controller distance`() {
		val stationary = trip(7L).copy(distanceM = 0f, hasMeasuredDistance = true)
		val state = DashboardRecentHistoryState.Content(listOf(DashboardRecentHistoryEntry.Physical(stationary)))

		val display = resolveIdleDisplaySession(state, session(7L), null)

		display?.distanceInM shouldBe 0f
		display?.hasMeasuredDistance shouldBe true
	}

	@Test
	fun `mismatched controller snapshot cannot replace top physical row`() {
		val state = DashboardRecentHistoryState.Content(
			listOf(DashboardRecentHistoryEntry.Physical(trip(7L))),
		)

		val display = resolveIdleDisplaySession(state, session(9L), session(8L))

		display?.id shouldBe 7L
		display?.distanceInM shouldBe 100f
	}

	@Test
	fun `loading and unavailable remain reachable idle states while empty content stays empty`() {
		listOf(
			DashboardRecentHistoryState.Loading,
			DashboardRecentHistoryState.Unavailable(),
			DashboardRecentHistoryState.Content(emptyList(), hasSavedBackgroundData = true),
			DashboardRecentHistoryState.Content(emptyList(), setOf(
				com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageProblem(
					com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI,
					com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE))),
		).forEach { state ->
			resolveDashboardMode(false, false, null, state) shouldBe DashboardMode.IDLE
		}
		resolveDashboardMode(
			isTracking = false,
			hasTodaySummary = false,
			displaySession = null,
			recentHistory = DashboardRecentHistoryState.Content(emptyList()),
		) shouldBe DashboardMode.EMPTY
	}

	private fun trip(id: Long) = Trip(
		id = id,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		distanceM = 100f,
		steps = 50,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 10,
		source = SegmentSource.USER_CREATED,
		createdAt = 1_000L,
	)

	private fun session(id: Long) = TrackerSessionSnapshot(
		id = id,
		start = 1_000L,
		end = 2_000L,
		isUserInitiated = false,
		collections = 10,
		distanceInM = 200f,
		steps = 50,
	)

	private fun stepsEntry() = StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey("steps"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		state = StepsOnlyHistoryListState.AVAILABLE,
	)
}
