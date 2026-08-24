package com.adsamcik.tracker.impexp.exporter.automation

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.impexp.exporter.proto.ExportBackupPlanProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportFormatProto
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import io.mockk.every
import io.mockk.mockk
import java.io.OutputStream
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalExportTest {

    @Test
    fun protoRoundTrip_preservesIncrementalFields() {
        val plan = ExportBackupPlan(
            id = ExportPlanId(99L),
            name = "Weekly GPX",
            format = ExportFormat.GPX,
            cadence = ExportCadence.Interval(
                unit = ExportCadence.IntervalUnit.WEEK,
                every = 1,
                atTime = LocalTime.of(6, 30),
            ),
            scope = ExportScope.RollingWindow(ExportScope.WindowUnit.WEEK, count = 2),
            destination = ExportDestination.PrivateStorage(
                relativeDirectory = "backups",
                fileNamePrefix = "weekly-",
                shareAfterExport = false,
            ),
            enabled = true,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
            lastWatermarkMs = 1_700_000_000_000L,
            lastWatermarkId = 17L,
            lastCompletedAt = 1_700_000_001_000L,
            lastRecordCount = 42,
            incrementalEnabled = true,
        )

        val rebuilt = plan.toProto().toDomain()

        assertEquals(plan, rebuilt)
        assertEquals(1_700_000_000_000L, rebuilt.lastWatermarkMs)
        assertEquals(17L, rebuilt.lastWatermarkId)
        assertEquals(1_700_000_001_000L, rebuilt.lastCompletedAt)
        assertEquals(42, rebuilt.lastRecordCount)
        assertTrue(rebuilt.incrementalEnabled)
    }

    @Test
    fun protoRoundTrip_preservesIncrementalDisabled() {
        val plan = buildTestPlan(
            lastWatermarkMs = 500L,
            lastCompletedAt = 600L,
            lastRecordCount = 10,
            incrementalEnabled = false,
            format = ExportFormat.JSON,
        )

        val rebuilt = plan.toProto().toDomain()

        assertEquals(plan, rebuilt)
        assertFalse(rebuilt.incrementalEnabled)
        assertEquals(500L, rebuilt.lastWatermarkMs)
    }

    @Test
    fun toDomain_defaultsIncrementalFieldsWhenMissing() {
        val proto = ExportBackupPlanProto.newBuilder()
            .setId(5L)
            .setName("Legacy plan")
            .setFormat(ExportFormatProto.EXPORT_FORMAT_GPX)
            .setEnabled(true)
            .build()

        val domain = proto.toDomain()

        assertEquals(0L, domain.lastWatermarkMs)
        assertEquals(0L, domain.lastCompletedAt)
        assertEquals(0, domain.lastRecordCount)
        assertTrue(domain.incrementalEnabled)
    }

    @Test
    fun incrementalScopeResolution_watermarkZero_fallsBackToFull() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 0L, incrementalEnabled = true),
            exporter = dateRangeExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(1_000L..5_000L, resolved.dateRange)
        assertFalse(resolved.isDelta)
        assertTrue(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun incrementalScopeResolution_withWatermark_narrows() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 3_000L, incrementalEnabled = true),
            exporter = dateRangeExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(3_000L..5_000L, resolved.dateRange)
        assertEquals(3_000L, resolved.afterTimeMs)
        assertEquals(0L, resolved.afterId)
        assertTrue(resolved.isDelta)
        assertTrue(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun incrementalScopeResolution_watermarkBeyondScope_returnsNoData() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 6_000L, incrementalEnabled = true),
            exporter = dateRangeExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(null, resolved.dateRange)
        assertTrue(resolved.skipBecauseEmptyIncremental)
        assertFalse(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun incrementalScopeResolution_nullScope_usesWatermarkToMaxLong() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 2_000L, incrementalEnabled = true),
            exporter = dateRangeExporter(),
            scopeDateRange = null,
        )

        assertEquals(2_000L..Long.MAX_VALUE, resolved.dateRange)
    }

    @Test
    fun incrementalScopeResolution_disabled_returnsOriginal() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 3_000L, incrementalEnabled = false),
            exporter = dateRangeExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(1_000L..5_000L, resolved.dateRange)
        assertFalse(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun incrementalScopeResolution_exporterCannotSelectDateRange_returnsOriginal() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 3_000L, incrementalEnabled = true),
            exporter = fullOnlyExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(1_000L..5_000L, resolved.dateRange)
        assertFalse(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun incrementalScopeResolution_databaseFormat_returnsOriginal() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(
                lastWatermarkMs = 3_000L,
                incrementalEnabled = true,
                format = ExportFormat.DATABASE,
            ),
            exporter = fullOnlyExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(1_000L..5_000L, resolved.dateRange)
        assertFalse(resolved.shouldAdvanceWatermark)
    }

    @Test
    fun watermarkUpdate_afterSuccessfulExport_setsMaxTimeMs() {
        val plan = buildTestPlan(lastWatermarkMs = 1_000L, incrementalEnabled = true)
        val updated = plan.copy(
            lastWatermarkMs = 5_000L,
            lastCompletedAt = 6_000L,
            lastRecordCount = 25,
        )

        assertEquals(5_000L, updated.lastWatermarkMs)
        assertEquals(6_000L, updated.lastCompletedAt)
        assertEquals(25, updated.lastRecordCount)
    }

    @Test
    fun jsonScheduledExport_prefersExporterReportedProgressForWatermarkAdvance() {
        val progress = worker().resolveExportProgress(
            result = ExportResult.Success(recordCount = 3, maxTimeMs = 5_000L, maxId = 9L),
            fallbackRecordCount = 0,
            fallbackMaxTimeMs = 0L,
            fallbackMaxId = 0L,
        )

        assertEquals(3, progress.recordCount)
        assertEquals(5_000L, progress.maxTimeMs)
        assertEquals(9L, progress.maxId)
        assertTrue(progress.recordCount > 0 && progress.maxTimeMs > 0L)
    }

    @Test
    fun incrementalScopeResolution_sameTimestampCarriesBoundaryId() {
        val resolved = worker().resolveExportRange(
            plan = buildTestPlan(lastWatermarkMs = 3_000L, lastWatermarkId = 12L),
            exporter = dateRangeExporter(),
            scopeDateRange = 1_000L..5_000L,
        )

        assertEquals(3_000L..5_000L, resolved.dateRange)
        assertEquals(3_000L, resolved.afterTimeMs)
        assertEquals(12L, resolved.afterId)
    }

    @Test
    fun watermarkUpdate_noUpdateOnFailure_preservesPrevious() {
        val plan = buildTestPlan(
            lastWatermarkMs = 1_000L,
            lastCompletedAt = 900L,
            lastRecordCount = 10,
            incrementalEnabled = true,
        )

        assertEquals(1_000L, plan.lastWatermarkMs)
        assertEquals(900L, plan.lastCompletedAt)
        assertEquals(10, plan.lastRecordCount)
    }

    @Test
    fun resetWatermark_clearsWatermarkFields() {
        val reset = buildTestPlan(
            lastWatermarkMs = 5_000L,
            lastCompletedAt = 6_000L,
            lastRecordCount = 50,
            incrementalEnabled = true,
        ).copy(
            lastWatermarkMs = 0L,
            lastCompletedAt = 0L,
            lastRecordCount = 0,
        )

        assertEquals(0L, reset.lastWatermarkMs)
        assertEquals(0L, reset.lastCompletedAt)
        assertEquals(0, reset.lastRecordCount)
        assertTrue(reset.incrementalEnabled)
    }

    @Test
    fun resetWatermark_preservesIncrementalEnabled() {
        val reset = buildTestPlan(
            lastWatermarkMs = 5_000L,
            incrementalEnabled = false,
        ).copy(
            lastWatermarkMs = 0L,
            lastCompletedAt = 0L,
            lastRecordCount = 0,
        )

        assertFalse(reset.incrementalEnabled)
    }

    @Test
    fun documentTreeDestination_requiresPersistedWritePermission() {
        val treeUri = mockk<Uri>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { contentResolver.persistedUriPermissions } returns emptyList()

        assertFalse(worker(context).hasPersistedWritePermission(treeUri))
    }

    @Test
    fun documentTreeDestination_acceptsPersistedWritePermission() {
        val treeUri = mockk<Uri>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val permission = mockk<UriPermission>(relaxed = true)
        every { context.contentResolver } returns contentResolver
        every { permission.uri } returns treeUri
        every { permission.isWritePermission } returns true
        every { contentResolver.persistedUriPermissions } returns listOf(permission)

        assertTrue(worker(context).hasPersistedWritePermission(treeUri))
    }

    private fun buildTestPlan(
        lastWatermarkMs: Long = 0L,
        lastWatermarkId: Long = 0L,
        lastCompletedAt: Long = 0L,
        lastRecordCount: Int = 0,
        incrementalEnabled: Boolean = true,
        format: ExportFormat = ExportFormat.GPX,
    ) = ExportBackupPlan(
        id = ExportPlanId(1L),
        name = "Test Plan",
        format = format,
        cadence = ExportCadence.AfterSession,
        scope = ExportScope.EntireHistory,
        destination = ExportDestination.PrivateStorage(),
        enabled = true,
        createdAtMillis = 100L,
        updatedAtMillis = 200L,
        lastWatermarkMs = lastWatermarkMs,
        lastWatermarkId = lastWatermarkId,
        lastCompletedAt = lastCompletedAt,
        lastRecordCount = lastRecordCount,
        incrementalEnabled = incrementalEnabled,
    )

    private fun worker(appContext: Context = mockk(relaxed = true)): ExportPlanWorker {
        return ExportPlanWorker(
            appContext = appContext,
            params = mockk(relaxed = true),
            planStore = mockk(relaxed = true),
            appDatabaseProvider = Provider { mockk(relaxed = true) },
            ioDispatcher = Dispatchers.Unconfined,
            clock = object : Clock {
                override fun currentTimeMillis(): Long = 0L
                override fun elapsedRealtimeNanos(): Long = 0L
            },
			trackingStartupGate = READY_STARTUP_GATE,
        )
    }

	private companion object {
		val READY_STARTUP_GATE = object : TrackingStartupGate {
			override val isReady: Boolean = true
			override suspend fun reconcile(retryFailedStorage: Boolean) =
				TrackingStartupResult.Ready(legacyRecoveryPartial = false, liveCompletedThroughOrdinal = 0L)
		}
	}

    private fun dateRangeExporter(): Exporter = object : Exporter {
        override val canSelectDateRange: Boolean = true
        override val mimeType: String = "application/gpx+xml"
        override val extension: String = "gpx"

        override suspend fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?,
        ): ExportResult = ExportResult.Success
    }

    private fun fullOnlyExporter(): Exporter = object : Exporter {
        override val canSelectDateRange: Boolean = false
        override val mimeType: String = "application/octet-stream"
        override val extension: String = "bin"

        override suspend fun export(
            context: Context,
            locationData: Sequence<LocationSample>,
            outputStream: OutputStream,
            dateRange: LongRange?,
        ): ExportResult = ExportResult.Success
    }
}
