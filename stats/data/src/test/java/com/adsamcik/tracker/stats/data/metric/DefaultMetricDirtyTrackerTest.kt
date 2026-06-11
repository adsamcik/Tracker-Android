package com.adsamcik.tracker.stats.data.metric

import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker.Consumer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefaultMetricDirtyTracker")
class DefaultMetricDirtyTrackerTest {

	private val tracker = DefaultMetricDirtyTracker()

	@Test
	fun `idle consumeDirty returns empty for every consumer`() {
		tracker.consumeDirty(Consumer.LIVE).shouldBeEmpty()
		tracker.consumeDirty(Consumer.PERSISTENCE).shouldBeEmpty()
	}

	@Test
	fun `markDirty then consume returns the marked table once for LIVE`() {
		tracker.markDirty("daily_summary")
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactly setOf("daily_summary")
	}

	@Test
	fun `consume is idempotent — second consume returns empty for same consumer`() {
		tracker.markDirty("session_segment")
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactly setOf("session_segment")
		tracker.consumeDirty(Consumer.LIVE).shouldBeEmpty()
	}

	@Test
	fun `multiple markDirty calls union the set`() {
		tracker.markDirty("daily_summary")
		tracker.markDirty("session_segment")
		tracker.markDirty("daily_summary")
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}

	@Test
	fun `markDirty with set unions the elements`() {
		tracker.markDirty(setOf("daily_summary", "session_segment"))
		tracker.markDirty(setOf("exploration_cell"))
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment", "exploration_cell",
		)
	}

	@Test
	fun `markDirty with empty set is no-op`() {
		tracker.markDirty(emptySet())
		tracker.consumeDirty(Consumer.LIVE).shouldBeEmpty()
	}

	@Test
	fun `concurrent markDirty calls never lose marks for any consumer`() {
		val tables = (1..50).map { "table_$it" }
		val threads = tables.map { t ->
			Thread { repeat(100) { tracker.markDirty(t) } }
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }
		// All marks must be visible to each independent consumer.
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder tables.toSet()
		tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder tables.toSet()
	}

	// --- R1+R2 round-6 starvation regression: PERSISTENCE consumer must NOT be
	// drained when LIVE consumes, and vice versa. ----------------------------

	@Test
	fun `markDirty fans out to all consumers — both see the same writes`() {
		tracker.markDirty(setOf("daily_summary", "session_segment"))
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
		// PERSISTENCE consumer still sees its own copy — LIVE's drain did not affect it.
		tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}

	@Test
	fun `LIVE consume does not drain PERSISTENCE set`() {
		tracker.markDirty("exploration_cell")
		tracker.consumeDirty(Consumer.LIVE).shouldContainExactly(setOf("exploration_cell"))
		// PERSISTENCE still has the bit set
		tracker.consumeDirty(Consumer.PERSISTENCE).shouldContainExactly(setOf("exploration_cell"))
	}

	@Test
	fun `PERSISTENCE consume does not drain LIVE set`() {
		tracker.markDirty("daily_summary")
		tracker.consumeDirty(Consumer.PERSISTENCE).shouldContainExactly(setOf("daily_summary"))
		// LIVE still has its independent copy
		tracker.consumeDirty(Consumer.LIVE).shouldContainExactly(setOf("daily_summary"))
	}

	@Test
	fun `consumers track independently after staggered writes`() {
		tracker.markDirty("daily_summary")
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactly setOf("daily_summary")
		// Between LIVE's two reads, another write happens.
		tracker.markDirty("session_segment")
		// LIVE only sees the post-consume write.
		tracker.consumeDirty(Consumer.LIVE) shouldContainExactly setOf("session_segment")
		// PERSISTENCE accumulated both writes since it never consumed.
		tracker.consumeDirty(Consumer.PERSISTENCE) shouldContainExactlyInAnyOrder setOf(
			"daily_summary", "session_segment",
		)
	}
}
