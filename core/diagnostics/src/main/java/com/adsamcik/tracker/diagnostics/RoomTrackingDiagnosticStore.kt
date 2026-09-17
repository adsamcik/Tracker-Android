@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package com.adsamcik.tracker.diagnostics

import androidx.room.withTransaction
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

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

	override suspend fun append(
		event: RecordedTrackingDiagnosticEvent,
	): TrackingDiagnosticStorageResult {
		val encoded = EncodedTrackingDiagnosticEvent.from(event)
		if (TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
				encoded.serializedFields.map { (field, _) -> field.wireName },
			) !is TrackingDiagnosticPrivacyValidation.Allowed
		) {
			return TrackingDiagnosticStorageResult.PERMANENT_REJECTED
		}
		val nowMs = wallClock().coerceAtLeast(0L)
		val entity = encoded.toEntity(nowMs)
		if (entity.encodedByteCount > policy.maxEncodedEventBytes) {
			return TrackingDiagnosticStorageResult.PERMANENT_REJECTED
		}

		return runStorageOperation {
			database.withTransaction {
				pruneExpired(nowMs)
				if (!consumeRateBudget(entity.source, nowMs)) {
					return@withTransaction TrackingDiagnosticStorageResult.DROPPED_RATE_LIMIT
				}
				val aggregationCutoff = subtractFloorZero(nowMs, policy.aggregationWindowMillis)
				val candidate = dao.findAggregationCandidate(
					source = entity.source,
					purpose = entity.purpose,
					pipelineStage = entity.pipelineStage,
					operation = entity.operation,
					result = entity.result,
					reason = entity.reason,
					lifecycle = entity.lifecycle,
					scopeDurationBucket = entity.scopeDurationBucket,
					encodedEnvelopeSizeBucket = entity.encodedEnvelopeSizeBucket,
					queueBacklogBucket = entity.queueBacklogBucket,
					drainedEnvelopeCountBucket = entity.drainedEnvelopeCountBucket,
					remainingEnvelopeBacklogBucket = entity.remainingEnvelopeBacklogBucket,
					persistedEnvelopeCountBucket = entity.persistedEnvelopeCountBucket,
					observedAfterMs = aggregationCutoff,
				)
				val result = if (candidate == null) {
					check(dao.insert(entity) > 0L) { "Diagnostic event insert was rejected" }
					TrackingDiagnosticStorageResult.STORED
				} else {
					val replacement = candidate.copy(
						operationScope = entity.operationScope,
						scopeSequence = entity.scopeSequence,
						coarseLocalTimestamp = entity.coarseLocalTimestamp,
						lastObservedAtMs = nowMs,
						repeatCount = (candidate.repeatCount + 1)
							.coerceAtMost(policy.maxAggregatedOccurrences),
						encodedByteCount = entity.encodedByteCount,
					)
					check(dao.update(replacement) == 1) {
						"Diagnostic aggregation target disappeared"
					}
					TrackingDiagnosticStorageResult.AGGREGATED
				}
				transactionCheckpoint()
				enforceSourceLimits(entity.source)
				enforceGlobalLimits()
				result
			}
		}
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
				beforeObservedAtMs = before.beforeObservedAtMs,
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
				beforeObservedAtMs = before.beforeObservedAtMs,
				beforeRowId = before.beforeRowId,
				limit = readLimit,
			)
		}
	}

	override suspend fun clearAll(): TrackingDiagnosticClearResult = try {
		database.withTransaction {
			dao.deleteAllEvents()
			dao.deleteAllRateLimits()
		}
		TrackingDiagnosticClearResult.CLEARED
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Throwable) {
		TrackingDiagnosticClearResult.STORAGE_RETRYABLE
	}

	override suspend fun pruneExpiredAndOverflow(): TrackingDiagnosticMaintenanceResult = try {
		database.withTransaction {
			pruneExpired(wallClock().coerceAtLeast(0L))
			TrackingDiagnosticSource.entries.forEach { source ->
				enforceSourceLimits(source.name)
			}
			enforceGlobalLimits()
		}
		TrackingDiagnosticMaintenanceResult.PRUNED
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Throwable) {
		TrackingDiagnosticMaintenanceResult.STORAGE_RETRYABLE
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
							beforeObservedAtMs = row.lastObservedAtMs,
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
					)
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Throwable) {
			TrackingDiagnosticReadResult.StorageRetryable
		}
	}

	private suspend fun consumeRateBudget(source: String, nowMs: Long): Boolean {
		val global = dao.nextRateLimit(
			rateKey = GLOBAL_RATE_KEY,
			nowMs = nowMs,
			windowMillis = policy.rateLimitWindowMillis,
		)
		val sourceRateKey = "$SOURCE_RATE_KEY_PREFIX$source"
		val sourceRate = dao.nextRateLimit(
			rateKey = sourceRateKey,
			nowMs = nowMs,
			windowMillis = policy.rateLimitWindowMillis,
		)
		if (global.acceptedCount >= policy.globalRateLimit ||
			sourceRate.acceptedCount >= policy.perSourceRateLimit
		) {
			return false
		}
		dao.writeRateLimit(global.copy(acceptedCount = global.acceptedCount + 1))
		dao.writeRateLimit(sourceRate.copy(acceptedCount = sourceRate.acceptedCount + 1))
		return true
	}

	private suspend fun TrackingDiagnosticDao.nextRateLimit(
		rateKey: String,
		nowMs: Long,
		windowMillis: Long,
	): TrackingDiagnosticRateLimitEntity {
		val current = readRateLimit(rateKey)
		return if (
			current == null ||
			nowMs < current.windowStartedAtMs ||
			nowMs - current.windowStartedAtMs >= windowMillis
		) {
			TrackingDiagnosticRateLimitEntity(
				rateKey = rateKey,
				windowStartedAtMs = nowMs,
				acceptedCount = 0,
			)
		} else {
			current
		}
	}

	private suspend fun pruneExpired(nowMs: Long) {
		dao.deleteExpired(subtractFloorZero(nowMs, policy.retentionMillis))
	}

	private suspend fun enforceSourceLimits(source: String) {
		val footprint = dao.sourceFootprint(source)
		deleteOverflow(
			footprint = footprint,
			maxCount = policy.perSourceEventCap,
			maxBytes = policy.perSourceEncodedByteCap,
		)
	}

	private suspend fun enforceGlobalLimits() {
		deleteOverflow(
			footprint = dao.globalFootprint(),
			maxCount = policy.globalEventCap,
			maxBytes = policy.globalEncodedByteCap,
		)
	}

	private suspend fun deleteOverflow(
		footprint: List<TrackingDiagnosticStoredFootprint>,
		maxCount: Int,
		maxBytes: Long,
	) {
		var retainedCount = footprint.size
		var retainedBytes = footprint.sumOf { row -> row.encodedByteCount.toLong() }
		val removals = buildList {
			footprint.forEach { row ->
				if (retainedCount > maxCount || retainedBytes > maxBytes) {
					add(row.eventId)
					retainedCount -= 1
					retainedBytes -= row.encodedByteCount
				}
			}
		}
		if (removals.isNotEmpty()) {
			check(dao.deleteEvents(removals) == removals.size) {
				"Diagnostic pruning did not delete its complete bounded selection"
			}
		}
	}

	private suspend fun runStorageOperation(
		block: suspend () -> TrackingDiagnosticStorageResult,
	): TrackingDiagnosticStorageResult = try {
		block()
	} catch (_: CancellationException) {
		TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
	} catch (_: Throwable) {
		TrackingDiagnosticStorageResult.STORAGE_RETRYABLE
	}

	private fun EncodedTrackingDiagnosticEvent.toEntity(
		observedAtMs: Long,
	): TrackingDiagnosticEventEntity {
		val fields = serializedFields.toMap()
		return TrackingDiagnosticEventEntity(
			source = fields.required(TrackingDiagnosticField.SOURCE),
			purpose = fields.required(TrackingDiagnosticField.PURPOSE),
			pipelineStage = fields.required(TrackingDiagnosticField.PIPELINE_STAGE),
			operation = fields.required(TrackingDiagnosticField.OPERATION),
			result = fields.required(TrackingDiagnosticField.RESULT),
			reason = fields.required(TrackingDiagnosticField.REASON),
			lifecycle = fields.required(TrackingDiagnosticField.LIFECYCLE),
			operationScope = fields.required(TrackingDiagnosticField.OPERATION_SCOPE),
			scopeSequence = fields.required(TrackingDiagnosticField.SCOPE_SEQUENCE),
			coarseLocalTimestamp =
				fields.required(TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP),
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
			lastObservedAtMs = observedAtMs,
			repeatCount = 1,
			encodedByteCount = TrackingDiagnosticUtf8Size.encodedFields(serializedFields),
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
		check(operationScope.matches(PROCESS_SCOPE_PATTERN))
		enumValueOf<TrackingDiagnosticScopeSequence>(scopeSequence)
		val parsedCoarseTimestamp = OffsetDateTime.parse(coarseLocalTimestamp)
		check(
			parsedCoarseTimestamp.minute % COARSE_TIMESTAMP_MINUTES == 0 &&
				parsedCoarseTimestamp.second == 0 &&
				parsedCoarseTimestamp.nano == 0
		)
		check(repeatCount in 1..policy.maxAggregatedOccurrences)
		val storedMetrics = buildSet {
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
		check(
			storedMetrics == TrackingDiagnosticMetricPolicy.allowedMetrics(
				parsedSource,
				parsedStage,
				parsedOperation,
			),
		)
		return TrackingDiagnosticStoredEvent(
			source = parsedSource,
			purpose = enumValueOf(purpose),
			pipelineStage = parsedStage,
			operation = parsedOperation,
			result = parsedResult,
			reason = parsedReason,
			lifecycle = enumValueOf(lifecycle),
			coarseLocalTimestamp = coarseLocalTimestamp,
			scopeDurationBucket = enumValueOf(scopeDurationBucket),
			occurrenceCountBucket =
				TrackingDiagnosticCountBucket.fromCount(repeatCount.toLong()),
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

	private companion object {
		const val GLOBAL_RATE_KEY = "GLOBAL"
		const val SOURCE_RATE_KEY_PREFIX = "SOURCE:"
		const val COARSE_TIMESTAMP_MINUTES = 15
		val PROCESS_SCOPE_PATTERN = Regex("""epoch_[0-9a-f]{16}_scope_[0-9a-f]{8}""")

		fun subtractFloorZero(value: Long, delta: Long): Long =
			if (value <= delta) 0L else value - delta
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
