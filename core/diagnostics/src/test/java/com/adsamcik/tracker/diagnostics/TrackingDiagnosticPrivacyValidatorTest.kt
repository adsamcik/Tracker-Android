package com.adsamcik.tracker.diagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class TrackingDiagnosticPrivacyValidatorTest {
	@Test
	fun `request implementations have no raw or extensible payload field`() {
		val requests = listOf(
			TrackingDiagnosticEvents.unmetered(
				source = TrackingDiagnosticSource.CELL,
				purpose = TrackingDiagnosticPurpose.CONTROL_AUTOSTART,
				pipelineStage = TrackingDiagnosticPipelineStage.ACQUISITION,
				operation = TrackingDiagnosticOperation.RECEIVE,
				result = TrackingDiagnosticResult.NO_EFFECT,
				reason = TrackingDiagnosticNoEffectReason.EXACT_REPLAY,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
			),
			TrackingDiagnosticEvents.enqueue(
				source = TrackingDiagnosticSource.CELL,
				purpose = TrackingDiagnosticPurpose.CONTROL_AUTOSTART,
				pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
				result = TrackingDiagnosticResult.DEFERRED,
				reason = TrackingDiagnosticDeferredReason.BACKLOG_LIMIT,
				lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
				encodedEnvelopeBytes = 1L,
				queuedEnvelopeBacklog = 1L,
			),
			TrackingDiagnosticEvents.drain(
				source = TrackingDiagnosticSource.CELL,
				purpose = TrackingDiagnosticPurpose.CONTROL_AUTOSTART,
				pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticSuccessReason.COMPLETED,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
				drainedEnvelopeCount = 1L,
				remainingEnvelopeBacklog = 0L,
			),
			TrackingDiagnosticEvents.writeBatch(
				source = TrackingDiagnosticSource.CELL,
				purpose = TrackingDiagnosticPurpose.CONTROL_AUTOSTART,
				pipelineStage = TrackingDiagnosticPipelineStage.PERSISTENCE,
				result = TrackingDiagnosticResult.SUCCEEDED,
				reason = TrackingDiagnosticSuccessReason.COMPLETED,
				lifecycle = TrackingDiagnosticEventLifecycle.TERMINAL,
				persistedEnvelopeCount = 1L,
			),
		)

		requests.flatMap { request ->
			request.javaClass.declaredFields.filterNot { field ->
				Modifier.isStatic(field.modifiers)
			}
		}.filter { field ->
			field.type == String::class.java ||
				field.type == ByteArray::class.java ||
				Number::class.java.isAssignableFrom(field.type) ||
				Map::class.java.isAssignableFrom(field.type) ||
				Collection::class.java.isAssignableFrom(field.type)
		}.shouldBeEmpty()
	}

	@Test
	fun `complete adapter allowlist is accepted`() {
		val allowedWireNames =
			TrackingDiagnosticPrivacyValidator.allowedFields.map { field -> field.wireName }.toSet()

		allowedWireNames shouldBe setOf(
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
		)
		TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
			TrackingDiagnosticPrivacyValidator.allowedFields.map { field -> field.wireName },
		) shouldBe TrackingDiagnosticPrivacyValidation.Allowed
		TrackingDiagnosticPrivacyValidator.validateStoredSchema(
			TrackingDiagnosticPrivacyValidator.allowedStoredFields.map { field -> field.wireName },
		) shouldBe TrackingDiagnosticPrivacyValidation.Allowed
		TrackingDiagnosticPrivacyValidator.allowedStoredFields shouldBe
			TrackingDiagnosticPrivacyValidator.allowedFields +
				TrackingDiagnosticField.OCCURRENCE_COUNT_BUCKET
		TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
			listOf(TrackingDiagnosticField.OCCURRENCE_COUNT_BUCKET.wireName),
		) shouldBe TrackingDiagnosticPrivacyValidation.Rejected(
			TrackingDiagnosticPrivacyRejectionReason.UNKNOWN_FIELD,
		)
	}

	@Test
	fun `encoded event omits in-memory correlation and caller supplied raw values`() {
		val request = TrackingDiagnosticEvents.enqueue(
			source = TrackingDiagnosticSource.WIFI,
			purpose = TrackingDiagnosticPurpose.AMBIENT_PRODUCT,
			pipelineStage = TrackingDiagnosticPipelineStage.DURABLE_INGRESS,
			result = TrackingDiagnosticResult.DEFERRED,
			reason = TrackingDiagnosticDeferredReason.BACKLOG_LIMIT,
			lifecycle = TrackingDiagnosticEventLifecycle.PROGRESS,
			encodedEnvelopeBytes = 12_345L,
			queuedEnvelopeBacklog = 77L,
		)
		val encoded = EncodedTrackingDiagnosticEvent.from(
			request.toRecordedEvent(
				operationScope = TrackingDiagnosticScopeOpaque.fixedForTest(42L),
				scopeSequence = TrackingDiagnosticScopeSequence.EVENT_04,
				coarseTimeBucket =
					TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(
						epochMilliseconds = 1_789_630_524_522L,
					),
				scopeDurationBucket = TrackingDiagnosticDurationBucket.ONE_TO_FOUR_SECONDS,
			),
		)
		val fields = encoded.serializedFields.toMap()

		fields.keys.none { field ->
			field.wireName == "operation_scope" || field.wireName == "scope_sequence"
		} shouldBe true
		fields[TrackingDiagnosticField.COARSE_TIME_BUCKET] shouldBe
			(1_789_630_524_522L /
				TrackingDiagnosticCoarseTimeBucket.BUCKET_MILLISECONDS).toString()
		fields[TrackingDiagnosticField.ENCODED_ENVELOPE_SIZE_BUCKET] shouldBe
			"UP_TO_SIXTEEN_KIBIBYTES"
		fields[TrackingDiagnosticField.QUEUE_BACKLOG_BUCKET] shouldBe
			"THIRTY_THREE_TO_ONE_HUNDRED_TWENTY_EIGHT"
		("12345" in fields.values) shouldBe false
		("77" in fields.values) shouldBe false
	}

	@Test
	fun `encoder rejects an event whose empty metric set violates its operation`() {
		val request = mockk<TrackingDiagnosticEventRequest>()
		every { request.source } returns TrackingDiagnosticSource.WIFI
		every { request.purpose } returns TrackingDiagnosticPurpose.SESSION_CAPTURE
		every { request.pipelineStage } returns TrackingDiagnosticPipelineStage.DURABLE_INGRESS
		every { request.operation } returns TrackingDiagnosticOperation.ENQUEUE
		every { request.result } returns TrackingDiagnosticResult.SUCCEEDED
		every { request.reason } returns TrackingDiagnosticSuccessReason.COMPLETED
		every { request.lifecycle } returns TrackingDiagnosticEventLifecycle.PROGRESS
		val invalid = UnmeteredRecordedTrackingDiagnosticEvent(
			request = request,
			operationScope = TrackingDiagnosticScopeOpaque.fixedForTest(1L),
			scopeSequence = TrackingDiagnosticScopeSequence.EVENT_01,
			coarseTimeBucket = TrackingDiagnosticCoarseTimeBucket.fromEpochMilliseconds(0L),
			scopeDurationBucket = TrackingDiagnosticDurationBucket.UNDER_TEN_MILLISECONDS,
		)

		TrackingDiagnosticPrivacyValidator.validate(invalid) shouldBe
			TrackingDiagnosticPrivacyValidation.Rejected(
				TrackingDiagnosticPrivacyRejectionReason.METRIC_OPERATION_MISMATCH,
			)
		shouldThrow<IllegalArgumentException> {
			EncodedTrackingDiagnosticEvent.from(invalid)
		}
	}

	@Test
	fun `forbidden field categories are explicitly rejected`() {
		val examples = mapOf(
			"latitudeE7" to TrackingDiagnosticPrivacyRejectionReason.COORDINATES,
			"pressureHpa" to TrackingDiagnosticPrivacyRejectionReason.SENSOR_VALUES,
			"stepCount" to TrackingDiagnosticPrivacyRejectionReason.SENSOR_VALUES,
			"rssi" to TrackingDiagnosticPrivacyRejectionReason.SENSOR_VALUES,
			"bssid" to TrackingDiagnosticPrivacyRejectionReason.RADIO_IDENTIFIERS,
			"selectedOpaqueId" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"sourceEventId" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"logicalTrackingId" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"serviceRunId" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"operationScope" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"scopeSequence" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"contentUri" to TrackingDiagnosticPrivacyRejectionReason.FILE_REFERENCES,
			"sha256" to TrackingDiagnosticPrivacyRejectionReason.CHECKSUMS,
			"androidId" to TrackingDiagnosticPrivacyRejectionReason.STABLE_IDENTIFIERS,
			"exceptionMessage" to TrackingDiagnosticPrivacyRejectionReason.EXCEPTION_DETAILS,
			"stackTrace" to TrackingDiagnosticPrivacyRejectionReason.EXCEPTION_DETAILS,
			"cause" to TrackingDiagnosticPrivacyRejectionReason.EXCEPTION_DETAILS,
			"providerPayload" to TrackingDiagnosticPrivacyRejectionReason.PROVIDER_PAYLOADS,
		)

		examples.forEach { (fieldName, expectedReason) ->
			TrackingDiagnosticPrivacyValidator.validateAdapterSchema(listOf(fieldName)) shouldBe
				TrackingDiagnosticPrivacyValidation.Rejected(expectedReason)
		}
	}

	@Test
	fun `unknown free-form extension is rejected instead of becoming metadata`() {
		TrackingDiagnosticPrivacyValidator.validateAdapterSchema(listOf("debugLabel")) shouldBe
			TrackingDiagnosticPrivacyValidation.Rejected(
				TrackingDiagnosticPrivacyRejectionReason.UNKNOWN_FIELD,
			)
	}

	@Test
	fun `raw values collapse into bounded buckets only inside the diagnostics module`() {
		TrackingDiagnosticCountBucket.fromCount(64L) shouldBe
			TrackingDiagnosticCountBucket.SEVENTEEN_TO_SIXTY_FOUR
		TrackingDiagnosticCountBucket.fromCount(Long.MAX_VALUE) shouldBe
			TrackingDiagnosticCountBucket.SIXTY_FIVE_OR_MORE
		TrackingDiagnosticDurationBucket.fromMilliseconds(30_000L) shouldBe
			TrackingDiagnosticDurationBucket.THIRTY_SECONDS_OR_MORE
		TrackingDiagnosticBacklogBucket.fromItemCount(129L) shouldBe
			TrackingDiagnosticBacklogBucket.ONE_HUNDRED_TWENTY_NINE_OR_MORE
		TrackingDiagnosticSizeBucket.fromBytes(65_537L) shouldBe
			TrackingDiagnosticSizeBucket.OVER_SIXTY_FOUR_KIBIBYTES
	}
}
