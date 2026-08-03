package com.adsamcik.tracker.app.tracebox

import dev.tracebox.ui.compose.TraceboxAdvancedControls
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiConfiguration
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiStrings
import dev.tracebox.ui.compose.TraceboxPackageActions
import dev.tracebox.ui.compose.TraceboxPrimaryAction

/** Tracker's product choices for Tracebox's reusable casual/advanced diagnostics screen. */
internal object TrackerTraceboxUi {
    val configuration = TraceboxDiagnosticsUiConfiguration(
        strings = TraceboxDiagnosticsUiStrings(
            title = "Help improve Tracker",
            description =
                "If Tracker behaved unexpectedly, share local diagnostics with the developer.",
            supportTitle = "Share diagnostics with the developer",
            supportDescription =
                "Recent crashes, errors, and performance context can help identify what went wrong.",
            reviewAndShare = "Review and share with developer",
        ),
        // Tracker already owns the settings app bar, so the reusable screen should not repeat it.
        showHeading = false,
        primaryAction = TraceboxPrimaryAction.SHARE,
        packageActions = TraceboxPackageActions(
            // Enable this only when Tracker supplies a real authenticated uploader.
            upload = false,
            share = true,
            save = true,
            deleteAllData = true,
        ),
        advancedControls = TraceboxAdvancedControls(
            visible = true,
            initiallyExpanded = false,
        ),
        defaultPolicy = TrackerTraceboxRuntime.defaultPolicy,
    )
}
