package com.adsamcik.tracker.diagnostics

import android.app.Application
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException
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
	private var nowMs = TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS

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
		TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES shouldBe 512
		TrackingDiagnosticStorageLimits.GLOBAL_ENCODED_BYTE_CAP shouldBe 256 * 1_024
		TrackingDiagnosticStorageLimits.PER_SOURCE_ENCODED_BYTE_CAP shouldBe 64 * 1_024
		TrackingDiagnosticStorageLimits.AGGREGATION_WINDOW_MILLIS shouldBe 60_000L
		TrackingDiagnosticStorageLimits.RATE_LIMIT_WINDOW_MILLIS shouldBe 60_000L
		TrackingDiagnosticStorageLimits.GLOBAL_RATE_LIMIT shouldBe 240
		TrackingDiagnosticStorageLimits.PER_SOURCE_RATE_LIMIT shouldBe 60
		TrackingDiagnosticStorageLimits.MAX_PAGE_SIZE shouldBe 100
		maximumValidStoredEncodingBytes() <=
			TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES shouldBe true
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
		val eventBytes = storedBytes(event)
		(eventBytes <= TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES) shouldBe true
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
	fun `identical operational events aggregate in memory before source rate limit`() = runTest {
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
	fun `process restart starts a new aggregate without persisted correlation state`() = runTest {
		val processPolicy = testPolicy(
			aggregationWindowMillis = 60_000L,
			globalRateLimit = 1,
			perSourceRateLimit = 1,
		)
		val firstProcess = store(policy = processPolicy)
		firstProcess.append(recordedEvent(scopeSeed = 7L, processSeed = 11L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		nowMs += 1L

		val secondProcess = store(policy = processPolicy)
		secondProcess.append(recordedEvent(scopeSeed = 7L, processSeed = 12L)) shouldBe
			TrackingDiagnosticStorageResult.STORED

		secondProcess.querySource(TrackingDiagnosticSource.WIFI).page().events
			.map { event -> event.occurrenceCountBucket } shouldContainExactly
			listOf(TrackingDiagnosticCountBucket.ONE, TrackingDiagnosticCountBucket.ONE)
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
		val eventBytes = storedBytes(event)
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
	fun `maintenance prunes only by coarse fifteen minute age bucket`() = runTest {
		val oneBucket = TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS
		val store = store(policy = testPolicy(retentionMillis = oneBucket))
		store.append(recordedEvent()) shouldBe TrackingDiagnosticStorageResult.STORED
		nowMs += oneBucket * 2L

		store.pruneExpiredAndOverflow() shouldBe TrackingDiagnosticMaintenanceResult.PRUNED

		store.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()
	}

	@Test
	fun `append permanently rejects an already expired coarse bucket`() = runTest {
		val oneBucket = TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS
		val staleEvent = recordedEvent()
		nowMs += oneBucket * 2L
		val store = store(policy = testPolicy(retentionMillis = oneBucket))

		store.append(staleEvent) shouldBe TrackingDiagnosticStorageResult.PERMANENT_REJECTED
		store.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe emptyList()
	}

	@Test
	fun `transaction failure rolls back event and does not consume in-memory rate`() = runTest {
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
	fun `transaction cancellation rolls back and propagates`() = runTest {
		var cancel = true
		val cancellingStore = store(
			policy = testPolicy(globalRateLimit = 1, perSourceRateLimit = 1),
			transactionCheckpoint = {
				if (cancel) throw CancellationException("cancel append")
			},
		)

		runCatching { cancellingStore.append(recordedEvent()) }
			.exceptionOrNull()
			.shouldBeInstanceOf<CancellationException>()
		cancellingStore.querySource(TrackingDiagnosticSource.WIFI).page().events shouldBe
			emptyList()
		cancel = false
		cancellingStore.append(recordedEvent(scopeSeed = 2L)) shouldBe
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
	fun `clear removes events and resets in-memory rate limits`() = runTest {
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
	fun `manual TableInfo contract matches final standalone v1 schema`() {
		val manual = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						createManualSchema(db)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						error("Standalone v1 schema has no migration")
					}
				})
				.build(),
		)
		try {
			TableInfo.read(manual.writableDatabase, EVENT_TABLE) shouldBe
				TableInfo.read(database.openHelper.writableDatabase, EVENT_TABLE)
		} finally {
			manual.close()
		}
	}

	@Test
	fun `raw schema and rows cannot correlate process or operation instances`() = runTest {
		val store = store()
		store.append(recordedEvent(scopeSeed = 7L, processSeed = 11L)) shouldBe
			TrackingDiagnosticStorageResult.STORED
		nowMs += 1L
		store.append(recordedEvent(scopeSeed = 8L, processSeed = 12L)) shouldBe
			TrackingDiagnosticStorageResult.STORED

		val raw = database.openHelper.readableDatabase
		val tableInfo = TableInfo.read(raw, EVENT_TABLE)
		val diagnosticTables = mutableListOf<String>()
		raw.query(
			"""
			SELECT name FROM sqlite_master
			WHERE type = 'table' AND name LIKE 'tracking_diagnostic_%'
			ORDER BY name
			""".trimIndent(),
		).use { cursor ->
			while (cursor.moveToNext()) diagnosticTables += cursor.getString(0)
		}
		diagnosticTables shouldContainExactly listOf(EVENT_TABLE)
		tableInfo.columns.keys shouldBe setOf(
			"event_id",
			"source",
			"purpose",
			"pipeline_stage",
			"operation",
			"result",
			"reason",
			"lifecycle",
			"coarse_time_bucket",
			"scope_duration_bucket",
			"encoded_envelope_size_bucket",
			"queue_backlog_bucket",
			"drained_envelope_count_bucket",
			"remaining_envelope_backlog_bucket",
			"persisted_envelope_count_bucket",
			"occurrence_count_bucket",
		)
		(tableInfo.columns.keys - "event_id") shouldBe
			TrackingDiagnosticPrivacyValidator.allowedStoredFields
				.map { field -> field.wireName }
				.toSet()
		tableInfo.columns.keys.none { column ->
			column == "operation_scope" ||
				column == "scope_sequence" ||
				column.contains("timestamp") ||
				column.endsWith("_ms") ||
				column.contains("throwable") ||
				column.contains("message") ||
				column.contains("path") ||
				column.contains("uri") ||
				column.contains("checksum") ||
				column.contains("sensor") ||
				column.contains("radio")
		} shouldBe true
		tableInfo.indices.orEmpty().map { index -> index.name }.toSet() shouldBe setOf(
			"index_tracking_diagnostic_event_source_purpose_recency",
			"index_tracking_diagnostic_event_recency",
		)

		val rawValues = mutableListOf<String>()
		raw.query("SELECT * FROM $EVENT_TABLE ORDER BY event_id").use { cursor ->
			while (cursor.moveToNext()) {
				repeat(cursor.columnCount) { column ->
					if (!cursor.isNull(column)) rawValues += cursor.getString(column)
				}
			}
		}
		rawValues.none { value ->
			value.startsWith("epoch_") ||
				value.startsWith("scope_") ||
				value.startsWith("EVENT_")
		} shouldBe true

		val approvedRows = mutableListOf<List<String?>>()
		raw.query(
			"""
			SELECT source, purpose, pipeline_stage, operation, result, reason, lifecycle,
			       coarse_time_bucket, scope_duration_bucket, encoded_envelope_size_bucket,
			       queue_backlog_bucket, drained_envelope_count_bucket,
			       remaining_envelope_backlog_bucket, persisted_envelope_count_bucket,
			       occurrence_count_bucket
			FROM tracking_diagnostic_event
			ORDER BY event_id
			""".trimIndent(),
		).use { cursor ->
			while (cursor.moveToNext()) {
				approvedRows += List(cursor.columnCount) { column ->
					cursor.getString(column)
				}
			}
		}
		approvedRows.size shouldBe 2
		approvedRows.distinct().size shouldBe 1
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
		retentionMillis: Long = TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS * 10L,
		maxEncodedEventBytes: Int = TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES,
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
	): EncodedTrackingDiagnosticEvent = EncodedTrackingDiagnosticEvent.from(
		TrackingDiagnosticEvents.unmetered(
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
			coarseTimeBucket = TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(nowMs),
			scopeDurationBucket = TrackingDiagnosticDurationBucket.UNDER_TEN_MILLISECONDS,
		),
	)

	private fun storedBytes(event: EncodedTrackingDiagnosticEvent): Int =
		TrackingDiagnosticUtf8Size.encodedFields(
			event.serializedFields +
				(TrackingDiagnosticField.OCCURRENCE_COUNT_BUCKET to
					TrackingDiagnosticCountBucket.ONE.name),
		)

	private fun TrackingDiagnosticReadResult.page(): TrackingDiagnosticPage =
		shouldBeInstanceOf<TrackingDiagnosticReadResult.Page>().value

	private fun createManualSchema(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `tracking_diagnostic_event` (
			    `event_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
			    `source` TEXT NOT NULL,
			    `purpose` TEXT NOT NULL,
			    `pipeline_stage` TEXT NOT NULL,
			    `operation` TEXT NOT NULL,
			    `result` TEXT NOT NULL,
			    `reason` TEXT NOT NULL,
			    `lifecycle` TEXT NOT NULL,
			    `coarse_time_bucket` INTEGER NOT NULL,
			    `scope_duration_bucket` TEXT NOT NULL,
			    `encoded_envelope_size_bucket` TEXT,
			    `queue_backlog_bucket` TEXT,
			    `drained_envelope_count_bucket` TEXT,
			    `remaining_envelope_backlog_bucket` TEXT,
			    `persisted_envelope_count_bucket` TEXT,
			    `occurrence_count_bucket` TEXT NOT NULL
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_tracking_diagnostic_event_source_purpose_recency`
			ON `tracking_diagnostic_event` (
			    `source`,
			    `purpose`,
			    `coarse_time_bucket`,
			    `event_id`
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_tracking_diagnostic_event_recency`
			ON `tracking_diagnostic_event` (`coarse_time_bucket`, `event_id`)
			""".trimIndent(),
		)
	}

	private fun maximumValidStoredEncodingBytes(): Int {
		val base = listOf(
			TrackingDiagnosticField.SOURCE to "PRESSURE",
			TrackingDiagnosticField.PURPOSE to "CONTROL_CONTINUATION",
			TrackingDiagnosticField.PIPELINE_STAGE to "DURABLE_INGRESS",
			TrackingDiagnosticField.OPERATION to "UNREGISTER",
			TrackingDiagnosticField.RESULT to "PERMANENT_FAILURE",
			TrackingDiagnosticField.REASON to "PERMISSION_RECONCILIATION_FAILURE",
			TrackingDiagnosticField.LIFECYCLE to "TERMINAL",
			TrackingDiagnosticField.COARSE_TIME_BUCKET to Long.MAX_VALUE.toString(),
			TrackingDiagnosticField.SCOPE_DURATION_BUCKET to
				"ONE_HUNDRED_TO_NINE_HUNDRED_NINETY_NINE_MILLISECONDS",
			TrackingDiagnosticField.OCCURRENCE_COUNT_BUCKET to "SIXTY_FIVE_OR_MORE",
		)
		val schemaMetrics = listOf(
			listOf(
				TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET to
					"UP_TO_SIXTY_FOUR_KIBIBYTES",
				TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET to
					"ONE_HUNDRED_TWENTY_NINE_OR_MORE",
			),
			listOf(
				TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET to
					"SIXTY_FIVE_OR_MORE",
				TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET to
					"ONE_HUNDRED_TWENTY_NINE_OR_MORE",
			),
			listOf(
				TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET to
					"SIXTY_FIVE_OR_MORE",
			),
		)
		return schemaMetrics.maxOf { metrics ->
			TrackingDiagnosticUtf8Size.encodedFields(base + metrics)
		}
	}

	private companion object {
		const val EVENT_TABLE = "tracking_diagnostic_event"
	}
}
