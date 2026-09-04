package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.data.DashboardWidgetRegistry
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.tracker.insights.InsightCategory
import com.adsamcik.tracker.tracker.insights.SessionInsight
import com.adsamcik.tracker.tracker.insights.SessionInsightsGenerator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelSessionInsightsTest {
	@Test
	fun `clearing insights cancels and fences a suspended previous session`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try {
			val generator = ControllableSessionInsightsGenerator()
			val viewModel = createViewModel(generator)
			val sessionA = session(1L)

			viewModel.refreshSessionInsights(isTracking = false, session = sessionA)
			runCurrent()
			viewModel.refreshSessionInsights(isTracking = true, session = null)
			runCurrent()
			generator.complete(sessionA, insight("A"))
			advanceUntilIdle()

			viewModel.sessionInsights.value shouldBe emptyList()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `late previous session cannot overwrite completed replacement session`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try {
			val generator = ControllableSessionInsightsGenerator()
			val viewModel = createViewModel(generator)
			val sessionA = session(1L)
			val sessionB = session(2L)
			val insightA = insight("A")
			val insightB = insight("B")

			viewModel.refreshSessionInsights(isTracking = false, session = sessionA)
			runCurrent()
			viewModel.refreshSessionInsights(isTracking = false, session = sessionB)
			runCurrent()
			generator.complete(sessionB, insightB)
			runCurrent()
			viewModel.sessionInsights.value shouldBe listOf(insightB)

			generator.complete(sessionA, insightA)
			advanceUntilIdle()

			viewModel.sessionInsights.value shouldBe listOf(insightB)
		} finally {
			Dispatchers.resetMain()
		}
	}

	private fun createViewModel(generator: SessionInsightsGenerator): DashboardViewModel {
		val layoutRepository = mockk<DashboardLayoutStore>()
		every { layoutRepository.layout } returns flowOf(DashboardLayout())
		val trackerStateReader = mockk<TrackerStateReader>()
		every { trackerStateReader.isServiceRunningFlow } returns MutableStateFlow(false)
		every { trackerStateReader.sessionFlow } returns MutableStateFlow(null)
		val lockManager = mockk<LockManager>()
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		val trackingParamsRepository = mockk<TrackingParamsRepository>()
		every { trackingParamsRepository.data } returns flowOf(TrackingParamsState())

		return DashboardViewModel(
			appContext = ApplicationProvider.getApplicationContext<Context>(),
			historyRepository = mockk<DashboardHistoryRepository>(),
			trackingHistoryRepository = mockk<TrackingHistoryRepository>(),
			layoutRepository = layoutRepository,
			sessionInsightsGenerator = generator,
			widgetRegistry = mockk<DashboardWidgetRegistry>(relaxed = true),
			trackerStateReader = trackerStateReader,
			lockManager = lockManager,
			dailySummaryProvider = mockk(),
			dailyPointsProviderFactory = mockk(),
			goalProgressProviderFactory = mockk(),
			trackingParamsRepository = trackingParamsRepository,
		)
	}

	private fun session(id: Long) = TrackerSessionSnapshot(
		id = id,
		start = id * 1_000L,
		end = id * 1_000L + 500L,
		distanceInM = id.toFloat(),
	)

	private fun insight(label: String) = SessionInsight(
		category = InsightCategory.FUN_FACT,
		title = label,
		description = label,
		iconRes = 1,
	)
}

private class ControllableSessionInsightsGenerator : SessionInsightsGenerator {
	private val results = mutableMapOf<Long, CompletableDeferred<List<SessionInsight>>>()

	override suspend fun generate(session: TrackerSessionSnapshot): List<SessionInsight> {
		val result = results.getOrPut(session.id) { CompletableDeferred() }
		return try {
			result.await()
		} catch (_: CancellationException) {
			withContext(NonCancellable) { result.await() }
		}
	}

	fun complete(session: TrackerSessionSnapshot, insight: SessionInsight) {
		results.getOrPut(session.id) { CompletableDeferred() }.complete(listOf(insight))
	}
}
