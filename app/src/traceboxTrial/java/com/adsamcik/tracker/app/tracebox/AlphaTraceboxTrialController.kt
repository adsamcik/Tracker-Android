package com.adsamcik.tracker.app.tracebox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.tracebox.Tracebox
import io.github.tracebox.TraceboxConfiguration
import io.github.tracebox.api.DeleteRequest
import io.github.tracebox.api.DiagnosticCode
import io.github.tracebox.api.DiagnosticsProfile
import io.github.tracebox.api.GeneratedBreadcrumb
import io.github.tracebox.api.PolicyUpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the current Tracebox alpha into Tracker's explicitly opt-in trial.
 *
 * The alpha handle is installed disabled so the process cannot accept generated events before the
 * user opts in. It is deliberately limited to an application-owned structural code emitted at
 * opt-in; it does not receive Tracker data, exceptions, locations, or free-form values.
 */
@Singleton
class AlphaTraceboxTrialController @Inject constructor(
    @ApplicationContext context: Context,
) : TraceboxTrialController {
    private val lock = Any()
    private val handle = Tracebox.install(
        context,
        TraceboxConfiguration.builder()
            .setInitialProfile(DiagnosticsProfile.Disabled)
            .build(),
    )
    private val mutableIsEnabled = MutableStateFlow(false)

    override val isEnabled: StateFlow<Boolean> = mutableIsEnabled.asStateFlow()

    override fun enableForCurrentAppRun() {
        synchronized(lock) {
            if (mutableIsEnabled.value) return

            val result = handle.updateProfile(DiagnosticsProfile.StandardDiagnostics)
            if (result is PolicyUpdateResult.Applied) {
                mutableIsEnabled.value = true
                handle.diagnostics.breadcrumb(
                    GeneratedBreadcrumb(DiagnosticCode.of(TRIAL_ENABLED_CODE)),
                )
            }
        }
    }

    override fun disableAndClear() {
        synchronized(lock) {
            handle.updateProfile(DiagnosticsProfile.Disabled)
            handle.delete(DeleteRequest.All)
            mutableIsEnabled.value = false
        }
    }

    private companion object {
        /** Application-owned trial code: the user enabled Tracebox for this process. */
        const val TRIAL_ENABLED_CODE = 900_001
    }
}
