package com.adsamcik.tracker.logging.api

/**
 * Minimal logging facade to avoid hard dependency on :logger from foundational modules.
 * Modules call ReporterFacade; the :logger module installs a delegate at runtime.
 */
object ReporterFacade {
    @Volatile
    private var delegate: ErrorReporter? = null

    fun setDelegate(d: ErrorReporter) {
        delegate = d
    }

    fun report(message: String) {
        delegate?.report(message)
    }

    fun report(exception: Throwable) {
        delegate?.report(exception)
    }

    fun log(message: String) {
        delegate?.log(message)
    }

    /** Emits a low-severity diagnostic event when the logger module is available. */
    fun info(source: String, message: String) {
        delegate?.info(source, message)
    }
}
