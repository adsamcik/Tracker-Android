package com.adsamcik.tracker.diagnostics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TrackerDiagnostics")
class TrackerDiagnosticsTest {
    @AfterEach
    fun tearDown() {
        TrackerDiagnostics.clear()
    }

    @Test
    fun `records fixed codes`() {
        val events = mutableListOf<TrackerDiagnosticCode>()
        TrackerDiagnostics.install(TrackerDiagnosticSink(events::add))

        TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_WARNING)
        TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_FAILED)
        events.shouldContainExactly(
            TrackerDiagnosticCode.OSM_IMPORT_WARNING,
            TrackerDiagnosticCode.OSM_IMPORT_FAILED,
        )
    }

    @Test
    fun `backend runtime failure never reaches caller`() {
        TrackerDiagnostics.install(TrackerDiagnosticSink { error("backend unavailable") })

        runCatching {
            TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_FAILED)
        }.isSuccess shouldBe true
    }

    @Test
    fun `wire codes are unique`() {
        TrackerDiagnosticCode.entries
            .map(TrackerDiagnosticCode::wireCode)
            .distinct()
            .size shouldBe TrackerDiagnosticCode.entries.size
    }

    @Test
    fun `sink contract cannot accept free-form payloads`() {
        TrackerDiagnosticSink::class.java.declaredMethods
            .flatMap { it.parameterTypes.asList() }
            .none { it == String::class.java || Throwable::class.java.isAssignableFrom(it) }
            .shouldBe(true)
    }

}
