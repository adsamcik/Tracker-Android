package com.adsamcik.tracker.tracker.data

import kotlinx.coroutines.flow.SharedFlow

/**
 * Interface for collecting and observing database persistence errors.
 * 
 * Components report failures via [reportError], and observers
 * (e.g., TrackerService, UI) collect errors from [errors] flow.
 * 
 * Per copilot-instructions Section 14:
 * - Errors crossing module boundaries must surface as sealed results
 * - Represent transient sensor degradation as structured state
 */
interface PersistenceErrorCollector {
    /**
     * Flow of persistence errors for observation.
     * Observers can display to user, log, or take corrective action.
     */
    val errors: SharedFlow<PersistenceError>
    
    /**
     * Report a persistence failure.
     * 
     * @param error The error details.
     */
    suspend fun reportError(error: PersistenceError)
    
    /**
     * Report a persistence failure (fire-and-forget from non-suspending context).
     * 
     * @param error The error details.
     */
    fun reportErrorAsync(error: PersistenceError)
}
