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
