package com.adsamcik.tracker.impexp.exporter.automation

import com.adsamcik.tracker.impexp.exporter.proto.ExportBackupPlanProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportCadenceProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportFormatProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportScopeProto
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
 
class ExportBackupPlanMappingTest {

    @Test
    fun protoRoundTrip_preservesDocumentTreeConfiguration() {
        val plan = ExportBackupPlan(
            id = ExportPlanId(42L),
            name = "Weekly GPX",
            format = ExportFormat.GPX,
            cadence = ExportCadence.Interval(
                unit = ExportCadence.IntervalUnit.WEEK,
                every = 1,
                atTime = LocalTime.of(6, 30)
            ),
            scope = ExportScope.RollingWindow(ExportScope.WindowUnit.WEEK, count = 1),
            destination = ExportDestination.PrivateStorage(
                relativeDirectory = "backups",
                fileNamePrefix = "weekly-",
                shareAfterExport = true
            ),
            enabled = true,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L
        )

        val rebuilt = plan.toProto().toDomain()

        assertEquals(plan, rebuilt)
    }

    @Test
    fun toDomain_appliesFallbacksForMissingFields() {
        val proto = ExportBackupPlanProto.newBuilder()
            .setId(7L)
            .setName("")
            .setFormat(ExportFormatProto.EXPORT_FORMAT_UNSPECIFIED)
            .setCadence(ExportCadenceProto.getDefaultInstance())
            .setScope(ExportScopeProto.getDefaultInstance())
            .build()

        val domain = proto.toDomain()

        assertEquals("Plan 7", domain.name)
        assertTrue(domain.cadence is ExportCadence.AfterSession)
        assertTrue(domain.destination is ExportDestination.PrivateStorage)
    }
}
