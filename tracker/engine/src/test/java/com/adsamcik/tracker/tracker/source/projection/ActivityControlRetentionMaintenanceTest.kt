package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ACTIVITY_CONTROL_RETENTION_NANOS
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityControlRetentionMaintenanceTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
    }

    @After fun tearDown() = database.close()

    @Test
    fun `elapsed retention expires exactly at 48 hours and reboot expires immediately`() {
        controlEvidenceExpired("boot", 2L, "boot", ACTIVITY_CONTROL_RETENTION_NANOS + 1L) shouldBe false
        controlEvidenceExpired("boot", 2L, "boot", ACTIVITY_CONTROL_RETENTION_NANOS + 2L) shouldBe true
        controlEvidenceExpired("old", 2L, "boot", 3L) shouldBe true
        controlEvidenceExpired("boot", 4L, "boot", 3L) shouldBe true
    }

    @Test
    fun `due orphaned control effect is removed while recent effect remains`() = runTest {
        val now = ACTIVITY_CONTROL_RETENTION_NANOS + 100L
        // A delayed callback can have an older receipt than an earlier admission.
        database.sourceProjectionStateDao().insertOutbox(effect("fresh", 1L, "current-boot", now - 1L))
        database.sourceProjectionStateDao().insertOutbox(effect("old", 2L, "previous-boot", 5L))
        val maintenance = ActivityControlRetentionMaintenance(
            database,
            BootClockDomainProvider { "current-boot" },
            object : TrackingStartupGate {
                override val isReady = true
                override val currentGeneration = 1L
                override suspend fun reconcile(retryFailedStorage: Boolean) =
                    TrackingStartupResult.Ready(false, 0L)
            },
        )

        maintenance.pruneBatch("current-boot", now, 100L).outboxDeleted shouldBe 1
        database.sourceProjectionStateDao().outbox("old") shouldBe null
        database.sourceProjectionStateDao().outbox("fresh")?.stableId shouldBe "fresh"
    }

    @Test
    fun `bounded outbox scan resumes after a fresh first page`() = runTest {
        val now = ACTIVITY_CONTROL_RETENTION_NANOS + 100L
        database.sourceProjectionStateDao().insertOutbox(effect("fresh", 1L, "current-boot", now - 1L))
        database.sourceProjectionStateDao().insertOutbox(effect("old", 2L, "previous-boot", 5L))
        val maintenance = ActivityControlRetentionMaintenance(
            database,
            BootClockDomainProvider { "current-boot" },
            object : TrackingStartupGate {
                override val isReady = true
                override val currentGeneration = 1L
                override suspend fun reconcile(retryFailedStorage: Boolean) =
                    TrackingStartupResult.Ready(false, 0L)
            },
        )

        maintenance.pruneBatch("current-boot", now, 100L, limit = 1).let {
            it.outboxDeleted shouldBe 0
            it.outboxScanIncomplete shouldBe true
        }
        database.sourceProjectionStateDao().checkpoint("activity-control-expiry", 1)
            ?.contiguousAdmissionOrdinal shouldBe 1L
        maintenance.pruneBatch("current-boot", now, 100L, limit = 1).outboxDeleted shouldBe 1
        database.sourceProjectionStateDao().outbox("old") shouldBe null
        database.sourceProjectionStateDao().outbox("fresh")?.stableId shouldBe "fresh"
    }

    @Test
    fun `unknown version survives while unusable known payload is retired`() = runTest {
        val now = ACTIVITY_CONTROL_RETENTION_NANOS + 100L
        val old = effect("unknown", 1L, "previous-boot", 5L)
        database.sourceProjectionStateDao().insertOutbox(old.copy(projectionVersion = 5))
        database.sourceProjectionStateDao().insertOutbox(effect("damaged", 2L, "previous-boot", 5L)
            .copy(payload = byteArrayOf(1, 2, 3)))
        val maintenance = ActivityControlRetentionMaintenance(
            database,
            BootClockDomainProvider { "current-boot" },
            object : TrackingStartupGate {
                override val isReady = true
                override val currentGeneration = 1L
                override suspend fun reconcile(retryFailedStorage: Boolean) =
                    TrackingStartupResult.Ready(false, 0L)
            },
        )

        maintenance.pruneBatch("current-boot", now, 100L).outboxDeleted shouldBe 1
        database.sourceProjectionStateDao().outbox("unknown")?.stableId shouldBe "unknown"
        database.sourceProjectionStateDao().outbox("damaged") shouldBe null
    }

    private fun effect(
        stableId: String,
        ordinal: Long,
        boot: String,
        received: Long,
    ) = SourceProjectionOutboxEntity(
        stableId = stableId,
        projectionId = ActivityAutomationProjection.ID,
        projectionVersion = ActivityAutomationProjection.VERSION,
        admissionOrdinal = ordinal,
        effectKind = ActivityAutomationProjection.OUTBOX_KIND,
        payloadVersion = ActivityAutomationProjection.PAYLOAD_VERSION,
        payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(ActivityAutomationProjection.KIND_RECOGNITION)
                repeat(3) { output.writeInt(0) }
                output.writeUTF(boot)
                output.writeLong(received)
                output.writeLong(received)
                output.writeLong(1L)
                output.writeLong(1L)
                output.writeUTF("authorization")
                output.writeLong(1L)
                output.writeLong(1L)
            }
            bytes.toByteArray()
        },
        createdAtMs = 1L,
        deliveredAtMs = null,
    )
}
