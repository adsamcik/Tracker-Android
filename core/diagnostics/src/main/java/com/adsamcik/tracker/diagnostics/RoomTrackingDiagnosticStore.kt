@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.adsamcik.tracker.diagnostics

import androidx.room.withTransaction
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RoomTrackingDiagnosticStore(
	private val database: TrackingDiagnosticDatabase,
	private val wallClock: () -> Long = System::currentTimeMillis,
	private val policy: TrackingDiagnosticStoragePolicy = TrackingDiagnosticStoragePolicy(),
	private val transactionCheckpoint: () -> Unit = {},
) : TrackingDiagnosticEventStore,
	TrackingDiagnosticHistory,
	TrackingDiagnosticDataControl,
	TrackingDiagnosticMaintenance {
	private val dao = database.diagnosticDao()
	private val mutationMutex = Mutex()
	private val aggregationState = linkedMapOf<AggregationKey, AggregationEntry>()
	private var globalRateWindow: RateWindow? = null
	private val sourceRateWindows = mutableMapOf<TrackingDiagnosticSource, RateWindow>()

	override suspend fun append(
		event: EncodedTrackingDiagnosticEvent,
	): TrackingDiagnosticStorageResult = try {
		mutationMutex.withLock {
			if (TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
					event.serializedFields.map { (field, _) -> field.wireName },
				) !is TrackingDiagnosticPrivacyValidation.Allowed
			) {
				return@withLock TrackingDiagnosticStorageResult.PERMANENT_REJECTED
			}
			val nowMs = wallClock().coerceAtLeast(0L)
			val entity = event.toEntity()
			if (TrackingDiagnosticPrivacyValidator.validateStoredSchema(
					entity.storedFields().map { (field, _) -> field.wireName },
				) !is TrackingDiagnosticPrivacyValidation.Allowed
			) {
				return@withLock TrackingDiagnosticStorageResult.PERMANENT_REJECTED
			}
			if (entity.encodedByteCount() > policy.maxEncodedEventBytes) {
				return@withLock TrackingDiagnosticStorageResult.PERMANENT_REJECTED
			}
			if (entity.coarseTimeBucket < retentionCutoffBucket(nowMs)) {
				return@withLock TrackingDiagnosticStorageResult.PERMANENT_REJECTED
			}
			val source = enumValueOf<TrackingDiagnosticSource>(entity.source)
			if (!rateBudgetAvailable(source, nowMs)) {
				return@withLock TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT
			}

			pruneAggregationState(nowMs)
			val aggregationKey = entity.aggregationKey()
			val previousAggregation = aggregationState[aggregationKey]
			val operation = runStorageOperation {
				database.withTransaction {
					pruneExpired(nowMs)
					val candidate = previousAggregation
						?.takeIf { state ->
							nowMs - state.lastSeenAtMs <= policy.aggregationWindowMillis
						}
						?.let { state -> dao.findById(state.rowId) }
						?.takeIf { stored -> stored.aggregationKey() == aggregationKey }
					val committed = if (candidate == null) {
						val rowId = dao.insert(entity)
						check(rowId > 0L) { "Diagnostic event insert was rejected" }
						AppendCommit(
							result = TrackingDiagnosticStorageResult.STORED,
							rowId = rowId,
							occurrenceCount = 1,
						)
					} else {
						val nextCount = (requireNotNull(previousAggregation).occurrenceCount + 1)
							.coerceAtMost(policy.maxAggregatedOccurrences)
						val replacement = candidate.copy(
							occurrenceCountBucket =
								TrackingDiagnosticCountBucket.fromCount(nextCount.toLong()).name,
						)
						check(replacement.encodedByteCount() <= policy.maxEncodedEventBytes) {
							"Aggregated diagnostic event exceeds its encoded byte bound"
						}
						check(dao.update(replacement) == 1) {
							"Diagnostic aggregation target disappeared"
						}
						AppendCommit(
							result = TrackingDiagnosticStorageResult.AGGREGATED,
							rowId = candidate.eventId,
							occurrenceCount = nextCount,
						)
					}
					transactionCheckpoint()
					enforceSourceLimits(entity.source, protectedRowId = committed.rowId)
					enforceGlobalLimits(protectedRowId = committed.rowId)
					committed
				}
			}
			when (operation) {
				StorageOperation.Retryable ->
					TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
				is StorageOperation.Succeeded -> {
					recordRateBudget(source, nowMs)
					recordAggregation(aggregationKey, operation.value, nowMs)
					operation.value.result
				}
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Throwable) {
		TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
	}

	override suspend fun querySource(
		source: TrackingDiagnosticSource,
		pageSize: Int,
		before: TrackingDiagnosticPageCursor?,
	): TrackingDiagnosticReadResult = query(pageSize) { readLimit ->
		if (before == null) {
			dao.querySourceFirst(source.name, readLimit)
		} else {
			dao.querySourceBefore(
				source = source.name,
				beforeCoarseTimeBucket = before.beforeCoarseTimeBucket,
				beforeRowId = before.beforeRowId,
				limit = readLimit,
			)
		}
	}

	override suspend fun querySourcePurpose(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pageSize: Int,
		before: TrackingDiagnosticPageCursor?,
	): TrackingDiagnosticReadResult = query(pageSize) { readLimit ->
		if (before == null) {
			dao.querySourcePurposeFirst(source.name, purpose.name, readLimit)
		} else {
			dao.querySourcePurposeBefore(
				source = source.name,
				purpose = purpose.name,
				beforeCoarseTimeBucket = before.beforeCoarseTimeBucket,
				beforeRowId = before.beforeRowId,
				limit = readLimit,
			)
		}
	}

	override suspend fun clearAll(): TrackingDiagnosticClearResult = mutationMutex.withLock {
		try {
			database.withTransaction {
				dao.deleteAllEvents()
			}
			clearInMemoryState()
			TrackingDiagnosticClearResult.CLEARED
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Throwable) {
			TrackingDiagnosticClearResult.STORAGE_RETRYABLE
		}
	}

	override suspend fun pruneExpiredAndOverflow(): TrackingDiagnosticMaintenanceResult =
		mutationMutex.withLock {
			try {
				database.withTransaction {
					pruneExpired(wallClock().coerceAtLeast(0L))
					TrackingDiagnosticSource.entries.forEach { source ->
						enforceSourceLimits(source.name)
					}
					enforceGlobalLimits()
				}
				aggregationState.clear()
				TrackingDiagnosticMaintenanceResult.PRUNED
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Throwable) {
				TrackingDiagnosticMaintenanceResult.STORAGE_RETRYABLE
			}
		}

	private suspend fun query(
		pageSize: Int,
		read: suspend (Int) -> List<TrackingDiagnosticEventEntity>,
	): TrackingDiagnosticReadResult {
		if (pageSize !in 1..TrackingDiagnosticStorageLimits.MAX_PAGE_SIZE) {
			return TrackingDiagnosticReadResult.PermanentRejected
		}
		return try {
			database.withTransaction {
				pruneExpired(wallClock().coerceAtLeast(0L))
				TrackingDiagnosticSource.entries.forEach { source ->
					enforceSourceLimits(source.name)
				}
				enforceGlobalLimits()
				val rows = read(pageSize + 1)
				val pageRows = rows.take(pageSize)
				val nextCursor = if (rows.size > pageSize) {
					pageRows.lastOrNull()?.let { row ->
						TrackingDiagnosticPageCursor(
							beforeCoarseTimeBucket = row.coarseTimeBucket,
							beforeRowId = row.eventId,
						)
					}
				} else {
					null
				}
				TrackingDiagnosticReadResult.Page(
					TrackingDiagnosticPage(
						events = pageRows.map { row -> row.toStoredEvent() },
						nextCursor = nextCursor,
					),
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Throwable) {
			TrackingDiagnosticReadResult.StorageRetryable
		}
	}

	private fun rateBudgetAvailable(source: TrackingDiagnosticSource, nowMs: Long): Boolean {
		val global = globalRateWindow.activeAt(nowMs)
		val sourceWindow = sourceRateWindows[source].activeAt(nowMs)
		return global.acceptedCount < policy.globalRateLimit &&
			sourceWindow.acceptedCount < policy.perSourceRateLimit
	}

	private fun recordRateBudget(source: TrackingDiagnosticSource, nowMs: Long) {
		globalRateWindow = globalRateWindow.activeAt(nowMs).incremented()
		sourceRateWindows[source] = sourceRateWindows[source].activeAt(nowMs).incremented()
	}

	private fun RateWindow?.activeAt(nowMs: Long): RateWindow =
		if (this == null) {
			RateWindow(startedAtMs = nowMs, acceptedCount = 0)
		} else if (
			nowMs < this.startedAtMs ||
			nowMs - this.startedAtMs >= policy.rateLimitWindowMillis
		) {
			RateWindow(startedAtMs = nowMs, acceptedCount = 0)
		} else {
			this
		}

	private fun RateWindow.incremented(): RateWindow =
		copy(acceptedCount = acceptedCount + 1)

	private fun pruneAggregationState(nowMs: Long) {
		val iterator = aggregationState.iterator()
		while (iterator.hasNext()) {
			val state = iterator.next().value
			if (
				nowMs < state.lastSeenAtMs ||
				nowMs - state.lastSeenAtMs > policy.aggregationWindowMillis
			) {
				iterator.remove()
			}
		}
	}

	private fun recordAggregation(
		key: AggregationKey,
		commit: AppendCommit,
		nowMs: Long,
	) {
		aggregationState[key] = AggregationEntry(
			rowId = commit.rowId,
			occurrenceCount = commit.occurrenceCount,
			lastSeenAtMs = nowMs,
		)
		while (
			aggregationState.size >
			TrackingDiagnosticStorageLimits.MAX_IN_MEMORY_AGGREGATION_KEYS
		) {
			val oldest = aggregationState.entries
				.minBy { entry -> entry.value.lastSeenAtMs }
				.key
			aggregationState.remove(oldest)
		}
	}

	private suspend fun pruneExpired(nowMs: Long) {
		dao.deleteExpired(retentionCutoffBucket(nowMs))
	}

	private fun retentionCutoffBucket(nowMs: Long): Long {
		val currentBucket =
			TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(nowMs).epochQuarterHour
		val retentionBuckets =
			(policy.retentionMillis - 1L) /
				TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS +
				1L
		return if (currentBucket <= retentionBuckets) {
			0L
		} else {
			currentBucket - retentionBuckets
		}
	}

	private suspend fun enforceSourceLimits(
		source: String,
		protectedRowId: Long? = null,
	) {
		deleteOverflow(
			rows = dao.sourceRowsOldest(source),
			maxCount = policy.perSourceEventCap,
			maxBytes = policy.perSourceEncodedByteCap,
			protectedRowId = protectedRowId,
		)
	}

	private suspend fun enforceGlobalLimits(protectedRowId: Long? = null) {
		deleteOverflow(
			rows = dao.globalRowsOldest(),
			maxCount = policy.globalEventCap,
			maxBytes = policy.globalEncodedByteCap,
			protectedRowId = protectedRowId,
		)
	}

	private suspend fun deleteOverflow(
		rows: List<TrackingDiagnosticEventEntity>,
		maxCount: Int,
		maxBytes: Long,
		protectedRowId: Long? = null,
	) {
		var retainedCount = rows.size
		var retainedBytes = rows.sumOf { row -> row.encodedByteCount().toLong() }
		val removals = buildList {
			rows.filterNot { row -> row.eventId == protectedRowId }.forEach { row ->
				if (retainedCount > maxCount || retainedBytes > maxBytes) {
					add(row.eventId)
					retainedCount -= 1
					retainedBytes -= row.encodedByteCount()
				}
			}
			check(retainedCount <= maxCount && retainedBytes <= maxBytes) {
				"Diagnostic limits cannot retain the protected event"
			}
		}
		if (removals.isNotEmpty()) {
			check(dao.deleteEvents(removals) == removals.size) {
				"Diagnostic pruning did not delete its complete bounded selection"
			}
		}
	}

	private suspend fun <T> runStorageOperation(
		block: suspend () -> T,
	): StorageOperation<T> = try {
		StorageOperation.Succeeded(block())
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Throwable) {
		StorageOperation.Retryable
	}

	private fun EncodedTrackingDiagnosticEvent.toEntity(): TrackingDiagnosticEventEntity {
		val fields = serializedFields.toMap()
		return TrackingDiagnosticEventEntity(
			source = fields.required(TrackingDiagnosticField.SOURCE),
			purpose = fields.required(TrackingDiagnosticField.PURPOSE),
			pipelineStage = fields.required(TrackingDiagnosticField.PIPELINE_STAGE),
			operation = fields.required(TrackingDiagnosticField.OPERATION),
			result = fields.required(TrackingDiagnosticField.RESULT),
			reason = fields.required(TrackingDiagnosticField.REASON),
			lifecycle = fields.required(TrackingDiagnosticField.LIFECYCLE),
			coarseTimeBucket =
				fields.required(TrackingDiagnosticField.COARSE_TIME_BUCKET).toLong(),
			scopeDurationBucket =
				fields.required(TrackingDiagnosticField.SCOPE_DURATION_BUCKET),
			encodedEnvelopeSizeBucket =
				fields[TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET],
			queueBacklogBucket = fields[TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET],
			drainedEnvelopeCountBucket =
				fields[TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET],
			remainingEnvelopeBacklogBucket =
				fields[TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET],
			persistedEnvelopeCountBucket =
				fields[TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET],
			occurrenceCountBucket = TrackingDiagnosticCountBucket.ONE.name,
		)
	}

	private fun Map<TrackingDiagnosticField, String>.required(
		field: TrackingDiagnosticField,
	): String = requireNotNull(get(field)) { "Missing approved diagnostic field ${field.wireName}" }

	private fun TrackingDiagnosticEventEntity.toStoredEvent(): TrackingDiagnosticStoredEvent {
		val parsedSource = enumValueOf<TrackingDiagnosticSource>(source)
		val parsedStage = enumValueOf<TrackingDiagnosticPipelineStage>(pipelineStage)
		val parsedOperation = enumValueOf<TrackingDiagnosticOperation>(operation)
		val parsedResult = enumValueOf<TrackingDiagnosticResult>(result)
		val parsedReason = parseReason(parsedResult, reason)
		check(parsedReason.isCompatibleWith(parsedResult))
		check(coarseTimeBucket >= 0L)
		val occurrenceBucket = enumValueOf<TrackingDiagnosticCountBucket>(occurrenceCountBucket)
		check(
			occurrenceBucket != TrackingDiagnosticCountBucket.NOT_REPORTED &&
				occurrenceBucket != TrackingDiagnosticCountBucket.ZERO,
		)
		val storedMetrics = metricSet()
		check(
			TrackingDiagnosticPrivacyValidator.validateMetricSet(
				parsedSource,
				parsedStage,
				parsedOperation,
				storedMetrics,
			) is TrackingDiagnosticPrivacyValidation.Allowed,
		)
		check(
			TrackingDiagnosticPrivacyValidator.validateStoredSchema(
				storedFields().map { (field, _) -> field.wireName },
			) is TrackingDiagnosticPrivacyValidation.Allowed,
		)
		return TrackingDiagnosticStoredEvent(
			source = parsedSource,
			purpose = enumValueOf(purpose),
			pipelineStage = parsedStage,
			operation = parsedOperation,
			result = parsedResult,
			reason = parsedReason,
			lifecycle = enumValueOf(lifecycle),
			coarseTimeBucket = coarseTimeBucket,
			scopeDurationBucket = enumValueOf(scopeDurationBucket),
			occurrenceCountBucket = occurrenceBucket,
			metrics = TrackingDiagnosticStoredMetrics(
				encodedEnvelopeSizeBucket = encodedEnvelopeSizeBucket?.let { value ->
					enumValueOf<TrackingDiagnosticSizeBucket>(value)
				},
				queueBacklogBucket = queueBacklogBucket?.let { value ->
					enumValueOf<TrackingDiagnosticBacklogBucket>(value)
				},
				drainedEnvelopeCountBucket = drainedEnvelopeCountBucket?.let { value ->
					enumValueOf<TrackingDiagnosticCountBucket>(value)
				},
				remainingEnvelopeBacklogBucket =
					remainingEnvelopeBacklogBucket?.let { value ->
						enumValueOf<TrackingDiagnosticBacklogBucket>(value)
					},
				persistedEnvelopeCountBucket =
					persistedEnvelopeCountBucket?.let { value ->
						enumValueOf<TrackingDiagnosticCountBucket>(value)
					},
			),
		)
	}

	private fun TrackingDiagnosticEventEntity.aggregationKey(): AggregationKey = AggregationKey(
		source = source,
		purpose = purpose,
		pipelineStage = pipelineStage,
		operation = operation,
		result = result,
		reason = reason,
		lifecycle = lifecycle,
		coarseTimeBucket = coarseTimeBucket,
		scopeDurationBucket = scopeDurationBucket,
		encodedEnvelopeSizeBucket = encodedEnvelopeSizeBucket,
		queueBacklogBucket = queueBacklogBucket,
		drainedEnvelopeCountBucket = drainedEnvelopeCountBucket,
		remainingEnvelopeBacklogBucket = remainingEnvelopeBacklogBucket,
		persistedEnvelopeCountBucket = persistedEnvelopeCountBucket,
	)

	private fun TrackingDiagnosticEventEntity.metricSet(): Set<TrackingDiagnosticMetric> =
		buildSet {
			if (encodedEnvelopeSizeBucket != null) {
				add(TrackingDiagnosticMetric.ENCODED_ENVELOPE_SIZE)
			}
			if (queueBacklogBucket != null) add(TrackingDiagnosticMetric.QUEUE_BACKLOG)
			if (drainedEnvelopeCountBucket != null) {
				add(TrackingDiagnosticMetric.DRAINED_ENVELOPE_COUNT)
			}
			if (remainingEnvelopeBacklogBucket != null) {
				add(TrackingDiagnosticMetric.REMAINING_ENVELOPE_BACKLOG)
			}
			if (persistedEnvelopeCountBucket != null) {
				add(TrackingDiagnosticMetric.PERSISTED_ENVELOPE_COUNT)
			}
		}

	private fun TrackingDiagnosticEventEntity.storedFields(): List<Pair<TrackingDiagnosticField, String>> =
		buildList {
		add(TrackingDiagnosticField.SOURCE to source)
		add(TrackingDiagnosticField.PURPOSE to purpose)
		add(TrackingDiagnosticField.PIPELINE_STAGE to pipelineStage)
		add(TrackingDiagnosticField.OPERATION to operation)
		add(TrackingDiagnosticField.RESULT to result)
		add(TrackingDiagnosticField.REASON to reason)
		add(TrackingDiagnosticField.LIFECYCLE to lifecycle)
		add(TrackingDiagnosticField.COARSE_TIME_BUCKET to coarseTimeBucket.toString())
		add(TrackingDiagnosticField.SCOPE_DURATION_BUCKET to scopeDurationBucket)
		encodedEnvelopeSizeBucket?.let { value ->
			add(TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET to value)
		}
		queueBacklogBucket?.let { value ->
			add(TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET to value)
		}
		drainedEnvelopeCountBucket?.let { value ->
			add(TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET to value)
		}
		remainingEnvelopeBacklogBucket?.let { value ->
			add(TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET to value)
		}
		persistedEnvelopeCountBucket?.let { value ->
			add(TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET to value)
		}
		add(TrackingDiagnosticField.OCCURRENCE_COUNT_BUCKET to occurrenceCountBucket)
	}

	private fun TrackingDiagnosticEventEntity.encodedByteCount(): Int =
		TrackingDiagnosticUtf8Size.encodedFields(storedFields())

	private fun parseReason(
		result: TrackingDiagnosticResult,
		stableName: String,
	): TrackingDiagnosticReason = when (result) {
		TrackingDiagnosticResult.SUCCEEDED ->
			enumValueOf<TrackingDiagnosticSuccessReason>(stableName)
		TrackingDiagnosticResult.NO_EFFECT ->
			enumValueOf<TrackingDiagnosticNoEffectReason>(stableName)
		TrackingDiagnosticResult.BLOCKED ->
			enumValueOf<TrackingDiagnosticBlockedReason>(stableName)
		TrackingDiagnosticResult.DEFERRED ->
			enumValueOf<TrackingDiagnosticDeferredReason>(stableName)
		TrackingDiagnosticResult.REJECTED ->
			enumValueOf<TrackingDiagnosticRejectedReason>(stableName)
		TrackingDiagnosticResult.RETRYABLE_FAILURE,
		TrackingDiagnosticResult.PERMANENT_FAILURE,
		-> enumValueOf<TrackingDiagnosticFailureReason>(stableName)
		TrackingDiagnosticResult.CANCELLED ->
			enumValueOf<TrackingDiagnosticCancellationReason>(stableName)
	}

	private fun clearInMemoryState() {
		aggregationState.clear()
		globalRateWindow = null
		sourceRateWindows.clear()
	}

	private data class AppendCommit(
		val result: TrackingDiagnosticStorageResult,
		val rowId: Long,
		val occurrenceCount: Int,
	)

	private data class AggregationEntry(
		val rowId: Long,
		val occurrenceCount: Int,
		val lastSeenAtMs: Long,
	)

	@Suppress("LongParameterList")
	private data class AggregationKey(
		val source: String,
		val purpose: String,
		val pipelineStage: String,
		val operation: String,
		val result: String,
		val reason: String,
		val lifecycle: String,
		val coarseTimeBucket: Long,
		val scopeDurationBucket: String,
		val encodedEnvelopeSizeBucket: String?,
		val queueBacklogBucket: String?,
		val drainedEnvelopeCountBucket: String?,
		val remainingEnvelopeBacklogBucket: String?,
		val persistedEnvelopeCountBucket: String?,
	)

	private data class RateWindow(
		val startedAtMs: Long,
		val acceptedCount: Int,
	)

	private sealed interface StorageOperation<out T> {
		data class Succeeded<T>(val value: T) : StorageOperation<T>
		data object Retryable : StorageOperation<Nothing>
	}
}

internal object TrackingDiagnosticUtf8Size {
	fun encodedFields(fields: List<Pair<TrackingDiagnosticField, String>>): Int =
		fields.sumOf { (field, value) ->
			field.wireName.toByteArray(StandardCharsets.UTF_8).size +
				value.toByteArray(StandardCharsets.UTF_8).size +
				FIELD_ENCODING_OVERHEAD_BYTES
		}

	internal fun value(value: String): Int =
		value.toByteArray(StandardCharsets.UTF_8).size

	private const val FIELD_ENCODING_OVERHEAD_BYTES = 2
}
