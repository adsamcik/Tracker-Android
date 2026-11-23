package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.app.di.TrackingInfrastructureIntegrationEntryPoint
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.RetentionWorkExecutionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Modifies retention and deletes expired rows; run only against a disposable debug install. */
@RunWith(AndroidJUnit4::class)
class DataRetentionInstrumentationTest {
    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var database: AppDatabase
    private lateinit var retainedSample: LocationSample
    private var expiredSampleId: Long = 0L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.adsamcik.tracker.debug") {
            "Retention instrumentation requires the disposable Debug app"
        }
        application = context.applicationContext as Application
        runBlocking {
            assertTrue(
                "Retention instrumentation requires completed Ready startup",
                application.awaitStartupReconciliation() is TrackingStartupResult.Ready,
            )
            val initial = RetentionConfigStore(context, Dispatchers.IO).config.first()
            check(!initial.autoCleanupEnabled && !initial.autoPurgeEnabled) {
                "Seed retention instrumentation in a fresh Debug profile before enabling cleanup"
            }
        }

        // Seed before enabling cleanup, so the first scheduled tick sees both rows.
        database = AppDatabase.database(context)
        val now = System.currentTimeMillis()
        val old = now - 2L * 365L * 24L * 60L * 60L * 1000L
        val sampleOld = LocationSample(
            timeMs = old,
            elapsedRealtimeNanos = 0L,
            latE7 = 0,
            lonE7 = 0,
            altitudeM = null,
            rawGpsAltitudeM = null,
            hAccM = null,
            vAccM = null,
            speedMps = null,
            speedAccuracyMps = null,
            provider = "gps",
            quality = SampleQuality.HIGH,
            motionState = null,
            policy = null,
            bucketId = null,
            createdAt = old,
        )
        val sampleNew = sampleOld.copy(
            timeMs = now,
            latE7 = (1.0 * 1e7).toInt(),
            lonE7 = (1.0 * 1e7).toInt(),
            createdAt = now,
        )
        runBlocking {
            val ids = database.locationSampleDao().insert(listOf(sampleOld, sampleNew))
            assertTrue(ids.all { it > 0L })
            expiredSampleId = ids.first()
            retainedSample = sampleNew.copy(id = ids.last())
        }
    }

    @Test
    fun retention_applies_approved_configuration_on_worker_tick() = runBlocking {
        val wm = WorkManager.getInstance(context)
        val producer = EntryPointAccessors.fromApplication(
            application,
            TrackingInfrastructureIntegrationEntryPoint::class.java,
        ).retentionAuthorityProducer()
        // Use the same singleton scheduler and WorkManager/Hilt graph as app maintenance.
        application.dataRetentionSchedulerProvider.get().initialize()
        val applied = RetentionConfigStore(context, Dispatchers.IO).updateWithApproval(
            block = { copy(autoCleanupEnabled = true, dataRetentionYears = 1) },
            prepare = { stage -> producer.preparePendingConfiguration(stage.policy.configurationGeneration) },
            approve = { stage -> producer.reconcilePendingConfiguration(stage.policy.configurationGeneration) },
        )
        assertTrue(
            "Retention configuration was not approved: ${applied.approval}",
            applied.approval is RetentionConfigurationApprovalResult.Approved ||
                applied.approval is RetentionConfigurationApprovalResult.AlreadyApproved,
        )
        withTimeout(60_000L) {
            val request = wm.getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.WORK_NAME)
                .first { infos -> infos.any { !it.state.isFinished } }
                .single { !it.state.isFinished }
            val receipt = database.invalidationTracker
                .createFlow("retention_work_execution_receipt", emitInitialState = true)
                .map { database.retentionWorkExecutionReceiptDao().latest(request.id.toString()) }
                .first { it?.state == RetentionWorkExecutionReceiptEntity.STATE_FINAL }
            assertEquals(RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE, receipt?.workerKind)
            // Successful periodic work is enqueued for its next period, rather than SUCCEEDED.
            wm.getWorkInfoByIdFlow(request.id).first {
                it?.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
            }
        }
        assertTrue(database.locationSampleDao().byIds(listOf(expiredSampleId)).isEmpty())
        assertEquals(listOf(retainedSample), database.locationSampleDao().byIds(listOf(retainedSample.id)))
        assertEquals(1, database.locationSampleDao().countBetween(0L, Long.MAX_VALUE))
    }
}
