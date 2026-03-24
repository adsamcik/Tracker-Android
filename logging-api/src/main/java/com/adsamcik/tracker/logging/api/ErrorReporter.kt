package com.adsamcik.tracker.logging.api

/**
 * Minimal error reporting contract. Implemented by :logger, consumed everywhere.
 */
interface ErrorReporter {
    fun report(message: String)
    fun report(exception: Throwable)
    fun log(message: String)
}
