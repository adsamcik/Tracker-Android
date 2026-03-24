package com.adsamcik.tracker.logger.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Logger-internal dispatcher abstraction.
 */
internal object LoggerDispatchers {
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val default: CoroutineDispatcher get() = Dispatchers.Default
}
