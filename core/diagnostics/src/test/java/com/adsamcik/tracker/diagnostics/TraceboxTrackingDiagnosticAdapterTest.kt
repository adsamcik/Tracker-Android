package com.adsamcik.tracker.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TraceboxTrackingDiagnosticAdapterTest {
	@Test
	fun `adapter serializes exact allowlisted keys and bucket values`() {
		val writes = mutableListOf<EncodedTrackingDiagnosticEvent>()
		val adapter = TraceboxTrackingDiagnosticAdapter(
			writer = { event -> writes += event },
			failureReporter = { error("failure reporter must not run") },
		)

		adapter.append(recordedEnqueue()) shouldBe
			TrackingDiagnosticStoreResult.RECORDED_LOCALLY

		val encoded = writes.single()
		encoded.schema shouldBe TrackingDiagnosticSerializedSchema.ENQUEUE
		encoded.serializedFields.map { (field, _) -> field.wireName } shouldBe listOf(
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
		)
		encoded.serializedFields.toMap() shouldBe mapOf(
			TrackingDiagnosticField.SOURCE to "WIFI",
			TrackingDiagnosticField.PURPOSE to "AMBIENT_PRODUCT",
			TrackingDiagnosticField.PIPELINE_STAGE to "DURABLE_INGRESS",
			TrackingDiagnosticField.OPERATION to "ENQUEUE",
			TrackingDiagnosticField.RESULT to "DEFERRED",
			TrackingDiagnosticField.REASON to "BACKLOG_LIMIT",
			TrackingDiagnosticField.LIFECYCLE to "PROGRESS",
			TrackingDiagnosticField.OPERATION_SCOPE to
				TrackingDiagnosticScopeOpaque.fixedForTest(11L).wireValue,
			TrackingDiagnosticField.SCOPE_SEQUENCE to "EVENT_03",
			TrackingDiagnosticField.COARSE_LOCAL_TIMESTAMP to "2026-09-17T09:30+02:00",
			TrackingDiagnosticField.SCOPE_DURATION_BUCKET to "ONE_TO_FOUR_SECONDS",
			TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET to
				"UP_TO_FOUR_KIBIBYTES",
			TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET to "NINE_TO_THIRTY_TWO",
		)
	}

	@Test
	fun `every event shape has only its exact metric keys`() {
		val cases = listOf(
			recordedUnmetered() to TrackingDiagnosticSerializedSchema.UNMETERED,
			recordedEnqueue() to TrackingDiagnosticSerializedSchema.ENQUEUE,
			recordedDrain() to TrackingDiagnosticSerializedSchema.DRAIN,
			recordedWrite() to TrackingDiagnosticSerializedSchema.WRITE_BATCH,
		)

		cases.forEach { (event, expectedSchema) ->
			val encoded = EncodedTrackingDiagnosticEvent.from(event)

			encoded.schema shouldBe expectedSchema
			encoded.serializedFields.map { (field, _) -> field } shouldBe expectedSchema.fields
			TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
				encoded.serializedFields.map { (field, _) -> field.wireName },
			) shouldBe TrackingDiagnosticPrivacyValidation.Allowed
		}
	}

	@Test
	fun `adapter failure is reported once and never becomes success`() {
		var failureReports = 0
		val adapter = TraceboxTrackingDiagnosticAdapter(
			writer = { error("Tracebox unavailable") },
			failureReporter = { failureReports += 1 },
		)

		adapter.append(recordedUnmetered()) shouldBe TrackingDiagnosticStoreResult.FAILED
		failureReports shouldBe 1
	}

	@Test
	fun `failure reporter failure does not escape or recurse`() {
		var failureReports = 0
		val adapter = TraceboxTrackingDiagnosticAdapter(
			writer = { error("Tracebox unavailable") },
			failureReporter = {
				failureReports += 1
				error("nontracking diagnostics unavailable")
			},
		)

		adapter.append(recordedUnmetered()) shouldBe TrackingDiagnosticStoreResult.FAILED
		failureReports shouldBe 1
	}

	@Test
	fun `failure reporting cannot recursively report another adapter failure`() {
		var failureReports = 0
		lateinit var adapter: TraceboxTrackingDiagnosticAdapter
		adapter = TraceboxTrackingDiagnosticAdapter(
			writer = { error("Tracebox unavailable") },
			failureReporter = {
				failureReports += 1
				adapter.append(recordedUnmetered())
			},
		)

		adapter.append(recordedUnmetered()) shouldBe TrackingDiagnosticStoreResult.FAILED
		failureReports shouldBe 1
	}

	@Test
	fun `severity is derived only from the closed result enum`() {
		val expectations = mapOf(
			TrackingDiagnosticResult.SUCCEEDED to TrackingDiagnosticSeverity.INFO,
			TrackingDiagnosticResult.NO_EFFECT to TrackingDiagnosticSeverity.INFO,
			TrackingDiagnosticResult.DEFERRED to TrackingDiagnosticSeverity.INFO,
			TrackingDiagnosticResult.CANCELLED to TrackingDiagnosticSeverity.INFO,
			TrackingDiagnosticResult.BLOCKED to TrackingDiagnosticSeverity.WARNING,
			TrackingDiagnosticResult.REJECTED to TrackingDiagnosticSeverity.WARNING,
			TrackingDiagnosticResult.RETRYABLE_FAILURE to TrackingDiagnosticSeverity.ERROR,
			TrackingDiagnosticResult.PERMANENT_FAILURE to TrackingDiagnosticSeverity.ERROR,
		)

		expectations.forEach { (result, expectedSeverity) ->
			EncodedTrackingDiagnosticEvent.from(
				recordedUnmetered(
					result = result,
					reason = reasonFor(result),
				),
			).severity shouldBe expectedSeverity
		}
	}

	private fun recordedEnqueue(): RecordedTrackingDiagnosticEvent =
		TrackingDiagnosticEvents.enqueue(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT,
			pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
			result = TrackingDiagnosticResult.DEFERRED,
			reason = TrackingDiagnosticDeferredReason.BACKLOG_LIMIT,
			lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
			encodedEnvelopeBytes = 2_048L,
			queuedEnvelopeBacklog = 9L,
		).recorded(
			scopeSequence = TrackingDiagnosticScopeSequence.EVENT_03,
		)

	private fun recordedDrain(): RecordedTrackingDiagnosticEvent =
		TrackingDiagnosticEvents.drain(
			source = TrackingDiagnosticSource.CELL,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
			result = TrackingDiagnosticResult.SUCCEEDED,
			reason = TrackingDiagnosticSuccessReason.COMPLETED,
			lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
			drainedEnvelopeCount = 17L,
			remainingEnvelopeBacklog = 2L,
		).recorded()

	private fun recordedWrite(): RecordedTrackingDiagnosticEvent =
		TrackingDiagnosticEvents.writeBatch(
			source = TrackingDiagnosticSource.PRESSURE,
			purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
			pipelineStage = TrackingDiagnosticPipelineStage.PERSISTENCE,
			result = TrackingDiagnosticResult.SUCCEEDED,
			reason = TrackingDiagnosticSuccessReason.COMPLETED,
			lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
			persistedEnvelopeCount = 65L,
		).recorded()

	private fun recordedUnmetered(
		result: TrackingDiagnosticResult = TrackingDiagnosticResult.SUCCEEDED,
		reason: TrackingDiagnosticReason = TrackingDiagnosticSuccessReason.COMPLETED,
	): RecordedTrackingDiagnosticEvent = TrackingDiagnosticEvents.unmetered(
		source = TrackingDiagnosticSource.STEPS,
		purpose = TrackingDiagnosticPurpose.SESSION_CAPTURE,
		pipelineStage = TrackingDiagnosticPipelineStage.LIFECYCLE,
		operation = TrackingDiagnosticOperation.START,
		result = result,
		reason = reason,
		lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
	).recorded()

	private fun TrackingDiagnosticEventRequest.recorded(
		scopeSequence: TrackingDiagnosticScopeSequence = TrackingDiagnosticScopeSequence.EVENT_01,
	): RecordedTrackingDiagnosticEvent = toRecordedEvent(
		operationScope = TrackingDiagnosticScopeOpaque.fixedForTest(11L),
		scopeSequence = scopeSequence,
		coarseLocalTimestamp = TrackingDiagnosticCoarseLocalTimestamp.fromEpochMilliseconds(
			epochMilliseconds = 1_789_630_524_522L,
			zoneId = java.time.ZoneOffset.ofHours(2),
		),
		scopeDurationBucket = TrackingDiagnosticDurationBucket.ONE_TO_FOUR_SECONDS,
	)

	private fun reasonFor(result: TrackingDiagnosticResult): TrackingDiagnosticReason = when (result) {
		TrackingDiagnosticResult.SUCCEEDED -> TrackingDiagnosticSuccessReason.COMPLETED
		TrackingDiagnosticResult.NO_EFFECT -> TrackingDiagnosticNoEffectReason.ALREADY_APPLIED
		TrackingDiagnosticResult.BLOCKED -> TrackingDiagnosticBlockedReason.POLICY_DISABLED
		TrackingDiagnosticResult.DEFERRED -> TrackingDiagnosticDeferredReason.DEPENDENCY_NOT_READY
		TrackingDiagnosticResult.REJECTED -> TrackingDiagnosticRejectedReason.STALE_CALLBACK
		TrackingDiagnosticResult.RETRYABLE_FAILURE,
		TrackingDiagnosticResult.PERMANENT_FAILURE,
		-> TrackingDiagnosticFailureReason.PROVIDER_FAILURE
		TrackingDiagnosticResult.CANCELLED -> TrackingDiagnosticCancellationReason.LIFECYCLE_STOP
	}
}
