package com.adsamcik.tracker.maintenance

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** Eight hours at 1 Hz against an isolated file using the SQLiteX runtime shipped by the app. */
@RunWith(AndroidJUnit4::class)
class LongSessionStorageInstrumentationTest {
	private lateinit var context: Context
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
		database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
			.openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
			.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
			.build()
	}

	@After
	fun tearDown() {
		database.close()
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun eightHourSourceWalAndDeliveredOutboxRemainPrunableAtBoundedSize() = runBlocking {
		val wal = database.sourceEventWalDao()
		val projection = database.sourceProjectionStateDao()
		val eventPayload = ByteArray(EVENT_PAYLOAD_BYTES) { it.toByte() }
		val outboxPayload = ByteArray(OUTBOX_PAYLOAD_BYTES) { (it * 3).toByte() }
		projection.register(
			SourceProjectionRegistrationEntity(
				projectionId = PROJECTION_ID,
				projectionVersion = 1,
				activationOrdinal = 1L,
				retentionRequired = true,
				status = "ACTIVE",
				createdAtMs = BASE_TIME_MS,
			),
		)

		val insertMs = measureTimeMillis {
			for (batchStart in 0 until EVENT_COUNT step INSERT_BATCH_SIZE) {
				val batchEnd = minOf(batchStart + INSERT_BATCH_SIZE, EVENT_COUNT)
				database.withTransaction {
					for (index in batchStart until batchEnd) {
						val ordinal = index + 1L
						val eventTime = BASE_TIME_MS + index * 1_000L
						assertEquals(
							ordinal,
							wal.insertIgnoringDuplicate(
								sourceEvent(ordinal, eventTime, eventPayload),
							),
						)
						assertTrue(
							projection.insertOutbox(
								SourceProjectionOutboxEntity(
									stableId = "effect-$ordinal",
									projectionId = PROJECTION_ID,
									projectionVersion = 1,
									admissionOrdinal = ordinal,
									effectKind = "long-session-test",
									payloadVersion = 1,
									payload = outboxPayload,
									createdAtMs = eventTime,
									deliveredAtMs = eventTime + 1L,
								),
							) >= 0L,
						)
					}
				}
			}
		}
		projection.saveCheckpoint(
			SourceProjectionCheckpointEntity(
				projectionId = PROJECTION_ID,
				projectionVersion = 1,
				contiguousAdmissionOrdinal = EVENT_COUNT.toLong(),
				stateVersion = 1,
				updatedAtMs = END_TIME_MS,
			),
		)
		checkpointWal()

		val allocatedBytes = pragmaLong("page_count") * pragmaLong("page_size")
		val bytesPerEventPair = allocatedBytes.toDouble() / EVENT_COUNT
		Log.i(
			TAG,
			"inserted=$EVENT_COUNT insertMs=$insertMs allocatedBytes=$allocatedBytes " +
				"bytesPerEventPair=$bytesPerEventPair payloadBytes=${wal.payloadBytes()}",
		)
		assertEquals(EVENT_COUNT.toLong(), wal.countAll())
		assertEquals(EVENT_COUNT.toLong(), tableCount("source_projection_outbox"))
		assertEquals((EVENT_COUNT * EVENT_PAYLOAD_BYTES).toLong(), wal.payloadBytes())
		assertTrue(
			"Eight hours of source WAL plus delivered outbox exceeded the 64 MiB guardrail",
			allocatedBytes < MAX_ALLOCATED_BYTES,
		)

		val pruneMs: Long
		val pruneResult = run {
			var result = com.adsamcik.tracker.shared.base.database.SourceEventStoragePruneResult(0, 0)
			pruneMs = measureTimeMillis {
				// The last effect is delivered one millisecond after its source event, and
				// retention is intentionally strict (< cutoff), so advance past both values.
				result = database.pruneSourceEventStorageBefore(END_TIME_MS + 2L)
			}
			result
		}
		checkpointWal()
		val reusableBytes = pragmaLong("freelist_count") * pragmaLong("page_size")
		Log.i(
			TAG,
			"pruneMs=$pruneMs walDeleted=${pruneResult.walEventsDeleted} " +
				"outboxDeleted=${pruneResult.deliveredEffectsDeleted} reusableBytes=$reusableBytes",
		)
		assertEquals(EVENT_COUNT, pruneResult.walEventsDeleted)
		assertEquals(EVENT_COUNT, pruneResult.deliveredEffectsDeleted)
		assertEquals(0L, wal.countAll())
		assertEquals(0L, tableCount("source_projection_outbox"))
		assertTrue("Pruned pages should become reusable without requiring VACUUM", reusableBytes > 0L)
	}

	private fun sourceEvent(
		ordinal: Long,
		eventTime: Long,
		payload: ByteArray,
	) = SourceEventWalEntity(
		admissionOrdinal = ordinal,
		eventId = "event-$ordinal",
		providerDedupKey = null,
		logicalTrackingId = "eight-hour-session",
		serviceRunId = "service-run",
		sourceKind = 1,
		sourceInstanceId = "location-instance",
		registrationGeneration = 1L,
		sourceSequence = ordinal,
		configRevision = 1L,
		planAttribution = 0,
		clockDomainId = "boot-count:1",
		observedElapsedNanos = ordinal * 1_000_000_000L,
		receivedElapsedNanos = ordinal * 1_000_000_000L + 1L,
		wallTimeMs = eventTime,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = 0L,
		acquiredAtMs = eventTime,
		qualityFlags = 0L,
		qualityConfidence = 1f,
		payloadVersion = 1,
		payload = payload,
		payloadChecksum = "checksum-$ordinal",
		createdAtMs = eventTime,
	)

	private fun checkpointWal() {
		database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
	}

	private fun pragmaLong(name: String): Long =
		database.openHelper.readableDatabase.query("PRAGMA $name").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun tableCount(table: String): Long =
		database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private companion object {
		const val TAG = "LongSessionStorage"
		const val DATABASE_NAME = "long_session_storage_test.db"
		const val PROJECTION_ID = "long-session-projection"
		const val EVENT_COUNT = 8 * 60 * 60
		const val INSERT_BATCH_SIZE = 1_000
		const val EVENT_PAYLOAD_BYTES = 128
		const val OUTBOX_PAYLOAD_BYTES = 64
		const val MAX_ALLOCATED_BYTES = 64L * 1024L * 1024L
		const val BASE_TIME_MS = 1_700_000_000_000L
		const val END_TIME_MS = BASE_TIME_MS + (EVENT_COUNT - 1L) * 1_000L
	}
}
