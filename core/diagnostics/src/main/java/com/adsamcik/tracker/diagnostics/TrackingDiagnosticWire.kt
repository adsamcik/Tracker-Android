package com.adsamcik.tracker.diagnostics

import java.security.SecureRandom

/** Random process epoch that rotates whenever a recorder is constructed. */
internal class TrackingDiagnosticProcessEpoch private constructor(
	internal val opaqueValue: String,
) {
	override fun toString(): String = "TrackingDiagnosticProcessEpoch(opaque)"

	companion object {
		private const val RANDOM_BYTE_COUNT = 8

		fun random(random: SecureRandom): TrackingDiagnosticProcessEpoch =
			fromBytes(ByteArray(RANDOM_BYTE_COUNT).also { bytes -> random.nextBytes(bytes) })

		fun fixedForTest(seed: Long): TrackingDiagnosticProcessEpoch =
			fromBytes(deterministicBytes(seed, RANDOM_BYTE_COUNT))

		private fun fromBytes(bytes: ByteArray): TrackingDiagnosticProcessEpoch =
			TrackingDiagnosticProcessEpoch(encodeHex(bytes))
	}
}

/**
 * Random diagnostic correlation scoped to one process operation, never a product identity.
 *
 * The in-memory representation combines a random process epoch with a short random scope. It is
 * never serialized, and a new recorder rotates the epoch.
 */
internal class TrackingDiagnosticScopeOpaque private constructor(
	internal val opaqueValue: String,
) {
	override fun toString(): String = "TrackingDiagnosticScopeOpaque(opaque)"

	companion object {
		private const val RANDOM_SCOPE_BYTE_COUNT = 4

		fun random(
			processEpoch: TrackingDiagnosticProcessEpoch,
			random: SecureRandom,
		): TrackingDiagnosticScopeOpaque = fromParts(
			processEpoch = processEpoch,
			scopeBytes =
				ByteArray(RANDOM_SCOPE_BYTE_COUNT).also { bytes -> random.nextBytes(bytes) },
		)

		fun fixedForTest(
			scopeSeed: Long,
			processSeed: Long = 0L,
		): TrackingDiagnosticScopeOpaque = fromParts(
			processEpoch = TrackingDiagnosticProcessEpoch.fixedForTest(processSeed),
			scopeBytes = deterministicBytes(scopeSeed, RANDOM_SCOPE_BYTE_COUNT),
		)

		private fun fromParts(
			processEpoch: TrackingDiagnosticProcessEpoch,
			scopeBytes: ByteArray,
		): TrackingDiagnosticScopeOpaque = TrackingDiagnosticScopeOpaque(
			"epoch_${processEpoch.opaqueValue}_scope_${encodeHex(scopeBytes)}",
		)
	}
}

private const val BYTES_PER_LONG = 8
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xff
private val HEX = "0123456789abcdef".toCharArray()

private fun deterministicBytes(seed: Long, count: Int): ByteArray =
	ByteArray(count) { index ->
		val shift = (index % BYTES_PER_LONG) * BITS_PER_BYTE
		((seed ushr shift) xor index.toLong()).toByte()
	}

private fun encodeHex(bytes: ByteArray): String {
	val encoded = CharArray(bytes.size * 2)
	bytes.forEachIndexed { index, byte ->
		val value = byte.toInt() and BYTE_MASK
		encoded[index * 2] = HEX[value ushr 4]
		encoded[index * 2 + 1] = HEX[value and 0x0f]
	}
	return String(encoded)
}

internal enum class TrackingDiagnosticScopeSequence {
	EVENT_01,
	EVENT_02,
	EVENT_03,
	EVENT_04,
	EVENT_05,
	EVENT_06,
	EVENT_07,
	EVENT_08,
	EVENT_09,
	EVENT_10,
	EVENT_11,
	EVENT_12,
	EVENT_13,
	EVENT_14,
	EVENT_15,
	EVENT_16,
	;

	companion object {
		fun fromEventCount(eventCount: Int): TrackingDiagnosticScopeSequence {
			require(eventCount in 1..entries.size) { "Scope event count is outside its bound" }
			return entries[eventCount - 1]
		}
	}
}

internal class TrackingDiagnosticCoarseTimeBucket private constructor(
	internal val epochQuarterHour: Long,
) {
	internal val wireValue: String
		get() = epochQuarterHour.toString()

	companion object {
		internal const val BUCKET_MILLISECONDS = 15L * 60L * 1_000L

		fun fromEpochMilliseconds(
			epochMilliseconds: Long,
		): TrackingDiagnosticCoarseTimeBucket = TrackingDiagnosticCoarseTimeBucket(
			epochMilliseconds.coerceAtLeast(0L) / BUCKET_MILLISECONDS,
		)
	}
}

