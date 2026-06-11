package com.adsamcik.tracker.shared.base.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction over coroutine dispatchers to enable test dispatcher swapping.
 */
interface DispatchersProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

object DefaultDispatchersProvider : DispatchersProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

/** Test implementation can override any dispatcher with a single supplied one. */
class TestDispatchersProvider(private val dispatcher: CoroutineDispatcher) : DispatchersProvider {
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
    override val unconfined: CoroutineDispatcher get() = dispatcher
}
