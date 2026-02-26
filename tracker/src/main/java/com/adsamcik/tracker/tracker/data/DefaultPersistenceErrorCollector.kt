package com.adsamcik.tracker.tracker.data

import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Default implementation of [PersistenceErrorCollector].
 * 
 * Uses a SharedFlow to broadcast persistence errors to all observers.
 * Maintains a small replay buffer to ensure errors aren't lost if
 * observers start collecting after an error occurs.
 */
class DefaultPersistenceErrorCollector : PersistenceErrorCollector {
    
    private val scope = CoroutineScope(SupervisorJob() + DefaultDispatchersProvider.default)
    
    private val _errors = MutableSharedFlow<PersistenceError>(
        replay = 5,
        extraBufferCapacity = 10
    )
    
    override val errors: SharedFlow<PersistenceError> = _errors.asSharedFlow()
    
    override suspend fun reportError(error: PersistenceError) {
        _errors.emit(error)
    }
    
    override fun reportErrorAsync(error: PersistenceError) {
        scope.launch {
            _errors.emit(error)
        }
    }
    
    /**
     * Cancels the internal coroutine scope, stopping any pending async operations.
     * Should be called when this collector is no longer needed.
     */
    fun clear() {
        scope.coroutineContext[Job]?.cancelChildren()
    }
}
