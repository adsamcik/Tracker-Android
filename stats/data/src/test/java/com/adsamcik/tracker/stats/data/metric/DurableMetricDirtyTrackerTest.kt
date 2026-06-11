package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** In-memory fake [PersistentDirtyState] for deterministic test setup. */
private class FakePersistentDirtyState(
	initial: Set<String> = emptySet(),
) : PersistentDirtyState {
	private val state = HashSet(initial)
	val snapshot: Set<String> get() = synchronized(state) { state.toSet() }

	override suspend fun load(): Set<String> = synchronized(state) { state.toSet() }
	override suspend fun add(tables: Set<String>) {
		synchronized(state) { state += tables.filter { it.isNotEmpty() } }
	}
	override suspend fun remove(tables: Set<String>) {
		synchronized(state) { state -= tables }
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DurableMetricDirtyTracker")
class DurableMetricDirtyTrackerTest {

	private val testDispatcher = UnconfinedTestDispatcher()
	private lateinit var scope: CoroutineScope
	private lateinit var persistent: FakePersistentDirtyState

	@BeforeEach
	fun setUp() {
		scope = TestScope(testDispatcher + SupervisorJob())
		persistent = FakePersistentDirtyState()
	}

	@AfterEach
	fun tearDown() {
		scope.cancel()
	}

	private fun newTracker(initialPersisted: Set<String> = emptySet()): DurableMetricDirtyTracker {
		persistent = FakePersistentDirtyState(initialPersisted)
		return DurableMetricDirtyTracker(
			delegate = DefaultMetricDirtyTracker(),
			persistentState = persistent,
			persistenceScope = scope,
			ioDispatcher = testDispatcher,
		)
	}

	@Test
	fun `init with empty persisted state — tracker starts clean`() = runTest(testDispatcher) {
		val tracker = newTracker()
		advanceUntilIdle()
		tracker.consumeDirty(Consumer.PERSISTENCE).shouldBeEmpty()
		tracker.consumeDirty(Consumer.LIVE).shouldBeEmpty()
	}

	@Test
	fun `init with persisted bits — PERSISTENCE consumer sees them after rehydration`() =
		runTest(testDispatcher) {
			val tracker = newTracker(initialPersisted = setOf("daily_summary", "exploration_cell"))
			advanceUntilIdle()
			tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf(
				"daily_summary", "exploration_cell",
			)
		}

	@Test
	fun `init with persisted bits — LIVE consumer ALSO sees them (in-memory parity)`() =
		runTest(testDispatcher) {
			val tracker = newTracker(initialPersisted = setOf("daily_summary"))
			advanceUntilIdle()
			// Rehydration fans out to BOTH consumer in-memory views (parity with
			// what the original markDirty would have done before process death).
			tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf("daily_summary")
		}

	@Test
	fun `markDirty single table — persists to disk`() = runTest(testDispatcher) {
		val tracker = newTracker()
		tracker.markDirty("session_segment")
		advanceUntilIdle()
		persistent.snapshot shouldContainExactlyInAnyOrder setOf("session_segment")
	}

	@Test
	fun `markDirty set — persists all`() = runTest(testDispatcher) {
		val tracker = newTracker()
		tracker.markDirty(setOf("a", "b", "c"))
		advanceUntilIdle()
		persistent.snapshot shouldContainExactlyInAnyOrder setOf("a", "b", "c")
	}

	@Test
	fun `consumeDirty PERSISTENCE — removes from disk`() = runTest(testDispatcher) {
		val tracker = newTracker()
		tracker.markDirty(setOf("a", "b"))
		advanceUntilIdle()
		tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf("a", "b")
		advanceUntilIdle()
		persistent.snapshot.shouldBeEmpty()
	}

	@Test
	fun `consumeDirty LIVE — does NOT touch disk (PERSISTENCE bits stay)`() =
		runTest(testDispatcher) {
			val tracker = newTracker()
			tracker.markDirty(setOf("a", "b"))
			advanceUntilIdle()

			tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf("a", "b")
			advanceUntilIdle()

			// Disk still has them — PERSISTENCE consumer needs them for recovery.
			persistent.snapshot shouldContainExactlyInAnyOrder setOf("a", "b")
		}

	@Test
	fun `process-death recovery — marks survive tracker recreation against the same store`() =
		runTest(testDispatcher) {
			val sharedStore = FakePersistentDirtyState()
			val firstTracker = DurableMetricDirtyTracker(
				delegate = DefaultMetricDirtyTracker(),
				persistentState = sharedStore,
				persistenceScope = scope,
				ioDispatcher = testDispatcher,
			)
			firstTracker.markDirty(setOf("daily_summary", "exploration_cell"))
			advanceUntilIdle()

			// Simulate process death: drop the first tracker, create a new one
			// pointing at the SAME store (as if the new process opened the same
			// `filesDir/metric_dirty_persistence.txt`).
			val secondTracker = DurableMetricDirtyTracker(
				delegate = DefaultMetricDirtyTracker(),
				persistentState = sharedStore,
				persistenceScope = scope,
				ioDispatcher = testDispatcher,
			)
			advanceUntilIdle()

			// The second tracker's PERSISTENCE consumer should see the bits the
			// first tracker marked, even though the first tracker's in-memory CAS
			// state died with it.
			secondTracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf(
				"daily_summary", "exploration_cell",
			)
		}

	@Test
	fun `consumeDirty PERSISTENCE persists removal even when LIVE consumes first`() =
		runTest(testDispatcher) {
			val tracker = newTracker()
			tracker.markDirty(setOf("a"))
			advanceUntilIdle()

			// LIVE drains first (doesn't touch disk).
			tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf("a")
			advanceUntilIdle()
			persistent.snapshot shouldContainExactlyInAnyOrder setOf("a") // still on disk

			// PERSISTENCE drains second — now removed from disk.
			tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf("a")
			advanceUntilIdle()
			persistent.snapshot.shouldBeEmpty()
		}

	@Test
	fun `markDirty empty set — no disk activity`() = runTest(testDispatcher) {
		val tracker = newTracker()
		tracker.markDirty(emptySet())
		advanceUntilIdle()
		persistent.snapshot.shouldBeEmpty()
	}

	@Test
	fun `consumeDirty empty in-memory — no disk removal scheduled`() = runTest(testDispatcher) {
		// Pre-populate disk but NOT in-memory. consumeDirty returns empty (in-memory
		// is the source of truth post-init). Disk should NOT be modified.
		val sharedStore = FakePersistentDirtyState(setOf("preexisting"))
		// Construct WITHOUT going through the rehydration init — use the delegate
		// directly so we can pin "in-memory empty but disk non-empty" state.
		val tracker = DurableMetricDirtyTracker(
			delegate = DefaultMetricDirtyTracker(), // fresh, empty
			persistentState = sharedStore,
			persistenceScope = scope,
			ioDispatcher = testDispatcher,
		)
		advanceUntilIdle()
		// init() rehydrated → in-memory has "preexisting"; consume it.
		tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf("preexisting")
		advanceUntilIdle()
		sharedStore.snapshot.shouldBeEmpty()

		// Now in-memory is empty AND disk is empty. consumeDirty returns empty.
		tracker.consumeDirty(Consumer.PERSISTENCE).shouldBeEmpty()
		advanceUntilIdle()
		sharedStore.snapshot.shouldBeEmpty()
	}

	@Test
	fun `consumeDirty PERSISTENCE blocks until async rehydration completes`() {
		// Use StandardTestDispatcher so the init coroutine does NOT execute
		// eagerly — we control when it runs via advanceUntilIdle.
		val standardDispatcher = StandardTestDispatcher()
		val controlledScope = CoroutineScope(standardDispatcher + SupervisorJob())

		// Gate load behind a CompletableDeferred so we can verify the worker
		// thread actually blocks on rehydrationComplete.
		val loadGate = CompletableDeferred<Unit>()
		val gatedState = object : PersistentDirtyState {
			override suspend fun load(): Set<String> {
				loadGate.await()
				return setOf("recovered")
			}
			override suspend fun add(tables: Set<String>) {}
			override suspend fun remove(tables: Set<String>) {}
		}

		val tracker = DurableMetricDirtyTracker(
			delegate = DefaultMetricDirtyTracker(),
			persistentState = gatedState,
			persistenceScope = controlledScope,
			ioDispatcher = standardDispatcher,
		)

		// Advance so the init coroutine starts — it will suspend on loadGate.
		standardDispatcher.scheduler.advanceUntilIdle()

		val result = AtomicReference<Set<String>>()
		val finished = CountDownLatch(1)
		val workerThread = Thread {
			result.set(tracker.consumeDirty(Consumer.PERSISTENCE))
			finished.countDown()
		}
		workerThread.start()

		// Worker should be blocked (rehydration not complete yet).
		finished.await(300, TimeUnit.MILLISECONDS) shouldBe false

		// Complete the gate and advance → rehydration finishes → worker unblocks.
		loadGate.complete(Unit)
		standardDispatcher.scheduler.advanceUntilIdle()

		finished.await(2, TimeUnit.SECONDS) shouldBe true
		result.get() shouldContainExactlyInAnyOrder setOf("recovered")

		controlledScope.cancel()
	}

	@Test
	fun `consumeDirty LIVE does NOT block on rehydration`() {
		// LIVE consumers should never block, even if rehydration hasn't finished.
		val standardDispatcher = StandardTestDispatcher()
		val controlledScope = CoroutineScope(standardDispatcher + SupervisorJob())

		val neverCompletingState = object : PersistentDirtyState {
			override suspend fun load(): Set<String> {
				// Suspend forever — rehydration never completes.
				CompletableDeferred<Unit>().await()
				@Suppress("UNREACHABLE_CODE")
				return emptySet()
			}
			override suspend fun add(tables: Set<String>) {}
			override suspend fun remove(tables: Set<String>) {}
		}

		val tracker = DurableMetricDirtyTracker(
			delegate = DefaultMetricDirtyTracker(),
			persistentState = neverCompletingState,
			persistenceScope = controlledScope,
			ioDispatcher = standardDispatcher,
		)
		standardDispatcher.scheduler.advanceUntilIdle()

		// LIVE consume should return immediately (empty, no blocking).
		val finished = CountDownLatch(1)
		val result = AtomicReference<Set<String>>()
		val workerThread = Thread {
			result.set(tracker.consumeDirty(Consumer.LIVE))
			finished.countDown()
		}
		workerThread.start()

		finished.await(2, TimeUnit.SECONDS) shouldBe true
		result.get().shouldBeEmpty()

		controlledScope.cancel()
	}
}
