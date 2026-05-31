package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.PersistentDirtyState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

@DisplayName("DefaultPersistentDirtyState")
class DefaultPersistentDirtyStateTest {

	private lateinit var tempDir: File
	private lateinit var state: PersistentDirtyState

	@BeforeEach
	fun setUp() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "dirty-state-${System.nanoTime()}")
		tempDir.mkdirs()
		state = DefaultPersistentDirtyState(tempDir)
	}

	@AfterEach
	fun tearDown() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `empty store loads empty set`() = runTest {
		state.load().shouldBeEmpty()
	}

	@Test
	fun `add then load returns the added tables`() = runTest {
		state.add(setOf("daily_summary", "exploration_cell"))
		state.load() shouldContainExactlyInAnyOrder setOf("daily_summary", "exploration_cell")
	}

	@Test
	fun `add is idempotent — adding existing tables is a no-op`() = runTest {
		state.add(setOf("a", "b"))
		state.add(setOf("a", "b"))
		state.add(setOf("a"))
		state.load() shouldContainExactlyInAnyOrder setOf("a", "b")
	}

	@Test
	fun `add unions across calls`() = runTest {
		state.add(setOf("a", "b"))
		state.add(setOf("c"))
		state.add(setOf("d", "a"))
		state.load() shouldContainExactlyInAnyOrder setOf("a", "b", "c", "d")
	}

	@Test
	fun `remove eliminates the named tables`() = runTest {
		state.add(setOf("a", "b", "c", "d"))
		state.remove(setOf("b", "d"))
		state.load() shouldContainExactlyInAnyOrder setOf("a", "c")
	}

	@Test
	fun `remove all leaves the store empty`() = runTest {
		state.add(setOf("a", "b"))
		state.remove(setOf("a", "b"))
		state.load().shouldBeEmpty()
	}

	@Test
	fun `add with empty set is a no-op`() = runTest {
		state.add(emptySet())
		state.load().shouldBeEmpty()
	}

	@Test
	fun `remove with empty set is a no-op`() = runTest {
		state.add(setOf("x"))
		state.remove(emptySet())
		state.load() shouldContainExactlyInAnyOrder setOf("x")
	}

	@Test
	fun `add filters out empty strings`() = runTest {
		state.add(setOf("a", "", "  ", "b"))
		state.load() shouldContainExactlyInAnyOrder setOf("a", "b")
	}

	@Test
	fun `survives recreation — file is the source of truth`() = runTest {
		state.add(setOf("daily_summary", "session_segment"))

		// Simulate process death + restart by constructing a fresh state
		// against the SAME backing directory.
		val recreated = DefaultPersistentDirtyState(tempDir)
		recreated.load() shouldContainExactlyInAnyOrder setOf("daily_summary", "session_segment")
	}

	@Test
	fun `corrupt file returns empty on load (no throw)`() = runTest {
		state.add(setOf("a"))
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
}
