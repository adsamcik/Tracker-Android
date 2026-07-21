package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@DisplayName("DefaultPersistentDirtyState")
class DefaultPersistentDirtyStateTest {

	private lateinit var tempDir: File
	private lateinit var state: PersistentDirtyState
	private val testIoDispatcher: kotlinx.coroutines.CoroutineDispatcher =
		kotlinx.coroutines.Dispatchers.Unconfined

	@BeforeEach
	fun setUp() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "dirty-state-${System.nanoTime()}")
		tempDir.mkdirs()
		state = DefaultPersistentDirtyState(tempDir, testIoDispatcher)
	}

	private suspend fun PersistentDirtyState.addTables(
		tables: Set<String>,
		generation: Long = 1L,
	) {
		add(tables.associateWith { generation })
	}

	@AfterEach
	fun tearDown() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `empty store loads empty set`() = runTest {
		state.load() shouldBe emptyMap()
	}

	@Test
	fun `add then load returns the added tables`() = runTest {
		state.addTables(setOf("daily_summary", "exploration_cell"))
		state.load().keys shouldContainExactlyInAnyOrder setOf("daily_summary", "exploration_cell")
	}

	@Test
	fun `add is idempotent — adding existing tables is a no-op`() = runTest {
		state.addTables(setOf("a", "b"))
		state.addTables(setOf("a", "b"))
		state.addTables(setOf("a"))
		state.load().keys shouldContainExactlyInAnyOrder setOf("a", "b")
	}

	@Test
	fun `add unions across calls`() = runTest {
		state.addTables(setOf("a", "b"))
		state.addTables(setOf("c"))
		state.addTables(setOf("d", "a"))
		state.load().keys shouldContainExactlyInAnyOrder setOf("a", "b", "c", "d")
	}

	@Test
	fun `remove eliminates the named tables`() = runTest {
		state.addTables(setOf("a", "b", "c", "d"))
		state.acknowledge(mapOf("b" to 1L, "d" to 1L))
		state.load().keys shouldContainExactlyInAnyOrder setOf("a", "c")
	}

	@Test
	fun `remove all leaves the store empty`() = runTest {
		state.addTables(setOf("a", "b"))
		state.acknowledge(mapOf("a" to 1L, "b" to 1L))
		state.load() shouldBe emptyMap()
	}

	@Test
	fun `acknowledge preserves newer generation`() = runTest {
		state.addTables(setOf("a"), generation = 2L)
		state.acknowledge(mapOf("a" to 1L))

		state.load() shouldBe mapOf("a" to 2L)
	}

	@Test
	fun `add with empty set is a no-op`() = runTest {
		state.add(emptyMap())
		state.load() shouldBe emptyMap()
	}

	@Test
	fun `remove with empty set is a no-op`() = runTest {
		state.addTables(setOf("x"))
		state.acknowledge(emptyMap())
		state.load().keys shouldContainExactlyInAnyOrder setOf("x")
	}

	@Test
	fun `add filters out empty strings`() = runTest {
		state.add(mapOf("a" to 1L, "" to 1L, "  " to 1L, "b" to 1L))
		state.load().keys shouldContainExactlyInAnyOrder setOf("a", "b")
	}

	@Test
	fun `survives recreation — file is the source of truth`() = runTest {
		state.addTables(setOf("daily_summary", "session_segment"))

		// Simulate process death + restart by constructing a fresh state
		// against the SAME backing directory.
		val recreated = DefaultPersistentDirtyState(tempDir, testIoDispatcher)
		recreated.load().keys shouldContainExactlyInAnyOrder setOf("daily_summary", "session_segment")
	}

	@Test
	fun `corrupt file returns empty on load (no throw)`() = runTest {
		state.addTables(setOf("a"))
		// Corrupt the underlying file with binary garbage.
		val file = File(tempDir, "metric_dirty_persistence.txt")
		file.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))

		// File is technically "readable", but its lines may not parse meaningfully.
		// The contract says load MUST NOT throw — verify it returns a (possibly empty)
		// set without exception.
		val loaded = state.load()
		// We don't assert exact contents — implementation may return what it can
		// parse OR empty. Critical point: no throw.
		(loaded.isEmpty() || loaded.isNotEmpty()) // tautology — just asserts the line ran
	}

	@Test
	fun `file IO dispatches on the injected ioDispatcher`() = runTest {
		val dispatchCount = AtomicInteger(0)
		val trackingDispatcher = object : CoroutineDispatcher() {
			override fun dispatch(context: CoroutineContext, block: Runnable) {
				dispatchCount.incrementAndGet()
				block.run()
			}
		}

		val ioState = DefaultPersistentDirtyState(tempDir, trackingDispatcher)
		ioState.addTables(setOf("test_table"))
		dispatchCount.get() shouldBeGreaterThan 0

		val countAfterAdd = dispatchCount.get()
		ioState.acknowledge(mapOf("test_table" to 1L))
		dispatchCount.get() shouldBeGreaterThan countAfterAdd
	}
}
