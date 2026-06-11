package com.adsamcik.tracker.testing

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher

/**
 * Test implementation of DispatchersProvider using TestDispatcher for deterministic coroutine execution.
 *
 * Contract:
 * - Input: Single TestDispatcher instance (typically StandardTestDispatcher)
 * - Output: All dispatcher properties return the same TestDispatcher
 * - Thread-safety: TestDispatcher is thread-safe
 * - Lifecycle: Test-scoped (create new instance per test)
 *
 * Usage:
 * ```kotlin
 * @Test
 * fun `verify async operation completes`() = runTest {
 *     val testDispatcher = StandardTestDispatcher(testScheduler)
 *     val dispatchers = TestDispatchersProvider(testDispatcher)
 *     val repository = MyRepository(dispatchers)
 *
 *     repository.fetchData()
 *     advanceUntilIdle() // Execute all pending coroutines
 *
 *     assertEquals(expectedData, repository.data.value)
 * }
 * ```
 */
class TestDispatchersProvider(
    val testDispatcher: TestDispatcher
) : DispatchersProvider {
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val unconfined: CoroutineDispatcher = testDispatcher
}
