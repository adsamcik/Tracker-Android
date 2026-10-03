package com.adsamcik.tracker.impexp.importer.worker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.adsamcik.tracker.impexp.exporter.automation.ExportBackupPlan
import com.adsamcik.tracker.impexp.exporter.automation.ExportCadence
import com.adsamcik.tracker.impexp.exporter.automation.ExportDestination
import com.adsamcik.tracker.impexp.exporter.automation.ExportFormat
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanId
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanWorker
import com.adsamcik.tracker.impexp.exporter.automation.ExportScope
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferWorkerAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val neverOpenDatabase = Provider<AppDatabase> { error("Rejected transfer opened collected data") }

    @Test
    fun `import original epoch is rejected before transfer and database access`() = runTest(dispatcher) {
        val gate = DecliningTransferGate()
        val lifecycle = mockk<CollectedDataLifecycleStore> {
            coEvery { snapshot() } returns CollectedDataLifecycleSnapshot(8L, null)
        }
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(ImportWorker.ARG_FILE_URI to "content://tracker/input",
                ImportWorker.ARG_ORIGINAL_DATA_EPOCH to 7L)
        }
        ImportWorker(context, parameters, neverOpenDatabase, TestDispatchersProvider(dispatcher), gate, lifecycle)
            .doWork() shouldBe ListenableWorker.Result.failure()
        gate.admissionAttempts shouldBe 0
    }

    @Test
    fun `import reconciles before admission and declines without hashing or opening database`() = runTest(dispatcher) {
        val gate = DecliningTransferGate()
        val lifecycle = mockk<CollectedDataLifecycleStore> {
            coEvery { snapshot() } returns CollectedDataLifecycleSnapshot(7L, null)
        }
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(ImportWorker.ARG_FILE_URI to "content://tracker/input",
                ImportWorker.ARG_ORIGINAL_DATA_EPOCH to 7L)
        }
        ImportWorker(context, parameters, neverOpenDatabase, TestDispatchersProvider(dispatcher), gate, lifecycle)
            .doWork() shouldBe ListenableWorker.Result.success()
        gate.events shouldBe listOf("reconcile", "transfer:0")
    }

    @Test
    fun `scheduled export reconciles before admission and declines before source or destination access`() = runTest(dispatcher) {
        val gate = DecliningTransferGate()
        val plans = mockk<ExportPlanStore> {
            coEvery { getPlan(ExportPlanId(2L)) } returns ExportBackupPlan(
                ExportPlanId(2L), "Data", ExportFormat.JSON, ExportCadence.AfterSession,
                ExportScope.EntireHistory, ExportDestination.PrivateStorage(), true, 1L, 1L,
            )
        }
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(ExportPlanWorker.KEY_PLAN_ID to 2L)
        }
        val clock = object : Clock {
            override fun currentTimeMillis() = 1L
            override fun elapsedRealtimeNanos() = 1L
        }
        ExportPlanWorker(context, parameters, plans, neverOpenDatabase, dispatcher, clock, gate)
            .doWork() shouldBe ListenableWorker.Result.success()
        gate.events shouldBe listOf("reconcile", "transfer:0")
    }

    private class DecliningTransferGate : TrackingStartupGate {
        val events = mutableListOf<String>()
        var admissionAttempts = 0
        override val isReady = true
        override val permitsNewCollection = false
        override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
            events += "reconcile"
            return TrackingStartupResult.Ready(false, 0L)
        }
        override suspend fun <T> withReadyGenerationTransfer(expectedGeneration: Long, operation: suspend () -> T): T? {
            events += "transfer:$expectedGeneration"
            admissionAttempts++
            return null
        }
    }
}
