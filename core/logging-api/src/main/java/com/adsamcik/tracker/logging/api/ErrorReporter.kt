package com.adsamcik.tracker.logging.api

/**
 * Minimal error reporting contract. Implemented by :logger, consumed everywhere.
 */
interface ErrorReporter {
    fun report(message: String)
    fun report(exception: Throwable)
    fun log(message: String)

    /**
     * Emits a low-severity diagnostic event with a stable source label.
     *
     * Existing implementations retain a compatible default while the production logger can map
     * this to an informational log level.
     */
    fun info(source: String, message: String) {
        log("[$source] $message")
    }
}
