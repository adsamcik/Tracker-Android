package com.adsamcik.tracker.tracker.data

/**
 * Sealed result type for database persistence operations.
 * 
 * Per copilot-instructions Section 14:
 * - Errors crossing module boundaries must surface as sealed results
 * - All public cross-module operation outcomes MUST be represented by a sealed type suffixed with `Result`
 * 
 * Used by database components (LocationComponent, CellComponent, WifiComponent)
 * to report persistence failures instead of silently swallowing exceptions.
 */
sealed class PersistenceResult {
    /**
     * Persistence operation succeeded.
     * @param recordCount Number of records successfully persisted.
     */
    data class Success(val recordCount: Int = 1) : PersistenceResult()
    
    /**
     * Persistence operation failed with a recoverable error.
     * The system may retry or continue operating.
     * 
     * @param source Identifies which component encountered the error (e.g., "location", "cell", "wifi").
     * @param message Human-readable error description.
     * @param cause The underlying exception, if available.
     */
    data class Failure(
        val source: String,
        val message: String,
        val cause: Throwable? = null
    ) : PersistenceResult()
}

/**
 * Error event emitted when database persistence fails.
 * Contains information for logging, user notification, and debugging.
 * 
 * @param source The component that encountered the error (e.g., "LocationComponent").
 * @param operation Description of the failed operation (e.g., "batch insert locations").
 * @param recordCount Number of records that failed to persist.
 * @param cause The underlying exception.
 * @param timestamp When the error occurred (epoch millis).
 */
data class PersistenceError(
    val source: String,
    val operation: String,
    val recordCount: Int,
    val cause: Throwable,
    val timestamp: Long = System.currentTimeMillis()
)
