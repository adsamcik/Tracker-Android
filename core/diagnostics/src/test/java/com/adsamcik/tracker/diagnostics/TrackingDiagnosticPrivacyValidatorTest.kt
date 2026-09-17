package com.adsamcik.tracker.diagnostics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
		TrackingDiagnosticPrivacyValidator.validateAdapterSchema(
			TrackingDiagnosticPrivacyValidator.allowedFields.map { field -> field.wireName },
		) shouldBe TrackingDiagnosticPrivacyValidation.Allowed
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
