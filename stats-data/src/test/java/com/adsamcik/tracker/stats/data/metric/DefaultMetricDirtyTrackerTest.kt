package com.adsamcik.tracker.stats.data.metric

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultMetricDirtyTracker")
class DefaultMetricDirtyTrackerTest {

	private val tracker = DefaultMetricDirtyTracker()

	@Test
	fun `idle consumeDirty returns empty`() {
		tracker.consumeDirty().shouldBeEmpty()
	}

	@Test
	fun `markDirty then consume returns the marked table once`() {
		tracker.markDirty("daily_summary")
		tracker.consumeDirty() shouldContainExactly setOf("daily_summary")
	}

	@Test
	fun `consume is idempotent — second consume returns empty`() {
		tracker.markDirty("session_segment")
		tracker.consumeDirty() shouldContainExactly setOf("session_segment")
		tracker.consumeDirty().shouldBeEmpty()
	}

	@Test
	fun `multiple markDirty calls union the set`() {
		tracker.markDirty("daily_summary")
		tracker.markDirty("session_segment")
		tracker.markDirty("daily_summary")
		tracker.consumeDirty() shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}

	@Test
	fun `markDirty with set unions the elements`() {
		tracker.markDirty(setOf("daily_summary", "session_segment"))
		tracker.markDirty(setOf("exploration_cell"))
		tracker.consumeDirty() shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment", "exploration_cell",
		)
	}

	@Test
	fun `markDirty with empty set is no-op`() {
		tracker.markDirty(emptySet())
		tracker.consumeDirty().shouldBeEmpty()
	}

	@Test
	fun `concurrent markDirty calls never lose marks`() {
		val tables = (1..50).map { "table_$it" }
		val threads = tables.map { t ->
			Thread { repeat(100) { tracker.markDirty(t) } }
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }
		tracker.consumeDirty() shouldContainExactlyInAnyOrder tables.toSet()
	}
}
