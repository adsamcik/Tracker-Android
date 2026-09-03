package com.adsamcik.tracker.shared.base.database.data

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

class PressureFactRevisionIntegrityTest {
	@Test
	@Suppress("LongMethod")
	fun `canonical checksum rejects every independently mutable retained fact field`() {
		val binding = binding()
		val unsigned = fact()
		val signed = unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding),
		)
		PressureFactRevisionIntegrity.hasValidEffectChecksum(signed, binding) shouldBe true

		val mutations: List<NamedFactMutation> = listOf(
			NamedFactMutation("logical fact id") { it.copy(logicalFactId = "pressure-session-facts:other") },
			NamedFactMutation("semantic revision") { it.copy(semanticRevision = 2L) },
			NamedFactMutation("mutation id") { it.copy(mutationId = "pressure-session-facts:event-1:other") },
			NamedFactMutation("source event id") { it.copy(sourceEventId = "event-2") },
			NamedFactMutation("source admission ordinal") { it.copy(sourceAdmissionOrdinal = 2L) },
			NamedFactMutation("wall interval") {
				it.copy(
					intervalStartTimeMs = it.intervalStartTimeMs + 1L,
					intervalEndTimeMs = it.intervalEndTimeMs + 1L,
					appliedAtMs = it.appliedAtMs + 1L,
				)
			},
			NamedFactMutation("elapsed window") {
				it.copy(
					windowStartElapsedRealtimeNanos = it.windowStartElapsedRealtimeNanos + 1L,
					windowEndElapsedRealtimeNanos = it.windowEndElapsedRealtimeNanos + 1L,
				)
			},
			NamedFactMutation("clock domain") { it.copy(clockDomainId = "boot-2") },
			NamedFactMutation("wall uncertainty") { it.copy(wallTimeUncertaintyMs = 2L) },
			NamedFactMutation("sample count") {
				it.copy(sampleCount = 5, lastProviderSequence = it.lastProviderSequence + 1L)
			},
			NamedFactMutation("mean") { it.copy(meanHectopascals = 1_001.6) },
			NamedFactMutation("sum squared deviations") { it.copy(sumSquaredDeviations = 6.0) },
			NamedFactMutation("minimum") { it.copy(minimumHectopascals = 999.5f) },
			NamedFactMutation("maximum") { it.copy(maximumHectopascals = 1_003.5f) },
			NamedFactMutation("provider sequence range") {
				it.copy(
					firstProviderSequence = it.firstProviderSequence + 10L,
					lastProviderSequence = it.lastProviderSequence + 10L,
				)
			},
			NamedFactMutation("first pressure") { it.copy(firstHectopascals = 1_000.25f) },
			NamedFactMutation("last pressure") { it.copy(lastHectopascals = 1_002.75f) },
			NamedFactMutation("slope") { it.copy(slopeHectopascalsPerSecond = 21.0) },
			NamedFactMutation("r squared") { it.copy(rSquared = 0.9) },
			NamedFactMutation("sensor accuracy") {
				it.copy(sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_MEDIUM)
			},
			NamedFactMutation("sample period") {
				it.copy(
					effectiveSamplePeriodMicros = 60_000,
					targetWindowDurationNanos = 180_000_000L,
					expectedSampleCount = 3,
				)
			},
			NamedFactMutation("maximum report latency") {
				it.copy(effectiveMaximumReportLatencyMicros = 200_001)
			},
			NamedFactMutation("target window") { it.copy(targetWindowDurationNanos = 180_000_000L) },
			NamedFactMutation("expected sample count") {
				it.copy(
					intervalEndTimeMs = it.intervalEndTimeMs + 50L,
					windowEndElapsedRealtimeNanos = it.windowEndElapsedRealtimeNanos + 50_000_000L,
					sampleCount = 5,
					lastProviderSequence = it.lastProviderSequence + 1L,
					targetWindowDurationNanos = 250_000_000L,
					expectedSampleCount = 5,
					appliedAtMs = it.appliedAtMs + 50L,
				)
			},
			NamedFactMutation("maximum inter-sample gap") {
				it.copy(maximumInterSampleGapNanos = 40_000_000L)
			},
			NamedFactMutation("closure and qualification") {
				it.copy(
					closureKind = PressureFactRevisionEntity.CLOSURE_SOURCE_BOUNDARY,
					qualification = PressureFactRevisionEntity.QUALIFICATION_PARTIAL,
				)
			},
			NamedFactMutation("quality flags") { it.copy(sourceQualityFlags = 2L) },
			NamedFactMutation("quality confidence") { it.copy(sourceQualityConfidence = null) },
			NamedFactMutation("logical tracking id") { it.copy(logicalTrackingId = "logical-2") },
			NamedFactMutation("service run id") { it.copy(serviceRunId = "run-2") },
			NamedFactMutation("manifest revision") { it.copy(manifestRevision = 2L) },
			NamedFactMutation("source policy revision") { it.copy(sourcePolicyRevision = 2L) },
			NamedFactMutation("capture consent epoch") { it.copy(captureConsentEpoch = 2L) },
			NamedFactMutation("collected data epoch") { it.copy(collectedDataEpoch = 2L) },
		)
		mutations.forEach { mutation ->
			withClue(mutation.name) {
				PressureFactRevisionIntegrity.hasValidEffectChecksum(
					mutation.mutate(signed),
					binding,
				) shouldBe false
			}
		}
	}

	@Test
	fun `canonical checksum rejects a different valid immutable destination writer binding`() {
		val binding = binding()
		val unsigned = fact()
		val signed = unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, binding),
		)
		val legacyBinding = binding.copy(
			writerOwner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
			writerOwnerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			writerProjectionId = null,
			writerProjectionVersion = null,
			writerBindingGeneration = null,
		)

		PressureFactRevisionIntegrity.hasValidEffectChecksum(signed, legacyBinding) shouldBe false
	}

	private fun binding() = SessionManifestSourceEntity(
		logicalTrackingId = "logical-1",
		manifestRevision = 1L,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 2,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	@Suppress("LongMethod")
	private fun fact() = PressureFactRevisionEntity(
		logicalFactId = "pressure-session-facts:event-1",
		semanticRevision = 1L,
		mutationId = "pressure-session-facts:event-1:1",
		sourceEventId = "event-1",
		sourceAdmissionOrdinal = 1L,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		windowStartElapsedRealtimeNanos = 1_000_000_000L,
		windowEndElapsedRealtimeNanos = 1_150_000_000L,
		clockDomainId = "boot-1",
		wallTimeUncertaintyMs = 1L,
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstProviderSequence = 1L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = 1L,
		sourceQualityConfidence = 0.75f,
		logicalTrackingId = "logical-1",
		serviceRunId = "run-1",
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		effectChecksum = "pending",
		appliedAtMs = 1_150L,
	)

	private data class NamedFactMutation(
		val name: String,
		val mutate: (PressureFactRevisionEntity) -> PressureFactRevisionEntity,
	)
}
