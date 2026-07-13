package com.adsamcik.tracker.sbase

import android.app.Application
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DomainEventDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: DomainEventDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.domainEventDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `minimum cursor timestamp returns slowest consumer cursor`() = runTest {
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "fast", lastProcessedMs = 5_000L))
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "slow", lastProcessedMs = 2_000L))

		dao.getMinimumCursorTimestampMs() shouldBe 2_000L
	}

	@Test
	fun `minimum cursor timestamp is null when no consumer cursors exist`() = runTest {
		dao.getMinimumCursorTimestampMs().shouldBeNull()
	}

	@Test
	fun `idempotent insert ignores an already committed event batch`() = runTest {
		val event = eventAt(1_000L)

		dao.insertAllIdempotent(listOf(event))
		dao.insertAllIdempotent(listOf(event))

		dao.getUnconsumedBatchSeek(lastMs = 0L, lastId = 0L, limit = 10) shouldHaveSize 1
	}

	@Test
	fun `lagging cursor preserves unconsumed events during retention clamp`() = runTest {
		dao.insertAll(listOf(eventAt(1_000L), eventAt(2_000L), eventAt(3_000L)))
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "fast", lastProcessedMs = 10_000L))
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "slow", lastProcessedMs = 2_000L))

		val rawCutoff = 2_500L
		val clampedCutoff = minOf(rawCutoff, requireNotNull(dao.getMinimumCursorTimestampMs()))
		dao.deleteOlderThan(clampedCutoff)

		dao.remainingTimestamps() shouldContainExactly listOf(2_000L, 3_000L)
	}

	@Test
	fun `fully consumed old events purge when cursors are past raw cutoff`() = runTest {
		dao.insertAll(listOf(eventAt(1_000L), eventAt(2_000L), eventAt(4_000L)))
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "consumer-a", lastProcessedMs = 10_000L))
		dao.upsertCursor(DomainEventCursorEntity(consumerId = "consumer-b", lastProcessedMs = 12_000L))

		val rawCutoff = 3_000L
		val clampedCutoff = minOf(rawCutoff, requireNotNull(dao.getMinimumCursorTimestampMs()))
		dao.deleteOlderThan(clampedCutoff)

		dao.remainingTimestamps() shouldContainExactly listOf(4_000L)
	}

	@Test
	fun `observeSinceLimited emits newest bounded events in ascending order`() = runTest {
		dao.insertAll(listOf(eventAt(1_000L), eventAt(2_000L), eventAt(3_000L), eventAt(4_000L)))

		val timestamps = dao.observeSinceLimited(sinceMs = 0L, limit = 2)
			.first()
			.map(DomainEventEntity::timestampMs)

		timestamps shouldContainExactly listOf(3_000L, 4_000L)
	}

	@Test
	fun `composite cursor returns same-ms event after timestamp-only ack would have skipped it`() = runTest {
		// Insert two events sharing timestamp 1000ms — typical batch case (e.g.
		// session-end emits SessionEnded + DailySummaryUpdated at the same millis).
		dao.insertAll(listOf(eventAt(1_000L), eventAt(1_000L), eventAt(2_000L)))
		val all = dao.observeSince(0L).first().sortedBy { it.id }
		all.size shouldBe 3
		val firstAt1000 = all[0]
		val secondAt1000 = all[1]

		// Consumer ack the first event by (timestamp, id). Under the OLD timestamp-only
		// cursor this would have set lastProcessedMs = 1000 and the next fetch's
		// `timestamp_ms > 1000` predicate would have skipped the second event at 1000.
		dao.upsertCursor(
			DomainEventCursorEntity(
				consumerId = "c1",
				lastProcessedMs = firstAt1000.timestampMs,
				lastProcessedId = firstAt1000.id,
			)
		)

		// New seek-style query: PK-lookup cursor, then direct seek. MUST return the
		// second 1000ms event (id > lastId).
		val cursor = dao.getCursor("c1")
		val nextBatch = dao.getUnconsumedBatchSeek(
			lastMs = cursor?.lastProcessedMs ?: 0L,
			lastId = cursor?.lastProcessedId ?: 0L,
			limit = 99,
		)
		nextBatch.map { it.id } shouldContainExactly listOf(secondAt1000.id, all[2].id)
	}

	@Test
	fun `composite cursor stops a fully consumed same-ms boundary from being re-delivered`() = runTest {
		dao.insertAll(listOf(eventAt(1_000L), eventAt(1_000L)))
		val all = dao.observeSince(0L).first().sortedBy { it.id }
		val secondAt1000 = all[1]
		dao.upsertCursor(
			DomainEventCursorEntity(
				consumerId = "c1",
				lastProcessedMs = secondAt1000.timestampMs,
				lastProcessedId = secondAt1000.id,
			)
		)

		val cursor = dao.getCursor("c1")
		val next = dao.getUnconsumedBatchSeek(
			lastMs = cursor?.lastProcessedMs ?: 0L,
			lastId = cursor?.lastProcessedId ?: 0L,
			limit = 99,
		)
		next shouldBe emptyList()
	}

	// region seek-query planner regression
	// These tests guard against the SQLite planner regressing the seek query back to
	// a full index SCAN. The fix wraps two SEARCH-able halves in a UNION ALL, and the
	// EQP test below asserts that EXPLAIN QUERY PLAN never emits SCAN of `domain_event`.

	@Test
	fun `seek across same-ms clusters returns every event without skipping or duplication`() = runTest {
		// Build SEEK_CLUSTER_COUNT clusters each sharing a single timestamp. This is
		// the worst case for a same-ms boundary skip — drains MUST include every event
		// across batch boundaries, not just one per cluster.
		val events = buildList(SEEK_CLUSTER_COUNT * SEEK_EVENTS_PER_CLUSTER) {
			for (cluster in 0 until SEEK_CLUSTER_COUNT) {
				val ms = (cluster + 1) * SEEK_CLUSTER_STEP_MS
				repeat(SEEK_EVENTS_PER_CLUSTER) { add(eventAt(ms)) }
			}
		}
		dao.insertAll(events)

		val total = SEEK_CLUSTER_COUNT * SEEK_EVENTS_PER_CLUSTER
		val drained = mutableListOf<DomainEventEntity>()
		var lastMs = 0L
		var lastId = 0L
		var iterations = 0
		while (true) {
			iterations++
			check(iterations < total) {
				"seek drain failed to terminate after $iterations iterations (lastMs=$lastMs lastId=$lastId)"
			}
			val batch = dao.getUnconsumedBatchSeek(lastMs, lastId, SEEK_BATCH_LIMIT)
			if (batch.isEmpty()) break
			batch shouldHaveAtLeastSize 1
			drained += batch
			val tail = batch.last()
			lastMs = tail.timestampMs
			lastId = tail.id
		}

		drained shouldHaveSize total
		drained.map { it.id }.toSet() shouldHaveSize total

		// Drain must be in strict (timestamp_ms, id) ascending order.
		drained.zipWithNext { a, b ->
			val ordered = a.timestampMs < b.timestampMs ||
				(a.timestampMs == b.timestampMs && a.id < b.id)
			check(ordered) {
				"drain out of order at id=${a.id} -> id=${b.id} (ms ${a.timestampMs} -> ${b.timestampMs})"
			}
		}

		// Terminal seek: cursor at the very last event returns nothing.
		val terminal = drained.last()
		dao.getUnconsumedBatchSeek(terminal.timestampMs, terminal.id, SEEK_BATCH_LIMIT) shouldBe emptyList()
	}

	@Test
	fun `seek query plans as SEARCH not SCAN on the composite index`() = runTest {
		// Insert enough rows for the planner to actually consider the index. With < 8
		// rows SQLite may decide a SCAN of the table is cheaper than walking the
		// index, masking the regression we are guarding against.
		dao.insertAll(
			buildList(PLAN_PROBE_ROW_COUNT) {
				repeat(PLAN_PROBE_ROW_COUNT) { idx -> add(eventAt(idx * 100L + 1L)) }
			}
		)

		// Re-use the exact SQL Room generates for getUnconsumedBatchSeek. If the @Query
		// is reformulated to a form SQLite cannot decompose into SEARCH-able branches
		// (e.g. the OR predicate), this test fires.
		val explainSql = """
			EXPLAIN QUERY PLAN
			SELECT * FROM (
				SELECT * FROM (
					SELECT *
					FROM domain_event
					WHERE timestamp_ms = ? AND id > ?
					ORDER BY id ASC
					LIMIT ?
				)
				UNION ALL
				SELECT * FROM (
					SELECT *
					FROM domain_event
					WHERE timestamp_ms > ?
					ORDER BY timestamp_ms ASC, id ASC
					LIMIT ?
				)
			)
			ORDER BY timestamp_ms ASC, id ASC
			LIMIT ?
		""".trimIndent()

		val raw = database.openHelper.readableDatabase
		val args = arrayOf<Any>(
			0L,
			0L,
			PLAN_PROBE_LIMIT,
			0L,
			PLAN_PROBE_LIMIT,
			PLAN_PROBE_LIMIT,
		)
		val planLines = mutableListOf<String>()
		raw.query(explainSql, args).use { cursor: Cursor ->
			val detailColumn = cursor.getColumnIndexOrThrow("detail")
			while (cursor.moveToNext()) {
				planLines += cursor.getString(detailColumn)
			}
		}

		check(planLines.isNotEmpty()) { "EXPLAIN QUERY PLAN returned no rows" }

		// Hard regression guard: any SCAN of the domain_event table means the planner
		// regressed off the composite index.
		val scanOfTable = planLines.filter { line ->
			line.contains("SCAN") && line.contains("domain_event") &&
				!line.contains("USING INDEX")
		}
		check(scanOfTable.isEmpty()) {
			"Query plan regressed to SCAN of domain_event:\n${planLines.joinToString("\n")}"
		}

		// And it must SEARCH using the composite index for the bounded halves.
		val searchUsingComposite = planLines.count { line ->
			line.contains("SEARCH") && line.contains("domain_event") &&
				line.contains("index_domain_event_timestamp_ms_id")
		}
		check(searchUsingComposite >= 2) {
			"Expected ≥2 SEARCH steps on index_domain_event_timestamp_ms_id, got $searchUsingComposite:\n${planLines.joinToString("\n")}"
		}
	}

	// endregion seek-query planner regression

	private suspend fun DomainEventDao.remainingTimestamps(): List<Long> =
		observeSince(0L).first().map(DomainEventEntity::timestampMs)

	private fun eventAt(timestampMs: Long): DomainEventEntity =
		DomainEventEntity(
			eventType = "TestEvent",
			processorId = "test-processor",
			timestampMs = timestampMs,
			payload = "{}",
		)

	private companion object {
		const val SEEK_CLUSTER_COUNT = 10
		const val SEEK_EVENTS_PER_CLUSTER = 100
		const val SEEK_CLUSTER_STEP_MS = 1_000L
		const val SEEK_BATCH_LIMIT = 50
		const val PLAN_PROBE_ROW_COUNT = 64
		const val PLAN_PROBE_LIMIT = 32
	}
}
