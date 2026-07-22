package com.adsamcik.tracker.app.tracebox

import kotlinx.coroutines.flow.StateFlow

/**
 * App-facing control surface for the privacy-bounded Tracebox alpha trial.
 *
 * Implementations must start disabled for every app process. While enabled, they may accept only
 * predefined structural codes into a bounded in-memory buffer. They must not accept free-form or
 * user data, persist or transmit the buffer, or capture crashes or ANRs.
 */
interface TraceboxTrialController {
    val isEnabled: StateFlow<Boolean>

    /** Enables structural-code recording until this app process ends or [disableAndClear] is called. */
    fun enableForCurrentAppRun()

    /** Stops recording immediately and clears every code currently held in memory. */
    fun disableAndClear()
}
