package com.adsamcik.tracker.diagnostics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class TrackingDiagnosticPrivacyValidatorTest {
	@Test
	fun `fixed event implementation has no raw or extensible payload field`() {
		val event = TrackingDiagnosticEvent.create(
			source = TrackingDiagnosticSource.CELL,
			purpose = TrackingDiagnosticPurpose.CONTROL_AUTOSTART,
			pipelineStage = TrackingDiagnosticPipelineStage.ACQUISITION,
			operation = TrackingDiagnosticOperation.RECEIVE,
			result = TrackingDiagnosticResult.NO_EFFECT,
			reason = TrackingDiagnosticNoEffectReason.EXACT_REPLAY,
		)
		val instanceFields = event.javaClass.declaredFields
			.filterNot { field -> Modifier.isStatic(field.modifiers) }

		instanceFields.map { field -> field.name }.toSet() shouldBe setOf(
			"source",
			"purpose",
			"pipelineStage",
			"operation",
			"result",
			"reason",
			"correlationToken",
			"countBucket",
			"durationBucket",
			"backlogBucket",
			"sizeBucket",
		)
		instanceFields.filter { field ->
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
			"bssid" to TrackingDiagnosticPrivacyRejectionReason.RADIO_IDENTIFIERS,
			"selectedOpaqueId" to TrackingDiagnosticPrivacyRejectionReason.OPAQUE_SELECTIONS,
			"contentUri" to TrackingDiagnosticPrivacyRejectionReason.FILE_REFERENCES,
			"sha256" to TrackingDiagnosticPrivacyRejectionReason.CHECKSUMS,
			"androidId" to TrackingDiagnosticPrivacyRejectionReason.STABLE_IDENTIFIERS,
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
	fun `recorder exposes no network upload storage or observer API`() {
		val forbiddenApiTerms = setOf(
			"network",
			"upload",
			"http",
			"send",
			"share",
			"persist",
			"store",
			"subscribe",
			"listener",
			"observer",
		)
		val declaredNames = TrackingDiagnosticRecorder::class.java.declaredMethods
			.map { method -> method.name.lowercase() }

		declaredNames.filter { name ->
			forbiddenApiTerms.any(name::contains)
		}.shouldBeEmpty()
	}
}
