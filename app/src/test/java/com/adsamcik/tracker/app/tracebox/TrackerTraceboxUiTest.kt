package com.adsamcik.tracker.app.tracebox

import dev.tracebox.ui.compose.TraceboxDiagnosticsUiStrings
import dev.tracebox.ui.compose.TraceboxPrimaryAction
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrackerTraceboxUiTest {
    @Test
    fun `casual share is primary and technical controls begin collapsed`() {
        val strings = TraceboxDiagnosticsUiStrings(title = "Localized diagnostics")
        val configuration = TrackerTraceboxUi.configuration(strings)

        configuration.strings shouldBe strings
        configuration.showHeading shouldBe false
        configuration.primaryAction shouldBe TraceboxPrimaryAction.SHARE
        configuration.packageActions.upload shouldBe false
        configuration.packageActions.share shouldBe true
        configuration.packageActions.save shouldBe true
        configuration.packageActions.deleteAllData shouldBe true
        configuration.advancedControls.visible shouldBe true
        configuration.advancedControls.initiallyExpanded shouldBe false
        configuration.defaultPolicy shouldBe TrackerTraceboxRuntime.defaultPolicy
    }
}
