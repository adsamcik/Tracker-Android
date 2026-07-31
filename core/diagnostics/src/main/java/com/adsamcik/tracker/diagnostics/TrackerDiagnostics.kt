package com.adsamcik.tracker.diagnostics

/**
 * Android-independent handoff from Tracker call sites to the installed diagnostics backend.
 *
 * The boundary deliberately accepts only a fixed code. Messages, tags, exception objects, and
 * arbitrary maps cannot cross into the backend.
 */
fun interface TrackerDiagnosticSink {
    fun record(code: TrackerDiagnosticCode)
}

object TrackerDiagnostics {
    private val noOpSink = TrackerDiagnosticSink { }

    @Volatile
    private var sink: TrackerDiagnosticSink = noOpSink

    fun install(newSink: TrackerDiagnosticSink) {
        sink = newSink
    }

    fun record(code: TrackerDiagnosticCode) {
        try {
            sink.record(code)
        } catch (_: RuntimeException) {
            // Diagnostics must never become a failure source for Tracker.
        }
    }

    /**
     * Clears only the currently installed sink. Intended for deterministic test and shutdown
     * cleanup; the application normally installs one sink for the process lifetime.
     */
    fun clear() {
        sink = noOpSink
    }
}
