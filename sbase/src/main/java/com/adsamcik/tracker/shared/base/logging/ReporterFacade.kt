package com.adsamcik.tracker.shared.base.logging

/**
 * Minimal logging facade to avoid hard dependency on :logger from foundational modules.
 * Modules can call ReporterFacade; the :logger module installs a delegate at runtime.
 */
interface ErrorReporter {
    fun report(message: String)
    fun report(exception: Throwable)
    fun log(message: String)
}

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
}
