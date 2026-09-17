package com.adsamcik.tracker.diagnostics

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomTrackingDiagnosticStoreTest {
	private lateinit var context: Application
	private lateinit var database: TrackingDiagnosticDatabase
	private var nowMs = 1_000L

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		database = Room.inMemoryDatabaseBuilder(
			context,
			TrackingDiagnosticDatabase::class.java,
		)
			.allowMainThreadQueries()
			.build()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `storage outcomes are closed and complete`() {
		TrackingDiagnosticStorageResult.entries.map { result -> result.name } shouldContainExactly
			listOf(
				"STORED",
				"AGGREGATED",
				"DROPPED_RATE_LIMIT",
				"STORAGE_RETRYABLE",
				"PERMANENT_REJECTED",
			)
	}

	@Test
	fun `production storage limits remain fixed and bounded`() {
		TrackingDiagnosticStorageLimits.GLOBAL_EVENT_CAP shouldBe 512
		TrackingDiagnosticStorageLimits.PER_SOURCE_EVENT_CAP shouldBe 128
		TrackingDiagnosticStorageLimits.RETENTION_DAYS shouldBe 7L
		TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES shouldBe 1_024
		TrackingDiagnosticStorageLimits.GLOBAL_ENCODED_BYTE_CAP shouldBe 512 * 1_024
		TrackingDiagnosticStorageLimits.PER_SOURCE_ENCODED_BYTE_CAP shouldBe 128 * 1_024
		TrackingDiagnosticStorageLimits.AGGREGATION_WINDOW_MILLIS shouldBe 60_000L
		TrackingDiagnosticStorageLimits.RATE_LIMIT_WINDOW_MILLIS shouldBe 60_000L
		TrackingDiagnosticStorageLimits.GLOBAL_RATE_LIMIT shouldBe 240
		TrackingDiagnosticStorageLimits.PER_SOURCE_RATE_LIMIT shouldBe 60
		TrackingDiagnosticStorageLimits.MAX_PAGE_SIZE shouldBe 100
	}

	@Test
	fun `append enforces fixed source and global row caps`() = runTest {
		val store = store(
			policy = testPolicy(
				globalEventCap = 3,
				perSourceEventCap = 2,
			),
		)

		repeat(3) { index ->
			nowMs += 1L
			store.append(
				recordedEvent(
					source = TrackingDiagnosticSource.WIFI,
					scopeSeed = index.toLong(),
				),
			) shouldBe TrackingDiagnosticStorageResult.STORED
		}
		repeat(2) { index ->
			nowMs += 1L
			store.append(
				recordedEvent(
					source = TrackingDiagnosticSource.CELL,
					scopeSeed = index.toLong(),
				),
			) shouldBe TrackingDiagnosticStorageResult.STORED
		}

		store.querySource(TrackingDiagnosticSource.WIFI).page().events.size shouldBe 1
		store.querySource(TrackingDiagnosticSource.CELL).page().events.size shouldBe 2
	}

	@Test
	fun `append prunes source and global encoded byte footprints`() = runTest {
		val event = recordedEvent()
		val eventBytes = TrackingDiagnosticUtf8Size.encodedFields(
			EncodedTrackingDiagnosticEvent.from(event).serializedFields,
		)
		val store = store(
			policy = testPolicy(
				maxEncodedEventBytes = eventBytes + 20,
				globalEncodedByteCap = (eventBytes.toLong() + 20L) * 2L,
				perSourceEncodedByteCap = eventBytes.toLong() + 20L,
			),
		)

		store.append(event) shouldBe TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(recordedEvent(scopeSeed = 2L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(
			recordedEvent(
				source = TrackingDiagnosticSource.CELL,
				scopeSeed = 3L,
			),
		) shouldBe TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(
			recordedEvent(
				source = TrackingDiagnosticSource.LOCATION,
				scopeSeed = 4L,
			),
		) shouldBe TrackingDiagnosticStorageResult.STORED

		store.querySource(TrackingDiagnosticSource.WIFI).page().events.size shouldBe 0
		store.querySource(TrackingDiagnosticSource.CELL).page().events.size shouldBe 1
		store.querySource(TrackingDiagnosticSource.LOCATION).page().events.size shouldBe 1
	}

	@Test
	fun `identical operational events aggregate before the source rate limit drops them`() = runTest {
		val store = store(
			policy = testPolicy(
				globalRateLimit = 3,
				perSourceRateLimit = 2,
				aggregationWindowMillis = 1_000L,
			),
		)

		store.append(recordedEvent(scopeSeed = 1L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(recordedEvent(scopeSeed = 2L)) shouldBe
			TrackingDiagnosticStorageResult.AGGREGATED
		nowMs += 1L
		store.append(recordedEvent(scopeSeed = 3L)) shouldBe
			TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT

		val stored = store.querySourcePurpose(
			TrackingDiagnosticSource.WIFI,
			TrackingDiagnosticPurpose.SESSION_CAPTURE,
		).page().events.single()
		stored.occurrenceCountBucket shouldBe TrackingDiagnosticCountBucket.TWO_TO_FOUR
	}

	@Test
	fun `global rate limit spans independent sources`() = runTest {
		val store = store(
			policy = testPolicy(
				globalRateLimit = 2,
				perSourceRateLimit = 2,
			),
		)

		store.append(recordedEvent(source = TrackingDiagnosticSource.WIFI)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		store.append(recordedEvent(source = TrackingDiagnosticSource.CELL)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		store.append(recordedEvent(source = TrackingDiagnosticSource.STEPS)) shouldBe
			TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT
	}

	@Test
	fun `encoded limit uses UTF8 bytes and permanently rejects oversized records`() = runTest {
		TrackingDiagnosticUtf8Size.value("é") shouldBe 2
		TrackingDiagnosticUtf8Size.value("🙂") shouldBe 4

		val event = recordedEvent()
		val eventBytes = TrackingDiagnosticUtf8Size.encodedFields(
			EncodedTrackingDiagnosticEvent.from(event).serializedFields,
		)
		val store = store(
			policy = testPolicy(
				maxEncodedEventBytes = eventBytes - 1,
				globalEncodedByteCap = eventBytes.toLong(),
				perSourceEncodedByteCap = eventBytes.toLong(),
			),
		)

		store.append(event) shouldBe TrackingDiagnosticStorageResult.PERMANENT_REJECTED
		store.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()
	}

	@Test
	fun `maintenance prunes events older than the local retention bound`() = runTest {
		val store = store(policy = testPolicy(retentionMillis = 100L))
		store.append(recordedEvent()) shouldBe TrackingDiagnosticStorageResult.STORED
		nowMs += 101L

		store.pruneExpiredAndOverflow() shouldBe TrackingDiagnosticMaintenanceResult.PRUNED

		store.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()
	}

	@Test
	fun `transaction failure rolls back event and rate state and becomes retryable`() = runTest {
		val failingStore = store(
			policy = testPolicy(globalRateLimit = 1, perSourceRateLimit = 1),
			transactionCheckpoint = { error("fail after write") },
		)

		failingStore.append(recordedEvent()) shouldBe
			TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
		failingStore.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()

		val recoveredStore = store(
			policy = testPolicy(globalRateLimit = 1, perSourceRateLimit = 1),
		)
		recoveredStore.append(recordedEvent(scopeSeed = 2L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
	}

	@Test
	fun `closed database is retryable and does not escape`() = runTest {
		val store = store()
		database.close()

		store.append(recordedEvent()) shouldBe
			TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
	}

	@Test
	fun `clear removes events and resets persisted rate limits`() = runTest {
		val store = store(policy = testPolicy(globalRateLimit = 1, perSourceRateLimit = 1))
		store.append(recordedEvent()) shouldBe TrackingDiagnosticStorageResult.STORED
		store.append(recordedEvent(scopeSeed = 2L)) shouldBe
			TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT

		store.clearAll() shouldBe TrackingDiagnosticClearResult.CLEARED

		store.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()
		store.append(recordedEvent(scopeSeed = 3L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
	}

	@Test
	fun `keyset pages stay bounded and isolate source and purpose`() = runTest {
		val store = store()
		repeat(5) { index ->
			nowMs += 1L
			store.append(
				recordedEvent(
					source = TrackingDiagnosticSource.WIFI,
					purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
					scopeSeed = index.toLong(),
				),
			)
		}
		repeat(2) { index ->
			nowMs += 1L
			store.append(
				recordedEvent(
					source = TrackingDiagnosticSource.WIFI,
					purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT,
					scopeSeed = (10 + index).toLong(),
				),
			)
			nowMs += 1L
			store.append(
				recordedEvent(
					source = TrackingDiagnosticSource.CELL,
					purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
					scopeSeed = (20 + index).toLong(),
				),
			)
		}

		val first = store.querySourcePurpose(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pageSize = 2,
		).page()
		val second = store.querySourcePurpose(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pageSize = 2,
			before = first.nextCursor,
		).page()
		val third = store.querySourcePurpose(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pageSize = 2,
			before = second.nextCursor,
		).page()

		listOf(first.events.size, second.events.size, third.events.size) shouldContainExactly
			listOf(2, 2, 1)
		(first.events + second.events + third.events).all { event ->
			event.source == TrackingDiagnosticSource.WIFI &&
				event.purpose == TrackingDiagnosticPurpose.SESSION_CAPTURE
		} shouldBe true
		store.querySource(TrackingDiagnosticSource.CELL).page().events.size shouldBe 2
		store.querySource(
			source = TrackingDiagnosticSource.WIFI,
			pageSize = TrackingDiagnosticStorageLimits.MAX_PAGE_SIZE + 1,
		) shouldBe TrackingDiagnosticReadResult.PermanentRejected
	}

	@Test
	fun `stored schema contains only approved fields and bounded store metadata`() = runTest {
		val store = store()
		store.append(recordedEvent()) shouldBe TrackingDiagnosticStorageResult.STORED

		val columns = mutableSetOf<String>()
		database.openHelper.readableDatabase.query(
			"PRAGMA table_info(tracking_diagnostic_event)",
		).use { cursor ->
			val nameColumn = cursor.getColumnIndexOrThrow("name")
			while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
		}

		columns shouldBe setOf(
			"event_id",
			"source",
			"purpose",
			"pipeline_stage",
			"operation",
			"result",
			"reason",
			"lifecycle",
			"operation_scope",
			"scope_sequence",
			"coarse_local_timestamp",
			"scope_duration_bucket",
			"encoded_envelope_size_bucket",
			"queue_backlog_bucket",
			"drained_envelope_count_bucket",
			"remaining_envelope_backlog_bucket",
			"persisted_envelope_count_bucket",
			"last_observed_at_ms",
			"repeat_count",
			"encoded_byte_count",
		)
		columns.none { column ->
			Regex(
				"throwable|message|path|uri|checksum|sensor|bssid|ssid|cell_id|radio_id",
				RegexOption.IGNORE_CASE,
			).containsMatchIn(column)
		} shouldBe true
	}

	@Test
	fun `persisted scope rotates its random process epoch across recorder processes`() = runTest {
		val store = store()
		store.append(recordedEvent(scopeSeed = 7L, processSeed = 11L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(recordedEvent(scopeSeed = 7L, processSeed = 12L)) shouldBe
			TrackingDiagnosticStorageResult.STORED

		val tokens = mutableListOf<String>()
		database.openHelper.readableDatabase.query(
			"""
			SELECT operation_scope
			FROM tracking_diagnostic_event
			ORDER BY event_id
			""".trimIndent(),
		).use { cursor ->
			while (cursor.moveToNext()) tokens += cursor.getString(0)
		}

		tokens.size shouldBe 2
		tokens.map { it.substringBefore("_scope_") }.distinct().size shouldBe 2
		tokens.map { it.substringAfter("_scope_") }.distinct().size shouldBe 1
	}

	private fun store(
		policy: TrackingDiagnosticStoragePolicy = testPolicy(),
		transactionCheckpoint: () -> Unit = {},
	): RoomTrackingDiagnosticStore = RoomTrackingDiagnosticStore(
		database = database,
		wallClock = { nowMs },
		policy = policy,
		transactionCheckpoint = transactionCheckpoint,
	)

	private fun testPolicy(
		globalEventCap: Int = 100,
		perSourceEventCap: Int = 100,
		retentionMillis: Long = 10_000L,
		maxEncodedEventBytes: Int = 1_024,
		globalEncodedByteCap: Long = 102_400L,
		perSourceEncodedByteCap: Long = 102_400L,
		aggregationWindowMillis: Long = 0L,
		rateLimitWindowMillis: Long = 1_000L,
		globalRateLimit: Int = 100,
		perSourceRateLimit: Int = 100,
	): TrackingDiagnosticStoragePolicy = TrackingDiagnosticStoragePolicy(
		globalEventCap = globalEventCap,
		perSourceEventCap = perSourceEventCap,
		retentionMillis = retentionMillis,
		maxEncodedEventBytes = maxEncodedEventBytes,
		globalEncodedByteCap = globalEncodedByteCap,
		perSourceEncodedByteCap = perSourceEncodedByteCap,
		aggregationWindowMillis = aggregationWindowMillis,
		rateLimitWindowMillis = rateLimitWindowMillis,
		globalRateLimit = globalRateLimit,
		perSourceRateLimit = perSourceRateLimit,
	)

	private fun recordedEvent(
		source: TrackingDiagnosticSource = TrackingDiagnosticSource.WIFI,
		purpose: TrackingDiagnosticPurpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
		scopeSeed: Long = 1L,
		processSeed: Long = 1L,
	): RecordedTrackingDiagnosticEvent = TrackingDiagnosticEvents.unmetered(
		source = source,
		purpose = purpose,
		pipelineStage = TrackingDiagnosticPipelineStage.LIFECYCLE,
		operation = TrackingDiagnosticOperation.START,
		result = TrackingDiagnosticResult.SUCCEEDED,
		reason = TrackingDiagnosticSuccessReason.COMPLETED,
		lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
	).toRecordedEvent(
		operationScope = TrackingDiagnosticScopeOpaque.fixedForTest(
			scopeSeed = scopeSeed,
			processSeed = processSeed,
		),
		scopeSequence = TrackingDiagnosticScopeSequence.EVENT_01,
		coarseLocalTimestamp = TrackingDiagnosticCoarseLocalTimestamp.fromEpochMilliseconds(
			epochMilliseconds = nowMs,
			zoneId = ZoneOffset.UTC,
		),
		scopeDurationBucket = TrackingDiagnosticDurationBucket.UNDER_TEN_MILLISECONDS,
	)

	private fun TrackingDiagnosticReadResult.page(): TrackingDiagnosticPage =
		shouldBeInstanceOf<TrackingDiagnosticReadResult.Page>().value
}
