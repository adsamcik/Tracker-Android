package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.DomainEventDao
import com.adsamcik.tracker.shared.base.database.data.DomainEventCursorEntity
import com.adsamcik.tracker.shared.base.database.data.DomainEventEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class DomainEventDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: DomainEventDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.domainEventDao()
	}

	@AfterEach
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

	private suspend fun DomainEventDao.remainingTimestamps(): List<Long> =
		observeSince(0L).first().map(DomainEventEntity::timestampMs)

	private fun eventAt(timestampMs: Long): DomainEventEntity =
		DomainEventEntity(
			eventType = "TestEvent",
			processorId = "test-processor",
			timestampMs = timestampMs,
			payload = "{}",
		)
}
