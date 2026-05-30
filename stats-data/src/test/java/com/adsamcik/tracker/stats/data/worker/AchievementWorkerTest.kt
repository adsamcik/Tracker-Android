package com.adsamcik.tracker.stats.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.stats.api.metric.MetricSnapshot
import com.adsamcik.tracker.stats.api.repository.AchievementMetricsProvider
import com.adsamcik.tracker.stats.data.metric.DefaultMetricDirtyTracker
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the battery-critical short-circuit added in R2 round-6:
 * the worker must not invoke [AchievementMetricsProvider.collect] (which fans out
 * to a stack of aggregate queries) when the dirty tracker is empty, and it must
 * re-mark the consumed dirty bits if evaluation throws so the next scheduled run
 * still observes the underlying writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AchievementWorkerTest {

	private fun newWorker(
		metricsProvider: AchievementMetricsProvider,
		achievementDao: AchievementProgressDao,
		dirtyTracker: DefaultMetricDirtyTracker,
	): AchievementWorker {
		val context = ApplicationProvider.getApplicationContext<Context>()
		return TestListenableWorkerBuilder<AchievementWorker>(context)
			.setWorkerFactory(
				object : androidx.work.WorkerFactory() {
					override fun createWorker(
						appContext: Context,
						workerClassName: String,
						workerParameters: androidx.work.WorkerParameters,
					): ListenableWorker = AchievementWorker(
						appContext,
						workerParameters,
						metricsProvider,
						achievementDao,
						dirtyTracker,
					)
				},
			)
			.build()
	}

	@Test
	fun `empty dirty tracker short-circuits without calling metricsProvider`() = runTest {
		val metricsProvider = mockk<AchievementMetricsProvider>(relaxed = true)
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		val dirtyTracker = DefaultMetricDirtyTracker()

		val worker = newWorker(metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 0) { metricsProvider.collect() }
		coVerify(exactly = 0) { achievementDao.getAll() }
	}

	@Test
	fun `non-empty dirty tracker runs full evaluation`() = runTest {
		val metricsProvider = mockk<AchievementMetricsProvider>()
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { metricsProvider.collect() } returns MetricSnapshot.Empty
		coEvery { achievementDao.getAll() } returns emptyList()

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty("daily_summary")

		val worker = newWorker(metricsProvider, achievementDao, dirtyTracker)
		val result = worker.doWork()

		result shouldBe ListenableWorker.Result.success()
		coVerify(exactly = 1) { metricsProvider.collect() }
		coVerify(exactly = 1) { achievementDao.getAll() }
		// Dirty bits drained after a successful evaluation.
		dirtyTracker.consumeDirty().isEmpty() shouldBe true
	}

	@Test
	fun `metrics provider throwable re-marks consumed dirty bits`() = runTest {
		val metricsProvider = mockk<AchievementMetricsProvider>()
		val achievementDao = mockk<AchievementProgressDao>(relaxed = true)
		coEvery { metricsProvider.collect() } throws IllegalStateException("boom")

		val dirtyTracker = DefaultMetricDirtyTracker()
		dirtyTracker.markDirty(setOf("daily_summary", "session_segment"))

		val worker = newWorker(metricsProvider, achievementDao, dirtyTracker)

		val thrown = runCatching { worker.doWork() }
		thrown.exceptionOrNull()!!.shouldBeInstanceOf<IllegalStateException>()
		// The dirty bits must be observable to the next worker pass.
		dirtyTracker.consumeDirty() shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}
}
