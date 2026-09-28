package com.adsamcik.tracker.app.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityControlCleanupWorkerRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `ready cleanup retries a remaining page and completes after draining`() = runTest {
        val maintenance = mockk<ActivityControlRetentionMaintenance>()
        coEvery { maintenance.pruneDue() } returnsMany listOf(true, false)
        val gate = gate(TrackingStartupResult.Ready(false, 0L))

        worker(gate, maintenance).doWork() shouldBe ListenableWorker.Result.retry()
        worker(gate, maintenance).doWork() shouldBe ListenableWorker.Result.success()
        coVerify(exactly = 2) { maintenance.pruneDue() }
    }

    @Test
    fun `startup failure retries and blocked storage is left untouched`() = runTest {
        val maintenance = mockk<ActivityControlRetentionMaintenance>()
        worker(gate(TrackingStartupResult.RetryableFailure(
            TrackingStartupStage.STORAGE, "TEMPORARY",
        )), maintenance).doWork() shouldBe ListenableWorker.Result.retry()
        worker(gate(TrackingStartupResult.Blocked(
            TrackingStartupStage.STORAGE, "UNSUPPORTED",
        )), maintenance).doWork() shouldBe ListenableWorker.Result.success()
        coVerify(exactly = 0) { maintenance.pruneDue() }
    }

    private fun gate(result: TrackingStartupResult): TrackingStartupGate = mockk {
        coEvery { reconcile(any()) } returns result
    }

    private fun worker(
        gate: TrackingStartupGate,
        maintenance: ActivityControlRetentionMaintenance,
    ): ActivityControlCleanupWorker = TestListenableWorkerBuilder<ActivityControlCleanupWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = ActivityControlCleanupWorker(
                appContext, workerParameters, gate, maintenance,
            )
        })
        .build() as ActivityControlCleanupWorker
}
