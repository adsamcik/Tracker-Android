package com.adsamcik.tracker.app.tracebox

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.adsamcik.tracker.R
import dev.tracebox.ui.compose.TraceboxAdvancedControls
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiConfiguration
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiStrings
import dev.tracebox.ui.compose.TraceboxPackageActions
import dev.tracebox.ui.compose.TraceboxPrimaryAction

/** Tracker's product choices for Tracebox's reusable casual/advanced diagnostics screen. */
internal object TrackerTraceboxUi {
    @Composable
    fun configuration(): TraceboxDiagnosticsUiConfiguration = configuration(
        strings = TraceboxDiagnosticsUiStrings(
            title = stringResource(R.string.settings_tracebox_title),
            description = stringResource(R.string.settings_tracebox_root_summary),
        ),
    )

    internal fun configuration(
        strings: TraceboxDiagnosticsUiStrings,
    ) = TraceboxDiagnosticsUiConfiguration(
        strings = strings,
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