internal enum class TrackingDiagnosticSeverity {
	INFO,
	WARNING,
	ERROR,
}

private val BASE_TRACKING_DIAGNOSTIC_FIELDS = listOf(
	TrackingDiagnosticField.SOURCE,
	TrackingDiagnosticField.PURPOSE,
	TrackingDiagnosticField.PIPELINE_STAGE,
	TrackingDiagnosticField.OPERATION,
	TrackingDiagnosticField.RESULT,
	TrackingDiagnosticField.REASON,
	TrackingDiagnosticField.LIFECYCLE,
	TrackingDiagnosticField.COARSE_TIME_BUCKET,
	TrackingDiagnosticField.SCOPE_DURATION_BUCKET,
)

internal enum class TrackingDiagnosticSerializedSchema(
	internal val fields: List<TrackingDiagnosticField>,
) {
	UNMETERED(BASE_TRACKING_DIAGNOSTIC_FIELDS),
	ENQUEUE(
		BASE_TRACKING_DIAGNOSTIC_FIELDS + listOf(
			TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET,
			TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET,
		),
	),
	DRAIN(
		BASE_TRACKING_DIAGNOSTIC_FIELDS + listOf(
			TrackingDiagnosticField.DRAINED_ENVELOPE_COUNT_BUCKET,
			TrackingDiagnosticField.REMAINING_ENVELOPE_BACKLOG_BUCKET,
		),
	),
	WRITE_BATCH(
		BASE_TRACKING_DIAGNOSTIC_FIELDS +
			TrackingDiagnosticField.PERSISTED_ENVELOPE_COUNT_BUCKET,
	),
	;
}

internal class EncodedTrackingDiagnosticEvent private constructor(
	internal val severity: TrackingDiagnosticSeverity,
	internal val schema: TrackingDiagnosticSerializedSchema,
	private val values: List<String>,
) {
	init {
		require(values.size == schema.fields.size)
	}

	internal val serializedFields: List<Pair<TrackingDiagnosticField, String>>
		get() = schema.fields.zip(values)

	internal fun value(field: TrackingDiagnosticField): String {
		val index = schema.fields.indexOf(field)
		check(index >= 0) { "${field.wireName} is not present in ${schema.name}" }
		return values[index]
	}

	companion object {
		fun from(event: RecordedTrackingDiagnosticEvent): EncodedTrackingDiagnosticEvent {
			require(
				TrackingDiagnosticPrivacyValidator.validate(event) is
					TrackingDiagnosticPrivacyValidation.Allowed,
			) {
				"Tracking diagnostic event does not match its closed metric schema"
			}
			val commonValues = listOf(
				event.source.name,
				event.purpose.name,
				event.pipelineStage.name,
				event.operation.name,
				event.result.name,
				event.reason.stableName,
				event.lifecycle.name,
				event.coarseTimeBucket.wireValue,
				event.scopeDurationBucket.name,
			)
			val (schema, metricValues) = when (event) {
				is EnqueueRecordedTrackingDiagnosticEvent ->
					TrackingDiagnosticSerializedSchema.ENQUEUE to listOf(
						event.encodedEnvelopeSizeBucket.name,
						event.queueBacklogBucket.name,
					)
				is DrainRecordedTrackingDiagnosticEvent ->
					TrackingDiagnosticSerializedSchema.DRAIN to listOf(
						event.drainedEnvelopeCountBucket.name,
						event.remainingEnvelopeBacklogBucket.name,
					)
				is WriteRecordedTrackingDiagnosticEvent ->
					TrackingDiagnosticSerializedSchema.WRITE_BATCH to listOf(
						event.persistedEnvelopeCountBucket.name,
					)
				is UnmeteredRecordedTrackingDiagnosticEvent ->
					TrackingDiagnosticSerializedSchema.UNMETERED to emptyList()
			}
			return EncodedTrackingDiagnosticEvent(
				severity = event.result.toSeverity(),
				schema = schema,
				values = commonValues + metricValues,
			)
		}
	}
}

private fun TrackingDiagnosticResult.toSeverity(): TrackingDiagnosticSeverity = when (this) {
	TrackingDiagnosticResult.RETRYABLE_FAILURE,
	TrackingDiagnosticResult.PERMANENT_FAILURE,
	-> TrackingDiagnosticSeverity.ERROR
	TrackingDiagnosticResult.BLOCKED,
	TrackingDiagnosticResult.REJECTED,
	-> TrackingDiagnosticSeverity.WARNING
	else -> TrackingDiagnosticSeverity.INFO
}
