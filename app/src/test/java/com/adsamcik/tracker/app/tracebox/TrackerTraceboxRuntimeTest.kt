package com.adsamcik.tracker.app.tracebox

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import dev.tracebox.api.DiagnosticContext
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.generated.GeneratedBreadcrumb
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedHandledError
import dev.tracebox.api.generated.GeneratedRecord
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerTraceboxRuntimeTest {
    @After
    fun clearDiagnosticSink() {
        TrackerDiagnostics.clear()
    }

    @Test
    fun `only exact package process is the Tracker client process`() {
        isTrackerMainProcessName("com.example.tracker", "com.example.tracker") shouldBe true
        isTrackerMainProcessName(
            "com.example.tracker:tracebox_handler",
            "com.example.tracker",
        ) shouldBe false
        isTrackerMainProcessName(null, "com.example.tracker") shouldBe false
    }

    @Test
    fun `only dedicated suffix is recognized as Tracebox handler`() {
        isTraceboxHandlerProcessName(
            "com.example.tracker:tracebox_handler",
            "com.example.tracker",
        ) shouldBe true
        isTraceboxHandlerProcessName(
            "com.example.tracker:worker",
            "com.example.tracker",
        ) shouldBe false
        isTraceboxHandlerProcessName(null, "com.example.tracker") shouldBe false
    }

    @Test
    fun `handled event emits one record without invented frames`() {
        val diagnostics = RecordingDiagnostics()
        installTrackerDiagnosticSink(diagnostics)

        TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_FAILED)
        val handled = diagnostics.records.filterIsInstance<GeneratedHandledError>()
        handled.size shouldBe 1
        handled.forEach { record ->
            record.kind shouldBe TrackerDiagnosticCode.OSM_IMPORT_FAILED.wireCode
            record.frame_count shouldBe 0u
        }
    }

    @Test
    fun `breadcrumb event emits one fixed-code record`() {
        val diagnostics = RecordingDiagnostics()
        installTrackerDiagnosticSink(diagnostics)

        TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_WARNING)
        val breadcrumbs = diagnostics.records.filterIsInstance<GeneratedBreadcrumb>()
        breadcrumbs.size shouldBe 1
        breadcrumbs.forEach { record ->
            record.code shouldBe TrackerDiagnosticCode.OSM_IMPORT_WARNING.wireCode
        }
    }

    private class RecordingDiagnostics : Diagnostics {
        val records = mutableListOf<GeneratedRecord>()

        override fun eventEnabled(eventId: GeneratedEventId): Boolean = true

        override fun record(value: GeneratedRecord, context: DiagnosticContext?) {
            records += value
        }
    }
}
